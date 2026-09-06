# Borsa / Hyperdash オーダーブックソース追加 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ログイン画面のドロップダウンで Hyperliquid / Borsa / Hyperdash を選び、選んだソースの WebSocket から l2Book と trades を購読して Bookmap に配信できるようにする。

**Architecture:** 新しい値オブジェクト `SourceProfile`(WS URI、メタデータ REST URI、ハンドシェイクヘッダー、l2Book 購読パラメータ、フィードモード)をログイン時に解決し、Provider → Session → Connector → Transport へ渡す。Hyperliquid/Hyperdash は既存の全量スナップショット経路(`OrderBookSnapshotDiff`)をそのまま使い、Borsa だけは新設の `DeltaOrderBook`(世代別ステージド板+発行済み板)でシード→差分を処理する。既存の世代・ACK・回復の状態機械は変更せず、板の処理だけをモードで分岐する。

**Tech Stack:** Java(`options.release = 8`。`var`、`List.of`、`record` は使用不可。ラムダは可)、Gson 2.4、Jetty 9.3.8 WebSocket client、Bookmap api-core 7.4.0.10、JUnit 4.13.2、Gradle 9.1(spotless/google-java-format、checkstyle、spotbugs)。

仕様書: `docs/superpowers/specs/2026-09-06-borsa-hyperdash-data-sources-design.md`

## Global Constraints

- Java 8 バイトコード互換(`build.gradle` の `options.release = 8`)。`java.util.Map.of` 等の Java 9+ API は使わない。
- 新しい外部依存を追加しない。
- 公開型には Javadoc 必須(checkstyle `MissingJavadocType`)。行長 100 桁以内。`import *` 禁止。
- spotbugs は `LOW` 閾値で失敗するため、可変コレクションをそのまま返さない(防御コピーか unmodifiable)。
- コミット前に `spotlessApply` で整形する。
- テスト実行コマンド(fish shell 前提。`JAVA_HOME` 未設定だと Gradle が起動しない):

  ```bash
  env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '<FQCN>'
  ```

  全体品質ゲート:

  ```bash
  env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check
  ```

- コミットメッセージは英語。末尾に次のトレーラーを付ける:

  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW
  ```

- 仕様の非目的(同時集約、自動フェイルオーバー、testnet 既定値変更、ソース側データ正確性検証)は実装しない。

## ファイル構成

| 種別 | パス | 責務 |
|---|---|---|
| 新規 | `src/main/java/.../hyperliquid/MarketDataSource.java` | ソース列挙体とドロップダウン値の解決(不明値は Hyperliquid) |
| 新規 | `src/main/java/.../hyperliquid/SourceProfile.java` | ソースごとの接続特性(URI、ヘッダー、l2Book パラメータ、フィードモード) |
| 新規 | `src/main/java/.../hyperliquid/model/L2BookParameters.java` | `nSigFigs`/`nLevels`/`mantissa` の任意パラメータ(null は省略) |
| 新規 | `src/main/java/.../hyperliquid/book/DeltaOrderBook.java` | Borsa 用のステージド板・発行済み板と差分適用・板置換 |
| 変更 | `model/SubscriptionKey.java` | l2Book 購読 JSON にパラメータを付与(等価性は coin+type のまま) |
| 変更 | `parse/HyperliquidMessageParser.java` | ACK の `nLevels` 許容、板 `sz` の 0 許容 |
| 変更 | `model/PerpetualInstrument.java` | `toDepthSizeUnits`(0 を受理) |
| 変更 | `book/OrderBookSnapshotDiff.java` | SNAPSHOT モードで `sz=0` レベルを捨てる |
| 変更 | `transport/HyperliquidTransport.java`, `JettyHyperliquidTransport.java` | `connect` にハンドシェイクヘッダーを追加 |
| 変更 | `HyperliquidConnector.java` | `start(SourceProfile)`。メタデータ URI と WS URI を分離 |
| 変更 | `session/HyperliquidSessionApi.java`, `session/HyperliquidSession.java` | `login(SourceProfile)`。板処理をモードで分岐 |
| 変更 | `session/SubscriptionRecord.java` | フィードモード、`DeltaOrderBook`、l2Book パラメータ付きキー |
| 変更 | `HyperliquidFieldManager.java`, `Provider.java` | ドロップダウン追加とログイン解決 |
| 変更 | `README.md` | ソース選択の説明 |
| テスト | 上記に対応する `src/test/java/...` の各テスト + `FakeHyperliquidTransport` | |

`...` は `com/bookmap/plugins/layer0/hyperliquid` の略。

## タスク間インターフェース早見表

```java
// Task 1
enum MarketDataSource { HYPERLIQUID, BORSA, HYPERDASH;
  String fieldValue(); static MarketDataSource fromFieldValue(String); static String[] fieldValues(); }
final class L2BookParameters { static final L2BookParameters NONE;
  L2BookParameters(Integer nSigFigs, Integer nLevels, Integer mantissa);
  Integer nSigFigs(); Integer nLevels(); Integer mantissa(); void appendTo(JsonObject subscription); }
final class SourceProfile { enum FeedMode { SNAPSHOT, SEED_THEN_DELTA }
  static SourceProfile of(MarketDataSource, HyperliquidEnvironment);
  MarketDataSource source(); HyperliquidEnvironment environment(); URI infoUri(); URI webSocketUri();
  Map<String,String> handshakeHeaders(); L2BookParameters l2BookParameters(); FeedMode feedMode(); }
// Task 2
SubscriptionKey(String coin, SubscriptionType type, L2BookParameters parameters)
// Task 3
HyperliquidTransport.connect(URI uri, Map<String,String> headers, long timeoutMillis, SocketCallback cb)
HyperliquidConnector.start(SourceProfile profile)
HyperliquidSessionApi.login(SourceProfile profile)
FakeHyperliquidTransport.connectHeaders() -> List<Map<String,String>>
// Task 5/6
PerpetualInstrument.toDepthSizeUnits(BigDecimal) // 0 -> 0
DeltaOrderBook(PerpetualInstrument); boolean seeded(); void beginGeneration();
  Result applySeed(BookSnapshot); Result applyDelta(BookSnapshot, boolean live);
  List<DepthUpdate> publishStaged(); List<DepthUpdate> replacePublished(); List<DepthUpdate> clearPublished(); void reset();
// Task 7
SubscriptionRecord(String alias, PerpetualInstrument, SubscriptionPermit, long deadline,
                   SourceProfile.FeedMode feedMode, L2BookParameters l2BookParameters)
  FeedMode feedMode(); DeltaOrderBook deltaBook(); boolean hasActivationBook(); List<DepthUpdate> clearPublishedBook();
```

---

### Task 1: ソースモデル(`MarketDataSource`、`L2BookParameters`、`SourceProfile`)

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/MarketDataSource.java`
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParameters.java`
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfile.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfileTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParametersTest.java`

**Interfaces:**
- Consumes: 既存 `HyperliquidEnvironment`(`infoUri()`, `webSocketUri()`)
- Produces: 早見表の Task 1 シグネチャ。以降の全タスクが利用する。

- [ ] **Step 0: 未コミットの仕様書修正をコミットする**

```bash
git add docs/superpowers/specs/2026-09-06-borsa-hyperdash-data-sources-design.md docs/superpowers/plans/2026-09-06-borsa-hyperdash-data-sources.md
git commit -m "docs: resolve second spec review and add implementation plan for orderbook sources

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfileTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import org.junit.Test;

/** Pins the per-source connection characteristics resolved at login. */
public class SourceProfileTest {

  @Test
  public void hyperliquidFollowsTheSelectedEnvironmentWithoutExtras() {
    SourceProfile profile = SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.TESTNET);

    assertEquals(MarketDataSource.HYPERLIQUID, profile.source());
    assertEquals(HyperliquidEnvironment.TESTNET, profile.environment());
    assertEquals("https://api.hyperliquid-testnet.xyz/info", profile.infoUri().toString());
    assertEquals("wss://api.hyperliquid-testnet.xyz/ws", profile.webSocketUri().toString());
    assertTrue(profile.handshakeHeaders().isEmpty());
    assertEquals(L2BookParameters.NONE, profile.l2BookParameters());
    assertEquals(SourceProfile.FeedMode.SNAPSHOT, profile.feedMode());
  }

  @Test
  public void borsaAlwaysUsesMainnetMetadataAndSeedThenDeltaBooks() {
    SourceProfile profile = SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.TESTNET);

    assertEquals(HyperliquidEnvironment.MAINNET, profile.environment());
    assertEquals("https://api.hyperliquid.xyz/info", profile.infoUri().toString());
    assertEquals("wss://ws.borsa.cc/", profile.webSocketUri().toString());
    assertTrue(profile.handshakeHeaders().isEmpty());
    assertEquals(L2BookParameters.NONE, profile.l2BookParameters());
    assertEquals(SourceProfile.FeedMode.SEED_THEN_DELTA, profile.feedMode());
  }

  @Test
  public void hyperdashSendsBrowserHeadersAndFiveSignificantFigures() {
    SourceProfile profile = SourceProfile.of(MarketDataSource.HYPERDASH, HyperliquidEnvironment.TESTNET);

    assertEquals(HyperliquidEnvironment.MAINNET, profile.environment());
    assertEquals("https://api.hyperliquid.xyz/info", profile.infoUri().toString());
    assertEquals("wss://api.hyperdash.com/ws/orderbook", profile.webSocketUri().toString());
    assertEquals("https://hyperdash.com", profile.handshakeHeaders().get("Origin"));
    assertTrue(profile.handshakeHeaders().get("User-Agent").startsWith("Mozilla/5.0"));
    assertEquals(2, profile.handshakeHeaders().size());
    assertEquals(Integer.valueOf(5), profile.l2BookParameters().nSigFigs());
    assertNull(profile.l2BookParameters().nLevels());
    assertNull(profile.l2BookParameters().mantissa());
    assertEquals(SourceProfile.FeedMode.SNAPSHOT, profile.feedMode());
  }

  @Test
  public void dropdownValuesResolveCaseInsensitivelyAndFallBackToHyperliquid() {
    assertEquals(MarketDataSource.BORSA, MarketDataSource.fromFieldValue("borsa"));
    assertEquals(MarketDataSource.HYPERDASH, MarketDataSource.fromFieldValue("Hyperdash"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue("Hyperliquid"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue("unknown"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue(null));
    assertEquals("Hyperliquid", MarketDataSource.fieldValues()[0]);
    assertEquals(3, MarketDataSource.fieldValues().length);
  }
}
```

