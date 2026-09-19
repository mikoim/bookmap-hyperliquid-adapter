# Trade Execution Grouping Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the fills of one Hyperliquid transaction to Bookmap as one execution, by setting `TradeInfo.isExecutionStart` on the first fill and `isExecutionEnd` on the last.

**Architecture:** The parser carries `WsTrade.hash` as a nullable `executionId` on `TradeEvent`; the session carries it on `PendingTrade`. Flags are computed by a stateless `TradeExecutions` helper over the list of trades that will really be published — after deduplication and after buffering — so every published execution is well-formed. `SessionSink.onTrade` gains the two flags and `Provider` passes them to `TradeInfo`.

**Tech Stack:** Java 8 source level, JUnit 4, Gson 2.4, Bookmap api-core 7.8.0.13, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-19-trade-execution-grouping-design.md`

## Global Constraints

- Work directly on `main`; commit after every task. End every commit message with exactly
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` (after a blank line). Do not substitute
  any other model name.
- Gradle only starts with the JDK passed explicitly, and dependencies are cached offline. The shell
  is fish, so use `env`:
  `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline <tasks>`
- Full gate before every commit: `... ./gradlew --offline spotlessApply check verifyJava8Bytecode`
  (Spotless, Checkstyle with zero warnings, SpotBugs at LOW confidence, Java 8 bytecode).
- Checkstyle requires Javadoc on every public and package-private type and on public methods, and
  forbids non-private fields in classes (`VisibilityModifier`) — test fixtures included.
- All mutable session state is touched only on the state lane.
- `hash` never affects whether a trade is accepted, deduplicated or buffered. The deduplication key
  stays `TradeKey(coin, time, tid)`.
- A single trade is published with `isExecutionStart == true` and `isExecutionEnd == true`, exactly
  as today. No existing test assertion may change, except call sites that must pass the two new
  `onTrade` arguments.
- "Same execution" rule, verbatim from the spec: two adjacent trades `a`, `b` belong to the same
  execution only if `a.executionId() != null`, `a.executionId().equals(b.executionId())` and
  `a.buyAggressor() == b.buyAggressor()`.
- `executionId` rule, verbatim: the `hash` element is a JSON string primitive, fully matches
  `0x[0-9a-fA-F]+`, and contains at least one character other than `0` after `0x`; otherwise
  `executionId` is `null`, the trade is still accepted and no diagnostic is emitted.
- Large-file rule: `HyperliquidSession.java` exceeds 1,000 lines. Edit it with targeted `Edit` calls
  only; never rewrite it whole. After each edit run `git diff --check` and read `git diff`.

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `src/main/java/.../model/TradeEvent.java` | Modify | Gains nullable `executionId` |
| `src/main/java/.../parse/HyperliquidMessageParser.java` | Modify | Reads `hash` into `executionId` |
| `src/main/java/.../session/SubscriptionRecord.java` | Modify | `PendingTrade` gains `executionId` |
| `src/main/java/.../session/TradeExecutions.java` | Create | The grouping rule: start/end flags for a list |
| `src/main/java/.../session/SessionSink.java` | Modify | `onTrade` gains the two flags |
| `src/main/java/.../Provider.java` | Modify | Passes the flags to `TradeInfo` |
| `src/main/java/.../session/HyperliquidSession.java` | Modify | Batches trades per frame, publishes through `TradeExecutions` |
| `src/test/java/.../parse/HyperliquidMessageParserTest.java` | Modify | `hash` cases |
| `src/test/java/.../model/SubscriptionKeyTest.java` | Modify | Constructor argument only |
| `src/test/java/.../session/TradeExecutionsTest.java` | Create | Grouping rule |
| `src/test/java/.../session/RecordingSessionSink.java` | Modify | Records the flags on `Trade` |
| `src/test/java/.../ProviderTest.java` | Modify | Flags reach `TradeInfo` |
| `src/test/java/.../session/HyperliquidSessionTradeExecutionTest.java` | Create | Session behavior |
| `src/test/java/.../ProviderEndToEndTest.java` | Modify | One end-to-end case |
| `README.md`, `docs/development.md` | Modify | User-visible behavior, package table |

`...` is `com/bookmap/plugins/layer0/hyperliquid` throughout.

---

### Task 1: The parser carries `hash` as `executionId`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/TradeEvent.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParser.java` (`parseTrade`)
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/SubscriptionKeyTest.java` (one constructor call)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParserTest.java`

**Interfaces:**
- Produces:
  `TradeEvent(String coin, long time, long tid, boolean isBuyAggressor, BigDecimal price, BigDecimal size, String executionId)`
  and `String TradeEvent.executionId()` (nullable). The 6-argument constructor is removed.

- [ ] **Step 1: Write the failing tests**

