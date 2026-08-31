package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;

/** One price and quantity level in a Hyperliquid order book. */
public final class BookLevel {

  private final BigDecimal price;
  private final BigDecimal size;

  /** Creates a book level. */
  public BookLevel(BigDecimal price, BigDecimal size) {
    this.price = price;
    this.size = size;
  }

  /** Returns the level price. */
  public BigDecimal price() {
    return price;
  }

  /** Returns the level quantity. */
  public BigDecimal size() {
    return size;
  }
}