`src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParametersTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.gson.JsonObject;
import org.junit.Test;

/** Tests optional l2Book subscription parameters. */
public class L2BookParametersTest {

  @Test
  public void appendsOnlyNonNullParametersInWireOrder() {
    JsonObject subscription = new JsonObject();
    new L2BookParameters(Integer.valueOf(5), null, Integer.valueOf(2)).appendTo(subscription);

    assertEquals("{\"nSigFigs\":5,\"mantissa\":2}", subscription.toString());
  }

  @Test
  public void noneAppendsNothingAndValuesAreComparable() {
    JsonObject subscription = new JsonObject();
    L2BookParameters.NONE.appendTo(subscription);

    assertEquals("{}", subscription.toString());
    assertEquals(new L2BookParameters(null, null, null), L2BookParameters.NONE);
    assertNotEquals(new L2BookParameters(Integer.valueOf(5), null, null), L2BookParameters.NONE);
  }
}
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.SourceProfileTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.model.L2BookParametersTest'`
Expected: コンパイルエラー(`MarketDataSource`、`SourceProfile`、`L2BookParameters` が存在しない)

- [ ] **Step 3: 実装する**

`src/main/java/com/bookmap/plugins/layer0/hyperliquid/MarketDataSource.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid;

/** Selectable order-book and trade sources that speak the Hyperliquid WebSocket protocol. */
public enum MarketDataSource {
  HYPERLIQUID("Hyperliquid"),
  BORSA("Borsa"),
  HYPERDASH("Hyperdash");

  private final String fieldValue;

  MarketDataSource(String fieldValue) {
    this.fieldValue = fieldValue;
  }

  /** Returns the value shown in, and serialized by, the login dropdown. */
  public String fieldValue() {
    return fieldValue;
  }

  /** Resolves a dropdown value; null or unknown values fall back to Hyperliquid. */
  public static MarketDataSource fromFieldValue(String value) {
    for (MarketDataSource candidate : values()) {
      if (candidate.fieldValue.equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return HYPERLIQUID;
  }

  /** Returns the dropdown values in display order, Hyperliquid first. */
  public static String[] fieldValues() {
    MarketDataSource[] sources = values();
    String[] fieldValues = new String[sources.length];
    for (int index = 0; index < sources.length; index++) {
      fieldValues[index] = sources[index].fieldValue;
    }
    return fieldValues;
  }
}
```

`src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParameters.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Optional Hyperliquid l2Book subscription parameters; a null parameter is omitted on the wire. */
public final class L2BookParameters {

  /** No optional parameters: the minimal Hyperliquid subscription. */
  public static final L2BookParameters NONE = new L2BookParameters(null, null, null);

  private final Integer nSigFigs;
  private final Integer nLevels;
  private final Integer mantissa;

  /** Creates parameters; each may be null to omit it from the subscription JSON. */
  public L2BookParameters(Integer nSigFigs, Integer nLevels, Integer mantissa) {
    this.nSigFigs = nSigFigs;
    this.nLevels = nLevels;
    this.mantissa = mantissa;
  }

  /** Returns the significant-figure aggregation, or null when omitted. */
  public Integer nSigFigs() {
    return nSigFigs;
  }

  /** Returns the requested depth per side, or null when omitted. */
  public Integer nLevels() {
    return nLevels;
  }

  /** Returns the mantissa aggregation, or null when omitted. */
  public Integer mantissa() {
    return mantissa;
  }

  /** Adds every non-null parameter to a subscription object. */
  public void appendTo(JsonObject subscription) {
    if (nSigFigs != null) {
      subscription.addProperty("nSigFigs", nSigFigs);
    }
    if (nLevels != null) {
      subscription.addProperty("nLevels", nLevels);
    }
    if (mantissa != null) {
      subscription.addProperty("mantissa", mantissa);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof L2BookParameters)) {
      return false;
    }
    L2BookParameters that = (L2BookParameters) other;
    return Objects.equals(nSigFigs, that.nSigFigs)
        && Objects.equals(nLevels, that.nLevels)
        && Objects.equals(mantissa, that.mantissa);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nSigFigs, nLevels, mantissa);
  }
}
```

`src/main/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfile.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable connection characteristics of one selected market-data source. */
public final class SourceProfile {

  /** How a source delivers l2Book frames. */
  public enum FeedMode {
    /** Every frame is a complete snapshot. */
    SNAPSHOT,
    /** The first frame per generation is a complete seed; later frames carry changed levels. */
    SEED_THEN_DELTA
  }

  static final URI BORSA_WEB_SOCKET_URI = URI.create("wss://ws.borsa.cc/");
  static final URI HYPERDASH_WEB_SOCKET_URI = URI.create("wss://api.hyperdash.com/ws/orderbook");
  static final String HYPERDASH_ORIGIN = "https://hyperdash.com";
  static final String BROWSER_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/128.0.0.0 Safari/537.36";
  private static final L2BookParameters HYPERDASH_PARAMETERS =
      new L2BookParameters(Integer.valueOf(5), null, null);

  private final MarketDataSource source;
  private final HyperliquidEnvironment environment;
  private final URI webSocketUri;
  private final Map<String, String> handshakeHeaders;
  private final L2BookParameters l2BookParameters;
  private final FeedMode feedMode;

  private SourceProfile(
      MarketDataSource source,
      HyperliquidEnvironment environment,
      URI webSocketUri,
      Map<String, String> handshakeHeaders,
      L2BookParameters l2BookParameters,
      FeedMode feedMode) {
    this.source = source;
    this.environment = environment;
    this.webSocketUri = webSocketUri;
    this.handshakeHeaders =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(handshakeHeaders));
    this.l2BookParameters = l2BookParameters;
    this.feedMode = feedMode;
  }

  /**
   * Resolves the profile for a source. Hyperliquid follows the selected environment; Borsa and
   * Hyperdash relay the Mainnet book, so their metadata always comes from Mainnet.
   */
  public static SourceProfile of(MarketDataSource source, HyperliquidEnvironment environment) {
    if (source == null || environment == null) {
      throw new IllegalArgumentException("source and environment must not be null");
    }
    Map<String, String> noHeaders = Collections.emptyMap();
    switch (source) {
      case BORSA:
        return new SourceProfile(
            source,
            HyperliquidEnvironment.MAINNET,
            BORSA_WEB_SOCKET_URI,
            noHeaders,
            L2BookParameters.NONE,
            FeedMode.SEED_THEN_DELTA);
      case HYPERDASH:
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Origin", HYPERDASH_ORIGIN);
        headers.put("User-Agent", BROWSER_USER_AGENT);
        return new SourceProfile(
            source,
            HyperliquidEnvironment.MAINNET,
            HYPERDASH_WEB_SOCKET_URI,
            headers,
            HYPERDASH_PARAMETERS,
            FeedMode.SNAPSHOT);
      default:
        return new SourceProfile(
            source,
            environment,
            environment.webSocketUri(),
            noHeaders,
            L2BookParameters.NONE,
            FeedMode.SNAPSHOT);
    }
  }

  /** Returns the selected source. */
  public MarketDataSource source() {
    return source;
  }

  /** Returns the Hyperliquid environment whose REST metadata describes the instruments. */
  public HyperliquidEnvironment environment() {
    return environment;
  }

  /** Returns the REST metadata endpoint. */
  public URI infoUri() {
    return environment.infoUri();
  }

  /** Returns the market-data WebSocket endpoint. */
  public URI webSocketUri() {
    return webSocketUri;
  }

  /** Returns the extra WebSocket handshake headers; empty when none are required. */
  public Map<String, String> handshakeHeaders() {
    return handshakeHeaders;
  }

  /** Returns the l2Book subscription parameters for this source. */
  public L2BookParameters l2BookParameters() {
    return l2BookParameters;
  }

  /** Returns how this source delivers l2Book frames. */
  public FeedMode feedMode() {
    return feedMode;
  }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests 'com.bookmap.plugins.layer0.hyperliquid.SourceProfileTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.model.L2BookParametersTest'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/MarketDataSource.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfile.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParameters.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/SourceProfileTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/L2BookParametersTest.java
git commit -m "feat: add market data source profiles for Borsa and Hyperdash

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 2: 購読 JSON パラメータとパーサー緩和

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/SubscriptionKey.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParser.java:122-137`(`parseBookSide`)、`:216-221`(`isAllowedSubscriptionField`)、`:243-255`(`requiredPositiveDecimalString` の隣)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/SubscriptionKeyTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParserTest.java`

**Interfaces:**
- Consumes: `L2BookParameters`(Task 1)
- Produces: `SubscriptionKey(String coin, SubscriptionType type, L2BookParameters parameters)`。既存 2 引数コンストラクタは `NONE` で委譲。`equals`/`hashCode`/`compareTo` は coin+type のまま。

- [ ] **Step 1: 失敗するテストを書く**

`SubscriptionKeyTest` に追加:

```java
  /** Optional parameters are emitted for l2Book only, and never affect key identity. */
  @Test
  public void appendsL2BookParametersWithoutChangingIdentity() {
    L2BookParameters parameters = new L2BookParameters(Integer.valueOf(5), null, null);
    SubscriptionKey key = new SubscriptionKey("BTC", SubscriptionType.L2_BOOK, parameters);

    JsonObject actual = new JsonParser().parse(key.subscribeJson()).getAsJsonObject();
    JsonObject expected =
        new JsonParser()
            .parse(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\",\"nSigFigs\":5}}")
            .getAsJsonObject();
    assertEquals(expected, actual);
    assertEquals(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), key);
    assertEquals(new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).hashCode(), key.hashCode());
    assertEquals(0, new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).compareTo(key));
  }

  /** Trades subscriptions ignore l2Book parameters entirely. */
  @Test
  public void tradesIgnoreL2BookParameters() {
    SubscriptionKey key =
        new SubscriptionKey(
            "BTC", SubscriptionType.TRADES, new L2BookParameters(Integer.valueOf(5), null, null));

    assertEquals(
        new SubscriptionKey("BTC", SubscriptionType.TRADES).subscribeJson(), key.subscribeJson());
  }
