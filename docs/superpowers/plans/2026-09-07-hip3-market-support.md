# HIP-3 Market Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** List every live Hyperliquid perpetual — including HIP-3 builder-deployed markets such as `xyz:CL` — in Bookmap, with the same tick-size candidate quality the validator-operated markets already get.

**Architecture:** Metadata splits into two sources. Static attributes (name, `szDecimals`, `isDelisted`) come from one REST `allPerpMetas` call covering every perp dex. Mark prices come from the `fastAssetCtxs` WebSocket subscription (base64 + raw DEFLATE, keyed by fully qualified coin name), merged into an `AssetContextStore` and pushed back into the session's instrument map. The Hyperliquid source carries that subscription on its market-data connection; the Borsa and Hyperdash relays reject it, so those sources get a second, non-owning connector (`AssetContextFeed`) pointed at Hyperliquid Mainnet and sharing the same transport.

**Tech Stack:** Java 8 bytecode (no `var`, `List.of`, records), Gson 2.4, Bookmap api-core 7.4.0.10, Jetty 9.3.8, JUnit 4.13.2, Gradle wrapper with spotless (google-java-format 1.30.0), checkstyle, spotbugs.

**Spec:** `docs/superpowers/specs/2026-09-07-hip3-market-support-design.md`

## Global Constraints

- Source and bytecode level is Java 8: no `var`, no `List.of`, no records, no `Map.entry`; the `verifyJava8Bytecode` task fails otherwise. Lambdas and method references ARE allowed (the codebase already uses them).
- Every public and package-private type needs a Javadoc comment (checkstyle `MissingJavadocType`); line length is checked; no star imports; no unused imports.
- Gradle only runs with `JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1` and `--offline`. The shell is fish, so prefix commands with `env`.
- Test command: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '<FQCN>'`. Full gate before every commit: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`.
- Always run `spotlessApply` before committing; the repository rejects unformatted code.
- The adapter is read-only market data: no trading, credentials, or private data anywhere.
- Prices are exchanged as `BigDecimal`; `double` appears only at the Bookmap API boundary (`pips`, trade price units).
- Bookmap symbols are the fully qualified Hyperliquid names (`BTC`, `xyz:CL`). Never split a HIP-3 name into symbol + exchange.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
  ```

## File Structure

`...` stands for `com/bookmap/plugins/layer0/hyperliquid`.

| File | Responsibility |
|---|---|
| `src/main/java/.../transport/HyperliquidTransport.java` | Add the `start()` idempotence contract to the interface Javadoc |
| `src/main/java/.../transport/JettyHyperliquidTransport.java` | Raise the Jetty WebSocket message limit and pin the HTTP response limit |
| `src/main/java/.../parse/AssetContextCodec.java` (new) | Pure decoder: base64 → raw DEFLATE → UTF-8 → `Map<String, BigDecimal>` of mark prices |
| `src/main/java/.../model/ControlEvent.java` | Add `Kind.ASSET_CONTEXTS` carrying the decoded mark prices |
| `src/main/java/.../parse/HyperliquidMessageParser.java` | Accept the `fastAssetCtxs` channel, coin-less subscription acks, and feed-level errors |
| `src/main/java/.../OutboundMessage.java` | Add `Kind.SUBSCRIBE_FEED` for connection-scoped subscriptions |
| `src/main/java/.../HyperliquidConnector.java` | Send the feed subscription per generation, `startWithoutMetadata`, transport ownership, request `allPerpMetas` |
| `src/main/java/.../session/AssetContextStore.java` (new) | Merged symbol → mark price map with snapshot/delta semantics |
| `src/main/java/.../session/AssetContextConnectorFactory.java` (new) | Construction seam for the relay-only ctx connector |
| `src/main/java/.../session/AssetContextFeed.java` (new) | Owns the relay-only ctx connection and its listener contract |
| `src/main/java/.../session/HyperliquidSession.java` | Apply mark prices, replace the instrument map, republish known instruments |
| `src/main/java/.../parse/HyperliquidMetaParser.java` | Replace `parse` with `parseAllPerpMetas` |
| `src/main/java/.../Provider.java` | Supply the ctx connector factory from `ProductionSessionFactory` |
| `src/test/java/.../FakeHyperliquidTransport.java` | Targeted per-connection open/close so two connectors can be driven |
| `src/test/java/.../TestMetadata.java` | Build `allPerpMetas` responses and `fastAssetCtxs` frames |
| `README.md` | Document HIP-3 coverage, the relay connection cost, and the live tick refresh |

---

### Task 1: Transport message limits and `start()` idempotence

The Jetty 9.3 default `maxTextMessageSize` is 65536 bytes and the transport never overrides it. The Testnet `fastAssetCtxs` snapshot is already 50 KB on the wire and the Borsa 400-level book is 28.8 KB, so this ceiling has to move before anything else lands. The shared transport in Task 7 also gets started twice, so the interface has to promise idempotence.

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport/HyperliquidTransport.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport/JettyHyperliquidTransport.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/FakeHyperliquidTransport.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/transport/JettyHyperliquidTransportContractTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `JettyHyperliquidTransport.MAX_WEB_SOCKET_MESSAGE_BYTES` (`int`, 1048576), `JettyHyperliquidTransport.MAX_HTTP_RESPONSE_BYTES` (`int`, 4194304), and the package-private static `void JettyHyperliquidTransport.applyMessageLimits(org.eclipse.jetty.websocket.api.WebSocketPolicy policy)`. `HyperliquidTransport.start()` is contractually idempotent.

- [ ] **Step 1: Write the failing test**

Add to `JettyHyperliquidTransportContractTest`:

```java
  /** Pins the Jetty default we are overriding and the limit the adapter needs instead. */
  @Test
  public void messageLimitsExceedTheLargestObservedFrames() {
    WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    assertEquals(65_536, policy.getMaxTextMessageSize());

    JettyHyperliquidTransport.applyMessageLimits(policy);

    assertEquals(1_048_576, policy.getMaxTextMessageSize());
    assertEquals(1_048_576, policy.getMaxBinaryMessageSize());
    assertEquals(1_048_576, JettyHyperliquidTransport.MAX_WEB_SOCKET_MESSAGE_BYTES);
    assertEquals(4_194_304, JettyHyperliquidTransport.MAX_HTTP_RESPONSE_BYTES);
  }
```

Add `import org.eclipse.jetty.websocket.api.WebSocketPolicy;` to the test.

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.transport.JettyHyperliquidTransportContractTest'`
Expected: compilation failure — `cannot find symbol: method applyMessageLimits`.

- [ ] **Step 3: Write the implementation**

In `JettyHyperliquidTransport`, add the import `org.eclipse.jetty.websocket.api.WebSocketPolicy;` and these members above the constructor:

```java
  static final int MAX_WEB_SOCKET_MESSAGE_BYTES = 1024 * 1024;
  static final int MAX_HTTP_RESPONSE_BYTES = 4 * 1024 * 1024;
```

Change the constructor body to:

```java
  /** Creates a transport with separately owned HTTP and WebSocket TLS clients. */
  public JettyHyperliquidTransport() {
    httpClient = new HttpClient(new SslContextFactory());
    webSocketClient = new WebSocketClient(new SslContextFactory());
    applyMessageLimits(webSocketClient.getPolicy());
  }

  /**
   * Raises Jetty 9.3's 65536-byte default message ceiling. The Testnet {@code fastAssetCtxs}
   * snapshot is 50 KB and a 400-level Borsa book is 29 KB, both of which grow over time.
   */
  static void applyMessageLimits(WebSocketPolicy policy) {
    policy.setMaxTextMessageSize(MAX_WEB_SOCKET_MESSAGE_BYTES);
    policy.setMaxBinaryMessageSize(MAX_WEB_SOCKET_MESSAGE_BYTES);
  }
```

In `postJson`, change `new BufferingResponseListener() {` to `new BufferingResponseListener(MAX_HTTP_RESPONSE_BYTES) {`.

In `HyperliquidTransport`, replace the `start()` Javadoc with:

```java
  /**
   * Starts the underlying clients. Implementations must be idempotent: one transport is shared by
   * the market-data connector and the asset-context connector, so this is called once per connector.
   *
   * @throws Exception when the underlying clients cannot start
   */
  void start() throws Exception;
```

In `FakeHyperliquidTransport`, replace the `start()` override with:

```java
  private int startCount;

  @Override
  public void start() {
    startCount++;
  }

  public int startCount() {
    return startCount;
  }
```

Place `private int startCount;` with the other fields, not inline.

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.transport.JettyHyperliquidTransportContractTest'`
Expected: PASS

- [ ] **Step 5: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS — nothing else touches these members yet.

- [ ] **Step 6: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport src/test/java/com/bookmap/plugins/layer0/hyperliquid/transport src/test/java/com/bookmap/plugins/layer0/hyperliquid/FakeHyperliquidTransport.java
git commit -m "$(cat <<'EOF'
fix: raise the WebSocket message ceiling above the frames we receive

