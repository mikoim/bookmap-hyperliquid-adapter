package com.bookmap.plugins.layer0.hyperliquid.model;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Identifies one Hyperliquid market-data subscription. */
public final class SubscriptionKey implements Comparable<SubscriptionKey> {

  private final String coin;
  private final SubscriptionType type;

  /** Creates a subscription definition. */
  public SubscriptionKey(String coin, SubscriptionType type) {
    if (coin == null || coin.isEmpty()) {
      throw new IllegalArgumentException("coin must be non-empty");
    }
    this.coin = coin;
    this.type = type;
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
    JsonObject envelope = new JsonObject();
    envelope.addProperty("method", method);
    envelope.add("subscription", subscription);
    return envelope.toString();
  }
}