```

必要 import: `com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters` は同一パッケージなので不要。

`HyperliquidMessageParserTest` に追加(既存 `l2BookWith` / `assertInvalid` ヘルパーを使う。`parser` フィールドは既存):

```java
  /** Borsa deltas carry zero-size removals; the parser must pass them through. */
  @Test
  public void acceptsZeroSizeBookLevelsButRejectsNegativeSizes() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":42,\"levels\":["
                + "[{\"px\":\"99\",\"sz\":\"0\"}],[{\"px\":\"101\",\"sz\":\"1\"}]]}}");

    assertTrue(frame.diagnostics().isEmpty());
    BookSnapshot snapshot = (BookSnapshot) frame.marketEvents().get(0);
    assertEquals(0, snapshot.bids().get(0).size().signum());
    assertInvalid(
        l2BookWith(
            "\"coin\":\"BTC\",\"time\":1,\"levels\":[[{\"px\":\"99\",\"sz\":\"-1\"}],[]]"));
  }

  /** Borsa and Hyperdash echo nLevels in the subscription acknowledgement. */
  @Test
  public void acceptsNLevelsInL2BookSubscriptionAck() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"subscriptionResponse\",\"data\":{"
                + "\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\","
                + "\"coin\":\"BTC\",\"nSigFigs\":5,\"nLevels\":20}}}");

    assertEquals(1, frame.controlEvents().size());
    assertEquals(
        new SubscriptionKey("BTC", SubscriptionType.L2_BOOK), frame.controlEvents().get(0).target());
  }
```

このテストが使う `ParsedFrame`、`BookSnapshot`、`SubscriptionKey`、`SubscriptionType` の import と `assertTrue` は `HyperliquidMessageParserTest` に既に存在する(追加不要)。`frame.diagnostics()` / `frame.marketEvents()` / `frame.controlEvents()` / `ControlEvent.target()` は既存 API。

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKeyTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParserTest'`
Expected: `SubscriptionKeyTest` はコンパイルエラー(3 引数コンストラクタなし)。

- [ ] **Step 3: 実装する**

`SubscriptionKey.java`:

```java
  private final String coin;
  private final SubscriptionType type;
  private final L2BookParameters parameters;

  /** Creates a subscription definition without optional parameters. */
  public SubscriptionKey(String coin, SubscriptionType type) {
    this(coin, type, L2BookParameters.NONE);
  }

  /** Creates a subscription definition; parameters are only emitted for l2Book subscriptions. */
  public SubscriptionKey(String coin, SubscriptionType type, L2BookParameters parameters) {
    if (coin == null || coin.isEmpty()) {
      throw new IllegalArgumentException("coin must be non-empty");
    }
    if (parameters == null) {
      throw new IllegalArgumentException("parameters must not be null");
    }
    this.coin = coin;
    this.type = type;
    this.parameters = parameters;
  }
```

`json(String method)` 内、`subscription.addProperty("coin", coin);` の直後に追加:

```java
    if (type == SubscriptionType.L2_BOOK) {
      parameters.appendTo(subscription);
    }
```

`equals`/`hashCode`/`compareTo` は変更しない。

`HyperliquidMessageParser.java`:

`parseBookSide` の `requiredPositiveDecimalString(object, "sz")` を `requiredNonNegativeDecimalString(object, "sz")` に変更し、`requiredPositiveDecimalString` の直後にメソッドを追加:

```java
  private BigDecimal requiredNonNegativeDecimalString(JsonObject object, String field)
      throws ProtocolException {
    String raw = requiredString(object, field);
    try {
      BigDecimal value = new BigDecimal(raw);
      if (value.signum() < 0) {
        throw new ProtocolException(field + " must be non-negative");
      }
      return value;
    } catch (NumberFormatException failure) {
      throw new ProtocolException(field + " must be a decimal string", failure);
    }
  }
```

`isAllowedSubscriptionField`:

```java
    return "l2Book".equals(type)
        && ("nSigFigs".equals(field)
            || "nLevels".equals(field)
            || "mantissa".equals(field)
            || "fast".equals(field));
```

trades 側の `parseTrade` は `requiredPositiveDecimalString` のまま(sz=0 の trade は引き続き拒否。既存テスト 83 行目が担保)。