Jetty 9.3 caps text messages at 65536 bytes by default and the transport
never overrode it. A 400-level Borsa book is already 29 KB and the
fastAssetCtxs snapshot this branch introduces is 50 KB on Testnet.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 2: `AssetContextCodec`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/AssetContextCodec.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/AssetContextCodecTest.java`

**Interfaces:**
- Consumes: `com.bookmap.plugins.layer0.hyperliquid.parse.ProtocolException` (existing checked exception with `(String)` and `(String, Throwable)` constructors).
- Produces: `public final class AssetContextCodec` with `public Map<String, BigDecimal> decode(String data) throws ProtocolException`. Keys are fully qualified coin names; values are positive mark prices. Entries without a usable `markPx` are dropped individually; a malformed payload throws.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/AssetContextCodecTest.java`:

```java
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
```

- [ ] **Step 2: Write the test-only DEFLATE encoder**

The size-limit test needs to build a payload larger than the limit. Create
`src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/TestDeflate.java`:

```java
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.AssetContextCodecTest'`
Expected: compilation failure — `cannot find symbol: class AssetContextCodec`.

- [ ] **Step 4: Write the implementation**

Create `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/AssetContextCodec.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.parse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
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
      root = new JsonParser().parse(json);
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.AssetContextCodecTest'`
Expected: PASS

If `rejectsNonJsonPayload` fails because Gson's lenient parser accepted the truncated document, tighten the check by asserting the parsed root is a complete object — the implementation above already rejects a non-object root, so the assertion message tells you which branch fired. In that case change the expected fragment in that one test from `"JSON"` to `"object"`; do not weaken the implementation.

- [ ] **Step 6: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/AssetContextCodec.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse
git commit -m "$(cat <<'EOF'
feat: decode fastAssetCtxs mark-price payloads

Adds the base64 + raw DEFLATE decoder for Hyperliquid's fastAssetCtxs
channel, which carries mark prices for every coin across every perp dex
including HIP-3 markets.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 3: Parse the `fastAssetCtxs` channel

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/ControlEvent.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParser.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParserTest.java`

**Interfaces:**
- Consumes: `AssetContextCodec.decode(String)` from Task 2.
- Produces: `ControlEvent.Kind.ASSET_CONTEXTS`, the static factory `ControlEvent.assetContexts(Map<String, BigDecimal> markPrices)`, and the accessor `Map<String, BigDecimal> ControlEvent.markPrices()` which returns an empty map for every other kind. `HyperliquidMessageParser.parse` returns an accepted frame with one `ASSET_CONTEXTS` control event for a `fastAssetCtxs` channel, an ignored frame for a coin-less `subscriptionResponse`, and an ignored frame with a diagnostic for a `fastAssetCtxs` error.

- [ ] **Step 1: Write the failing test**

Add to `HyperliquidMessageParserTest`:

```java
  /** Accepts a fastAssetCtxs frame and exposes the decoded mark prices. */
  @Test
  public void acceptsAssetContextFrame() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"fastAssetCtxs\",\"data\":\""
                + "q1ZyCnFWsqpWyk0syg6oULJSMrc0tjTRM1DSUcrNTIGJWBjqmSrV6ihVVFZZOfugqLc00jOyMEZSDhYw"
                + "UqqtBQA=\"}");

    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    assertEquals(1, frame.controlEvents().size());
    ControlEvent event = frame.controlEvents().get(0);
    assertEquals(ControlEvent.Kind.ASSET_CONTEXTS, event.kind());
    assertEquals(0, new BigDecimal("92.283").compareTo(event.markPrices().get("xyz:CL")));
    assertTrue(frame.marketEvents().isEmpty());
  }

  /** Rejects a fastAssetCtxs frame whose payload cannot be decoded, without emitting events. */
  @Test
  public void rejectsUndecodableAssetContextFrame() {
    ParsedFrame frame = parser.parse("{\"channel\":\"fastAssetCtxs\",\"data\":\"////////\"}");

    assertEquals(ParsedFrame.Disposition.INVALID, frame.disposition());
    assertTrue(frame.controlEvents().isEmpty());
  }

  /** Ignores the ack for a connection-scoped subscription, which carries no coin. */
  @Test
  public void ignoresCoinlessSubscriptionAck() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
                + "\"subscription\":{\"type\":\"fastAssetCtxs\"}}}");

    assertEquals(ParsedFrame.Disposition.IGNORED, frame.disposition());
    assertTrue(frame.controlEvents().isEmpty());
  }

  /** Reports a rejected feed subscription as a diagnostic, never as a per-instrument error. */
  @Test
  public void reportsFeedSubscriptionErrorAsDiagnostic() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"error\",\"data\":\"Invalid subscription "
                + "{\\\"type\\\":\\\"fastAssetCtxs\\\"}\"}");

    assertEquals(ParsedFrame.Disposition.IGNORED, frame.disposition());
    assertTrue(frame.controlEvents().isEmpty());
    assertEquals(1, frame.diagnostics().size());
    assertTrue(frame.diagnostics().get(0), frame.diagnostics().get(0).contains("fastAssetCtxs"));
  }

  /** Keeps reporting an untargeted non-feed error as a subscription error. */
  @Test
  public void keepsUntargetedErrorAsSubscriptionError() {
    ParsedFrame frame = parser.parse("{\"channel\":\"error\",\"data\":\"Already subscribed\"}");

    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    assertEquals(1, frame.controlEvents().size());
    assertEquals(
        ControlEvent.Kind.SUBSCRIPTION_ERROR, frame.controlEvents().get(0).kind());
  }
```

Make sure the test class imports `java.math.BigDecimal`, `com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent`, `com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame`, and `static org.junit.Assert.assertTrue`. If the class does not already hold a `parser` field, add `private final HyperliquidMessageParser parser = new HyperliquidMessageParser();` and use it in the new tests only — leave existing tests as they are.

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParserTest'`
Expected: compilation failure — `cannot find symbol: ASSET_CONTEXTS`.

- [ ] **Step 3: Extend `ControlEvent`**

Replace the whole body of `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/ControlEvent.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A non-market-data event received from the Hyperliquid WebSocket protocol. */
public final class ControlEvent {

  /** The supported control-event categories. */
  public enum Kind {
    SUBSCRIPTION_ACK,
    SUBSCRIPTION_ERROR,
    PONG,
    ASSET_CONTEXTS
  }

  private final Kind kind;
  private final SubscriptionKey target;
  private final Map<String, BigDecimal> markPrices;

  /** Creates a control event, optionally targeted at one subscription. */
  public ControlEvent(Kind kind, SubscriptionKey target) {
    this(kind, target, Collections.<String, BigDecimal>emptyMap());
  }

  private ControlEvent(Kind kind, SubscriptionKey target, Map<String, BigDecimal> markPrices) {
    this.kind = kind;
    this.target = target;
    this.markPrices = markPrices;
  }

  /** Creates an untargeted event carrying decoded mark prices keyed by coin name. */
  public static ControlEvent assetContexts(Map<String, BigDecimal> markPrices) {
    if (markPrices == null) {
      throw new IllegalArgumentException("markPrices must not be null");
    }
    return new ControlEvent(
        Kind.ASSET_CONTEXTS,
        null,
        Collections.unmodifiableMap(new LinkedHashMap<String, BigDecimal>(markPrices)));
  }

  /** Returns the control-event category. */
  public Kind kind() {
    return kind;
  }

  /** Returns the affected subscription, or null when the event is not targetable. */
  public SubscriptionKey target() {
    return target;
  }

  /** Returns the decoded mark prices; empty for every kind other than ASSET_CONTEXTS. */
  public Map<String, BigDecimal> markPrices() {
    return markPrices;
  }
}
```

- [ ] **Step 4: Extend the message parser**

In `HyperliquidMessageParser`, add the field just below `MAX_TID` (`java.math.BigDecimal` and `java.util.Map` are already imported):

```java
  private final AssetContextCodec assetContextCodec = new AssetContextCodec();
```

In `parse(String)`, add this branch immediately after the `"l2Book"` branch:

```java
      if ("fastAssetCtxs".equals(channel)) {
        return parseAssetContexts(object);
      }
```

Add the handler next to `parsePong`:

```java
  private ParsedFrame parseAssetContexts(JsonObject object) throws ProtocolException {
    JsonElement data = object.get("data");
    if (data == null || !data.isJsonPrimitive() || !data.getAsJsonPrimitive().isString()) {
      throw new ProtocolException("fastAssetCtxs data must be a string");
    }
    ControlEvent event = ControlEvent.assetContexts(assetContextCodec.decode(data.getAsString()));
    return ParsedFrame.accepted(
        Collections.<MarketDataEvent>emptyList(),
        Collections.singletonList(event),
        Collections.<String>emptyList());
  }
```

In `parseSubscriptionResponse`, insert the coin-less guard between the subscription null-check and `parseSubscription`:

```java
    JsonObject subscriptionObject = subscription.getAsJsonObject();
    if (!subscriptionObject.has("coin")) {
      // Connection-scoped feeds such as fastAssetCtxs acknowledge without a coin.
      return ParsedFrame.ignored(Collections.<String>emptyList());
    }
    SubscriptionKey key = parseSubscription(subscriptionObject);
```

Delete the old `SubscriptionKey key = parseSubscription(subscription.getAsJsonObject());` line it replaces.

In `parseError`, add the feed branch just before the `ControlEvent event = ...` line:

```java
    if (target == null && data != null && data.toString().contains("fastAssetCtxs")) {
      return ParsedFrame.ignored(
          Collections.singletonList("fastAssetCtxs subscription rejected: " + data));
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParserTest'`
Expected: PASS

- [ ] **Step 6: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS

- [ ] **Step 7: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/ControlEvent.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParser.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParserTest.java
git commit -m "$(cat <<'EOF'
feat: parse fastAssetCtxs frames into asset-context control events

Also stops treating a connection-scoped subscription ack as malformed:
fastAssetCtxs acknowledges without a coin, and its rejection is reported
as a diagnostic instead of a per-instrument subscription error.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 4: Connector sends the feed subscription every generation

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/OutboundMessage.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java`
- Modify (fixtures): `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTickSizeTest.java`, `HyperliquidSessionLifecycleTest.java`, `HyperliquidSessionSubscriptionTest.java`, `HyperliquidSessionDeltaBookTest.java`, `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `OutboundMessage.Kind.SUBSCRIBE_FEED` (subscription is null) and the package-private constant `HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON` = `{"method":"subscribe","subscription":{"type":"fastAssetCtxs"}}`. Every opened generation sends that frame first, before any instrument subscription.

- [ ] **Step 1: Write the failing test**

Add to `HyperliquidConnectorTest`:

```java
  /** Every opened generation subscribes to the asset-context feed before anything else. */
  @Test
  public void opensEachGenerationWithTheAssetContextSubscription() {
    Fixture fixture = new Fixture();

    fixture.startAndOpen();
    fixture.transport.socket().succeedNextSend();

    assertEquals(
        Arrays.asList(HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON),
        fixture.transport.socket().successfulSendBodies());
  }

  /** The feed subscription precedes restored instrument subscriptions after a reconnect. */
  @Test
  public void assetContextSubscriptionPrecedesRestoredSubscriptions() {
    Fixture fixture = new Fixture();
    fixture.startAndOpen();
    fixture.transport.socket().succeedNextSend();
    fixture.connector.subscribe(
        new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), Long.MAX_VALUE);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.clearSuccessfulSendBodies();

    fixture.connector.reconnect(null);
    fixture.advanceAndOpen(1_000L);
    fixture.transport.socket().succeedNextSend();
    fixture.transport.socket().succeedNextSend();

    List<String> bodies = fixture.transport.socket().successfulSendBodies();
    assertEquals(2, bodies.size());
    assertEquals(HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON, bodies.get(0));
    assertTrue(bodies.get(1), bodies.get(1).contains("\"coin\":\"BTC\""));
  }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnectorTest'`
Expected: compilation failure — `cannot find symbol: ASSET_CONTEXTS_SUBSCRIBE_JSON`.

- [ ] **Step 3: Add the outbound message kind**

In `OutboundMessage`, extend the enum and replace the invariant check:

```java
  /** Outbound protocol message categories. */
  public enum Kind {
    SUBSCRIBE,
    UNSUBSCRIBE,
    PING,
    SUBSCRIBE_FEED
  }
```

```java
    boolean connectionScoped = kind == Kind.PING || kind == Kind.SUBSCRIBE_FEED;
    if (connectionScoped != (subscription == null)) {
      throw new IllegalArgumentException(
          "only PING and SUBSCRIBE_FEED messages may omit a subscription");
    }
```

Delete the old `if ((kind == Kind.PING) != (subscription == null))` block. Update the constructor Javadoc to
`/** Creates an outbound JSON message. PING and SUBSCRIBE_FEED messages have no subscription. */`.

- [ ] **Step 4: Send the feed subscription per generation**

In `HyperliquidConnector`, add the constant next to `RECONNECT_DELAYS`:

```java
  static final String ASSET_CONTEXTS_SUBSCRIBE_JSON =
      "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"fastAssetCtxs\"}}";
```

Only a connection to Hyperliquid itself carries the feed. Borsa and Hyperdash reject the
subscription, and their rejection text is not guaranteed to name `fastAssetCtxs`, so an unrecognised
untargeted `error` frame would reach `HyperliquidSession.handleSubscriptionError(null)`, find no
record, and `stop(StopCause.FATAL)` — killing the relay session on every generation. Send the frame
only where the exchange accepts it. Add the predicate next to `activationDeadlineFor`:

```java
  /** Only Hyperliquid serves fastAssetCtxs; the relays reject the subscription outright. */
  private boolean sendsAssetContextFeed() {
    return profile != null && profile.source() == MarketDataSource.HYPERLIQUID;
  }

  private int reservedFrameCount() {
    return desired.size() + (sendsAssetContextFeed() ? 2 : 1);
  }
```

In `attemptConnection`, change the reservation to leave room for the feed frame:

```java
    int reservedFrames = initial ? 0 : reservedFrameCount();
```

In `socketOpened`, change the reservation cross-check to match:

```java
    if (!openingInitial && connectionPermit.reservedFramesRemaining() > reservedFrameCount()) {
```

Still in `socketOpened`, send the feed frame immediately after `listener.onSocketOpened(openingGeneration);` and before the `if (!openingInitial)` block:

```java
    if (sendsAssetContextFeed()) {
      sendWhenPossible(
          new OutboundMessage(
              OutboundMessage.Kind.SUBSCRIBE_FEED, null, ASSET_CONTEXTS_SUBSCRIBE_JSON),
          openingGeneration,
          Long.MAX_VALUE,
          openingInitial ? null : connectionPermit);
    }
```

`MarketDataSource` is in the connector's own package, so no import is needed. The asset-context
connector added in Task 7 starts with `SourceProfile.of(MarketDataSource.HYPERLIQUID, MAINNET)`, so
it always sends the frame.

No other change is needed: `isMessageRelevant`, `sendWhenPossible`'s expiry branch, and `sendSucceeded` all key off `Kind.SUBSCRIBE`, so `SUBSCRIBE_FEED` never reaches the `desired` `TreeMap` with a null key.

- [ ] **Step 5: Run the connector test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnectorTest'`
Expected: PASS for the two new tests. Existing connector tests that count pending sends or assert the first successful body now see the feed frame first; for each failure, insert `fixture.transport.socket().succeedNextSend();` right after the `openSocket()`/`advanceAndOpen(...)` that precedes the assertion, and adjust any `pendingSendCount()` expectation by one.

- [ ] **Step 6: Flush the feed frame in every session fixture**

Each session-level fixture opens a socket and then completes a fixed number of sends. On a
**Hyperliquid** source the feed frame is now the first pending send, so flush it at every open. On a
relay source nothing changes, because Step 4 gates the frame on the source.

In `HyperliquidSessionSubscriptionTest`, `HyperliquidSessionLifecycleTest`, and
`ProviderEndToEndTest` — all of which log in with `MarketDataSource.HYPERLIQUID` — replace every
occurrence of

```java
      transport.openSocket();
      drain();
```

with

```java
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
```

Where a test opens a socket without a following `drain()`, add both lines after the existing `openSocket()` call. `HyperliquidSessionLifecycleTest` has twelve such call sites (initial connects and reconnects); update all of them.

`HyperliquidSessionTickSizeTest.Fixture` takes the source as a constructor argument and is
instantiated with `HYPERLIQUID`, `BORSA` and `HYPERDASH`, so its single open site becomes:

```java
      transport.openSocket();
      drain();
      if (source == MarketDataSource.HYPERLIQUID) {
        transport.socket().succeedNextSend();
        drain();
      }
```

Keep the constructor argument in a field if the fixture does not already retain it.

`HyperliquidSessionDeltaBookTest` logs in with `BORSA` only; leave its three open sites unchanged.

- [ ] **Step 7: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS. Any remaining failure is an assertion on the exact list of `successfulSendBodies()`; add `HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON` as the first expected element rather than deleting the assertion.

- [ ] **Step 8: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add -A src/main src/test
git commit -m "$(cat <<'EOF'
feat: subscribe to the asset-context feed on every generation

The fastAssetCtxs subscription is connection scoped rather than
instrument scoped, so it gets its own outbound message kind and stays
out of the desired-subscription map and its ack-timeout bookkeeping.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 5: `AssetContextStore`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStore.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStoreTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: package-private `final class AssetContextStore` with `void applySnapshot(Map<String, BigDecimal> snapshot)`, `Set<String> applyDelta(Map<String, BigDecimal> delta)` returning only the symbols whose value actually changed, and `BigDecimal markPrice(String symbol)` returning null for unknown symbols.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStoreTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Merges fastAssetCtxs snapshots and deltas into a single mark-price view. */
public class AssetContextStoreTest {

  private final AssetContextStore store = new AssetContextStore();

  /** A delta updates only the coins it names and leaves every other value untouched. */
  @Test
  public void deltaKeepsPreviousValuesForAbsentSymbols() {
    store.applySnapshot(prices("BTC", "79394.0", "xyz:CL", "92.283"));

    Set<String> changed = store.applyDelta(prices("xyz:CL", "92.5"));

    assertEquals(Collections.singleton("xyz:CL"), changed);
    assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
    assertEquals(0, new BigDecimal("92.5").compareTo(store.markPrice("xyz:CL")));
  }

