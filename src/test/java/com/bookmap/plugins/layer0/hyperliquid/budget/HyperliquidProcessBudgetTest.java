package com.bookmap.plugins.layer0.hyperliquid.budget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.ConnectionPermit;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.Decision;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.FrameReservation;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.SubscriptionPermit;
import org.junit.Test;

/** Tests process-wide atomic Hyperliquid resource reservations. */
public class HyperliquidProcessBudgetTest {

  @Test
  public void reservesConnectionAttemptAndReconnectFramesAtomically() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(1, 2, 4, 4, 60_000);

    Decision<ConnectionPermit> first = budget.tryAcquireConnection(1_000, 3);

    assertTrue(first.acquired());
    assertFalse(budget.tryAcquireConnection(1_000, 1).acquired());
    first.permit().close();
    assertTrue(budget.tryAcquireConnection(1_000, 1).acquired());
  }

  @Test
  public void reservesAndReleasesTheSubscriptionPairAtomically() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 2, 2, 3, 60_000);

    Decision<SubscriptionPermit> first = budget.tryReserveSubscriptionPair(1_000);

    assertTrue(first.acquired());
    assertEquals(2, first.permit().slots());
    assertFalse(budget.tryReserveSubscriptionPair(1_000).acquired());
    first.permit().close();
    assertTrue(budget.tryReserveSubscriptionPair(1_000).acquired());
  }

  @Test
  public void sharesOneBudgetAcrossAllProviders() {
    assertSame(HyperliquidProcessBudget.shared(), HyperliquidProcessBudget.shared());
  }

  @Test
  public void closingPermitsTwiceReleasesOnlyTheirOriginalResources() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(1, 2, 2, 2, 60_000);
    ConnectionPermit connection = budget.tryAcquireConnection(0, 1).permit();
    SubscriptionPermit subscription = budget.tryReserveSubscriptionPair(0).permit();

    connection.close();
    connection.close();
    subscription.close();
    subscription.close();

    assertTrue(budget.tryAcquireConnection(1, 1).acquired());
    assertTrue(budget.tryReserveSubscriptionPair(1).acquired());
  }

  @Test
  public void failedConnectionReservationDoesNotConsumeAFrameSlot() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 1, 1, 2, 60_000);
    ConnectionPermit first = budget.tryAcquireConnection(0, 0).permit();

    Decision<ConnectionPermit> rejected = budget.tryAcquireConnection(1, 1);

    assertFalse(rejected.acquired());
    assertEquals(60_000, rejected.retryAtMillis());
    assertTrue(budget.tryAcquireFrames(1, 1).acquired());
    first.close();
  }

  @Test
  public void makesConnectionAttemptsAvailableAtTheRollingWindowBoundary() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 1, 2, 2, 60_000);
    ConnectionPermit first = budget.tryAcquireConnection(0, 0).permit();
    first.close();

    Decision<ConnectionPermit> beforeExpiry = budget.tryAcquireConnection(59_999, 0);

    assertFalse(beforeExpiry.acquired());
    assertEquals(60_000, beforeExpiry.retryAtMillis());
    assertTrue(budget.tryAcquireConnection(60_000, 0).acquired());
  }

  @Test
  public void chargesFramesFromTheMomentTheirSendStarts() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 2, 1, 2, 60_000);
    FrameReservation frame = budget.tryAcquireFrames(0, 1).permit();

    assertTrue(frame.takeFrame(9_000));
    Decision<FrameReservation> beforeExpiry = budget.tryAcquireFrames(68_999, 1);

    assertFalse(beforeExpiry.acquired());
    assertEquals(69_000, beforeExpiry.retryAtMillis());
    assertTrue(budget.tryAcquireFrames(69_000, 1).acquired());
  }

  @Test
  public void closingAnUnsentReservationFreesItsHeldSlotImmediately() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 2, 1, 2, 60_000);
    FrameReservation frame = budget.tryAcquireFrames(0, 1).permit();

    frame.close();

    assertTrue(budget.tryAcquireFrames(0, 1).acquired());
  }

  @Test
  public void rejectsAFrameRequestBeyondTheHardLimitWithoutMutation() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(2, 2, 2, 2, 60_000);

    Decision<FrameReservation> rejected = budget.tryAcquireFrames(1_000, 3);

    assertFalse(rejected.acquired());
    assertEquals(1_000, rejected.retryAtMillis());
    assertTrue(budget.tryAcquireFrames(1_000, 2).acquired());
  }

  @Test
  public void reportsTheCurrentTimeWhenConcurrentSocketLimitIsTheBlocker() {
    HyperliquidProcessBudget budget = new HyperliquidProcessBudget(1, 1, 2, 2, 60_000);
    ConnectionPermit first = budget.tryAcquireConnection(0, 0).permit();

    Decision<ConnectionPermit> rejected = budget.tryAcquireConnection(1_000, 0);

    assertFalse(rejected.acquired());
    assertEquals(1_000, rejected.retryAtMillis());
    first.close();
  }
}
