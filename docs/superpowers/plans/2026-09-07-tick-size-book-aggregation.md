# Tick Size Selection and Book Aggregation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Offer Hyperliquid-style tick size candidates in Bookmap's Subscribe dialog, request the matching server-side `l2Book` aggregation (`nSigFigs` / `mantissa`, plus `nLevels=400` for Borsa), and aggregate locally on publish so the book stays correct whatever quantum the server sends.

**Architecture:** Internal books stay on the native price grid (`10^-(6-szDecimals)` int units, lossless). A per-subscription `PriceBucketer` maps native units onto the chosen tick on publish (bids floor, asks ceil, sizes summed, saturating at `Integer.MAX_VALUE`). A pure `TickSizePlan` derives the candidate ticks and the server parameters from the instrument's login-time mark price, which now comes from `metaAndAssetCtxs`.

**Tech Stack:** Java 8 bytecode (no `var`, `List.of`, records), Gson 2.4, Bookmap api-core 7.4.0.10, JUnit 4.13.2, Gradle wrapper with spotless (google-java-format 1.30.0), checkstyle, spotbugs.

**Spec:** `docs/superpowers/specs/2026-09-07-tick-size-book-aggregation-design.md`

## Global Constraints

- Source and bytecode level is Java 8: no `var`, no `List.of`, no records, no `Map.entry`; the `verifyJava8Bytecode` task fails otherwise.
- Every public and package-private type needs a Javadoc comment (checkstyle `MissingJavadocType`); line length is checked; no star imports; no unused imports.
- Gradle only runs with `JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1` and `--offline`. The shell is fish, so prefix commands with `env`.
- Test command: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '<FQCN>'`. Full gate before every commit: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`.
- Always run `spotlessApply` before committing; the repository rejects unformatted code.
- The adapter is read-only market data: no trading, credentials, or private data anywhere.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01LLTnSsKL5QCvYGtKL737mP
  ```
- Prices are exchanged as `BigDecimal`; `double` appears only at the Bookmap API boundary (`pips`, trade price units).
- Rounding on publish is always: bid bucket = floor, ask bucket = ceil, sizes summed. Never round to nearest.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/.../model/PerpetualInstrument.java` | Existing metadata + new nullable `referencePrice` (mark price at login) |
| `src/main/java/.../model/TickSizePlan.java` (new) | Pure functions: native quantum, tick candidates, default tick, server parameters, tick/grid ratio |
| `src/main/java/.../book/PriceBucketer.java` (new) | Native-unit → bucket mapping for one subscription, trade price scaling, saturating sums |
| `src/main/java/.../book/OrderBookSnapshotDiff.java` | Snapshot path: bucketize normalized snapshots before diffing |
| `src/main/java/.../book/DeltaOrderBook.java` | Seed-then-delta path: native staged/published books + published bucket totals |
| `src/main/java/.../parse/HyperliquidMetaParser.java` | Parse `[meta, ctxs]` and read `markPx` |
| `src/main/java/.../HyperliquidConnector.java` | Request `metaAndAssetCtxs` |
| `src/main/java/.../SourceProfile.java` | Replace fixed `l2BookParameters()` with `nLevels()` |
| `src/main/java/.../session/HyperliquidSessionApi.java`, `HyperliquidSession.java`, `SubscriptionRecord.java`, `SessionSink.java` | Carry the chosen tick, build parameters and bucketer per subscription |
| `src/main/java/.../Provider.java` | Offer candidates, read `SubscribeInfoCrypto.pips`, publish `InstrumentInfo.pips = tick` |
| `src/test/java/.../TestMetadata.java` (new) | Test helper that builds `metaAndAssetCtxs` JSON |
| `README.md` | Document tick selection, depth per source, and decade-crossing behavior |

`...` stands for `com/bookmap/plugins/layer0/hyperliquid`.

---

### Task 1: `PerpetualInstrument.referencePrice`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrument.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrumentTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `public PerpetualInstrument(String symbol, int sizeDecimals, BigDecimal referencePrice)` and `public BigDecimal referencePrice()` (null when unknown or non-positive). The existing two-argument constructor keeps working and yields a null reference price.

- [ ] **Step 1: Write the failing test**

Add to `PerpetualInstrumentTest`:

```java
  /** Keeps a positive mark price as the reference price and drops anything else. */
  @Test
  public void keepsPositiveReferencePriceAndDropsOthers() {
    assertEquals(
        new BigDecimal("87.785"),
        new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785")).referencePrice());
    assertNull(new PerpetualInstrument("HYPE", 2).referencePrice());
    assertNull(new PerpetualInstrument("HYPE", 2, BigDecimal.ZERO).referencePrice());
    assertNull(new PerpetualInstrument("HYPE", 2, new BigDecimal("-1")).referencePrice());
  }
```

Add `import static org.junit.Assert.assertNull;`.

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrumentTest'`
Expected: compilation error, `referencePrice()` and the three-argument constructor do not exist.

- [ ] **Step 3: Write minimal implementation**

In `PerpetualInstrument`:

```java
  private final BigDecimal referencePrice;

  /** Creates metadata without a reference price; tick candidates then fall back to the grid. */
  public PerpetualInstrument(String symbol, int sizeDecimals) {
    this(symbol, sizeDecimals, null);
  }

  /**
   * Creates metadata for a perpetual instrument with a valid Hyperliquid size scale.
   *
   * @param referencePrice mark price observed at login, or null; non-positive values are dropped
   */
  public PerpetualInstrument(String symbol, int sizeDecimals, BigDecimal referencePrice) {
    if (sizeDecimals < 0 || sizeDecimals > 6) {
      throw new IllegalArgumentException("sizeDecimals must be between zero and six");
    }
    this.symbol = symbol;
    this.sizeDecimals = sizeDecimals;
    this.priceDecimals = 6 - sizeDecimals;
    this.pips = Math.pow(10d, -priceDecimals);
    this.sizeMultiplier = Math.pow(10d, sizeDecimals);
    this.referencePrice =
        referencePrice != null && referencePrice.signum() > 0 ? referencePrice : null;
  }

  /** Returns the mark price observed at login, or null when unknown. */
  public BigDecimal referencePrice() {
    return referencePrice;
  }
```

Replace the existing constructor body with the delegation shown above. Update the Javadoc of `pips()` to: `/** Returns the native price grid (lossless display quantum), not the Bookmap tick chosen at subscribe time. */`.

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrumentTest'`
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrument.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrumentTest.java
git commit -m "feat: keep the login mark price on PerpetualInstrument"
```

---

### Task 2: `metaAndAssetCtxs` metadata with mark prices

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMetaParser.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java` (the `postJson` body near line 284)
- Create: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/TestMetadata.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMetaParserTest.java`
- Modify (fixtures): `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java`, `ProviderEndToEndTest.java`, `session/HyperliquidSessionDeltaBookTest.java`, `session/HyperliquidSessionLifecycleTest.java`, `session/HyperliquidSessionSubscriptionTest.java`

**Interfaces:**
- Consumes: `PerpetualInstrument(String, int, BigDecimal)` from Task 1.
- Produces: `HyperliquidMetaParser.parse(String)` now requires the `[meta, ctxs]` array and fills `referencePrice` from `ctxs[i].markPx`. Test helper `TestMetadata.universe(String... symbols)` (legacy object with `szDecimals: 2`), `TestMetadata.wrap(String metaObject)` (empty contexts), `TestMetadata.wrap(String metaObject, String... markPxs)`.

- [ ] **Step 1: Write the test helper**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/TestMetadata.java`:

```java
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
```

- [ ] **Step 2: Rewrite the parser tests**

Replace the body of `HyperliquidMetaParserTest` (keep the `symbols` and `assertRejected` helpers) with:

```java
  @Test
  public void parsesValidUniverseAndFiltersOnlyAfterValidation() throws Exception {
    String json =
        TestMetadata.wrap(
            "{\"universe\":["
                + "{\"name\":\"BTC\",\"szDecimals\":5},"
                + "{\"name\":\"ETH\",\"szDecimals\":4,\"isDelisted\":false},"
                + "{\"name\":\"OLD\",\"szDecimals\":2,\"isDelisted\":true}]}",
            "80203.0",
            "2519.3",
            "0.5");

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(Arrays.asList("BTC", "ETH"), symbols(result));
    assertEquals(new BigDecimal("80203.0"), result.get(0).referencePrice());
    assertEquals(new BigDecimal("2519.3"), result.get(1).referencePrice());
  }

  @Test
  public void referencePriceIsNullWhenMarkPxIsUnusable() throws Exception {
    String meta =
        "{\"universe\":[{\"name\":\"A\",\"szDecimals\":1},{\"name\":\"B\",\"szDecimals\":1},"
            + "{\"name\":\"C\",\"szDecimals\":1},{\"name\":\"D\",\"szDecimals\":1},"
            + "{\"name\":\"E\",\"szDecimals\":1}]}";
    String json =
        "["
            + meta
            + ",[{},{\"markPx\":null},{\"markPx\":\"abc\"},{\"markPx\":\"0\"},\"not-an-object\"]]";

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(5, result.size());
    for (PerpetualInstrument instrument : result) {
      assertNull(instrument.referencePrice());
    }
  }

  @Test
  public void rejectsNonArrayRootWrongLengthAndContextMismatch() {
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]}");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]}]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},[],[]]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},[]]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},{}]");
    assertRejected("[[],[]]");
  }

  @Test
  public void rejectsMissingOrNonArrayUniverse() {
    assertRejected(TestMetadata.wrap("{}"));
    assertRejected(TestMetadata.wrap("{\"universe\":{}}"));
  }

  @Test
  public void rejectsMissingBlankOrDuplicateNames() {
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"szDecimals\":1}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"  \",\"szDecimals\":1}]}"));
    assertRejected(
        TestMetadata.wrap(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
                + "{\"name\":\"BTC\",\"szDecimals\":2}]}"));
  }

  @Test
  public void rejectsNonIntegralOrOutOfRangeSizeDecimals() {
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1.5}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":\"1\"}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":-1}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":7}]}"));
  }

  @Test
  public void rejectsNonBooleanDelistedFlag() {
    assertRejected(
        TestMetadata.wrap(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1,\"isDelisted\":\"false\"}]}"));
  }

  @Test
  public void validatesDelistedMembersBeforeFiltering() {
    assertRejected(
        TestMetadata.wrap(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
                + "{\"name\":\"OLD\",\"szDecimals\":7,\"isDelisted\":true}]}"));
  }
```