  /** A delta that repeats the current value reports no change. */
  @Test
  public void unchangedDeltaValueIsNotReportedAsChanged() {
    store.applySnapshot(prices("BTC", "79394.0"));

    assertTrue(store.applyDelta(prices("BTC", "79394.0")).isEmpty());
  }

  /** A delta introduces coins the snapshot never carried without disturbing existing ones. */
  @Test
  public void deltaAddsUnknownSymbols() {
    store.applySnapshot(prices("BTC", "79394.0"));

    Set<String> changed = store.applyDelta(prices("para:AVGO", "312.5"));

    assertEquals(Collections.singleton("para:AVGO"), changed);
    assertEquals(0, new BigDecimal("312.5").compareTo(store.markPrice("para:AVGO")));
    assertEquals(0, new BigDecimal("79394.0").compareTo(store.markPrice("BTC")));
  }

  /** A snapshot replaces the whole view so a delisted coin cannot linger. */
  @Test
  public void snapshotReplacesEveryPreviousValue() {
    store.applySnapshot(prices("BTC", "79394.0", "GONE", "1.0"));

    store.applySnapshot(prices("BTC", "80000.0"));

    assertEquals(0, new BigDecimal("80000.0").compareTo(store.markPrice("BTC")));
    assertNull(store.markPrice("GONE"));
  }

  /** An unknown symbol has no mark price. */
  @Test
  public void unknownSymbolHasNoMarkPrice() {
    assertNull(store.markPrice("BTC"));
  }

  private static Map<String, BigDecimal> prices(String... symbolsAndPrices) {
    Map<String, BigDecimal> prices = new LinkedHashMap<String, BigDecimal>();
    for (int index = 0; index < symbolsAndPrices.length; index += 2) {
      prices.put(symbolsAndPrices[index], new BigDecimal(symbolsAndPrices[index + 1]));
    }
    return prices;
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.AssetContextStoreTest'`
Expected: compilation failure — `cannot find symbol: class AssetContextStore`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStore.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Holds the merged mark price of every coin the fastAssetCtxs feed reports. The first frame of a
 * connection is a full snapshot; later frames name only the coins that moved.
 */
final class AssetContextStore {

  private final Map<String, BigDecimal> markPrices = new HashMap<String, BigDecimal>();

  /** Replaces the whole view, dropping coins the exchange no longer reports. */
  void applySnapshot(Map<String, BigDecimal> snapshot) {
    markPrices.clear();
    markPrices.putAll(snapshot);
  }

  /** Merges a delta and returns the symbols whose mark price actually changed. */
  Set<String> applyDelta(Map<String, BigDecimal> delta) {
    Set<String> changed = new HashSet<String>();
    for (Map.Entry<String, BigDecimal> entry : delta.entrySet()) {
      BigDecimal previous = markPrices.put(entry.getKey(), entry.getValue());
      if (previous == null || previous.compareTo(entry.getValue()) != 0) {
        changed.add(entry.getKey());
      }
    }
    return changed;
  }

  /** Returns the current mark price, or null when the coin has never been reported. */
  BigDecimal markPrice(String symbol) {
    return markPrices.get(symbol);
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.AssetContextStoreTest'`
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStore.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextStoreTest.java
git commit -m "$(cat <<'EOF'
feat: merge fastAssetCtxs snapshots and deltas

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 6: Session applies mark prices to the instrument map

This is where the tick-size behaviour is preserved. `handleSubscribe` reads `instruments.get(symbol).referencePrice()` and feeds it to both `TickSizePlan.defaultTick` and `TickSizePlan.parametersFor`, so publishing to the sink alone is not enough — the map itself has to be replaced.

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/TestMetadata.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionAssetContextTest.java` (new)

**Interfaces:**
- Consumes: `AssetContextStore` (Task 5), `ControlEvent.Kind.ASSET_CONTEXTS` and `ControlEvent.markPrices()` (Task 3).
- Produces: package-private `void HyperliquidSession.onAssetContexts(Map<String, BigDecimal> markPrices, boolean snapshot)`, which callers must invoke while already on the state lane. `TestMetadata.assetContextsFrame(String markPriceJson)` returns a complete `fastAssetCtxs` WebSocket frame carrying that JSON object.

- [ ] **Step 1: Add the test helper**

Add to `src/test/java/com/bookmap/plugins/layer0/hyperliquid/TestMetadata.java` (and the imports `java.io.ByteArrayOutputStream`, `java.nio.charset.StandardCharsets`, `java.util.Base64`, `java.util.zip.Deflater`):

```java
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
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionAssetContextTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Drives mark prices through the session the way the fastAssetCtxs feed does. */
public class HyperliquidSessionAssetContextTest {

  /** A snapshot refreshes the known-instrument list with reference prices attached. */
  @Test
  public void snapshotRepublishesKnownInstrumentsWithReferencePrices() {
    Fixture fixture = new Fixture();

    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    assertEquals(2, fixture.sink.knownInstrumentPublications());
    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** The refreshed reference price reaches the subscribe path, not just the sink. */
  @Test
  public void refreshedReferencePriceDrivesServerSideGrouping() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    fixture.session.subscribe("HYPE", "", "PERPETUAL", new java.math.BigDecimal("0.01"));
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":4"));
  }

  /** A delta touching no known instrument never rebuilds the list. */
  @Test
  public void unrelatedDeltaDoesNotRepublish() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    int publications = fixture.sink.knownInstrumentPublications();

    fixture.clock.now += 60_000L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"BTC\":{\"markPx\":\"79394.0\"}}"));

    assertEquals(publications, fixture.sink.knownInstrumentPublications());
  }

  /** A known instrument that moves republishes at most once every five seconds. */
  @Test
  public void knownInstrumentDeltaIsThrottledToFiveSeconds() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    int publications = fixture.sink.knownInstrumentPublications();

    fixture.clock.now += 4_999L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"88.0\"}}"));
    assertEquals(publications, fixture.sink.knownInstrumentPublications());

    fixture.clock.now += 1L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"88.5\"}}"));
    assertEquals(publications + 1, fixture.sink.knownInstrumentPublications());
    assertEquals("88.5", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** A reopened generation treats its first frame as a snapshot again. */
  @Test
  public void reconnectRestartsSnapshotDetection() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    fixture.reconnect();
    fixture.receiveOnGeneration(2L, TestMetadata.assetContextsFrame("{\"OTHER\":{\"markPx\":\"1\"}}"));

    assertEquals(null, fixture.sink.lastReferencePrice("HYPE"));
  }

  /** A rejected feed never fails login and never removes the instrument list. */
  @Test
  public void rejectedFeedIsOnlyADiagnostic() {
    Fixture fixture = new Fixture();

    fixture.receive(
        "{\"channel\":\"error\",\"data\":\"Invalid subscription {\\\"type\\\":"
            + "\\\"fastAssetCtxs\\\"}\"}");

    assertFalse(hasEvent(fixture.sink.events(), "login-failed"));
    assertEquals(1, fixture.sink.knownInstrumentPublications());
  }

  /** RecordingSessionSink appends the failure reason, so events are matched by prefix. */
  private static boolean hasEvent(List<String> events, String prefix) {
    for (String event : events) {
      if (event.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionAssetContextTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            HyperliquidSessionAssetContextTest::noop);

    Fixture() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.wrap(TestMetadata.universe("HYPE")));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    void receive(String frame) {
      receiveOnGeneration(1L, frame);
    }

    void receiveOnGeneration(long generation, String frame) {
      session.onFrame(generation, frame);
      drain();
    }

    void reconnect() {
      connector.reconnect(null);
      drain();
      clock.now += 1_000L;
      scheduler.advanceBy(1_000L);
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    String sentBody(String type) {
      List<String> bodies = transport.socket().successfulSendBodies();
      for (String body : bodies) {
        if (body.contains("\"type\":\"" + type + "\"")) {
          return body;
        }
      }
      throw new AssertionError("no " + type + " subscription sent: " + bodies);
    }

    void drain() {
      executor.drain();
    }
  }

  private static void noop() {
    // Intentionally empty.
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              int.class, int.class, int.class, int.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class TestScheduler implements CancellableScheduler {
    private final MutableClock clock;
    private final PriorityQueue<Task> tasks = new PriorityQueue<Task>();
    private long sequence;

    TestScheduler(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis), sequence++);
      tasks.add(scheduled);
      return scheduled;
    }

    void advanceBy(long elapsedMillis) {
      long deadline = clock.now + elapsedMillis;
      while (!tasks.isEmpty() && tasks.peek().due <= deadline) {
        Task due = tasks.poll();
        if (!due.cancelled) {
          due.task.run();
        }
      }
    }

    private static final class Task implements Cancellable, Comparable<Task> {
      private final Runnable task;
      private final long due;
      private final long sequence;
      private boolean cancelled;

      Task(Runnable task, long due, long sequence) {
        this.task = task;
        this.due = due;
        this.sequence = sequence;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }

      @Override
      public int compareTo(Task other) {
        int compareDue = Long.compare(due, other.due);
        return compareDue != 0 ? compareDue : Long.compare(sequence, other.sequence);
      }
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }
}
```

- [ ] **Step 3: Extend the recording sink**

`RecordingSessionSink` currently records only aliases from `onKnownInstruments`. Add the two accessors the test needs, next to the existing ones:

```java
  private int knownInstrumentPublications;
  private final Map<String, String> lastReferencePrices = new HashMap<String, String>();

  int knownInstrumentPublications() {
    return knownInstrumentPublications;
  }

  String lastReferencePrice(String symbol) {
    return lastReferencePrices.get(symbol);
  }
