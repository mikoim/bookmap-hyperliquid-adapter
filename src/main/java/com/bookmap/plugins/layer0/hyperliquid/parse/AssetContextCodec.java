package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the {@code fastAssetCtxs} payload: base64 wrapping a raw DEFLATE stream (RFC 1951, no
 * zlib or gzip header) whose UTF-8 text is a JSON object keyed by fully qualified coin name.
 */
public final class AssetContextCodec {

  static final int MAX_DECOMPRESSED_BYTES = 8 * 1024 * 1024;

  private static final int CHUNK_BYTES = 8192;
  private static final int MAX_PRICE_DIGITS = 20;

  /** Stateless and thread-safe, unlike the Gson instance whose factory chain builds it. */
  private static final TypeAdapter<JsonElement> JSON_ADAPTER =
      new Gson().getAdapter(JsonElement.class);

  /**
   * Decodes one payload into positive mark prices. Entries without a usable {@code markPx} are
   * dropped individually; a payload that cannot be decoded at all is rejected.
   */
  public Map<String, BigDecimal> decode(String data) throws ProtocolException {
    if (data == null) {
      throw new ProtocolException("asset context payload must be a string");
    }
    byte[] compressed;
    try {
      compressed = Base64.getDecoder().decode(data);
    } catch (IllegalArgumentException invalid) {
      throw new ProtocolException("asset context payload is not base64", invalid);
    }
    return parse(decodeUtf8(inflate(compressed)));
  }

  private static byte[] inflate(byte[] compressed) throws ProtocolException {
    Inflater inflater = new Inflater(true);
    try {
      inflater.setInput(compressed);
      ByteArrayOutputStream inflated = new ByteArrayOutputStream(CHUNK_BYTES);
      byte[] chunk = new byte[CHUNK_BYTES];
      while (!inflater.finished()) {
        int produced;
        try {
          produced = inflater.inflate(chunk);
        } catch (DataFormatException malformed) {
          throw new ProtocolException("asset context payload is not raw DEFLATE", malformed);
        }
        if (produced == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) {
            throw new ProtocolException("asset context payload is not raw DEFLATE");
          }
          continue;
        }
        if (inflated.size() + produced > MAX_DECOMPRESSED_BYTES) {
          throw new ProtocolException("asset context payload exceeds the decompression limit");
        }
        inflated.write(chunk, 0, produced);
      }
      return inflated.toByteArray();
    } finally {
      inflater.end();
    }
  }

  private static String decodeUtf8(byte[] inflated) throws ProtocolException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(inflated))
          .toString();
    } catch (CharacterCodingException invalid) {
      throw new ProtocolException("asset context payload is not UTF-8", invalid);
    }
  }

  private static Map<String, BigDecimal> parse(String json) throws ProtocolException {
    JsonElement root;
    try {
      validateJsonCharacters(json);
      JsonReader reader = new JsonReader(new StringReader(json));
      reader.setLenient(false);
      // JsonParser and Gson.fromJson temporarily enable leniency; the adapter preserves it.
      root = JSON_ADAPTER.read(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new ProtocolException("asset context payload is not JSON");
      }
    } catch (IOException invalid) {
      throw new ProtocolException("asset context payload is not JSON", invalid);
    } catch (RuntimeException invalid) {
      throw new ProtocolException("asset context payload is not JSON", invalid);
    }
    if (root == null || !root.isJsonObject()) {
      throw new ProtocolException("asset context payload must be a JSON object");
    }
    Map<String, BigDecimal> markPrices = new LinkedHashMap<String, BigDecimal>();
    for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
      BigDecimal markPrice = markPrice(entry.getValue());
      if (markPrice != null) {
        markPrices.put(entry.getKey(), markPrice);
      }
    }
    return markPrices;
  }

  /** Covers string and keyword extensions accepted even by Gson 2.4's non-lenient reader. */
  private static void validateJsonCharacters(String json) throws ProtocolException {
    boolean quoted = false;
    for (int index = 0; index < json.length(); index++) {
      char character = json.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (quoted) {
        if (character < 0x20) {
          throw new ProtocolException("asset context payload is not JSON");
        }
        if (character == '\\') {
          if (++index == json.length() || "\"\\/bfnrtu".indexOf(json.charAt(index)) < 0) {
            throw new ProtocolException("asset context payload is not JSON");
          }
          // The reader validates the four hexadecimal digits following a Unicode escape.
        }
      } else if ("tTfFnN".indexOf(character) >= 0) {
        String keyword = character == 't' ? "true" : character == 'f' ? "false" : "null";
        if (!json.startsWith(keyword, index)) {
          throw new ProtocolException("asset context payload is not JSON");
        }
        index += keyword.length() - 1;
      }
    }
  }

  /** Applies the same acceptance rule the metadata parser used for {@code markPx}. */
  private static BigDecimal markPrice(JsonElement context) {
    if (context == null || !context.isJsonObject()) {
      return null;
    }
    JsonObject object = context.getAsJsonObject();
    JsonElement markPx = object.get("markPx");
    if (markPx == null || !markPx.isJsonPrimitive() || !markPx.getAsJsonPrimitive().isString()) {
      return null;
    }
    try {
      BigDecimal price = new BigDecimal(markPx.getAsString());
      if (price.signum() <= 0) {
        return null;
      }
      if (price.precision() > MAX_PRICE_DIGITS || Math.abs(price.scale()) > MAX_PRICE_DIGITS) {
        return null;
      }
      return price;
    } catch (NumberFormatException invalid) {
      return null;
    }
  }
}
