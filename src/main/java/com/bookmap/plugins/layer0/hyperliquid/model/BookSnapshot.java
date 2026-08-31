package com.bookmap.plugins.layer0.hyperliquid.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable full order-book snapshot for one coin at one exchange timestamp. */
public final class BookSnapshot implements MarketDataEvent {

  private final String coin;
  private final long time;
  private final List<BookLevel> bids;
  private final List<BookLevel> asks;

  /** Creates a snapshot with defensive immutable copies of both sides. */
  public BookSnapshot(String coin, long time, List<BookLevel> bids, List<BookLevel> asks) {
    this.coin = coin;
    this.time = time;
    this.bids = Collections.unmodifiableList(new ArrayList<BookLevel>(bids));
    this.asks = Collections.unmodifiableList(new ArrayList<BookLevel>(asks));
  }

  /** Returns the coin whose book was captured. */
  public String coin() {
    return coin;
  }

  /** Returns the exchange snapshot time in milliseconds. */
  public long time() {
    return time;
  }

  /** Returns the immutable bid side. */
  public List<BookLevel> bids() {
    return bids;
  }

  /** Returns the immutable ask side. */
  public List<BookLevel> asks() {
    return asks;
  }
}
