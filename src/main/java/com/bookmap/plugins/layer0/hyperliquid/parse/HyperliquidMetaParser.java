package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses and validates Hyperliquid perpetual-instrument metadata for every perp dex. */
public final class HyperliquidMetaParser {

  /**
   * Parses an allPerpMetas response: one element per perp dex, each an object with a universe.
   * HIP-3 names arrive fully qualified as {@code dex:coin}, so no dex prefix is applied here.
   * Delisted entries are filtered only after every entry has been validated.
   */
  public List<PerpetualInstrument> parseAllPerpMetas(String json) throws ProtocolException {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (root == null || !root.isJsonArray()) {
        throw new ProtocolException("allPerpMetas response must be an array");
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

      List<PerpetualInstrument> instruments = new ArrayList<PerpetualInstrument>();
      for (MetadataEntry entry : entries) {
        if (!entry.delisted) {
          instruments.add(new PerpetualInstrument(entry.name, entry.sizeDecimals));
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
    return new MetadataEntry(name, Integer.parseInt(rawSizeDecimals), isDelisted);
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
