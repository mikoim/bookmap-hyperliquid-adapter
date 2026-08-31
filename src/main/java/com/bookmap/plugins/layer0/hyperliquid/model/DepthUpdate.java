package com.bookmap.plugins.layer0.hyperliquid.model;

/** A normalized order-book level change. */
public final class DepthUpdate {

  private final boolean bid;
  private final int price;
  private final int size;

  /** Creates a depth change in Bookmap integer units. */
  public DepthUpdate(boolean bid, int price, int size) {
    this.bid = bid;
    this.price = price;
    this.size = size;
  }

  /** Returns whether this is a bid-side update. */
  public boolean bid() {
    return bid;
  }

  /** Returns the normalized price units. */
  public int price() {
    return price;
  }

  /** Returns the normalized size units. */
  public int size() {
    return size;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof DepthUpdate)) {
      return false;
    }
    DepthUpdate that = (DepthUpdate) other;
    return bid == that.bid && price == that.price && size == that.size;
  }

  @Override
  public int hashCode() {
    int result = Boolean.valueOf(bid).hashCode();
    result = 31 * result + price;
    return 31 * result + size;
  }
}
