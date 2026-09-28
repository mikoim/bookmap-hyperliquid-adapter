package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rule that decides whether a trade is newer than everything already published. */
public class TradeWatermarkTest {

  @Test
  public void beforeTheAnchorEveryTradeIsAdmitted() {
    TradeWatermark watermark = new TradeWatermark();

    assertTrue(watermark.admits(0L, 1L));
    assertTrue(watermark.admits(5L, 1L));
  }

  @Test
  public void anchorRejectsOlderTradesAndAdmitsTradesAtOrAfterIt() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);

    assertFalse(watermark.admits(9L, 1L));
    assertTrue(watermark.admits(10L, 1L));
    assertTrue(watermark.admits(11L, 1L));
  }

  @Test
  public void sameTimeIsAdmittedOnlyForAnUnseenTradeId() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);

    assertFalse(watermark.admits(10L, 7L));
    assertTrue(watermark.admits(10L, 8L));
  }

  @Test
  public void advancingTheTimeForgetsTheTradeIdsOfTheOlderTime() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);
    watermark.advance(11L, 8L);

    assertFalse(watermark.admits(10L, 9L));
    assertFalse(watermark.admits(11L, 8L));
    assertTrue(watermark.admits(11L, 7L));
  }

  @Test
  public void advancingWithinTheSameTimeRemembersEveryTradeId() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(11L, 1L);
    watermark.advance(11L, 2L);

    assertFalse(watermark.admits(11L, 1L));
    assertFalse(watermark.admits(11L, 2L));
    assertTrue(watermark.admits(11L, 3L));
  }

  @Test
  public void anchoringAgainClearsTheTradeIds() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);

    watermark.anchor(10L);

    assertTrue(watermark.admits(10L, 7L));
  }

  @Test(expected = IllegalArgumentException.class)
  public void advancingBackwardsIsRejected() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);

    watermark.advance(9L, 1L);
  }
}
