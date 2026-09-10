package com.bookmap.plugins.layer0.hyperliquid;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

/** Builds allPerpMetas and spotMeta responses and fastAssetCtxs frames for tests. */
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

  /** Returns a spotMeta response with no tokens and no pairs: the default for perp-only tests. */
  public static String emptySpotMeta() {
    return "{\"tokens\":[],\"universe\":[]}";
  }

  /** Wraps token and pair JSON fragments as a spotMeta response. */
  public static String spotMeta(String tokensJson, String universeJson) {
    return "{\"tokens\":[" + tokensJson + "],\"universe\":[" + universeJson + "]}";
  }

  /** Returns one spotMeta token entry with the fields the adapter reads plus realistic noise. */
  public static String spotToken(int index, String name, int szDecimals) {
    return "{\"name\":\""
        + name
        + "\",\"szDecimals\":"
        + szDecimals
        + ",\"weiDecimals\":8,\"index\":"
        + index
        + ",\"tokenId\":\"0x0\",\"isCanonical\":false,\"evmContract\":null,\"fullName\":null}";
  }

  /** Returns one spotMeta universe entry. */
  public static String spotPair(String name, int baseIndex, int quoteIndex) {
    return "{\"name\":\""
        + name
        + "\",\"tokens\":["
        + baseIndex
        + ","
        + quoteIndex
        + "],\"index\":"
        + Math.max(baseIndex - 1, 0)
        + ",\"isCanonical\":false}";
  }

  /**
   * Returns a spotMeta response quoting every base in USDC (token 0, szDecimals 8). Base {@code i}
   * is token {@code i + 1} with szDecimals 2, and its pair is {@code @(i + 1)}, so the first base
   * becomes {@code BASE/USDC} with coin {@code @1}.
   */
  public static String spotMetaWithUsdcPairs(String... baseNames) {
    StringBuilder tokens = new StringBuilder(spotToken(0, "USDC", 8));
    StringBuilder universe = new StringBuilder();
    for (int i = 0; i < baseNames.length; i++) {
      tokens.append(',').append(spotToken(i + 1, baseNames[i], 2));
      if (i != 0) {
        universe.append(',');
      }
      universe.append(spotPair("@" + (i + 1), i + 1, 0));
    }
    return spotMeta(tokens.toString(), universe.toString());
  }
}
