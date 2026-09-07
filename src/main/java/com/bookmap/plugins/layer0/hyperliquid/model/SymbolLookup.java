package com.bookmap.plugins.layer0.hyperliquid.model;

import java.util.Map;

/**
 * Resolves a requested symbol against known instruments. Bookmap's Subscribe dialog upper-cases the
 * symbol before it reaches the adapter, so a lookup by exact string alone loses every instrument
 * whose Hyperliquid name is not already upper-case — every HIP-3 market, whose name carries a
 * lower-case dex prefix, and the {@code k}-prefixed validator-operated markets.
 */
public final class SymbolLookup {

  private SymbolLookup() {
    // static utility
  }

  /**
   * Returns the instrument for a requested symbol: the exact match when there is one, otherwise the
   * only case-insensitive match. An unknown or ambiguous request returns null rather than guessing
   * between instruments whose names differ only by case.
   */
  public static PerpetualInstrument resolve(
      Map<String, PerpetualInstrument> bySymbol, String requested) {
    if (bySymbol == null || requested == null) {
      return null;
    }
    PerpetualInstrument exact = bySymbol.get(requested);
    if (exact != null) {
      return exact;
    }
    PerpetualInstrument match = null;
    for (Map.Entry<String, PerpetualInstrument> entry : bySymbol.entrySet()) {
      if (matches(entry.getKey(), requested)) {
        if (match != null) {
          return null;
        }
        match = entry.getValue();
      }
    }
    return match;
  }

  /**
   * Bookmap upper-cases with the default locale, which {@code equalsIgnoreCase} cannot reproduce:
   * under a Turkish locale {@code knetiq} becomes {@code KNETİQ}. Both comparisons are tried so a
   * name stays resolvable whichever locale the platform runs under.
   */
  private static boolean matches(String known, String requested) {
    return known.equalsIgnoreCase(requested) || requested.equals(known.toUpperCase());
  }
}
