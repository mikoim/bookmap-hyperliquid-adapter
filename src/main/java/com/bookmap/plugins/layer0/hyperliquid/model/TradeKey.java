package com.bookmap.plugins.layer0.hyperliquid.model;

import java.util.Objects;

/** Value identity for one Hyperliquid trade. */
public final class TradeKey {

  private final String coin;
  private final long time;
  private final long tid;

  /** Creates a trade identity from its coin, exchange time, and trade id. */
  public TradeKey(String coin, long time, long tid) {
    this.coin = coin;
    this.time = time;
    this.tid = tid;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TradeKey)) {
      return false;
    }
    TradeKey that = (TradeKey) other;
    return time == that.time && tid == that.tid && Objects.equals(coin, that.coin);
  }

  @Override
  public int hashCode() {
    return Objects.hash(coin, time, tid);
  }
}
