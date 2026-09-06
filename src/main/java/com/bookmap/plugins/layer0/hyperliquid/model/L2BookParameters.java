package com.bookmap.plugins.layer0.hyperliquid.model;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Optional Hyperliquid l2Book subscription parameters; a null parameter is omitted on the wire. */
public final class L2BookParameters {

  /** No optional parameters: the minimal Hyperliquid subscription. */
  public static final L2BookParameters NONE = new L2BookParameters(null, null, null);

  private final Integer nSigFigs;
  private final Integer nLevels;
  private final Integer mantissa;

  /** Creates parameters; each may be null to omit it from the subscription JSON. */
  public L2BookParameters(Integer nSigFigs, Integer nLevels, Integer mantissa) {
    this.nSigFigs = nSigFigs;
    this.nLevels = nLevels;
    this.mantissa = mantissa;
  }

  /** Returns the significant-figure aggregation, or null when omitted. */
  public Integer nSigFigs() {
    return nSigFigs;
  }

  /** Returns the requested depth per side, or null when omitted. */
  public Integer nLevels() {
    return nLevels;
  }

  /** Returns the mantissa aggregation, or null when omitted. */
  public Integer mantissa() {
    return mantissa;
  }

  /** Adds every non-null parameter to a subscription object. */
  public void appendTo(JsonObject subscription) {
    if (nSigFigs != null) {
      subscription.addProperty("nSigFigs", nSigFigs);
    }
    if (nLevels != null) {
      subscription.addProperty("nLevels", nLevels);
    }
    if (mantissa != null) {
      subscription.addProperty("mantissa", mantissa);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof L2BookParameters)) {
      return false;
    }
    L2BookParameters that = (L2BookParameters) other;
    return Objects.equals(nSigFigs, that.nSigFigs)
        && Objects.equals(nLevels, that.nLevels)
        && Objects.equals(mantissa, that.mantissa);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nSigFigs, nLevels, mantissa);
  }
}
