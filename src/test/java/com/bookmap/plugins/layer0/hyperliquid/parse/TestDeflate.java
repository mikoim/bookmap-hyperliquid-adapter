package com.bookmap.plugins.layer0.hyperliquid.parse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

/** Builds base64 + raw DEFLATE payloads the way Hyperliquid encodes fastAssetCtxs data. */
public final class TestDeflate {

  private TestDeflate() {
    // static utility
  }

  /** Compresses UTF-8 text as raw DEFLATE and returns its base64 encoding. */
  public static String encode(String text) {
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
}