Add imports: `com.bookmap.plugins.layer0.hyperliquid.TestMetadata`, `java.math.BigDecimal`, `static org.junit.Assert.assertNull`.

- [ ] **Step 3: Run parser tests to verify they fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParserTest'`
Expected: FAIL (`parsesValidUniverse...` rejects the array root; `rejectsNonArrayRoot...` fails because the legacy object is accepted).

- [ ] **Step 4: Implement the parser**

Replace `parse` and `MetadataEntry` in `HyperliquidMetaParser`:

```java
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
```

Change `parseEntry(JsonElement element, Set<String> names)` to `parseEntry(JsonElement element, Set<String> names, JsonElement context)` and end it with `return new MetadataEntry(name, Integer.parseInt(rawSizeDecimals), isDelisted, referencePrice(context));`. Add:

```java
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
```

Extend `MetadataEntry` with `private final BigDecimal referencePrice;` and a four-argument constructor. Add imports `com.google.gson.JsonArray` and `java.math.BigDecimal`.

In `HyperliquidConnector`, change the request body string `"{\"type\":\"meta\"}"` to `"{\"type\":\"metaAndAssetCtxs\"}"` and update the Javadoc of the class or method if it names `meta`.

- [ ] **Step 5: Run parser tests to verify they pass**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParserTest'`
Expected: PASS

- [ ] **Step 6: Update every test fixture that fakes metadata**

Run `grep -rn 'universe' src/test/java --include=*.java | grep -v HyperliquidMetaParserTest | grep -v TestMetadata` (the Java sources spell it `\"universe\"`, so do not quote it in the pattern) and convert every hit so the string passed to `completeMeta` / `lateCompleteMeta` is wrapped. Concretely:

- `HyperliquidConnectorTest`: line 38 becomes `assertEquals("{\"type\":\"metaAndAssetCtxs\"}", fixture.transport.httpBody());`; `validMeta(String coin)` returns `TestMetadata.wrap(TestMetadata.universe(coin))`. Convert any other inline `"{\"universe\":...}"` literal in this file with `TestMetadata.wrap(...)`.
- `ProviderEndToEndTest`: replace the private `metadata(String... symbols)` body with `return TestMetadata.wrap(TestMetadata.universe(symbols));`.
- `session/HyperliquidSessionDeltaBookTest.login()`: `transport.completeMeta(200, TestMetadata.wrap(TestMetadata.universe("BTC")));`
- `session/HyperliquidSessionLifecycleTest`: wrap each inline literal (the invalid `{"universe":[{"name":"BTC"}]}` becomes `TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\"}]}")` and must still fail login); in `loginWithSymbols`, keep building the universe object into `metadata`, then pass `TestMetadata.wrap(metadata.toString())`.
- `session/HyperliquidSessionSubscriptionTest.loginWithSymbols`: same wrapping as above.
- Any remaining hit: same pattern. Import `com.bookmap.plugins.layer0.hyperliquid.TestMetadata` in session-package tests.

Empty contexts leave `referencePrice` null, so every existing expectation (native tick, existing depth units) stays valid.

- [ ] **Step 7: Run the whole test suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS. If a test still fails with `metadata response must be a two-element array`, its fixture was missed in Step 6. Also run `grep -rn 'completeMeta\|lateCompleteMeta' src/test/java` and confirm every argument goes through `TestMetadata.wrap`.

- [ ] **Step 8: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add -A src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMetaParser.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java src/test/java
git commit -m "feat: load mark prices from metaAndAssetCtxs at login"
```

---

### Task 3: `TickSizePlan`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/TickSizePlan.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/TickSizePlanTest.java`

**Interfaces:**
- Consumes: `L2BookParameters(Integer nSigFigs, Integer nLevels, Integer mantissa)` (existing).
- Produces (all `public static`):
  - `BigDecimal nativeQuantum(BigDecimal referencePrice, int priceDecimals)`
  - `List<BigDecimal> candidates(BigDecimal referencePrice, int priceDecimals)` — ascending, trailing zeros stripped, first element is the default
  - `BigDecimal defaultTick(BigDecimal referencePrice, int priceDecimals)`
  - `L2BookParameters parametersFor(BigDecimal tick, BigDecimal referencePrice, int priceDecimals, Integer nLevels)`
  - `long ratio(BigDecimal tick, int priceDecimals)` — throws `IllegalArgumentException` for null, non-positive, off-grid, or `> Integer.MAX_VALUE`
  - `boolean isSupportedTick(BigDecimal tick, int priceDecimals)`

- [ ] **Step 1: Write the failing tests**

Create `TickSizePlanTest`:

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests tick candidates and l2Book aggregation parameters derived from a reference price. */
public class TickSizePlanTest {

  private static final BigDecimal HYPE = new BigDecimal("87.785");
  private static final int HYPE_DECIMALS = 4;

  @Test
  public void candidatesFollowTheHyperliquidDropdownForEachPriceMagnitude() {
    assertEquals(
        Arrays.asList("0.001", "0.002", "0.005", "0.01", "0.1", "1"),
        plain(TickSizePlan.candidates(HYPE, HYPE_DECIMALS)));
    assertEquals(
        Arrays.asList("0.1", "0.2", "0.5", "1", "10", "100"),
        plain(TickSizePlan.candidates(new BigDecimal("2519.3"), 2)));
    assertEquals(
        Arrays.asList("1", "2", "5", "10", "100", "1000"),
        plain(TickSizePlan.candidates(new BigDecimal("80203.0"), 1)));
    assertEquals(
        Arrays.asList("1", "10", "20", "50", "100", "1000", "10000"),
        plain(TickSizePlan.candidates(new BigDecimal("112345"), 1)));
    assertEquals(
        Arrays.asList("0.000001", "0.00001", "0.0001"),
        plain(TickSizePlan.candidates(new BigDecimal("0.0012345"), 6)));
  }

  @Test
  public void withoutReferencePriceOnlyTheNativeGridIsOffered() {
    assertEquals(Arrays.asList("0.0001"), plain(TickSizePlan.candidates(null, 4)));
    assertEquals(Arrays.asList("0.0001"), plain(TickSizePlan.candidates(BigDecimal.ZERO, 4)));
    assertEquals("0.0001", TickSizePlan.defaultTick(null, 4).toPlainString());
  }

  @Test
  public void defaultTickIsTheFinestQuotedQuantum() {
    assertEquals("0.001", TickSizePlan.defaultTick(HYPE, HYPE_DECIMALS).toPlainString());
    assertEquals("1", TickSizePlan.defaultTick(new BigDecimal("112345"), 1).toPlainString());
  }

  @Test
  public void parametersPickTheCoarsestServerQuantumDividingTheTick() {
    assertEquals(params(null, null), parametersFor("0.001"));
    assertEquals(params(5, 2), parametersFor("0.002"));
    assertEquals(params(5, 5), parametersFor("0.005"));
    assertEquals(params(4, null), parametersFor("0.01"));
    assertEquals(params(3, null), parametersFor("0.1"));
    assertEquals(params(2, null), parametersFor("1"));
    assertEquals(params(4, null), parametersFor("0.02"));
    assertEquals(params(2, null), parametersFor("10"));
  }

  @Test
  public void staleOrFinerTicksAndMissingReferenceFallBackToFullPrecision() {
    assertEquals(params(null, null), parametersFor("0.0001"));
    assertEquals(params(null, null), parametersFor("0.00005"));
    assertEquals(
        new L2BookParameters(null, Integer.valueOf(400), null),
        TickSizePlan.parametersFor(new BigDecimal("0.01"), null, 4, Integer.valueOf(400)));
    assertEquals(
        new L2BookParameters(Integer.valueOf(4), Integer.valueOf(400), null),
        TickSizePlan.parametersFor(new BigDecimal("0.01"), HYPE, HYPE_DECIMALS, Integer.valueOf(400)));
    assertEquals(params(null, null), TickSizePlan.parametersFor(null, HYPE, HYPE_DECIMALS, null));
  }

  @Test
  public void ratioCountsNativeGridStepsAndRejectsUnsupportedTicks() {
    assertEquals(10L, TickSizePlan.ratio(new BigDecimal("0.001"), 4));
    assertEquals(1L, TickSizePlan.ratio(new BigDecimal("0.0001"), 4));
    assertEquals(10000L, TickSizePlan.ratio(new BigDecimal("1"), 4));
    assertEquals(3L, TickSizePlan.ratio(new BigDecimal("0.0003"), 4));
    assertTrue(TickSizePlan.isSupportedTick(new BigDecimal("0.01"), 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("0.00005"), 4));
    assertFalse(TickSizePlan.isSupportedTick(BigDecimal.ZERO, 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("-0.01"), 4));
    assertFalse(TickSizePlan.isSupportedTick(null, 4));
    assertFalse(TickSizePlan.isSupportedTick(new BigDecimal("1000000000"), 4));
    try {
      TickSizePlan.ratio(new BigDecimal("0.00005"), 4);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // off-grid ticks are rejected
    }
  }