Add to `HyperliquidMessageParserTest` (it already has a `parser` field and imports `TradeEvent`,
`ParsedFrame`; add `assertNull` to the static imports if missing):

```java
  private static final String HASH =
      "0xad8e0566e813bdf98176040e6d51bd011100efa789e89430cdf17964235f55d8";

  @Test
  public void tradeHashBecomesTheExecutionId() {
    TradeEvent trade = onlyTrade(",\"hash\":\"" + HASH + "\"");

    assertEquals(HASH, trade.executionId());
  }

  @Test
  public void unusableTradeHashLeavesTheTradeWithoutAnExecutionId() {
    String zero = "0x0000000000000000000000000000000000000000000000000000000000000000";
    String[] hashFields = {
      "",
      ",\"hash\":null",
      ",\"hash\":12",
      ",\"hash\":\"\"",
      ",\"hash\":\"0x\"",
      ",\"hash\":\"" + zero + "\"",
      ",\"hash\":\"0xzz\"",
      ",\"hash\":\"ad8e\"",
      ",\"hash\":[\"" + HASH + "\"]"
    };
    for (String hashField : hashFields) {
      ParsedFrame frame = parser.parse(tradeFrame(hashField));

      assertEquals(hashField, ParsedFrame.Disposition.ACCEPTED, frame.disposition());
      assertTrue(hashField, frame.diagnostics().isEmpty());
      assertEquals(hashField, 1, frame.marketEvents().size());
      assertNull(hashField, ((TradeEvent) frame.marketEvents().get(0)).executionId());
    }
  }

  private TradeEvent onlyTrade(String hashField) {
    ParsedFrame frame = parser.parse(tradeFrame(hashField));
    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    return (TradeEvent) frame.marketEvents().get(0);
  }

  private static String tradeFrame(String hashField) {
    return "{\"channel\":\"trades\",\"data\":[{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"100\","
        + "\"sz\":\"1\",\"time\":1,\"tid\":1"
        + hashField
        + "}]}";
  }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidMessageParserTest'`
Expected: compilation failure, `cannot find symbol ... executionId()`.

- [ ] **Step 3: Extend `TradeEvent`**

Add the field, the constructor parameter (last), the assignment, and the accessor:

```java
  private final String executionId;
```

```java
  /**
   * Creates a trade event.
   *
   * @param executionId the transaction hash shared by the fills of one execution, or null when the
   *     exchange reported none
   */
  public TradeEvent(
      String coin,
      long time,
      long tid,
      boolean isBuyAggressor,
      BigDecimal price,
      BigDecimal size,
      String executionId) {
    this.coin = coin;
    this.time = time;
    this.tid = tid;
    this.isBuyAggressor = isBuyAggressor;
    this.price = price;
    this.size = size;
    this.executionId = executionId;
  }
```

```java
  /** Returns the identifier shared by the fills of one execution, or null when there is none. */
  public String executionId() {
    return executionId;
  }
```

`key()` stays as it is.

- [ ] **Step 4: Read `hash` in the parser**

In `HyperliquidMessageParser`, add the import `java.util.regex.Pattern`, the constant beside
`MAX_TID`, and the helper; then pass the result to the constructor in `parseTrade`.

```java
  private static final Pattern TRANSACTION_HASH = Pattern.compile("0x[0-9a-fA-F]+");
```

```java
    return new TradeEvent(coin, time, tid, buyAggressor, price, size, executionId(trade));
```

```java
  /**
   * Returns the transaction hash as the execution identifier. The field is optional and never
   * rejects a trade: a missing, malformed or all-zero hash means the fill stands alone. All-zero
   * hashes are real; the exchange sends them for fills no user transaction caused.
   */
  private String executionId(JsonObject trade) {
    JsonElement element = trade.get("hash");
    if (element == null
        || !element.isJsonPrimitive()
        || !element.getAsJsonPrimitive().isString()) {
      return null;
    }
    String hash = element.getAsString();
    if (!TRANSACTION_HASH.matcher(hash).matches()) {
      return null;
    }
    for (int index = 2; index < hash.length(); index++) {
      if (hash.charAt(index) != '0') {
        return hash;
      }
    }
    return null;
  }
```

- [ ] **Step 5: Fix the one other constructor call**

In `SubscriptionKeyTest.derivesTradeKeyFromTradeEvent`, add `, null` as the last constructor
argument. Nothing else in that test changes.

- [ ] **Step 6: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: carry the trade's transaction hash as an execution id"
```

---

### Task 2: `TradeExecutions` and `PendingTrade.executionId`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java` (nested `PendingTrade`)
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java` (`convertTrade` only)
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeExecutions.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeExecutionsTest.java`

