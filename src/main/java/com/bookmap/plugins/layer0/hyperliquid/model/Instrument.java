package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Immutable metadata and exact unit conversion rules for one instrument, perpetual or spot. The
 * symbol is what Bookmap displays and subscribes by; the coin is the exchange's WebSocket key and
 * the key under which fastAssetCtxs reports its mark price. For a perpetual both are the same name;
 * for a spot pair the symbol is {@code BASE/QUOTE} and the coin is {@code @index}.
 */
public final class Instrument {

  private static final BigInteger MAX_SIZE_UNITS = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MAX_DEPTH_PRICE_UNITS = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger MAX_TRADE_PRICE_UNITS =
      BigInteger.valueOf(9_007_199_254_740_992L);

  private final String symbol;
  private final String coin;
  private final Market market;
  private final int sizeDecimals;
  private final int priceDecimals;
  private final double pips;
  private final double sizeMultiplier;
  private final BigDecimal referencePrice;

  /**
   * Creates instrument metadata.
   *
   * @param referencePrice mark price observed after login, or null; non-positive values are dropped
   */
  public Instrument(
      String symbol, String coin, Market market, int sizeDecimals, BigDecimal referencePrice) {
    if (market == null) {
      throw new IllegalArgumentException("market must not be null");
    }
    if (sizeDecimals < 0 || sizeDecimals > market.maxDecimals()) {
      throw new IllegalArgumentException(
          "sizeDecimals must be between zero and " + market.maxDecimals());
    }
    this.symbol = symbol;
    this.coin = coin;
    this.market = market;
    this.sizeDecimals = sizeDecimals;
    this.priceDecimals = market.maxDecimals() - sizeDecimals;
    this.pips = Math.pow(10d, -priceDecimals);
    this.sizeMultiplier = Math.pow(10d, sizeDecimals);
    this.referencePrice =
        referencePrice != null && referencePrice.signum() > 0 ? referencePrice : null;
  }

  /** Creates a perpetual without a reference price; tick candidates then fall back to the grid. */
  public static Instrument perpetual(String symbol, int sizeDecimals) {
    return perpetual(symbol, sizeDecimals, null);
  }

  /** Creates a perpetual, whose coin is its symbol. */
  public static Instrument perpetual(String symbol, int sizeDecimals, BigDecimal referencePrice) {
    return new Instrument(symbol, symbol, Market.PERPETUAL, sizeDecimals, referencePrice);
  }

  /** Creates a spot pair without a reference price. */
  public static Instrument spot(String symbol, String coin, int sizeDecimals) {
    return new Instrument(symbol, coin, Market.SPOT, sizeDecimals, null);
  }

  /** Returns a copy with the given reference price; every other attribute is kept. */
  public Instrument withReferencePrice(BigDecimal newReferencePrice) {
    return new Instrument(symbol, coin, market, sizeDecimals, newReferencePrice);
  }

  /** Returns the Bookmap symbol and alias. */
  public String symbol() {
    return symbol;
  }

  /** Returns the WebSocket subscription key, also the fastAssetCtxs key. */
  public String coin() {
    return coin;
  }

  /** Returns the market kind. */
  public Market market() {
    return market;
  }

  /** Returns the number of decimal places allowed for quantity. */
  public int sizeDecimals() {
    return sizeDecimals;
  }

  /** Returns the derived number of decimal places used for price units. */
  public int priceDecimals() {
    return priceDecimals;
  }

  /**
   * Returns the native price grid (lossless display quantum), not the Bookmap tick chosen at
   * subscribe time.
   */
  public double pips() {
    return pips;
  }

  /** Returns the last observed mark price, or null when unknown. */
  public BigDecimal referencePrice() {
    return referencePrice;
  }

  /** Returns the quantity multiplier used by Bookmap. */
  public double sizeMultiplier() {
    return sizeMultiplier;
  }

  /**
   * Converts a price to exact native depth units on the {@code 10^-priceDecimals} grid. The result
   * is a long so an 8-decimal spot grid still represents prices in the thousands; the bucketer maps
   * it onto the int Bookmap tick.
   */
  public long toDepthPriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_DEPTH_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return units.longValue();
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
