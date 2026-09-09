package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.Test;

/** Decodes the base64 + raw DEFLATE payload Hyperliquid sends on the fastAssetCtxs channel. */
public class AssetContextCodecTest {

  /** Captured from wss://api.hyperliquid.xyz/ws, shortened to two coins. */
  private static final String SNAPSHOT =
      "q1ZyCnFWsqpWyk0syg6oULJSMrc0tjTRM1DSUcrNTIGJWBjqmSrV6ihVVFZZOfugqLc00jOyMEZSDhYwUqqtBQA=";

  private static final String DELTA = "q1aqqKyycvZRsqpWyk0syg6oULJSsjTSM1WqrQUA";

  private static final String MIXED =
      "q1ZyCnFWsqpWyk0syg6oULJSMrc0tjTRM1Cq1VGKcg3yR5EDi/r5B0SARTNTwIKGEMV+ob7Iag31TIGCHqHurigm"
          + "GLpqW0KBmVJtLQA=";

  private static final String TRUNCATED_JSON = "q1ZyCnFWsqpWyk0syg6oULICAA==";

  private static final String NOT_AN_OBJECT = "izbUMdIxjgUA";

  private static final String INVALID_UTF8 = "+//vLwA=";

  private final AssetContextCodec codec = new AssetContextCodec();

  /** Decodes a snapshot and keeps the fully qualified HIP-3 name intact. */
  @Test
  public void decodesSnapshotMarkPrices() throws Exception {
    Map<String, BigDecimal> markPrices = codec.decode(SNAPSHOT);

    assertEquals(2, markPrices.size());
    assertEquals(0, new BigDecimal("79394.0").compareTo(markPrices.get("BTC")));
    assertEquals(0, new BigDecimal("92.283").compareTo(markPrices.get("xyz:CL")));
  }

  /** Decodes a delta, which carries only the coins that moved. */
  @Test
  public void decodesDeltaMarkPrices() throws Exception {
    Map<String, BigDecimal> markPrices = codec.decode(DELTA);

    assertEquals(1, markPrices.size());
    assertEquals(0, new BigDecimal("92.5").compareTo(markPrices.get("xyz:CL")));
  }

  /** Drops only the unusable entries: non-positive, absent, non-string, and absurd magnitudes. */
  @Test
  public void dropsUnusableEntriesIndividually() throws Exception {
    Map<String, BigDecimal> markPrices = codec.decode(MIXED);

    assertEquals(0, new BigDecimal("79394.0").compareTo(markPrices.get("BTC")));
    assertNull(markPrices.get("ZERO"));
    assertNull(markPrices.get("NOPX"));
    assertNull(markPrices.get("NUM"));
    assertNull(markPrices.get("HUGE"));
    assertEquals(1, markPrices.size());
  }

  /** Rejects a payload that is not base64 at all. */
  @Test
  public void rejectsNonBase64Payload() {
    assertRejected("not*base64!", "base64");
  }

  /** Rejects base64 that does not inflate as raw DEFLATE. */
  @Test
  public void rejectsNonDeflatePayload() {
    assertRejected("////////", "DEFLATE");
  }

  /** Rejects inflated bytes that are not valid UTF-8. */
  @Test
  public void rejectsNonUtf8Payload() {
    assertRejected(INVALID_UTF8, "UTF-8");
  }

  /** Rejects inflated text that is not JSON. */
  @Test
  public void rejectsNonJsonPayload() {
    assertRejected(TRUNCATED_JSON, "JSON");
  }

  /** Gson extensions are not valid JSON, even inside otherwise unused fields. */
  @Test
  public void rejectsLenientJsonSyntax() throws Exception {
    String[] invalid = {
      "{HYPE:{markPx:'100'}}",
      "{\"HYPE\":{\"markPx\":\"100\"}} // comment",
      "{\"HYPE\":{\"markPx\":\"100\",\"extra\":[1,]}}",
      "{\"HYPE\":{\"markPx\":\"100\",\"extra\":01}}",
      "{\"HYPE\":{\"markPx\":\"100\",\"extra\":NaN}}",
      "{\"HYPE\":{\"markPx\":\"100\"}} {}",
      "{\"HYPE\":{\"markPx\":\"100\",\"extra\":\"raw\nline\"}}",
      "{\"HYPE\":{\"markPx\":\"100\",\"extra\":\"\\'\"}}"
    };
    for (String json : invalid) {
      assertRejected(TestDeflate.encode(json), "JSON");
    }
  }

  /** JSON keywords are lowercase, including letters after the first character. */
  @Test
  public void rejectsMixedCaseKeywords() throws Exception {
    for (String keyword : new String[] {"tRue", "falsE", "nulL"}) {
      assertRejected(
          TestDeflate.encode("{\"HYPE\":{\"markPx\":\"100\",\"extra\":" + keyword + "}}"), "JSON");
    }
  }

  /** Valid escaping and nested unused fields remain accepted. */
  @Test
  public void acceptsStandardJsonSyntax() throws Exception {
    Map<String, BigDecimal> prices =
        codec.decode(
            TestDeflate.encode(
                " {\"HYPE\":{\"markPx\":\"100\",\"extra\":[true,false,null,-1.2e+3,"
                    + "{\"s\":\"line\\n\\\"\\\\\"}]}} \n"));
    assertEquals(new BigDecimal("100"), prices.get("HYPE"));
  }

  /** Rejects a JSON document whose root is not an object. */
  @Test
  public void rejectsNonObjectRoot() {
    assertRejected(NOT_AN_OBJECT, "object");
  }

  /** Rejects a null payload rather than returning an empty map. */
  @Test
  public void rejectsNullPayload() {
    assertRejected(null, "string");
  }

  /** Stops inflating once the decompressed payload exceeds the configured limit. */
  @Test
  public void rejectsPayloadsBeyondTheDecompressionLimit() throws Exception {
    StringBuilder json = new StringBuilder("{");
    for (int index = 0; json.length() <= AssetContextCodec.MAX_DECOMPRESSED_BYTES; index++) {
      if (index != 0) {
        json.append(',');
      }
      json.append("\"COIN").append(index).append("\":{\"markPx\":\"1.0\"}");
    }
    json.append('}');

    assertRejected(TestDeflate.encode(json.toString()), "limit");
  }

  private void assertRejected(String payload, String messageFragment) {
    try {
      codec.decode(payload);
      fail("expected ProtocolException for " + messageFragment);
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
    }
  }
}