- [ ] **Step 4: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKeyTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParserTest'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/SubscriptionKey.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParser.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/SubscriptionKeyTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/parse/HyperliquidMessageParserTest.java
git commit -m "feat: support l2Book subscription parameters and zero-size book levels

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 3: プロファイル配線(Transport ヘッダー → Connector → Session)

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport/HyperliquidTransport.java:16`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/transport/JettyHyperliquidTransport.java:66-89`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java:77`(`environment` フィールド)、`:139-147`(`start`)、`:270-300`(`startOnStateLane`)、`:330`、`:366-367`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionApi.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java:114-123`(`login`)、`:334-339`(`handleLogin`)
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java:84-86`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/FakeHyperliquidTransport.java`
- Modify: 既存テスト `HyperliquidConnectorTest`、`HyperliquidSessionSubscriptionTest`、`HyperliquidSessionLifecycleTest`、`ProviderEndToEndTest`、`ProviderTest`(シグネチャ追随)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/transport/JettyHyperliquidTransportContractTest.java`

**Interfaces:**
- Consumes: `SourceProfile`(Task 1)
- Produces:
  - `HyperliquidTransport.Cancellable connect(URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback)`
  - `static ClientUpgradeRequest JettyHyperliquidTransport.upgradeRequest(Map<String, String> headers)`(package-private)
  - `HyperliquidConnector.start(SourceProfile profile)`
  - `HyperliquidSessionApi.login(SourceProfile profile)`
  - `FakeHyperliquidTransport.connectHeaders()` → `List<Map<String, String>>`
  - `HyperliquidSession` はプロファイルを `private SourceProfile profile` に保持する(Task 7 が `feedMode()`/`l2BookParameters()` を読む)。

- [ ] **Step 1: 失敗するテストを書く**

`HyperliquidConnectorTest` に追加(既存 `Fixture`、`validMeta` ヘルパーを使う):

```java
  /** Hyperdash relays the Mainnet book, so metadata stays on Mainnet even with testnet selected. */
  @Test
  public void hyperdashStartUsesMainnetMetadataAndSendsHandshakeHeaders() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERDASH, HyperliquidEnvironment.TESTNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals("https://api.hyperliquid.xyz/info", fixture.transport.httpUri().toString());
    assertEquals(
        "wss://api.hyperdash.com/ws/orderbook",
        fixture.transport.connectCalls().get(0).toString());
    assertEquals(
        "https://hyperdash.com", fixture.transport.connectHeaders().get(0).get("Origin"));
    assertTrue(fixture.transport.connectHeaders().get(0).get("User-Agent").startsWith("Mozilla"));
  }

  /** Borsa needs no handshake headers. */
  @Test
  public void borsaStartConnectsWithoutHandshakeHeaders() {
    Fixture fixture = new Fixture();

    fixture.connector.start(SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals("wss://ws.borsa.cc/", fixture.transport.connectCalls().get(0).toString());
    assertTrue(fixture.transport.connectHeaders().get(0).isEmpty());
  }
```

`JettyHyperliquidTransportContractTest` に追加:

```java
  /** Pins that profile headers reach the Jetty upgrade request verbatim. */
  @Test
  public void upgradeRequestCarriesEveryHandshakeHeader() {
    java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
    headers.put("Origin", "https://hyperdash.com");
    headers.put("User-Agent", "Mozilla/5.0 test");

    org.eclipse.jetty.websocket.client.ClientUpgradeRequest request =
        JettyHyperliquidTransport.upgradeRequest(headers);

    assertEquals("https://hyperdash.com", request.getHeader("Origin"));
    assertEquals("Mozilla/5.0 test", request.getHeader("User-Agent"));
    assertNull(
        JettyHyperliquidTransport.upgradeRequest(java.util.Collections.<String, String>emptyMap())
            .getHeader("Origin"));
  }
```

(`import static org.junit.Assert.assertNull;` を追加。`ClientUpgradeRequest.getHeader(String)` と `setHeader(String, String)` は websocket-client 9.3.8 の javap で確認済み。)

```java
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnectorTest'`
Expected: コンパイルエラー(`start(SourceProfile)`、`connectHeaders()`、`upgradeRequest` なし)

- [ ] **Step 3: Transport のシグネチャを変更する**

`HyperliquidTransport.java`: import `java.util.Map` を追加し、

```java
  /** Opens a WebSocket with extra handshake headers and reports its lifecycle. */
  Cancellable connect(
      URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback);
```

`JettyHyperliquidTransport.java`: import に `java.util.Map` と `org.eclipse.jetty.websocket.client.ClientUpgradeRequest` を追加。`connect` を差し替え:

```java
  @Override
  public Cancellable connect(
      URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
    webSocketClient.setConnectTimeout(timeoutMillis);
    final SocketAdapter adapter = new SocketAdapter(callback);
    final Future<Session> future;
    try {
      future = webSocketClient.connect(adapter, uri, upgradeRequest(headers));
    } catch (Exception failure) {
      callback.onFailure(failure);
      return new Cancellable() {
        @Override
        public void cancel() {
          // The synchronous connect failure was already delivered to the callback.
        }
      };
    }
    return new Cancellable() {
      @Override
      public void cancel() {
        future.cancel(true);
        adapter.closeSession();
      }
    };
  }

  /** Builds the upgrade request carrying the supplied handshake headers verbatim. */
  static ClientUpgradeRequest upgradeRequest(Map<String, String> headers) {
    ClientUpgradeRequest request = new ClientUpgradeRequest();
    for (Map.Entry<String, String> header : headers.entrySet()) {
      request.setHeader(header.getKey(), header.getValue());
    }
    return request;
  }
```

`FakeHyperliquidTransport.java`: フィールド `private final List<Map<String, String>> connectHeaders = new ArrayList<Map<String, String>>();` を追加(import `java.util.Map`、`java.util.LinkedHashMap`)。`connect` を差し替え:

```java
  @Override
  public Cancellable connect(
      URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
    connectCalls.add(uri);
    connectHeaders.add(new LinkedHashMap<String, String>(headers));
    connectTimeoutMillis = timeoutMillis;
    socketCallback = callback;
    socketCallbacks.add(callback);
    TrackedHandle handle = new TrackedHandle(false);
    connectHandles.add(handle);
    return handle;
  }

  public List<Map<String, String>> connectHeaders() {
    return new ArrayList<Map<String, String>>(connectHeaders);
  }
```

- [ ] **Step 4: Connector を `SourceProfile` に切り替える**

`HyperliquidConnector.java`:
- フィールド `private HyperliquidEnvironment environment;` を `private SourceProfile profile;` に変更。
- `start`:

```java
  /** Starts metadata discovery for the supplied source profile. */
  public void start(final SourceProfile newProfile) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            startOnStateLane(newProfile);
          }
        });
  }
```

- `startOnStateLane(HyperliquidEnvironment newEnvironment)` → `startOnStateLane(SourceProfile newProfile)`。本文の `newEnvironment == null` → `newProfile == null`、エラーメッセージは `"listener and profile must be set before start"`、`environment = newEnvironment;` → `profile = newProfile;`、`environment.infoUri()` → `profile.infoUri()`。
- 330 行目付近 `environment == null` → `profile == null`。
- 366 行目付近の `transport.connect(environment.webSocketUri(), HANDSHAKE_TIMEOUT_MILLIS, ...)` → `transport.connect(profile.webSocketUri(), profile.handshakeHeaders(), HANDSHAKE_TIMEOUT_MILLIS, ...)`。
- `import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment` が同ファイル内で未使用になれば削除(同パッケージなので import は元々無いはず。`grep -n HyperliquidEnvironment` で確認)。

- [ ] **Step 5: Session API と Provider を追随させる**

`HyperliquidSessionApi.java`:

```java
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
...
  /** Starts metadata discovery and the WebSocket connection for a source profile. */
  void login(SourceProfile profile);
```

`HyperliquidSession.java`:
- import `HyperliquidEnvironment` を `SourceProfile` に置換。
- フィールド追加: `private SourceProfile profile;`
- `login(final HyperliquidEnvironment environment)` → `login(final SourceProfile profile)`、内部 `handleLogin(profile)`。
- `handleLogin`:

```java
  private void handleLogin(SourceProfile newProfile) {
    if (!closed && newProfile != null) {
      profile = newProfile;
      connectionState = ConnectionState.STARTING;
      connector.start(newProfile);
    }
  }
```

`Provider.java` の `login`(暫定。Task 4 でドロップダウン解決に置換):

```java
  @Override
  public void login(LoginData loginData) {
    session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, environment(loginData)));
  }
```

- [ ] **Step 6: 既存テストを追随させる**

一括置換(実行前に `grep -rn "login(HyperliquidEnvironment\.\|start(HyperliquidEnvironment\." src/test | wc -l` で件数を控え、置換後に 0 件になることを確認):

```bash
sed -i 's/session\.login(HyperliquidEnvironment\.MAINNET)/session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET))/g' src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionSubscriptionTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionLifecycleTest.java
sed -i 's/connector\.start(HyperliquidEnvironment\.\(MAINNET\|TESTNET\))/connector.start(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.\1))/g' src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java
```

セッションテスト 2 ファイルと `HyperliquidConnectorTest` に import を追加:

```java
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
```

(`HyperliquidConnectorTest` は同パッケージなので import 不要。)

`ProviderTest` の `FakeSession`:

```java
    private SourceProfile lastLoginProfile;

    @Override
    public void login(SourceProfile profile) {
      commandCount++;
      lastLoginProfile = profile;
    }
```

`loginSelectsTestnetOnlyForTrueExtendedField` の各 assert を `factory.session.lastLoginProfile.environment()` に対する比較へ変更(期待値は従来どおり `MAINNET`/`TESTNET`)。

`ProviderEndToEndTest` は `provider.login(LoginData)` 経由なので変更不要。

- [ ] **Step 7: 全テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test`
Expected: BUILD SUCCESSFUL(既存テスト全件+新規 3 件)

- [ ] **Step 8: コミット**

```bash
git add -A src/main src/test
git commit -m "feat: route source profiles through transport, connector, and session

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 4: ログイン UI と Provider の解決

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidFieldManager.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java:84-86`、`:172-185`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidFieldManagerTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java`

**Interfaces:**
- Consumes: `MarketDataSource.fromFieldValue`/`fieldValues`、`SourceProfile.of`、`HyperliquidSessionApi.login(SourceProfile)`
- Produces: `HyperliquidFieldManager.SOURCE_FIELD = "source"`。`CredentialsDropdown(String name, boolean isKey, String label, String[] values)` は api-core 7.4.0.10 のコンストラクタ(javap で確認済み)。

- [ ] **Step 1: 失敗するテストを書く**

`HyperliquidFieldManagerTest` の既存テストを置き換え:

```java
  /** The source dropdown comes first (Hyperliquid default) and the testnet checkbox stays. */
  @Test
  public void exposesSourceDropdownAndOptionalTestnetCheckbox() {
    HyperliquidFieldManager manager = new HyperliquidFieldManager();

    assertEquals(2, manager.getCredentialsComponents().size());
    CredentialsComponent first = manager.getCredentialsComponents().get(0);
    assertTrue(first instanceof CredentialsDropdown);
    CredentialsDropdown dropdown = (CredentialsDropdown) first;
    assertEquals(HyperliquidFieldManager.SOURCE_FIELD, dropdown.getName());
    assertTrue(dropdown.isKey());
    CredentialsComponent second = manager.getCredentialsComponents().get(1);
    assertTrue(second instanceof CredentialsCheckbox);
    CredentialsCheckbox checkbox = (CredentialsCheckbox) second;
    assertEquals(HyperliquidFieldManager.TESTNET_FIELD, checkbox.getName());
    assertTrue(checkbox.isKey());
    assertFalse(checkbox.getValue());
    assertTrue(manager.isConfigured(Collections.emptyMap()));
  }
```

import に `velox.api.layer0.credentialscomponents.CredentialsDropdown` を追加。

`ProviderTest` に追加(既存 `field(String)` ヘルパー、`FakeSessionFactory` を使う):

```java
  /** The dropdown picks the source; testnet only matters for Hyperliquid; unknown falls back. */
  @Test
  public void loginResolvesSourceFromDropdownAndFallsBackToHyperliquid() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    Map<String, CredentialsSerializationField> fields =
        new HashMap<String, CredentialsSerializationField>();

    fields.put("source", field("Borsa"));
    fields.put("testnet", field("true"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.BORSA, factory.session.lastLoginProfile.source());
    assertEquals(HyperliquidEnvironment.MAINNET, factory.session.lastLoginProfile.environment());

    fields.put("source", field("Hyperdash"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERDASH, factory.session.lastLoginProfile.source());

    fields.put("source", field("something-else"));
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERLIQUID, factory.session.lastLoginProfile.source());
    assertEquals(HyperliquidEnvironment.TESTNET, factory.session.lastLoginProfile.environment());

    fields.remove("source");
    provider.login(new ExtendedLoginData(fields));
    assertEquals(MarketDataSource.HYPERLIQUID, factory.session.lastLoginProfile.source());
  }
```

import に `java.util.HashMap`、`java.util.Map` を追加(無ければ)。

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.HyperliquidFieldManagerTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderTest'`
Expected: コンパイルエラー(`SOURCE_FIELD` なし)

- [ ] **Step 3: 実装する**

`HyperliquidFieldManager.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import velox.api.layer0.credentialscomponents.CredentialsCheckbox;
import velox.api.layer0.credentialscomponents.CredentialsComponent;
import velox.api.layer0.credentialscomponents.CredentialsDropdown;
import velox.api.layer0.credentialscomponents.CredentialsFieldManager;
import velox.api.layer0.credentialscomponents.CredentialsSerializationField;

/** Supplies the order-book source dropdown and the optional Hyperliquid environment selector. */
public final class HyperliquidFieldManager implements CredentialsFieldManager {

  public static final String SOURCE_FIELD = "source";
  public static final String TESTNET_FIELD = "testnet";

  private final CredentialsDropdown source =
      new CredentialsDropdown(
          SOURCE_FIELD, true, "Order book source", MarketDataSource.fieldValues());
  private final CredentialsCheckbox testnet =
      new CredentialsCheckbox(TESTNET_FIELD, true, "Use Hyperliquid testnet");

  @Override
  public List<CredentialsComponent> getCredentialsComponents() {
    return Arrays.<CredentialsComponent>asList(source, testnet);
  }

  @Override
  public boolean isConfigured(Map<String, CredentialsSerializationField> fields) {
    return true;
  }
}
```

`Provider.java`: `login` と `environment(LoginData)` を置換:

```java
  @Override
  public void login(LoginData loginData) {
    session.login(profile(loginData));
  }
```

```java
  /** Resolves the source dropdown and testnet checkbox; missing or unknown values pick defaults. */
  private static SourceProfile profile(LoginData loginData) {
    Map<String, CredentialsSerializationField> fields = null;
    if (loginData instanceof ExtendedLoginData) {
      fields = ((ExtendedLoginData) loginData).extendedData;
    }
    if (fields == null) {
      return SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET);
    }
    MarketDataSource source =
        MarketDataSource.fromFieldValue(
            stringValue(fields.get(HyperliquidFieldManager.SOURCE_FIELD)));
    boolean testnet =
        Boolean.parseBoolean(stringValue(fields.get(HyperliquidFieldManager.TESTNET_FIELD)));
    return SourceProfile.of(
        source, testnet ? HyperliquidEnvironment.TESTNET : HyperliquidEnvironment.MAINNET);
  }

  private static String stringValue(CredentialsSerializationField field) {
    return field == null ? null : field.getStringValue();
  }
```

`Provider.java` の import に `velox.api.layer0.credentialscomponents.CredentialsSerializationField` を追加し、旧 `environment(...)` 内の完全修飾参照を削除。`java.util.Map` は既存 import を確認。

- [ ] **Step 4: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests 'com.bookmap.plugins.layer0.hyperliquid.HyperliquidFieldManagerTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderEndToEndTest'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidFieldManager.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/Provider.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidFieldManagerTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderTest.java
git commit -m "feat: add order book source dropdown to the login dialog

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 5: SNAPSHOT モードの `sz=0` レベル除外と深度サイズ変換

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrument.java:80-87`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiff.java:96-127`(`normalize`)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrumentTest.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiffTest.java`

**Interfaces:**
- Produces: `int PerpetualInstrument.toDepthSizeUnits(BigDecimal size) throws ValueConversionException`(0 → 0、それ以外は `toSizeUnits` と同じ)。Task 6 が使う。

- [ ] **Step 1: 失敗するテストを書く**

`PerpetualInstrumentTest` に追加(`import static org.junit.Assert.fail;` を追加。`ValueConversionException` は同一パッケージなので import 不要):

```java
  /** Depth sizes accept zero for removals while trade sizes keep rejecting it. */
  @Test
  public void depthSizeUnitsAcceptZero() throws ValueConversionException {
    PerpetualInstrument instrument = new PerpetualInstrument("BTC", 2);

    assertEquals(0, instrument.toDepthSizeUnits(new BigDecimal("0")));
    assertEquals(0, instrument.toDepthSizeUnits(new BigDecimal("0.00")));
    assertEquals(150, instrument.toDepthSizeUnits(new BigDecimal("1.5")));
    try {
      instrument.toSizeUnits(new BigDecimal("0"));
      fail("trade size zero must stay rejected");
    } catch (ValueConversionException expected) {
      assertEquals(ValueConversionException.Reason.NON_POSITIVE, expected.reason());
    }
  }
```

`OrderBookSnapshotDiffTest` に追加(既存 `valid`/`snapshot`/`bids`/`asks`/`level`/`depth` ヘルパーを使う):

```java
  /** A zero-size level in a full snapshot means the level is absent. */
  @Test
  public void treatsZeroSizeSnapshotLevelsAsAbsent() {
    List<DepthUpdate> initial =
        diff.apply(
            valid(snapshot(10, bids(level("100", "1"), level("99", "0")), asks(level("101", "0")))),
            false);
    assertEquals(Collections.singletonList(depth(true, 100000000, 1)), initial);

    List<DepthUpdate> next =
        diff.apply(valid(snapshot(11, bids(level("100", "0")), asks(level("101", "2")))), false);
    assertEquals(
        Arrays.asList(depth(true, 100000000, 0), depth(false, 101000000, 2)), next);
  }
```

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrumentTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiffTest'`
Expected: `PerpetualInstrumentTest` はコンパイルエラー。`OrderBookSnapshotDiffTest` の新規テストは `valid(...)` が null を返して NPE または比較失敗(現状 `normalize` が 0 を "Size is invalid" として拒否するため)。

- [ ] **Step 3: 実装する**

`PerpetualInstrument.java`、`toSizeUnits` の直後:

```java
  /** Converts a depth size to Bookmap units; zero means the level is gone. */
  public int toDepthSizeUnits(BigDecimal size) throws ValueConversionException {
    if (size != null && size.signum() == 0) {
      return 0;
    }
    return toSizeUnits(size);
  }
```

`OrderBookSnapshotDiff.normalize` のループ先頭、`level == null` チェックの直後に追加:

```java
      if (level.size() != null && level.size().signum() == 0) {
        // A zero-size level in a complete snapshot is simply absent.
        continue;
      }
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests 'com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrumentTest' --tests 'com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiffTest'`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrument.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiff.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrumentTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/OrderBookSnapshotDiffTest.java
git commit -m "feat: treat zero-size snapshot levels as absent and add depth size conversion

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 6: `DeltaOrderBook`(ステージド板・発行済み板・板置換)

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBook.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBookTest.java`

**Interfaces:**
- Consumes: `PerpetualInstrument.toDepthPriceUnits` / `toDepthSizeUnits`(Task 5)、`BookSnapshot`、`BookLevel`、`DepthUpdate`、`ValueConversionException`
- Produces(Task 7 が使う):

```java
public final class DeltaOrderBook {
  public enum Status { APPLIED, INVALID, UNSUPPORTED_PRICE }
  public static final class Result { Status status(); List<DepthUpdate> updates(); String diagnostic(); }
  public DeltaOrderBook(PerpetualInstrument instrument);
  public boolean seeded();
  public void beginGeneration();                 // シードフラグを落とし、ステージド板を破棄(発行済み板は保持)
  public Result applySeed(BookSnapshot seed);     // ステージド板を新規作成(sz=0 は捨てる)。updates は空
  public Result applyDelta(BookSnapshot delta, boolean live); // ステージド板へ畳み込み。live なら発行済み板も更新。updates = 発行すべき変更
  public List<DepthUpdate> publishStaged();       // アクティベーション: ステージド板全レベルを発行し、発行済み板にコピー
  public List<DepthUpdate> replacePublished();    // 板置換: 消えた価格を size=0、ステージド板全レベルを更新
  public List<DepthUpdate> clearPublished();      // 発行済み板を全削除
  public void reset();                            // 全状態破棄(発行なし)
}
```

タイムスタンプの鮮度判定はこのクラスの責務ではない(Task 7 のセッションが `lastAcceptedBookTime` で行う)。

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBookTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests the seed-then-delta book used for Borsa. Prices use szDecimals=0, so 1 = 1e6 units. */
public class DeltaOrderBookTest {

  private final DeltaOrderBook book = new DeltaOrderBook(new PerpetualInstrument("BTC", 0));

  @Test
  public void seedIsStagedUntilPublishedAndDropsZeroSizeLevels() {
    DeltaOrderBook.Result seed =
        book.applySeed(
            snapshot(1L, levels(level("100", "1"), level("99", "0")), levels(level("101", "2"))));

    assertEquals(DeltaOrderBook.Status.APPLIED, seed.status());
    assertTrue(seed.updates().isEmpty());
    assertTrue(book.seeded());
    assertEquals(
        Arrays.asList(depth(true, 100000000, 1), depth(false, 101000000, 2)),
        book.publishStaged());
  }

  @Test
  public void liveDeltaUpdatesRemovesAndIgnoresUnknownRemovals() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels(level("101", "2"))));
    book.publishStaged();

    DeltaOrderBook.Result delta =
        book.applyDelta(
            snapshot(
                1L,
                levels(level("100", "0"), level("98", "5"), level("97", "0")),
                levels(level("101", "3"))),
            true);

    assertEquals(DeltaOrderBook.Status.APPLIED, delta.status());
    assertEquals(
        Arrays.asList(depth(true, 100000000, 0), depth(true, 98000000, 5), depth(false, 101000000, 3)),
        delta.updates());
    assertEquals(
        Arrays.asList(depth(true, 98000000, 0), depth(false, 101000000, 0)), book.clearPublished());
  }

  @Test
  public void stagedDeltaDuringRecoveryIsAppliedByReplacePublished() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels(level("101", "2"))));
    book.publishStaged();
    book.beginGeneration();
    assertFalse(book.seeded());

    book.applySeed(snapshot(5L, levels(level("100", "1")), levels(level("102", "4"))));
    DeltaOrderBook.Result delta =
        book.applyDelta(snapshot(5L, levels(level("100", "0"), level("99", "7")), levels()), false);

    assertEquals(
        Arrays.asList(depth(true, 100000000, 0), depth(true, 99000000, 7), depth(false, 101000000, 0),
            depth(false, 102000000, 4)),
        book.replacePublished());
    assertEquals(
        Arrays.asList(depth(true, 99000000, 0), depth(false, 102000000, 0)), book.clearPublished());
    assertEquals(DeltaOrderBook.Status.APPLIED, delta.status());
  }

  @Test
  public void newGenerationSeedReplacesPublishedBookEvenWhenUnchanged() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels()));
    book.publishStaged();
    book.beginGeneration();
    book.applySeed(snapshot(0L, levels(level("100", "1")), levels()));

    assertEquals(Collections.singletonList(depth(true, 100000000, 1)), book.replacePublished());
  }

  @Test
  public void unsupportedPriceAndInvalidSizeAreReported() {
    DeltaOrderBook.Result unsupported =
        book.applySeed(snapshot(1L, levels(level("99999999999", "1")), levels()));
    assertEquals(DeltaOrderBook.Status.UNSUPPORTED_PRICE, unsupported.status());
    assertFalse(book.seeded());

    DeltaOrderBook.Result invalid =
        book.applySeed(snapshot(1L, levels(level("100", "0.5")), levels()));
    assertEquals(DeltaOrderBook.Status.INVALID, invalid.status());
    assertFalse(book.seeded());
  }

  @Test
  public void resetForgetsEverythingWithoutUpdates() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels()));
    book.publishStaged();

    book.reset();

    assertFalse(book.seeded());
    assertTrue(book.clearPublished().isEmpty());
  }

  private static BookSnapshot snapshot(long time, List<BookLevel> bids, List<BookLevel> asks) {
    return new BookSnapshot("BTC", time, bids, asks);
  }

  private static List<BookLevel> levels(BookLevel... levels) {
    return Arrays.asList(levels);
  }

  private static BookLevel level(String price, String size) {
    return new BookLevel(new BigDecimal(price), new BigDecimal(size));
  }

  private static DepthUpdate depth(boolean bid, int price, int size) {
    return new DepthUpdate(bid, price, size);
  }
}
```

`stagedDeltaDuringRecoveryIsAppliedByReplacePublished` では、回復中の差分(`live=false`)が発行済み板を変えていないことを、直後の `replacePublished()` が `100 → 0` と `101 → 0` を出すことで担保している。

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBookTest'`
Expected: コンパイルエラー(`DeltaOrderBook` なし)

- [ ] **Step 3: 実装する**

`src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBook.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Seed-then-delta order book for one alias: a per-generation staged book that seeds and deltas
 * are folded into, and the book currently published to Bookmap. Timestamp freshness is the
 * caller's responsibility.
 */
public final class DeltaOrderBook {

  /** Classifies one applied frame. */
  public enum Status {
    APPLIED,
    INVALID,
    UNSUPPORTED_PRICE
  }

  /** Immutable outcome of applying one seed or delta frame. */
  public static final class Result {
    private final Status status;
    private final List<DepthUpdate> updates;
    private final String diagnostic;

    private Result(Status status, List<DepthUpdate> updates, String diagnostic) {
      this.status = status;
      this.updates = Collections.unmodifiableList(new ArrayList<DepthUpdate>(updates));
      this.diagnostic = diagnostic;
    }

    /** Returns whether the frame was applied. */
    public Status status() {
      return status;
    }

    /** Returns the depth updates implied by an applied delta; empty for seeds and failures. */
    public List<DepthUpdate> updates() {
      return updates;
    }

    /** Returns a concise diagnostic for a non-applied result. */
    public String diagnostic() {
      return diagnostic;
    }
  }

  private final PerpetualInstrument instrument;
  private final SortedMap<Integer, Integer> publishedBids = new TreeMap<Integer, Integer>();
  private final SortedMap<Integer, Integer> publishedAsks = new TreeMap<Integer, Integer>();
  private SortedMap<Integer, Integer> stagedBids = new TreeMap<Integer, Integer>();
  private SortedMap<Integer, Integer> stagedAsks = new TreeMap<Integer, Integer>();
  private boolean seeded;

  /** Creates an empty book that normalizes prices and sizes with the instrument's rules. */
  public DeltaOrderBook(PerpetualInstrument instrument) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
  }

  /** Returns whether the current generation's seed has been staged. */
  public boolean seeded() {
    return seeded;
  }

  /** Starts a new generation: forgets the staged book and waits for a fresh seed. */
  public void beginGeneration() {
    seeded = false;
    stagedBids = new TreeMap<Integer, Integer>();
    stagedAsks = new TreeMap<Integer, Integer>();
  }

  /** Stages a complete seed for this generation; zero-size levels are absent. */
  public Result applySeed(BookSnapshot seed) {
    List<DepthUpdate> levels;
    try {
      levels = normalize(seed, false);
    } catch (UnsupportedPriceException failure) {
      return new Result(Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    SortedMap<Integer, Integer> bids = new TreeMap<Integer, Integer>();
    SortedMap<Integer, Integer> asks = new TreeMap<Integer, Integer>();
    for (DepthUpdate level : levels) {
      (level.bid() ? bids : asks).put(Integer.valueOf(level.price()), Integer.valueOf(level.size()));
    }
    stagedBids = bids;
    stagedAsks = asks;
    seeded = true;
    return new Result(Status.APPLIED, Collections.<DepthUpdate>emptyList(), "");
  }

  /**
   * Folds a delta into the staged book and, when {@code live}, into the published book as well.
   * Removals of prices the book does not hold are ignored and produce no update.
   */
  public Result applyDelta(BookSnapshot delta, boolean live) {
    List<DepthUpdate> levels;
    try {
      levels = normalize(delta, true);
    } catch (UnsupportedPriceException failure) {
      return new Result(Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (DepthUpdate level : levels) {
      SortedMap<Integer, Integer> staged = level.bid() ? stagedBids : stagedAsks;
      SortedMap<Integer, Integer> published = level.bid() ? publishedBids : publishedAsks;
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
      updates.add(level);
    }
    return new Result(Status.APPLIED, updates, "");
  }

  /** Publishes every staged level for activation and records the staged book as published. */
  public List<DepthUpdate> publishStaged() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendUpserts(updates, true, stagedBids);
    appendUpserts(updates, false, stagedAsks);
    copyStagedToPublished();
    return Collections.unmodifiableList(updates);
  }

  /**
   * Replaces the published book with the staged book: deletes every published price the staged
   * book lacks, then re-emits every staged level.
   */
  public List<DepthUpdate> replacePublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendVanished(updates, true, publishedBids, stagedBids);
    appendUpserts(updates, true, stagedBids);
    appendVanished(updates, false, publishedAsks, stagedAsks);
    appendUpserts(updates, false, stagedAsks);
    copyStagedToPublished();
    return Collections.unmodifiableList(updates);
  }

  /** Deletes every published level and empties the published book. */
  public List<DepthUpdate> clearPublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (Integer price : publishedBids.keySet()) {
      updates.add(new DepthUpdate(true, price.intValue(), 0));
    }
    for (Integer price : publishedAsks.keySet()) {
      updates.add(new DepthUpdate(false, price.intValue(), 0));
    }
    publishedBids.clear();
    publishedAsks.clear();
    return Collections.unmodifiableList(updates);
  }

  /** Drops all state without emitting updates. */
  public void reset() {
    beginGeneration();
    publishedBids.clear();
    publishedAsks.clear();
  }

  private void copyStagedToPublished() {
    publishedBids.clear();
    publishedBids.putAll(stagedBids);
    publishedAsks.clear();
    publishedAsks.putAll(stagedAsks);
  }

  private static void appendUpserts(
      List<DepthUpdate> updates, boolean bid, SortedMap<Integer, Integer> levels) {
    for (Map.Entry<Integer, Integer> level : levels.entrySet()) {
      updates.add(new DepthUpdate(bid, level.getKey().intValue(), level.getValue().intValue()));
    }
  }

  private static void appendVanished(
      List<DepthUpdate> updates,
      boolean bid,
      SortedMap<Integer, Integer> published,
      SortedMap<Integer, Integer> staged) {
    for (Integer price : published.keySet()) {
      if (!staged.containsKey(price)) {
        updates.add(new DepthUpdate(bid, price.intValue(), 0));
      }
    }
  }

  private List<DepthUpdate> normalize(BookSnapshot snapshot, boolean keepZeroSizes)
      throws UnsupportedPriceException, InvalidLevelException {
    if (snapshot == null) {
      throw new InvalidLevelException("Book is missing");
    }
    List<DepthUpdate> levels = new ArrayList<DepthUpdate>();
    normalizeSide(snapshot.bids(), true, keepZeroSizes, levels);
    normalizeSide(snapshot.asks(), false, keepZeroSizes, levels);
    return levels;
  }

  private void normalizeSide(
      List<BookLevel> side, boolean bid, boolean keepZeroSizes, List<DepthUpdate> out)
      throws UnsupportedPriceException, InvalidLevelException {
    if (side == null) {
      throw new InvalidLevelException("Book side is missing");
    }
    for (BookLevel level : side) {
      if (level == null || level.size() == null) {
        throw new InvalidLevelException("Book level is missing");
      }
      final int price;
      try {
        price = instrument.toDepthPriceUnits(level.price());
      } catch (ValueConversionException failure) {
        if (failure.reason() == ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE) {
          throw new UnsupportedPriceException("Price cannot be represented as depth units");
        }
        throw new InvalidLevelException("Price is invalid");
      }
      final int size;
      try {
        size = instrument.toDepthSizeUnits(level.size());
      } catch (ValueConversionException failure) {
        throw new InvalidLevelException("Size is invalid");
      }
      if (size == 0 && !keepZeroSizes) {
        continue;
      }
      out.add(new DepthUpdate(bid, price, size));
    }
  }

  private static final class InvalidLevelException extends Exception {
    private InvalidLevelException(String message) {
      super(message);
    }
  }

  private static final class UnsupportedPriceException extends Exception {
    private UnsupportedPriceException(String message) {
      super(message);
    }
  }
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests 'com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBookTest'`
Expected: BUILD SUCCESSFUL。`unsupportedPriceAndInvalidSizeAreReported` の `99999999999`(×10^6 が int 上限超え)が UNSUPPORTED_PRICE、`0.5`(szDecimals 0 で非整数)が INVALID になること。

- [ ] **Step 5: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBook.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/book/DeltaOrderBookTest.java
git commit -m "feat: add seed-then-delta order book state for Borsa

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 7: セッションの差分モード経路

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java:365-368`(record 生成)、`:475-502`(`handleBook`)、`:553-568`(`activateIfReady`)、`:600-606`(`removeRecord` の clear)、`:728-744`(`maybeRestore`)
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionSubscriptionTest.java:332`(直接コンストラクタ呼び出し)
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionDeltaBookTest.java`

**Interfaces:**
- Consumes: `SourceProfile.FeedMode`、`L2BookParameters`、`SubscriptionKey(coin,type,params)`、`DeltaOrderBook`、`HyperliquidSession.profile`(Task 3)
- Produces:

```java
SubscriptionRecord(String alias, PerpetualInstrument instrument, SubscriptionPermit permit,
    long activationDeadlineMillis, SourceProfile.FeedMode feedMode, L2BookParameters l2BookParameters)
SourceProfile.FeedMode feedMode();
DeltaOrderBook deltaBook();            // SNAPSHOT モードでは null
boolean hasActivationBook();           // SNAPSHOT: pendingBook != null / DELTA: deltaBook.seeded()
List<DepthUpdate> clearPublishedBook(); // モードに応じて diff.clear() / deltaBook.clearPublished()
```

- [ ] **Step 1: 失敗するテストを書く**

`src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionDeltaBookTest.java`(Fixture は `HyperliquidSessionSubscriptionTest` と同型。メタデータは `szDecimals:2` なので価格 `100.000` → `1000000` ユニット、サイズ `1` → `100` ユニット):

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Exercises the Borsa seed-then-delta book path through the real session and connector. */
public class HyperliquidSessionDeltaBookTest {

  @Test
  public void seedIsPublishedOnActivationAndDeltasStreamLive() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 1L, bids(level("100.000", "1"), level("99.000", "2")), asks()));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(
        Arrays.asList(
            "instrument-added:BTC", "depth:BTC:990000:200", "depth:BTC:1000000:100"),
        fixture.sink.events());
    fixture.sink.events().clear();

    fixture.receive(
        book("BTC", 1L, bids(level("100.000", "0")), asks(level("102.000", "3"))));
    fixture.drain();
    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "depth:BTC:1020000:300"), fixture.sink.events());
  }

  @Test
  public void deltaBeforeActivationIsFoldedIntoTheSeedNotPublished() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
    fixture.receive(book("BTC", 1L, bids(level("101.000", "2")), asks()));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.drain();
    assertTrue(fixture.sink.events().isEmpty());
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "instrument-added:BTC", "depth:BTC:1000000:100", "depth:BTC:1010000:200"),
        fixture.sink.events());
  }

  @Test
  public void staleDeltasAndUnknownRemovalsAreIgnored() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 0L, bids(level("105.000", "1")), asks()));
    fixture.receive(book("BTC", 2L, bids(level("999.000", "0")), asks()));
    fixture.drain();

    assertTrue(fixture.sink.events().isEmpty());
  }

  @Test
  public void seedArrivingAfterRestoreReplacesThePublishedBook() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(Collections.singletonList("connection-restored"), fixture.sink.events());
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 0L, bids(level("101.000", "2")), asks()));
    fixture.drain();

    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "depth:BTC:1010000:200"), fixture.sink.events());
  }

  @Test
  public void seedAndDeltasBeforeRestoreArePublishedAtTheBarrier() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 5L, bids(level("101.000", "2")), asks()));
    fixture.receive(book("BTC", 5L, bids(), asks(level("102.000", "3"))));
    fixture.drain();
    assertTrue(fixture.sink.events().isEmpty());
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "connection-restored",
            "depth:BTC:1000000:0",
            "depth:BTC:1010000:200",
            "depth:BTC:1020000:300"),
        fixture.sink.events());
  }

  @Test
  public void generationChangeWhilePendingWaitsForTheNewSeed() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
    fixture.drain();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());

    fixture.receive(book("BTC", 1L, bids(level("101.000", "2")), asks()));
    fixture.drain();

    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    assertTrue(fixture.sink.events().contains("depth:BTC:1010000:200"));
    assertTrue(!fixture.sink.events().contains("depth:BTC:1000000:100"));
  }

  @Test
  public void seedWithUnsupportedPriceRemovesTheAlias() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);

    fixture.receive(book("BTC", 1L, bids(level("99999999999.000", "1")), asks()));
    fixture.drain();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  private static String ack(SubscriptionType type) {
    return "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
        + "\"subscription\":{\"type\":\""
        + type.wireName()
        + "\",\"coin\":\"BTC\"}}}";
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

  private static String bids(String... levels) {
    return String.join(",", levels);
  }

  private static String asks(String... levels) {
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
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionDeltaBookTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport, new HyperliquidMetaParser(), budget, scheduler, clock, dispatcher::submitControl);
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
            HyperliquidSessionDeltaBookTest::noop);
    private long generation = 1L;

    void login() {
      session.login(SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]}");
      drain();
      transport.openSocket();
      drain();
    }

    void subscribe(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
      drain();
    }

    void activateBtc() {
      login();
      subscribe("BTC");
      completeSends(2);
      receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
      receive(ack(SubscriptionType.L2_BOOK));
      receive(ack(SubscriptionType.TRADES));
      drain();
    }

    void beginRecovery() {
      transport.remoteClose(1006, "lost");
      drain();
      clock.now += 1_000L;
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation = 2L;
      drain();
    }

    void receive(String frame) {
      session.onFrame(generation, frame);
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

    void advanceBy(long elapsed) {
      while (!tasks.isEmpty() && tasks.peek().due <= clock.now) {
        Task next = tasks.remove();
        if (!next.cancelled) {
          next.task.run();
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

補足:
- `budget()` は `HyperliquidSessionSubscriptionTest.budget()` と同じ引数(10, 30, 2_000, 1_000, 60_000L)。
- `beginRecovery` は `HyperliquidSessionLifecycleTest.Fixture.beginRecovery` と同じ手順(1 秒進めて再接続)。
- `"connection-restored"` は `RecordingSessionSink.onConnectionRestored` が `events` に追加する文字列(同ファイルで確認)。
- `sink.systemMessages()` は `"UNCLASSIFIED:Hyperliquid book price is unsupported: ..."` 形式。

`HyperliquidSessionSubscriptionTest.java:332` のコンストラクタ呼び出しを更新:

```java
    SubscriptionRecord record =
        new SubscriptionRecord(
            "BTC", instrument, permit, 10_000L, SourceProfile.FeedMode.SNAPSHOT, L2BookParameters.NONE);
```

(`L2BookParameters` の import を追加。`SourceProfile` は Task 3 で import 済み。)

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.session.HyperliquidSessionDeltaBookTest'`
Expected: `HyperliquidSessionSubscriptionTest` のコンパイルエラー(6 引数コンストラクタなし)。

- [ ] **Step 3: `SubscriptionRecord` を拡張する**

import 追加: `com.bookmap.plugins.layer0.hyperliquid.SourceProfile`、`com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBook`、`com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate`、`com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters`、`java.util.List`。

フィールド追加とコンストラクタ置換:

```java
  private final SourceProfile.FeedMode feedMode;
  private final DeltaOrderBook deltaBook;

  /** Creates an alias record with its two reserved provider subscription slots. */
  public SubscriptionRecord(
      String alias,
      PerpetualInstrument instrument,
      SubscriptionPermit permit,
      long activationDeadlineMillis,
      SourceProfile.FeedMode feedMode,
      L2BookParameters l2BookParameters) {
    this.alias = alias;
    this.instrument = instrument;
    this.permit = permit;
    this.activationDeadlineMillis = activationDeadlineMillis;
    this.feedMode = feedMode;
    l2BookKey = new SubscriptionKey(instrument.symbol(), SubscriptionType.L2_BOOK, l2BookParameters);
    tradesKey = new SubscriptionKey(instrument.symbol(), SubscriptionType.TRADES);
    diff = new OrderBookSnapshotDiff(instrument);
    deltaBook =
        feedMode == SourceProfile.FeedMode.SEED_THEN_DELTA ? new DeltaOrderBook(instrument) : null;
  }

  /** Returns how this alias's source delivers l2Book frames. */
  public SourceProfile.FeedMode feedMode() {
    return feedMode;
  }

  /** Returns the seed-then-delta book, or null in snapshot mode. */
  public DeltaOrderBook deltaBook() {
    return deltaBook;
  }

  /** Returns whether a current-generation book is available for activation. */
  public boolean hasActivationBook() {
    return deltaBook == null ? pendingBook != null : deltaBook.seeded();
  }

  /** Deletes every published level of this alias, whichever book mode it uses. */
  public List<DepthUpdate> clearPublishedBook() {
    return deltaBook == null ? diff.clear() : deltaBook.clearPublished();
  }
```

`beginGeneration` の末尾に追加:

```java
    if (deltaBook != null) {
      deltaBook.beginGeneration();
    }
```

`remove()` の `diff.reset();` の直後に追加:

```java
    if (deltaBook != null) {
      deltaBook.reset();
    }
```

spotbugs の `EI_EXPOSE_REP` は `SubscriptionRecord` に対して既に除外済み(`config/spotbugs/exclude.xml`)。`deltaBook()` の返却もこれに含まれる。

- [ ] **Step 4: `HyperliquidSession` を差分モード対応にする**

import 追加: `com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBook`。

record 生成(`handleSubscribe`):

```java
    final SubscriptionRecord record =
        new SubscriptionRecord(
            symbol,
            instrument,
            decision.permit(),
            activationDeadlineMillis,
            profile.feedMode(),
            profile.l2BookParameters());
```

`handleBook` の先頭、`record == null || REMOVED` チェックの直後に分岐を追加:

```java
    if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
      handleDeltaBook(record, snapshot);
      return;
    }
```

新メソッド(`handleBook` の直後):

```java
  /**
   * Seed-then-delta path. The first frame of a generation is the seed and is always accepted;
   * later frames are deltas that must not be older than the last accepted frame.
   */
  private void handleDeltaBook(SubscriptionRecord record, BookSnapshot snapshot) {
    DeltaOrderBook book = record.deltaBook();
    boolean recoveringNow = recovering && connectionState == ConnectionState.RECONNECTING;
    if (!book.seeded()) {
      DeltaOrderBook.Result seed = book.applySeed(snapshot);
      if (!acceptDeltaResult(record, seed)) {
        return;
      }
      record.acceptActiveBook(snapshot.time());
      if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
        activateIfReady(record);
      } else if (!recoveringNow) {
        publishDepth(record, book.replacePublished());
      }
      return;
    }
    if (snapshot.time() < record.lastAcceptedBookTime()) {
      return;
    }
    boolean live = record.state() == SubscriptionRecord.State.ACTIVE && !recoveringNow;
    DeltaOrderBook.Result delta = book.applyDelta(snapshot, live);
    if (!acceptDeltaResult(record, delta)) {
      return;
    }
    record.acceptActiveBook(snapshot.time());
    if (live) {
      publishDepth(record, delta.updates());
    }
  }

  private boolean acceptDeltaResult(SubscriptionRecord record, DeltaOrderBook.Result result) {
    if (result.status() == DeltaOrderBook.Status.UNSUPPORTED_PRICE) {
      removeRecord(
          record.alias(),
          RemovalCause.UNSUPPORTED_PRICE,
          "Hyperliquid book price is unsupported: " + result.diagnostic());
      return false;
    }
    if (result.status() != DeltaOrderBook.Status.APPLIED) {
      sink.onDiagnostic("discarded invalid book for " + record.alias() + ": " + result.diagnostic());
      return false;
    }
    return true;
  }
```

`activateIfReady`: 条件の `|| record.pendingBook() == null` を `|| !record.hasActivationBook()` に置換し、発行行を差し替え:

```java
    if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
      publishDepth(record, record.deltaBook().publishStaged());
    } else {
      publishDepth(record, record.diff().apply(record.takePendingBook(), false));
    }
```

`removeRecord`: `publishDepth(record, record.diff().clear());` → `publishDepth(record, record.clearPublishedBook());`

`maybeRestore` の ACTIVE 分岐を置換:

```java
      if (record.state() == SubscriptionRecord.State.ACTIVE) {
        if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
          if (record.deltaBook().seeded()) {
            publishDepth(record, record.deltaBook().replacePublished());
          }
        } else {
          com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.NormalizedBookSnapshot
              book = record.takeRecoveryBook(generation);
          if (book != null) {
            publishDepth(record, record.diff().apply(book, true));
            record.acceptActiveBook(book.time());
            record.takeForceFullResync();
          }
        }
        ArrayDeque<PendingTrade> pending = record.takePendingTrades();
        while (!pending.isEmpty()) {
          publishIfNew(record, pending.removeFirst());
        }
      }
```

- [ ] **Step 5: テストが通ることを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test`
Expected: BUILD SUCCESSFUL(新規 7 件+既存全件)。`seedIsPublishedOnActivationAndDeltasStreamLive` の初回発行順は bid 昇順(`990000` → `1000000`)。

失敗した場合の確認ポイント:
- `staleDeltasAndUnknownRemovalsAreIgnored`: 時刻 0 の差分はシード時刻 1 より古いので破棄、時刻 2 の未知除去は `applyDelta` が無視。
- `seedArrivingAfterRestoreReplacesThePublishedBook`: 世代 2 のシードは時刻 0 でも受理される(シードは鮮度判定を挟まない)。

- [ ] **Step 6: コミット**

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionSubscriptionTest.java src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionDeltaBookTest.java
git commit -m "feat: deliver Borsa seed-then-delta books through the session state machine

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

---

### Task 8: エンドツーエンド検証、README、品質ゲート

**Files:**
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`
- Modify: `README.md:3-6`、`:14`、`:53-54`

**Interfaces:**
- Consumes: Task 1〜7 の全成果。E2E Fixture の既存ヘルパー `subscribe`、`completeSends`、`ack(coin,type)`、`drain`、`trace`、`transport`。

- [ ] **Step 1: 失敗するテストを書く**

`ProviderEndToEndTest.Fixture` にログインデータビルダーを追加(`testnetLogin()` の隣):

```java
    private static LoginData sourceLogin(String source, boolean testnet) {
      java.util.Map<String, CredentialsSerializationField> fields =
          new java.util.HashMap<String, CredentialsSerializationField>();
      fields.put(
          HyperliquidFieldManager.SOURCE_FIELD, new CredentialsSerializationField(true, false, source));
      fields.put(
          HyperliquidFieldManager.TESTNET_FIELD,
          new CredentialsSerializationField(true, false, Boolean.toString(testnet)));
      return new ExtendedLoginData(fields);
    }

    private void loginWithSource(String source, boolean testnet) {
      provider.login(sourceLogin(source, testnet));
      drain();
      transport.completeMeta(200, metadata("BTC"));
      drain();
      transport.openSocket();
      drain();
    }

    private void frame(String json) {
      transport.emitTextFromConnection(transport.connectCalls().size() - 1, json);
    }
```

テストを追加:

```java
  @Test
  public void borsaLoginUsesMainnetMetadataAndPublishesSeedThenDelta() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSource("Borsa", true);
    fixture.subscribe("BTC");
    fixture.completeSends();

    assertEquals("https://api.hyperliquid.xyz/info", fixture.transport.httpUri().toString());
    assertEquals("wss://ws.borsa.cc/", fixture.transport.connectCalls().get(0).toString());
    assertTrue(fixture.transport.connectHeaders().get(0).isEmpty());
    assertTrue(
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(
                "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\"}}"));

    fixture.ack("BTC", "l2Book");
    fixture.ack("BTC", "trades");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":1,\"levels\":["
            + "[{\"px\":\"100\",\"sz\":\"1\",\"n\":1}],[{\"px\":\"101\",\"sz\":\"2\",\"n\":1}]]}}");
    fixture.frame(
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":1,\"levels\":["
            + "[{\"px\":\"100\",\"sz\":\"0\",\"n\":0}],[{\"px\":\"102\",\"sz\":\"3\",\"n\":1}]]}}");
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "login",
            "added:BTC",
            "depth:BTC:1000000:100",
            "depth:BTC:1010000:200",
            "depth:BTC:1000000:0",
            "depth:BTC:1020000:300"),
        fixture.trace);
    fixture.closeTwice();
    fixture.assertClosed();
  }

  @Test
  public void hyperdashLoginSendsHandshakeHeadersAndNSigFigsAndAcceptsEchoedAck() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSource("Hyperdash", false);
    fixture.subscribe("BTC");
    fixture.completeSends();

    assertEquals(
        "wss://api.hyperdash.com/ws/orderbook",
        fixture.transport.connectCalls().get(0).toString());
    assertEquals(
        "https://hyperdash.com", fixture.transport.connectHeaders().get(0).get("Origin"));
    assertTrue(fixture.transport.connectHeaders().get(0).containsKey("User-Agent"));
    assertTrue(
        fixture
            .transport
            .socket()
            .successfulSendBodies()
            .contains(
                "{\"method\":\"subscribe\",\"subscription\":"
                    + "{\"type\":\"l2Book\",\"coin\":\"BTC\",\"nSigFigs\":5}}"));

    fixture.frame(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\",\"nSigFigs\":5}}}");
    fixture.ack("BTC", "trades");
    fixture.book("BTC", 1L, "100", "1", "101", "2");
    fixture.drain();

    assertEquals(
        Arrays.asList("login", "added:BTC", "depth:BTC:1000000:100", "depth:BTC:1010000:200"),
        fixture.trace);
    fixture.closeTwice();
    fixture.assertClosed();
  }
```

`successfulSendBodies()` に購読 JSON が入る前提は `FakeSocket.succeedNextSend` の実装どおり。`metadata("BTC")` の `szDecimals` が 2 であることは既存テストの `depth:BTC:1000000:100` から確認済み。

- [ ] **Step 2: テストが失敗することを確認する**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests 'com.bookmap.plugins.layer0.hyperliquid.ProviderEndToEndTest'`
Expected: 全タスクが完了していれば通る。通らない場合は失敗内容(URI、ヘッダー、JSON、trace の順序)を該当タスクに戻って修正する。通った場合は Step 3 へ。

- [ ] **Step 3: README を更新する**

`README.md` 冒頭段落(3〜6 行目)を置換:

```markdown
Read-only Bookmap Layer 0 market data for Hyperliquid default-DEX perpetuals. The adapter
loads perpetual metadata over REST, then publishes aggregate L2 books and trades over one
WebSocket. The `Order book source` dropdown selects Hyperliquid (default), Borsa, or Hyperdash;
the `Use Hyperliquid testnet` checkbox only applies to Hyperliquid. It never requests
credentials and never sends orders.
```

「Scope and behavior」の先頭項目(14 行目)を置換し、続けて 1 項目追加:

```markdown
- With the Hyperliquid source, the Mainnet/Testnet checkbox selects both the metadata REST
  endpoint and the market-data WebSocket. Borsa (`wss://ws.borsa.cc/`) and Hyperdash
  (`wss://api.hyperdash.com/ws/orderbook`) relay the Mainnet book, so they always load
  metadata from Hyperliquid Mainnet and ignore the checkbox.
- Borsa sends one full seed per connection followed by per-level deltas; the adapter folds the
  deltas locally and replaces the published book on every reconnect. Hyperdash sends full
  snapshots like Hyperliquid and requires browser-style handshake headers, which the adapter
  adds automatically. The adapter does not verify either relay against Hyperliquid itself.
```

53〜54 行目の段落末尾に 1 文追加:

```markdown
Borsa deltas are not self-healing after a dropped frame; the adapter reconnects and resynchronizes
from a fresh seed instead.
```

- [ ] **Step 4: 全品質ゲートを通す**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
Expected: BUILD SUCCESSFUL(spotless、checkstyle、spotbugs、全テスト、Java 8 バイトコード検証)。spotbugs が `DeltaOrderBook.Result.updates()` 等で `EI_EXPOSE_REP` を報告した場合は、返却値が `Collections.unmodifiableList` であることを確認したうえで `config/spotbugs/exclude.xml` に `SubscriptionRecord` と同形式の `<Match>` を追加する。

- [ ] **Step 5: コミット**

```bash
git add README.md src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java config/spotbugs/exclude.xml
git commit -m "test: verify Borsa and Hyperdash end to end and document source selection

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01FWSdzxyVgDyJHA8AoGdySW"
```

(`config/spotbugs/exclude.xml` を変更しなかった場合は `git add` から外す。)

---

## 仕様カバレッジ確認

| 仕様項目 | タスク |
|---|---|
| `MarketDataSource` 列挙体、`SourceProfile`(URI/ヘッダー/パラメータ/フィードモード)、メタデータは Borsa/Hyperdash で Mainnet 固定 | Task 1 |
| `SubscriptionKey` の任意パラメータ(null 省略、等価性は coin+type) | Task 2 |
| パーサー: ACK の `nLevels` 許容、板 `sz` の 0 許容(trades は正のみ) | Task 2 |
| Transport: `ClientUpgradeRequest` へのヘッダー設定、IF にヘッダーマップ追加、403 は `onFailure` 経路(既存) | Task 3 |
| Connector: メタデータ URI と WS URI の分離、`start(SourceProfile)` | Task 3 |
| Session API / Session の `login(SourceProfile)` | Task 3 |
| `HyperliquidFieldManager` ドロップダウン(既定 Hyperliquid)、Provider の解決(不明値フォールバック、testnet は Hyperliquid のみ) | Task 4 |
| 板正規化の `sz=0` 受理。SNAPSHOT では捨てる | Task 5 |
| SEED_THEN_DELTA: ステージド板・発行済み板、シード無条件受理、差分の鮮度判定(等号許容)、未知除去黙殺、板置換、世代境界 | Task 6(状態)+ Task 7(状態機械との接続) |
| PENDING_BOOK/回復中/アクティブでの差分扱い、アクティベーション条件、回復完了時と回復後シードの板置換 | Task 7 |
| フレーム溢れは既存 `reconnect` で新シードを得る(追加規則なし) | 変更不要(Task 7 のテストは既存 lifecycle テストで担保) |
| trades の互換(重複排除キー不変) | 変更不要(Task 8 の E2E で Borsa 経路の trade 配信を追加したい場合は `fixture.trade(...)` を追記) |
| エンドツーエンド(Borsa/Hyperdash)、README | Task 8 |
