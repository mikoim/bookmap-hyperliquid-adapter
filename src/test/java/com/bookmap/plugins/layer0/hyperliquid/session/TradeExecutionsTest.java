package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;

import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;
import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Pins which adjacent trades form one execution and how its first and last trade are flagged. */
public class TradeExecutionsTest {

  @Test
  public void emptyListPublishesNothing() {
    assertEquals("[]", publish().toString());
  }

  @Test
  public void aSingleTradeIsAWholeExecution() {
    assertEquals("[1:TT]", publish(buy(1, "0xa")).toString());
    assertEquals("[1:TT]", publish(buy(1, null)).toString());
  }

  @Test
  public void adjacentTradesWithOneIdAndSideFormOneExecution() {
    assertEquals(
        "[1:TF, 2:FF, 3:FT]", publish(buy(1, "0xa"), buy(2, "0xa"), buy(3, "0xa")).toString());
  }

  @Test
  public void aChangeOfSideEndsTheExecution() {
    assertEquals("[1:TT, 2:TT]", publish(buy(1, "0xa"), sell(2, "0xa")).toString());
  }

  @Test
  public void aTradeWithoutAnIdStandsAloneAndSplitsItsNeighbours() {
    assertEquals(
        "[1:TT, 2:TT, 3:TT]", publish(buy(1, "0xa"), buy(2, null), buy(3, "0xa")).toString());
    assertEquals("[1:TT, 2:TT]", publish(buy(1, null), buy(2, null)).toString());
  }

  @Test
  public void onlyAdjacentTradesAreGrouped() {
    assertEquals(
        "[1:TT, 2:TT, 3:TT, 4:TT]",
        publish(buy(1, "0xa"), buy(2, "0xb"), buy(3, "0xa"), buy(4, "0xb")).toString());
  }

  @Test
  public void consecutiveExecutionsEachGetAStartAndAnEnd() {
    assertEquals(
        "[1:TF, 2:FT, 3:TF, 4:FT]",
        publish(buy(1, "0xa"), buy(2, "0xa"), buy(3, "0xb"), buy(4, "0xb")).toString());
  }

  private static List<String> publish(PendingTrade... trades) {
    final List<String> published = new ArrayList<String>();
    List<PendingTrade> input =
        trades.length == 0 ? Collections.<PendingTrade>emptyList() : Arrays.asList(trades);
    TradeExecutions.publish(
        input,
        new TradeExecutions.Output() {
          @Override
          public void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd) {
            String flags = (executionStart ? "T" : "F") + (executionEnd ? "T" : "F");
            published.add(trade.sizeUnits() + ":" + flags);
          }
        });
    return published;
  }

  /** The size doubles as the trade's label in the expectations above. */
  private static PendingTrade buy(int label, String executionId) {
    return new PendingTrade(new TradeKey("BTC", 1L, label), 100d, label, true, executionId);
  }

  private static PendingTrade sell(int label, String executionId) {
    return new PendingTrade(new TradeKey("BTC", 1L, label), 100d, label, false, executionId);
  }
}