**Interfaces:**
- Consumes: `TradeEvent.executionId()` (Task 1).
- Produces:
  `PendingTrade(TradeKey key, double priceUnits, int sizeUnits, boolean buyAggressor, String executionId)`,
  `String PendingTrade.executionId()`, and

```java
final class TradeExecutions {
  interface Output {
    void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd);
  }
  static void publish(List<PendingTrade> trades, Output output);
}
```

- [ ] **Step 1: Write the failing tests**

Create `TradeExecutionsTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;

import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;
import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Pins which adjacent trades form one execution and how its first and last trade are flagged. */
public class TradeExecutionsTest {

  @Test
  public void emptyListPublishesNothing() {
    assertEquals("[]", publish().toString());
  }

  @Test
  public void aSingleTradeIsAWholeExecution() {
    assertEquals("[1:TT]", publish(buy(1, "0xa")).toString());
    assertEquals("[1:TT]", publish(buy(1, null)).toString());
  }

  @Test
  public void adjacentTradesWithOneIdAndSideFormOneExecution() {
    assertEquals(
        "[1:TF, 2:FF, 3:FT]", publish(buy(1, "0xa"), buy(2, "0xa"), buy(3, "0xa")).toString());
  }

  @Test
  public void aChangeOfSideEndsTheExecution() {
    assertEquals("[1:TT, 2:TT]", publish(buy(1, "0xa"), sell(2, "0xa")).toString());
  }

  @Test
  public void aTradeWithoutAnIdStandsAloneAndSplitsItsNeighbours() {
    assertEquals(
        "[1:TT, 2:TT, 3:TT]", publish(buy(1, "0xa"), buy(2, null), buy(3, "0xa")).toString());
    assertEquals("[1:TT, 2:TT]", publish(buy(1, null), buy(2, null)).toString());
  }

  @Test
  public void onlyAdjacentTradesAreGrouped() {
    assertEquals(
        "[1:TT, 2:TT, 3:TT, 4:TT]",
        publish(buy(1, "0xa"), buy(2, "0xb"), buy(3, "0xa"), buy(4, "0xb")).toString());
  }

  @Test
  public void consecutiveExecutionsEachGetAStartAndAnEnd() {
    assertEquals(
        "[1:TF, 2:FT, 3:TF, 4:FT]",
        publish(buy(1, "0xa"), buy(2, "0xa"), buy(3, "0xb"), buy(4, "0xb")).toString());
  }

  private static List<String> publish(PendingTrade... trades) {
    final List<String> published = new ArrayList<String>();
    List<PendingTrade> input =
        trades.length == 0 ? Collections.<PendingTrade>emptyList() : Arrays.asList(trades);
    TradeExecutions.publish(
        input,
        new TradeExecutions.Output() {
          @Override
          public void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd) {
            String flags = (executionStart ? "T" : "F") + (executionEnd ? "T" : "F");
            published.add(trade.sizeUnits() + ":" + flags);
          }
        });
    return published;
  }

  /** The size doubles as the trade's label in the expectations above. */
  private static PendingTrade buy(int label, String executionId) {
    return new PendingTrade(new TradeKey("BTC", 1L, label), 100d, label, true, executionId);
  }

  private static PendingTrade sell(int label, String executionId) {
    return new PendingTrade(new TradeKey("BTC", 1L, label), 100d, label, false, executionId);
  }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TradeExecutionsTest'`
Expected: compilation failure, `cannot find symbol class TradeExecutions`.

- [ ] **Step 3: Extend `PendingTrade`**

In `SubscriptionRecord.PendingTrade` add the field, the constructor parameter (last) with its
assignment, and the accessor:

```java
    private final String executionId;
```

```java
    PendingTrade(
        TradeKey key, double priceUnits, int sizeUnits, boolean buyAggressor, String executionId) {
      this.key = key;
      this.priceUnits = priceUnits;
      this.sizeUnits = sizeUnits;
      this.buyAggressor = buyAggressor;
      this.executionId = executionId;
    }
```

```java
    /** Returns the identifier shared by the fills of one execution, or null when there is none. */
    String executionId() {
      return executionId;
    }
```

In `HyperliquidSession.convertTrade`, pass it through — the only `new PendingTrade(` in `main`:

```java
      return new PendingTrade(
          trade.key(),
          record.bucketer().tradePriceUnits(trade.price()),
          record.instrument().toSizeUnits(trade.size()),
          trade.isBuyAggressor(),
          trade.executionId());
```

- [ ] **Step 4: Write `TradeExecutions`**

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import java.util.List;

/**
 * Marks where executions begin and end in a run of trades that is about to be published.
 * Hyperliquid reports one trade per maker filled, so a single aggressing transaction arrives as several trades
 * sharing a hash; Bookmap draws them as one execution when the first carries the start flag and the
 * last the end flag. Flags are derived from the list actually published, never earlier, so a trade
 * dropped by deduplication or a full buffer cannot leave an execution without its start or its end.
 */
