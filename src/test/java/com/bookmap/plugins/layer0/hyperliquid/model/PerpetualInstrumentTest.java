package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests exact unit conversion for perpetual instruments. */
public class PerpetualInstrumentTest {

  /** Ensures valid decimal values become their exact Bookmap units. */
  @Test
  public void convertsSizeAndPriceWithoutRounding() throws Exception {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);

    assertEquals(3, instrument.priceDecimals());
    assertEquals(0.001d, instrument.pips(), 0.0d);
    assertEquals(1000d, instrument.sizeMultiplier(), 0.0d);
    assertEquals(27123456, instrument.toDepthPriceUnits(new BigDecimal("27123.456")));
    assertEquals(27123456d, instrument.toTradePriceUnits(new BigDecimal("27123.456")), 0.0d);
    assertEquals(1001, instrument.toSizeUnits(new BigDecimal("1.001")));
  }

  /** Ensures the production and test endpoints are selected exactly. */
  @Test
  public void selectsExactEnvironmentEndpoints() {
    assertEquals(
        "https://api.hyperliquid.xyz/info", HyperliquidEnvironment.MAINNET.infoUri().toString());
    assertEquals(
        "wss://api.hyperliquid-testnet.xyz/ws",
        HyperliquidEnvironment.TESTNET.webSocketUri().toString());
  }

  /** Rejects Hyperliquid metadata whose size scale is outside the supported range. */
  @Test
  public void rejectsSizeDecimalsOutsideZeroThroughSix() {
    assertRejectedSizeDecimals(-1);
    assertRejectedSizeDecimals(7);
  }

  /** Classifies zero, negative, null, and fractional-unit values without rounding them. */
  @Test
  public void classifiesNonPositiveAndNonIntegralValues() {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);

    assertDepthConversionReason(instrument, null, ValueConversionException.Reason.NON_POSITIVE);
    assertDepthConversionReason(
        instrument, new BigDecimal("0"), ValueConversionException.Reason.NON_POSITIVE);
    assertDepthConversionReason(
        instrument, new BigDecimal("-1"), ValueConversionException.Reason.NON_POSITIVE);
    assertDepthConversionReason(
        instrument, new BigDecimal("1.0001"), ValueConversionException.Reason.NON_INTEGRAL);
  }

  /** Saturates a valid quantity only when it exceeds Bookmap's integer capacity. */
  @Test
  public void saturatesOnlySize() throws Exception {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);

    assertEquals(Integer.MAX_VALUE, instrument.toSizeUnits(new BigDecimal("9999999999.999")));
  }

  /** Rejects a depth price that needs one unit more than a signed integer can hold. */
  @Test
  public void rejectsDepthPriceOutsideIntegerRange() {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);

    assertDepthConversionReason(
        instrument,
        new BigDecimal("2147483.648"),
        ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
  }

  /** Rejects a trade price whose exact units cannot be represented by a double. */
  @Test
  public void rejectsTradePriceAboveExactDoubleIntegerLimit() {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 3);

    assertTradeConversionReason(
        instrument,
        new BigDecimal("9007199254740.993"),
        ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
  }

  private void assertRejectedSizeDecimals(int sizeDecimals) {
    boolean rejected = false;
    try {
      new PerpetualInstrument("BTC", sizeDecimals);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    assertTrue("Expected sizeDecimals " + sizeDecimals + " to be rejected", rejected);
  }

  private void assertDepthConversionReason(
      PerpetualInstrument instrument, BigDecimal value, ValueConversionException.Reason reason) {
    try {
      instrument.toDepthPriceUnits(value);
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
      return;
    }
    throw new AssertionError("Expected conversion to fail with " + reason);
  }

  private void assertTradeConversionReason(
      PerpetualInstrument instrument, BigDecimal value, ValueConversionException.Reason reason) {
    try {
      instrument.toTradePriceUnits(value);
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
      return;
    }
    throw new AssertionError("Expected conversion to fail with " + reason);
  }
}