  private static L2BookParameters parametersFor(String tick) {
    return TickSizePlan.parametersFor(new BigDecimal(tick), HYPE, HYPE_DECIMALS, null);
  }

  private static L2BookParameters params(Integer nSigFigs, Integer mantissa) {
    return new L2BookParameters(nSigFigs, null, mantissa);
  }

  private static List<String> plain(List<BigDecimal> ticks) {
    List<String> result = new ArrayList<String>();
    for (BigDecimal tick : ticks) {
      result.add(tick.toPlainString());
    }
    return result;
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlanTest'`
Expected: compilation error, `TickSizePlan` does not exist.

- [ ] **Step 3: Implement `TickSizePlan`**

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Pure tick-size arithmetic. Hyperliquid prices carry at most five significant figures and at most
 * {@code priceDecimals} decimals, and integer prices are always allowed; the reference price
 * (login mark price) therefore decides which absolute ticks the exchange actually quotes and which
 * {@code nSigFigs}/{@code mantissa} aggregation produces a given tick.
 */
public final class TickSizePlan {

  private static final int MAX_SIG_FIGS = 5;
  private static final int MIN_SIG_FIGS = 2;
  private static final int[] MANTISSAS = {1, 2, 5};

  private TickSizePlan() {}

  /** Returns the finest quantum the exchange quotes near the reference price. */
  public static BigDecimal nativeQuantum(BigDecimal referencePrice, int priceDecimals) {
    BigDecimal grid = pow10(-priceDecimals);
    if (!isUsable(referencePrice)) {
      return grid;
    }
    BigDecimal fiveSigFigs =
        pow10(integerDigits(referencePrice) - MAX_SIG_FIGS).min(BigDecimal.ONE);
    return fiveSigFigs.max(grid);
  }

  /** Returns ascending Bookmap tick candidates; the first one is the default. */
  public static List<BigDecimal> candidates(BigDecimal referencePrice, int priceDecimals) {
    BigDecimal quantum = nativeQuantum(referencePrice, priceDecimals);
    TreeSet<BigDecimal> ticks = new TreeSet<BigDecimal>();
    ticks.add(quantum);
    for (ServerQuantum server : serverQuanta(referencePrice)) {
      if (isMultiple(server.quantum, quantum)) {
        ticks.add(server.quantum);
      }
    }
    List<BigDecimal> result = new ArrayList<BigDecimal>();
    for (BigDecimal tick : ticks) {
      result.add(tick.stripTrailingZeros());
    }
    return Collections.unmodifiableList(result);
  }

  /** Returns the default tick: the finest quantum actually quoted near the reference price. */
  public static BigDecimal defaultTick(BigDecimal referencePrice, int priceDecimals) {
    return candidates(referencePrice, priceDecimals).get(0);
  }

  /**
   * Returns the coarsest server aggregation whose quantum divides the tick. Omitting nSigFigs wins
   * ties, and an unknown reference price or an unmatched tick keeps full precision.
   */
  public static L2BookParameters parametersFor(
      BigDecimal tick, BigDecimal referencePrice, int priceDecimals, Integer nLevels) {
    BigDecimal best = nativeQuantum(referencePrice, priceDecimals);
    Integer nSigFigs = null;
    Integer mantissa = null;
    if (tick != null && tick.signum() > 0) {
      BigDecimal quantum = best;
      for (ServerQuantum server : serverQuanta(referencePrice)) {
        if (isMultiple(server.quantum, quantum)
            && isMultiple(tick, server.quantum)
            && server.quantum.compareTo(best) > 0) {
          best = server.quantum;
          nSigFigs = server.nSigFigs;
          mantissa = server.mantissa;
        }
      }
    }
    return new L2BookParameters(nSigFigs, nLevels, mantissa);
  }

  /** Returns how many native grid steps one tick spans; rejects unsupported ticks. */
  public static long ratio(BigDecimal tick, int priceDecimals) {
    if (tick == null || tick.signum() <= 0) {
      throw new IllegalArgumentException("tick must be positive");
    }
    BigInteger steps;
    try {
      steps = tick.movePointRight(priceDecimals).toBigIntegerExact();
    } catch (ArithmeticException offGrid) {
      throw new IllegalArgumentException("tick must be a multiple of the native price grid");
    }
    if (steps.bitLength() > 31) {
      throw new IllegalArgumentException("tick is too coarse for int depth units");
    }
    return steps.longValue();
  }

  /** Returns whether {@link #ratio} accepts the tick. */
  public static boolean isSupportedTick(BigDecimal tick, int priceDecimals) {
    try {
      ratio(tick, priceDecimals);
      return true;
    } catch (IllegalArgumentException unsupported) {
      return false;
    }
  }

  /** Server quanta from finest to coarsest: 5 sig figs with mantissa 1/2/5, then 4, 3, 2. */
  private static List<ServerQuantum> serverQuanta(BigDecimal referencePrice) {
    List<ServerQuantum> result = new ArrayList<ServerQuantum>();
    if (!isUsable(referencePrice)) {
      return result;
    }
    int digits = integerDigits(referencePrice);
    BigDecimal fiveSigFigs = pow10(digits - MAX_SIG_FIGS);
    for (int mantissa : MANTISSAS) {
      result.add(
          new ServerQuantum(
              fiveSigFigs.multiply(BigDecimal.valueOf(mantissa)),
              Integer.valueOf(MAX_SIG_FIGS),
              mantissa == 1 ? null : Integer.valueOf(mantissa)));
    }
    for (int sigFigs = MAX_SIG_FIGS - 1; sigFigs >= MIN_SIG_FIGS; sigFigs--) {
      result.add(new ServerQuantum(pow10(digits - sigFigs), Integer.valueOf(sigFigs), null));
    }
    return result;
  }

  private static boolean isUsable(BigDecimal price) {
    return price != null && price.signum() > 0;
  }

  /** Number of integer digits: 87.785 → 2, 0.0012345 → -2, 80203.0 → 5. */
  private static int integerDigits(BigDecimal price) {
    return price.precision() - price.scale();
  }

  private static BigDecimal pow10(int exponent) {
    return BigDecimal.ONE.scaleByPowerOfTen(exponent);
  }

  private static boolean isMultiple(BigDecimal value, BigDecimal unit) {
    return value.compareTo(unit) >= 0 && value.remainder(unit).signum() == 0;
  }

  private static final class ServerQuantum {
    private final BigDecimal quantum;
    private final Integer nSigFigs;
    private final Integer mantissa;

    private ServerQuantum(BigDecimal quantum, Integer nSigFigs, Integer mantissa) {
      this.quantum = quantum;
      this.nSigFigs = nSigFigs;
      this.mantissa = mantissa;
    }
  }
}
```

Note: `TreeSet<BigDecimal>` orders by `compareTo`, so `0.001` and `0.0010` deduplicate. `toPlainString()` after `stripTrailingZeros()` prints `10`, not `1E+1`.

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlanTest'`
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/TickSizePlan.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/TickSizePlanTest.java
git commit -m "feat: derive tick candidates and l2Book aggregation from the mark price"
```

---

### Task 4: `PriceBucketer`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/PriceBucketer.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/PriceBucketerTest.java`

**Interfaces:**
- Consumes: `TickSizePlan.ratio(BigDecimal, int)`, `PerpetualInstrument.toTradePriceUnits(BigDecimal)`, `PerpetualInstrument.priceDecimals()`.
- Produces:
  - `public PriceBucketer(PerpetualInstrument instrument, BigDecimal tick)` (throws `IllegalArgumentException` for unsupported ticks)
  - `public static PriceBucketer identity(PerpetualInstrument instrument)` — tick equals the native grid
  - `public BigDecimal tick()`, `public long ratio()`
  - `public int bucket(boolean bid, int nativeUnits)`, `public int bidBucket(int)`, `public int askBucket(int)`
  - `public int[] nativeRange(int bucket, boolean bid)` — inclusive `{low, high}`
  - `public double tradePriceUnits(BigDecimal price) throws ValueConversionException`
  - `public static int saturate(long sizeUnits)` — clamps to `[0, Integer.MAX_VALUE]`

- [ ] **Step 1: Write the failing tests**

```java
package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests native-unit to tick-bucket mapping: bids floor, asks ceil. */
public class PriceBucketerTest {

  private final PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);

  @Test
  public void identityMapsEveryNativeUnitOntoItself() {
    PriceBucketer bucketer = PriceBucketer.identity(hype);

    assertEquals(1L, bucketer.ratio());
    assertEquals(0, new BigDecimal("0.0001").compareTo(bucketer.tick()));
    assertEquals(877840, bucketer.bidBucket(877840));
    assertEquals(877850, bucketer.askBucket(877850));
    assertArrayEquals(new int[] {877840, 877840}, bucketer.nativeRange(877840, true));
  }

  @Test
  public void bidsRoundDownAndAsksRoundUpToTheTick() {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.01"));

    assertEquals(100L, bucketer.ratio());
    assertEquals(8778, bucketer.bidBucket(877840));
    assertEquals(8778, bucketer.bidBucket(877899));
    assertEquals(8779, bucketer.askBucket(877850));
    assertEquals(8778, bucketer.askBucket(877800));
    assertEquals(8778, bucketer.bucket(true, 877840));
    assertEquals(8779, bucketer.bucket(false, 877850));
    assertArrayEquals(new int[] {877800, 877899}, bucketer.nativeRange(8778, true));
    assertArrayEquals(new int[] {877801, 877900}, bucketer.nativeRange(8779, false));
  }

  @Test
  public void extremeNativeUnitsDoNotOverflow() {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.001"));

    assertEquals(Integer.MAX_VALUE / 10 + 1, bucketer.askBucket(Integer.MAX_VALUE));
    int[] range = bucketer.nativeRange(Integer.MAX_VALUE / 10 + 1, false);
    assertTrue(range[0] <= Integer.MAX_VALUE && range[1] == Integer.MAX_VALUE);
    assertEquals(Integer.MAX_VALUE, PriceBucketer.saturate(4294967294L));
    assertEquals(0, PriceBucketer.saturate(-1L));
    assertEquals(7, PriceBucketer.saturate(7L));
  }

  @Test
  public void tradePriceUnitsScaleByTheRatioAndKeepTheNativeContract() throws Exception {
    PriceBucketer bucketer = new PriceBucketer(hype, new BigDecimal("0.002"));

    assertEquals(43953.25d, bucketer.tradePriceUnits(new BigDecimal("87.9065")), 0d);
    assertEquals(877840d, PriceBucketer.identity(hype).tradePriceUnits(new BigDecimal("87.784")), 0d);
    assertRejected(bucketer, new BigDecimal("87.90655"), ValueConversionException.Reason.NON_INTEGRAL);
    assertRejected(bucketer, BigDecimal.ZERO, ValueConversionException.Reason.NON_POSITIVE);
    assertRejected(bucketer, null, ValueConversionException.Reason.NON_POSITIVE);
  }

  @Test
  public void rejectsUnsupportedTicks() {
    try {
      new PriceBucketer(hype, new BigDecimal("0.00005"));
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // finer than the native grid
    }
  }

  private static void assertRejected(
      PriceBucketer bucketer, BigDecimal price, ValueConversionException.Reason reason) {
    try {
      bucketer.tradePriceUnits(price);
      fail("Expected ValueConversionException");
    } catch (ValueConversionException expected) {
      assertEquals(reason, expected.reason());
    }
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.PriceBucketerTest'`
Expected: compilation error, `PriceBucketer` does not exist.

- [ ] **Step 3: Implement `PriceBucketer`**

```java
package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlan;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Maps native price units (the lossless {@code 10^-priceDecimals} grid) onto the Bookmap tick
 * chosen for one subscription. Bids round down and asks round up, matching Hyperliquid's own
 * server-side aggregation, so a published bid is never better than any real bid it contains.
 */
public final class PriceBucketer {

  private final PerpetualInstrument instrument;
  private final BigDecimal tick;
  private final long ratio;

  /** Creates a bucketer for a tick that is an integer multiple of the instrument's grid. */
  public PriceBucketer(PerpetualInstrument instrument, BigDecimal tick) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.ratio = TickSizePlan.ratio(tick, instrument.priceDecimals());
    this.tick = tick;
  }

  /** Returns a bucketer whose tick is the native grid, i.e. the identity mapping. */
  public static PriceBucketer identity(PerpetualInstrument instrument) {
    return new PriceBucketer(
        instrument, BigDecimal.ONE.scaleByPowerOfTen(-instrument.priceDecimals()));
  }

  /** Returns the Bookmap tick this bucketer publishes at. */
  public BigDecimal tick() {
    return tick;
  }

  /** Returns how many native grid steps one bucket spans. */
  public long ratio() {
    return ratio;
  }

  /** Returns the bucket of a native price for the given side. */
  public int bucket(boolean bid, int nativeUnits) {
    return bid ? bidBucket(nativeUnits) : askBucket(nativeUnits);
  }

  /** Rounds a native bid price down to its bucket. */
  public int bidBucket(int nativeUnits) {
    return (int) Math.floorDiv((long) nativeUnits, ratio);
  }

  /** Rounds a native ask price up to its bucket. */
  public int askBucket(int nativeUnits) {
    return (int) (((long) nativeUnits + ratio - 1L) / ratio);
  }

  /** Returns the inclusive native-unit range {low, high} that maps onto one bucket. */
  public int[] nativeRange(int bucket, boolean bid) {
    long low = bid ? (long) bucket * ratio : ((long) bucket - 1L) * ratio + 1L;
    long high = bid ? ((long) bucket + 1L) * ratio - 1L : (long) bucket * ratio;
    return new int[] {(int) Math.max(0L, low), (int) Math.min(Integer.MAX_VALUE, high)};
  }

  /**
   * Converts a trade price to Bookmap units of this tick. The native conversion and its exception
   * contract are unchanged; the result may carry a fraction when the ratio exceeds one.
   */
  public double tradePriceUnits(BigDecimal price) throws ValueConversionException {
    return instrument.toTradePriceUnits(price) / (double) ratio;
  }

  /** Clamps a summed size to the int range Bookmap accepts. */
  public static int saturate(long sizeUnits) {
    if (sizeUnits <= 0L) {
      return 0;
    }
    return sizeUnits > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sizeUnits;
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.PriceBucketerTest'`
Expected: PASS

- [ ] **Step 5: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/PriceBucketer.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/PriceBucketerTest.java
git commit -m "feat: add PriceBucketer for tick-level book publication"
```

---

### Task 5: Bucketed snapshot diffs

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiff.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiffTest.java`

**Interfaces:**
- Consumes: `PriceBucketer` (Task 4).
- Produces: `public OrderBookSnapshotDiff(PerpetualInstrument instrument, PriceBucketer bucketer)`; the existing one-argument constructor delegates to `PriceBucketer.identity(instrument)`. `validate` is unchanged (native units). `apply`/`clear` now emit bucket-keyed `DepthUpdate`s with summed sizes.

- [ ] **Step 1: Write the failing tests**

Add to `OrderBookSnapshotDiffTest` (reuse the file's existing `snapshot`, `bids`, `asks`, `level`, `depth`, `valid` helpers):

```java
  /** Matches the aggregation Hyperliquid itself produced for nSigFigs=4 at the same instant. */
  @Test
  public void coarseTickSumsLevelsWithBidsFlooredAndAsksCeiled() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(
                        1,
                        bids(
                            level("87.784", "2.36"),
                            level("87.783", "1.32"),
                            level("87.779", "4.96"),
                            level("87.777", "20.63")),
                        asks(level("87.785", "161.16"), level("87.786", "24.1"), level("87.789", "17.08"))),
                    0)
                .snapshot(),
            false);

    assertEquals(
        Arrays.asList(
            depth(true, 8777, 2559), depth(true, 8778, 368), depth(false, 8779, 20234)),
        updates);
  }

  @Test
  public void coarseTickEmitsOnlyChangedBucketsAndDeletesEmptiedOnes() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));
    coarse.apply(
        coarse
            .validate(
                snapshot(
                    1, bids(level("87.784", "1"), level("87.783", "1")), asks(level("87.785", "1"))),
                0)
            .snapshot(),
        false);

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(2, bids(level("87.782", "1"), level("87.781", "1")), asks(level("87.791", "1"))),
                    1)
                .snapshot(),
            false);

    assertEquals(
        Arrays.asList(depth(false, 8779, 0), depth(false, 8780, 100)), updates);
    assertEquals(
        Arrays.asList(depth(true, 8778, 0), depth(false, 8780, 0)), coarse.clear());
  }

  @Test
  public void bucketSumsSaturateAtIntegerMax() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(
                        1,
                        bids(level("87.784", "21474836.47"), level("87.783", "21474836.47")),
                        asks()),
                    0)
                .snapshot(),
            false);

    assertEquals(Arrays.asList(depth(true, 8778, Integer.MAX_VALUE)), updates);
  }
```

In the second test the bid bucket 8778 keeps total 200 units across both snapshots, so no bid update is emitted; the ask moves from bucket 8779 to 8780.

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiffTest'`
Expected: compilation error (two-argument constructor missing).

- [ ] **Step 3: Implement bucketing in `OrderBookSnapshotDiff`**

- Add field `private final PriceBucketer bucketer;`.
- Constructors:

```java
  /** Creates a differ that publishes on the native grid (identity bucketing). */
  public OrderBookSnapshotDiff(PerpetualInstrument instrument) {
    this(instrument, PriceBucketer.identity(instrument));
  }

  /** Creates a differ that publishes bucket totals at the bucketer's tick. */
  public OrderBookSnapshotDiff(PerpetualInstrument instrument, PriceBucketer bucketer) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.bucketer = Objects.requireNonNull(bucketer, "bucketer");
  }
```

- The `bids`/`asks` baselines now hold bucket → summed size. Change `apply`:

```java
  public List<DepthUpdate> apply(NormalizedBookSnapshot snapshot, boolean forceFullResync) {
    Objects.requireNonNull(snapshot, "snapshot");
    SortedMap<Integer, BigDecimal> newBids = bucketize(snapshot.bids(), true);
    SortedMap<Integer, BigDecimal> newAsks = bucketize(snapshot.asks(), false);
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendUpdates(updates, true, bids, newBids, forceFullResync);
    appendUpdates(updates, false, asks, newAsks, forceFullResync);

    bids = newBids;
    asks = newAsks;
    return Collections.unmodifiableList(updates);
  }

  /** Sums native levels into buckets of the selected tick; bids floor, asks ceil. */
  private SortedMap<Integer, BigDecimal> bucketize(
      SortedMap<Integer, BigDecimal> nativeLevels, boolean bid) {
    SortedMap<Integer, BigDecimal> buckets = new TreeMap<Integer, BigDecimal>();
    for (Map.Entry<Integer, BigDecimal> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketer.bucket(bid, level.getKey().intValue()));
      BigDecimal total = buckets.get(bucket);
      buckets.put(bucket, total == null ? level.getValue() : total.add(level.getValue()));
    }
    return buckets;
  }
```

`appendUpserts` already converts with `instrument.toSizeUnits`, which saturates at `Integer.MAX_VALUE`; no change there. Update the class Javadoc: "Validates complete order-book snapshots on the native grid and emits deterministic incremental depth changes at the subscription's tick."

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiffTest'`
Expected: PASS (existing tests still pass because identity bucketing is unchanged).

- [ ] **Step 5: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiff.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiffTest.java
git commit -m "feat: publish snapshot books at the subscription tick"
```

---

### Task 6: Bucketed seed-then-delta book

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBook.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBookTest.java`

**Interfaces:**
- Consumes: `PriceBucketer` (Task 4).
- Produces: `public DeltaOrderBook(PerpetualInstrument instrument, PriceBucketer bucketer)`; one-argument constructor delegates to identity. `applySeed`, `applyDelta`, `publishStaged`, `replacePublished`, `clearPublished`, `reset`, `seeded`, `beginGeneration` keep their signatures; every returned `DepthUpdate` is bucket-keyed with saturating totals. A non-live delta returns an empty update list.

- [ ] **Step 1: Write the failing tests**

Add to `DeltaOrderBookTest` (reuse its `snapshot`, `levels`, `level`, `depth` helpers):

```java
  @Test
  public void coarseTickPublishesBucketTotalsAndFoldsDeltasIntoThem() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    DeltaOrderBook coarse = new DeltaOrderBook(hype, new PriceBucketer(hype, new BigDecimal("0.01")));
    coarse.applySeed(
        snapshot(
            1L,
            levels(level("87.784", "2.36"), level("87.783", "1.32")),
            levels(level("87.785", "161.16"))));

    assertEquals(
        Arrays.asList(depth(true, 8778, 368), depth(false, 8779, 16116)), coarse.publishStaged());

    DeltaOrderBook.Result shrink =
        coarse.applyDelta(snapshot(2L, levels(level("87.783", "0")), levels()), true);
    assertEquals(Arrays.asList(depth(true, 8778, 236)), shrink.updates());

    DeltaOrderBook.Result grow =
        coarse.applyDelta(
            snapshot(3L, levels(), levels(level("87.786", "1"), level("87.789", "2"))), true);
    assertEquals(Arrays.asList(depth(false, 8779, 16416)), grow.updates());

    DeltaOrderBook.Result vanish =
        coarse.applyDelta(snapshot(4L, levels(level("87.784", "0")), levels()), true);
    assertEquals(Arrays.asList(depth(true, 8778, 0)), vanish.updates());

    DeltaOrderBook.Result unchanged =
        coarse.applyDelta(snapshot(5L, levels(), levels(level("87.786", "1"))), true);
    assertTrue(unchanged.updates().isEmpty());
    assertEquals(Arrays.asList(depth(false, 8779, 0)), coarse.clearPublished());
  }

  @Test
  public void coarseTickReplacePublishedDeletesVanishedBucketsAndReemitsStagedOnes() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    DeltaOrderBook coarse = new DeltaOrderBook(hype, new PriceBucketer(hype, new BigDecimal("0.01")));
    coarse.applySeed(snapshot(1L, levels(level("87.784", "1")), levels(level("87.795", "1"))));
    coarse.publishStaged();
    coarse.beginGeneration();
    coarse.applySeed(snapshot(2L, levels(level("87.774", "2")), levels(level("87.795", "3"))));
    assertTrue(coarse.applyDelta(snapshot(2L, levels(level("87.771", "1")), levels()), false).updates().isEmpty());

    assertEquals(
        Arrays.asList(depth(true, 8778, 0), depth(true, 8777, 300), depth(false, 8780, 300)),
        coarse.replacePublished());
  }

  @Test
  public void coarseTickBucketTotalsSaturate() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    DeltaOrderBook coarse = new DeltaOrderBook(hype, new PriceBucketer(hype, new BigDecimal("0.01")));
    coarse.applySeed(
        snapshot(
            1L, levels(level("87.784", "21474836.47"), level("87.783", "21474836.47")), levels()));

    assertEquals(Arrays.asList(depth(true, 8778, Integer.MAX_VALUE)), coarse.publishStaged());
  }
```

Add `import java.math.BigDecimal;` if missing (the file already imports it for helpers).

- [ ] **Step 2: Run test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBookTest'`
Expected: compilation error (two-argument constructor missing).

- [ ] **Step 3: Implement bucketing in `DeltaOrderBook`**

Rewrite the mutable state and publication methods (keep `Result`, `Status`, `normalize`, `normalizeSide`, the exception classes):

```java
  private final PerpetualInstrument instrument;
  private final PriceBucketer bucketer;
  private final TreeMap<Integer, Integer> publishedBids = new TreeMap<Integer, Integer>();
  private final TreeMap<Integer, Integer> publishedAsks = new TreeMap<Integer, Integer>();
  private final TreeMap<Integer, Integer> publishedBidBuckets = new TreeMap<Integer, Integer>();
  private final TreeMap<Integer, Integer> publishedAskBuckets = new TreeMap<Integer, Integer>();
  private TreeMap<Integer, Integer> stagedBids = new TreeMap<Integer, Integer>();
  private TreeMap<Integer, Integer> stagedAsks = new TreeMap<Integer, Integer>();
  private boolean seeded;

  /** Creates an empty book published on the native grid. */
  public DeltaOrderBook(PerpetualInstrument instrument) {
    this(instrument, PriceBucketer.identity(instrument));
  }

  /** Creates an empty book whose published levels are bucket totals at the bucketer's tick. */
  public DeltaOrderBook(PerpetualInstrument instrument, PriceBucketer bucketer) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.bucketer = Objects.requireNonNull(bucketer, "bucketer");
  }
```

`beginGeneration` and `applySeed` stay as they are except that `stagedBids`/`stagedAsks` and the two local maps built inside `applySeed` are declared as `TreeMap<Integer, Integer>`. Replace `applyDelta`:

```java
  public Result applyDelta(BookSnapshot delta, boolean live) {
    List<DepthUpdate> levels;
    try {
      levels = normalize(delta, true);
    } catch (UnsupportedPriceException failure) {
      return new Result(
          Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    TreeSet<Integer> touchedBidBuckets = new TreeSet<Integer>();
    TreeSet<Integer> touchedAskBuckets = new TreeSet<Integer>();
    for (DepthUpdate level : levels) {
      TreeMap<Integer, Integer> staged = level.bid() ? stagedBids : stagedAsks;
      TreeMap<Integer, Integer> published = level.bid() ? publishedBids : publishedAsks;
      Integer price = Integer.valueOf(level.price());
      if (level.size() == 0) {
        if (staged.remove(price) == null) {
          continue;
        }
        if (live) {
          published.remove(price);
        }
      } else {
        staged.put(price, Integer.valueOf(level.size()));
        if (live) {
          published.put(price, Integer.valueOf(level.size()));
        }
      }
      if (live) {
        (level.bid() ? touchedBidBuckets : touchedAskBuckets)
            .add(Integer.valueOf(bucketer.bucket(level.bid(), level.price())));
      }
    }
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendBucketChanges(updates, true, touchedBidBuckets);
    appendBucketChanges(updates, false, touchedAskBuckets);
    return new Result(Status.APPLIED, updates, "");
  }

  /** Recomputes each touched bucket from the published native book and emits only real changes. */
  private void appendBucketChanges(List<DepthUpdate> updates, boolean bid, TreeSet<Integer> touched) {
    TreeMap<Integer, Integer> published = bid ? publishedBids : publishedAsks;
    TreeMap<Integer, Integer> buckets = bid ? publishedBidBuckets : publishedAskBuckets;
    for (Integer bucket : touched) {
      int total = bucketTotal(published, bucket.intValue(), bid);
      Integer previous = buckets.get(bucket);
      if (total == 0) {
        if (previous != null) {
          buckets.remove(bucket);
          updates.add(new DepthUpdate(bid, bucket.intValue(), 0));
        }
      } else if (previous == null || previous.intValue() != total) {
        buckets.put(bucket, Integer.valueOf(total));
        updates.add(new DepthUpdate(bid, bucket.intValue(), total));
      }
    }
  }

  private int bucketTotal(TreeMap<Integer, Integer> nativeLevels, int bucket, boolean bid) {
    int[] range = bucketer.nativeRange(bucket, bid);
    long total = 0L;
    for (Integer size :
        nativeLevels.subMap(Integer.valueOf(range[0]), true, Integer.valueOf(range[1]), true).values()) {
      total += size.longValue();
    }
    return PriceBucketer.saturate(total);
  }

  private TreeMap<Integer, Integer> bucketize(TreeMap<Integer, Integer> nativeLevels, boolean bid) {
    TreeMap<Integer, Long> totals = new TreeMap<Integer, Long>();
    for (Map.Entry<Integer, Integer> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketer.bucket(bid, level.getKey().intValue()));
      Long total = totals.get(bucket);
      totals.put(
          bucket, Long.valueOf((total == null ? 0L : total.longValue()) + level.getValue().longValue()));
    }
    TreeMap<Integer, Integer> buckets = new TreeMap<Integer, Integer>();
    for (Map.Entry<Integer, Long> entry : totals.entrySet()) {
      buckets.put(entry.getKey(), Integer.valueOf(PriceBucketer.saturate(entry.getValue().longValue())));
    }
    return buckets;
  }
```

Replace `publishStaged`, `replacePublished`, `clearPublished`, `reset`, `copyStagedToPublished`:

```java
  public List<DepthUpdate> publishStaged() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    TreeMap<Integer, Integer> bidBuckets = bucketize(stagedBids, true);
    TreeMap<Integer, Integer> askBuckets = bucketize(stagedAsks, false);
    appendUpserts(updates, true, bidBuckets);
    appendUpserts(updates, false, askBuckets);
    copyStagedToPublished(bidBuckets, askBuckets);
    return Collections.unmodifiableList(updates);
  }

  public List<DepthUpdate> replacePublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    TreeMap<Integer, Integer> bidBuckets = bucketize(stagedBids, true);
    TreeMap<Integer, Integer> askBuckets = bucketize(stagedAsks, false);
    appendVanished(updates, true, publishedBidBuckets, bidBuckets);
    appendUpserts(updates, true, bidBuckets);
    appendVanished(updates, false, publishedAskBuckets, askBuckets);
    appendUpserts(updates, false, askBuckets);
    copyStagedToPublished(bidBuckets, askBuckets);
    return Collections.unmodifiableList(updates);
  }

  public List<DepthUpdate> clearPublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (Integer bucket : publishedBidBuckets.keySet()) {
      updates.add(new DepthUpdate(true, bucket.intValue(), 0));
    }
    for (Integer bucket : publishedAskBuckets.keySet()) {
      updates.add(new DepthUpdate(false, bucket.intValue(), 0));
    }
    publishedBids.clear();
    publishedAsks.clear();
    publishedBidBuckets.clear();
    publishedAskBuckets.clear();
    return Collections.unmodifiableList(updates);
  }

  public void reset() {
    beginGeneration();
    publishedBids.clear();
    publishedAsks.clear();
    publishedBidBuckets.clear();
    publishedAskBuckets.clear();
  }

  private void copyStagedToPublished(
      TreeMap<Integer, Integer> bidBuckets, TreeMap<Integer, Integer> askBuckets) {
    publishedBids.clear();
    publishedBids.putAll(stagedBids);
    publishedAsks.clear();
    publishedAsks.putAll(stagedAsks);
    publishedBidBuckets.clear();
    publishedBidBuckets.putAll(bidBuckets);
    publishedAskBuckets.clear();
    publishedAskBuckets.putAll(askBuckets);
  }
```

`appendUpserts` and `appendVanished` keep their bodies but take `SortedMap<Integer, Integer>` (a `TreeMap` is one). Keep the Javadoc comments on every public method. Add imports `java.util.TreeSet` and keep `java.util.Map`, `java.util.SortedMap`.

- [ ] **Step 4: Run test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBookTest'`
Expected: PASS, including the pre-existing identity tests (with ratio 1 each native level is its own bucket, so `liveDeltaUpdatesRemovesAndIgnoresUnknownRemovals` still gets exactly the same three updates).

- [ ] **Step 5: Run the session delta tests to check identity behavior end to end**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionDeltaBookTest'`
Expected: PASS

- [ ] **Step 6: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBook.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBookTest.java
git commit -m "feat: publish seed-then-delta books as tick bucket totals"
```

---

### Task 7: Session carries the chosen tick

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfile.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionApi.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java` (`subscribe`, `handleSubscribe`, `activateIfReady`, `convertTrade`)
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SessionSink.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/RecordingSessionSink.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfileTest.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java` (only `FakeSession` gains the new overload so the build compiles; Provider logic changes in Task 8)
- Create: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTickSizeTest.java`

**Interfaces:**
- Consumes: `TickSizePlan`, `PriceBucketer`, `OrderBookSnapshotDiff(instrument, bucketer)`, `DeltaOrderBook(instrument, bucketer)`.
- Produces:
  - `SourceProfile.nLevels()` → `Integer` (400 for Borsa, null otherwise); `SourceProfile.l2BookParameters()` is removed.
  - `HyperliquidSessionApi.subscribe(String symbol, String exchange, String type, BigDecimal tick)`; the three-argument overload stays and means "default tick".
  - `SubscriptionRecord(String alias, PerpetualInstrument instrument, SubscriptionPermit permit, long activationDeadlineMillis, SourceProfile.FeedMode feedMode, L2BookParameters l2BookParameters, PriceBucketer bucketer)` and `SubscriptionRecord.bucketer()`.
  - `SessionSink.onInstrumentAdded(PerpetualInstrument instrument, BigDecimal tick)`.

- [ ] **Step 1: Update `SourceProfileTest` expectations**

Replace the three `l2BookParameters` assertions:

- Hyperliquid test: `assertNull(profile.nLevels());`
- Borsa test: `assertEquals(Integer.valueOf(400), profile.nLevels());`
- Hyperdash test: rename to `hyperdashSendsBrowserHeadersWithoutFixedAggregation`, replace the three `l2BookParameters()` lines with `assertNull(profile.nLevels());`.

Remove the `L2BookParameters` import.

- [ ] **Step 2: Write the failing session test**

Create `HyperliquidSessionTickSizeTest`:

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
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Exercises tick selection through the real session: parameters on the wire and bucketed output. */
public class HyperliquidSessionTickSizeTest {

  @Test
  public void hyperliquidCoarseTickRequestsMatchingSigFigsAndPublishesBucketTotals() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.01"));
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":4"));
    assertFalse(l2Book, l2Book.contains("nLevels"));
    assertFalse(l2Book, l2Book.contains("mantissa"));

    fixture.receive(
        book(
            "HYPE",
            1L,
            levels(level("87.784", "2.36"), level("87.783", "1.32")),
            levels(level("87.785", "161.16"), level("87.786", "24.1"))));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:8778:368", "depth:HYPE:8779:18526"),
        fixture.sink.events());
    assertEquals(0, new BigDecimal("0.01").compareTo(fixture.sink.addedTicks().get(0)));

