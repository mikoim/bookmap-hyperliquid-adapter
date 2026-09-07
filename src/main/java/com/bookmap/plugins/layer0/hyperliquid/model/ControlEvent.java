package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A non-market-data event received from the Hyperliquid WebSocket protocol. */
public final class ControlEvent {

  /** The supported control-event categories. */
  public enum Kind {
    SUBSCRIPTION_ACK,
    SUBSCRIPTION_ERROR,
    PONG,
    ASSET_CONTEXTS
  }

  private final Kind kind;
  private final SubscriptionKey target;
  private final Map<String, BigDecimal> markPrices;

  /** Creates a control event, optionally targeted at one subscription. */
  public ControlEvent(Kind kind, SubscriptionKey target) {
    this(kind, target, Collections.<String, BigDecimal>emptyMap());
  }

  private ControlEvent(Kind kind, SubscriptionKey target, Map<String, BigDecimal> markPrices) {
    this.kind = kind;
    this.target = target;
    this.markPrices =
        Collections.unmodifiableMap(new LinkedHashMap<String, BigDecimal>(markPrices));
  }

  /** Creates an untargeted event carrying decoded mark prices keyed by coin name. */
  public static ControlEvent assetContexts(Map<String, BigDecimal> markPrices) {
    if (markPrices == null) {
      throw new IllegalArgumentException("markPrices must not be null");
    }
    return new ControlEvent(Kind.ASSET_CONTEXTS, null, markPrices);
  }

  /** Returns the control-event category. */
  public Kind kind() {
    return kind;
  }

  /** Returns the affected subscription, or null when the event is not targetable. */
  public SubscriptionKey target() {
    return target;
  }

  /** Returns the decoded mark prices; empty for every kind other than ASSET_CONTEXTS. */
  public Map<String, BigDecimal> markPrices() {
    return markPrices;
  }
}
