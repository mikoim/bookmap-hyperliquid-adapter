package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests native-unit to tick-bucket mapping: bids floor, asks ceil. */
public class PriceBucketerTest {

  private final PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);

  @Test
  public void identityMapsEveryNativeUnitOntoItself() {
    PriceBucketer bucketer = PriceBucketer.identity(hype);

    assertEquals(1L, bucketer.ratio());
    assertEquals(0, new BigDecimal("0.0001").compareTo(bucketer.tick()));
    assertEquals(877840, bucketer.bidBucket(877840));
    assertEquals(877850, bucketer.askBucket(877850));
    assertArrayEquals(new int[] {877840, 877840}, bucketer.nativeRange(877840, true));
  }

  @Test
  public void bidsRoundDownAndAsksRoundUpToTheTick() {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.01"));

    assertEquals(100L, bucketer.ratio());
    assertEquals(8778, bucketer.bidBucket(877840));
    assertEquals(8778, bucketer.bidBucket(877899));
    assertEquals(8779, bucketer.askBucket(877850));
    assertEquals(8778, bucketer.askBucket(877800));
    assertEquals(8778, bucketer.bucket(true, 877840));
    assertEquals(8779, bucketer.bucket(false, 877850));
    assertArrayEquals(new int[] {877800, 877899}, bucketer.nativeRange(8778, true));
    assertArrayEquals(new int[] {877801, 877900}, bucketer.nativeRange(8779, false));
  }

  @Test
  public void extremeNativeUnitsDoNotOverflow() {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.001"));

    assertEquals(Integer.MAX_VALUE / 10 + 1, bucketer.askBucket(Integer.MAX_VALUE));
    int[] range = bucketer.nativeRange(Integer.MAX_VALUE / 10 + 1, false);
    assertTrue(range[0] <= Integer.MAX_VALUE && range[1] == Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, PriceBucketer.saturate(4294967294L));
    assertEquals(0, PriceBucketer.saturate(-1L));
    assertEquals(7, PriceBucketer.saturate(7L));
  }

  @Test
  public void tradePriceUnitsScaleByTheRatioAndKeepTheNativeContract() throws Exception {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.002"));

    assertEquals(43953.25d, bucketer.tradePriceUnits(new BigDecimal("87.9065")), 0d);
    assertEquals(
        877840d, PriceBucketer.identity(hype).tradePriceUnits(new BigDecimal("87.784")), 0d);
    assertRejected(
        bucketer, new BigDecimal("87.90655"), ValueConversionException.Reason.NON_INTEGRAL);
    assertRejected(bucketer, BigDecimal.ZERO, ValueConversionException.Reason.NON_POSITIVE);
    assertRejected(bucketer, null, ValueConversionException.Reason.NON_POSITIVE);
  }

  @Test
  public void rejectsUnsupportedTicks() {
    try {
      new PriceBucketer(hype, new BigDecimal("0.00005"));
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // finer than the native grid
    }
  }

  private static void assertRejected(
      PriceBucketer bucketer, BigDecimal price, ValueConversionException.Reason reason) {
    try {
      bucketer.tradePriceUnits(price);
      fail("Expected ValueConversionException");
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
    }
  }
}
