package com.bookmap.plugins.layer0.hyperliquid;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

/** Builds metaAndAssetCtxs responses for tests from a legacy universe object. */
public final class TestMetadata {

  private TestMetadata() {
    // static utility
  }

  /** Wraps a mark-price object as a fastAssetCtxs frame with Hyperliquid's payload encoding. */
  public static String assetContextsFrame(String markPriceJson) {
    return "{\"channel\":\"fastAssetCtxs\",\"data\":\"" + deflateBase64(markPriceJson) + "\"}";
  }

  private static String deflateBase64(String text) {
    Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
    try {
      byte[] input = text.getBytes(StandardCharsets.UTF_8);
      deflater.setInput(input);
      deflater.finish();
      ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 2 + 64);
      byte[] chunk = new byte[8192];
      while (!deflater.finished()) {
        out.write(chunk, 0, deflater.deflate(chunk));
      }
      return Base64.getEncoder().encodeToString(out.toByteArray());
    } finally {
      deflater.end();
    }
  }

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
