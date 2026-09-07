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
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.SubscriptionPermit;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Exercises subscription activation and market delivery through the real connector boundary. */
public class HyperliquidSessionSubscriptionTest {

  @Test
  public void addsOnlyAfterBothAcknowledgementsAndTheLatestValidBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();
    fixture.sink.events().clear();

    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();

    fixture.receive(book("BTC", 1L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());

    fixture.receive(book("BTC", 2L, "101.000", "2"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    assertEquals(
        Arrays.asList("instrument-added:BTC", "depth:BTC:1010000:200"), fixture.sink.events());
  }

  @Test
  public void invalidTradeDoesNotSuppressLaterValidTradeInTheSameFrame() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();

    fixture.receive(
        trades(
            trade("BTC", "B", "100.000", "0", 1L, 1L), trade("BTC", "A", "101.000", "1", 2L, 2L)));
    fixture.drain();

    assertEquals(1, fixture.sink.trades().size());
    assertFalse(fixture.sink.trades().get(0).isBuyAggressor());
    assertEquals(1010000d, fixture.sink.trades().get(0).price(), 0d);
  }

  @Test
  public void activationDeadlineNeverResetsAndReleasesBothSlots() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();

    fixture.advanceBy(9_000L);
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.session.onDisconnected(
        fixture.generation,
        new TransportFailure(TransportFailure.Kind.NETWORK, "connection lost", null));
    fixture.drain();
    fixture.advanceBy(1_000L);
    fixture.drain();
    fixture.completeSubscriptionSends();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList(
            new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).unsubscribeJson(),
            new SubscriptionKey("BTC", SubscriptionType.TRADES).unsubscribeJson()),
        fixture.transport.socket().successfulSendBodies());
  }

  @Test
  public void activationAtExactDeadlineIsRejectedAndReleasesBothSlots() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();

    fixture.clock.now = 10_000L;
    fixture.receive(book("BTC", 1L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    fixture.scheduler.advanceBy(0L);
    fixture.drain();
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void frameFromDisconnectedGenerationCannotDeliverMarketData() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.session.onDisconnected(
        fixture.generation,
        new TransportFailure(TransportFailure.Kind.NETWORK, "connection lost", null));
    fixture.drain();

    fixture.receive(trades(trade("BTC", "A", "101.000", "1", 2L, 2L)));
    fixture.drain();

    assertEquals(0, fixture.sink.trades().size());
  }

  @Test
  public void pendingTradesStayFifoAndCommitOnlyWhenActivationPublishes() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();

    String first = trade("BTC", "B", "100.000", "1", 1L, 1L);
    String second = trade("BTC", "A", "101.000", "1", 2L, 2L);
    fixture.receive(trades(first, first, second));
    fixture.drain();
    assertTrue(fixture.sink.trades().isEmpty());

    fixture.receive(book("BTC", 3L, "102.000", "1"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(2, fixture.sink.trades().size());
    assertEquals(1_000_000d, fixture.sink.trades().get(0).price(), 0d);
    assertEquals(1_010_000d, fixture.sink.trades().get(1).price(), 0d);
    assertFalse(fixture.sink.trades().get(1).isBuyAggressor());
  }

  @Test
  public void unsupportedActiveDepthClearsBookAndRemovesOnlyAlias() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();
    fixture.receive(book("BTC", 2L, "999999999999.000", "1"));
    fixture.drain();

    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "instrument-removed:BTC"), fixture.sink.events());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void everyActivationPermutationWaitsForAllThreeConditions() {
    String[][] orders = {
      {"book", "l2", "trades"},
      {"book", "trades", "l2"},
      {"l2", "book", "trades"},
      {"l2", "trades", "book"},
      {"trades", "book", "l2"},
      {"trades", "l2", "book"}
    };
    for (String[] order : orders) {
      Fixture fixture = new Fixture();
      fixture.login();
      fixture.session.subscribe("BTC", "", "PERPETUAL");
      fixture.drain();
      fixture.completeSubscriptionSends();
      String[] conditions = {"book", "l2", "trades"};
      for (int index = 0; index < order.length; index++) {
        String condition = order[index];
        fixture.receiveCondition(condition);
        fixture.drain();
        if (index < conditions.length - 1) {
          assertTrue(fixture.sink.addedAliases().isEmpty());
        }
      }
      assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    }
  }

  @Test
  public void unsolicitedAckBeforeSendDoesNotOpenActivationGate() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.receiveAt(0L, ack(SubscriptionType.L2_BOOK));
    fixture.receive(book("BTC", 1L, "100.000", "1"));
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());

    fixture.completeSubscriptionSends();
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
  }

  @Test
  public void pendingTradeBufferDropsOnlyAfterIts1024thUniqueTrade() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();

    String[] pending = new String[1_025];
    for (int index = 0; index < pending.length; index++) {
      pending[index] = trade("BTC", "B", "100.000", "1", index + 1L, index + 1L);
    }
    fixture.receive(trades(pending));
    fixture.drain();
    assertEquals(1, countContaining(fixture.sink.events(), "buffer full"));
    assertTrue(fixture.sink.trades().isEmpty());

    fixture.receive(book("BTC", 2_000L, "102.000", "1"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(1_024, fixture.sink.trades().size());
    assertEquals(1_000_000d, fixture.sink.trades().get(0).price(), 0d);
  }

  @Test
  public void notFoundAndDuplicateRequestsDoNotReserveOrSend() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();
    fixture.session.subscribe("UNKNOWN", "", "PERPETUAL");
    fixture.session.subscribe("BTC", "", "SPOT");
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList("not-found:UNKNOWN", "not-found:BTC", "already-subscribed:BTC"),
        fixture.sink.events());
  }

  @Test
  public void laterStaleInvalidAndUnknownBookEventsDoNotMutateActiveBook() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();
    fixture.receive(book("BTC", 0L, "100.000", "2"));
    fixture.receive("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\"}}");
    fixture.receive(book("ETH", 2L, "100.000", "1"));
    fixture.drain();
    assertEquals(2, countStartingWith(fixture.sink.events(), "diagnostic:"));
    assertEquals(2, fixture.sink.events().size());
  }

  @Test
  public void activeTradesPreserveFrameOrderAndSuppressDuplicateKeys() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.trades().clear();
    String first = trade("BTC", "B", "100.000", "1", 4L, 4L);
    String second = trade("BTC", "A", "101.000", "2", 5L, 5L);
    fixture.receive(trades(first, second, first));
    fixture.drain();
    assertEquals(2, fixture.sink.trades().size());
    assertEquals(1_000_000d, fixture.sink.trades().get(0).price(), 0d);
    assertEquals(1_010_000d, fixture.sink.trades().get(1).price(), 0d);
  }

  @Test
  public void resubscriptionStartsWithAnEmptyDiffBaseline() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.session.unsubscribe("BTC");
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.sink.events().clear();
    fixture.receive(book("BTC", 2L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:BTC", "depth:BTC:1000000:100"), fixture.sink.events());
  }

  @Test
  public void recoveryCandidateDoesNotMutateLiveBookTimeOrBaseline() {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 2);
    HyperliquidProcessBudget budget = budget();
    SubscriptionPermit permit = budget.tryReserveSubscriptionPair(0L).permit();
    SubscriptionRecord record =
        new SubscriptionRecord(
            "BTC",
            instrument,
            permit,
            10_000L,
            SourceProfile.FeedMode.SNAPSHOT,
            L2BookParameters.NONE);
    record.transitionToActive();
    record.beginGeneration(7L);
    OrderBookSnapshotDiff source = new OrderBookSnapshotDiff(instrument);
    BookSnapshot snapshot =
        new BookSnapshot(
            "BTC",
            5L,
            Arrays.asList(
                new BookLevel(new java.math.BigDecimal("100.000"), new java.math.BigDecimal("1"))),
            java.util.Collections.<BookLevel>emptyList());
    OrderBookSnapshotDiff.SnapshotValidation validation = source.validate(snapshot, -1L);

    assertTrue(record.acceptRecoveryBook(7L, validation.snapshot()));
    assertEquals(5L, record.recoveryBookTime());
    assertEquals(-1L, record.lastAcceptedBookTime());
    assertEquals(validation.snapshot(), record.recoveryBook());
    record.clearRecoveryBook();
    assertTrue(record.recoveryBook() == null);
    assertEquals(-1L, record.recoveryBookTime());
    record.remove();
  }

  @Test
  public void pendingTimeoutUnsubscribesBothFeedsWithoutRemovingInstrument() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();

    fixture.advanceBy(10_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertTrue(fixture.sink.removedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList(
            new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).unsubscribeJson(),
            new SubscriptionKey("BTC", SubscriptionType.TRADES).unsubscribeJson()),
        fixture.transport.socket().successfulSendBodies());
  }

  @Test
  public void pendingRejectionReleasesPermitAndRemovesCounterpart() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.receive(error(SubscriptionType.L2_BOOK));
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertTrue(fixture.sink.removedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(1, fixture.sink.systemMessages().size());
  }

  @Test
  public void unsupportedInitialDepthRemovesPendingAliasImmediately() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.receive(book("BTC", 1L, "999999999999.000", "1"));
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertTrue(fixture.sink.removedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void untargetableSubscriptionErrorUsesFatalLifecyclePath() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.receive(error(null));
    fixture.drain();
    assertFalse(fixture.sink.events().contains("login-failed:FATAL"));
    assertEquals(1, countContaining(fixture.sink.events(), "connection-lost:FATAL"));
    int eventCount = fixture.sink.events().size();
    fixture.receive(error(null));
    fixture.drain();
    assertEquals(eventCount, fixture.sink.events().size());
  }

  @Test
  public void sharedSubscriptionLimitRejectsThe501stAliasBeforeSending() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(501);
    for (int index = 0; index < 501; index++) {
      fixture.session.subscribe(index == 0 ? "BTC" : "C" + index, "", "PERPETUAL");
      fixture.drain();
    }
    assertEquals(1_000, fixture.budget.reservedSubscriptionSlots());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(1, countContaining(fixture.sink.systemMessages(), "SUBSCRIPTION_LIMIT"));
    assertEquals(1_000, fixture.transport.socket().pendingSendCount());
  }

  @Test
  public void targetablePendingRejectionLeavesOtherAliasManaged() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(2);
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.session.subscribe("C1", "", "PERPETUAL");
    fixture.drain();
    fixture.receive(error(SubscriptionType.L2_BOOK));
    fixture.drain();
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    assertTrue(fixture.sink.removedAliases().isEmpty());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(6, fixture.transport.socket().pendingSendCount());
  }

  @Test
  public void pendingUserUnsubscribeReleasesSlotsAndDoesNotNotifyRemoval() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.session.unsubscribe("BTC");
    fixture.drain();
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertTrue(fixture.sink.removedAliases().isEmpty());
    fixture.session.unsubscribe("UNKNOWN");
    fixture.drain();
    assertEquals(0, fixture.sink.removedAliases().size());
  }

  @Test
  public void pendingDuplicateKeyCanBePublishedAfterRemovalAndResubscription() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    String duplicate = trade("BTC", "B", "100.000", "1", 9L, 9L);
    fixture.receive(trades(duplicate));
    fixture.drain();
    fixture.session.unsubscribe("BTC");
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.receive(trades(duplicate));
    fixture.receive(book("BTC", 10L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(1, fixture.sink.trades().size());
  }

  @Test
  public void activationPublishesAllInitialLevelsThenPendingTradesInOrder() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    String first = trade("BTC", "B", "100.000", "1", 11L, 11L);
    String second = trade("BTC", "A", "102.000", "2", 12L, 12L);
    fixture.receive(trades(first, second));
    fixture.receive(multiBook("BTC", 3L));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "instrument-added:BTC",
            "depth:BTC:1000000:100",
            "depth:BTC:1010000:200",
            "depth:BTC:1020000:300",
            "depth:BTC:1030000:400",
            "trade:BTC:1000000.0:100",
            "trade:BTC:1020000.0:200"),
        fixture.sink.events());
  }

  @Test
  public void pendingStaleInvalidAndForeignBooksCannotReplaceCandidate() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.receive(book("BTC", 5L, "100.000", "1"));
    fixture.drain();
    fixture.receive(book("BTC", 4L, "101.000", "2"));
    fixture.receive("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\"}}");
    fixture.receive(book("ETH", 6L, "101.000", "2"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:100"));
    assertFalse(fixture.sink.events().contains("depth:BTC:1010000:200"));
  }

  @Test
  public void activeStaleInvalidAndForeignBooksLeaveBaselineForLaterDiff() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();
    fixture.receive(book("BTC", 0L, "100.000", "2"));
    fixture.receive("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\"}}");
    fixture.receive(book("ETH", 2L, "101.000", "2"));
    fixture.receive(book("BTC", 2L, "101.000", "2"));
    fixture.drain();
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:0"));
    assertTrue(fixture.sink.events().contains("depth:BTC:1010000:200"));
    assertFalse(fixture.sink.events().contains("depth:BTC:1000000:200"));
  }

  @Test
  public void activeTargetableRejectionClearsOnlyTargetAliasAndUnsubscribesBothFeeds() {
    Fixture fixture = new Fixture();
    fixture.loginWithSymbols(2);
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.session.subscribe("C1", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends(4);
    fixture.receive(bookFor("BTC", 1L, "100.000", "1"));
    fixture.receive(ackFor("BTC", SubscriptionType.L2_BOOK));
    fixture.receive(ackFor("BTC", SubscriptionType.TRADES));
    fixture.receive(bookFor("C1", 1L, "100.000", "1"));
    fixture.receive(ackFor("C1", SubscriptionType.L2_BOOK));
    fixture.receive(ackFor("C1", SubscriptionType.TRADES));
    fixture.drain();
    fixture.sink.events().clear();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.receive(errorFor("BTC", SubscriptionType.L2_BOOK));
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertEquals(Arrays.asList("BTC"), fixture.sink.removedAliases());
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList(
            new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).unsubscribeJson(),
            new SubscriptionKey("BTC", SubscriptionType.TRADES).unsubscribeJson()),
        fixture.transport.socket().successfulSendBodies());
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:0"));
  }

  @Test
  public void activeUserUnsubscribeRemovesOnceWithoutPublishingDepth() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.session.unsubscribe("BTC");
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();
    assertEquals(Arrays.asList("BTC"), fixture.sink.removedAliases());
    assertTrue(fixture.sink.events().contains("instrument-removed:BTC"));
    assertFalse(fixture.sink.events().toString().contains("depth:"));
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList(
            new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).unsubscribeJson(),
            new SubscriptionKey("BTC", SubscriptionType.TRADES).unsubscribeJson()),
        fixture.transport.socket().successfulSendBodies());
  }

  @Test
  public void pendingDeadlineSurvivesAReconnectGeneration() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.advanceBy(9_000L);
    fixture.session.onDisconnected(
        fixture.generation,
        new TransportFailure(TransportFailure.Kind.NETWORK, "connection lost", null));
    fixture.drain();
    fixture.session.onSocketOpened(2L);
    fixture.drain();
    fixture.advanceBy(1_000L);
    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  private static String ack(SubscriptionType type) {
    return ackFor("BTC", type);
  }

  private static String ackFor(String coin, SubscriptionType type) {
    String prefix =
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"";
    return prefix + type.wireName() + "\",\"coin\":\"" + coin + "\"}}}";
  }

  private static String book(String coin, long time, String price, String size) {
    return bookFor(coin, time, price, size);
  }

  private static String bookFor(String coin, long time, String price, String size) {
    return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
        + coin
        + "\",\"time\":"
        + time
        + ",\"levels\":[[{\"px\":\""
        + price
        + "\",\"sz\":\""
        + size
        + "\"}],[]]}}";
  }

  private static String multiBook(String coin, long time) {
    return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
        + coin
        + "\",\"time\":"
        + time
        + ",\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"},{\"px\":\"101.000\",\"sz\":\"2\"}],"
        + "[{\"px\":\"102.000\",\"sz\":\"3\"},{\"px\":\"103.000\",\"sz\":\"4\"}]]}}";
  }

  private static String error(SubscriptionType type) {
    return errorFor("BTC", type);
  }

  private static String errorFor(String coin, SubscriptionType type) {
    if (type == null) {
      return "{\"channel\":\"error\",\"data\":{}}";
    }
    return "{\"channel\":\"error\",\"data\":{\"subscription\":{\"type\":\""
        + type.wireName()
        + "\",\"coin\":\""
        + coin
        + "\"}}}";
  }

  private static int countContaining(List<String> values, String expectedPart) {
    int count = 0;
    for (String value : values) {
      if (value.contains(expectedPart)) {
        count++;
      }
    }
    return count;
  }

  private static int countStartingWith(List<String> values, String expectedPrefix) {
    int count = 0;
    for (String value : values) {
      if (value.startsWith(expectedPrefix)) {
        count++;
      }
    }
    return count;
  }

  private static String trades(String... trades) {
    return "{\"channel\":\"trades\",\"data\":[" + String.join(",", trades) + "]}";
  }

  private static String trade(
      String coin, String side, String price, String size, long time, long tid) {
    return "{\"coin\":\""
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

  private static void noop() {
    // The fixture does not need overflow handling.
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionSubscriptionTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            HyperliquidSessionSubscriptionTest::noop);
    private long generation = 1L;

    void login() {
      loginWithSymbols(1);
    }

    void loginWithSymbols(int count) {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
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
      transport.completeMeta(200, TestMetadata.wrap(metadata.toString()));
      drain();
      transport.openSocket();
      drain();
    }

    void activateBtc() {
      login();
      session.subscribe("BTC", "", "PERPETUAL");
      drain();
      completeSubscriptionSends();
      receive(book("BTC", 1L, "100.000", "1"));
      receive(ack(SubscriptionType.L2_BOOK));
      receive(ack(SubscriptionType.TRADES));
      drain();
    }

    void receive(String frame) {
      connectorListenerFrame(generation, frame);
    }

    void receiveAt(long callbackGeneration, String frame) {
      connectorListenerFrame(callbackGeneration, frame);
    }

    void receiveCondition(String condition) {
      if ("book".equals(condition)) {
        receive(book("BTC", 1L, "100.000", "1"));
      } else {
        receive(ack("l2".equals(condition) ? SubscriptionType.L2_BOOK : SubscriptionType.TRADES));
      }
    }

    void connectorListenerFrame(long callbackGeneration, String frame) {
      session.onFrame(callbackGeneration, frame);
    }

    void completeSubscriptionSends() {
      completeSubscriptionSends(2);
    }

    void completeSubscriptionSends(int count) {
      for (int index = 0; index < count; index++) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    void advanceBy(long elapsed) {
      clock.now += elapsed;
      scheduler.advanceBy(elapsed);
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
    private final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<Task>();
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
        Task next = tasks.remove();
        if (!next.cancelled) {
          next.task.run();
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
