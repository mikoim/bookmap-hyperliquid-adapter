package com.bookmap.plugins.layer0.hyperliquid;

/** Selectable order-book and trade sources that speak the Hyperliquid WebSocket protocol. */
public enum MarketDataSource {
  HYPERLIQUID("Hyperliquid"),
  BORSA("Borsa"),
  HYPERDASH("Hyperdash");

  private final String fieldValue;

  MarketDataSource(String fieldValue) {
    this.fieldValue = fieldValue;
  }

  /** Returns the value shown in, and serialized by, the login dropdown. */
  public String fieldValue() {
    return fieldValue;
  }

  /** Resolves a dropdown value; null or unknown values fall back to Hyperliquid. */
  public static MarketDataSource fromFieldValue(String value) {
    for (MarketDataSource candidate : values()) {
      if (candidate.fieldValue.equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return HYPERLIQUID;
  }

  /** Returns the dropdown values in display order, Hyperliquid first. */
  public static String[] fieldValues() {
    MarketDataSource[] sources = values();
    String[] fieldValues = new String[sources.length];
    for (int index = 0; index < sources.length; index++) {
      fieldValues[index] = sources[index].fieldValue;
    }
    return fieldValues;
  }
}
