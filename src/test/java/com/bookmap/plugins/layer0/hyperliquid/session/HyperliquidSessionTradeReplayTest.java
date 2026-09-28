package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TaskFailures;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/**
 * Session behavior for the recent trades Hyperliquid replays on every trades subscription: trades
 * older than the first published book are never published, and after a reconnect only trades newer
 * than the last published one are.
 */
public class HyperliquidSessionTradeReplayTest {

  @Test
  public void replayBufferedBeforeActivationDropsTradesOlderThanTheBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");

    fixture.trades(trade(1L, 1L), trade(2L, 2L), trade(10L, 3L));
    fixture.bookAndAcks(10L);

    assertEquals("[3]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void replayArrivingAfterActivationDropsTradesOlderThanTheBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);

    fixture.trades(trade(1L, 1L), trade(2L, 2L), trade(10L, 3L), trade(11L, 4L));

    assertEquals("[3, 4]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void reconnectPublishesOnlyTradesNewerThanTheLastPublishedOne() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L), trade(12L, 2L));
    fixture.clock.now += 11L * 60_000L;

    fixture.reconnect();
    fixture.completeAllSends();
    fixture.trades(trade(11L, 1L), trade(12L, 2L), trade(13L, 3L));
    fixture.bookAndAcks(14L);

    assertEquals("[1, 2, 3]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void sameTimeIsPublishedForANewTradeIdOnly() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L));
    assertEquals(0, fixture.diagnostics("discarded").size());

    fixture.trades(trade(11L, 1L), trade(11L, 2L));

    assertEquals("[1, 2]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 1 trades").size());
  }

  @Test
  public void resubscribingNeverPublishesTheEarlierSubscriptionsTradesAgain() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L));
    fixture.unsubscribe("BTC");

    fixture.subscribe("BTC");
    fixture.trades(trade(11L, 1L), trade(12L, 2L));
    fixture.bookAndAcks(13L);

    assertEquals("[1]", fixture.tids().toString());
  }

  @Test
  public void pendingSubscriptionAnchorsOnTheFirstBookOfTheNewConnection() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.book("BTC", 10L);
    fixture.drain();

    fixture.reconnect();
    fixture.completeAllSends();
    fixture.trades(trade(11L, 1L), trade(20L, 2L));
    fixture.acks("BTC");
    assertFalse(fixture.sink.events().contains("instrument-added:BTC"));

    fixture.book("BTC", 20L);
    fixture.drain();

    assertTrue(fixture.sink.events().contains("instrument-added:BTC"));
    assertEquals("[2]", fixture.tids().toString());
  }

  /** One BTC buy fill whose price, 100 + tid, identifies it in the published trades. */
  private static String trade(long time, long tid) {
    return "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\""
        + (100L + tid)
        + ".000\",\"sz\":\"1\",\"time\":"
        + time
        + ",\"tid\":"
        + tid
        + "}";
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final Clock clock = new Clock();
    private final Scheduler scheduler = new Scheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                // overflow is not exercised here
              }
            },
            TaskFailures::rethrow);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            new AssetContextConnectorFactory() {
              @Override
              public HyperliquidConnector create() {
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
            new MetadataRequestFactory() {
              @Override
              public MetadataRequest create(
                  SourceProfile profile, MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    dispatcher::submitControl,
                    profile.infoUri(),
                    callback);
              }
            },
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });
    private long generation = 1L;

    private void login() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC", "ETH")));
      drain();
      transport.openSocket();
      drain();
      completeAllSends();
    }

    private void subscribe(String coin) {
      session.subscribe(coin, "", "PERPETUAL");
      drain();
      completeAllSends();
    }

    private void unsubscribe(String coin) {
      session.unsubscribe(coin);
      drain();
      completeAllSends();
    }

    private void bookAndAcks(long time) {
      book("BTC", time);
      acks("BTC");
    }

    private void acks(String coin) {
      ack(coin, SubscriptionType.L2_BOOK);
      ack(coin, SubscriptionType.TRADES);
      drain();
    }

    private void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation++;
      drain();
    }

    private void completeAllSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void trades(String... fills) {
      StringBuilder frame = new StringBuilder("{\"channel\":\"trades\",\"data\":[");
      for (int index = 0; index < fills.length; index++) {
        if (index > 0) {
          frame.append(',');
        }
        frame.append(fills[index]);
      }
      session.onFrame(generation, frame.append("]}").toString());
      drain();
    }

    private void book(String coin, long time) {
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
              + coin
              + "\",\"time\":"
              + time
              + ",\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"}],[]]}}");
    }

    private void ack(String coin, SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\""
              + coin
              + "\"}}}");
    }

    /** Returns the tid of every published trade, recovered from its price of 100 + tid. */
    private List<Long> tids() {
      List<Long> tids = new ArrayList<Long>();
      for (RecordingSessionSink.Trade trade : sink.trades()) {
        tids.add(Long.valueOf(Math.round(trade.price() / 10_000d) - 100L));
      }
      return tids;
    }

    private List<String> diagnostics(String prefix) {
      List<String> matching = new ArrayList<String>();
      for (String event : sink.events()) {
        if (event.startsWith("diagnostic:" + prefix)) {
          matching.add(event);
        }
      }
      return matching;
    }

    private void drain() {
      executor.drain();
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  private static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class Scheduler implements CancellableScheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<Task>();

    private Scheduler(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis));
      tasks.add(scheduled);
      return scheduled;
    }

    private void advanceBy(long millis) {
      long target = clock.now + millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= target && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          clock.now = target;
          return;
        }
        tasks.remove(due);
        clock.now = Math.max(clock.now, due.due);
        due.task.run();
      }
    }

    private static final class Task implements Cancellable {
      private final Runnable task;
      private final long due;
      private boolean cancelled;

      private Task(Runnable task, long due) {
        this.task = task;
        this.due = due;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }
    }
  }
}
