package com.bookmap.plugins.layer0.hyperliquid.session;

import java.util.HashSet;
import java.util.Set;

/**
 * The exchange time of the last trade published for one subscription. Trades older than it are
 * replays of trades already published, or of trades from before the subscription's first book, and
 * are never published again. Trades at exactly that time are told apart by their trade id.
 */
final class TradeWatermark {

  private final Set<Long> tidsAtTime = new HashSet<Long>();
  private long time = -1L;

  /** Starts the watermark at the exchange time of the first book the subscription publishes. */
  void anchor(long bookTime) {
    time = bookTime;
    tidsAtTime.clear();
  }

  /** Returns whether a trade is newer than everything already published. */
  boolean admits(long tradeTime, long tid) {
    if (tradeTime != time) {
      return tradeTime > time;
    }
    return !tidsAtTime.contains(Long.valueOf(tid));
  }

  /** Records a published trade; a trade older than the watermark is a caller error. */
  void advance(long tradeTime, long tid) {
    if (tradeTime < time) {
      throw new IllegalArgumentException("trade time moved backwards");
    }
    if (tradeTime > time) {
      time = tradeTime;
      tidsAtTime.clear();
    }
    tidsAtTime.add(Long.valueOf(tid));
  }
}
