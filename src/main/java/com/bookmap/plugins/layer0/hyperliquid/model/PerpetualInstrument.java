package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Immutable metadata and exact unit conversion rules for one perpetual instrument. */
public final class PerpetualInstrument {

  private static final BigInteger MAX_SIZE_UNITS = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MAX_DEPTH_PRICE_UNITS = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MAX_TRADE_PRICE_UNITS =
      BigInteger.valueOf(9_007_199_254_740_992L);

  private final String symbol;
  private final int sizeDecimals;
  private final int priceDecimals;
  private final double pips;
  private final double sizeMultiplier;

  /** Creates metadata for a perpetual instrument with a valid Hyperliquid size scale. */
  public PerpetualInstrument(String symbol, int sizeDecimals) {
    if (sizeDecimals < 0 || sizeDecimals > 6) {
      throw new IllegalArgumentException("sizeDecimals must be between zero and six");
    }
    this.symbol = symbol;
    this.sizeDecimals = sizeDecimals;
    this.priceDecimals = 6 - sizeDecimals;
    this.pips = Math.pow(10d, -priceDecimals);
    this.sizeMultiplier = Math.pow(10d, sizeDecimals);
  }

  /** Returns the Hyperliquid symbol. */
  public String symbol() {
    return symbol;
  }

  /** Returns the number of decimal places allowed for quantity. */
  public int sizeDecimals() {
    return sizeDecimals;
  }

  /** Returns the derived number of decimal places used for price units. */
  public int priceDecimals() {
    return priceDecimals;
  }

  /** Returns the display price quantum. */
  public double pips() {
    return pips;
  }

  /** Returns the quantity multiplier used by Bookmap. */
  public double sizeMultiplier() {
    return sizeMultiplier;
  }

  /** Converts a price to exact depth units. */
  public int toDepthPriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_DEPTH_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return units.intValue();
  }

  /** Converts a price to exact trade units. */
  public double toTradePriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_TRADE_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
    }
    double converted = units.doubleValue();
    if (Double.isInfinite(converted) || Double.isNaN(converted)) {
      throw new ValueConversionException(ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
    }
    return converted;
  }

  /** Converts a size to exact Bookmap units, saturating only overflow. */
  public int toSizeUnits(BigDecimal size) throws ValueConversionException {
    BigInteger units = exactUnits(size, sizeDecimals);
    if (units.compareTo(MAX_SIZE_UNITS) > 0) {
      return Integer.MAX_VALUE;
    }
    return units.intValue();
  }

  /** Converts a depth size to Bookmap units; zero means the level is gone. */
  public int toDepthSizeUnits(BigDecimal size) throws ValueConversionException {
    if (size != null && size.signum() == 0) {
      return 0;
    }
    return toSizeUnits(size);
  }

  private BigInteger exactUnits(BigDecimal value, int decimals) throws ValueConversionException {
    if (value == null || value.signum() <= 0) {
      throw new ValueConversionException(ValueConversionException.Reason.NON_POSITIVE);
    }
    try {
      return value
          .movePointRight(decimals)
          .setScale(0, RoundingMode.UNNECESSARY)
          .toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw new ValueConversionException(ValueConversionException.Reason.NON_INTEGRAL, exception);
    }
  }
}