```

and record them at the top of `onKnownInstruments`:

```java
    knownInstrumentPublications++;
    lastReferencePrices.clear();
    for (PerpetualInstrument instrument : instruments) {
      if (instrument.referencePrice() != null) {
        lastReferencePrices.put(
            instrument.symbol(), instrument.referencePrice().toPlainString());
      }
    }
```

Add `java.util.HashMap` and `java.util.Map` imports.

- [ ] **Step 4: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionAssetContextTest'`
Expected: FAIL — `snapshotRepublishesKnownInstrumentsWithReferencePrices` reports 1 publication, not 2, because the session drops `ASSET_CONTEXTS` events.

- [ ] **Step 5: Apply asset contexts in the session**

In `HyperliquidSession`, add the constant next to `TRADE_DEDUPLICATION_TTL_MILLIS`:

```java
  private static final long ASSET_CONTEXT_REPUBLISH_INTERVAL_MILLIS = 5_000L;
```

Add the fields next to `tradeDeduplicator`:

```java
  private final AssetContextStore assetContexts = new AssetContextStore();
```

and next to `generationInvalidated`:

```java
  private boolean assetContextSnapshotPending;
  private boolean assetContextPublished;
  private long lastAssetContextPublishMillis;
```

In `onSocketOpened`, add `assetContextSnapshotPending = true;` immediately after `generationInvalidated = false;`.

In `handleControls`, add the branch after the `SUBSCRIPTION_ERROR` branch:

```java
      } else if (event.kind() == ControlEvent.Kind.ASSET_CONTEXTS) {
        boolean snapshot = assetContextSnapshotPending;
        assetContextSnapshotPending = false;
        onAssetContexts(event.markPrices(), snapshot);
      }
```

Add the entry point next to `handleControls`:

```java
  /**
   * Applies decoded mark prices and refreshes the known-instrument list. Callers must already run
   * on the state lane; the Hyperliquid source enters through {@link #handleControls} and a relay
   * enters through its dedicated asset-context feed.
   */
  void onAssetContexts(Map<String, BigDecimal> markPrices, boolean snapshot) {
    if (closed || !metadataReceived) {
      return;
    }
    if (snapshot) {
      assetContexts.applySnapshot(markPrices);
    } else if (!shouldRepublish(assetContexts.applyDelta(markPrices))) {
      return;
    }
    assetContextPublished = true;
    lastAssetContextPublishMillis = clock.getAsLong();
    for (Map.Entry<String, PerpetualInstrument> entry : instruments.entrySet()) {
      PerpetualInstrument current = entry.getValue();
      entry.setValue(
          new PerpetualInstrument(
              current.symbol(), current.sizeDecimals(), assetContexts.markPrice(current.symbol())));
    }
    sink.onKnownInstruments(new ArrayList<PerpetualInstrument>(instruments.values()));
  }

  /** Rebuilds only when a listed instrument moved and the throttle window has elapsed. */
  private boolean shouldRepublish(Set<String> changed) {
    boolean touchesKnownInstrument = false;
    for (String symbol : changed) {
      if (instruments.containsKey(symbol)) {
        touchesKnownInstrument = true;
        break;
      }
    }
    if (!touchesKnownInstrument) {
      return false;
    }
    return !assetContextPublished
        || clock.getAsLong() - lastAssetContextPublishMillis
            >= ASSET_CONTEXT_REPUBLISH_INTERVAL_MILLIS;
  }
```

`instruments` is a `TreeMap`, so `entry.setValue` updates it in place. Only `referencePrice` changes; `symbol`, `sizeDecimals`, `priceDecimals` and `pips` are identical, which is why an already subscribed `SubscriptionRecord` keeps its `PriceBucketer` untouched.

- [ ] **Step 6: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionAssetContextTest'`
Expected: PASS

- [ ] **Step 7: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS

- [ ] **Step 8: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add -A src/main src/test
git commit -m "$(cat <<'EOF'
feat: refresh instrument reference prices from the asset-context feed

Replaces the session's instrument map rather than only republishing to
the sink: handleSubscribe reads referencePrice() from that map for both
the default tick and the server-side nSigFigs/mantissa grouping.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 7: Relay-only asset-context connection

Borsa and Hyperdash both reject `fastAssetCtxs`, so those sources need a second connection to Hyperliquid Mainnet. It shares the transport, must not close it, and must answer its own pong frames — the session's control path gates on the market-data generation and would silently drop them, reconnecting the feed on every heartbeat.

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java`
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextConnectorFactory.java`
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeed.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/FakeHyperliquidTransport.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeedTest.java` (new)

**Interfaces:**
- Consumes: `HyperliquidSession.onAssetContexts(Map, boolean)` (Task 6), `HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON` (Task 4).
- Produces:
  - `public HyperliquidConnector(HyperliquidTransport, HyperliquidMetaParser, HyperliquidProcessBudget, CancellableScheduler, LongSupplier, Consumer<Runnable>, boolean ownsTransport)`; the six-argument constructor delegates with `true`.
  - `public void HyperliquidConnector.startWithoutMetadata(SourceProfile profile)`.
  - `interface AssetContextConnectorFactory { HyperliquidConnector create(); }` (package-private, in `session`).
  - `final class AssetContextFeed` (package-private) with `void start()` and `void close()`.
  - A ninth `HyperliquidSession` constructor parameter `AssetContextConnectorFactory assetContextConnectorFactory`, placed immediately before `Runnable afterClose`.
  - `FakeHyperliquidTransport.openConnection(int index)`, `FakeHyperliquidTransport.failConnection(int index, Throwable failure)`, and `FakeHyperliquidTransport.remoteCloseConnection(int index, int code, String reason)`.

- [ ] **Step 1: Add the targeted connection controls to the fake transport**

In `FakeHyperliquidTransport`, add next to `openSocket()`:

```java
  /** Opens one specific connection so tests can drive two connectors on one transport. */
  public void openConnection(int connectionIndex) {
    connectHandles.get(connectionIndex).completed = true;
    socket.open = true;
    socketCallbacks.get(connectionIndex).onOpen(socket);
  }

  /** Fails one specific connection without disturbing the others. */
  public void failConnection(int connectionIndex, Throwable failure) {
    connectHandles.get(connectionIndex).completed = true;
    socketCallbacks.get(connectionIndex).onFailure(failure);
  }

  /** Closes one specific connection without disturbing the others. */
  public void remoteCloseConnection(int connectionIndex, int code, String reason) {
    connectHandles.get(connectionIndex).completed = true;
    socket.open = false;
    socketCallbacks.get(connectionIndex).onClose(code, reason);
  }
```

Also count transport closes, so a test can prove the borrowed transport is closed exactly once. Add
`private int closeCount;` with the other fields, increment it at the top of the existing `close()`,
and expose it:

```java
  public int closeCount() {
    return closeCount;
  }