    fixture.sink.events().clear();
    fixture.receive(trade("HYPE", "B", "87.784", "1.5", 2L, 9L));
    fixture.drain();
    assertEquals(Arrays.asList("trade:HYPE:8778.4:150"), fixture.sink.events());
  }

  @Test
  public void defaultTickIsTheFinestQuotedQuantumWhenNoTickIsGiven() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribeDefault("HYPE");
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertFalse(l2Book, l2Book.contains("nSigFigs"));

    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:87784:100"), fixture.sink.events());
    assertEquals(0, new BigDecimal("0.001").compareTo(fixture.sink.addedTicks().get(0)));
  }

  @Test
  public void unsupportedTickFallsBackToTheDefaultWithADiagnostic() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.00005"));
    fixture.completeSends(2);
    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();

    assertTrue(fixture.sink.events().get(0).startsWith("diagnostic:"));
    assertEquals(0, new BigDecimal("0.001").compareTo(fixture.sink.addedTicks().get(0)));
  }

  @Test
  public void borsaAlwaysRequestsFourHundredLevelsAndAggregatesDeltasPerBucket() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.002"));
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":5"));
    assertTrue(l2Book, l2Book.contains("\"nLevels\":400"));
    assertTrue(l2Book, l2Book.contains("\"mantissa\":2"));

    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1"), level("87.785", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:43892:200"), fixture.sink.events());

    fixture.sink.events().clear();
    fixture.receive(book("HYPE", 2L, levels(level("87.785", "0")), levels()));
    fixture.drain();
    assertEquals(Arrays.asList("depth:HYPE:43892:100"), fixture.sink.events());
  }

  @Test
  public void hyperdashNoLongerForcesFiveSignificantFigures() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERDASH, null);
    fixture.subscribeDefault("HYPE");
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertFalse(l2Book, l2Book.contains("nSigFigs"));
    assertFalse(l2Book, l2Book.contains("nLevels"));
  }

  private static String ack(SubscriptionType type, String coin) {
    return "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
        + "\"subscription\":{\"type\":\""
        + type.wireName()
        + "\",\"coin\":\""
        + coin
        + "\"}}}";
  }

  private static String book(String coin, long time, String bids, String asks) {
    return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
        + coin
        + "\",\"time\":"
        + time
        + ",\"levels\":[["
        + bids
        + "],["
        + asks
        + "]]}}";
  }

  private static String trade(
      String coin, String side, String price, String size, long time, long tid) {
    return "{\"channel\":\"trades\",\"data\":[{\"coin\":\""
        + coin
        + "\",\"side\":\""
        + side
        + "\",\"px\":\""
        + price
        + "\",\"sz\":\""
        + size
        + "\",\"time\":"
        + time
        + ",\"tid\":"
        + tid
        + "}]}";
  }

  private static String levels(String... levels) {
    return String.join(",", levels);
  }

  private static String level(String price, String size) {
    return "{\"px\":\"" + price + "\",\"sz\":\"" + size + "\",\"n\":1}";
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static void noop() {
    // no-op
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionTickSizeTest::noop);
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
            HyperliquidSessionTickSizeTest::noop);

    /** Logs in with one HYPE instrument (szDecimals 2) whose markPx is the given string or absent. */
    Fixture(MarketDataSource source, String markPx) {
      session.login(SourceProfile.of(source, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(
          200, TestMetadata.wrap(TestMetadata.universe("HYPE"), new String[] {markPx}));
      drain();
      transport.openSocket();
      drain();
      sink.events().clear();
    }

    void subscribe(String symbol, BigDecimal tick) {
      session.subscribe(symbol, "", "PERPETUAL", tick);
      drain();
    }

    void subscribeDefault(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
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

    void receive(String frame) {
      session.onFrame(1L, frame);
    }

    void completeSends(int count) {
      for (int index = 0; index < count; index++) {
        transport.socket().succeedNextSend();
        drain();
      }
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
    private final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<Task>();
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

Expected numbers: HYPE has `priceDecimals=4`. Tick 0.01 → ratio 100: bids 87.784/87.783 → bucket 8778 with 2.36+1.32 = 3.68 → 368 units; asks 87.785/87.786 → ceil(8778.5) = ceil(8778.6) = 8779 with 161.16+24.1 = 185.26 → 18526. Trade 87.784 → 877840/100 = 8778.4, size 1.5 → 150. Default tick 0.001 → ratio 10 → 87.784 → 87784. Borsa tick 0.002 → ratio 20: bid bucket 43892 covers native units 877840..877859, so bids 87.784 and 87.785 both floor into it (200 units); removing 87.785 leaves 100.

Also add to `RecordingSessionSink`:

```java
  private final List<BigDecimal> addedTicks = new ArrayList<BigDecimal>();

  List<BigDecimal> addedTicks() {
    return addedTicks;
  }

  @Override
  public void onInstrumentAdded(PerpetualInstrument instrument, BigDecimal tick) {
    addedAliases.add(instrument.symbol());
    addedTicks.add(tick);
    events.add("instrument-added:" + instrument.symbol());
  }
```

(`import java.math.BigDecimal;`). The trade event string in `RecordingSessionSink.onTrade` is `"trade:" + alias + ":" + priceUnits + ":" + sizeUnits`, so `8778.4` prints as `8778.4`.

- [ ] **Step 3: Run the new test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionTickSizeTest'`
Expected: compilation error (`subscribe` with four arguments, `nLevels()`, `onInstrumentAdded(instrument, tick)` do not exist).

- [ ] **Step 4: Implement `SourceProfile.nLevels()`**

In `SourceProfile`: remove `HYPERDASH_PARAMETERS` and the `L2BookParameters` import; replace the field `l2BookParameters` with `private final Integer nLevels;`, the constructor parameter accordingly, and the three `of` branches with `Integer.valueOf(400)` for Borsa and `null` for the other two. Replace the accessor:

```java
  /** Returns the l2Book depth to request per side, or null when the source fixes it. */
  public Integer nLevels() {
    return nLevels;
  }
```

Add `static final int BORSA_LEVELS = 400;` and use it. Update the class-level and `of` Javadoc: Borsa serves up to 400 levels, Hyperdash accepts `nSigFigs` but rejects `nLevels`.

- [ ] **Step 5: Implement the session API and record changes**

`HyperliquidSessionApi`:

```java
  /** Starts market data for one perpetual at the default tick for its reference price. */
  void subscribe(String symbol, String exchange, String type);

  /**
   * Starts market data for one perpetual at the Bookmap tick chosen in the subscribe dialog. A null
   * or unsupported tick falls back to the default tick.
   */
  void subscribe(String symbol, String exchange, String type, BigDecimal tick);
```

`SessionSink`:

```java
  /** Publishes one active instrument at the tick its depth and trade units are expressed in. */
  void onInstrumentAdded(PerpetualInstrument instrument, BigDecimal tick);
```

`SubscriptionRecord`: add the constructor parameter `PriceBucketer bucketer` after `l2BookParameters`, store it in `private final PriceBucketer bucketer;`, build `diff = new OrderBookSnapshotDiff(instrument, bucketer);` and `deltaBook = ... ? new DeltaOrderBook(instrument, bucketer) : null;`, and add:

```java
  /** Returns the bucketer that maps native prices onto this alias's Bookmap tick. */
  public PriceBucketer bucketer() {
    return bucketer;
  }
```

`HyperliquidSession`:

```java
  /** Enqueues an asynchronous perpetual subscription command at the default tick. */
  @Override
  public void subscribe(final String symbol, final String exchange, final String type) {
    subscribe(symbol, exchange, type, null);
  }

  /** Enqueues an asynchronous perpetual subscription command at the chosen tick. */
  @Override
  public void subscribe(
      final String symbol, final String exchange, final String type, final BigDecimal tick) {
    final long activationDeadlineMillis = clock.getAsLong() + ACTIVATION_TIMEOUT_MILLIS;
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleSubscribe(symbol, exchange, type, tick, activationDeadlineMillis);
          }
        });
  }
```

In `handleSubscribe(String symbol, String exchange, String type, BigDecimal tick, long activationDeadlineMillis)`, after the instrument lookup and budget reservation, replace the record construction:

```java
    BigDecimal defaultTick =
        TickSizePlan.defaultTick(instrument.referencePrice(), instrument.priceDecimals());
    BigDecimal resolvedTick = defaultTick;
    if (tick != null) {
      if (TickSizePlan.isSupportedTick(tick, instrument.priceDecimals())) {
        resolvedTick = tick;
      } else {
        sink.onDiagnostic(
            "unsupported tick " + tick.toPlainString() + " for " + symbol + "; using default");
      }
    }
    L2BookParameters parameters =
        TickSizePlan.parametersFor(
            resolvedTick, instrument.referencePrice(), instrument.priceDecimals(), profile.nLevels());
    final SubscriptionRecord record =
        new SubscriptionRecord(
            symbol,
            instrument,
            decision.permit(),
            activationDeadlineMillis,
            profile.feedMode(),
            parameters,
            new PriceBucketer(instrument, resolvedTick));
```

In `activateIfReady`: `sink.onInstrumentAdded(record.instrument(), record.bucketer().tick());`.

In `convertTrade`: replace `record.instrument().toTradePriceUnits(trade.price())` with `record.bucketer().tradePriceUnits(trade.price())`.

Add imports for `BigDecimal`, `L2BookParameters`, `PriceBucketer`, `TickSizePlan`.

`ProviderTest.FakeSession`: add

```java
    @Override
    public void subscribe(String symbol, String exchange, String type, BigDecimal tick) {
      commandCount++;
      lastTick = tick;
    }
```

with `private BigDecimal lastTick;` and `import java.math.BigDecimal;`. `Provider.subscribe` itself still calls the three-argument method until Task 8.

`Provider.ProviderSessionSink.onInstrumentAdded(PerpetualInstrument instrument, BigDecimal tick)`: change the signature now and use `tick.doubleValue()` for `InstrumentInfo.pips` (this is the only Provider change in this task; the two `factory.sink.onInstrumentAdded(instrument)` calls in `ProviderTest` become `factory.sink.onInstrumentAdded(instrument, BigDecimal.valueOf(instrument.pips()))`).

- [ ] **Step 6: Run the session and profile tests**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.*' --tests 'com.bookmap.plugins.layer0.hyperliquid.SourceProfileTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderTest'`
Expected: PASS. If `borsaAlwaysRequests...` fails on the exact JSON, print the body and check field order is `type, coin, nSigFigs, nLevels, mantissa` (from `L2BookParameters.appendTo`); the assertions only use `contains`, so order does not matter.

- [ ] **Step 7: Run the full suite, format, commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
git add -A src/main/java src/test/java
git commit -m "feat: subscribe at the chosen tick with per-source l2Book depth"
```

---

### Task 8: Provider offers candidates and honors `SubscribeInfoCrypto.pips`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java` (`subscribe`, `pipsFor`)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`

**Interfaces:**
- Consumes: `TickSizePlan.candidates/defaultTick/isSupportedTick`, `HyperliquidSessionApi.subscribe(..., BigDecimal)`, `velox.api.layer1.data.SubscribeInfoCrypto` (`public final double pips`; constructor `(String symbol, String exchange, String type, double pips, double sizeMultiplier)`).
- Produces: Bookmap sees the candidate list in the Subscribe dialog and the chosen tick in `InstrumentInfo.pips`.

- [ ] **Step 1: Write the failing provider tests**

Add to `ProviderTest`:

```java
  @Test
  public void offersTickCandidatesDerivedFromTheReferencePrice() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785"))));

    DefaultAndList<Double> pips =
        provider.getSupportedFeatures().pipsFunction.apply(new SubscribeInfo("HYPE", "", "PERPETUAL"));

    assertEquals(Double.valueOf(0.001d), pips.valueDefault);
    assertEquals(Arrays.asList(0.001d, 0.002d, 0.005d, 0.01d, 0.1d, 1d), pips.valueOptions);
  }

  @Test
  public void passesTheChosenTickAndFallsBackForUnsupportedOrPlainSubscriptions() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    factory.sink.onKnownInstruments(
        Collections.singletonList(new PerpetualInstrument("HYPE", 2, new BigDecimal("87.785"))));

    provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.01d, 100d));
    assertEquals(0, new BigDecimal("0.01").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.00005d, 100d));
    assertEquals(0, new BigDecimal("0.001").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfo("HYPE", "", "PERPETUAL"));
    assertEquals(0, new BigDecimal("0.001").compareTo(factory.session.lastTick));

    provider.subscribe(new SubscribeInfoCrypto("UNKNOWN", "", "PERPETUAL", 0.01d, 100d));
    assertNull(factory.session.lastTick);
  }

  @Test
  public void instrumentInfoAndPriceFormattingUseTheChosenTick() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    RecordingInstrumentListener instruments = new RecordingInstrumentListener();
    provider.addListener(instruments);

    factory.sink.onInstrumentAdded(new PerpetualInstrument("HYPE", 2), new BigDecimal("0.01"));

    assertEquals(0.01d, instruments.instrument.pips, 0d);
    assertEquals("87.78", provider.formatPrice("HYPE", 87.78d));
  }
