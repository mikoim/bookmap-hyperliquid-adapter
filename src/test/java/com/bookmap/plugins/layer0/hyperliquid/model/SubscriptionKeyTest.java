package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests exact Hyperliquid subscription definitions. */
public class SubscriptionKeyTest {

  /** Emits the complete l2-book subscribe payload without optional subscription fields. */
  @Test
  public void createsExactL2BookSubscribeJson() {
    SubscriptionKey key = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);

    JsonObject actual = new JsonParser().parse(key.subscribeJson()).getAsJsonObject();
    JsonObject expected =
        new JsonParser()
            .parse(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\"}}")
            .getAsJsonObject();
    assertEquals(expected, actual);
    JsonObject subscription = actual.getAsJsonObject("subscription");
    assertFalse(subscription.has("dex"));
    assertFalse(subscription.has("fast"));
    assertFalse(subscription.has("nSigFigs"));
    assertFalse(subscription.has("mantissa"));
  }

  /** Emits the complete trades unsubscribe payload. */
  @Test
  public void createsExactTradesUnsubscribeJson() {
    SubscriptionKey key = new SubscriptionKey("ETH", SubscriptionType.TRADES);

    JsonObject actual = new JsonParser().parse(key.unsubscribeJson()).getAsJsonObject();
    JsonObject expected =
        new JsonParser()
            .parse(
                "{\"method\":\"unsubscribe\",\"subscription\":"
                    + "{\"type\":\"trades\",\"coin\":\"ETH\"}}")
            .getAsJsonObject();
    assertEquals(expected, actual);
  }

  /** Rejects an empty coin because it cannot identify a Hyperliquid market. */
  @Test
  public void rejectsEmptyCoin() {
    boolean rejected = false;
    try {
      new SubscriptionKey("", SubscriptionType.L2_BOOK);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    assertTrue("Expected an empty coin to be rejected", rejected);
  }

  /** Treats independently created definitions for the same wire subscription as equal. */
  @Test
  public void comparesEqualSubscriptionDefinitionsByValue() {
    SubscriptionKey first = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);
    SubscriptionKey second = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  /** Orders subscriptions by coin first and then their type. */
  @Test
  public void sortsByCoinThenType() {
    List<SubscriptionKey> keys = new ArrayList<SubscriptionKey>();
    keys.add(new SubscriptionKey("ETH", SubscriptionType.L2_BOOK));
    keys.add(new SubscriptionKey("BTC", SubscriptionType.TRADES));
    keys.add(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK));

    Collections.sort(keys);

    assertEquals(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), keys.get(0));
    assertEquals(new SubscriptionKey("BTC", SubscriptionType.TRADES), keys.get(1));
    assertEquals(new SubscriptionKey("ETH", SubscriptionType.L2_BOOK), keys.get(2));
  }

  /** Keeps snapshot sides independent from later caller changes and exposes immutable views. */
  @Test
  public void makesBookSnapshotSidesImmutableCopies() {
    BookLevel bid = new BookLevel(new BigDecimal("100.00"), new BigDecimal("2.5"));
    List<BookLevel> bids = new ArrayList<BookLevel>();
    bids.add(bid);
    BookSnapshot snapshot =
        new BookSnapshot("BTC", 1234L, bids, Collections.<BookLevel>emptyList());

    bids.clear();

    assertEquals(1, snapshot.bids().size());
    assertEquals(bid, snapshot.bids().get(0));
    try {
      snapshot.bids().add(bid);
    } catch (UnsupportedOperationException expected) {
      return;
    }
    throw new AssertionError("Expected snapshot sides to be immutable");
  }

  /** Derives a value-equal trade identity from its market event fields. */
  @Test
  public void derivesTradeKeyFromTradeEvent() {
    TradeEvent event =
        new TradeEvent(
            "BTC", 1720000000000L, 42L, true, new BigDecimal("100.25"), new BigDecimal("1.5"));
    TradeKey expected = new TradeKey("BTC", 1720000000000L, 42L);

    assertEquals(expected, event.key());
    assertEquals(expected.hashCode(), event.key().hashCode());
  }

  /** Treats identical normalized depth changes as the same value. */
  @Test
  public void comparesDepthUpdatesByValue() {
    DepthUpdate first = new DepthUpdate(true, 100_250, 1500);
    DepthUpdate second = new DepthUpdate(true, 100_250, 1500);

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  /** Optional parameters are emitted for l2Book only, and never affect key identity. */
  @Test
  public void appendsL2BookParametersWithoutChangingIdentity() {
    L2BookParameters parameters = new L2BookParameters(Integer.valueOf(5), null, null);
    SubscriptionKey key = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK, parameters);

    JsonObject actual = new JsonParser().parse(key.subscribeJson()).getAsJsonObject();
    JsonObject expected =
        new JsonParser()
            .parse(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\",\"nSigFigs\":5}}")
            .getAsJsonObject();
    assertEquals(expected, actual);
    assertEquals(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), key);
    assertEquals(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).hashCode(), key.hashCode());
    assertEquals(0, new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).compareTo(key));
  }

  /** Trades subscriptions ignore l2Book parameters entirely. */
  @Test
  public void tradesIgnoreL2BookParameters() {
    SubscriptionKey key =
        new SubscriptionKey(
            "BTC", SubscriptionType.TRADES, new L2BookParameters(Integer.valueOf(5), null, null));

    assertEquals(
        new SubscriptionKey("BTC", SubscriptionType.TRADES).subscribeJson(), key.subscribeJson());
  }
}
