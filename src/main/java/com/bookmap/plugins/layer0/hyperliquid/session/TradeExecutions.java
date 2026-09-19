package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import java.util.List;

/**
 * Marks where executions begin and end in a run of trades that is about to be published.
 * Hyperliquid reports one trade per maker filled, so a single aggressing transaction arrives as
 * several trades sharing a hash; Bookmap draws them as one execution when the first carries the
 * start flag and the last the end flag. Flags are derived from the list actually published, never
 * earlier, so a trade dropped by deduplication or a full buffer cannot leave an execution without
 * its start or its end.
 */
final class TradeExecutions {

  /** Receives each trade once, in order, with its flags. */
  interface Output {

    /** Publishes one trade. A trade that stands alone has both flags set. */
    void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd);
  }

  private TradeExecutions() {
    // static utility
  }

  /** Publishes the trades of one subscription in the order given. */
  static void publish(List<PendingTrade> trades, Output output) {
    int last = trades.size() - 1;
    for (int index = 0; index <= last; index++) {
      PendingTrade trade = trades.get(index);
      boolean start = index == 0 || !sameExecution(trades.get(index - 1), trade);
      boolean end = index == last || !sameExecution(trade, trades.get(index + 1));
      output.onTrade(trade, start, end);
    }
  }

  /** Only adjacent trades with one non-null id and one aggressor side share an execution. */
  private static boolean sameExecution(PendingTrade first, PendingTrade second) {
    return first.executionId() != null
        && first.executionId().equals(second.executionId())
        && first.buyAggressor() == second.buyAggressor();
  }
}
