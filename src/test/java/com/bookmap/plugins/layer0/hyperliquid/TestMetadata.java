package com.bookmap.plugins.layer0.hyperliquid;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Builds metaAndAssetCtxs responses for tests from a legacy universe object. */
public final class TestMetadata {

  private TestMetadata() {}

  /** Returns a universe object listing the symbols with szDecimals 2. */
  public static String universe(String... symbols) {
    StringBuilder result = new StringBuilder("{\"universe\":[");
    for (int i = 0; i < symbols.length; i++) {
      if (i != 0) {
        result.append(',');
      }
      result.append("{\"name\":\"").append(symbols[i]).append("\",\"szDecimals\":2}");
    }
    return result.append("]}").toString();
  }

  /** Wraps a universe object as {@code [meta, ctxs]} with one empty context per entry. */
  public static String wrap(String metaObject) {
    return wrap(metaObject, new String[0]);
  }

  /** Wraps a universe object as {@code [meta, ctxs]}; each markPx (or null) fills one context. */
  public static String wrap(String metaObject, String... markPxs) {
    int count = universeSize(metaObject);
    StringBuilder contexts = new StringBuilder("[");
    for (int i = 0; i < count; i++) {
      if (i != 0) {
        contexts.append(',');
      }
      if (i < markPxs.length && markPxs[i] != null) {
        contexts.append("{\"markPx\":\"").append(markPxs[i]).append("\"}");
      } else {
        contexts.append("{}");
      }
    }
    return "[" + metaObject + "," + contexts.append(']') + "]";
  }

  private static int universeSize(String metaObject) {
    try {
      JsonElement root = new JsonParser().parse(metaObject);
      JsonElement universe = root.isJsonObject() ? root.getAsJsonObject().get("universe") : null;
      return universe != null && universe.isJsonArray() ? universe.getAsJsonArray().size() : 0;
    } catch (RuntimeException invalid) {
      return 0;
    }
  }
}
