package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector.Listener;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
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

    fixture.connector.start(HyperliquidEnvironment.MAINNET);

    assertEquals(URI.create("https://api.hyperliquid.xyz/info"), fixture.transport.httpUri());
    assertEquals("application/json", fixture.transport.contentType());
    assertEquals("{\"type\":\"meta\"}", fixture.transport.httpBody());
    assertEquals(10_000L, fixture.transport.httpTimeoutMillis());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void validMetadataNotifiesListenerBeforeConnectingMainnetSocket() {
    Fixture fixture = new Fixture();

    fixture.connector.start(HyperliquidEnvironment.MAINNET);
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals(Arrays.asList("BTC"), fixture.listener.instrumentNames);
    assertEquals(
        Arrays.asList(URI.create("wss://api.hyperliquid.xyz/ws")),
        fixture.transport.connectCalls());
  }

  @Test
  public void testnetUsesBothTestnetEndpoints() {
    Fixture fixture = new Fixture();

    fixture.connector.start(HyperliquidEnvironment.TESTNET);
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

    fixture.connector.start(HyperliquidEnvironment.MAINNET);
    fixture.transport.completeMeta(503, "unavailable");

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.REMOTE, fixture.listener.initialFailures.get(0).kind());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void invalidMetadataDoesNotConnect() {
    Fixture fixture = new Fixture();

    fixture.connector.start(HyperliquidEnvironment.MAINNET);
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

    fixture.connector.start(HyperliquidEnvironment.MAINNET);
    fixture.connector.close();
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertTrue(fixture.transport.httpCancelled());
    assertTrue(fixture.transport.connectCalls().isEmpty());
    assertTrue(fixture.listener.instrumentNames.isEmpty());
  }

  private static String validMeta(String coin) {
    return "{\"universe\":[{\"name\":\"" + coin + "\",\"szDecimals\":2}]}";
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableClock clock = new MutableClock();
    private final RecordingListener listener = new RecordingListener();
    private final HyperliquidConnector connector;

    private Fixture() {
      Consumer<Runnable> directStateLane = Runnable::run;
      connector =
          new HyperliquidConnector(
              transport,
              new HyperliquidMetaParser(),
              HyperliquidProcessBudget.shared(),
              scheduler,
              clock,
              directStateLane);
      connector.setListener(listener);
    }

    private void startAndOpen() {
      connector.start(HyperliquidEnvironment.MAINNET);
      transport.completeMeta(200, validMeta("BTC"));
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
      // This test does not need the open notification.
    }

    @Override
    public void onFrame(long generation, String json) {
      // This test does not need raw frames.
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
