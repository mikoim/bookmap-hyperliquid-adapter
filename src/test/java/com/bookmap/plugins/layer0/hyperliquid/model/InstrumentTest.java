package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests exact unit conversion for perpetual and spot instruments. */
public class InstrumentTest {

  /** Ensures valid decimal values become their exact Bookmap units. */
  @Test
  public void convertsSizeAndPriceWithoutRounding() throws Exception {
    Instrument instrument = Instrument.perpetual("BTC", 3);

    assertEquals(3, instrument.priceDecimals());
    assertEquals(0.001d, instrument.pips(), 0.0d);
    assertEquals(1000d, instrument.sizeMultiplier(), 0.0d);
    assertEquals(27123456L, instrument.toDepthPriceUnits(new BigDecimal("27123.456")));
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

  /** Rejects perpetual metadata whose size scale is outside zero through six. */
  @Test
  public void rejectsSizeDecimalsOutsideZeroThroughSix() {
    assertRejectedSizeDecimals(-1);
    assertRejectedSizeDecimals(7);
  }

  /** Classifies zero, negative, null, and fractional-unit values without rounding them. */
  @Test
  public void classifiesNonPositiveAndNonIntegralValues() {
    Instrument instrument = Instrument.perpetual("BTC", 3);

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
    Instrument instrument = Instrument.perpetual("BTC", 3);

    assertEquals(Integer.MAX_VALUE, instrument.toSizeUnits(new BigDecimal("9999999999.999")));
  }

  /** Depth sizes accept zero for removals while trade sizes keep rejecting it. */
  @Test
  public void depthSizeUnitsAcceptZero() throws ValueConversionException {
    Instrument instrument = Instrument.perpetual("BTC", 2);

    assertEquals(0, instrument.toDepthSizeUnits(new BigDecimal("0")));
    assertEquals(0, instrument.toDepthSizeUnits(new BigDecimal("0.00")));
    assertEquals(150, instrument.toDepthSizeUnits(new BigDecimal("1.5")));
    try {
      instrument.toSizeUnits(new BigDecimal("0"));
      fail("trade size zero must stay rejected");
    } catch (ValueConversionException expected) {
      assertEquals(ValueConversionException.Reason.NON_POSITIVE, expected.reason());
    }
  }

  /** Keeps a positive mark price as the reference price and drops anything else. */
  @Test
  public void keepsPositiveReferencePriceAndDropsOthers() {
    assertEquals(
        new BigDecimal("87.785"),
        Instrument.perpetual("HYPE", 2, new BigDecimal("87.785")).referencePrice());
    assertNull(Instrument.perpetual("HYPE", 2).referencePrice());
    assertNull(Instrument.perpetual("HYPE", 2, BigDecimal.ZERO).referencePrice());
    assertNull(Instrument.perpetual("HYPE", 2, new BigDecimal("-1")).referencePrice());
  }

  /** Only a value beyond long is out of range; int-sized overflow is now a legitimate result. */
  @Test
  public void rejectsDepthPriceOutsideLongRange() throws Exception {
    Instrument instrument = Instrument.perpetual("BTC", 3);

    assertEquals(2_147_483_648L, instrument.toDepthPriceUnits(new BigDecimal("2147483.648")));
    assertDepthConversionReason(
        instrument,
        new BigDecimal("9223372036854775.808"),
        ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
  }

  /** Spot gold at 4378.7 with szDecimals 2 needs 4.4e9 native units, beyond int but within long. */
  @Test
  public void depthPriceUnitsExceedIntForHighPricedSpotPairs() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);

    assertEquals(4_378_700_000L, gold.toDepthPriceUnits(new BigDecimal("4378.7")));
  }

  /** Rejects a trade price whose exact units cannot be represented by a double. */
  @Test
  public void rejectsTradePriceAboveExactDoubleIntegerLimit() {
    Instrument instrument = Instrument.perpetual("BTC", 3);

    assertTradeConversionReason(
        instrument,
        new BigDecimal("9007199254740.993"),
        ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
  }

  private void assertRejectedSizeDecimals(int sizeDecimals) {
    boolean rejected = false;
    try {
      Instrument.perpetual("BTC", sizeDecimals);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    assertTrue("Expected sizeDecimals " + sizeDecimals + " to be rejected", rejected);
  }

  private void assertDepthConversionReason(
      Instrument instrument, BigDecimal value, ValueConversionException.Reason reason) {
    try {
      instrument.toDepthPriceUnits(value);
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
      return;
    }
    throw new AssertionError("Expected conversion to fail with " + reason);
  }

  private void assertTradeConversionReason(
      Instrument instrument, BigDecimal value, ValueConversionException.Reason reason) {
    try {
      instrument.toTradePriceUnits(value);
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
      return;
    }
    throw new AssertionError("Expected conversion to fail with " + reason);
  }

  /** A spot pair carries a display symbol, a wire coin, and the 8-decimal spot price rule. */
  @Test
  public void spotInstrumentDerivesEightDecimalPriceGrid() {
    Instrument hype = Instrument.spot("HYPE/USDC", "@107", 2);

    assertEquals("HYPE/USDC", hype.symbol());
    assertEquals("@107", hype.coin());
    assertEquals(Market.SPOT, hype.market());
    assertEquals("SPOT", hype.market().bookmapType());
    assertEquals(6, hype.priceDecimals());
    assertEquals(1e-6d, hype.pips(), 0.0d);
    assertNull(hype.referencePrice());
  }

  /** A perpetual keeps symbol and coin identical and the 6-decimal rule. */
  @Test
  public void perpetualInstrumentUsesItsSymbolAsCoin() {
    Instrument btc = Instrument.perpetual("BTC", 5, new BigDecimal("79394.0"));

    assertEquals("BTC", btc.coin());
    assertEquals(Market.PERPETUAL, btc.market());
    assertEquals("PERPETUAL", btc.market().bookmapType());
    assertEquals(1, btc.priceDecimals());
    assertEquals(new BigDecimal("79394.0"), btc.referencePrice());
  }

  /** The size-decimals ceiling follows the market: 6 for perps, 8 for spot. */
  @Test
  public void sizeDecimalsCeilingFollowsTheMarket() {
    assertEquals(0, Instrument.spot("USDC/USDH", "@9", 8).priceDecimals());
    try {
      Instrument.spot("X/USDC", "@1", 9);
      fail("spot szDecimals 9 must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("8"));
    }
  }

  /** Replacing the reference price keeps every other attribute. */
  @Test
  public void withReferencePriceKeepsIdentity() {
    Instrument hype = Instrument.spot("HYPE/USDC", "@107", 2);

    Instrument priced = hype.withReferencePrice(new BigDecimal("82.94"));

    assertEquals("HYPE/USDC", priced.symbol());
    assertEquals("@107", priced.coin());
    assertEquals(Market.SPOT, priced.market());
    assertEquals(2, priced.sizeDecimals());
    assertEquals(new BigDecimal("82.94"), priced.referencePrice());
    assertNull(priced.withReferencePrice(new BigDecimal("-1")).referencePrice());
  }
}
