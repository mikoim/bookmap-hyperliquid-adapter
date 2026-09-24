package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
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
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Session behavior when the instrument list is fetched again during a session. */
public class HyperliquidSessionMetadataRefreshTest {

  private static final long INTERVAL = 1_800_000L;

  @Test
  public void firstConnectionArmsTheThirtyMinuteRefresh() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    assertEquals(2, fixture.transport.httpHandleCount());

    fixture.advance(INTERVAL);

    assertEquals(4, fixture.transport.httpHandleCount());
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void reconnectRefreshesImmediately() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");

    fixture.reconnect();

    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshedListIsRepublishedWithKnownMarkPrices() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.session.onFrame(
        1L, TestMetadata.assetContextsFrame("{\"BTC\":{\"markPx\":\"100.5\"}}"));
    fixture.drain();

    fixture.advance(INTERVAL);
    fixture.transport.completeMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    assertEquals("known:2", fixture.sink.events().get(fixture.sink.events().size() - 1));
    assertEquals("100.5", fixture.sink.lastReferencePrice("BTC"));
    assertEquals(null, fixture.sink.lastReferencePrice("NEW"));
  }

  @Test
  public void aNewlyListedInstrumentCanBeSubscribed() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.advance(INTERVAL);
    fixture.transport.completeMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    fixture.session.subscribe("NEW", "", "PERPETUAL");
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "not-found:NEW"));
    fixture.completeAllSends();
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString(),
        fixture.transport.socket().successfulSendBodies().toString().contains("\"coin\":\"NEW\""));
  }

  @Test
  public void failedRefreshKeepsTheListAndOnlyLogs() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    int publishedBefore = count(fixture.sink.events(), "known:1");

    fixture.advance(INTERVAL);
    fixture.transport.failMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "diagnostic:metadata refresh failed: no dns"));
    assertEquals(publishedBefore, count(fixture.sink.events(), "known:1"));
    assertTrue(fixture.sink.systemMessages().isEmpty());
    assertEquals(0, count(fixture.sink.events(), "connection-lost"));
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "not-found:BTC"));
  }

  @Test
  public void closeStopsTheRefresherAndIgnoresALateResult() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");
    fixture.advance(INTERVAL);
    int eventsBefore = fixture.sink.events().size();

    fixture.session.close();
    fixture.drain();
    fixture.transport.lateCompleteMeta(200, perps("BTC", "NEW"));
    fixture.drain();

    assertTrue(fixture.transport.allHttpHandlesSettled());
    assertEquals(
        0,
        count(fixture.sink.events().subList(eventsBefore, fixture.sink.events().size()), "known:"));
  }

  @Test
  public void closingBeforeLoginDoesNotFail() {
    Fixture fixture = new Fixture();

    fixture.session.close();
    fixture.drain();

    assertEquals(0, fixture.transport.httpHandleCount());
  }

  private static final String SUFFIX =
      " no longer listed by Hyperliquid; open subscriptions stay active until removed";

  @Test
  public void unlistedSubscriptionStaysOpenAndIsReportedOnce() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");

    fixture.refreshTo("BTC");

    assertEquals("[UNCLASSIFIED:ETH" + SUFFIX + "]", fixture.sink.systemMessages().toString());
    assertEquals(0, count(fixture.sink.events(), "instrument-removed:ETH"));
    // The subscription still publishes.
    fixture.session.onFrame(
        fixture.generation,
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"ETH\",\"time\":2,"
            + "\"levels\":[[{\"px\":\"101.000\",\"sz\":\"1\"}],[]]}}");
    fixture.drain();
    assertTrue(count(fixture.sink.events(), "depth:ETH") >= 2);

    fixture.refreshTo("BTC");
    assertEquals(1, fixture.sink.systemMessages().size());
  }

  @Test
  public void instrumentsUnlistedTogetherShareOneSortedMessage() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "SOL", "ETH");
    fixture.activate("SOL");
    fixture.activate("ETH");

    fixture.refreshTo("BTC");

    assertEquals("[UNCLASSIFIED:ETH, SOL" + SUFFIX + "]", fixture.sink.systemMessages().toString());
  }

  @Test
  public void relistingRearmsTheReport() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");

    fixture.refreshTo("BTC", "ETH");
    assertEquals(1, fixture.sink.systemMessages().size());
    fixture.refreshTo("BTC");

    assertEquals(2, fixture.sink.systemMessages().size());
  }

  @Test
  public void unsubscribingRearmsTheReport() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");
    fixture.session.unsubscribe("ETH");
    fixture.drain();

    fixture.refreshTo("BTC", "ETH");
    fixture.activate("ETH");
    fixture.refreshTo("BTC");

    assertEquals(2, fixture.sink.systemMessages().size());
  }

  @Test
  public void unlistedInstrumentCannotBeSubscribedAgain() {
    Fixture fixture = new Fixture();
    fixture.login("BTC", "ETH");
    fixture.refreshTo("BTC");

    fixture.session.subscribe("ETH", "", "PERPETUAL");
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "not-found:ETH"));
    assertTrue(fixture.sink.systemMessages().isEmpty());
  }

  @Test
  public void unlistedSpotPairIsReportedByItsSymbol() {
    Fixture fixture = new Fixture();
    fixture.loginWithSpot("BTC", "HYPE");
    fixture.activate("HYPE/USDC", "SPOT", "@1");

    fixture.refreshTo("BTC");

    assertEquals(
        "[UNCLASSIFIED:HYPE/USDC" + SUFFIX + "]", fixture.sink.systemMessages().toString());
    assertEquals(0, count(fixture.sink.events(), "instrument-removed:HYPE/USDC"));
  }

  @Test
  public void failedRefreshWithoutAMessageNamesTheFailureKind() {
    Fixture fixture = new Fixture();
    fixture.login("BTC");

    fixture.advance(INTERVAL);
    fixture.transport.failMeta(new java.net.SocketException());
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "diagnostic:metadata refresh failed: NETWORK"));
  }

  static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  /** A private budget: the JVM-wide one would leak permits between test classes. */
  static HyperliquidProcessBudget budget() {
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

  static int count(List<String> events, String prefix) {
    int matches = 0;
    for (String event : events) {
      if (event.startsWith(prefix)) {
        matches++;
      }
    }
    return matches;
  }

  static final class Fixture {
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

    void login(String... symbols) {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, perps(symbols));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend(); // fastAssetCtxs
      drain();
    }

    /** Like {@link #login}, but with a non-empty spot universe alongside the given perp. */
    void loginWithSpot(String perp, String spotBase) {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs(spotBase));
      transport.completeMeta(200, perps(perp));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend(); // fastAssetCtxs
      drain();
    }

    void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      advance(1_000L);
      transport.openSocket();
      generation++;
      drain();
      transport.socket().succeedNextSend(); // fastAssetCtxs
      drain();
    }

    void activate(String coin) {
      activate(coin, "PERPETUAL", coin);
    }

    void activate(String symbol, String type, String coin) {
      session.subscribe(symbol, "", type);
      drain();
      completeAllSends();
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
              + coin
              + "\",\"time\":1,\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"}],[]]}}");
      ack(coin, SubscriptionType.L2_BOOK);
      ack(coin, SubscriptionType.TRADES);
      drain();
    }

    /**
     * Completes every queued send. After a 30-minute advance the queue also holds heartbeat pings;
     * completing one arms the 15-second pong deadline, so a pong is delivered straight away.
     */
    void completeAllSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
      session.onFrame(generation, "{\"channel\":\"pong\"}");
      drain();
    }

    void ack(String coin, SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\""
              + coin
              + "\"}}}");
    }

    void refreshTo(String... symbols) {
      advance(INTERVAL);
      transport.completeMeta(200, perps(symbols));
      drain();
    }

    void advance(long millis) {
      scheduler.advanceBy(millis);
      drain();
    }

    void drain() {
      executor.drain();
    }
  }

  static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  static final class Scheduler implements CancellableScheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<Task>();

    Scheduler(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis));
      tasks.add(scheduled);
      return scheduled;
    }

    /** Runs due tasks in due-time order, moving the clock to each task's due time. */
    void advanceBy(long millis) {
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
