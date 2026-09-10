package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlan;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Maps native price units (the lossless {@code 10^-priceDecimals} grid) onto the Bookmap tick
 * chosen for one subscription. Bids round down and asks round up, matching Hyperliquid's own
 * server-side aggregation, so a published bid is never better than any real bid it contains.
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
  public int bucket(boolean bid, int nativeUnits) {
    return bid ? bidBucket(nativeUnits) : askBucket(nativeUnits);
  }

  /** Rounds a native bid price down to its bucket. */
  public int bidBucket(int nativeUnits) {
    return (int) Math.floorDiv((long) nativeUnits, ratio);
  }

  /** Rounds a native ask price up to its bucket. */
  public int askBucket(int nativeUnits) {
    return (int) (((long) nativeUnits + ratio - 1L) / ratio);
  }

  /** Returns the inclusive native-unit range {low, high} that maps onto one bucket. */
  public int[] nativeRange(int bucket, boolean bid) {
    long low = bid ? (long) bucket * ratio : ((long) bucket - 1L) * ratio + 1L;
    long high = bid ? ((long) bucket + 1L) * ratio - 1L : (long) bucket * ratio;
    return new int[] {(int) Math.max(0L, low), (int) Math.min(Integer.MAX_VALUE, high)};
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
