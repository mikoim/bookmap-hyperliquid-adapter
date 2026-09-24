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
import java.util.regex.Pattern;

/** Parses Hyperliquid WebSocket messages into market-data and control-frame results. */
public final class HyperliquidMessageParser {

  private static final long MAX_TID = (1L << 50) - 1L;
  private static final Pattern TRANSACTION_HASH = Pattern.compile("0x[0-9a-fA-F]+");

  private final AssetContextCodec assetContextCodec = new AssetContextCodec();

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
      if ("fastAssetCtxs".equals(channel)) {
        return parseAssetContexts(object);
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
    return new TradeEvent(coin, time, tid, buyAggressor, price, size, executionId(trade));
  }

  /**
   * Returns the transaction hash as the execution identifier. The field is optional and never
   * rejects a trade: a missing, malformed or all-zero hash means the fill stands alone. All-zero
   * hashes are real; the exchange sends them for fills no user transaction caused.
   */
  private String executionId(JsonObject trade) {
    JsonElement element = trade.get("hash");
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      return null;
    }
    String hash = element.getAsString();
    if (!TRANSACTION_HASH.matcher(hash).matches()) {
      return null;
    }
    for (int index = 2; index < hash.length(); index++) {
      if (hash.charAt(index) != '0') {
        return hash;
      }
    }
    return null;
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
              requiredNonNegativeDecimalString(object, "sz")));
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
    JsonObject subscriptionObject = subscription.getAsJsonObject();
    if (isFastAssetCtxsSubscription(subscriptionObject) && !subscriptionObject.has("coin")) {
      // fastAssetCtxs is a connection-scoped feed and acknowledges without a coin.
      return ParsedFrame.ignored(Collections.<String>emptyList());
    }
    SubscriptionKey key = parseSubscription(subscriptionObject);
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

  private boolean isFastAssetCtxsSubscription(JsonObject subscription) {
    JsonElement type = subscription.get("type");
    return type != null
        && type.isJsonPrimitive()
        && type.getAsJsonPrimitive().isString()
        && "fastAssetCtxs".equals(type.getAsString());
  }

  /**
   * Turns an error frame into a subscription error. The exchange rejects a subscription with a
   * string such as {@code Invalid subscription {"type":"l2Book","coin":"BTC"}}; that embedded
   * subscription, or a structured {@code data.subscription} object, becomes the event's target. An
   * error naming no market-data subscription is an untargeted event plus a diagnostic; the receiver
   * decides what an untargeted error means on its connection.
   */
  private ParsedFrame parseError(JsonObject object) {
    JsonElement data = object.get("data");
    if (data != null && data.toString().contains("fastAssetCtxs")) {
      return ParsedFrame.ignored(
          Collections.singletonList("fastAssetCtxs subscription rejected: " + data));
    }
    SubscriptionKey target = errorTarget(data);
    List<String> diagnostics =
        target == null
            ? Collections.singletonList("error not attributable to one subscription: " + data)
            : Collections.<String>emptyList();
    ControlEvent event = new ControlEvent(ControlEvent.Kind.SUBSCRIPTION_ERROR, target);
    return ParsedFrame.accepted(
        Collections.<MarketDataEvent>emptyList(), Collections.singletonList(event), diagnostics);
  }

  private SubscriptionKey errorTarget(JsonElement data) {
    if (data == null) {
      return null;
    }
    JsonElement subscription = null;
    if (data.isJsonObject()) {
      subscription = data.getAsJsonObject().get("subscription");
    } else if (data.isJsonPrimitive() && data.getAsJsonPrimitive().isString()) {
      subscription = embeddedJson(data.getAsString());
    }
    if (subscription == null || !subscription.isJsonObject()) {
      return null;
    }
    try {
      return parseSubscription(subscription.getAsJsonObject());
    } catch (ProtocolException ignored) {
      return null;
    }
  }

  /** Returns the JSON object embedded in an error string, or null when there is none. */
  private static JsonElement embeddedJson(String message) {
    int start = message.indexOf('{');
    int end = message.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      return new JsonParser().parse(message.substring(start, end + 1));
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  private ParsedFrame parseAssetContexts(JsonObject object) throws ProtocolException {
    JsonElement data = object.get("data");
    if (data == null || !data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) {
      throw new ProtocolException("fastAssetCtxs data must be a string");
    }
    ControlEvent event = ControlEvent.assetContexts(assetContextCodec.decode(data.getAsString()));
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
        && ("nSigFigs".equals(field)
            || "nLevels".equals(field)
            || "mantissa".equals(field)
            || "fast".equals(field));
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

  private BigDecimal requiredNonNegativeDecimalString(JsonObject object, String field)
      throws ProtocolException {
    String raw = requiredString(object, field);
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.signum() < 0) {
        throw new ProtocolException(field + " must be non-negative");
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