```

Imports: `java.math.BigDecimal`, `java.util.Arrays`, `velox.api.layer1.data.DefaultAndList`, `velox.api.layer1.data.SubscribeInfoCrypto`. `FakeSessionFactory` must expose its `FakeSession` as a field named `session` (add `final FakeSession session` if the factory currently creates it inline). `RecordingInstrumentListener.instrument` already exists.

- [ ] **Step 2: Run tests to verify they fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderTest'`
Expected: FAIL (`offersTickCandidates...` sees one option `1.0E-4`; `passesTheChosenTick...` sees `lastTick == null`).

- [ ] **Step 3: Implement the Provider changes**

```java
  @Override
  public void subscribe(SubscribeInfo subscribeInfo) {
    if (subscribeInfo == null) {
      return;
    }
    session.subscribe(
        subscribeInfo.symbol, subscribeInfo.exchange, subscribeInfo.type, tickFor(subscribeInfo));
  }

  /** Resolves the dialog's tick; unsupported values fall back to the instrument's default tick. */
  private BigDecimal tickFor(SubscribeInfo subscribeInfo) {
    PerpetualInstrument instrument = instrumentFor(subscribeInfo);
    if (instrument == null) {
      return null;
    }
    BigDecimal defaultTick =
        TickSizePlan.defaultTick(instrument.referencePrice(), instrument.priceDecimals());
    if (!(subscribeInfo instanceof SubscribeInfoCrypto)) {
      return defaultTick;
    }
    double pips = ((SubscribeInfoCrypto) subscribeInfo).pips;
    BigDecimal requested = Double.isFinite(pips) && pips > 0d ? BigDecimal.valueOf(pips) : null;
    if (requested != null && TickSizePlan.isSupportedTick(requested, instrument.priceDecimals())) {
      return requested;
    }
    Log.warn(
        "Unsupported tick size "
            + pips
            + " for "
            + subscribeInfo.symbol
            + "; using "
            + defaultTick.toPlainString());
    return defaultTick;
  }

  private DefaultAndList<Double> pipsFor(SubscribeInfo subscribeInfo) {
    PerpetualInstrument instrument = instrumentFor(subscribeInfo);
    if (instrument == null) {
      return new DefaultAndList<Double>(
          Double.valueOf(FALLBACK_PIPS), Collections.singletonList(Double.valueOf(FALLBACK_PIPS)));
    }
    List<Double> options = new ArrayList<Double>();
    for (BigDecimal tick :
        TickSizePlan.candidates(instrument.referencePrice(), instrument.priceDecimals())) {
      options.add(Double.valueOf(tick.doubleValue()));
    }
    return new DefaultAndList<Double>(options.get(0), Collections.unmodifiableList(options));
  }
```

