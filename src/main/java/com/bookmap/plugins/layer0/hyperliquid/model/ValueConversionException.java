package com.bookmap.plugins.layer0.hyperliquid.model;

/** Indicates that a market-data decimal cannot be represented exactly in Bookmap units. */
public final class ValueConversionException extends Exception {

  /** The classified reason a value conversion failed. */
  public enum Reason {
    NON_POSITIVE,
    NON_INTEGRAL,
    DEPTH_PRICE_OUT_OF_RANGE,
    TRADE_PRICE_OUT_OF_RANGE
  }

  private final Reason reason;

  /** Creates an exception for the supplied failure reason. */
  public ValueConversionException(Reason reason) {
    this.reason = reason;
  }

  /** Creates an exception for the supplied failure reason and cause. */
  public ValueConversionException(Reason reason, Throwable cause) {
    super(cause);
    this.reason = reason;
  }

  /** Returns the classified conversion failure reason. */
  public Reason reason() {
    return reason;
  }
}
