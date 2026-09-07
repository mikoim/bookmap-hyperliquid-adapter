package com.bookmap.plugins.layer0.hyperliquid.session;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the merged mark price of every coin the fastAssetCtxs feed reports. The first frame of a
 * connection is a full snapshot; later frames name only the coins that moved. All price values must
 * be non-null; {@code AssetContextCodec} guarantees this by dropping unusable entries.
 */
final class AssetContextStore {

  private final Map<String, BigDecimal> markPrices = new HashMap<String, BigDecimal>();

  /** Replaces the whole view, dropping coins the exchange no longer reports. */
  void applySnapshot(Map<String, BigDecimal> snapshot) {
    for (Map.Entry<String, BigDecimal> entry : snapshot.entrySet()) {
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "mark price for symbol '" + entry.getKey() + "' cannot be null");
      }
    }
    markPrices.clear();
    markPrices.putAll(snapshot);
  }

  /** Merges a delta and returns the symbols whose mark price actually changed. */
  Set<String> applyDelta(Map<String, BigDecimal> delta) {
    for (Map.Entry<String, BigDecimal> entry : delta.entrySet()) {
      if (entry.getValue() == null) {
        throw new IllegalArgumentException(
            "mark price for symbol '" + entry.getKey() + "' cannot be null");
      }
    }
    Set<String> changed = new HashSet<String>();
    for (Map.Entry<String, BigDecimal> entry : delta.entrySet()) {
      BigDecimal previous = markPrices.put(entry.getKey(), entry.getValue());
      if (previous == null || previous.compareTo(entry.getValue()) != 0) {
        changed.add(entry.getKey());
      }
    }
    return changed;
  }

  /** Returns the current mark price, or null when the coin has never been reported. */
  BigDecimal markPrice(String symbol) {
    return markPrices.get(symbol);
  }
}