final class TradeExecutions {

  /** Receives each trade once, in order, with its flags. */
  interface Output {

    /** Publishes one trade. A trade that stands alone has both flags set. */
    void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd);
  }

  private TradeExecutions() {
    // static utility
  }

  /** Publishes the trades of one subscription in the order given. */
  static void publish(List<PendingTrade> trades, Output output) {
    int last = trades.size() - 1;
    for (int index = 0; index <= last; index++) {
      PendingTrade trade = trades.get(index);
      boolean start = index == 0 || !sameExecution(trades.get(index - 1), trade);
      boolean end = index == last || !sameExecution(trade, trades.get(index + 1));
      output.onTrade(trade, start, end);
    }
  }

  /** Only adjacent trades with one non-null id and one aggressor side share an execution. */
  private static boolean sameExecution(PendingTrade first, PendingTrade second) {
    return first.executionId() != null
        && first.executionId().equals(second.executionId())
        && first.buyAggressor() == second.buyAggressor();
  }
}
```

- [ ] **Step 5: Run the tests, the gate, and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TradeExecutionsTest'`
Expected: 7 tests pass.

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: add the rule that groups adjacent trades into executions"
```

---

### Task 3: `SessionSink.onTrade` carries the flags to `TradeInfo`

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SessionSink.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java` (`ProviderSessionSink.onTrade`)
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java` (`publishIfNew` call to `sink.onTrade` only)
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/RecordingSessionSink.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java`

**Interfaces:**
- Produces:
  `void SessionSink.onTrade(String alias, double priceUnits, int sizeUnits, boolean isBuyAggressor, boolean isExecutionStart, boolean isExecutionEnd)`;
  `RecordingSessionSink.Trade.executionStart()` / `executionEnd()` (package-private booleans).

After this task the session still publishes every trade as a whole execution (`true, true`); Task 4
makes it group them.

- [ ] **Step 1: Write the failing test**

In `ProviderTest`, the test that forwards sink events to listeners calls
`factory.sink.onTrade("SOL", 123d, 45, true);` and `factory.sink.onTrade("SOL", 124d, 46, false);`
and later asserts `data.trades.get(0).isBidAggressor`. Change the two calls and add four assertions
right after the existing `isBidAggressor` assertions:

```java
    factory.sink.onTrade("SOL", 123d, 45, true, true, false);
    factory.sink.onTrade("SOL", 124d, 46, false, false, true);
```

```java
    assertTrue(data.trades.get(0).isExecutionStart);
    assertFalse(data.trades.get(0).isExecutionEnd);
    assertFalse(data.trades.get(1).isExecutionStart);
    assertTrue(data.trades.get(1).isExecutionEnd);
```

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*ProviderTest'`
Expected: compilation failure, `method onTrade in interface SessionSink cannot be applied to given types`.

- [ ] **Step 3: Change the interface**

In `SessionSink`, replace the `onTrade` declaration and extend its Javadoc:

```java
  /**
   * Publishes one trade. {@code isExecutionStart} marks the first trade of an execution and {@code
   * isExecutionEnd} the last; a trade that stands alone has both set.
   */
  void onTrade(
      String alias,
      double priceUnits,
      int sizeUnits,
      boolean isBuyAggressor,
      boolean isExecutionStart,
      boolean isExecutionEnd);
```

(If the existing declaration has its own Javadoc line, replace it rather than stacking two.)

- [ ] **Step 4: Update the three implementers and callers**

`Provider.ProviderSessionSink.onTrade`:

```java
    @Override
    public void onTrade(
        String alias,
        double priceUnits,
        int sizeUnits,
        boolean isBuyAggressor,
        boolean isExecutionStart,
        boolean isExecutionEnd) {
      TradeInfo tradeInfo = new TradeInfo(false, isBuyAggressor, isExecutionStart, isExecutionEnd);
      for (Layer1ApiDataListener listener : dataListeners) {
        listener.onTrade(alias, priceUnits, sizeUnits, tradeInfo);
      }
    }
```

`HyperliquidSession.publishIfNew` — keep today's behavior for now:

```java
      sink.onTrade(
          record.alias(), trade.priceUnits(), trade.sizeUnits(), trade.buyAggressor(), true, true);
```

`RecordingSessionSink`: extend `Trade` with two `private final boolean` fields
(`executionStart`, `executionEnd`), two package-private accessors `executionStart()` and
`executionEnd()`, the two extra constructor parameters, and the new `onTrade` signature. The event
string stays exactly `"trade:" + alias + ":" + priceUnits + ":" + sizeUnits`:

```java
  @Override
  public void onTrade(
      String alias,
      double priceUnits,
      int sizeUnits,
      boolean isBuyAggressor,
      boolean isExecutionStart,
      boolean isExecutionEnd) {
    trades.add(
        new Trade(alias, priceUnits, sizeUnits, isBuyAggressor, isExecutionStart, isExecutionEnd));
    events.add("trade:" + alias + ":" + priceUnits + ":" + sizeUnits);
  }
```

`grep -rn "onTrade(" src` afterwards: the only `SessionSink.onTrade` implementers are `Provider` and
`RecordingSessionSink`; `ProviderTest` and `ProviderEndToEndTest` also implement Bookmap's
`Layer1ApiDataListener.onTrade(String, double, int, TradeInfo)`, which does not change.

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`; no existing assertion changed.

```bash
git add -A src
git commit -m "feat: pass execution start and end flags through the session sink"
```

---

### Task 4: The session publishes trades as executions

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
  (`handleMarketFrame`, `handleTrade` → `handleTrades`, `publishIfNew` → `publishTrades`, the two
  buffer drains in `activateIfReady` and `maybeRestore`)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTradeExecutionTest.java`

**Interfaces:**
- Consumes: `TradeExecutions.publish`, `PendingTrade.executionId()` (Task 2);
  `SessionSink.onTrade(..., boolean, boolean)` and `RecordingSessionSink.Trade.executionStart()/executionEnd()` (Task 3).
- Produces: no API change.

- [ ] **Step 1: Write the failing tests**

Create `HyperliquidSessionTradeExecutionTest.java`. The fixture is the same shape as
`HyperliquidSessionMetadataRefreshTest.Fixture` (queued executor, clock-driven scheduler, private
budget), reduced to what these cases need.

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Session behavior when several trades of one transaction are published as one execution. */
public class HyperliquidSessionTradeExecutionTest {

  private static final String A =
      "0x00000000000000000000000000000000000000000000000000000000000000aa";
  private static final String B =
      "0x00000000000000000000000000000000000000000000000000000000000000bb";
  private static final String ZERO =
      "0x0000000000000000000000000000000000000000000000000000000000000000";