Imports: `com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlan`, `velox.api.layer1.data.SubscribeInfoCrypto`. `Double.isFinite` exists since Java 8.

- [ ] **Step 4: Run provider tests to verify they pass**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderTest'`
Expected: PASS (the pre-existing `publishesExactAnnotationsAndMetadataFeatures` still sees a single `0.001` option for BTC(3) with no reference price).

- [ ] **Step 5: Add an end-to-end regression**

In `ProviderEndToEndTest`, add a `metadata(String[] symbols, String... markPxs)` helper: `return TestMetadata.wrap(TestMetadata.universe(symbols), markPxs);` and a fixture method `loginWithPricedMetadata(String symbol, String markPx)` that mirrors `loginWithMetadata` but calls `transport.completeMeta(200, metadata(new String[] {symbol}, markPx));`. Then:

```java
  @Test
  public void coarseTickAggregatesTheBookAndScalesTradesThroughBookmapListeners() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithPricedMetadata("HYPE", "87.785");
    fixture.provider.subscribe(new SubscribeInfoCrypto("HYPE", "", "PERPETUAL", 0.01d, 100d));
    fixture.drain();
    fixture.completeSends();
    assertTrue(
        fixture.transport.socket().successfulSendBodies().toString().contains("\"nSigFigs\":4"));
    fixture.ack("HYPE", "l2Book");
    fixture.ack("HYPE", "trades");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"HYPE\",\"time\":1,\"levels\":[["
            + "{\"px\":\"87.784\",\"sz\":\"2.36\"},{\"px\":\"87.783\",\"sz\":\"1.32\"}],["
            + "{\"px\":\"87.785\",\"sz\":\"161.16\"}]]}}");
    fixture.trade("HYPE", "B", "87.784", "1", 2L, 7L);
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login", "added:HYPE", "depth:HYPE:8778:368", "depth:HYPE:8779:16116", "trade:HYPE:8778"),
        fixture.trace);
    assertEquals(0.01d, fixture.instruments.lastInfo.pips, 0d);
  }
```