```

The fake keeps one shared `FakeSocket`, so both connectors write into the same `successfulSendBodies()` list; the tests below assert on content, not on per-connection ownership.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeedTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import java.io.IOException;
import java.net.URI;
import org.junit.Test;

/** Covers the second connection a relay source needs for the fastAssetCtxs feed. */
public class AssetContextFeedTest {

  /** A relay opens a second Hyperliquid Mainnet connection and subscribes to the feed on it. */
  @Test
  public void relayOpensADedicatedMainnetFeedConnection() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    assertEquals(2, fixture.transport.connectCalls().size());
    assertEquals(URI.create("wss://ws.borsa.cc/"), fixture.transport.connectCalls().get(0));
    assertEquals(
        URI.create("wss://api.hyperliquid.xyz/ws"), fixture.transport.connectCalls().get(1));
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString(),
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(HyperliquidConnector.ASSET_CONTEXTS_SUBSCRIBE_JSON));
  }

  /** The Hyperliquid source keeps using one connection. */
  @Test
  public void hyperliquidSourceOpensNoSecondConnection() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID);

    assertEquals(1, fixture.transport.connectCalls().size());
  }

  /** Mark prices arriving on the feed connection reach the instrument list. */
  @Test
  public void feedFramesRefreshReferencePrices() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /**
   * The feed answers its own pong so its heartbeat never tears the connection down. Both connectors
   * ping on the same 30 s cadence and share one fake socket, so both pings are flushed and both
   * pongs delivered; without {@code acceptPong} on the feed connector its 15 s pong deadline fires
   * and the reconnect shows up as an extra connect call.
   */
  @Test
  public void feedAnswersItsOwnPong() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);
    int connectionsBefore = fixture.transport.connectCalls().size();

    fixture.advance(30_000L);
    while (fixture.transport.socket().pendingSendCount() > 0) {
      fixture.transport.socket().succeedNextSend();
      fixture.drain();
    }
    fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");
    fixture.transport.emitTextFromConnection(1, "{\"channel\":\"pong\"}");
    fixture.drain();
    fixture.advance(20_000L);

    assertEquals(connectionsBefore, fixture.transport.connectCalls().size());
  }

  /** A failing feed connection never fails login and never stops market data. */
  @Test
  public void feedFailureDoesNotFailLogin() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.transport.failConnection(1, new IOException("feed unavailable"));
    fixture.drain();

    assertFalse(fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "login-failed"));
    assertFalse(
        fixture.sink.events().toString(), hasEvent(fixture.sink.events(), "connection-lost"));
  }

  /** Mark prices still apply once the two connections' generation counters have diverged. */
  @Test
  public void feedGenerationIsIndependentOfTheMarketDataGeneration() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.connector.reconnect(null);
    fixture.advance(1_000L);
    fixture.transport.openConnection(2);
    fixture.drain();
    fixture.transport.emitTextFromConnection(
        1, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    fixture.drain();

    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** The feed connector never closes the transport it borrows. */
  @Test
  public void feedConnectorDoesNotCloseTheSharedTransport() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.feedConnector.close();
    fixture.drain();

    assertFalse(fixture.transport.closed());
  }

  /** Closing the session settles both connections and closes the shared transport exactly once. */
  @Test
  public void sessionCloseSettlesBothConnectionsAndClosesTheTransportOnce() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA);

    fixture.session.close();
    fixture.drain();

    assertTrue(fixture.transport.closed());
    assertEquals(1, fixture.transport.closeCount());
    assertTrue(fixture.transport.allConnectHandlesSettled());
  }

  /** RecordingSessionSink appends the failure reason, so events are matched by prefix. */
  private static boolean hasEvent(List<String> events, String prefix) {
    for (String event : events) {
      if (event.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static void noop() {
    // Intentionally empty.
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              int.class, int.class, int.class, int.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, AssetContextFeedTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final HyperliquidSession session;
    private HyperliquidConnector feedConnector;

    Fixture(MarketDataSource source) {
      session =
          new HyperliquidSession(
              connector,
              new HyperliquidMessageParser(),
              budget,
              scheduler,
              clock,
              dispatcher,
              sink,
              new AssetContextConnectorFactory() {
                @Override
                public HyperliquidConnector create() {
                  feedConnector =
                      new HyperliquidConnector(
                          transport,
                          new HyperliquidMetaParser(),
                          budget,
                          scheduler,
                          clock,
                          dispatcher::submitControl,
                          false);
                  return feedConnector;
                }
              },
              AssetContextFeedTest::noop);
      session.login(SourceProfile.of(source, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.wrap(TestMetadata.universe("HYPE")));
      drain();
      for (int index = 0; index < transport.connectCalls().size(); index++) {
        transport.openConnection(index);
        drain();
        // Only a Hyperliquid connection subscribes to the feed, so a relay's index 0 sends nothing.
        if (transport.socket().pendingSendCount() > 0) {
          transport.socket().succeedNextSend();
          drain();
        }
      }
    }

    void advance(long elapsedMillis) {
      clock.now += elapsedMillis;
      scheduler.advanceBy(elapsedMillis);
      drain();
    }

    void drain() {
      executor.drain();
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class TestScheduler implements CancellableScheduler {
    private final MutableClock clock;
    private final PriorityQueue<Task> tasks = new PriorityQueue<Task>();
    private long sequence;

    TestScheduler(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis), sequence++);
      tasks.add(scheduled);
      return scheduled;
    }

    void advanceBy(long elapsedMillis) {
      long deadline = clock.now + elapsedMillis;
      while (!tasks.isEmpty() && tasks.peek().due <= deadline) {
        Task due = tasks.poll();
        if (!due.cancelled) {
          due.task.run();
        }
      }
    }

    private static final class Task implements Cancellable, Comparable<Task> {
      private final Runnable task;
      private final long due;
      private final long sequence;
      private boolean cancelled;

      Task(Runnable task, long due, long sequence) {
        this.task = task;
        this.due = due;
        this.sequence = sequence;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }

      @Override
      public int compareTo(Task other) {
        int compareDue = Long.compare(due, other.due);
        return compareDue != 0 ? compareDue : Long.compare(sequence, other.sequence);
      }
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }
}
```

Add these imports to the test alongside the ones already listed: `com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget`, `com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler`, `com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher`, `com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser`, `com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser`, `java.lang.reflect.Constructor`, `java.util.ArrayDeque`, `java.util.List`, `java.util.PriorityQueue`, `java.util.concurrent.Executor`, and `java.util.function.LongSupplier`.

- [ ] **Step 3: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.AssetContextFeedTest'`
Expected: compilation failure — `cannot find symbol: class AssetContextConnectorFactory`.

- [ ] **Step 4: Add connector transport ownership and metadata-free start**

In `HyperliquidConnector`, add the field `private final boolean ownsTransport;` next to `stateSubmitter`, then replace the constructor with the pair:

```java
  /** Creates a connector that owns and closes the supplied transport. */
  public HyperliquidConnector(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      HyperliquidProcessBudget budget,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter) {
    this(transport, metaParser, budget, scheduler, clock, stateSubmitter, true);
  }

  /**
   * Creates a connector with explicitly injected asynchronous boundaries.
   *
   * @param ownsTransport whether {@link #close()} also closes the transport; pass false when the
   *     transport is shared with another connector
   */
  public HyperliquidConnector(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      HyperliquidProcessBudget budget,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter,
      boolean ownsTransport) {
    if (transport == null
        || metaParser == null
        || budget == null
        || scheduler == null
        || clock == null
        || stateSubmitter == null) {
      throw new IllegalArgumentException("connector dependencies must not be null");
    }
    this.transport = transport;
    this.metaParser = metaParser;
    this.budget = budget;
    this.scheduler = scheduler;
    this.clock = clock;
    this.stateSubmitter = stateSubmitter;
    this.ownsTransport = ownsTransport;
  }
```

In `closeOnStateLane`, guard the last line:

```java
    if (ownsTransport) {
      transport.close();
    }
```

Add the metadata-free start next to `start(SourceProfile)`:

```java
  /**
   * Starts the WebSocket lifecycle without requesting metadata. {@link Listener#onMetadata} is
   * never reported; the asset-context feed uses this because the session already has the universe.
   */
  public void startWithoutMetadata(final SourceProfile newProfile) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            startFeedOnlyOnStateLane(newProfile);
          }
        });
  }

  private void startFeedOnlyOnStateLane(SourceProfile newProfile) {
    if (closed || started) {
      return;
    }
    if (listener == null || newProfile == null) {
      throw new IllegalStateException("listener and profile must be set before start");
    }
    started = true;
    profile = newProfile;
    try {
      transport.start();
    } catch (Exception failure) {
      reportInitialFailure(classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    attemptConnection(true);
  }
```

- [ ] **Step 5: Add the factory seam and the feed**

Create `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextConnectorFactory.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;

/** Construction seam for the extra connector a relay source needs for the asset-context feed. */
public interface AssetContextConnectorFactory {

  /**
   * Creates a connector that shares the session's transport, scheduler, clock, budget and state
   * lane, and that does not own the transport.
   */
  HyperliquidConnector create();
}
```

It is public because `Provider` supplies it from another package and `HyperliquidSession`'s
constructor is public.

Create `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeed.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.OutboundMessage;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Owns the extra Hyperliquid Mainnet connection a relay source needs, because Borsa and Hyperdash
 * both reject the fastAssetCtxs subscription. Its failures never reach the session sink: mark
 * prices only refine tick-size candidates, so a broken feed leaves the last known values in place.
 */
final class AssetContextFeed implements HyperliquidConnector.Listener, AutoCloseable {

  private final HyperliquidConnector connector;
  private final HyperliquidMessageParser parser;
  private final HyperliquidSession session;
  private final Consumer<Runnable> stateLane;
  private final Consumer<String> diagnostics;

  private long currentGeneration = -1L;
  private boolean snapshotPending;
  private boolean failureReported;

  AssetContextFeed(
      HyperliquidConnector connector,
      HyperliquidMessageParser parser,
      HyperliquidSession session,
      Consumer<Runnable> stateLane,
      Consumer<String> diagnostics) {
    this.connector = connector;
    this.parser = parser;
    this.session = session;
    this.stateLane = stateLane;
    this.diagnostics = diagnostics;
  }

  /** Connects to Hyperliquid Mainnet without requesting metadata. */
  void start() {
    connector.setListener(this);
    connector.startWithoutMetadata(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
  }

  @Override
  public void close() {
    connector.close();
  }

  @Override
  public void onMetadata(List<PerpetualInstrument> instruments) {
    diagnostics.accept("asset-context feed reported unexpected metadata");
  }

  @Override
  public void onInitialFailure(TransportFailure failure) {
    reportOnce("asset-context feed could not connect", failure);
  }

  @Override
  public void onSocketOpened(long generation) {
    currentGeneration = generation;
    snapshotPending = true;
    failureReported = false;
  }

  @Override
  public void onFrame(final long generation, String json) {
    // Runs on the WebSocket callback thread: parse here, but publish only through the state lane.
    ParsedFrame frame = parser.parse(json);
    for (final String diagnostic : frame.diagnostics()) {
      stateLane.accept(
          new Runnable() {
            @Override
            public void run() {
              diagnostics.accept("asset-context feed: " + diagnostic);
            }
          });
    }
    for (ControlEvent event : frame.controlEvents()) {
      if (event.kind() == ControlEvent.Kind.PONG) {
        connector.acceptPong(generation);
      } else if (event.kind() == ControlEvent.Kind.ASSET_CONTEXTS) {
        submit(generation, event.markPrices());
      }
    }
  }

  @Override
  public void onFrameSent(long generation, OutboundMessage message, long sentAtMillis) {
    // The feed sends nothing whose acknowledgement matters.
  }

  @Override
  public void onDisconnected(long generation, TransportFailure failure) {
    reportOnce("asset-context feed disconnected", failure);
  }

  private void submit(final long generation, final Map<String, BigDecimal> markPrices) {
    stateLane.accept(
        new Runnable() {
          @Override
          public void run() {
            if (generation != currentGeneration) {
              return;
            }
            boolean snapshot = snapshotPending;
            snapshotPending = false;
            session.onAssetContexts(markPrices, snapshot);
          }
        });
  }

  private void reportOnce(String message, TransportFailure failure) {
    if (failureReported) {
      return;
    }
    failureReported = true;
    diagnostics.accept(message + (failure == null ? "" : ": " + failure.message()));
  }
}
```

