package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Covers the second connection a relay source needs for the fastAssetCtxs feed. */
public class AssetContextFeedTest {

  /** A relay opens a second Hyperliquid Mainnet connection and subscribes to the feed on it. */
  @Test
  public void relayOpensADedicatedMainnetFeedConnection() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    assertEquals(2, fixture.transport.connectCalls().size());
    assertEquals(URI.create("wss://ws.borsa.cc/"), fixture.transport.connectCalls().get(0));
    assertEquals(
        URI.create("wss://api.hyperliquid.xyz/ws"), fixture.transport.connectCalls().get(1));
    // Both connectors start the transport they share, which HyperliquidTransport.start() must
    // tolerate; that contract is otherwise only stated in Javadoc.
    assertEquals(2, fixture.transport.startCount());
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString(),
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON));
  }

  /** The Hyperliquid source keeps using one connection. */
  @Test
  public void hyperliquidSourceOpensNoSecondConnection() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID);

    assertEquals(1, fixture.transport.connectCalls().size());
  }

  /** Mark prices arriving on the feed connection reach the instrument list. */
  @Test
  public void feedFramesRefreshReferencePrices() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /**
   * The feed answers its own pong so its heartbeat never tears the connection down. Both connectors
   * ping on the same 30 s cadence and share one fake socket, so both pings are flushed and both
   * pongs delivered. The clock then passes the 15 s pong deadline and the 1 s first reconnect
   * backoff behind it; without {@code acceptPong} on the feed connector that deadline fires and the
   * reconnect shows up as an extra connect call.
   */
  @Test
  public void feedAnswersItsOwnPong() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);
    int connectionsBefore = fixture.transport.connectCalls().size();

    fixture.advance(30_000L);
    while (fixture.transport.socket().pendingSendCount() > 0) {
      fixture.transport.socket().succeedNextSend();
      fixture.drain();
    }
    fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");
    fixture.transport.emitTextFromConnection(1, "{\"channel\":\"pong\"}");
    fixture.drain();
    fixture.advance(20_000L);
    fixture.advance(2_000L);

    assertEquals(connectionsBefore, fixture.transport.connectCalls().size());
  }

  /** A failing feed connection never fails login and never stops market data. */
  @Test
  public void feedFailureDoesNotFailLogin() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.transport.failConnection(1, new IOException("feed unavailable"));
    fixture.drain();

    assertTrue(
        fixture.sink.events().toString(),
        hasEvent(fixture.sink.events(), "diagnostic:asset-context feed disconnected"));
    assertFalse(fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "login-failed"));
    assertFalse(
        fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "connection-lost"));
  }

  /**
   * Mark prices still apply once the two connections' generation counters have diverged. The
   * market-data connection is lost and reconnected, which advances the session's generation to 2
   * and appends a third connect call; the untouched feed connection stays on generation 1, so a
   * frame it delivers is only applied when the feed gates on its own generation.
   */
  @Test
  public void feedGenerationIsIndependentOfTheMarketDataGeneration() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.transport.remoteCloseConnection(0, 1006, "lost");
    fixture.drain();
    fixture.advance(1_000L);
    fixture.transport.openConnection(2);
    fixture.drain();

    assertEquals(3, fixture.transport.connectCalls().size());

    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /**
   * A feed connection that fails before it ever opens is retried on the connector's standard
   * backoff, and the retried connection carries mark prices normally. On the market-data path such
   * a failure is fatal because it means login failed; for the feed it is only a diagnostic.
   */
  @Test
  public void feedRetriesAConnectionThatNeverOpened() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, false);

    fixture.transport.failConnection(1, new IOException("feed unavailable"));
    fixture.drain();
    assertEquals(2, fixture.transport.connectCalls().size());

    // advance() drains after the scheduler runs, so the retry body executes inside this call.
    fixture.advance(1_000L);
    assertEquals(3, fixture.transport.connectCalls().size());

    fixture.transport.openConnection(2);
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.emitTextFromConnection(
        2, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
    assertEquals(
        1, countEvents(fixture.sink.events(), "diagnostic:asset-context feed could not connect"));
    assertFalse(fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "login-failed"));
    assertFalse(
        fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "connection-lost"));
  }

  /**
   * The retry chain survives an outage longer than a single attempt. Two consecutive failures
   * before the socket ever opens must still leave a further attempt scheduled, and the whole outage
   * is one incident: the diagnostic count must not grow with the retries.
   */
  @Test
  public void feedKeepsRetryingAfterRepeatedPreOpenFailures() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, false);

    fixture.transport.failConnection(1, new IOException("feed unavailable"));
    fixture.drain();
    fixture.advance(1_000L);
    assertEquals(3, fixture.transport.connectCalls().size());

    fixture.transport.failConnection(2, new IOException("still unavailable"));
    fixture.drain();
    fixture.advance(2_000L);
    assertEquals(4, fixture.transport.connectCalls().size());

    fixture.transport.openConnection(3);
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.emitTextFromConnection(
        3, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
    assertEquals(
        fixture.sink.events().toString(),
        1,
        countEvents(fixture.sink.events(), "diagnostic:asset-context feed"));
    assertFalse(fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "login-failed"));
    assertFalse(
        fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "connection-lost"));
  }

  /** A connection that failed before opening still starts and sustains its heartbeat on retry. */
  @Test
  public void retriedFeedConnectionSurvivesRepeatedHeartbeats() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, false);
    fixture.transport.failConnection(1, new IOException("feed unavailable"));
    fixture.drain();
    fixture.advance(1_000L);
    fixture.transport.openConnection(2);
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    int connectionsAfterRetry = fixture.transport.connectCalls().size();

    for (int cycle = 0; cycle < 3; cycle++) {
      fixture.advance(30_000L);
      while (fixture.transport.socket().pendingSendCount() > 0) {
        fixture.transport.socket().succeedNextSend();
        fixture.drain();
      }
      fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");
      fixture.transport.emitTextFromConnection(2, "{\"channel\":\"pong\"}");
      fixture.drain();
    }

    assertEquals(connectionsAfterRetry, fixture.transport.connectCalls().size());
  }

  /** A feed that previously opened must also survive beyond its second reconnect heartbeat. */
  @Test
  public void reconnectedFeedSurvivesRepeatedHeartbeats() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);
    fixture.transport.remoteCloseConnection(1, 1006, "lost");
    fixture.drain();
    fixture.advance(1_000L);
    assertEquals(3, fixture.transport.connectCalls().size());
    fixture.open(2);

    for (int cycle = 0; cycle < 10; cycle++) {
      fixture.advance(30_000L);
      assertEquals(
          "heartbeat " + (cycle + 1) + ": " + fixture.sink.events(),
          1,
          countEvents(fixture.sink.events(), "diagnostic:asset-context feed disconnected"));
      assertEquals(2, fixture.transport.socket().pendingSendCount());
      for (int ping = 0; ping < 2; ping++) {
        fixture.transport.socket().succeedNextSend();
        fixture.drain();
      }
      assertEquals(
          2 * (cycle + 1),
          countEvents(fixture.transport.socket().successfulSendBodies(), "{\"method\":\"ping\"}"));
      fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");
      fixture.transport.emitTextFromConnection(2, "{\"channel\":\"pong\"}");
      fixture.drain();
      assertEquals(3, fixture.transport.connectCalls().size());
    }

    fixture.advance(15_000L);
    fixture.advance(1_000L);
    assertEquals(3, fixture.transport.connectCalls().size());
    assertEquals(
        1, countEvents(fixture.sink.events(), "diagnostic:asset-context feed disconnected"));
    fixture.transport.emitTextFromConnection(
        2, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"88.125\"}}"));
    fixture.drain();
    assertEquals("88.125", fixture.sink.lastReferencePrice("HYPE"));
    fixture.session.close();
    fixture.drain();
  }

  /**
   * A subscription error reaching the feed is reported as a diagnostic and never as the market-data
   * path's fatal unknown-target stop, and one incident still yields one report.
   */
  @Test
  public void feedSubscriptionErrorIsOnlyADiagnostic() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);
    String rejection = "{\"channel\":\"error\",\"data\":{\"message\":\"bad\"}}";

    fixture.transport.emitTextFromConnection(1, rejection);
    fixture.transport.emitTextFromConnection(1, rejection);
    fixture.drain();

    assertEquals(
        fixture.sink.events().toString(),
        1,
        countEvents(fixture.sink.events(), "diagnostic:asset-context feed subscription rejected"));
    assertFalse(
        fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "connection-lost"));
  }

  /**
   * A relay starts its feed while metadata is still being applied, so the first snapshot can land
   * before the market-data socket opens. Login is then reported the moment that socket opens,
   * without waiting the timeout out, and still exactly once.
   */
  @Test
  public void loginIsReportedAtOnceWhenTheSnapshotPrecededTheSocket() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, true, false);

    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();
    assertEquals(0, countEvents(fixture.sink.events(), "login-successful"));

    fixture.openMarketDataConnection();

    assertEquals(1, countEvents(fixture.sink.events(), "login-successful"));
    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** The feed connector shuts its own connection down without closing the transport it borrows. */
  @Test
  public void feedConnectorDoesNotCloseTheSharedTransport() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.feedConnector.close();
    fixture.drain();
    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertFalse(fixture.transport.closed());
    assertEquals(null, fixture.sink.lastReferencePrice("HYPE"));
  }

  /**
   * Closing the session settles both connections and closes the shared transport exactly once. A
   * deliberate shutdown is not an incident, so the feed reports nothing on its way out.
   */
  @Test
  public void sessionCloseSettlesBothConnectionsAndClosesTheTransportOnce() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.session.close();
    fixture.drain();

    assertTrue(fixture.transport.closed());
    assertEquals(1, fixture.transport.closeCount());
    assertTrue(fixture.transport.allConnectHandlesSettled());
    assertFalse(
        fixture.sink.events().toString(),
        hasEvent(fixture.sink.events(), "diagnostic:asset-context feed"));
  }

  /** RecordingSessionSink appends the failure reason, so events are matched by prefix. */
  private static boolean hasEvent(List<String> events, String prefix) {
    for (String event : events) {
      if (event.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static int countEvents(List<String> events, String prefix) {
    int count = 0;
    for (String event : events) {
      if (event.startsWith(prefix)) {
        count++;
      }
    }
    return count;
  }

  private static void noop() {
    // Intentionally empty.
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              int.class, int.class, int.class, int.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class Fixture {
    private static final int MARKET_DATA_CONNECTION = 0;
    private static final int FEED_CONNECTION = 1;

    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, AssetContextFeedTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final HyperliquidSession session;
    private HyperliquidConnector feedConnector;

    Fixture(MarketDataSource source) {
      this(source, true, true);
    }

    Fixture(MarketDataSource source, boolean openFeedConnection) {
      this(source, openFeedConnection, true);
    }

    /**
     * Leaving {@code openMarketDataConnection} false holds the market-data socket closed so a test
     * can deliver mark prices first, the order a relay really produces.
     */
    Fixture(MarketDataSource source, boolean openFeedConnection, boolean openMarketDataConnection) {
      session =
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
                  feedConnector =
                      new HyperliquidConnector(
                          transport,
                          new HyperliquidMetaParser(),
                          budget,
                          scheduler,
                          clock,
                          dispatcher::submitControl,
                          false);
                  return feedConnector;
                }
              },
              AssetContextFeedTest::noop);
      session.login(SourceProfile.of(source, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("HYPE")));
      drain();
      for (int index = 0; index < transport.connectCalls().size(); index++) {
        if (!openFeedConnection && index == FEED_CONNECTION) {
          continue;
        }
        if (!openMarketDataConnection && index == MARKET_DATA_CONNECTION) {
          continue;
        }
        open(index);
      }
    }

    void openMarketDataConnection() {
      open(MARKET_DATA_CONNECTION);
    }

    private void open(int index) {
      transport.openConnection(index);
      drain();
      // Only a Hyperliquid connection subscribes to the feed, so a relay's index 0 sends nothing.
      if (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    void advance(long elapsedMillis) {
      clock.now += elapsedMillis;
      scheduler.advanceBy(elapsedMillis);
      drain();
    }

    void drain() {
      executor.drain();
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class TestScheduler implements CancellableScheduler {
    private final MutableClock clock;
    private final PriorityQueue<Task> tasks = new PriorityQueue<Task>();
    private long sequence;

    TestScheduler(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis), sequence++);
      tasks.add(scheduled);
      return scheduled;
    }

    void advanceBy(long elapsed) {
      while (!tasks.isEmpty() && tasks.peek().due <= clock.now) {
        Task due = tasks.poll();
        if (!due.cancelled) {
          due.task.run();
        }
      }
    }

    private static final class Task implements Cancellable, Comparable<Task> {
      private final Runnable task;
      private final long due;
      private final long sequence;
      private boolean cancelled;

      Task(Runnable task, long due, long sequence) {
        this.task = task;
        this.due = due;
        this.sequence = sequence;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }

      @Override
      public int compareTo(Task other) {
        int compareDue = Long.compare(due, other.due);
        return compareDue != 0 ? compareDue : Long.compare(sequence, other.sequence);
      }
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }
}
