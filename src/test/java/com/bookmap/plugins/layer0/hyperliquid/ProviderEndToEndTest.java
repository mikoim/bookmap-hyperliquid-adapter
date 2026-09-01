package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSession;
import com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionApi;
import com.bookmap.plugins.layer0.hyperliquid.session.SessionSink;
import java.lang.reflect.Constructor;
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
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();

    assertEquals(
        Arrays.asList("login", "added:BTC", "depth:BTC", "depth:BTC", "trade:BTC"), fixture.trace);
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
    fixture.advance(1_000L);
    fixture.transport.openSocket();
    fixture.drain();
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

    for (int i = 0; i < 4_096; i++) {
      fixture.transport.emitTextFromConnection(
          0, fixture.bookJson("BTC", i + 2L, "100", "1", "101", "2"));
    }
    fixture.transport.emitTextFromConnection(
        0, fixture.bookJson("BTC", 10_000L, "300", "1", "301", "2"));
    fixture.drain();
    assertTrue(fixture.admin.systemMessages.contains("market-data frame queue overflow"));
    assertEquals(depthsBeforeInvalid, fixture.data.depths.size());
    assertEquals(1, fixture.transport.connectCalls().size());
    assertEquals(1_000L, fixture.scheduler.nextDelayMillis());
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
    HyperliquidProcessBudget budget = budget(1, 1, 2, 2);
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
    second.subscribe("BTC");
    second.drain();
    assertEquals(1, first.transport.connectCalls().size());
    assertEquals(0, second.transport.connectCalls().size());
    assertEquals(2, budget.reservedSubscriptionSlots());
    assertEquals(0, second.executor.queuedTaskCount());

    first.closeTwice();
    second.closeTwice();
    first.assertClosed();
    second.assertClosed();
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

  private static final class Fixture {
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
      transport.emitTextFromConnection(
          transport.connectCalls().size() - 1,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\",\"subscription\":{\"type\":\""
              + type
              + "\",\"coin\":\""
              + coin
              + "\"}}}");
      drain();
    }

    private void book(
        String coin, long time, String bidPrice, String bidSize, String askPrice, String askSize) {
      transport.emitTextFromConnection(
          transport.connectCalls().size() - 1,
          bookJson(coin, time, bidPrice, bidSize, askPrice, askSize));
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
          transport.connectCalls().size() - 1,
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

    private void error(String coin) {
      String target =
          coin == null ? "" : ",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"" + coin + "\"}";
      transport.emitTextFromConnection(
          transport.connectCalls().size() - 1,
          "{\"channel\":\"error\",\"data\":{\"message\":\"bad\"" + target + "}}");
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
      assertTrue(executor.shutdownRequested);
      assertTrue(
          transport.httpCancelled()
              || transport.connectCancelled()
              || !transport.socket().isOpen());
      assertEquals(-1L, scheduler.nextDelayMillis());
      assertEquals(0, budget.reservedSubscriptionSlots());
      int eventCount = trace.size();
      if (!transport.connectCalls().isEmpty()) {
        transport.emitTextFromConnection(0, "{\"channel\":\"trades\",\"data\":[]}");
        drain();
      }
      assertEquals(eventCount, trace.size());
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
  }

  private static String metadata(String... symbols) {
    StringBuilder result = new StringBuilder("{\"universe\":[");
    for (int i = 0; i < symbols.length; i++) {
      if (i != 0) {
        result.append(',');
      }
      result.append("{\"name\":\"").append(symbols[i]).append("\",\"szDecimals\":2}");
    }
    return result.append("]}").toString();
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

    private RecordingInstrument(List<String> trace) {
      this.trace = trace;
    }

    @Override
    public void onInstrumentAdded(String alias, velox.api.layer1.data.InstrumentInfo info) {
      added.add(alias);
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
      trace.add("trade:" + alias);
    }
  }

  private static final class EmptyOrder implements OrderSendParameters {}
}
