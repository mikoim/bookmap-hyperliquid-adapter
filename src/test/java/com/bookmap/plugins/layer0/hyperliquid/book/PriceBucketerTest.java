package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests native-unit to tick-bucket mapping: bids floor, asks ceil. */
public class PriceBucketerTest {

  private final Instrument hype = Instrument.perpetual("HYPE", 2);

  @Test
  public void identityMapsEveryNativeUnitOntoItself() throws Exception {
    PriceBucketer bucketer = PriceBucketer.identity(hype);

    assertEquals(1L, bucketer.ratio());
    assertEquals(0, new BigDecimal("0.0001").compareTo(bucketer.tick()));
    assertEquals(877840, bucketer.bidBucket(877840));
    assertEquals(877850, bucketer.askBucket(877850));
    assertArrayEquals(new long[] {877840L, 877840L}, bucketer.nativeRange(877840, true));
  }

  @Test
  public void bidsRoundDownAndAsksRoundUpToTheTick() throws Exception {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.01"));

    assertEquals(100L, bucketer.ratio());
    assertEquals(8778, bucketer.bidBucket(877840));
    assertEquals(8778, bucketer.bidBucket(877899));
    assertEquals(8779, bucketer.askBucket(877850));
    assertEquals(8778, bucketer.askBucket(877800));
    assertEquals(8778, bucketer.bucket(true, 877840));
    assertEquals(8779, bucketer.bucket(false, 877850));
    assertArrayEquals(new long[] {877800L, 877899L}, bucketer.nativeRange(8778, true));
    assertArrayEquals(new long[] {877801L, 877900L}, bucketer.nativeRange(8779, false));
  }

  /** XAUT0/USDC: 4378.7 on the 1e-6 grid is 4.4e9 native units; the 0.1 tick maps it to 43787. */
  @Test
  public void highPricedSpotPairBucketsWithinIntAtTheDefaultTick() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    PriceBucketer bucketer = new PriceBucketer(gold, new BigDecimal("0.1"));

    assertEquals(100_000L, bucketer.ratio());
    assertEquals(43787, bucketer.bidBucket(4_378_700_000L));
    assertEquals(43787, bucketer.askBucket(4_378_700_000L));
    assertEquals(43788, bucketer.askBucket(4_378_700_001L));
    assertArrayEquals(
        new long[] {4_378_700_000L, 4_378_799_999L}, bucketer.nativeRange(43787, true));
    assertArrayEquals(
        new long[] {4_378_600_001L, 4_378_700_000L}, bucketer.nativeRange(43787, false));
  }

  /** Only a bucket beyond int is unsupported; the native units themselves may exceed int. */
  @Test
  public void bucketBeyondIntIsReportedAsOutOfRange() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    PriceBucketer identity = PriceBucketer.identity(gold);

    assertEquals(Integer.MAX_VALUE, identity.askBucket((long) Integer.MAX_VALUE));
    try {
      identity.bidBucket(4_378_700_000L);
      fail("bucket beyond int must be rejected");
    } catch (ValueConversionException expected) {
      assertEquals(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE, expected.reason());
    }
    PriceBucketer coarse = new PriceBucketer(gold, new BigDecimal("0.001"));
    assertEquals(Integer.MAX_VALUE, coarse.askBucket(Integer.MAX_VALUE * 1000L));
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