  @Test
  public void fillsOfOneTransactionInOneFrameFormOneExecution() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, A), fill(2, A), fill(3, A));

    assertEquals("[TF, FF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsWithoutATransactionHashStayWholeExecutions() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, ZERO), fill(2, ZERO), fill(3, null));

    assertEquals("[TT, TT, TT]", fixture.flags().toString());
  }

  @Test
  public void twoTransactionsInOneFrameFormTwoExecutions() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();

    fixture.trades(fill(1, A), fill(2, A), fill(3, B), fill(4, B));

    assertEquals("[TF, FT, TF, FT]", fixture.flags().toString());
  }

  @Test
  public void aDuplicateFirstFillLeavesTheNextOneAsTheStart() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    fixture.trades(fill(1, A));

    fixture.trades(fill(1, A), fill(2, A), fill(3, A));

    assertEquals("[TT, TF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsBufferedBeforeActivationArePublishedAsOneExecution() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribeBtc();
    fixture.trades(fill(1, A));
    fixture.trades(fill(2, A));
    assertEquals("[]", fixture.flags().toString());

    fixture.bookAndAcks();

    assertEquals("[TF, FT]", fixture.flags().toString());
  }

  @Test
  public void fillsBufferedDuringRecoveryArePublishedAsOneExecution() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    fixture.reconnect();
    fixture.trades(fill(1, A));
    fixture.trades(fill(2, A));
    assertEquals("[]", fixture.flags().toString());

    fixture.completeAllSends();
    fixture.bookAndAcks();

    assertEquals("[TF, FT]", fixture.flags().toString());
  }

  @Test
  public void booksAndTradesKeepTheirArrivalOrder() {
    Fixture fixture = new Fixture();
    fixture.loginAndActivateBtc();
    int before = fixture.sink.events().size();

    fixture.book("101.000", 2L);
    fixture.trades(fill(1, A), fill(2, A));
    fixture.book("102.000", 3L);
    fixture.drain();

    List<String> kinds = new ArrayList<String>();
    for (String event : fixture.sink.events().subList(before, fixture.sink.events().size())) {
      if (event.startsWith("depth:") || event.startsWith("trade:")) {
        kinds.add(event.substring(0, 5));
      }
    }
    // Each new best bid replaces the previous one: one removal and one addition per book.
    assertEquals("[depth, depth, trade, trade, depth, depth]", kinds.toString());
  }

  /** One buy fill; {@code hash == null} omits the field. */
  private static String fill(long tid, String hash) {
    return "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"101.000\",\"sz\":\"1\",\"time\":5,\"tid\":"
        + tid
        + (hash == null ? "" : ",\"hash\":\"" + hash + "\"")
        + "}";
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

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final Clock clock = new Clock();
    private final Scheduler scheduler = new Scheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                // overflow is not exercised here
              }
            });
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final HyperliquidSession session =
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
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
            new MetadataRequestFactory() {
              @Override
              public MetadataRequest create(
                  SourceProfile profile, MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    dispatcher::submitControl,
                    profile.infoUri(),
                    callback);
              }
            },
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });
    private long generation = 1L;
    private long bookTime = 1L;

    private void login() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC")));
      drain();
      transport.openSocket();
      drain();
      completeAllSends();
    }

    private void loginAndActivateBtc() {
      login();
      subscribeBtc();
      bookAndAcks();
    }

    private void subscribeBtc() {
      session.subscribe("BTC", "", "PERPETUAL");
      drain();
      completeAllSends();
    }

    private void bookAndAcks() {
      book("100.000", bookTime++);
      ack(SubscriptionType.L2_BOOK);
      ack(SubscriptionType.TRADES);
      drain();
    }

    private void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation++;
      drain();
    }

    private void completeAllSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void trades(String... fills) {
      StringBuilder frame = new StringBuilder("{\"channel\":\"trades\",\"data\":[");
      for (int index = 0; index < fills.length; index++) {
        if (index > 0) {
          frame.append(',');
        }
        frame.append(fills[index]);
      }
      session.onFrame(generation, frame.append("]}").toString());
      drain();
    }

    private void book(String price, long time) {
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":"
              + time
              + ",\"levels\":[[{\"px\":\""
              + price
              + "\",\"sz\":\"1\"}],[]]}}");
    }

    private void ack(SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\"BTC\"}}}");
    }

    private List<String> flags() {
      List<String> flags = new ArrayList<String>();
      for (RecordingSessionSink.Trade trade : sink.trades()) {
        flags.add((trade.executionStart() ? "T" : "F") + (trade.executionEnd() ? "T" : "F"));
      }
      return flags;
    }

    private void drain() {
      executor.drain();
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  private static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class Scheduler implements CancellableScheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<Task>();

    private Scheduler(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis));
      tasks.add(scheduled);
      return scheduled;
    }

    private void advanceBy(long millis) {
      long target = clock.now + millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= target && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          clock.now = target;
          return;
        }
        tasks.remove(due);
        clock.now = Math.max(clock.now, due.due);
        due.task.run();
      }
    }

    private static final class Task implements Cancellable {
      private final Runnable task;
      private final long due;
      private boolean cancelled;

      private Task(Runnable task, long due) {
        this.task = task;
        this.due = due;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }
    }
  }
}
```

Notes for the implementer:
- In the recovery case the fills are delivered after the socket reopened but before the
  resubscriptions are acknowledged, so the session is `RECONNECTING` with `recovering == true` and
  buffers them; `completeAllSends()` then lets the connector report the subscribe frames as sent,
  and `bookAndAcks()` supplies the recovery book and both acknowledgements, which triggers
  `maybeRestore` and the drain. `reconnect()` deliberately does not complete sends itself.
- `booksAndTradesKeepTheirArrivalOrder` expects two depth events per book because a changed best bid
  is published as a removal of the old level and an addition of the new one. If the count differs,
  print `fixture.sink.events()` and adjust the expected list to what the book diff really emits —
  the assertion that matters is that the two `trade` events sit between the two groups of `depth`
  events, in that order.

- [ ] **Step 2: Run it and watch it fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionTradeExecutionTest'`
Expected: `fillsOfOneTransactionInOneFrameFormOneExecution`, `twoTransactionsInOneFrameFormTwoExecutions`,
`aDuplicateFirstFillLeavesTheNextOneAsTheStart`, and the two buffered cases fail with every flag
`TT` (for example `expected:<[T[F, FF, F]T]> but was:<[T[T, TT, T]T]>`).
`fillsWithoutATransactionHashStayWholeExecutions` and `booksAndTradesKeepTheirArrivalOrder` already
pass; they pin behavior that must not change.

- [ ] **Step 3: Batch the trades of a frame**

All edits are in `HyperliquidSession.java`, each a targeted `Edit`.

1. Replace the whole `handleMarketFrame` method — including its leading
   `if (closed || generation != currentGeneration || generationInvalidated) return;` check, which
   the new body performs before every book and, inside `handleTrades`, before every run of
   trades — so consecutive trades are handled together:

```java
  private void handleMarketFrame(long generation, List<MarketDataEvent> events) {
    List<TradeEvent> trades = new ArrayList<TradeEvent>();
    for (MarketDataEvent event : events) {
      if (event instanceof TradeEvent) {
        trades.add((TradeEvent) event);
        continue;
      }
      if (!handleTrades(generation, trades)) {
        return;
      }
      trades = new ArrayList<TradeEvent>();
      if (closed || generation != currentGeneration || generationInvalidated) {
        return;
      }
      if (event instanceof BookSnapshot) {
        handleBook((BookSnapshot) event);
      }
    }
    handleTrades(generation, trades);
  }
```

