package com.bookmap.plugins.layer0.hyperliquid.model;

/**
 * Hyperliquid market kinds. Prices carry at most five significant figures and at most {@code
 * maxDecimals - szDecimals} decimals: six for perpetuals and eight for spot pairs (Tick and lot
 * size). The Bookmap type string is what the Subscribe dialog shows and sends back.
 */
public enum Market {
  PERPETUAL(6, "PERPETUAL"),
  SPOT(8, "SPOT");

  private final int maxDecimals;
  private final String bookmapType;

  Market(int maxDecimals, String bookmapType) {
    this.maxDecimals = maxDecimals;
    this.bookmapType = bookmapType;
  }

  /** Returns the exchange's MAX_DECIMALS for this market's prices. */
  public int maxDecimals() {
    return maxDecimals;
  }

  /** Returns the instrument type string used in Bookmap's SubscribeInfo and InstrumentInfo. */
  public String bookmapType() {
    return bookmapType;
  }
}
