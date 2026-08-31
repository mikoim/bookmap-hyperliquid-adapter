package com.bookmap.plugins.layer0.hyperliquid.trade;

import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, best-effort TTL and access-order duplicate suppression for trade notifications. */
public final class BoundedTradeDeduplicator {

  private final int capacity;
  private final long ttlMillis;
  private final LinkedHashMap<TradeKey, Long> seen =
      new LinkedHashMap<TradeKey, Long>(16, 0.75f, true);

  /** Creates a deduplicator with a positive capacity and a non-negative TTL. */
  public BoundedTradeDeduplicator(int capacity, long ttlMillis) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    if (ttlMillis < 0) {
      throw new IllegalArgumentException("ttlMillis must be non-negative");
    }
    this.capacity = capacity;
    this.ttlMillis = ttlMillis;
  }

  /** Returns whether a committed notification is still remembered, without adding a pending key. */
  public synchronized boolean contains(TradeKey key, long nowMillis) {
    purgeExpired(nowMillis);
    return seen.get(Objects.requireNonNull(key, "key")) != null;
  }

  /**
   * Marks a notification as committed when it is new, or returns false when it is still remembered.
   */
  public synchronized boolean markIfNew(TradeKey key, long nowMillis) {
    purgeExpired(nowMillis);
    if (seen.get(Objects.requireNonNull(key, "key")) != null) {
      return false;
    }
    seen.put(key, Long.valueOf(nowMillis));
    if (seen.size() > capacity) {
      Iterator<TradeKey> eldest = seen.keySet().iterator();
      eldest.next();
      eldest.remove();
    }
    return true;
  }

  /** Forgets every committed notification. */
  public synchronized void clear() {
    seen.clear();
  }

  private void purgeExpired(long nowMillis) {
    long expiryCutoff = nowMillis - ttlMillis;
    Iterator<Map.Entry<TradeKey, Long>> entries = seen.entrySet().iterator();
    while (entries.hasNext()) {
      if (entries.next().getValue().longValue() <= expiryCutoff) {
        entries.remove();
      }
    }
  }
}
