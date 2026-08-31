package com.bookmap.plugins.layer0.hyperliquid.model;

/** Hyperliquid WebSocket subscription categories. */
public enum SubscriptionType {
  L2_BOOK("l2Book"),
  TRADES("trades");

  private final String wireName;

  SubscriptionType(String wireName) {
    this.wireName = wireName;
  }

  /** Returns the type name used in Hyperliquid WebSocket JSON. */
  public String wireName() {
    return wireName;
  }
}
