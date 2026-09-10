package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure tick-size arithmetic. Hyperliquid prices carry at most five significant figures and at most
 * {@code priceDecimals} decimals, and integer prices are always allowed; the reference price (login
 * mark price) therefore decides which absolute ticks the exchange actually quotes and which {@code
 * nSigFigs}/{@code mantissa} aggregation produces a given tick.
 */
public final class TickSizePlan {

  private static final int MAX_SIG_FIGS = 5;
  private static final int MIN_SIG_FIGS = 2;
  private static final int[] MANTISSAS = {1, 2, 5};

  private TickSizePlan() {
    // static utility
  }

  /** Returns the finest quantum the exchange quotes near the reference price. */
  public static BigDecimal nativeQuantum(BigDecimal referencePrice, int priceDecimals) {
    BigDecimal grid = pow10(-priceDecimals);
    if (!isUsable(referencePrice)) {
      return grid;
    }
    BigDecimal fiveSigFigs =
        pow10(integerDigits(referencePrice) - MAX_SIG_FIGS).min(BigDecimal.ONE);
    return fiveSigFigs.max(grid);
  }

  /** Returns ascending Bookmap tick candidates; the first one is the default. */
  public static List<BigDecimal> candidates(BigDecimal referencePrice, int priceDecimals) {
    BigDecimal quantum = nativeQuantum(referencePrice, priceDecimals);
    TreeSet<BigDecimal> ticks = new TreeSet<BigDecimal>();
    ticks.add(quantum);
    for (ServerQuantum server : serverQuanta(referencePrice)) {
      if (isMultiple(server.quantum, quantum)) {
        ticks.add(server.quantum);
      }
    }
    List<BigDecimal> result = new ArrayList<BigDecimal>();
    for (BigDecimal tick : ticks) {
      result.add(tick.stripTrailingZeros());
    }
    return Collections.unmodifiableList(result);
  }

  /** Returns the default tick: the finest quantum actually quoted near the reference price. */
  public static BigDecimal defaultTick(BigDecimal referencePrice, int priceDecimals) {
    return candidates(referencePrice, priceDecimals).get(0);
  }

  /**
   * Returns the coarsest server aggregation whose quantum divides the tick. Omitting nSigFigs wins
   * ties, and an unknown reference price or an unmatched tick keeps full precision.
   */
  public static L2BookParameters parametersFor(
      BigDecimal tick, BigDecimal referencePrice, int priceDecimals, Integer nLevels) {
    BigDecimal best = nativeQuantum(referencePrice, priceDecimals);
    Integer nSigFigs = null;
    Integer mantissa = null;
    if (tick != null && tick.signum() > 0) {
      BigDecimal quantum = best;
      for (ServerQuantum server : serverQuanta(referencePrice)) {
        if (isMultiple(server.quantum, quantum)
            && isMultiple(tick, server.quantum)
            && server.quantum.compareTo(best) > 0) {
          best = server.quantum;
          nSigFigs = server.nSigFigs;
          mantissa = server.mantissa;
        }
      }
    }
    return new L2BookParameters(nSigFigs, nLevels, mantissa);
  }

  /** Returns how many native grid steps one tick spans; rejects unsupported ticks. */
  public static long ratio(BigDecimal tick, int priceDecimals) {
    if (tick == null || tick.signum() <= 0) {
      throw new IllegalArgumentException("tick must be positive");
    }
    BigInteger steps;
    try {
      steps = tick.movePointRight(priceDecimals).toBigIntegerExact();
    } catch (ArithmeticException offGrid) {
      throw new IllegalArgumentException("tick must be a multiple of the native price grid");
    }
    if (steps.bitLength() > 31) {
      throw new IllegalArgumentException("tick is too coarse for int depth buckets");
    }
    return steps.longValue();
  }

  /** Returns whether {@link #ratio} accepts the tick. */
  public static boolean isSupportedTick(BigDecimal tick, int priceDecimals) {
    try {
      ratio(tick, priceDecimals);
      return true;
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  /** Server quanta from finest to coarsest: 5 sig figs with mantissa 1/2/5, then 4, 3, 2. */
  private static List<ServerQuantum> serverQuanta(BigDecimal referencePrice) {
    List<ServerQuantum> result = new ArrayList<ServerQuantum>();
    if (!isUsable(referencePrice)) {
      return result;
    }
    int digits = integerDigits(referencePrice);
    BigDecimal fiveSigFigs = pow10(digits - MAX_SIG_FIGS);
    for (int mantissa : MANTISSAS) {
      result.add(
          new ServerQuantum(
              fiveSigFigs.multiply(BigDecimal.valueOf(mantissa)),
              Integer.valueOf(MAX_SIG_FIGS),
              mantissa == 1 ? null : Integer.valueOf(mantissa)));
    }
    for (int sigFigs = MAX_SIG_FIGS - 1; sigFigs >= MIN_SIG_FIGS; sigFigs--) {
      result.add(new ServerQuantum(pow10(digits - sigFigs), Integer.valueOf(sigFigs), null));
    }
    return result;
  }

  private static boolean isUsable(BigDecimal price) {
    return price != null && price.signum() > 0;
  }

  /** Number of integer digits: 87.785 → 2, 0.0012345 → -2, 80203.0 → 5. */
  private static int integerDigits(BigDecimal price) {
    return price.precision() - price.scale();
  }

  private static BigDecimal pow10(int exponent) {
    return BigDecimal.ONE.scaleByPowerOfTen(exponent);
  }

  private static boolean isMultiple(BigDecimal value, BigDecimal unit) {
    return value.compareTo(unit) >= 0 && value.remainder(unit).signum() == 0;
  }

  private static final class ServerQuantum {
    private final BigDecimal quantum;
    private final Integer nSigFigs;
    private final Integer mantissa;

    private ServerQuantum(BigDecimal quantum, Integer nSigFigs, Integer mantissa) {
      this.quantum = quantum;
      this.nSigFigs = nSigFigs;
      this.mantissa = mantissa;
    }
  }
}
