package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
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

/** Session behavior when several trades of one transaction are published as one execution. */
public class HyperliquidSessionTradeExecutionTest {

  private static final String A =
      "0x00000000000000000000000000000000000000000000000000000000000000aa";
  private static final String B =
      "0x00000000000000000000000000000000000000000000000000000000000000bb";
  private static final String ZERO =
      "0x0000000000000000000000000000000000000000000000000000000000000000";

  @Test
  public void fillsOfOneTransactionInOneFrameFormOneExecution() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, A), fill(2, A), fill(3, A));

    assertEquals("[TF, FF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsWithoutATransactionHashStayWholeExecutions() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, ZERO), fill(2, ZERO), fill(3, null));

    assertEquals("[TT, TT, TT]", fixture.flags().toString());
  }

  @Test
  public void twoTransactionsInOneFrameFormTwoExecutions() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, A), fill(2, A), fill(3, B), fill(4, B));

    assertEquals("[TF, FT, TF, FT]", fixture.flags().toString());
  }

  @Test
  public void aDuplicateFirstFillLeavesTheNextOneAsTheStart() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    fixture.trades(fill(1, A));

    fixture.trades(fill(1, A), fill(2, A), fill(3, A));

    assertEquals("[TT, TF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsBufferedBeforeActivationArePublishedAsOneExecution() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribeBtc();
    fixture.trades(fill(1, A));
    fixture.trades(fill(2, A));
    assertEquals("[]", fixture.flags().toString());

    fixture.bookAndAcks();

    assertEquals("[TF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsBufferedDuringRecoveryArePublishedAsOneExecution() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    fixture.reconnect();
    fixture.trades(fill(1, A));
    fixture.trades(fill(2, A));
    assertEquals("[]", fixture.flags().toString());

    fixture.completeAllSends();
    fixture.bookAndAcks();

    assertEquals("[TF, FT]", fixture.flags().toString());
  }

  @Test
  public void booksAndTradesKeepTheirArrivalOrder() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    int before = fixture.sink.events().size();

    fixture.book("101.000", 2L);
    fixture.trades(fill(1, A), fill(2, A));
    fixture.book("102.000", 3L);
    fixture.drain();

    List<String> kinds = new ArrayList<String>();
    for (String event : fixture.sink.events().subList(before, fixture.sink.events().size())) {
      if (event.startsWith("depth:") || event.startsWith("trade:")) {
        kinds.add(event.substring(0, 5));
      }
    }
    // Each new best bid replaces the previous one: one removal and one addition per book.
    assertEquals("[depth, depth, trade, trade, depth, depth]", kinds.toString());
  }

  @Test
  public void fillsOfDifferentInstrumentsInOneFrameNeverShareAnExecution() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribeBtc();
    fixture.subscribe("ETH");
    fixture.bookAndAcks();
    fixture.bookAndAcks("ETH");

    fixture.trades(fill("BTC", 1, A), fill("ETH", 2, A), fill("BTC", 3, A));

    assertEquals("[TT, TT, TT]", fixture.flags().toString());
    assertEquals("[BTC, ETH, BTC]", fixture.aliases().toString());
  }

  /** One BTC buy fill; {@code hash == null} omits the field. */
  private static String fill(long tid, String hash) {
    return fill("BTC", tid, hash);
  }

  /** One buy fill for {@code coin}; {@code hash == null} omits the field. */
  private static String fill(String coin, long tid, String hash) {
    return "{\"coin\":\""
        + coin
        + "\",\"side\":\"B\",\"px\":\"101.000\",\"sz\":\"1\",\"time\":5,\"tid\":"
        + tid
        + (hash == null ? "" : ",\"hash\":\"" + hash + "\"")
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
            });
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
    private long bookTime = 1L;

    private void login() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC", "ETH")));
      drain();
      transport.openSocket();
      drain();
      completeAllSends();
    }

    private void loginAndActivateBtc() {
      login();
      subscribeBtc();
      bookAndAcks();
    }

    private void subscribeBtc() {
      subscribe("BTC");
    }

    private void subscribe(String coin) {
      session.subscribe(coin, "", "PERPETUAL");
      drain();
      completeAllSends();
    }

    private void bookAndAcks() {
      bookAndAcks("BTC");
    }

    private void bookAndAcks(String coin) {
      book(coin, "100.000", bookTime++);
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

    private void book(String price, long time) {
      book("BTC", price, time);
    }

    private void book(String coin, String price, long time) {
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
              + coin
              + "\",\"time\":"
              + time
              + ",\"levels\":[[{\"px\":\""
              + price
              + "\",\"sz\":\"1\"}],[]]}}");
    }

    private void ack(SubscriptionType type) {
      ack("BTC", type);
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

    private List<String> flags() {
      List<String> flags = new ArrayList<String>();
      for (RecordingSessionSink.Trade trade : sink.trades()) {
        flags.add((trade.executionStart() ? "T" : "F") + (trade.executionEnd() ? "T" : "F"));
      }
      return flags;
    }

    private List<String> aliases() {
      List<String> aliases = new ArrayList<String>();
      for (RecordingSessionSink.Trade trade : sink.trades()) {
        aliases.add(trade.alias());
      }
      return aliases;
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