2. Replace `handleTrade(TradeEvent)` with `handleTrades`. The per-trade decisions are the existing
   ones, unchanged; only the live branch differs — it collects instead of publishing:

```java
  /**
   * Handles a run of trades from one frame. Live trades are collected per subscription and
   * published together, so that the fills of one transaction can be flagged as one execution.
   *
   * @return false when the generation is no longer current and the rest of the frame must be
   *     dropped
   */
  private boolean handleTrades(long generation, List<TradeEvent> trades) {
    if (trades.isEmpty()) {
      return true;
    }
    if (closed || generation != currentGeneration || generationInvalidated) {
      return false;
    }
    SubscriptionRecord publishing = null;
    List<PendingTrade> publishable = new ArrayList<PendingTrade>();
    for (TradeEvent trade : trades) {
      SubscriptionRecord record = records.get(trade.coin());
      if (record == null || record.state() == SubscriptionRecord.State.REMOVED) {
        sink.onDiagnostic("discarded trade for unsubscribed coin: " + trade.coin());
        continue;
      }
      PendingTrade converted = convertTrade(record, trade);
      if (converted == null) {
        continue;
      }
      boolean pendingBook = record.state() == SubscriptionRecord.State.PENDING_BOOK;
      if (pendingBook || (recovering && connectionState == ConnectionState.RECONNECTING)) {
        bufferTrade(record, converted, pendingBook ? "pending" : "recovery");
        continue;
      }
      if (!tradeDeduplicator.markIfNew(converted.key(), clock.getAsLong())) {
        continue;
      }
      if (publishing != record) {
        publishTrades(publishing, publishable);
        publishable = new ArrayList<PendingTrade>();
        publishing = record;
      }
      publishable.add(converted);
    }
    publishTrades(publishing, publishable);
    return true;
  }

  /** Buffers a trade that cannot be published yet; a full buffer is reported once per incident. */
  private void bufferTrade(SubscriptionRecord record, PendingTrade trade, String bufferName) {
    if (tradeDeduplicator.contains(trade.key(), clock.getAsLong())
        || record.containsPendingTradeKey(trade.key())) {
      return;
    }
    if (!record.addPendingTrade(trade) && !record.pendingTradeOverflowWarned()) {
      record.markPendingTradeOverflowWarned();
      dataHealth.tradeGap(record.alias(), currentGeneration, "trade buffer overflow");
      sink.onDiagnostic(bufferName + " trade buffer full for " + record.alias());
    }
  }
```

   `bufferTrade` replaces the two identical blocks in the old `handleTrade`; the diagnostic texts
   stay `"pending trade buffer full for "` and `"recovery trade buffer full for "`.

3. Replace `publishIfNew` with `publishTrades`:

```java
  /** Publishes trades that already passed deduplication, flagged as executions. */
  private void publishTrades(final SubscriptionRecord record, List<PendingTrade> trades) {
    if (record == null || trades.isEmpty()) {
      return;
    }
    TradeExecutions.publish(
        trades,
        new TradeExecutions.Output() {
          @Override
          public void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd) {
            sink.onTrade(
                record.alias(),
                trade.priceUnits(),
                trade.sizeUnits(),
                trade.buyAggressor(),
                executionStart,
                executionEnd);
            dataHealth.tradePublished(record.alias(), currentGeneration);
          }
        });
  }

  /** Publishes a drained buffer, dropping whatever was published in the meantime. */
  private void publishBuffered(SubscriptionRecord record, ArrayDeque<PendingTrade> buffered) {
    List<PendingTrade> fresh = new ArrayList<PendingTrade>(buffered.size());
    for (PendingTrade trade : buffered) {
      if (tradeDeduplicator.markIfNew(trade.key(), clock.getAsLong())) {
        fresh.add(trade);
      }
    }
    publishTrades(record, fresh);
  }
```

4. In `activateIfReady` and in `maybeRestore`, replace the drain loop

```java
    ArrayDeque<PendingTrade> pending = record.takePendingTrades();
    while (!pending.isEmpty()) {
      publishIfNew(record, pending.removeFirst());
    }
```

   with

```java
    publishBuffered(record, record.takePendingTrades());
```

   (two occurrences; indentation differs — match each site). Confirm afterwards that
   `grep -n "publishIfNew\|handleTrade(" HyperliquidSession.java` prints nothing.

