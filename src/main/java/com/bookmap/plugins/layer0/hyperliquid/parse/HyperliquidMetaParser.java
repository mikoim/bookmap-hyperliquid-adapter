package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.Market;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses and validates Hyperliquid instrument metadata: every perp dex and the spot universe. */
public final class HyperliquidMetaParser {

  /**
   * Parses an allPerpMetas response: one element per perp dex, each an object with a universe.
   * HIP-3 names arrive fully qualified as {@code dex:coin}, so no dex prefix is applied here.
   * Delisted entries are filtered only after every entry has been validated. An empty root array is
   * rejected; a response whose entries are all delisted yields an empty instrument list.
   */
  public List<Instrument> parseAllPerpMetas(String json) throws ProtocolException {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (root == null || !root.isJsonArray()) {
        throw new ProtocolException("allPerpMetas response must be an array");
      }
      // Zero perp dexes would log in with an empty universe and then fail every subscription with
      // an opaque "instrument not found"; reject it here instead. An all-delisted response is a
      // legitimate result and still succeeds.
      if (root.getAsJsonArray().size() == 0) {
        throw new ProtocolException("allPerpMetas response must contain at least one perp dex");
      }
      List<MetadataEntry> entries = new ArrayList<MetadataEntry>();
      Set<String> names = new HashSet<String>();
      for (JsonElement dex : root.getAsJsonArray()) {
        if (!dex.isJsonObject()) {
          throw new ProtocolException("perp dex metadata must be an object");
        }
        JsonElement universe = dex.getAsJsonObject().get("universe");
        if (universe == null || !universe.isJsonArray()) {
          throw new ProtocolException("universe must be an array");
        }
        for (JsonElement element : universe.getAsJsonArray()) {
          entries.add(parseEntry(element, names));
        }
      }

      List<Instrument> instruments = new ArrayList<Instrument>();
      for (MetadataEntry entry : entries) {
        if (!entry.delisted) {
          instruments.add(Instrument.perpetual(entry.name, entry.sizeDecimals));
        }
      }
      return instruments;
    } catch (ProtocolException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ProtocolException("invalid metadata JSON", failure);
    }
  }

  private MetadataEntry parseEntry(JsonElement element, Set<String> names)
      throws ProtocolException {
    if (!element.isJsonObject()) {
      throw new ProtocolException("universe entry must be an object");
    }
    JsonObject object = element.getAsJsonObject();
    String name = requiredString(object, "name");
    if (name.trim().isEmpty()) {
      throw new ProtocolException("name must be non-blank");
    }
    if (!names.add(name)) {
      throw new ProtocolException("universe contains duplicate name");
    }

    int sizeDecimals = requiredSizeDecimals(object, Market.PERPETUAL.maxDecimals());

    JsonElement delisted = object.get("isDelisted");
    boolean isDelisted = false;
    if (delisted != null) {
      if (!delisted.isJsonPrimitive() || !delisted.getAsJsonPrimitive().isBoolean()) {
        throw new ProtocolException("isDelisted must be a boolean");
      }
      isDelisted = delisted.getAsBoolean();
    }
    return new MetadataEntry(name, sizeDecimals, isDelisted);
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

  /**
   * Parses a spotMeta response: {@code tokens} describe each token by index, {@code universe} lists
   * pairs as {@code [baseIndex, quoteIndex]}. The Bookmap symbol is {@code BASE/QUOTE}, the coin is
   * the pair's own name ({@code @index}, or {@code PURR/USDC}), and the size scale is the base
   * token's. An empty universe is legitimate and yields no instruments.
   */
  public List<Instrument> parseSpotMeta(String json) throws ProtocolException {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (root == null || !root.isJsonObject()) {
        throw new ProtocolException("spotMeta response must be an object");
      }
      JsonElement tokens = root.getAsJsonObject().get("tokens");
      if (tokens == null || !tokens.isJsonArray()) {
        throw new ProtocolException("tokens must be an array");
      }
      JsonElement universe = root.getAsJsonObject().get("universe");
      if (universe == null || !universe.isJsonArray()) {
        throw new ProtocolException("universe must be an array");
      }
      Map<Integer, SpotToken> tokensByIndex = new HashMap<Integer, SpotToken>();
      for (JsonElement element : tokens.getAsJsonArray()) {
        SpotToken token = parseToken(element);
        if (tokensByIndex.put(Integer.valueOf(token.index), token) != null) {
          throw new ProtocolException("tokens contains duplicate index " + token.index);
        }
      }
      List<Instrument> instruments = new ArrayList<Instrument>();
      Set<String> coins = new HashSet<String>();
      Set<String> symbols = new HashSet<String>();
      for (JsonElement element : universe.getAsJsonArray()) {
        if (!element.isJsonObject()) {
          throw new ProtocolException("universe entry must be an object");
        }
        JsonObject pair = element.getAsJsonObject();
        String coin = requiredString(pair, "name");
        if (coin.trim().isEmpty()) {
          throw new ProtocolException("name must be non-blank");
        }
        JsonElement pairTokens = pair.get("tokens");
        if (pairTokens == null
            || !pairTokens.isJsonArray()
            || pairTokens.getAsJsonArray().size() != 2) {
          throw new ProtocolException("pair tokens must hold exactly two indices");
        }
        SpotToken base = resolveToken(tokensByIndex, pairTokens.getAsJsonArray().get(0));
        SpotToken quote = resolveToken(tokensByIndex, pairTokens.getAsJsonArray().get(1));
        String symbol = base.name + "/" + quote.name;
        if (!coins.add(coin)) {
          throw new ProtocolException("universe contains duplicate name " + coin);
        }
        if (!symbols.add(symbol)) {
          throw new ProtocolException("universe contains duplicate pair " + symbol);
        }
        instruments.add(Instrument.spot(symbol, coin, base.sizeDecimals));
      }
      return instruments;
    } catch (ProtocolException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ProtocolException("invalid metadata JSON", failure);
    }
  }

  /**
   * Concatenates perpetual and spot instruments, rejecting any symbol or coin that appears twice.
   * Perp names never contain a slash and spot coins are {@code @index}, so a collision means the
   * exchange changed its naming; failing login is safer than guessing which market a name means.
   */
  public static List<Instrument> combine(List<Instrument> perpetuals, List<Instrument> spots)
      throws ProtocolException {
    List<Instrument> combined = new ArrayList<Instrument>(perpetuals.size() + spots.size());
    combined.addAll(perpetuals);
    combined.addAll(spots);
    Set<String> symbols = new HashSet<String>();
    Set<String> coins = new HashSet<String>();
    for (Instrument instrument : combined) {
      if (!symbols.add(instrument.symbol())) {
        throw new ProtocolException("instrument symbol is not unique: " + instrument.symbol());
      }
      if (!coins.add(instrument.coin())) {
        throw new ProtocolException("instrument coin is not unique: " + instrument.coin());
      }
    }
    return combined;
  }

  private SpotToken parseToken(JsonElement element) throws ProtocolException {
    if (!element.isJsonObject()) {
      throw new ProtocolException("token entry must be an object");
    }
    JsonObject object = element.getAsJsonObject();
    String name = requiredString(object, "name");
    if (name.trim().isEmpty()) {
      throw new ProtocolException("token name must be non-blank");
    }
    return new SpotToken(
        requiredIndex(object.get("index"), "token index"),
        name,
        requiredSizeDecimals(object, Market.SPOT.maxDecimals()));
  }

  private SpotToken resolveToken(Map<Integer, SpotToken> tokensByIndex, JsonElement indexElement)
      throws ProtocolException {
    int index = requiredIndex(indexElement, "pair token index");
    SpotToken token = tokensByIndex.get(Integer.valueOf(index));
    if (token == null) {
      throw new ProtocolException("unknown token index " + index);
    }
    return token;
  }

  private int requiredIndex(JsonElement element, String field) throws ProtocolException {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      throw new ProtocolException(field + " must be an integer");
    }
    String raw = element.getAsJsonPrimitive().toString();
    if (!raw.matches("0|[1-9][0-9]{0,8}")) {
      throw new ProtocolException(field + " must be a non-negative integer");
    }
    return Integer.parseInt(raw);
  }

  private int requiredSizeDecimals(JsonObject object, int maxDecimals) throws ProtocolException {
    JsonElement sizeElement = object.get("szDecimals");
    if (sizeElement == null
        || !sizeElement.isJsonPrimitive()
        || !sizeElement.getAsJsonPrimitive().isNumber()) {
      throw new ProtocolException("szDecimals must be an integer");
    }
    String raw = sizeElement.getAsJsonPrimitive().toString();
    if (!raw.matches("[0-9]") || Integer.parseInt(raw) > maxDecimals) {
      throw new ProtocolException("szDecimals must be between 0 and " + maxDecimals);
    }
    return Integer.parseInt(raw);
  }

  private static final class SpotToken {
    private final int index;
    private final String name;
    private final int sizeDecimals;

    private SpotToken(int index, String name, int sizeDecimals) {
      this.index = index;
      this.name = name;
      this.sizeDecimals = sizeDecimals;
    }
  }

  private static final class MetadataEntry {
    private final String name;
    private final int sizeDecimals;
    private final boolean delisted;

    private MetadataEntry(String name, int sizeDecimals, boolean delisted) {
      this.name = name;
      this.sizeDecimals = sizeDecimals;
      this.delisted = delisted;
    }
  }
}