- [ ] **Step 6: Wire the feed into the session**

In `HyperliquidSession`, add the field next to `assetContexts`:

```java
  private final AssetContextConnectorFactory assetContextConnectorFactory;
```

and

```java
  private AssetContextFeed assetContextFeed;
```

next to `profile`.

Add `AssetContextConnectorFactory assetContextConnectorFactory` to the constructor signature immediately before `Runnable afterClose`, include it in the null check, and assign it.

Start the feed at the **end of `onMetadata`**, not in `handleLogin`. Two reasons: `onAssetContexts`
drops everything while `metadataReceived` is false, and the feed clears its own `snapshotPending`
when it hands a frame over — a snapshot that arrives before metadata would be lost for good, leaving
the store permanently partial until the next reconnect. Starting after metadata also puts the relay's
market-data connection at connection index 0 and the feed at index 1, which is the order the tests
below assert.

Append to `onMetadata`, after `sink.onKnownInstruments(...)`:

```java
      if (profile != null
          && profile.source() != MarketDataSource.HYPERLIQUID
          && assetContextFeed == null) {
        HyperliquidConnector feedConnector = assetContextConnectorFactory.create();
        if (feedConnector == null) {
          sink.onDiagnostic("asset-context feed unavailable; tick candidates stay on the grid");
        } else {
          assetContextFeed =
              new AssetContextFeed(
                  feedConnector, parser, this, dispatcher::submitControl, sink::onDiagnostic);
          assetContextFeed.start();
        }
      }
```

Add the import `com.bookmap.plugins.layer0.hyperliquid.MarketDataSource`.

In `stop(StopCause)`, close the feed before the market-data connector:

```java
    if (assetContextFeed != null) {
      assetContextFeed.close();
      assetContextFeed = null;
    }
    connector.close();
```

- [ ] **Step 7: Supply the factory from the provider**

In `Provider.ProductionSessionFactory.create`, add the factory just before constructing the session and pass it as the new argument:

```java
      AssetContextConnectorFactory assetContextConnectorFactory =
          () ->
              new HyperliquidConnector(
                  transport,
                  new HyperliquidMetaParser(),
                  budget,
                  scheduler,
                  clock,
                  dispatcher::submitControl,
                  false);
```

Add the import `com.bookmap.plugins.layer0.hyperliquid.session.AssetContextConnectorFactory` to `Provider`. `transport`, `budget`, `scheduler`, `clock` and `dispatcher` are already effectively final locals in `create`, so the lambda captures them directly.

- [ ] **Step 8: Update the other session fixtures for the new constructor parameter**

Six test classes construct `HyperliquidSession` directly: `HyperliquidSessionTickSizeTest`,
`HyperliquidSessionLifecycleTest`, `HyperliquidSessionSubscriptionTest`,
`HyperliquidSessionDeltaBookTest`, `HyperliquidSessionAssetContextTest`, and the anonymous
`HyperliquidSessionFactory` inside `ProviderEndToEndTest.Fixture`. Each needs the new argument
before the `afterClose` runnable.

`HyperliquidSessionLifecycleTest`, `HyperliquidSessionSubscriptionTest`,
`HyperliquidSessionAssetContextTest` and `ProviderEndToEndTest` only ever log in with
`MarketDataSource.HYPERLIQUID`, so the factory is never called there. Give those four:

```java
            new AssetContextConnectorFactory() {
              @Override
              public HyperliquidConnector create() {
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
```

`HyperliquidSessionDeltaBookTest` logs in with Borsa, and `HyperliquidSessionTickSizeTest` is
parameterised by source and is instantiated with Borsa and Hyperdash as well as Hyperliquid. Both
need a real factory that returns a non-owning connector on the shared transport, exactly as in the
Task 7 fixture.

Because the feed adds a second connection for a relay source, the *initial* connect in those two
fixtures can no longer use `transport.openSocket()` / `transport.remoteClose(...)`, which act on the
**last** connect handle — after Step 6 that is the feed (index 1), not the market-data connection
(index 0). Fix the initial-connect sites only:

| File | Site | Change |
|---|---|---|
| `HyperliquidSessionTickSizeTest.Fixture` | the single `transport.openSocket()` | `transport.openConnection(0)` |
| `HyperliquidSessionDeltaBookTest.Fixture.login()` | `transport.openSocket()` | `transport.openConnection(0)` |
| `HyperliquidSessionDeltaBookTest.Fixture.beginRecovery()` | `transport.remoteClose(1006, "lost")` | `transport.remoteCloseConnection(0, 1006, "lost")` |

Leave the two reconnect sites (`beginRecovery()`'s `openSocket()` and the one after
`onMarketOverflow()`) alone: a reconnect creates connect handle 2, which is the last one again. Leave
the feed connection (index 1) unopened; neither fixture asserts anything about it, and `close()`
still settles its handle.

- [ ] **Step 9: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.AssetContextFeedTest'`
Expected: PASS

- [ ] **Step 10: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS

- [ ] **Step 11: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add -A src/main src/test
git commit -m "$(cat <<'EOF'
feat: give relay sources a dedicated asset-context connection

Borsa and Hyperdash both reject fastAssetCtxs, so those sources open a
second Hyperliquid Mainnet connection on the shared transport. The feed
answers its own pong frames: the session's control path gates on the
market-data generation and would drop them, reconnecting the feed on
every heartbeat.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 8: Load every perp dex with `allPerpMetas`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMetaParser.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/TestMetadata.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMetaParserTest.java`
- Modify (fixtures): every test that calls `TestMetadata.wrap`

**Interfaces:**
- Consumes: `TestMetadata.assetContextsFrame(String)` (Task 6).
- Produces: `public List<PerpetualInstrument> HyperliquidMetaParser.parseAllPerpMetas(String json) throws ProtocolException`; the old `parse(String)` is deleted. `TestMetadata.allPerpMetas(String... metaObjects)` replaces `TestMetadata.wrap`.

- [ ] **Step 1: Replace the test helper**

In `TestMetadata`, delete `wrap(String)`, `wrap(String, String...)` and `universeSize(String)`, and delete the now-unused `JsonElement` / `JsonParser` imports. Add:

```java
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
```

Keep `universe(String...)` and `assetContextsFrame(String)` unchanged.

- [ ] **Step 2: Write the failing test**

Replace the body of `HyperliquidMetaParserTest` with tests against the new entry point. Keep the existing validation cases and retarget them; add the multi-dex cases:

```java
  /** Reads live instruments from every perp dex, keeping HIP-3 names fully qualified. */
  @Test
  public void readsEveryPerpDexUniverse() throws Exception {
    List<PerpetualInstrument> instruments =
        parser.parseAllPerpMetas(
            TestMetadata.allPerpMetas(
                TestMetadata.universe("BTC", "ETH"), TestMetadata.universe("xyz:CL")));

    assertEquals(3, instruments.size());
    assertEquals("BTC", instruments.get(0).symbol());
    assertEquals("xyz:CL", instruments.get(2).symbol());
    assertNull(instruments.get(2).referencePrice());
  }

  /** A perp dex whose whole universe is delisted contributes nothing. */
  @Test
  public void skipsFullyDelistedPerpDexes() throws Exception {
    List<PerpetualInstrument> instruments =
        parser.parseAllPerpMetas(
            "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]},"
                + "{\"universe\":[{\"name\":\"flx:OIL\",\"szDecimals\":2,\"isDelisted\":true}]}]");

    assertEquals(1, instruments.size());
    assertEquals("BTC", instruments.get(0).symbol());
  }

  /** Names must be unique across every perp dex, not only inside one. */
  @Test
  public void rejectsDuplicateNamesAcrossPerpDexes() {
    assertRejected(
        TestMetadata.allPerpMetas(
            TestMetadata.universe("BTC"), TestMetadata.universe("BTC")),
        "duplicate");
  }

  /** Validation happens before delisted entries are dropped. */
  @Test
  public void validatesDelistedEntriesBeforeDroppingThem() {
    assertRejected(
        "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":9,\"isDelisted\":true}]}]",
        "szDecimals");
  }

  /** A perp dex element must be an object carrying a universe array. */
  @Test
  public void rejectsMalformedPerpDexElements() {
    assertRejected("[[]]", "object");
    assertRejected("[{\"marginTables\":[]}]", "universe");
    assertRejected("{\"universe\":[]}", "array");
  }

  private void assertRejected(String json, String messageFragment) {
    try {
      parser.parseAllPerpMetas(json);
      fail("expected ProtocolException for " + messageFragment);
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
    }
  }