- [ ] **Step 4: Run the new tests, then the whole suite**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionTradeExecutionTest'`
Expected: 7 tests pass.

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: all green. Existing trade tests publish single trades or trades without a `hash`, so they
still see whole executions. If an existing test fails, do not edit its assertion — diagnose the
ordering or deduplication difference and fix `handleTrades`.

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src
git commit -m "feat: publish the fills of one transaction as one execution"
```

---

### Task 5: End-to-end case and documentation

**Files:**
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`
- Modify: `README.md` (Scope section), `docs/development.md` (package table),
  `docs/superpowers/specs/2026-09-19-trade-execution-grouping-design.md` (status line)

**Interfaces:**
- Consumes: everything above, through `Provider`.

- [ ] **Step 1: Write the test**

`ProviderEndToEndTest`'s `RecordingData.onTrade(String alias, double price, int size, TradeInfo trade)`
records only `"trade:" + alias + ":" + (int) price`. Add a second list to `RecordingData` that keeps
the flags, without touching the existing `trades`/`trace` entries:

```java
    private final List<String> tradeFlags = new ArrayList<String>();
```

and, inside `onTrade`, after the existing lines:

```java
      tradeFlags.add((trade.isExecutionStart ? "T" : "F") + (trade.isExecutionEnd ? "T" : "F"));
```

Then add the case, using the fixture's existing helpers (`loginWithMetadata`, `subscribe`,
`completeSends`, `ack`, `book`, `drain`, `marketDataConnection()`, `transport`, `data`) the way the
first subscription case in the file does:

```java
  @Test
  public void fillsOfOneTransactionReachBookmapAsOneExecution() {
    Fixture fixture = new Fixture(budget(2, 20, 50, 2));
    fixture.loginWithMetadata("BTC");
    fixture.subscribe("BTC");
    fixture.completeSends();
    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();
    String hash = "0xad8e0566e813bdf98176040e6d51bd011100efa789e89430cdf17964235f55d8";

    fixture.transport.emitTextFromConnection(
        fixture.marketDataConnection(),
        "{\"channel\":\"trades\",\"data\":["
            + "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"101\",\"sz\":\"1\",\"time\":9,\"tid\":1,"
            + "\"hash\":\""
            + hash
            + "\"},"
            + "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"102\",\"sz\":\"1\",\"time\":9,\"tid\":2,"
            + "\"hash\":\""
            + hash
            + "\"}]}");
    fixture.drain();

    assertEquals("[TF, FT]", fixture.data.tradeFlags.toString());
  }
```

`marketDataConnection()` and `transport` are private members of the nested `Fixture`; the enclosing
test class may use them directly, as the existing `multiTradeJson` callers do.

- [ ] **Step 2: Run it, then prove it can fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*ProviderEndToEndTest'`
Expected: pass on the first run (Tasks 1–4 implement it; this guards the `Provider` wiring).
Mutation check: temporarily change `Provider.ProviderSessionSink.onTrade` back to
`new TradeInfo(false, isBuyAggressor)`, watch the new assertion fail with
`expected:<[T[F, F]T]> but was:<[T[T, T]T]>`, and restore the line. `git diff -- src/main` must be
empty before committing.

- [ ] **Step 3: Update `README.md`**

In the `## Scope` section, after the paragraph about the instrument list being fetched again, add:

```markdown
Trades keep the grouping the exchange gives them. Hyperliquid reports one trade per resting order
filled, so a single market order that sweeps five levels arrives as five trades sharing one
transaction hash; the adapter delivers them to Bookmap as one execution. Fills are grouped only when
they arrive together, carry the same hash and the same aggressor side. Two orders for the same
instrument and side sent in one transaction cannot be told apart and form one execution. Fills
without a transaction hash — the exchange sends an all-zero hash for some system-generated fills —
are delivered one by one, as before.
```

- [ ] **Step 4: Update `docs/development.md`**

In the package table, extend the `hyperliquid.session` row to mention execution grouping:

```markdown
| `hyperliquid.session` | Bridging to Bookmap session output: subscription state, grouping fills into executions, data-health monitoring, the periodic metadata refresh, and the extra mark-price connection a relay source needs |
```

- [ ] **Step 5: Run the gate and commit**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A src README.md docs/development.md
git commit -m "docs: describe how fills are grouped into executions"
```

- [ ] **Step 6: Mark the spec implemented**

Change the spec's status line to `- 状態: spec-review READY、人間の承認済み、実装済み` and commit:

```bash
git add docs/superpowers/specs/2026-09-19-trade-execution-grouping-design.md
git commit -m "docs: mark the trade execution grouping spec as implemented"
```

---

## Verification after the last task

- `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline clean build` succeeds.
- `grep -rn "new TradeInfo(" src/main` shows exactly one call, with four arguments.
- `grep -n "publishIfNew\|void handleTrade(" src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
  prints nothing.
- Not verifiable here, left for a Bookmap session (spec, "実装後に実機で確認すべき事項"): that trade
  dots and Time & Sales aggregate per execution.
