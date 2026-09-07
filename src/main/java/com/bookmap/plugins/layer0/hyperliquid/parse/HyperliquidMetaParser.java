package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses and validates Hyperliquid perpetual-instrument metadata responses. */
public final class HyperliquidMetaParser {

  /**
   * Parses a complete metaAndAssetCtxs response, filtering delisted entries only after validation.
   * The response is {@code [meta, ctxs]}; {@code ctxs[i].markPx} becomes the reference price of
   * {@code meta.universe[i]} when it is a positive decimal string, and null otherwise.
   */
  public List<PerpetualInstrument> parse(String json) throws ProtocolException {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (!root.isJsonArray() || root.getAsJsonArray().size() != 2) {
        throw new ProtocolException("metadata response must be a two-element array");
      }
      JsonElement meta = root.getAsJsonArray().get(0);
      JsonElement contexts = root.getAsJsonArray().get(1);
      if (!meta.isJsonObject()) {
        throw new ProtocolException("metadata must be an object");
      }
      JsonElement universe = meta.getAsJsonObject().get("universe");
      if (universe == null || !universe.isJsonArray()) {
        throw new ProtocolException("universe must be an array");
      }
      JsonArray universeArray = universe.getAsJsonArray();
      if (!contexts.isJsonArray() || contexts.getAsJsonArray().size() != universeArray.size()) {
        throw new ProtocolException("asset contexts must match the universe");
      }
      JsonArray contextArray = contexts.getAsJsonArray();

      List<MetadataEntry> entries = new ArrayList<MetadataEntry>();
      Set<String> names = new HashSet<String>();
      for (int index = 0; index < universeArray.size(); index++) {
        entries.add(parseEntry(universeArray.get(index), names, contextArray.get(index)));
      }

      List<PerpetualInstrument> instruments = new ArrayList<PerpetualInstrument>();
      for (MetadataEntry entry : entries) {
        if (!entry.delisted) {
          instruments.add(
              new PerpetualInstrument(entry.name, entry.sizeDecimals, entry.referencePrice));
        }
      }
      return instruments;
    } catch (ProtocolException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ProtocolException("invalid metadata JSON", failure);
    }
  }

  private MetadataEntry parseEntry(JsonElement element, Set<String> names, JsonElement context)
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

    JsonElement sizeElement = object.get("szDecimals");
    if (sizeElement == null
        || !sizeElement.isJsonPrimitive()
        || !sizeElement.getAsJsonPrimitive().isNumber()) {
      throw new ProtocolException("szDecimals must be an integer");
    }
    String rawSizeDecimals = sizeElement.getAsJsonPrimitive().toString();
    if (!rawSizeDecimals.matches("0|[1-6]") || rawSizeDecimals.length() != 1) {
      throw new ProtocolException("szDecimals must be between 0 and 6");
    }

    JsonElement delisted = object.get("isDelisted");
    boolean isDelisted = false;
    if (delisted != null) {
      if (!delisted.isJsonPrimitive() || !delisted.getAsJsonPrimitive().isBoolean()) {
        throw new ProtocolException("isDelisted must be a boolean");
      }
      isDelisted = delisted.getAsBoolean();
    }
    return new MetadataEntry(
        name, Integer.parseInt(rawSizeDecimals), isDelisted, referencePrice(context));
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

  /** Reads a positive decimal markPx string; anything else yields no reference price. */
  private static BigDecimal referencePrice(JsonElement context) {
    if (context == null || !context.isJsonObject()) {
      return null;
    }
    JsonElement markPx = context.getAsJsonObject().get("markPx");
    if (markPx == null || !markPx.isJsonPrimitive() || !markPx.getAsJsonPrimitive().isString()) {
      return null;
    }
    try {
      BigDecimal price = new BigDecimal(markPx.getAsString());
      return price.signum() > 0 ? price : null;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  private static final class MetadataEntry {
    private final String name;
    private final int sizeDecimals;
    private final boolean delisted;
    private final BigDecimal referencePrice;

    private MetadataEntry(
        String name, int sizeDecimals, boolean delisted, BigDecimal referencePrice) {
      this.name = name;
      this.sizeDecimals = sizeDecimals;
      this.delisted = delisted;
      this.referencePrice = referencePrice;
    }
  }
}
