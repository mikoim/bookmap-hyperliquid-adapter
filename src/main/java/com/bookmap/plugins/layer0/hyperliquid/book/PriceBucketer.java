package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlan;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Maps native price units (the lossless {@code 10^-priceDecimals} grid) onto the Bookmap tick
 * chosen for one subscription. Bids round down and asks round up, matching Hyperliquid's own
 * server-side aggregation, so a published bid is never better than any real bid it contains. Native
 * units are long; buckets are the int Bookmap publishes.
 */
public final class PriceBucketer {

  private final Instrument instrument;
  private final BigDecimal tick;
  private final long ratio;

  /** Creates a bucketer for a tick that is an integer multiple of the instrument's grid. */
  public PriceBucketer(Instrument instrument, BigDecimal tick) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.ratio = TickSizePlan.ratio(tick, instrument.priceDecimals());
    this.tick = tick;
  }

  /** Returns a bucketer whose tick is the native grid, i.e. the identity mapping. */
  public static PriceBucketer identity(Instrument instrument) {
    return new PriceBucketer(
        instrument, BigDecimal.ONE.scaleByPowerOfTen(-instrument.priceDecimals()));
  }

  /** Returns the Bookmap tick this bucketer publishes at. */
  public BigDecimal tick() {
    return tick;
  }

  /** Returns how many native grid steps one bucket spans. */
  public long ratio() {
    return ratio;
  }

  /** Returns the bucket of a native price for the given side. */
  public int bucket(boolean bid, long nativeUnits) throws ValueConversionException {
    return bid ? bidBucket(nativeUnits) : askBucket(nativeUnits);
  }

  /** Rounds a native bid price down to its bucket. */
  public int bidBucket(long nativeUnits) throws ValueConversionException {
    return toBucket(Math.floorDiv(nativeUnits, ratio));
  }

  /** Rounds a native ask price up to its bucket; {@code (n - 1) / r + 1} avoids overflow. */
  public int askBucket(long nativeUnits) throws ValueConversionException {
    if (nativeUnits <= 0L) {
      return toBucket(Math.floorDiv(nativeUnits, ratio));
    }
    return toBucket(Math.floorDiv(nativeUnits - 1L, ratio) + 1L);
  }

  /** Bookmap depth prices are int; a bucket beyond that cannot be published. */
  private static int toBucket(long bucket) throws ValueConversionException {
    if (bucket > Integer.MAX_VALUE) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return (int) bucket;
  }

  /** Returns the inclusive native-unit range {low, high} that maps onto one bucket. */
  public long[] nativeRange(int bucket, boolean bid) {
    long low = bid ? (long) bucket * ratio : ((long) bucket - 1L) * ratio + 1L;
    long high = bid ? ((long) bucket + 1L) * ratio - 1L : (long) bucket * ratio;
    return new long[] {Math.max(0L, low), high};
  }

  /**
   * Converts a trade price to Bookmap units of this tick. The native conversion and its exception
   * contract are unchanged; the result may carry a fraction when the ratio exceeds one.
   */
  public double tradePriceUnits(BigDecimal price) throws ValueConversionException {
    return instrument.toTradePriceUnits(price) / (double) ratio;
  }

  /** Clamps a summed size to the int range Bookmap accepts. */
  public static int saturate(long sizeUnits) {
    if (sizeUnits <= 0L) {
      return 0;
    }
    return sizeUnits > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sizeUnits;
  }
}
