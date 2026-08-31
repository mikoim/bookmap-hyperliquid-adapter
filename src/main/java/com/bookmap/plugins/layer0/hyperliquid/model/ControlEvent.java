package com.bookmap.plugins.layer0.hyperliquid.model;

/** A non-market-data event received from the Hyperliquid WebSocket protocol. */
public final class ControlEvent {

  /** The supported control-event categories. */
  public enum Kind {
    SUBSCRIPTION_ACK,
    SUBSCRIPTION_ERROR,
    PONG
  }

  private final Kind kind;
  private final SubscriptionKey target;

  /** Creates a control event, optionally targeted at one subscription. */
  public ControlEvent(Kind kind, SubscriptionKey target) {
    this.kind = kind;
    this.target = target;
  }

  /** Returns the control-event category. */
  public Kind kind() {
    return kind;
  }

  /** Returns the affected subscription, or null when the event is not targetable. */
  public SubscriptionKey target() {
    return target;
  }
}
