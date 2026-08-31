package com.bookmap.plugins.layer0.hyperliquid.trade;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;
import org.junit.Test;

/** Tests bounded best-effort suppression of duplicate trade notifications. */
public class BoundedTradeDeduplicatorTest {

  @Test
  public void suppressesDuplicatesUntilTheirOriginalTimestampExpires() {
    BoundedTradeDeduplicator dedup = new BoundedTradeDeduplicator(3, 1_000);
    TradeKey first = key(1);

    assertTrue(dedup.markIfNew(first, 0));
    assertFalse(dedup.markIfNew(first, 999));
    assertTrue(dedup.markIfNew(first, 1_000));
  }

  @Test
  public void containsDoesNotInsertAKeyThatWasNotCommitted() {
    BoundedTradeDeduplicator dedup = new BoundedTradeDeduplicator(3, 1_000);
    TradeKey pending = key(1);

    assertFalse(dedup.contains(pending, 0));
    assertTrue(dedup.markIfNew(pending, 1));
    assertTrue(dedup.contains(pending, 1));
  }

  @Test
  public void capacityEvictionAllowsTheEldestNotificationToBeUsedAgain() {
    BoundedTradeDeduplicator dedup = new BoundedTradeDeduplicator(2, 1_000);

    assertTrue(dedup.markIfNew(key(1), 0));
    assertTrue(dedup.markIfNew(key(2), 1));
    assertTrue(dedup.markIfNew(key(3), 2));
    assertTrue(dedup.markIfNew(key(1), 3));
  }

  @Test
  public void duplicateAccessMakesTheAccessedEntryMostRecentForCapacityEviction() {
    BoundedTradeDeduplicator dedup = new BoundedTradeDeduplicator(2, 1_000);

    assertTrue(dedup.markIfNew(key(1), 0));
    assertTrue(dedup.markIfNew(key(2), 1));
    assertTrue(dedup.contains(key(1), 2));
    assertTrue(dedup.markIfNew(key(3), 3));
    assertTrue(dedup.contains(key(1), 4));
    assertTrue(dedup.markIfNew(key(2), 5));
  }

  @Test
  public void clearForgetsAllCommittedNotifications() {
    BoundedTradeDeduplicator dedup = new BoundedTradeDeduplicator(3, 1_000);
    TradeKey first = key(1);

    assertTrue(dedup.markIfNew(first, 0));
    dedup.clear();
    assertTrue(dedup.markIfNew(first, 1));
  }

  private static TradeKey key(long tid) {
    return new TradeKey("BTC", 1, tid);
  }
}
