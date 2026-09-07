package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests tick candidates and l2Book aggregation parameters derived from a reference price. */
public class TickSizePlanTest {

  private static final BigDecimal HYPE = new BigDecimal("87.785");
  private static final int HYPE_DECIMALS = 4;

  @Test
  public void candidatesFollowTheHyperliquidDropdownForEachPriceMagnitude() {
    assertEquals(
        Arrays.asList("0.001", "0.002", "0.005", "0.01", "0.1", "1"),
        plain(TickSizePlan.candidates(HYPE, HYPE_DECIMALS)));
    assertEquals(
        Arrays.asList("0.1", "0.2", "0.5", "1", "10", "100"),
        plain(TickSizePlan.candidates(new BigDecimal("2519.3"), 2)));
    assertEquals(
        Arrays.asList("1", "2", "5", "10", "100", "1000"),
        plain(TickSizePlan.candidates(new BigDecimal("80203.0"), 1)));
    assertEquals(
        Arrays.asList("1", "10", "20", "50", "100", "1000", "10000"),
        plain(TickSizePlan.candidates(new BigDecimal("112345"), 1)));
    assertEquals(
        Arrays.asList("0.000001", "0.00001", "0.0001"),
        plain(TickSizePlan.candidates(new BigDecimal("0.0012345"), 6)));
  }

  @Test
  public void withoutReferencePriceOnlyTheNativeGridIsOffered() {
    assertEquals(Arrays.asList("0.0001"), plain(TickSizePlan.candidates(null, 4)));
    assertEquals(Arrays.asList("0.0001"), plain(TickSizePlan.candidates(BigDecimal.ZERO, 4)));
    assertEquals("0.0001", TickSizePlan.defaultTick(null, 4).toPlainString());
  }

  @Test
  public void defaultTickIsTheFinestQuotedQuantum() {
    assertEquals("0.001", TickSizePlan.defaultTick(HYPE, HYPE_DECIMALS).toPlainString());
    assertEquals("1", TickSizePlan.defaultTick(new BigDecimal("112345"), 1).toPlainString());
  }

  @Test
  public void parametersPickTheCoarsestServerQuantumDividingTheTick() {
    assertEquals(params(null, null), parametersFor("0.001"));
    assertEquals(params(5, 2), parametersFor("0.002"));
    assertEquals(params(5, 5), parametersFor("0.005"));
    assertEquals(params(4, null), parametersFor("0.01"));
    assertEquals(params(3, null), parametersFor("0.1"));
    assertEquals(params(2, null), parametersFor("1"));
    assertEquals(params(4, null), parametersFor("0.02"));
    assertEquals(params(2, null), parametersFor("10"));
  }

  @Test
  public void staleOrFinerTicksAndMissingReferenceFallBackToFullPrecision() {
    assertEquals(params(null, null), parametersFor("0.0001"));
    assertEquals(params(null, null), parametersFor("0.00005"));
    assertEquals(
        new L2BookParameters(null, Integer.valueOf(400), null),
        TickSizePlan.parametersFor(new BigDecimal("0.01"), null, 4, Integer.valueOf(400)));
    assertEquals(
        new L2BookParameters(Integer.valueOf(4), Integer.valueOf(400), null),
        TickSizePlan.parametersFor(
            new BigDecimal("0.01"), HYPE, HYPE_DECIMALS, Integer.valueOf(400)));
    assertEquals(params(null, null), TickSizePlan.parametersFor(null, HYPE, HYPE_DECIMALS, null));
  }

  @Test
  public void ratioCountsNativeGridStepsAndRejectsUnsupportedTicks() {
    assertEquals(10L, TickSizePlan.ratio(new BigDecimal("0.001"), 4));
    assertEquals(1L, TickSizePlan.ratio(new BigDecimal("0.0001"), 4));
    assertEquals(10000L, TickSizePlan.ratio(new BigDecimal("1"), 4));
    assertEquals(3L, TickSizePlan.ratio(new BigDecimal("0.0003"), 4));
    assertTrue(TickSizePlan.isSupportedTick(new BigDecimal("0.01"), 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("0.00005"), 4));
    assertFalse(TickSizePlan.isSupportedTick(BigDecimal.ZERO, 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("-0.01"), 4));
    assertFalse(TickSizePlan.isSupportedTick(null, 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("1000000000"), 4));
    try {
      TickSizePlan.ratio(new BigDecimal("0.00005"), 4);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // off-grid ticks are rejected
    }
  }

  private static L2BookParameters parametersFor(String tick) {
    return TickSizePlan.parametersFor(new BigDecimal(tick), HYPE, HYPE_DECIMALS, null);
  }

  private static L2BookParameters params(Integer nSigFigs, Integer mantissa) {
    return new L2BookParameters(nSigFigs, null, mantissa);
  }

  private static List<String> plain(List<BigDecimal> ticks) {
    List<String> result = new ArrayList<String>();
    for (BigDecimal tick : ticks) {
      result.add(tick.toPlainString());
    }
    return result;
  }
}
