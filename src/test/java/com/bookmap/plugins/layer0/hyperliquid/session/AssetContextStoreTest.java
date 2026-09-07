package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Merges fastAssetCtxs snapshots and deltas into a single mark-price view. */
public class AssetContextStoreTest {

  private final AssetContextStore store = new AssetContextStore();

  /** A delta updates only the coins it names and leaves every other value untouched. */
  @Test
  public void deltaKeepsPreviousValuesForAbsentSymbols() {
    store.applySnapshot(prices("BTC", "79394.0", "xyz:CL", "92.283"));

    Set<String> changed = store.applyDelta(prices("xyz:CL", "92.5"));

    assertEquals(Collections.singleton("xyz:CL"), changed);
    assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
    assertEquals(0, new BigDecimal("92.5").compareTo(store.markPrice("xyz:CL")));
  }

  /** A delta that repeats the current value reports no change. */
  @Test
  public void unchangedDeltaValueIsNotReportedAsChanged() {
    store.applySnapshot(prices("BTC", "79394.0"));

    assertTrue(store.applyDelta(prices("BTC", "79394.0")).isEmpty());
  }

  /** A delta introduces coins the snapshot never carried without disturbing existing ones. */
  @Test
  public void deltaAddsUnknownSymbols() {
    store.applySnapshot(prices("BTC", "79394.0"));

    Set<String> changed = store.applyDelta(prices("para:AVGO", "312.5"));

    assertEquals(Collections.singleton("para:AVGO"), changed);
    assertEquals(0, new BigDecimal("312.5").compareTo(store.markPrice("para:AVGO")));
    assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
  }

  /** A snapshot replaces the whole view so a delisted coin cannot linger. */
  @Test
  public void snapshotReplacesEveryPreviousValue() {
    store.applySnapshot(prices("BTC", "79394.0", "GONE", "1.0"));

    store.applySnapshot(prices("BTC", "80000.0"));

    assertEquals(0, new BigDecimal("80000.0").compareTo(store.markPrice("BTC")));
    assertNull(store.markPrice("GONE"));
  }

  /** An unknown symbol has no mark price. */
  @Test
  public void unknownSymbolHasNoMarkPrice() {
    assertNull(store.markPrice("BTC"));
  }

  /** Different scales of the same price are recognized as unchanged. */
  @Test
  public void deltaWithDifferentScaleSameValueIsNotReportedAsChanged() {
    store.applySnapshot(prices("BTC", "92.5"));

    assertTrue(store.applyDelta(prices("BTC", "92.50")).isEmpty());
  }

  /** Snapshot with null value throws IllegalArgumentException and leaves store unchanged. */
  @Test
  public void snapshotWithNullValueThrowsAndLeavesStoreUnchanged() {
    store.applySnapshot(prices("BTC", "79394.0"));

    Map<String, BigDecimal> snapshotWithNull = new LinkedHashMap<String, BigDecimal>();
    snapshotWithNull.put("ETH", new BigDecimal("2000.0"));
    snapshotWithNull.put("GONE", null);

    try {
      store.applySnapshot(snapshotWithNull);
      fail("Expected IllegalArgumentException for null mark price");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("GONE"));
      assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
      assertNull(store.markPrice("ETH"));
      assertNull(store.markPrice("GONE"));
    }
  }

  /** Delta with null value throws IllegalArgumentException and leaves store unchanged. */
  @Test
  public void deltaWithNullValueThrowsAndLeavesStoreUnchanged() {
    store.applySnapshot(prices("BTC", "79394.0"));

    Map<String, BigDecimal> deltaWithNull = new LinkedHashMap<String, BigDecimal>();
    deltaWithNull.put("ETH", new BigDecimal("2000.0"));
    deltaWithNull.put("XRP", null);

    try {
      store.applyDelta(deltaWithNull);
      fail("Expected IllegalArgumentException for null mark price");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("XRP"));
      assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
      assertNull(store.markPrice("ETH"));
      assertNull(store.markPrice("XRP"));
    }
  }

  private static Map<String, BigDecimal> prices(String... symbolsAndPrices) {
    Map<String, BigDecimal> prices = new LinkedHashMap<String, BigDecimal>();
    for (int index = 0; index < symbolsAndPrices.length; index += 2) {
      prices.put(symbolsAndPrices[index], new BigDecimal(symbolsAndPrices[index + 1]));
    }
    return prices;
  }
}