```

Add `private final HyperliquidMetaParser parser = new HyperliquidMetaParser();` if the class does not have it, plus imports for `java.util.List`, `PerpetualInstrument`, `assertNull`, `assertTrue`, and `fail`. Every existing test in this file that calls `parser.parse(TestMetadata.wrap(...))` becomes `parser.parseAllPerpMetas(TestMetadata.allPerpMetas(...))`; tests that asserted a `markPx`-derived reference price move to `HyperliquidSessionAssetContextTest` and are deleted here, because `allPerpMetas` carries no contexts.

- [ ] **Step 3: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParserTest'`
Expected: compilation failure — `cannot find symbol: method parseAllPerpMetas`.

- [ ] **Step 4: Rewrite the metadata parser**

Replace the body of `HyperliquidMetaParser` with:

```java
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
```

- [ ] **Step 5: Request `allPerpMetas` in the connector**

In `HyperliquidConnector.startOnStateLane`, change the request body to `"{\"type\":\"allPerpMetas\"}"`, and in `completeMetadata` change `metaParser.parse(body)` to `metaParser.parseAllPerpMetas(body)`.

- [ ] **Step 6: Migrate the remaining fixtures**

Run `grep -rn 'TestMetadata.wrap' src/test` and replace every hit with
`TestMetadata.allPerpMetas(TestMetadata.universe(...))`. The call sites are:

| File | Change |
|---|---|
| `HyperliquidConnectorTest.validMeta` | Build the response with `TestMetadata.allPerpMetas(...)` |
| `HyperliquidConnectorTest.startPostsMainnetMetadataBeforeOpeningTheWebSocket` | Expect `{"type":"allPerpMetas"}` as the request body |
| `HyperliquidSessionLifecycleTest` (5 hits) | Drop the mark-price argument; the tests there assert lifecycle, not ticks |
| `HyperliquidSessionSubscriptionTest` (1 hit) | Drop the mark-price argument |
| `HyperliquidSessionDeltaBookTest` (1 hit) | Drop the mark-price argument |
| `HyperliquidSessionAssetContextTest` (1 hit, added in Task 6) | Drop the wrapper only |
| `AssetContextFeedTest` (1 hit, added in Task 7) | Drop the wrapper only |
| `HyperliquidSessionTickSizeTest.Fixture` (1 hit) | Deliver the mark price through the feed, below |
| `ProviderEndToEndTest.metadata(String...)` and `metadata(String[], String...)` (2 hits) | Split as below |

In `HyperliquidSessionTickSizeTest.Fixture(MarketDataSource source, String markPx)`, replace the
`transport.completeMeta` line with `TestMetadata.allPerpMetas(TestMetadata.universe("HYPE"))` and add
the feed frame after the socket is open and the feed subscription has been flushed:

```java
      if (markPx != null) {
        session.onFrame(
            1L, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"" + markPx + "\"}}"));
        drain();
      }
```

In `ProviderEndToEndTest`, keep `metadata(String... symbols)` but have it return
`TestMetadata.allPerpMetas(TestMetadata.universe(symbols))`, delete
`metadata(String[] symbols, String... markPxs)`, and change `loginWithPricedMetadata(symbol, markPx)`
to complete the unpriced metadata and then push the price through the feed:

```java
    private void loginWithPricedMetadata(String symbol, String markPx) {
      loginWithMetadata(symbol);
      frame(
          TestMetadata.assetContextsFrame(
              "{\"" + symbol + "\":{\"markPx\":\"" + markPx + "\"}}"));
      drain();
    }
```

- [ ] **Step 7: Run the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS

- [ ] **Step 8: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
git add -A src/main src/test
git commit -m "$(cat <<'EOF'
feat: list HIP-3 markets by loading every perp dex universe

Replaces the single metaAndAssetCtxs request, which only ever returned
the validator-operated dex, with one allPerpMetas request covering every
perp dex. Mark prices now come from the fastAssetCtxs feed, so no
per-dex REST fan-out is needed and Mainnet and Testnet share one path.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

### Task 9: End-to-end coverage and documentation

**Files:**
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above.
- Produces: no new API.

- [ ] **Step 1: Write the failing end-to-end test**

Add to `ProviderEndToEndTest`, using the helpers its `Fixture` already exposes (`loginWithMetadata`,
`subscribe`, `completeSends`, `ack`, `book`, `trade`, `frame`, `drain`) and the recorders
`instruments` and `data`:

```java
  /** A HIP-3 instrument subscribes, prices, and trades under its fully qualified name. */
  @Test
  public void hip3InstrumentFlowsEndToEnd() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithMetadata("BTC", "xyz:CL");
    fixture.frame(TestMetadata.assetContextsFrame("{\"xyz:CL\":{\"markPx\":\"92.283\"}}"));
    fixture.drain();

    fixture.subscribe("xyz:CL");
    fixture.completeSends();
    fixture.ack("xyz:CL", "l2Book");
    fixture.ack("xyz:CL", "trades");
    fixture.book("xyz:CL", 1L, "92.282", "1.5", "92.283", "2.5");
    fixture.trade("xyz:CL", "B", "92.283", "1.0", 2L, 9L);
    fixture.drain();

    assertEquals(Collections.singletonList("xyz:CL"), fixture.instruments.added);
    assertEquals(0.001d, fixture.instruments.lastInfo.pips, 1e-12d);
    assertFalse(fixture.data.depths.toString(), fixture.data.depths.isEmpty());
    assertFalse(fixture.data.trades.toString(), fixture.data.trades.isEmpty());
    fixture.closeTwice();
    fixture.assertClosed();
  }
```

`TestMetadata.universe` uses `szDecimals` 2, so `xyz:CL` has `priceDecimals` 4 and a native grid of
0.0001. The mark price 92.283 has two integer digits, so the finest quantum the exchange actually
quotes is `10^(2-5)` = 0.001 — which is why `lastInfo.pips` is 0.001 and not 0.0001. That assertion is
what proves the feed's mark price reached the subscribe path through the provider, not just the sink.
The server-side `nSigFigs` selection is already covered by
`HyperliquidSessionAssetContextTest.refreshedReferencePriceDrivesServerSideGrouping`, which
subscribes at an explicitly coarser tick.

- [ ] **Step 2: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderEndToEndTest'`
Expected: PASS. If the `pips` assertion fails, print `fixture.trace` and check the value against
`TickSizePlan.defaultTick(new BigDecimal("92.283"), 4)` before changing the expectation — a 0.0001
result means the mark price never reached the instrument map, which is the bug this test exists to
catch.

- [ ] **Step 3: Update the README**

In `README.md`, make these edits:

1. In the opening paragraph, replace "Hyperliquid default-DEX perpetuals" with "Hyperliquid perpetuals, including HIP-3 builder-deployed markets".
2. In "Scope and behavior", replace the bullet that begins "Only `PERPETUAL` subscriptions are accepted" with:

```markdown
- Only `PERPETUAL` subscriptions are accepted; Spot, history, account data, credentials, orders,
  and gap filling are not implemented. Every live perpetual across every perp dex is listed,
  including HIP-3 markets, which keep their fully qualified `dex:coin` names (for example
  `xyz:CL`). Metadata is one `allPerpMetas` request; mark prices arrive continuously on the
  `fastAssetCtxs` WebSocket feed.
```

3. Replace the sentence "Re-login to refresh the candidates after a large move." with "Mark prices
   are refreshed from the live feed, so the candidates follow the market without re-login."
4. In "Operating limits", add:

```markdown
Borsa and Hyperdash reject the `fastAssetCtxs` feed, so those sources open a second WebSocket to
Hyperliquid Mainnet for mark prices. That connection is read-only, never affects login or the book,
and consumes one of the ten concurrent connections the in-process budget allows, which caps a single
JVM at five relay-backed providers.

On Testnet the instrument list holds roughly 630 symbols across about 200 perp dexes, most of them
throwaway markets deployed by other developers.
```

- [ ] **Step 4: Run the full gate**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A src/test README.md
git commit -m "$(cat <<'EOF'
test: cover a HIP-3 instrument end to end and document the change

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_016vtyHbYas3bF8nKJjQAca5
EOF
)"
```

---

## Manual verification after implementation

These cannot be checked from the test suite and are recorded in the spec as open items:

- [ ] Load the JAR in Bookmap, subscribe to `xyz:CL`, and confirm the alias containing `:` survives a
      workspace save/reload and a recording. Windows filenames cannot contain `:`, and how Bookmap
      names those files is only observable on a real installation.
- [ ] Confirm the Testnet instrument list (~630 symbols) is still usable in the Subscribe dialog.
- [ ] Confirm with a relay source selected (Borsa) that tick-size candidates match the Hyperliquid
      source for the same instrument, which proves the second connection is delivering mark prices.
