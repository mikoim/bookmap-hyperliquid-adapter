package com.bookmap.plugins.layer0.hyperliquid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

/** Builds allPerpMetas responses and fastAssetCtxs frames for tests. */
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

  /** Wraps universe objects as an allPerpMetas response, one element per perp dex. */
  public static String allPerpMetas(String... metaObjects) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < metaObjects.length; index++) {
      if (index != 0) {
        result.append(',');
      }
      result.append(metaObjects[index]);
    }
    return result.append(']').toString();
  }
}
