package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;

/** One parsed Hyperliquid perpetual trade. */
public final class TradeEvent implements MarketDataEvent {

  private final String coin;
  private final long time;
  private final long tid;
  private final boolean isBuyAggressor;
  private final BigDecimal price;
  private final BigDecimal size;

  /** Creates a trade event. */
  public TradeEvent(
      String coin, long time, long tid, boolean isBuyAggressor, BigDecimal price, BigDecimal size) {
    this.coin = coin;
    this.time = time;
    this.tid = tid;
    this.isBuyAggressor = isBuyAggressor;
    this.price = price;
    this.size = size;
  }

  /** Returns the traded coin. */
  public String coin() {
    return coin;
  }

  /** Returns the exchange trade time in milliseconds. */
  public long time() {
    return time;
  }

  /** Returns the exchange trade identifier. */
  public long tid() {
    return tid;
  }

  /** Returns whether the buyer was the aggressor. */
  public boolean isBuyAggressor() {
    return isBuyAggressor;
  }

  /** Returns the exact trade price. */
  public BigDecimal price() {
    return price;
  }

  /** Returns the exact trade quantity. */
  public BigDecimal size() {
    return size;
  }

  /** Returns the value identity used to deduplicate this trade. */
  public TradeKey key() {
    return new TradeKey(coin, time, tid);
  }
}