`RecordingData.onTrade` records `(int) price`, hence `trade:HYPE:8778`. Add `private InstrumentInfo lastInfo;` to `RecordingInstrument.onInstrumentAdded` (assign `lastInfo = info;`) and import `velox.api.layer1.data.SubscribeInfoCrypto`. If `ack(coin, type)` hardcodes another coin, generalize it (it already takes `coin`).

- [ ] **Step 6: Run the end-to-end test**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderEndToEndTest'`
Expected: PASS

- [ ] **Step 7: Format and commit**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java
git commit -m "feat: offer tick size candidates and honor the chosen tick in Bookmap"
```

---

### Task 9: README and full quality gate

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README**

In "Scope and behavior":

- Replace the Hyperdash sentence `Hyperdash is subscribed with nSigFigs=5, so its prices are aggregated to five significant figures and its book is coarser than Hyperliquid's.` with: `All three sources are subscribed with the price aggregation (nSigFigs / mantissa) that matches the Tick size chosen in Bookmap's Subscribe dialog; Borsa is additionally asked for nLevels=400, Hyperliquid ignores nLevels, and Hyperdash rejects it.`
- Replace the bullet starting `Each subscription uses one l2Book feed and one trades feed` with:

```markdown
- Each subscription uses one `l2Book` feed and one `trades` feed on a single WebSocket. Hyperliquid
  and Hyperdash snapshots carry at most 20 levels per side; Borsa serves up to 400 levels per side.
  Coarser tick sizes therefore cover a wider price range with the same number of levels. Trades
  received while disconnected can be missed.
