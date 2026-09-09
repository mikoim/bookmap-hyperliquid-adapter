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
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.lang.reflect.Constructor;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Focused lifecycle tests for session state transitions and reconnect barriers. */
public class HyperliquidSessionLifecycleTest {

  @Test
  public void staleBookWarnsOnceAndOnlyValidCurrentBookClearsIt() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.scheduler.advanceBy(29_999L);
    fixture.drain();
    assertTrue(fixture.sink.dataStatuses().isEmpty());
    fixture.scheduler.advanceBy(1L);
    fixture.drain();
    assertEquals(1, statusCount(fixture, "BOOK_STALE"));
    String warning = fixture.sink.dataStatuses().get(0);
    assertTrue(warning.contains("source=HYPERLIQUID"));
    assertTrue(warning.contains("symbol=BTC"));
    assertTrue(warning.contains("generation=1"));
    assertTrue(warning.contains("elapsedMillis=30000"));
    fixture.scheduler.advanceBy(1_000L);
    fixture.book(0L); // Rejected exchange timestamp must not refresh freshness.
    fixture.drain();
    assertEquals(1, statusCount(fixture, "BOOK_STALE"));
    assertEquals(0, statusCount(fixture, "BOOK_RESUMED"));
    fixture.book(2L); // Even an unchanged book is a valid fresh reception.
    fixture.drain();
    assertEquals(1, statusCount(fixture, "BOOK_RESUMED"));
    fixture.book(3L);
    fixture.drain();
    assertEquals(1, statusCount(fixture, "BOOK_RESUMED"));
  }

  @Test
  public void bookRecoveryNotificationWaitsForPublishedBookAfterAcknowledgements() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    assertEquals(1, statusCount(fixture, "BOOK_RESYNCING"));
    assertEquals(1, statusCount(fixture, "TRADE_GAP_POSSIBLE"));
    fixture.completeSends(2);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(0, statusCount(fixture, "BOOK_RESUMED"));
    fixture.book(2L);
    fixture.drain();
    assertEquals(1, statusCount(fixture, "BOOK_RESUMED"));
    assertTrue(
        fixture.sink.dataStatuses().toString().contains("historical trades are not backfilled"));
  }

  @Test
  public void oneDisconnectReportsEachHealthStateOnceForAllSymbols() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(3);
    fixture.subscribe("BTC");
    fixture.subscribe("C1");
    fixture.subscribe("C2");
    fixture.completeSends(6);
    for (String symbol : new String[] {"BTC", "C1", "C2"}) {
      fixture.book(symbol, "100.000", 1L);
      fixture.ack(symbol, SubscriptionType.L2_BOOK);
      fixture.ack(symbol, SubscriptionType.TRADES);
    }
    fixture.drain();

    fixture.beginRecovery();

    assertEquals(1, statusCount(fixture, "BOOK_RESYNCING"));
    assertEquals(1, statusCount(fixture, "TRADE_GAP_POSSIBLE"));
    assertTrue(
        fixture.sink.dataStatuses().toString(),
        fixture.sink.dataStatuses().get(0).contains("symbol=BTC,C1,C2"));
  }

  @Test
  public void disconnectIgnoresSubscriptionsThatNeverReceivedABook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);

    fixture.beginRecovery();

    assertEquals(0, statusCount(fixture, "BOOK_RESYNCING"));
    assertEquals(0, statusCount(fixture, "TRADE_GAP_POSSIBLE"));
  }

  @Test
  public void fatalDisconnectDoesNotPromiseRecovery() {
    Fixture fixture = fixtureWithActiveBtc();

    fixture.session.onDisconnected(
        1L, new TransportFailure(TransportFailure.Kind.PROTOCOL, "bad protocol", null));
    fixture.drain();

    assertTrue(fixture.sink.dataStatuses().toString(), fixture.sink.dataStatuses().isEmpty());
  }

  @Test
  public void restoredSubscriptionThatNeverReceivesABookStillReportsStaleness() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "connection-restored"));
    assertEquals(0, statusCount(fixture, "BOOK_STALE"));

    fixture.scheduler.advanceBy(30_000L);
    fixture.drain();

    assertEquals(1, statusCount(fixture, "BOOK_STALE"));
  }

  @Test
  public void unsubscribeAndCloseSuppressFreshnessTimers() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.session.unsubscribe("BTC");
    fixture.drain();
    fixture.scheduler.advanceBy(30_000L);
    fixture.drain();
    assertTrue(fixture.sink.dataStatuses().isEmpty());
    fixture.session.close();
    fixture.drain();
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertTrue(fixture.sink.dataStatuses().isEmpty());
  }

  @Test
  public void pendingTradeOverflowReportsPossibleGapOnce() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    for (int index = 0; index < 1_026; index++) {
      fixture.trade(1L, index);
    }
    fixture.drain();
    assertEquals(1, statusCount(fixture, "TRADE_GAP_POSSIBLE"));
  }

  private static int statusCount(Fixture fixture, String state) {
    int count = 0;
    for (String message : fixture.sink.dataStatuses()) {
      if (message.contains("state=" + state)) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void reconnectedSessionSurvivesRepeatedHeartbeats() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.book(2L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "connection-restored"));

    for (int cycle = 0; cycle < 10; cycle++) {
      fixture.scheduler.advanceBy(30_000L);
      fixture.drain();
      assertEquals(
          "heartbeat " + (cycle + 1) + ": " + fixture.sink.events(),
          0,
          count(fixture.sink.events(), "connection-lost:FATAL"));
      assertEquals(1, fixture.transport.socket().pendingSendCount());
      fixture.completeSends(1);
      assertEquals(
          cycle + 1,
          count(fixture.transport.socket().successfulSendBodies(), "{\"method\":\"ping\"}"));
      fixture.transport.emitTextFromConnection(1, "{\"channel\":\"pong\"}");
      fixture.drain();
      assertEquals(2, fixture.transport.connectCalls().size());
      assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    }

    // Drain the PONG deadline before advancing its possible reconnect backoff.
    fixture.scheduler.advanceBy(15_000L);
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.trade(3L, 99L);
    fixture.drain();
    assertEquals(1, fixture.sink.trades().size());
    assertEquals(2, fixture.transport.connectCalls().size());
    fixture.session.close();
    fixture.drain();
  }

  @Test
  public void invalidMetadataFailsFatallyWithoutOpeningWebSocket() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.completeMeta(
        200, TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"BTC\"}]}"));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:FATAL"));
    assertTrue(fixture.transport.connectCalls().isEmpty());
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void initialNetworkFailureIsOneShotAndNeverReconnects() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.session.onInitialFailure(
        new TransportFailure(TransportFailure.Kind.NETWORK, "offline", null));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:NO_INTERNET_CONNECTION"));
    fixture.session.onInitialFailure(
        new TransportFailure(TransportFailure.Kind.NETWORK, "late", null));
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "login-failed:NO_INTERNET_CONNECTION"));
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void metadataTimeoutIsClassifiedAsNoInternetAndDoesNotConnect() throws Exception {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.failMeta(new SocketTimeoutException("metadata timeout"));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:NO_INTERNET_CONNECTION"));
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void metadataHttpFailureIsFatalAndDoesNotConnect() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.completeMeta(503, "unavailable");
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:FATAL"));
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void initialHandshakeTimeoutIsNoInternetAndDoesNotReconnect() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC")));
    fixture.drain();
    fixture.clock.now = 10_000L;
    fixture.scheduler.advanceBy(10_000L);
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:NO_INTERNET_CONNECTION"));
    assertEquals(1, fixture.transport.connectCalls().size());
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertEquals(1, fixture.transport.connectCalls().size());
  }

  @Test
  public void initialHandshakeNetworkFailureIsNoInternetAndDoesNotReconnect() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC")));
    fixture.drain();
    fixture.transport.failSocket(new SocketTimeoutException("handshake failed"));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:NO_INTERNET_CONNECTION"));
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertEquals(1, fixture.transport.connectCalls().size());
  }

  @Test
  public void restorationWaitsForEveryCurrentGenerationAck() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    fixture.sink.events().clear();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.completeSends(1);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    fixture.completeSends(1);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "connection-restored"));
  }

  @Test
  public void multiAliasRestorationWaitsForEveryDefinitionAndIgnoresOldGeneration() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(2);
    fixture.subscribe("BTC");
    fixture.subscribe("C1");
    fixture.completeSends(4);
    fixture.book("BTC", "100.000", 1L);
    fixture.book("C1", "100.000", 1L);
    fixture.ack("BTC", SubscriptionType.L2_BOOK);
    fixture.ack("BTC", SubscriptionType.TRADES);
    fixture.ack("C1", SubscriptionType.L2_BOOK);
    fixture.ack("C1", SubscriptionType.TRADES);
    fixture.drain();
    fixture.sink.events().clear();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.completeSends(4);
    fixture.book("BTC", "101.000", 2L);
    fixture.session.onFrame(
        1L,
        "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\",\"side\":\"B\","
            + "\"px\":\"102.000\",\"sz\":\"1\",\"time\":3,\"tid\":99}]}");
    fixture.drain();
    fixture.ack("BTC", SubscriptionType.L2_BOOK);
    fixture.ack("BTC", SubscriptionType.TRADES);
    fixture.ack("C1", SubscriptionType.L2_BOOK);
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    fixture.ack("C1", SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "connection-restored"));
    assertEquals(0, fixture.sink.trades().size());
  }

  @Test
  public void pendingAliasDeadlineDuringRecoveryDoesNotRemoveActiveAlias() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(2);
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book("BTC", "100.000", 1L);
    fixture.ack("BTC", SubscriptionType.L2_BOOK);
    fixture.ack("BTC", SubscriptionType.TRADES);
    fixture.drain();
    fixture.subscribe("C1");
    fixture.drain();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.scheduler.advanceBy(10_000L);
    fixture.drain();

    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    assertTrue(fixture.sink.removedAliases().isEmpty());
    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
  }

  @Test
  public void overflowDuringRecoveryRestartsTheCurrentGeneration() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    int connectCalls = fixture.transport.connectCalls().size();

    fixture.session.onMarketOverflow();
    fixture.drain();
    fixture.scheduler.advanceBy(2_000L);
    fixture.drain();

    assertEquals(connectCalls + 1, fixture.transport.connectCalls().size());
    assertEquals(1, count(fixture.sink.events(), "connection-lost:UNKNOWN"));
    assertEquals(1, fixture.sink.systemMessages().size());
  }

  @Test
  public void recoveryPublishesFullBookAndBufferedTradesOnlyAfterTheBarrier() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    fixture.sink.events().clear();
    fixture.sink.trades().clear();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.completeSends(2);
    fixture.book("101.000", 2L);
    fixture.trade(2L, 7L);
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "connection-restored"));
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:0"));
    assertTrue(fixture.sink.events().contains("depth:BTC:1010000:100"));
    assertEquals(1, fixture.sink.trades().size());
  }

  @Test
  public void fatalDisconnectClearsActiveBooksRemovesAliasesAndReleasesPermits() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    fixture.sink.events().clear();

    fixture.session.onDisconnected(
        1L, new TransportFailure(TransportFailure.Kind.PROTOCOL, "bad protocol", null));
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "connection-lost:FATAL"));
    assertEquals(1, fixture.sink.removedAliases().size());
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:0"));
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertFalse(fixture.sink.events().contains("connection-restored"));
  }

  @Test
  public void closeIsIdempotentAndSuppressesLateFramesAndCallbacks() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    fixture.session.close();
    fixture.session.close();
    fixture.drain();
    int eventCount = fixture.sink.events().size();
    fixture.transport.openSocket();

    fixture.session.onFrame(
        1L,
        "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\",\"side\":\"B\","
            + "\"px\":\"100.000\",\"sz\":\"1\",\"time\":2,\"tid\":2}]}");
    fixture.session.onSocketOpened(3L);
    fixture.session.onInitialFailure(
        new TransportFailure(TransportFailure.Kind.NETWORK, "late", null));
    fixture.drain();

    assertEquals(eventCount, fixture.sink.events().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void partialRecoveryAckTimeoutReconnectsWithoutRestoring() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.scheduler.advanceBy(10_000L);
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    assertEquals(1, count(fixture.sink.events(), "connection-lost:UNKNOWN"));
  }

  @Test
  public void delayedWriteCallbackKeepsAckDeadlineAnchoredToSendStart() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.scheduler.advanceBy(9_999L);
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.scheduler.advanceBy(1L);
    fixture.drain();
    int reconnectsBeforeRetry = fixture.transport.connectCalls().size();
    fixture.scheduler.advanceBy(2_000L);
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    assertEquals(reconnectsBeforeRetry + 1, fixture.transport.connectCalls().size());
  }

  @Test
  public void partialRecoverySendFailurePreventsRestorationAndReconnects() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    fixture.transport.socket().failNextSend(new IllegalStateException("write failed"));
    fixture.drain();
    int reconnectsBeforeRetry = fixture.transport.connectCalls().size();
    fixture.scheduler.advanceBy(2_000L);
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    assertEquals(reconnectsBeforeRetry + 1, fixture.transport.connectCalls().size());
  }

  @Test
  public void partialRecoveryConnectionFailurePreventsRestorationAndReconnects() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    fixture.session.onDisconnected(
        2L, new TransportFailure(TransportFailure.Kind.NETWORK, "connection failed", null));
    fixture.drain();

    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    assertEquals(1, count(fixture.sink.events(), "connection-lost:UNKNOWN"));
  }

  @Test
  public void staleGenerationPendingSendTimerAndSocketCallbackLeaveReplacementUntouched() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.beginRecovery();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    Runnable oldGenerationAcknowledgement =
        fixture.scheduler.takeTaskAt(fixture.clock.now + 10_000L);
    assertTrue(oldGenerationAcknowledgement != null);
    assertEquals(1, fixture.transport.socket().pendingSendCount());

    fixture.transport.remoteClose(1006, "replace generation");
    fixture.drain();
    fixture.scheduler.advanceBy(2_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 3L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();

    int eventsBeforeStaleCallbacks = fixture.sink.events().size();
    int connectsBeforeStaleCallbacks = fixture.transport.connectCalls().size();
    int activeTimersBeforeStaleCallbacks = fixture.scheduler.activeTaskCount();
    fixture.transport.socket().succeedNextSend();
    oldGenerationAcknowledgement.run();
    fixture.transport.emitTextFromConnection(
        1,
        "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\",\"side\":\"B\","
            + "\"px\":\"101.000\",\"sz\":\"1\",\"time\":2,\"tid\":2}]}");
    fixture.drain();

    assertEquals(eventsBeforeStaleCallbacks, fixture.sink.events().size());
    assertEquals(connectsBeforeStaleCallbacks, fixture.transport.connectCalls().size());
    assertEquals(activeTimersBeforeStaleCallbacks, fixture.scheduler.activeTaskCount());
  }

  @Test
  public void fullDispatcherOverflowRejectsWholeFrameAndSignalsAgainAfterReplacementOpen() {
    Fixture fixture = fixtureWithActiveBtc();
    for (int index = 0; index < 4_096; index++) {
      assertTrue(
          fixture.dispatcher.submitMarketFrame(
              1,
              new Runnable() {
                @Override
                public void run() {
                  throw new AssertionError("queued market frame must be discarded");
                }
              }));
    }
    assertFalse(
        fixture.dispatcher.submitMarketFrame(
            2,
            new Runnable() {
              @Override
              public void run() {
                throw new AssertionError("partial frame must never run");
              }
            }));
    fixture.drain();
    assertEquals(1, fixture.sink.systemMessages().size());

    fixture.scheduler.advanceBy(2_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 3L;
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    for (int index = 0; index < 4_096; index++) {
      assertTrue(
          fixture.dispatcher.submitMarketFrame(
              1,
              new Runnable() {
                @Override
                public void run() {
                  throw new AssertionError("queued market frame must be discarded");
                }
              }));
    }
    assertFalse(
        fixture.dispatcher.submitMarketFrame(
            2,
            new Runnable() {
              @Override
              public void run() {
                throw new AssertionError("partial frame must never run");
              }
            }));
    fixture.drain();
    assertEquals(2, fixture.sink.systemMessages().size());
  }

  @Test
  public void closeDuringMetadataCompletionSuppressesLateLoginAndConnect() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.session.close();
    fixture.drain();
    fixture.transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC")));
    fixture.drain();

    assertTrue(fixture.sink.events().isEmpty());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void closeDuringReconnectAndActivationTimersSuppressesLateCallbacks() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.session.close();
    fixture.drain();
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();

    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertTrue(fixture.sink.addedAliases().isEmpty());
  }

  @Test
  public void closeWithPendingReconnectTimerPreventsAnotherConnectionAttempt() {
    Fixture fixture = fixtureWithActiveBtc();
    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    Runnable reconnect = fixture.scheduler.takeTaskAt(fixture.clock.now + 1_000L);
    assertTrue(reconnect != null);

    int connectsBeforeClose = fixture.transport.connectCalls().size();
    fixture.session.close();
    fixture.drain();
    int eventsAfterClose = fixture.sink.events().size();
    reconnect.run();
    fixture.drain();

    assertEquals(connectsBeforeClose, fixture.transport.connectCalls().size());
    assertEquals(eventsAfterClose, fixture.sink.events().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void closeWithQueuedHeartbeatCallbackSuppressesHeartbeatSend() {
    Fixture fixture = fixtureWithActiveBtc();
    Runnable heartbeat = fixture.scheduler.takeTaskAt(fixture.clock.now + 30_000L);
    assertTrue(heartbeat != null);
    assertEquals(0, fixture.transport.socket().pendingSendCount());

    fixture.session.close();
    fixture.drain();
    int eventsAfterClose = fixture.sink.events().size();
    heartbeat.run();
    fixture.drain();

    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(0, fixture.transport.socket().pendingSendCount());
    assertEquals(eventsAfterClose, fixture.sink.events().size());
  }

  @Test
  public void closeWithQueuedOverflowControlPreventsOverflowReconnectAfterShutdown() {
    Fixture fixture = fixtureWithActiveBtc();
    for (int index = 0; index < 4_096; index++) {
      assertTrue(fixture.dispatcher.submitMarketFrame(1, ignoredMarketFrame()));
    }
    assertFalse(fixture.dispatcher.submitMarketFrame(1, ignoredMarketFrame()));
    assertEquals(1, fixture.executor.queuedTaskCount());

    int connectsBeforeClose = fixture.transport.connectCalls().size();
    fixture.session.close();
    fixture.drain();
    int eventsAfterClose = fixture.sink.events().size();
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();

    assertTrue(fixture.sink.systemMessages().isEmpty());
    assertEquals(connectsBeforeClose, fixture.transport.connectCalls().size());
    assertEquals(eventsAfterClose, fixture.sink.events().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  private static Runnable ignoredMarketFrame() {
    return new Runnable() {
      @Override
      public void run() {
        throw new AssertionError("queued market frame must be discarded");
      }
    };
  }

  private static Fixture fixtureWithActiveBtc() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    return fixture;
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
    private final AtomicReference<HyperliquidSession> overflowSession =
        new AtomicReference<HyperliquidSession>();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                HyperliquidSession session = overflowSession.get();
                if (session != null) {
                  session.onMarketOverflow();
                }
              }
            });
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private long generation = 1L;
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
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });

    {
      overflowSession.set(session);
    }

    private void login() {
      loginWithSymbols(1);
    }

    private void loginWithSymbols(int count) {
      startLogin();
      StringBuilder metadata = new StringBuilder("{\"universe\":[");
      for (int index = 0; index < count; index++) {
        if (index > 0) {
          metadata.append(',');
        }
        metadata.append("{\"name\":\"");
        metadata.append(index == 0 ? "BTC" : "C" + index);
        metadata.append("\",\"szDecimals\":2}");
      }
      metadata.append("]}");
      transport.completeMeta(200, TestMetadata.allPerpMetas(metadata.toString()));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    private void startLogin() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
    }

    private void subscribe(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
      drain();
    }

    private void beginRecovery() {
      transport.remoteClose(1006, "lost");
      drain();
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation = 2L;
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    private void completeSends(int count) {
      for (int index = 0; index < count; index++) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void book(long time) {
      book("BTC", "100.000", time);
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

    private void trade(long time, long tid) {
      session.onFrame(
          generation,
          "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\",\"side\":\"B\","
              + "\"px\":\"101.000\",\"sz\":\"1\",\"time\":"
              + time
              + ",\"tid\":"
              + tid
              + "}]}");
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

    private int queuedTaskCount() {
      return tasks.size();
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
      clock.now += millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= clock.now && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          return;
        }
        tasks.remove(due);
        due.task.run();
      }
    }

    private Runnable takeTaskAt(long due) {
      for (int index = 0; index < tasks.size(); index++) {
        Task task = tasks.get(index);
        if (!task.cancelled && task.due == due) {
          tasks.remove(index);
          return task.task;
        }
      }
      return null;
    }

    private int activeTaskCount() {
      int count = 0;
      for (Task task : tasks) {
        if (!task.cancelled) {
          count++;
        }
      }
      return count;
    }

    private static final class Task implements Cancellable, Comparable<Task> {
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

      @Override
      public int compareTo(Task other) {
        return Long.compare(due, other.due);
      }
    }
  }
}
