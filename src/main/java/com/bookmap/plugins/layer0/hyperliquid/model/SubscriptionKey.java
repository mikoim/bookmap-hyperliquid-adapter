package com.bookmap.plugins.layer0.hyperliquid.model;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Identifies one Hyperliquid market-data subscription. */
public final class SubscriptionKey implements Comparable<SubscriptionKey> {

  private final String coin;
  private final SubscriptionType type;
  private final L2BookParameters parameters;

  /** Creates a subscription definition without optional parameters. */
  public SubscriptionKey(String coin, SubscriptionType type) {
    this(coin, type, L2BookParameters.NONE);
  }

  /** Creates a subscription definition; parameters are only emitted for l2Book subscriptions. */
  public SubscriptionKey(String coin, SubscriptionType type, L2BookParameters parameters) {
    if (coin == null || coin.isEmpty()) {
      throw new IllegalArgumentException("coin must be non-empty");
    }
    if (parameters == null) {
      throw new IllegalArgumentException("parameters must not be null");
    }
    this.coin = coin;
    this.type = type;
    this.parameters = parameters;
  }

  /** Returns the subscription's coin. */
  public String coin() {
    return coin;
  }

  /** Returns the subscription's type. */
  public SubscriptionType type() {
    return type;
  }

  /** Returns the subscribe request JSON. */
  public String subscribeJson() {
    return json("subscribe");
  }

  /** Returns the unsubscribe request JSON. */
  public String unsubscribeJson() {
    return json("unsubscribe");
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SubscriptionKey)) {
      return false;
    }
    SubscriptionKey that = (SubscriptionKey) other;
    return coin.equals(that.coin) && type == that.type;
  }

  @Override
  public int hashCode() {
    return Objects.hash(coin, type);
  }

  @Override
  public int compareTo(SubscriptionKey other) {
    int coinComparison = coin.compareTo(other.coin);
    if (coinComparison != 0) {
      return coinComparison;
    }
    return type.compareTo(other.type);
  }

  private String json(String method) {
    JsonObject subscription = new JsonObject();
    subscription.addProperty("type", type.wireName());
    subscription.addProperty("coin", coin);
    if (type == SubscriptionType.L2_BOOK) {
      parameters.appendTo(subscription);
    }
    JsonObject envelope = new JsonObject();
    envelope.addProperty("method", method);
    envelope.add("subscription", subscription);
    return envelope.toString();
  }
}
