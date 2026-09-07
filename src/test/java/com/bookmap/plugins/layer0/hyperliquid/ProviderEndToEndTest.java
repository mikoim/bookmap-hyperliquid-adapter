package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.FrameReservation;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.session.AssetContextConnectorFactory;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSession;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionApi;
import com.bookmap.plugins.layer0.hyperliquid.session.SessionSink;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.Test;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;
import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiDataAdapter;
import velox.api.layer1.Layer1ApiInstrumentAdapter;
import velox.api.layer1.data.ExtendedLoginData;
import velox.api.layer1.data.LoginData;
import velox.api.layer1.data.OrderSendParameters;
import velox.api.layer1.data.OrderUpdateParameters;
import velox.api.layer1.data.SubscribeInfo;
import velox.api.layer1.data.SubscribeInfoCrypto;
import velox.api.layer1.data.SystemTextMessageType;
import velox.api.layer1.data.TradeInfo;

/**
 * End-to-end provider regressions using the real session, connector, parsers, and fake transport.
 */
public class ProviderEndToEndTest {

  @Test
  public void mainnetLifecyclePublishesBookAndTradeThenClosesCleanly() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.login(HyperliquidEnvironment.MAINNET);
    fixture.endAssetContextWait();
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login",
            "added:BTC",
            "depth:BTC:1000000:100",
            "depth:BTC:1010000:200",
            "trade:BTC:1000000"),
        fixture.trace);
    assertEquals("https://api.hyperliquid.xyz/info", fixture.transport.httpUri().toString());
    assertEquals(
        "wss://api.hyperliquid.xyz/ws", fixture.transport.connectCalls().get(0).toString());
    fixture.provider.unsubscribe("BTC");
    fixture.drain();
    fixture.completeSends();
    assertEquals(Collections.singletonList("BTC"), fixture.instruments.removed);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void tradeDeduplicationUsesTenMinuteTtlAndTenThousandEntryCapacity() {
    Fixture fixture = new Fixture(budget(2, 20, 20, 2));
    fixture.login(HyperliquidEnvironment.MAINNET);
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();

    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();
    assertEquals(1, fixture.data.trades.size());

    fixture.clock.now = 60_000L;
    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();
    assertEquals(1, fixture.data.trades.size());

    for (int index = 0; index < 10_000; index++) {
      fixture.trade("BTC", "B", "100", "1", 60_000L, index + 100L);
      if (index % 1_000 == 999) {
        fixture.drain();
      }
    }
    fixture.drain();
    assertEquals(10_001, fixture.data.trades.size());

    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();
    assertEquals(10_002, fixture.data.trades.size());

    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void testnetLoginSelectsBothTestnetEndpointsAndActivates() {
    Fixture fixture = new Fixture(budget(2, 10, 20, 2));
    fixture.login(HyperliquidEnvironment.TESTNET);
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();

    assertEquals(
        "https://api.hyperliquid-testnet.xyz/info", fixture.transport.httpUri().toString());
    assertEquals(
        "wss://api.hyperliquid-testnet.xyz/ws", fixture.transport.connectCalls().get(0).toString());
    assertEquals(Collections.singletonList("BTC"), fixture.instruments.added);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void disconnectResubscribesOnceAndResendsFirstBookAsFullSnapshot() {
    Fixture fixture = new Fixture(budget(2, 20, 50, 2));
    fixture.login(HyperliquidEnvironment.MAINNET);
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();
    int depthsBeforeLoss = fixture.data.depths.size();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    assertEquals(1, fixture.admin.connectionLostCount);
    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
    fixture.advance(1_000L);
    fixture.transport.openSocket();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertEquals(2, fixture.transport.connectCalls().size());
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 2L, "100", "1", "101", "2");
    fixture.drain();

    assertEquals(1, fixture.admin.connectionLostCount);
    assertEquals(1, fixture.admin.connectionRestoredCount);
    assertTrue(fixture.data.depths.size() > depthsBeforeLoss);
    assertEquals("depth:BTC:1000000:0", fixture.data.depths.get(depthsBeforeLoss));
    assertEquals("depth:BTC:1000000:100", fixture.data.depths.get(depthsBeforeLoss + 1));
    assertEquals("depth:BTC:1010000:0", fixture.data.depths.get(depthsBeforeLoss + 2));
    assertEquals("depth:BTC:1010000:200", fixture.data.depths.get(depthsBeforeLoss + 3));
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void metadataAndHandshakeFailuresUnwindPartialStartupIdempotently() {
    Fixture metadataFailure = new Fixture(budget(2, 10, 10, 2));
    metadataFailure.provider.login(Fixture.mainnetLogin());
    metadataFailure.drain();
    metadataFailure.transport.failMeta(new IllegalStateException("metadata unavailable"));
    metadataFailure.drain();
    metadataFailure.closeTwice();
    metadataFailure.assertClosed();

    Fixture handshakeFailure = new Fixture(budget(2, 10, 10, 2));
    handshakeFailure.provider.login(Fixture.mainnetLogin());
    handshakeFailure.drain();
    handshakeFailure.transport.completeMeta(200, metadata("BTC"));
    handshakeFailure.drain();
    handshakeFailure.transport.failSocket(new IllegalStateException("handshake failed"));
    handshakeFailure.drain();
    handshakeFailure.closeTwice();
    assertTrue(handshakeFailure.transport.connectCancelled());
    handshakeFailure.assertClosed();
  }

  @Test
  public void pendingActivationKeepsOriginalDeadlineAcrossDisconnectAndExpires() {
    Fixture fixture = new Fixture(budget(2, 20, 20, 2));
    fixture.login(HyperliquidEnvironment.MAINNET);
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.transport.remoteClose(1006, "before activation");
    fixture.drain();
    fixture.advance(1_000L);
    fixture.transport.openSocket();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.completeSends();
    fixture.advance(9_001L);
    fixture.drain();

    assertTrue(fixture.instruments.added.isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void invalidSnapshotIsIgnoredAndQueueOverflowRejectsWholeFrame() {
    Fixture fixture = new Fixture(budget(2, 20, 5_000, 2));
    fixture.login(HyperliquidEnvironment.MAINNET);
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();
    int depthsBeforeInvalid = fixture.data.depths.size();
    fixture.book("BTC", 0L, "100", "1", "101", "2");
    fixture.drain();
    assertEquals(depthsBeforeInvalid, fixture.data.depths.size());
    int tradesBeforeOverflow = fixture.data.trades.size();

    for (int i = 0; i < 4_095; i++) {
      fixture.transport.emitTextFromConnection(
          0, fixture.bookJson("BTC", i + 2L, "100", "1", "101", "2"));
    }
    fixture.transport.emitTextFromConnection(
        0, fixture.multiTradeJson("BTC", 10_000L, "300", "301", 7L, 8L));
    fixture.drain();
    assertTrue(fixture.admin.systemMessages.contains("market-data frame queue overflow"));
    assertEquals(depthsBeforeInvalid, fixture.data.depths.size());
    assertEquals(tradesBeforeOverflow, fixture.data.trades.size());
    assertFalse(fixture.trace.contains("trade:BTC:300"));
    assertFalse(fixture.trace.contains("trade:BTC:301"));
    assertEquals(1, fixture.transport.connectCalls().size());
    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
    assertEquals(1, fixture.admin.connectionLostCount);
    fixture.advance(1_000L);
    fixture.transport.openSocket();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 10_001L, "100", "1", "101", "2");
    fixture.drain();
    assertEquals(2, fixture.transport.connectCalls().size());
    assertEquals(1, fixture.admin.connectionRestoredCount);
    assertEquals("depth:BTC:1000000:0", fixture.data.depths.get(depthsBeforeInvalid));
    assertEquals("depth:BTC:1000000:100", fixture.data.depths.get(depthsBeforeInvalid + 1));

    for (int i = 0; i < 4_095; i++) {
      fixture.transport.emitTextFromConnection(
          1, fixture.bookJson("BTC", i + 20_000L, "100", "1", "101", "2"));
    }
    fixture.transport.emitTextFromConnection(
        1, fixture.multiTradeJson("BTC", 30_000L, "400", "401", 9L, 10L));
    fixture.drain();
    assertEquals(2, count(fixture.admin.systemMessages, "market-data frame queue overflow"));
    assertEquals(tradesBeforeOverflow, fixture.data.trades.size());
    assertFalse(fixture.trace.contains("trade:BTC:400"));
    assertFalse(fixture.trace.contains("trade:BTC:401"));
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void targetedRejectionRemovesOnlyOneAliasAndUntargetedErrorIsFatal() {
    Fixture fixture = new Fixture(budget(2, 20, 50, 4));
    fixture.loginWithMetadata("BTC", "ETH");
    fixture.subscribe("BTC");
    fixture.subscribe("ETH");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.ack("ETH", "l2Book");
    fixture.ack("ETH", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.book("ETH", 1L, "200", "1", "201", "2");
    fixture.drain();
    fixture.error("BTC");
    fixture.drain();
    assertEquals(Collections.singletonList("BTC"), fixture.instruments.removed);
    assertTrue(fixture.instruments.added.contains("ETH"));
    fixture.book("ETH", 2L, "200", "2", "201", "3");
    fixture.trade("ETH", "A", "201", "1", 3L, 8L);
    fixture.drain();
    assertTrue(fixture.data.depths.contains("depth:ETH:2000000:200"));
    assertTrue(fixture.trace.contains("trade:ETH:2010000"));

    fixture.error(null);
    fixture.drain();
    assertTrue(fixture.admin.connectionLostFatal);
    fixture.provider.sendOrder(new EmptyOrder());
    fixture.provider.updateOrder(new OrderUpdateParameters("id"));
    assertEquals(2, fixture.admin.orderFailures);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void sharedBudgetCapsConnectionsSubscriptionsAndFramesWithoutBlockingStateLanes() {
    HyperliquidProcessBudget budget = budget(2, 20, 4, 4);
    Fixture first = new Fixture(budget);
    Fixture second = new Fixture(budget);
    first.login(HyperliquidEnvironment.MAINNET);
    first.subscribe("BTC");
    first.completeSends();
    first.ack("BTC", "l2Book");
    first.ack("BTC", "trades");
    first.book("BTC", 1L, "100", "1", "101", "2");
    first.drain();

    second.provider.login(Fixture.testnetLogin());
    second.drain();
    second.transport.completeMeta(200, metadata("BTC"));
    second.drain();
    second.transport.openSocket();
    second.drain();
    second.transport.socket().succeedNextSend();
    second.drain();
    second.subscribe("BTC");
    second.drain();
    assertEquals(1, first.transport.connectCalls().size());
    assertEquals(1, second.transport.connectCalls().size());
    assertEquals(4, budget.reservedSubscriptionSlots());
    assertEquals(0, second.transport.socket().pendingSendCount());
    assertEquals(0, second.executor.queuedTaskCount());

    first.closeTwice();
    second.closeTwice();
    first.assertClosed();
    second.assertClosed();

    assertFalse(budget.tryAcquireFrames(0L, 1).acquired());
    assertFalse(budget.tryAcquireFrames(59_999L, 1).acquired());
    FrameReservation available = budget.tryAcquireFrames(60_000L, 1).permit();
    assertTrue(available != null);
    available.close();
  }

  @Test
  public void sharedBudgetConnectionAndAttemptLimitsRetryWithoutBlocking() {
    HyperliquidProcessBudget budget = budget(1, 1, 4, 2);
    Fixture first = new Fixture(budget);
    Fixture second = new Fixture(budget);
    first.login(HyperliquidEnvironment.MAINNET);
    second.provider.login(Fixture.mainnetLogin());
    second.drain();
    second.transport.completeMeta(200, metadata("BTC"));
    second.drain();

    assertEquals(1, first.transport.connectCalls().size());
    assertEquals(0, second.transport.connectCalls().size());
    assertEquals(10L, second.scheduler.nextDelayMillis());

    first.closeTwice();
    first.assertClosed();
    second.advance(10L);
    assertEquals(0, second.transport.connectCalls().size());
    assertEquals(59_990L, second.scheduler.nextDelayMillis());
    second.advance(59_990L);
    assertEquals(1, second.transport.connectCalls().size());
    second.transport.openSocket();
    second.drain();
    second.transport.socket().succeedNextSend();
    second.drain();
    second.closeTwice();
    second.assertClosed();
  }

  @Test
  public void sharedFrameWindowDefersProviderHeartbeatUntilExpiry() {
    HyperliquidProcessBudget budget = budget(2, 20, 4, 2);
    Fixture first = new Fixture(budget);
    Fixture second = new Fixture(budget);
    first.login(HyperliquidEnvironment.MAINNET);
    first.subscribe("BTC");
    first.completeSends();
    first.ack("BTC", "l2Book");
    first.ack("BTC", "trades");
    first.book("BTC", 1L, "100", "1", "101", "2");
    first.drain();

    second.login(HyperliquidEnvironment.TESTNET);
    second.advance(30_000L);
    assertEquals(0, second.transport.socket().pendingSendCount());
    first.closeTwice();
    first.assertClosed(false);
    second.advance(30_000L);
    assertTrue(second.transport.socket().pendingSendCount() > 0);
    second.completeSends();
    assertTrue(second.transport.socket().successfulSendBodies().contains("{\"method\":\"ping\"}"));
    second.closeTwice();
    second.assertClosed();
  }

  @Test
  public void sharedSubscriptionSlotsRejectThenRetryAfterOtherProviderUnsubscribes() {
    HyperliquidProcessBudget budget = budget(2, 20, 20, 2);
    Fixture first = new Fixture(budget);
    Fixture second = new Fixture(budget);
    first.login(HyperliquidEnvironment.MAINNET);
    second.provider.login(Fixture.testnetLogin());
    second.drain();
    second.transport.completeMeta(200, metadata("BTC"));
    second.drain();
    second.transport.openSocket();
    second.drain();
    second.transport.socket().succeedNextSend();
    second.drain();

    first.subscribe("BTC");
    first.completeSends();
    first.ack("BTC", "l2Book");
    first.ack("BTC", "trades");
    first.book("BTC", 1L, "100", "1", "101", "2");
    first.drain();
    second.subscribe("BTC");
    assertTrue(second.admin.systemMessages.contains("Hyperliquid subscription limit reached"));
    assertEquals(2, budget.reservedSubscriptionSlots());

    first.provider.unsubscribe("BTC");
    first.drain();
    first.completeSends();
    assertEquals(0, budget.reservedSubscriptionSlots());
    second.subscribe("BTC");
    second.completeSends();
    second.ack("BTC", "l2Book");
    second.ack("BTC", "trades");
    second.book("BTC", 2L, "100", "1", "101", "2");
    second.drain();
    assertTrue(second.instruments.added.contains("BTC"));
    first.closeTwice();
    second.closeTwice();
    first.assertClosed();
    second.assertClosed();
  }

  @Test
  public void borsaLoginUsesMainnetMetadataAndPublishesSeedThenDelta() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSource("Borsa", true);
    fixture.endAssetContextWait();
    fixture.subscribe("BTC");
    fixture.completeSends();

    assertEquals("https://api.hyperliquid.xyz/info", fixture.transport.httpUri().toString());
    assertEquals("wss://ws.borsa.cc/", fixture.transport.connectCalls().get(0).toString());
    assertTrue(fixture.transport.connectHeaders().get(0).isEmpty());
    assertTrue(
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\",\"nLevels\":400}}"));

    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":1,\"levels\":["
            + "[{\"px\":\"100\",\"sz\":\"1\",\"n\":1}],[{\"px\":\"101\",\"sz\":\"2\",\"n\":1}]]}}");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":1,\"levels\":["
            + "[{\"px\":\"100\",\"sz\":\"0\",\"n\":0}],[{\"px\":\"102\",\"sz\":\"3\",\"n\":1}]]}}");
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login",
            "added:BTC",
            "depth:BTC:1000000:100",
            "depth:BTC:1010000:200",
            "depth:BTC:1000000:0",
            "depth:BTC:1020000:300"),
        fixture.trace);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void hyperdashLoginSendsHandshakeHeadersAndAcceptsEchoedAck() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSource("Hyperdash", false);
    fixture.endAssetContextWait();
    fixture.subscribe("BTC");
    fixture.completeSends();

    assertEquals(
        "wss://api.hyperdash.com/ws/orderbook", fixture.transport.connectCalls().get(0).toString());
    assertEquals("https://hyperdash.com", fixture.transport.connectHeaders().get(0).get("Origin"));
    assertTrue(fixture.transport.connectHeaders().get(0).containsKey("User-Agent"));
    assertTrue(
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\"}}"));

    fixture.frame(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\"}}}");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();

    assertEquals(
        Arrays.asList("login", "added:BTC", "depth:BTC:1000000:100", "depth:BTC:1010000:200"),
        fixture.trace);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void coarseTickAggregatesTheBookAndScalesTradesThroughBookmapListeners() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithPricedMetadata("HYPE", "87.785");
    fixture.provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.01d, 100d));
    fixture.drain();
    fixture.completeSends();
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString().contains("\"nSigFigs\":4"));
    fixture.ack("HYPE", "l2Book");
    fixture.ack("HYPE", "trades");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"HYPE\",\"time\":1,\"levels\":[["
            + "{\"px\":\"87.784\",\"sz\":\"2.36\"},{\"px\":\"87.783\",\"sz\":\"1.32\"}],["
            + "{\"px\":\"87.785\",\"sz\":\"161.16\"}]]}}");
    fixture.trade("HYPE", "B", "87.784", "1", 2L, 7L);
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login",
            "added:HYPE",
            "depth:HYPE:8778:368",
            "depth:HYPE:8779:16116",
            "trade:HYPE:8778"),
        fixture.trace);
    assertEquals(0.01d, fixture.instruments.lastInfo.pips, 0d);
  }

  @Test
  public void legacyWorkspaceTickSubscribesOnTheNativeGridWithoutAggregation() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithPricedMetadata("HYPE", "87.785");
    fixture.provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.0001d, 100d));
    fixture.drain();
    fixture.completeSends();
    String bodies = fixture.transport.socket().successfulSendBodies().toString();
    assertFalse(bodies.contains("nSigFigs"));
    assertFalse(bodies.contains("mantissa"));
    assertFalse(bodies.contains("nLevels"));
    fixture.ack("HYPE", "l2Book");
    fixture.ack("HYPE", "trades");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"HYPE\",\"time\":1,\"levels\":[["
            + "{\"px\":\"87.784\",\"sz\":\"2.36\"},{\"px\":\"87.783\",\"sz\":\"1.32\"}],["
            + "{\"px\":\"87.785\",\"sz\":\"161.16\"}]]}}");
    fixture.trade("HYPE", "B", "87.784", "1", 2L, 7L);
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login",
            "added:HYPE",
            "depth:HYPE:877830:132",
            "depth:HYPE:877840:236",
            "depth:HYPE:877850:16116",
            "trade:HYPE:877840"),
        fixture.trace);
    assertEquals(0.0001d, fixture.instruments.lastInfo.pips, 0d);
  }

  /** A HIP-3 instrument subscribes, prices, and trades under its fully qualified name. */
  @Test
  public void hip3InstrumentFlowsEndToEnd() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithMetadata("BTC", "xyz:CL");
    fixture.frame(TestMetadata.assetContextsFrame("{\"xyz:CL\":{\"markPx\":\"92.283\"}}"));
    fixture.drain();

    fixture.subscribe("xyz:CL");
    fixture.completeSends();
    fixture.ack("xyz:CL", "l2Book");
    fixture.ack("xyz:CL", "trades");
    fixture.book("xyz:CL", 1L, "92.282", "1.5", "92.283", "2.5");
    fixture.trade("xyz:CL", "B", "92.283", "1.0", 2L, 9L);
    fixture.drain();

    assertEquals(Collections.singletonList("xyz:CL"), fixture.instruments.added);
    assertEquals(0.001d, fixture.instruments.lastInfo.pips, 1e-12d);
    assertFalse(fixture.data.depths.toString(), fixture.data.depths.isEmpty());
    assertFalse(fixture.data.trades.toString(), fixture.data.trades.isEmpty());
    fixture.closeTwice();
    fixture.assertClosed();
  }

  private static HyperliquidProcessBudget budget(
      int connections, int attempts, int frames, int subscriptions) {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(connections, attempts, frames, subscriptions, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError("unable to construct deterministic budget", failure);
    }
  }

  private static int count(List<String> values, String expected) {
    int count = 0;
    for (String value : values) {
      if (expected.equals(value)) {
        count++;
      }
    }
    return count;
  }

  private static final class Fixture {
    private static final int MARKET_DATA_CONNECTION = 0;
    private static final int FEED_CONNECTION = 1;

    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final Clock clock = new Clock();
    private final ManualExecutor executor = new ManualExecutor();
    private final HyperliquidProcessBudget budget;
    private final AtomicReference<HyperliquidSession> sessionRef =
        new AtomicReference<HyperliquidSession>();
    private final StateEventDispatcher dispatcher;
    private final Provider provider;
    private final List<String> trace = new ArrayList<String>();
    private final RecordingAdmin admin = new RecordingAdmin(trace);
    private final RecordingInstrument instruments = new RecordingInstrument(trace);
    private final RecordingData data = new RecordingData(trace);
    private boolean feedConnectorCreated;

    private Fixture(HyperliquidProcessBudget budget) {
      this.budget = budget;
      dispatcher =
          new StateEventDispatcher(
              executor,
              4_096,
              new Runnable() {
                @Override
                public void run() {
                  HyperliquidSession session = sessionRef.get();
                  if (session != null) {
                    session.onMarketOverflow();
                  }
                }
              });
      provider =
          new Provider(
              new HyperliquidSessionFactory() {
                @Override
                public HyperliquidSessionApi create(SessionSink sink) {
                  HyperliquidConnector connector =
                      new HyperliquidConnector(
                          transport,
                          new HyperliquidMetaParser(),
                          budget,
                          scheduler,
                          clock,
                          dispatcher::submitControl);
                  HyperliquidSession session =
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
                              feedConnectorCreated = true;
                              return new HyperliquidConnector(
                                  transport,
                                  new HyperliquidMetaParser(),
                                  budget,
                                  scheduler,
                                  clock,
                                  dispatcher::submitControl,
                                  false);
                            }
                          },
                          new Runnable() {
                            @Override
                            public void run() {
                              executor.shutdownRequested = true;
                            }
                          });
                  sessionRef.set(session);
                  return session;
                }
              });
      provider.addListener(admin);
      provider.addListener(instruments);
      provider.addListener(data);
    }

    private void login(HyperliquidEnvironment environment) {
      loginWithMetadata(environment, "BTC");
    }

    private void loginWithMetadata(String... symbols) {
      loginWithMetadata(HyperliquidEnvironment.MAINNET, symbols);
    }

    private void loginWithMetadata(HyperliquidEnvironment environment, String... symbols) {
      provider.login(
          environment == HyperliquidEnvironment.TESTNET ? testnetLogin() : mainnetLogin());
      drain();
      transport.completeMeta(200, metadata(symbols));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    private void loginWithPricedMetadata(String symbol, String markPx) {
      loginWithMetadata(symbol);
      frame(
          TestMetadata.assetContextsFrame("{\"" + symbol + "\":{\"markPx\":\"" + markPx + "\"}}"));
      drain();
    }

    private void subscribe(String symbol) {
      provider.subscribe(new SubscribeInfo(symbol, "", "PERPETUAL"));
      drain();
    }

    private void completeSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void ack(String coin, String type) {
      String response =
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type
              + "\",\"coin\":\""
              + coin
              + "\"}}}";
      transport.emitTextFromConnection(marketDataConnection(), response);
      drain();
    }

    private void book(
        String coin, long time, String bidPrice, String bidSize, String askPrice, String askSize) {
      transport.emitTextFromConnection(
          marketDataConnection(), bookJson(coin, time, bidPrice, bidSize, askPrice, askSize));
    }

    private String bookJson(
        String coin, long time, String bidPrice, String bidSize, String askPrice, String askSize) {
      return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
          + coin
          + "\",\"time\":"
          + time
          + ",\"levels\":[[{\"px\":\""
          + bidPrice
          + "\",\"sz\":\""
          + bidSize
          + "\"}],[{\"px\":\""
          + askPrice
          + "\",\"sz\":\""
          + askSize
          + "\"}]]}}";
    }

    private void trade(String coin, String side, String price, String size, long time, long tid) {
      transport.emitTextFromConnection(
          marketDataConnection(),
          "{\"channel\":\"trades\",\"data\":[{\"coin\":\""
              + coin
              + "\",\"side\":\""
              + side
              + "\",\"px\":\""
              + price
              + "\",\"sz\":\""
              + size
              + "\",\"time\":"
              + time
              + ",\"tid\":"
              + tid
              + "}]}");
    }

    private String multiTradeJson(
        String coin,
        long time,
        String firstPrice,
        String secondPrice,
        long firstTid,
        long secondTid) {
      return "{\"channel\":\"trades\",\"data\":[{\"coin\":\""
          + coin
          + "\",\"side\":\"B\",\"px\":\""
          + firstPrice
          + "\",\"sz\":\"1\",\"time\":"
          + time
          + ",\"tid\":"
          + firstTid
          + "},{\"coin\":\""
          + coin
          + "\",\"side\":\"A\",\"px\":\""
          + secondPrice
          + "\",\"sz\":\"1\",\"time\":"
          + time
          + ",\"tid\":"
          + secondTid
          + "}]}";
    }

    private void error(String coin) {
      String target =
          coin == null ? "" : ",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"" + coin + "\"}";
      transport.emitTextFromConnection(
          marketDataConnection(),
          "{\"channel\":\"error\",\"data\":{\"message\":\"bad\"" + target + "}}");
    }

    /**
     * Ends the session's brief wait for a first mark price. These fixtures never open the feed
     * connection, so login arrives on the timeout, exactly as it does when a feed is rejected.
     */
    private void endAssetContextWait() {
      advance(2_000L);
    }

    private void advance(long elapsed) {
      clock.now += elapsed;
      scheduler.advanceBy(elapsed);
      drain();
    }

    private void drain() {
      executor.drain();
    }

    private void closeTwice() {
      provider.close();
      provider.close();
      drain();
    }

    private void assertClosed() {
      assertClosed(true);
    }

    private void assertClosed(boolean assertBudgetReleased) {
      assertTrue(executor.shutdownRequested);
      assertEquals(0, executor.queuedTaskCount());
      assertTrue(transport.closed());
      assertFalse(transport.socket().isOpen());
      assertEquals(0, transport.socket().pendingSendCount());
      assertEquals(transport.httpHandleCount(), transport.settledHttpHandleCount());
      assertTrue(transport.allHttpHandlesSettled());
      assertEquals(transport.connectionHandleCount(), transport.settledConnectHandleCount());
      assertTrue(transport.allConnectHandlesSettled());
      assertEquals(-1L, scheduler.nextDelayMillis());
      if (assertBudgetReleased) {
        assertEquals(0, budgetInt("openConnections"));
        assertEquals(0, budgetInt("heldFrames"));
        assertEquals(0, budget.reservedSubscriptionSlots());
      }
      int eventCount = trace.size();
      transport.lateCompleteMeta(200, metadata("BTC"));
      transport.lateOpenConnections();
      transport.socket().lateSucceedAllSends();
      for (int index = 0; index < transport.connectionHandleCount(); index++) {
        transport.emitTextFromConnection(index, bookJson("BTC", 99_999L, "333", "1", "334", "1"));
      }
      drain();
      assertEquals(eventCount, trace.size());
    }

    private int budgetInt(String fieldName) {
      try {
        Field field = HyperliquidProcessBudget.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(budget);
      } catch (Exception failure) {
        throw new AssertionError("unable to inspect budget state", failure);
      }
    }

    private static LoginData mainnetLogin() {
      return new ExtendedLoginData(Collections.<String, CredentialsSerializationField>emptyMap());
    }

    private static LoginData testnetLogin() {
      return new ExtendedLoginData(
          Collections.singletonMap(
              HyperliquidFieldManager.TESTNET_FIELD,
              new CredentialsSerializationField(true, false, "true")));
    }

    private static LoginData sourceLogin(String source, boolean testnet) {
      java.util.Map<String, CredentialsSerializationField> fields =
          new java.util.HashMap<String, CredentialsSerializationField>();
      fields.put(
          HyperliquidFieldManager.SOURCE_FIELD,
          new CredentialsSerializationField(true, false, source));
      fields.put(
          HyperliquidFieldManager.TESTNET_FIELD,
          new CredentialsSerializationField(true, false, Boolean.toString(testnet)));
      return new ExtendedLoginData(fields);
    }

    private void loginWithSource(String source, boolean testnet) {
      provider.login(sourceLogin(source, testnet));
      drain();
      transport.completeMeta(200, metadata("BTC"));
      drain();
      transport.openConnection(MARKET_DATA_CONNECTION);
      drain();
    }

    private void frame(String json) {
      transport.emitTextFromConnection(marketDataConnection(), json);
    }

    /**
     * A relay's asset-context feed owns connect handle {@link #FEED_CONNECTION}; every other handle
     * belongs to the market-data connection, whose newest generation is the last handle.
     */
    private int marketDataConnection() {
      int last = transport.connectCalls().size() - 1;
      return feedConnectorCreated && last == FEED_CONNECTION ? MARKET_DATA_CONNECTION : last;
    }
  }

  private static String metadata(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  private static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();
    private boolean shutdownRequested;

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }

    private int queuedTaskCount() {
      return tasks.size();
    }
  }

  private static final class RecordingAdmin implements Layer1ApiAdminAdapter {
    private final List<String> trace;
    private final List<String> systemMessages = new ArrayList<String>();
    private int connectionLostCount;
    private int connectionRestoredCount;
    private boolean connectionLostFatal;
    private int orderFailures;

    private RecordingAdmin(List<String> trace) {
      this.trace = trace;
    }

    @Override
    public void onLoginSuccessful() {
      trace.add("login");
    }

    @Override
    public void onConnectionLost(velox.api.layer1.data.DisconnectionReason reason, String message) {
      connectionLostCount++;
      connectionLostFatal |= reason.name().equals("FATAL");
    }

    @Override
    public void onConnectionRestored() {
      connectionRestoredCount++;
    }

    @Override
    public void onSystemTextMessage(String message, SystemTextMessageType type) {
      systemMessages.add(message);
      if (type == SystemTextMessageType.ORDER_FAILURE) {
        orderFailures++;
      }
    }
  }

  private static final class RecordingInstrument implements Layer1ApiInstrumentAdapter {
    private final List<String> trace;
    private final List<String> added = new ArrayList<String>();
    private final List<String> removed = new ArrayList<String>();
    private velox.api.layer1.data.InstrumentInfo lastInfo;

    private RecordingInstrument(List<String> trace) {
      this.trace = trace;
    }

    @Override
    public void onInstrumentAdded(String alias, velox.api.layer1.data.InstrumentInfo info) {
      added.add(alias);
      lastInfo = info;
      trace.add("added:" + alias);
    }

    @Override
    public void onInstrumentRemoved(String alias) {
      removed.add(alias);
      trace.add("removed:" + alias);
    }
  }

  private static final class RecordingData implements Layer1ApiDataAdapter {
    private final List<String> trace;
    private final List<String> depths = new ArrayList<String>();
    private final List<String> trades = new ArrayList<String>();

    private RecordingData(List<String> trace) {
      this.trace = trace;
    }

    @Override
    public void onDepth(String alias, boolean bid, int price, int size) {
      String event = "depth:" + alias + ":" + price + ":" + size;
      depths.add(event);
      trace.add(event);
    }

    @Override
    public void onTrade(String alias, double price, int size, TradeInfo trade) {
      String event = "trade:" + alias + ":" + (int) price;
      trades.add(event);
      trace.add(event);
    }
  }

  private static final class EmptyOrder implements OrderSendParameters {
    @Override
    public String toString() {
      return "empty";
    }
  }
}
