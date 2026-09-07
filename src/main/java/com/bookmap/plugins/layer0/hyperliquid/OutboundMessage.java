package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;

/** Immutable WebSocket message owned by the Hyperliquid connector. */
public final class OutboundMessage {

  /** Outbound protocol message categories. */
  public enum Kind {
    SUBSCRIBE,
    UNSUBSCRIBE,
    PING,
    SUBSCRIBE_FEED
  }

  private final Kind kind;
  private final SubscriptionKey subscription;
  private final String body;

  /** Creates an outbound JSON message. PING and SUBSCRIBE_FEED messages have no subscription. */
  public OutboundMessage(Kind kind, SubscriptionKey subscription, String body) {
    if (kind == null || body == null) {
      throw new IllegalArgumentException("kind and body must not be null");
    }
    boolean connectionScoped = kind == Kind.PING || kind == Kind.SUBSCRIBE_FEED;
    if (connectionScoped != (subscription == null)) {
      throw new IllegalArgumentException(
          "only PING and SUBSCRIBE_FEED messages may omit a subscription");
    }
    this.kind = kind;
    this.subscription = subscription;
    this.body = body;
  }

  /** Returns the protocol message category. */
  public Kind kind() {
    return kind;
  }

  /** Returns the associated subscription, or null for PING. */
  public SubscriptionKey subscription() {
    return subscription;
  }

  /** Returns the exact JSON body. */
  public String body() {
    return body;
  }
}
