package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector.Listener;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.ConnectionPermit;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.FrameReservation;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.lang.reflect.Constructor;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Tests the connector lifecycle and its owned outbound transport state. */
public class HyperliquidConnectorTest {

  @Test
  public void startPostsMainnetMetadataBeforeOpeningTheWebSocket() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));

    assertEquals(URI.create("https://api.hyperliquid.xyz/info"), fixture.transport.httpUri());
    assertEquals("application/json", fixture.transport.contentType());
    assertEquals("{\"type\":\"meta\"}", fixture.transport.httpBody());
    assertEquals(10_000L, fixture.transport.httpTimeoutMillis());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void validMetadataNotifiesListenerBeforeConnectingMainnetSocket() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals(Arrays.asList("BTC"), fixture.listener.instrumentNames);
    assertEquals(
        Arrays.asList(URI.create("wss://api.hyperliquid.xyz/ws")),
        fixture.transport.connectCalls());
  }

  @Test
  public void testnetUsesBothTestnetEndpoints() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.TESTNET));
    fixture.transport.completeMeta(200, validMeta("ETH"));

    assertEquals(
        URI.create("https://api.hyperliquid-testnet.xyz/info"), fixture.transport.httpUri());
    assertEquals(
        Arrays.asList(URI.create("wss://api.hyperliquid-testnet.xyz/ws")),
        fixture.transport.connectCalls());
  }

  @Test
  public void failedMetadataDoesNotConnect() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(503, "unavailable");

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.REMOTE, fixture.listener.initialFailures.get(0).kind());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void invalidMetadataDoesNotConnect() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, "{}");

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.listener.initialFailures.get(0).kind());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void reconnectsWithBackoffAndResendsEveryDesiredDefinition() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.TRADES), 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.clearSuccessfulSendBodies();

    fixture.transport.remoteClose(1006, "lost");

    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
    fixture.clock.now = 1_000L;
    fixture.scheduler.advanceBy(1_000L);
    fixture.transport.openSocket();
    fixture.transport.socket().succeedNextSend();
    fixture.transport.socket().succeedNextSend();
    assertEquals(
        Arrays.asList(
            "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\"}}",
            "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"BTC\"}}"),
        fixture.transport.socket().successfulSendBodies());
  }

  @Test
  public void acknowledgementUsesTimeCapturedWhenSendStarts() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.clock.now = 500L;
    SubscriptionKey key = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);

    fixture.connector.subscribe(key, 20_000L);
    fixture.clock.now = 900L;
    fixture.transport.socket().succeedNextSend();

    assertEquals(1, fixture.listener.sentTimes.size());
    assertEquals(Long.valueOf(500L), fixture.listener.sentTimes.get(0));
  }

  @Test
  public void pingStartsAtThirtySecondsAndMissingPongReconnects() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();

    fixture.clock.now = 30_000L;
    fixture.scheduler.advanceBy(30_000L);
    assertEquals(1, fixture.transport.socket().pendingSendCount());
    fixture.transport.socket().succeedNextSend();
    fixture.clock.now = 45_000L;
    fixture.scheduler.advanceBy(15_000L);

    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void closeCancelsOutstandingMetadataAndSuppressesLateCallback() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.connector.close();
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertTrue(fixture.transport.httpCancelled());
    assertTrue(fixture.transport.connectCalls().isEmpty());
    assertTrue(fixture.listener.instrumentNames.isEmpty());
  }

  @Test
  public void closeSuppressesLateRawFrameCallbacks() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();

    fixture.connector.close();
    fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");

    assertTrue(fixture.listener.frames.isEmpty());
  }

  @Test
  public void initialHandshakeTimeoutFailsWithoutSchedulingReconnect() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));
    fixture.clock.now = 10_000L;
    fixture.scheduler.advanceBy(10_000L);

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.NETWORK, fixture.listener.initialFailures.get(0).kind());
    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void synchronousSocketSendFailureStartsOneReconnect() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.transport.socket().throwOnNextSend(new IllegalStateException("send broke"));

    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);

    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void reconnectBackoffResetsOnlyAfterListenerMarksGenerationHealthy() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);
    fixture.transport.socket().succeedNextSend();

    fixture.transport.remoteClose(1006, "first");
    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
    fixture.advanceAndOpen(1_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.remoteClose(1006, "second");
    assertEquals(2_000L, fixture.scheduler.nextDelayMillis());
    fixture.advanceAndOpen(2_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.connector.markHealthy(fixture.listener.lastGeneration);
    fixture.transport.remoteClose(1006, "third");

    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void closeCancelsConnectAndEveryOutstandingTimer() {
    Fixture fixture = new Fixture();
    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    fixture.connector.close();

    assertTrue(fixture.transport.connectCancelled());
    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void staleGenerationRawFrameIsNotForwardedAfterReconnect() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();

    fixture.transport.remoteClose(1006, "lost");
    fixture.advanceAndOpen(1_000L);
    fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");

    assertTrue(fixture.listener.frames.isEmpty());
  }

  @Test
  public void initialSocketSlotExhaustionBecomesFatalAfterTenSeconds() {
    HyperliquidProcessBudget budget = newBudget(1, 2, 5, 2);
    ConnectionPermit holder = budget.tryAcquireConnection(0L, 0).permit();
    Fixture fixture = new Fixture(budget);

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));
    fixture.clock.now = 10_000L;
    fixture.scheduler.advanceBy(10_000L);

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.listener.initialFailures.get(0).kind());
    holder.close();
  }

  @Test
  public void initialRollingAttemptLimitWaitDoesNotBecomeSocketSlotFatal() {
    HyperliquidProcessBudget budget = newBudget(1, 1, 5, 2);
    ConnectionPermit earlierAttempt = budget.tryAcquireConnection(0L, 0).permit();
    earlierAttempt.close();
    Fixture fixture = new Fixture(budget);

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));
    fixture.clock.now = 10_000L;
    fixture.scheduler.advanceBy(10_000L);

    assertTrue(fixture.listener.initialFailures.isEmpty());
    assertEquals(50_000L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void failedAsyncWriteStartsOneReconnect() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);

    fixture.transport.socket().failNextSend(new IllegalStateException("write broke"));

    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void reconnectReservationIsReplacedWhenAConnectionSubscriptionIsRemoved() {
    HyperliquidProcessBudget budget = newBudget(2, 10, 5, 2);
    Fixture fixture = new Fixture(budget);
    SubscriptionKey book = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);
    SubscriptionKey trades = new SubscriptionKey("BTC", SubscriptionType.TRADES);
    fixture.startAndOpen();
    fixture.connector.subscribe(book, 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.connector.subscribe(trades, 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.remoteClose(1006, "lost");
    fixture.clock.now = 1_000L;
    fixture.scheduler.advanceBy(1_000L);

    fixture.connector.unsubscribe(trades);
    FrameReservation available = budget.tryAcquireFrames(1_000L, 1).permit();

    assertTrue(available != null);
    available.close();
    fixture.connector.close();
  }

  @Test
  public void reconnectReservationIsReplacedWhenAConnectionSubscriptionIsAdded() {
    HyperliquidProcessBudget budget = newBudget(2, 10, 4, 2);
    Fixture fixture = new Fixture(budget);
    SubscriptionKey book = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);
    SubscriptionKey trades = new SubscriptionKey("BTC", SubscriptionType.TRADES);
    fixture.startAndOpen();
    fixture.connector.subscribe(book, 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.remoteClose(1006, "lost");
    fixture.clock.now = 1_000L;
    fixture.scheduler.advanceBy(1_000L);

    fixture.connector.subscribe(trades, 20_000L);
    assertEquals(3, fixture.transport.connectCalls().size());
    assertFalse(budget.tryAcquireFrames(1_000L, 1).acquired());
    fixture.transport.openSocket();
    fixture.transport.socket().succeedNextSend();
    fixture.transport.socket().succeedNextSend();
    fixture.clock.now = 31_000L;
    fixture.scheduler.advanceBy(30_000L);

    assertEquals(1, fixture.transport.socket().pendingSendCount());
    fixture.connector.close();
  }

  @Test
  public void expiredAddedSubscriptionIsNotReplayedAfterReconnectReplacement() {
    HyperliquidProcessBudget budget = newBudget(2, 10, 4, 2);
    Fixture fixture = new Fixture(budget);
    SubscriptionKey book = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);
    SubscriptionKey trades = new SubscriptionKey("BTC", SubscriptionType.TRADES);
    fixture.startAndOpen();
    fixture.connector.subscribe(book, 20_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.remoteClose(1006, "lost");
    fixture.clock.now = 1_000L;
    fixture.scheduler.advanceBy(1_000L);

    fixture.connector.subscribe(trades, 1_500L);
    fixture.clock.now = 2_000L;
    fixture.transport.openSocket();
    fixture.transport.openSocket();

    assertEquals(1, fixture.transport.socket().pendingSendCount());
    fixture.transport.socket().succeedNextSend();

    assertTrue(fixture.transport.socket().successfulSendBodies().contains(book.subscribeJson()));
    assertFalse(fixture.transport.socket().successfulSendBodies().contains(trades.subscribeJson()));
    fixture.connector.close();
    assertTrue(budget.tryAcquireFrames(2_000L, 2).acquired());
  }

  @Test
  public void frameBudgetRetrySendsAfterCapacityIsReleased() {
    HyperliquidProcessBudget budget = newBudget(2, 2, 1, 2);
    FrameReservation held = budget.tryAcquireFrames(0L, 1).permit();
    Fixture fixture = new Fixture(budget);
    fixture.startAndOpen();

    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);
    held.close();
    fixture.clock.now = 10L;
    fixture.scheduler.advanceBy(10L);

    assertEquals(1, fixture.transport.socket().pendingSendCount());
    fixture.connector.close();
  }

  @Test
  public void closeCancelsFrameBudgetRetryTimer() {
    HyperliquidProcessBudget budget = newBudget(2, 2, 1, 2);
    FrameReservation held = budget.tryAcquireFrames(0L, 1).permit();
    Fixture fixture = new Fixture(budget);
    fixture.startAndOpen();
    fixture.connector.subscribe(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), 20_000L);

    fixture.connector.close();
    held.close();

    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  @Test
  public void closeCancelsHeartbeatPongAndReconnectTimers() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.clock.now = 30_000L;
    fixture.scheduler.advanceBy(30_000L);
    fixture.transport.socket().succeedNextSend();

    fixture.connector.close();

    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  /** Hyperdash relays the Mainnet book, so metadata stays on Mainnet even with testnet selected. */
  @Test
  public void hyperdashStartUsesMainnetMetadataAndSendsHandshakeHeaders() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERDASH, HyperliquidEnvironment.TESTNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals("https://api.hyperliquid.xyz/info", fixture.transport.httpUri().toString());
    assertEquals(
        "wss://api.hyperdash.com/ws/orderbook", fixture.transport.connectCalls().get(0).toString());
    assertEquals("https://hyperdash.com", fixture.transport.connectHeaders().get(0).get("Origin"));
    assertTrue(fixture.transport.connectHeaders().get(0).get("User-Agent").startsWith("Mozilla"));
  }

  /** Borsa needs no handshake headers. */
  @Test
  public void borsaStartConnectsWithoutHandshakeHeaders() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals("wss://ws.borsa.cc/", fixture.transport.connectCalls().get(0).toString());
    assertTrue(fixture.transport.connectHeaders().get(0).isEmpty());
  }

  private static String validMeta(String coin) {
    return "{\"universe\":[{\"name\":\"" + coin + "\",\"szDecimals\":2}]}";
  }

  private static HyperliquidProcessBudget newBudget(
      int connections, int attempts, int frames, int subscriptions) {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(connections, attempts, frames, subscriptions, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError("unable to construct deterministic test budget", failure);
    }
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableClock clock = new MutableClock();
    private final RecordingListener listener = new RecordingListener();
    private final HyperliquidConnector connector;

    private Fixture() {
      this(HyperliquidProcessBudget.shared());
    }

    private Fixture(HyperliquidProcessBudget budget) {
      Consumer<Runnable> directStateLane = Runnable::run;
      connector =
          new HyperliquidConnector(
              transport, new HyperliquidMetaParser(), budget, scheduler, clock, directStateLane);
      connector.setListener(listener);
    }

    private void startAndOpen() {
      connector.start(
          SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      transport.completeMeta(200, validMeta("BTC"));
      transport.openSocket();
    }

    private void advanceAndOpen(long elapsedMillis) {
      clock.now += elapsedMillis;
      scheduler.advanceBy(elapsedMillis);
      transport.openSocket();
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class RecordingListener implements Listener {
    private final List<String> instrumentNames = new ArrayList<String>();
    private final List<TransportFailure> initialFailures = new ArrayList<TransportFailure>();
    private final List<Long> sentTimes = new ArrayList<Long>();
    private final List<String> frames = new ArrayList<String>();
    private long lastGeneration;

    @Override
    public void onMetadata(List<PerpetualInstrument> instruments) {
      for (PerpetualInstrument instrument : instruments) {
        instrumentNames.add(instrument.symbol());
      }
    }

    @Override
    public void onInitialFailure(TransportFailure failure) {
      initialFailures.add(failure);
    }

    @Override
    public void onSocketOpened(long generation) {
      lastGeneration = generation;
    }

    @Override
    public void onFrame(long generation, String json) {
      frames.add(json);
    }

    @Override
    public void onFrameSent(long generation, OutboundMessage message, long sentAtMillis) {
      sentTimes.add(Long.valueOf(sentAtMillis));
    }

    @Override
    public void onDisconnected(long generation, TransportFailure failure) {
      // This test asserts reconnect scheduling instead.
    }
  }
}