- The Tick size dropdown lists the ticks Hyperliquid's own order book offers for the instrument's
  price magnitude (for HYPE near 88: 0.001, 0.002, 0.005, 0.01, 0.1, 1), derived from the mark
  price loaded at login via `metaAndAssetCtxs`. The default is the finest tick the exchange
  actually quotes at that magnitude. Books are kept on the lossless `10^-(6-szDecimals)` grid and
  aggregated on publish (bids round down, asks round up, sizes summed), so a stale tick from a saved
  workspace or a price that crosses a power of ten never corrupts the book; it only changes how many
  levels the server-side window covers. Re-login to refresh the candidates after a large move.
```

- Replace the bullet `pips = 10^-(6-szDecimals) is a lossless Bookmap display quantum ...` with: `- The native grid 10^-(6-szDecimals) is the lossless internal price unit; the Bookmap pips of a subscription is the tick chosen in the dialog, and trade prices are reported in units of that tick (fractions allowed).`

In "Operating limits", replace `The adapter uses per-side 20-level aggregate snapshots.` with `The adapter uses per-side aggregate books of 20 levels (Hyperliquid, Hyperdash) or 400 levels (Borsa) at the requested aggregation.`

- [ ] **Step 2: Run the full gate**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: BUILD SUCCESSFUL with zero checkstyle warnings and zero spotbugs findings. Fix any finding in the touched files (typical: missing Javadoc on a new nested class, `int` overflow warnings resolved by the `long` arithmetic already used).

- [ ] **Step 3: Build the JAR**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline clean build`
Expected: `build/libs/hyperliquid-adapter-1.0.0.jar` exists.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: describe tick size selection and per-source book depth"
```

---

### Task 10: Manual verification in Bookmap

**Files:** none (verification only). Do this in the real Bookmap install; it is the only place `SubscribeInfoCrypto` delivery from the dialog can be confirmed.

- [ ] **Step 1: Install the JAR**

```bash
cp build/libs/hyperliquid-adapter-1.0.0.jar "$HOME/.bookmap/API/Layer0ApiModules/hyperliquid-adapter-1.0.0.jar"
```

Restart Bookmap.

- [ ] **Step 2: Check the dialog**

Connect with the Hyperliquid source (Mainnet), open Subscribe for `HYPE`, and confirm the Tick size dropdown shows `0.001, 0.002, 0.005, 0.01, 0.1, 1` with `0.001` selected. Confirm `ETH` shows `0.1, 0.2, 0.5, 1, 10, 100` and `BTC` shows `1, 2, 5, 10, 100, 1000` (or the six-digit list if BTC trades above 100000).

- [ ] **Step 3: Check depth and aggregation**

Subscribe `HYPE` at `0.01`. Expect a heatmap whose visible book spans roughly 20 rows of 0.01 on each side (about ±0.2) for Hyperliquid, and no empty rows between quoted levels. Switch the source to Borsa, subscribe `HYPE` at `0.01`, and expect about ±4 (400 rows) of depth. With Hyperdash confirm the book still loads.

- [ ] **Step 4: Check the fallback path**

Subscribe `HYPE` with a plain saved workspace subscription from before this change (tick `0.0001`) if one exists; the instrument must load with a log line `Unsupported tick size ...` absent (0.0001 is on-grid, so it is accepted and simply shows sparse rows). Note the observation in the final report; no code change is expected.

- [ ] **Step 5: Record the result**

Add a short "Verified in Bookmap" note with the date and observed dropdown values to the final summary (not to the README).
