package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.MarketDataEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.model.TradeEvent;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Parses Hyperliquid WebSocket messages into market-data and control-frame results. */
public final class HyperliquidMessageParser {

  private static final long MAX_TID = (1L << 50) - 1L;

  /** Parses one WebSocket message without propagating malformed-input failures. */
  public ParsedFrame parse(String json) {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (!root.isJsonObject()) {
        return invalid("frame must be an object");
      }
      JsonObject object = root.getAsJsonObject();
      String channel = requiredString(object, "channel");
      if ("trades".equals(channel)) {
        return parseTrades(object);
      }
      if ("l2Book".equals(channel)) {
        return parseL2Book(object);
      }
      if ("subscriptionResponse".equals(channel)) {
        return parseSubscriptionResponse(object);
      }
      if ("error".equals(channel)) {
        return parseError(object);
      }
      if ("pong".equals(channel)) {
        return parsePong(object);
      }
      return ParsedFrame.ignored(Collections.singletonList("unsupported channel: " + channel));
    } catch (ProtocolException failure) {
      return invalid(failure.getMessage());
    } catch (RuntimeException failure) {
      return invalid("invalid frame JSON");
    }
  }

  private ParsedFrame parseTrades(JsonObject object) throws ProtocolException {
    JsonElement data = object.get("data");
    if (data == null || !data.isJsonArray()) {
      throw new ProtocolException("trades data must be an array");
    }
    List<MarketDataEvent> events = new ArrayList<MarketDataEvent>();
    List<String> diagnostics = new ArrayList<String>();
    for (JsonElement element : data.getAsJsonArray()) {
      try {
        if (!element.isJsonObject()) {
          throw new ProtocolException("trade must be an object");
        }
        events.add(parseTrade(element.getAsJsonObject()));
      } catch (ProtocolException failure) {
        diagnostics.add(failure.getMessage());
      } catch (RuntimeException failure) {
        diagnostics.add("invalid trade");
      }
    }
    if (events.isEmpty()) {
      diagnostics.add("trades frame contains no valid trades");
      return ParsedFrame.invalid(diagnostics);
    }
    return ParsedFrame.accepted(events, Collections.<ControlEvent>emptyList(), diagnostics);
  }

  private TradeEvent parseTrade(JsonObject trade) throws ProtocolException {
    String coin = requiredNonBlankString(trade, "coin");
    String side = requiredString(trade, "side");
    boolean buyAggressor;
    if ("B".equals(side)) {
      buyAggressor = true;
    } else if ("A".equals(side)) {
      buyAggressor = false;
    } else {
      throw new ProtocolException("trade side must be B or A");
    }
    BigDecimal price = requiredPositiveDecimalString(trade, "px");
    BigDecimal size = requiredPositiveDecimalString(trade, "sz");
    long time = requiredInteger(trade, "time", Long.MAX_VALUE);
    long tid = requiredInteger(trade, "tid", MAX_TID);
    return new TradeEvent(coin, time, tid, buyAggressor, price, size);
  }

  private ParsedFrame parseL2Book(JsonObject object) throws ProtocolException {
    JsonElement data = object.get("data");
    if (data == null || !data.isJsonObject()) {
      throw new ProtocolException("l2Book data must be an object");
    }
    JsonObject snapshot = data.getAsJsonObject();
    String coin = requiredNonBlankString(snapshot, "coin");
    long time = requiredInteger(snapshot, "time", Long.MAX_VALUE);
    JsonElement levels = snapshot.get("levels");
    if (levels == null || !levels.isJsonArray() || levels.getAsJsonArray().size() != 2) {
      throw new ProtocolException("l2Book levels must contain bid and ask sides");
    }
    List<BookLevel> bids = parseBookSide(levels.getAsJsonArray().get(0));
    List<BookLevel> asks = parseBookSide(levels.getAsJsonArray().get(1));
    List<MarketDataEvent> events = new ArrayList<MarketDataEvent>();
    events.add(new BookSnapshot(coin, time, bids, asks));
    return ParsedFrame.accepted(
        events, Collections.<ControlEvent>emptyList(), Collections.<String>emptyList());
  }

  private List<BookLevel> parseBookSide(JsonElement element) throws ProtocolException {
    if (!element.isJsonArray()) {
      throw new ProtocolException("book side must be an array");
    }
    List<BookLevel> levels = new ArrayList<BookLevel>();
    for (JsonElement level : element.getAsJsonArray()) {
      if (!level.isJsonObject()) {
        throw new ProtocolException("book level must be an object");
      }
      JsonObject object = level.getAsJsonObject();
      levels.add(
          new BookLevel(
              requiredPositiveDecimalString(object, "px"),
              requiredPositiveDecimalString(object, "sz")));
    }
    return levels;
  }

  private ParsedFrame parseSubscriptionResponse(JsonObject object) throws ProtocolException {
    JsonElement data = object.get("data");
    if (data == null || !data.isJsonObject()) {
      throw new ProtocolException("subscription response data must be an object");
    }
    JsonObject response = data.getAsJsonObject();
    String method = requiredString(response, "method");
    JsonElement subscription = response.get("subscription");
    if (subscription == null || !subscription.isJsonObject()) {
      throw new ProtocolException("subscription response must contain a subscription object");
    }
    SubscriptionKey key = parseSubscription(subscription.getAsJsonObject());
    if ("unsubscribe".equals(method)) {
      return ParsedFrame.ignored(Collections.<String>emptyList());
    }
    if (!"subscribe".equals(method)) {
      throw new ProtocolException("unsupported subscription response method");
    }
    ControlEvent event = new ControlEvent(ControlEvent.Kind.SUBSCRIPTION_ACK, key);
    return ParsedFrame.accepted(
        Collections.<MarketDataEvent>emptyList(),
        Collections.singletonList(event),
        Collections.<String>emptyList());
  }

  private ParsedFrame parseError(JsonObject object) {
    SubscriptionKey target = null;
    JsonElement data = object.get("data");
    if (data != null && data.isJsonObject()) {
      JsonElement subscription = data.getAsJsonObject().get("subscription");
      if (subscription != null && subscription.isJsonObject()) {
        try {
          target = parseSubscription(subscription.getAsJsonObject());
        } catch (ProtocolException ignored) {
          target = null;
        }
      }
    }
    ControlEvent event = new ControlEvent(ControlEvent.Kind.SUBSCRIPTION_ERROR, target);
    return ParsedFrame.accepted(
        Collections.<MarketDataEvent>emptyList(),
        Collections.singletonList(event),
        Collections.<String>emptyList());
  }

  private ParsedFrame parsePong(JsonObject object) throws ProtocolException {
    if (object.entrySet().size() != 1) {
      throw new ProtocolException("pong frame must contain only channel");
    }
    ControlEvent event = new ControlEvent(ControlEvent.Kind.PONG, null);
    return ParsedFrame.accepted(
        Collections.<MarketDataEvent>emptyList(),
        Collections.singletonList(event),
        Collections.<String>emptyList());
  }

  private SubscriptionKey parseSubscription(JsonObject subscription) throws ProtocolException {
    if (!subscription.has("type") || !subscription.has("coin")) {
      throw new ProtocolException("subscription must contain type and coin");
    }
    String type = requiredString(subscription, "type");
    String coin = requiredNonBlankString(subscription, "coin");
    for (Map.Entry<String, JsonElement> field : subscription.entrySet()) {
      if (!isAllowedSubscriptionField(type, field.getKey())) {
        throw new ProtocolException("subscription contains an unsupported field");
      }
    }
    for (SubscriptionType candidate : SubscriptionType.values()) {
      if (candidate.wireName().equals(type)) {
        return new SubscriptionKey(coin, candidate);
      }
    }
    throw new ProtocolException("unsupported subscription type");
  }

  private boolean isAllowedSubscriptionField(String type, String field) {
    if ("type".equals(field) || "coin".equals(field)) {
      return true;
    }
    return "l2Book".equals(type)
        && ("nSigFigs".equals(field) || "mantissa".equals(field) || "fast".equals(field));
  }

  private String requiredString(JsonObject object, String field) throws ProtocolException {
    JsonElement element = object.get(field);
    if (element == null || !element.isJsonPrimitive()) {
      throw new ProtocolException(field + " must be a string");
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isString()) {
      throw new ProtocolException(field + " must be a string");
    }
    return primitive.getAsString();
  }

  private String requiredNonBlankString(JsonObject object, String field) throws ProtocolException {
    String value = requiredString(object, field);
    if (value.trim().isEmpty()) {
      throw new ProtocolException(field + " must be non-blank");
    }
    return value;
  }

  private BigDecimal requiredPositiveDecimalString(JsonObject object, String field)
      throws ProtocolException {
    String raw = requiredString(object, field);
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.signum() <= 0) {
        throw new ProtocolException(field + " must be positive");
      }
      return value;
    } catch (NumberFormatException failure) {
      throw new ProtocolException(field + " must be a decimal string", failure);
    }
  }

  private long requiredInteger(JsonObject object, String field, long maximum)
      throws ProtocolException {
    JsonElement element = object.get(field);
    if (element == null || !element.isJsonPrimitive()) {
      throw new ProtocolException(field + " must be an integer");
    }
    JsonPrimitive primitive = element.getAsJsonPrimitive();
    if (!primitive.isNumber()) {
      throw new ProtocolException(field + " must be an integer");
    }
    String raw = primitive.toString();
    if (!raw.matches("0|[1-9][0-9]*")) {
      throw new ProtocolException(field + " must be a non-negative integer");
    }
    try {
      long value = Long.parseLong(raw);
      if (value > maximum) {
        throw new ProtocolException(field + " is out of range");
      }
      return value;
    } catch (NumberFormatException failure) {
      throw new ProtocolException(field + " is out of range", failure);
    }
  }

  private ParsedFrame invalid(String diagnostic) {
    return ParsedFrame.invalid(Collections.singletonList(diagnostic));
  }
}
