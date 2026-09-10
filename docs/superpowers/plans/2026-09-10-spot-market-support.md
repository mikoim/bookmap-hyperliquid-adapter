# スポット市場サポート 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hyperliquid のスポット市場(`BASE/QUOTE`)を perp と同じ経路で Bookmap に配信し、板のネイティブ価格単位を `long` にして 8 桁格子でも溢れないようにする。

**Architecture:** `PerpetualInstrument` を `Instrument`(`symbol` / `coin` / `Market`)に統合し、ログイン時に `allPerpMetas` と `spotMeta` を並行取得して 1 つの銘柄リストに結合する。セッションは symbol で銘柄を解決し、WebSocket とマーク価格は `coin`(`@107`)で扱う。板は `long` のネイティブ単位から `int` の Bookmap bucket に丸める。

**Tech Stack:** Java 8 ソースレベル(`var`・`List.of`・record 不可)、Gson 2.4、JUnit 4.13、Gradle wrapper(オフライン)、Spotless / Checkstyle / SpotBugs。

**仕様:** `docs/superpowers/specs/2026-09-10-spot-market-support-design.md`(承認済み)。判断に迷ったら仕様が正。

## Global Constraints

- Java 8 バイトコード(`options.release = 8`)。`var`、`List.of`、record、`Map.entry` は使わない。ジェネリクスはダイヤモンドでなく明示型(`new ArrayList<Instrument>()`)で既存に合わせる。
- Checkstyle: 行長 100 以下、public 型と public メソッドに Javadoc 必須。
- ビルドは必ず `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline ...` で実行する(PATH に `java` がない。ネットワークは使わない)。
- 各タスクの最後に `spotlessApply` を掛けてからコミットする。フォーマット崩れは Checkstyle ではなく Spotless が直す。
- Bookmap type 文字列は `"PERPETUAL"` と `"SPOT"` の 2 つだけ。
- 価格桁: perp `priceDecimals = 6 − szDecimals`、spot `priceDecimals = 8 − szDecimals`。
- スポットの Bookmap symbol は `baseName/quoteName`、購読・マーク価格キーは `universe[].name`(`@107` または `PURR/USDC`)。
- `spotMeta` の失敗はログイン失敗。部分成功は作らない。
- コミットメッセージは英語の Conventional Commits(`feat:` / `refactor:` / `test:` / `docs:`)。末尾に次のトレーラーを付ける:

  ```text
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24
  ```

## ファイル構成

| 操作 | パス | 責務 |
|---|---|---|
| Create | `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/Market.java` | 市場種別。価格桁上限と Bookmap type 文字列 |
| Rename+Modify | `model/PerpetualInstrument.java` → `model/Instrument.java` | symbol / coin / market を持つ銘柄と単位変換(`long` depth 単位) |
| Modify | `book/PriceBucketer.java` | `long` ネイティブ単位 → `int` bucket。溢れは `DEPTH_PRICE_OUT_OF_RANGE` |
| Modify | `book/DeltaOrderBook.java`、`book/OrderBookSnapshotDiff.java` | ネイティブ板のキーを `Long` に |
| Modify | `parse/HyperliquidMetaParser.java` | `parseSpotMeta`、`combine` |
| Modify | `HyperliquidConnector.java` | 2 リクエスト並行取得と結合、失敗時の取り消し |
| Modify | `session/HyperliquidSession.java`、`session/SubscriptionRecord.java` | coin キーの購読、type と market の照合、coin でのマーク価格参照 |
| Modify | `Provider.java` | `SubscribeInfo` / `InstrumentInfo` の type を market から取る |
| Modify | `src/test/.../TestMetadata.java`、`FakeHyperliquidTransport.java` | spotMeta フィクスチャ、2 リクエストの完了操作 |
| Rename+Modify | `src/test/.../model/PerpetualInstrumentTest.java` → `model/InstrumentTest.java` | |
| Modify | 各テスト(`PriceBucketerTest`、`DeltaOrderBookTest`、`OrderBookSnapshotDiffTest`、`HyperliquidMetaParserTest`、`HyperliquidConnectorTest`、`HyperliquidSessionSubscriptionTest`、`HyperliquidSessionAssetContextTest`、`ProviderTest`、`ProviderEndToEndTest`) | |
| Modify | `README.md`、`docs/development.md` | 利用者向け説明 |

すべてのソースは `src/main/java/com/bookmap/plugins/layer0/hyperliquid/` 配下、テストは `src/test/java/com/bookmap/plugins/layer0/hyperliquid/` 配下。以下、パスはこの接頭辞を省略する。

---

### Task 1: `Market` enum と `Instrument` への統合(改名、coin、market)

**Files:**
- Create: `model/Market.java`
- Rename: `model/PerpetualInstrument.java` → `model/Instrument.java`(全面書き換え)
- Rename: `src/test/.../model/PerpetualInstrumentTest.java` → `model/InstrumentTest.java`
- Modify: `PerpetualInstrument` を参照する全 22 ファイル(機械的置換)
- Modify: `session/HyperliquidSession.java`(参照価格の再構築を `withReferencePrice` に)

**Interfaces:**
- Produces:
  - `enum Market { PERPETUAL, SPOT }` with `int maxDecimals()`, `String bookmapType()`
  - `Instrument(String symbol, String coin, Market market, int sizeDecimals, BigDecimal referencePrice)`
  - `static Instrument perpetual(String symbol, int sizeDecimals)` / `perpetual(String, int, BigDecimal)`
  - `static Instrument spot(String symbol, String coin, int sizeDecimals)`
  - `Instrument withReferencePrice(BigDecimal)`, `String coin()`, `Market market()`
  - 既存の `symbol()`, `sizeDecimals()`, `priceDecimals()`, `pips()`, `referencePrice()`, `sizeMultiplier()`, `toDepthPriceUnits`(このタスクではまだ `int`), `toTradePriceUnits`, `toSizeUnits`, `toDepthSizeUnits` は不変

- [ ] **Step 1: 失敗するテストを書く**

`git mv` でテストを改名してから、ファイル末尾(最後の `}` の前)に追加する。

```bash
git mv src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrumentTest.java \
       src/test/java/com/bookmap/plugins/layer0/hyperliquid/model/InstrumentTest.java
```

`InstrumentTest.java` の追加テスト(クラス名とクラス Javadoc も `InstrumentTest` / "Tests exact unit conversion for perpetual and spot instruments." に直す):

```java
  /** A spot pair carries a display symbol, a wire coin, and the 8-decimal spot price rule. */
  @Test
  public void spotInstrumentDerivesEightDecimalPriceGrid() {
    Instrument hype = Instrument.spot("HYPE/USDC", "@107", 2);

    assertEquals("HYPE/USDC", hype.symbol());
    assertEquals("@107", hype.coin());
    assertEquals(Market.SPOT, hype.market());
    assertEquals("SPOT", hype.market().bookmapType());
    assertEquals(6, hype.priceDecimals());
    assertEquals(1e-6d, hype.pips(), 0.0d);
    assertNull(hype.referencePrice());
  }

  /** A perpetual keeps symbol and coin identical and the 6-decimal rule. */
  @Test
  public void perpetualInstrumentUsesItsSymbolAsCoin() {
    Instrument btc = Instrument.perpetual("BTC", 5, new BigDecimal("79394.0"));

    assertEquals("BTC", btc.coin());
    assertEquals(Market.PERPETUAL, btc.market());
    assertEquals("PERPETUAL", btc.market().bookmapType());
    assertEquals(1, btc.priceDecimals());
    assertEquals(new BigDecimal("79394.0"), btc.referencePrice());
  }

  /** The size-decimals ceiling follows the market: 6 for perps, 8 for spot. */
  @Test
  public void sizeDecimalsCeilingFollowsTheMarket() {
    assertEquals(0, Instrument.spot("USDC/USDH", "@9", 8).priceDecimals());
    try {
      Instrument.spot("X/USDC", "@1", 9);
      fail("spot szDecimals 9 must be rejected");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("8"));
    }
  }

  /** Replacing the reference price keeps every other attribute. */
  @Test
  public void withReferencePriceKeepsIdentity() {
    Instrument hype = Instrument.spot("HYPE/USDC", "@107", 2);

    Instrument priced = hype.withReferencePrice(new BigDecimal("82.94"));

    assertEquals("HYPE/USDC", priced.symbol());
    assertEquals("@107", priced.coin());
    assertEquals(Market.SPOT, priced.market());
    assertEquals(2, priced.sizeDecimals());
    assertEquals(new BigDecimal("82.94"), priced.referencePrice());
    assertNull(priced.withReferencePrice(new BigDecimal("-1")).referencePrice());
  }
```

`rejectsSizeDecimalsOutsideZeroThroughSix` の Javadoc は "Rejects perpetual metadata whose size scale is outside zero through six." にする(内容は不変)。

- [ ] **Step 2: 改名を機械的に適用する**

```bash
cd /home/dev/src/bookmap-hyperliquid-adapter
git mv src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/PerpetualInstrument.java \
       src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/Instrument.java
grep -rl 'PerpetualInstrument' src | xargs sed -i \
  -e 's/new PerpetualInstrument(/Instrument.perpetual(/g' \
  -e 's/PerpetualInstrument/Instrument/g'
grep -rn 'PerpetualInstrument' src ; echo "remaining: $?"   # 1 (no match) expected
```

- [ ] **Step 3: `Market` を作る**

`model/Market.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

/**
 * Hyperliquid market kinds. Prices carry at most five significant figures and at most {@code
 * maxDecimals - szDecimals} decimals: six for perpetuals and eight for spot pairs (Tick and lot
 * size). The Bookmap type string is what the Subscribe dialog shows and sends back.
 */
public enum Market {
  PERPETUAL(6, "PERPETUAL"),
  SPOT(8, "SPOT");

  private final int maxDecimals;
  private final String bookmapType;

  Market(int maxDecimals, String bookmapType) {
    this.maxDecimals = maxDecimals;
    this.bookmapType = bookmapType;
  }

  /** Returns the exchange's MAX_DECIMALS for this market's prices. */
  public int maxDecimals() {
    return maxDecimals;
  }

  /** Returns the instrument type string used in Bookmap's SubscribeInfo and InstrumentInfo. */
  public String bookmapType() {
    return bookmapType;
  }
}
```

- [ ] **Step 4: `Instrument` を書き直す**

`model/Instrument.java` 全体(`sed` 後の内容を置き換える):

```java
package com.bookmap.plugins.layer0.hyperliquid.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Immutable metadata and exact unit conversion rules for one instrument, perpetual or spot. The
 * symbol is what Bookmap displays and subscribes by; the coin is the exchange's WebSocket key and
 * the key under which fastAssetCtxs reports its mark price. For a perpetual both are the same
 * name; for a spot pair the symbol is {@code BASE/QUOTE} and the coin is {@code @index}.
 */
public final class Instrument {

  private static final BigInteger MAX_SIZE_UNITS = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MAX_DEPTH_PRICE_UNITS = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger MAX_TRADE_PRICE_UNITS =
      BigInteger.valueOf(9_007_199_254_740_992L);

  private final String symbol;
  private final String coin;
  private final Market market;
  private final int sizeDecimals;
  private final int priceDecimals;
  private final double pips;
  private final double sizeMultiplier;
  private final BigDecimal referencePrice;

  /**
   * Creates instrument metadata.
   *
   * @param referencePrice mark price observed after login, or null; non-positive values are dropped
   */
  public Instrument(
      String symbol, String coin, Market market, int sizeDecimals, BigDecimal referencePrice) {
    if (market == null) {
      throw new IllegalArgumentException("market must not be null");
    }
    if (sizeDecimals < 0 || sizeDecimals > market.maxDecimals()) {
      throw new IllegalArgumentException(
          "sizeDecimals must be between zero and " + market.maxDecimals());
    }
    this.symbol = symbol;
    this.coin = coin;
    this.market = market;
    this.sizeDecimals = sizeDecimals;
    this.priceDecimals = market.maxDecimals() - sizeDecimals;
    this.pips = Math.pow(10d, -priceDecimals);
    this.sizeMultiplier = Math.pow(10d, sizeDecimals);
    this.referencePrice =
        referencePrice != null && referencePrice.signum() > 0 ? referencePrice : null;
  }

  /** Creates a perpetual without a reference price; tick candidates then fall back to the grid. */
  public static Instrument perpetual(String symbol, int sizeDecimals) {
    return perpetual(symbol, sizeDecimals, null);
  }

  /** Creates a perpetual, whose coin is its symbol. */
  public static Instrument perpetual(String symbol, int sizeDecimals, BigDecimal referencePrice) {
    return new Instrument(symbol, symbol, Market.PERPETUAL, sizeDecimals, referencePrice);
  }

  /** Creates a spot pair without a reference price. */
  public static Instrument spot(String symbol, String coin, int sizeDecimals) {
    return new Instrument(symbol, coin, Market.SPOT, sizeDecimals, null);
  }

  /** Returns a copy with the given reference price; every other attribute is kept. */
  public Instrument withReferencePrice(BigDecimal newReferencePrice) {
    return new Instrument(symbol, coin, market, sizeDecimals, newReferencePrice);
  }

  /** Returns the Bookmap symbol and alias. */
  public String symbol() {
    return symbol;
  }

  /** Returns the WebSocket subscription key, also the fastAssetCtxs key. */
  public String coin() {
    return coin;
  }

  /** Returns the market kind. */
  public Market market() {
    return market;
  }

  /** Returns the number of decimal places allowed for quantity. */
  public int sizeDecimals() {
    return sizeDecimals;
  }

  /** Returns the derived number of decimal places used for price units. */
  public int priceDecimals() {
    return priceDecimals;
  }

  /**
   * Returns the native price grid (lossless display quantum), not the Bookmap tick chosen at
   * subscribe time.
   */
  public double pips() {
    return pips;
  }

  /** Returns the last observed mark price, or null when unknown. */
  public BigDecimal referencePrice() {
    return referencePrice;
  }

  /** Returns the quantity multiplier used by Bookmap. */
  public double sizeMultiplier() {
    return sizeMultiplier;
  }

  /** Converts a price to exact depth units. */
  public int toDepthPriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_DEPTH_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return units.intValue();
  }

  /** Converts a price to exact trade units. */
  public double toTradePriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_TRADE_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
    }
    double converted = units.doubleValue();
    if (Double.isInfinite(converted) || Double.isNaN(converted)) {
      throw new ValueConversionException(ValueConversionException.Reason.TRADE_PRICE_OUT_OF_RANGE);
    }
    return converted;
  }

  /** Converts a size to exact Bookmap units, saturating only overflow. */
  public int toSizeUnits(BigDecimal size) throws ValueConversionException {
    BigInteger units = exactUnits(size, sizeDecimals);
    if (units.compareTo(MAX_SIZE_UNITS) > 0) {
      return Integer.MAX_VALUE;
    }
    return units.intValue();
  }

  /** Converts a depth size to Bookmap units; zero means the level is gone. */
  public int toDepthSizeUnits(BigDecimal size) throws ValueConversionException {
    if (size != null && size.signum() == 0) {
      return 0;
    }
    return toSizeUnits(size);
  }

  private BigInteger exactUnits(BigDecimal value, int decimals) throws ValueConversionException {
    if (value == null || value.signum() <= 0) {
      throw new ValueConversionException(ValueConversionException.Reason.NON_POSITIVE);
    }
    try {
      return value
          .movePointRight(decimals)
          .setScale(0, RoundingMode.UNNECESSARY)
          .toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw new ValueConversionException(ValueConversionException.Reason.NON_INTEGRAL, exception);
    }
  }
}
```

- [ ] **Step 5: セッションの参照価格再構築を `withReferencePrice` にする**

`session/HyperliquidSession.java` の `onAssetContexts` 内、`sed` 後に次の形になっている箇所を置き換える。

置換前(`sed` 後の状態):

```java
      entry.setValue(
          Instrument.perpetual(
              current.symbol(), current.sizeDecimals(), assetContexts.markPrice(current.symbol())));
```

置換後:

```java
      entry.setValue(current.withReferencePrice(assetContexts.markPrice(current.symbol())));
```

(`coin()` への切り替えは Task 5 で行う。)

- [ ] **Step 6: コンパイルとテスト**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
```

期待: すべて PASS。`grep -rn 'PerpetualInstrument' src docs/development.md` が空であること(`docs/superpowers/` 内の履歴文書は対象外)。

- [ ] **Step 7: コミット**

```bash
git add -A src
git commit -m "refactor: unify perpetual and spot metadata in Instrument with a Market kind

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 2: 板のネイティブ単位を `long` にする

**Files:**
- Modify: `model/Instrument.java`(`toDepthPriceUnits` → `long`)
- Modify: `book/PriceBucketer.java`
- Modify: `book/DeltaOrderBook.java`
- Modify: `book/OrderBookSnapshotDiff.java`
- Test: `model/InstrumentTest.java`、`book/PriceBucketerTest.java`、`book/DeltaOrderBookTest.java`、`book/OrderBookSnapshotDiffTest.java`

**Interfaces:**
- Consumes: Task 1 の `Instrument.spot(...)`。
- Produces:
  - `long Instrument.toDepthPriceUnits(BigDecimal)`(上限は `Long.MAX_VALUE`)
  - `int PriceBucketer.bidBucket(long)` / `askBucket(long)` / `bucket(boolean, long)` — いずれも `throws ValueConversionException`(`DEPTH_PRICE_OUT_OF_RANGE` は bucket が `int` を超えたとき)
  - `long[] PriceBucketer.nativeRange(int bucket, boolean bid)`
  - `OrderBookSnapshotDiff.NormalizedBookSnapshot.bids()/asks()` は `SortedMap<Long, BigDecimal>`

- [ ] **Step 1: 失敗するテストを書く**

`InstrumentTest.java` の `convertsSizeAndPriceWithoutRounding` 内の行を `long` に変え、XAUT0 ケースを追加:

```java
    assertEquals(27123456L, instrument.toDepthPriceUnits(new BigDecimal("27123.456")));
```

```java
  /** Spot gold at 4378.7 with szDecimals 2 needs 4.4e9 native units, beyond int but within long. */
  @Test
  public void depthPriceUnitsExceedIntForHighPricedSpotPairs() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);

    assertEquals(4_378_700_000L, gold.toDepthPriceUnits(new BigDecimal("4378.7")));
  }
```

既存の `rejectsDepthPriceOutsideIntegerRange`(`2147483.648` で `DEPTH_PRICE_OUT_OF_RANGE` を期待)は `long` 化で通らなくなるので、次に置き換える(`int` 超は正常値、`long` 超だけが範囲外):

```java
  /** Only a value beyond long is out of range; int-sized overflow is now a legitimate result. */
  @Test
  public void rejectsDepthPriceOutsideLongRange() throws Exception {
    Instrument instrument = Instrument.perpetual("BTC", 3);

    assertEquals(2_147_483_648L, instrument.toDepthPriceUnits(new BigDecimal("2147483.648")));
    assertDepthConversionReason(
        instrument,
        new BigDecimal("9223372036854775.808"),
        ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
  }
```

`PriceBucketerTest.java`: `nativeRange` の期待値を `long[]` にし(`assertArrayEquals(new long[] {877840L, 877840L}, ...)` の形に、削除する `extremeNativeUnitsDoNotOverflow` 以外の 3 箇所)、`extremeNativeUnitsDoNotOverflow` を次に置き換え、テストメソッドに `throws Exception` を付ける(`bidBucket` 等が checked 例外を投げるため):

```java
  /** XAUT0/USDC: 4378.7 on the 1e-6 grid is 4.4e9 native units; the 0.1 tick maps it to 43787. */
  @Test
  public void highPricedSpotPairBucketsWithinIntAtTheDefaultTick() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    PriceBucketer bucketer = new PriceBucketer(gold, new BigDecimal("0.1"));

    assertEquals(100_000L, bucketer.ratio());
    assertEquals(43787, bucketer.bidBucket(4_378_700_000L));
    assertEquals(43787, bucketer.askBucket(4_378_700_000L));
    assertEquals(43788, bucketer.askBucket(4_378_700_001L));
    assertArrayEquals(new long[] {4_378_700_000L, 4_378_799_999L}, bucketer.nativeRange(43787, true));
    assertArrayEquals(new long[] {4_378_600_001L, 4_378_700_000L}, bucketer.nativeRange(43787, false));
  }

  /** Only a bucket beyond int is unsupported; the native units themselves may exceed int. */
  @Test
  public void bucketBeyondIntIsReportedAsOutOfRange() throws Exception {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    PriceBucketer identity = PriceBucketer.identity(gold);

    assertEquals(Integer.MAX_VALUE, identity.askBucket((long) Integer.MAX_VALUE));
    try {
      identity.bidBucket(4_378_700_000L);
      fail("bucket beyond int must be rejected");
    } catch (ValueConversionException expected) {
      assertEquals(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE, expected.reason());
    }
    PriceBucketer coarse = new PriceBucketer(gold, new BigDecimal("0.001"));
    assertEquals(Integer.MAX_VALUE, coarse.askBucket(Integer.MAX_VALUE * 1000L));
  }
```

`DeltaOrderBookTest.java` に追加(既存の `snapshot` / `levels` / `level` / `depth` ヘルパーを使う):

```java
  /** A spot pair whose native units exceed int is published as int buckets at its default tick. */
  @Test
  public void highPricedSpotSeedPublishesIntBucketsFromLongNativeUnits() {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    DeltaOrderBook book = new DeltaOrderBook(gold, new PriceBucketer(gold, new BigDecimal("0.1")));

    book.applySeed(snapshot(1L, levels(level("4378.7", "0.5")), levels(level("4378.8", "0.25"))));
    List<DepthUpdate> published = book.publishStaged();
    DeltaOrderBook.Result delta =
        book.applyDelta(snapshot(2L, levels(level("4378.65", "1")), levels()), true);

    assertEquals(Arrays.asList(depth(true, 43787, 50), depth(false, 43788, 25)), published);
    assertEquals(Collections.singletonList(depth(true, 43786, 100)), delta.updates());
  }
```

`OrderBookSnapshotDiffTest.java` に追加(既存の `snapshot` / `bids` / `asks` / `level` / `depth` / `valid` ヘルパーを使う):

```java
  /** A spot pair whose native units exceed int diffs correctly at its default tick. */
  @Test
  public void highPricedSpotSnapshotDiffsInIntBuckets() {
    Instrument gold = Instrument.spot("XAUT0/USDC", "@182", 2);
    OrderBookSnapshotDiff goldDiff =
        new OrderBookSnapshotDiff(gold, new PriceBucketer(gold, new BigDecimal("0.1")));

    SnapshotValidation validation =
        goldDiff.validate(snapshot(1, bids(level("4378.7", "0.5")), asks(level("4378.8", "0.25"))), 0);

    assertEquals(SnapshotValidation.Status.VALID, validation.status());
    assertEquals(
        Arrays.asList(depth(true, 43787, 50), depth(false, 43788, 25)),
        goldDiff.apply(validation.snapshot(), false));
  }
```

`Instrument` の import を各テストに追加する(Task 1 の `sed` で既に `Instrument` を import しているファイルは不要)。

- [ ] **Step 2: テストが失敗することを確認**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline compileTestJava
```

期待: `long` と `int` の不一致でコンパイルエラー。

- [ ] **Step 3: `Instrument.toDepthPriceUnits` を `long` にする**

```java
  private static final BigInteger MAX_DEPTH_PRICE_UNITS = BigInteger.valueOf(Long.MAX_VALUE);
```

```java
  /**
   * Converts a price to exact native depth units on the {@code 10^-priceDecimals} grid. The result
   * is a long so an 8-decimal spot grid still represents prices in the thousands; the bucketer
   * maps it onto the int Bookmap tick.
   */
  public long toDepthPriceUnits(BigDecimal price) throws ValueConversionException {
    BigInteger units = exactUnits(price, priceDecimals);
    if (units.compareTo(MAX_DEPTH_PRICE_UNITS) > 0) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return units.longValue();
  }
```

- [ ] **Step 4: `PriceBucketer` を `long` 入力にする**

`book/PriceBucketer.java` の `bucket` / `bidBucket` / `askBucket` / `nativeRange` を置き換える:

```java
  /** Returns the bucket of a native price for the given side. */
  public int bucket(boolean bid, long nativeUnits) throws ValueConversionException {
    return bid ? bidBucket(nativeUnits) : askBucket(nativeUnits);
  }

  /** Rounds a native bid price down to its bucket. */
  public int bidBucket(long nativeUnits) throws ValueConversionException {
    return toBucket(Math.floorDiv(nativeUnits, ratio));
  }

  /** Rounds a native ask price up to its bucket; {@code (n - 1) / r + 1} avoids overflow. */
  public int askBucket(long nativeUnits) throws ValueConversionException {
    if (nativeUnits <= 0L) {
      return toBucket(Math.floorDiv(nativeUnits, ratio));
    }
    return toBucket(Math.floorDiv(nativeUnits - 1L, ratio) + 1L);
  }

  /** Bookmap depth prices are int; a bucket beyond that cannot be published. */
  private static int toBucket(long bucket) throws ValueConversionException {
    if (bucket > Integer.MAX_VALUE) {
      throw new ValueConversionException(ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE);
    }
    return (int) bucket;
  }

  /** Returns the inclusive native-unit range {low, high} that maps onto one bucket. */
  public long[] nativeRange(int bucket, boolean bid) {
    long low = bid ? (long) bucket * ratio : ((long) bucket - 1L) * ratio + 1L;
    long high = bid ? ((long) bucket + 1L) * ratio - 1L : (long) bucket * ratio;
    return new long[] {Math.max(0L, low), high};
  }
```

クラス Javadoc に "Native units are long; buckets are the int Bookmap publishes." を一文足す。

- [ ] **Step 5: `DeltaOrderBook` のネイティブ板を `Long` キーにする**

変更点の一覧。`DepthUpdate` を「ネイティブ単位の一時表現」として使っていた箇所を専用の内部型に替える。

1. フィールド:

```java
  private final TreeMap<Long, Integer> publishedBids = new TreeMap<Long, Integer>();
  private final TreeMap<Long, Integer> publishedAsks = new TreeMap<Long, Integer>();
  private final TreeMap<Integer, Integer> publishedBidBuckets = new TreeMap<Integer, Integer>();
  private final TreeMap<Integer, Integer> publishedAskBuckets = new TreeMap<Integer, Integer>();
  private TreeMap<Long, Integer> stagedBids = new TreeMap<Long, Integer>();
  private TreeMap<Long, Integer> stagedAsks = new TreeMap<Long, Integer>();
```

`beginGeneration` の 2 行も `new TreeMap<Long, Integer>()` に。

2. 内部型を追加(`InvalidLevelException` の前):

```java
  /** One validated level on the native grid, with the bucket it maps onto. */
  private static final class NativeLevel {
    private final boolean bid;
    private final long price;
    private final int size;
    private final int bucket;

    private NativeLevel(boolean bid, long price, int size, int bucket) {
      this.bid = bid;
      this.price = price;
      this.size = size;
      this.bucket = bucket;
    }
  }
```

3. `normalize` / `normalizeSide` は `List<NativeLevel>` を返す。`normalizeSide` の変換部:

```java
      final long price;
      final int bucket;
      try {
        price = instrument.toDepthPriceUnits(level.price());
        bucket = bucketer.bucket(bid, price);
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
      out.add(new NativeLevel(bid, price, size, bucket));
```

4. `applySeed`:

```java
    TreeMap<Long, Integer> bids = new TreeMap<Long, Integer>();
    TreeMap<Long, Integer> asks = new TreeMap<Long, Integer>();
    for (NativeLevel level : levels) {
      (level.bid ? bids : asks).put(Long.valueOf(level.price), Integer.valueOf(level.size));
    }
```

5. `applyDelta` のループ:

```java
    for (NativeLevel level : levels) {
      TreeMap<Long, Integer> staged = level.bid ? stagedBids : stagedAsks;
      TreeMap<Long, Integer> published = level.bid ? publishedBids : publishedAsks;
      Long price = Long.valueOf(level.price);
      if (level.size == 0) {
        if (staged.remove(price) == null) {
          continue;
        }
        if (live) {
          published.remove(price);
        }
      } else {
        staged.put(price, Integer.valueOf(level.size));
        if (live) {
          published.put(price, Integer.valueOf(level.size));
        }
      }
      if (live) {
        (level.bid ? touchedBidBuckets : touchedAskBuckets).add(Integer.valueOf(level.bucket));
      }
    }
```

6. `appendBucketChanges` の引数 `published` は `TreeMap<Long, Integer>`。`bucketTotal`:

```java
  private int bucketTotal(TreeMap<Long, Integer> nativeLevels, int bucket, boolean bid) {
    long[] range = bucketer.nativeRange(bucket, bid);
    Collection<Integer> sizes =
        nativeLevels.subMap(Long.valueOf(range[0]), true, Long.valueOf(range[1]), true).values();
    long total = 0L;
    for (Integer size : sizes) {
      total += size.longValue();
    }
    return PriceBucketer.saturate(total);
  }
```

7. `bucketize`:

```java
  private TreeMap<Integer, Integer> bucketize(TreeMap<Long, Integer> nativeLevels, boolean bid) {
    TreeMap<Integer, Long> totals = new TreeMap<Integer, Long>();
    for (Map.Entry<Long, Integer> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketOfValidated(bid, level.getKey().longValue()));
      Long total = totals.get(bucket);
      totals.put(
          bucket,
          Long.valueOf((total == null ? 0L : total.longValue()) + level.getValue().longValue()));
    }
    TreeMap<Integer, Integer> buckets = new TreeMap<Integer, Integer>();
    for (Map.Entry<Integer, Long> entry : totals.entrySet()) {
      buckets.put(
          entry.getKey(), Integer.valueOf(PriceBucketer.saturate(entry.getValue().longValue())));
    }
    return buckets;
  }

  /** Every stored native price was bucketed once during normalization, so this cannot fail. */
  private int bucketOfValidated(boolean bid, long nativeUnits) {
    try {
      return bucketer.bucket(bid, nativeUnits);
    } catch (ValueConversionException impossible) {
      throw new IllegalStateException("stored native price cannot be bucketed", impossible);
    }
  }
```

8. `copyStagedToPublished` の引数型は bucket 側(`TreeMap<Integer, Integer>`)なので不変。`appendUpserts` / `appendVanished` は bucket map を受けるので不変。`import` に `com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate` は残る(bucket 出力で使用)。

- [ ] **Step 6: `OrderBookSnapshotDiff` のネイティブ板を `Long` キーにする**

1. `normalize(List<BookLevel> levels)` を `normalize(List<BookLevel> levels, boolean bid)` にし、`validate` 内の呼び出しを `normalize(snapshot.bids(), true)` / `normalize(snapshot.asks(), false)` にする。戻り値は `SortedMap<Long, BigDecimal>`。変換部:

```java
      final long price;
      try {
        price = instrument.toDepthPriceUnits(level.price());
        bucketer.bucket(bid, price);
      } catch (ValueConversionException exception) {
        if (exception.reason() == ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE) {
          throw new UnsupportedPriceException("Price cannot be represented as depth units");
        }
        throw new InvalidSnapshotException("Price is invalid");
      }
      try {
        instrument.toSizeUnits(level.size());
      } catch (ValueConversionException exception) {
        throw new InvalidSnapshotException("Size is invalid");
      }
      if (normalized.containsKey(Long.valueOf(price))) {
        throw new InvalidSnapshotException("Duplicate normalized price");
      }
      normalized.put(Long.valueOf(price), level.size());
```

2. `bucketize`:

```java
  private SortedMap<Integer, BigDecimal> bucketize(
      SortedMap<Long, BigDecimal> nativeLevels, boolean bid) {
    SortedMap<Integer, BigDecimal> buckets = new TreeMap<Integer, BigDecimal>();
    for (Map.Entry<Long, BigDecimal> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketOfValidated(bid, level.getKey().longValue()));
      BigDecimal total = buckets.get(bucket);
      buckets.put(bucket, total == null ? level.getValue() : total.add(level.getValue()));
    }
    return buckets;
  }

  /** Every normalized price was bucketed once during validation, so this cannot fail. */
  private int bucketOfValidated(boolean bid, long nativeUnits) {
    try {
      return bucketer.bucket(bid, nativeUnits);
    } catch (ValueConversionException impossible) {
      throw new IllegalStateException("normalized price cannot be bucketed", impossible);
    }
  }
```

3. `NormalizedBookSnapshot` のフィールド、コンストラクタ引数、`bids()` / `asks()`、`immutableCopy` の型を `SortedMap<Long, BigDecimal>` にする。Javadoc の "normalized to Bookmap depth units" は "normalized to native long depth units" に直す。

4. フィールド `bids` / `asks`(bucket 単位の基準線)は `SortedMap<Integer, BigDecimal>` のまま。

- [ ] **Step 7: テストを通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
```

期待: すべて PASS。特に既存の `unsupportedPriceAndInvalidSizeAreReported`(identity bucketer で 1e17 ネイティブ単位 → bucket 溢れ)と `rejectsPricesOutsideBookmapIntegerRangeWithoutChangingTheBaseline` が引き続き `UNSUPPORTED_PRICE` になること。

- [ ] **Step 8: コミット**

```bash
git add -A src
git commit -m "feat: keep native book prices in long so 8-decimal spot grids cannot overflow

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 3: `spotMeta` のパーサーと結合

**Files:**
- Modify: `parse/HyperliquidMetaParser.java`
- Modify: `src/test/.../TestMetadata.java`
- Test: `parse/HyperliquidMetaParserTest.java`

**Interfaces:**
- Consumes: `Instrument.spot(symbol, coin, sizeDecimals)`、`Instrument.perpetual(...)`。
- Produces:
  - `List<Instrument> HyperliquidMetaParser.parseSpotMeta(String json) throws ProtocolException`
  - `static List<Instrument> HyperliquidMetaParser.combine(List<Instrument> perpetuals, List<Instrument> spots) throws ProtocolException`
  - テスト用: `TestMetadata.spotMeta(String tokensJson, String universeJson)`、`TestMetadata.spotToken(int index, String name, int szDecimals)`、`TestMetadata.spotPair(String name, int baseIndex, int quoteIndex)`、`TestMetadata.spotMetaWithUsdcPairs(String... baseNames)`、`TestMetadata.emptySpotMeta()`

- [ ] **Step 1: `TestMetadata` にヘルパーを追加**

```java
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
```

クラス Javadoc を "Builds allPerpMetas and spotMeta responses and fastAssetCtxs frames for tests." に更新。

- [ ] **Step 2: 失敗するテストを書く**

`HyperliquidMetaParserTest.java` に追加。既存の `symbols(List<Instrument>)` ヘルパーを流用し、`coins` ヘルパーを追加する:

```java
  private static List<String> coins(List<Instrument> instruments) {
    List<String> result = new ArrayList<String>();
    for (Instrument instrument : instruments) {
      result.add(instrument.coin());
    }
    return result;
  }
```

```java
  /** Spot pairs display as BASE/QUOTE, subscribe by the universe name, and size by the base. */
  @Test
  public void parsesSpotPairsFromTokensAndUniverse() throws Exception {
    String json =
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8)
                + ","
                + TestMetadata.spotToken(1, "PURR", 0)
                + ","
                + TestMetadata.spotToken(2, "HFUN", 2)
                + ","
                + TestMetadata.spotToken(360, "USDT0", 2)
                + ","
                + TestMetadata.spotToken(400, "XAUT0", 2),
            TestMetadata.spotPair("PURR/USDC", 1, 0)
                + ","
                + TestMetadata.spotPair("@1", 2, 0)
                + ","
                + TestMetadata.spotPair("@209", 400, 360));

    List<Instrument> result = parser.parseSpotMeta(json);

    assertEquals(Arrays.asList("PURR/USDC", "HFUN/USDC", "XAUT0/USDT0"), symbols(result));
    assertEquals(Arrays.asList("PURR/USDC", "@1", "@209"), coins(result));
    assertEquals(Market.SPOT, result.get(0).market());
    assertEquals(8, result.get(0).priceDecimals());
    assertEquals(6, result.get(1).priceDecimals());
    assertNull(result.get(2).referencePrice());
  }

  /** An exchange with no spot pairs is a legitimate empty list, not a protocol error. */
  @Test
  public void emptySpotUniverseYieldsNoInstruments() throws Exception {
    assertTrue(parser.parseSpotMeta(TestMetadata.emptySpotMeta()).isEmpty());
    assertTrue(
        parser.parseSpotMeta(TestMetadata.spotMeta(TestMetadata.spotToken(0, "USDC", 8), ""))
            .isEmpty());
  }

  @Test
  public void rejectsMalformedSpotMeta() {
    assertSpotRejected("[]", "must be an object");
    assertSpotRejected("{\"universe\":[]}", "tokens must be an array");
    assertSpotRejected("{\"tokens\":[]}", "universe must be an array");
    assertSpotRejected(
        TestMetadata.spotMeta("", TestMetadata.spotPair("@1", 1, 0)), "unknown token index");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(0, "PURR", 0),
            ""),
        "duplicate index");
    assertSpotRejected(
        TestMetadata.spotMeta(TestMetadata.spotToken(0, "USDC", 9), ""),
        "szDecimals must be between 0 and 8");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            "{\"name\":\"@1\",\"tokens\":[1],\"index\":1}"),
        "two indices");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            TestMetadata.spotPair("@1", 1, 0) + "," + TestMetadata.spotPair("@1", 1, 0)),
        "duplicate name");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            TestMetadata.spotPair("@1", 1, 0) + "," + TestMetadata.spotPair("@2", 1, 0)),
        "duplicate pair");
    assertSpotRejected("not json", "invalid metadata JSON");
  }

  /** Perp and spot lists merge in order; a repeated symbol or coin is a protocol error. */
  @Test
  public void combineRejectsRepeatedSymbolsAndCoins() throws Exception {
    List<Instrument> perps = Arrays.asList(Instrument.perpetual("BTC", 5));
    List<Instrument> spots = Arrays.asList(Instrument.spot("HYPE/USDC", "@107", 2));

    assertEquals(
        Arrays.asList("BTC", "HYPE/USDC"), symbols(HyperliquidMetaParser.combine(perps, spots)));
    try {
      HyperliquidMetaParser.combine(perps, Arrays.asList(Instrument.spot("BTC", "@5", 2)));
      fail("repeated symbol must be rejected");
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("BTC"));
    }
    try {
      HyperliquidMetaParser.combine(perps, Arrays.asList(Instrument.spot("BTC/USDC", "BTC", 2)));
      fail("repeated coin must be rejected");
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("BTC"));
    }
  }

  private void assertSpotRejected(String json, String messageFragment) {
    try {
      parser.parseSpotMeta(json);
      fail("expected rejection: " + messageFragment);
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
    }
  }
```

import に `com.bookmap.plugins.layer0.hyperliquid.model.Market` を追加。

- [ ] **Step 3: 失敗を確認**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline compileTestJava
```

期待: `parseSpotMeta` / `combine` が未定義でコンパイルエラー。

- [ ] **Step 4: パーサーを実装**

`parse/HyperliquidMetaParser.java` に追加。import に `java.util.HashMap`、`java.util.Map` を足す。既存の `parseEntry` 内の `szDecimals` 検証は共通化した `requiredSizeDecimals(object, 6)` に置き換える。

```java
  /**
   * Parses a spotMeta response: {@code tokens} describe each token by index, {@code universe}
   * lists pairs as {@code [baseIndex, quoteIndex]}. The Bookmap symbol is {@code BASE/QUOTE}, the
   * coin is the pair's own name ({@code @index}, or {@code PURR/USDC}), and the size scale is the
   * base token's. An empty universe is legitimate and yields no instruments.
   */
  public List<Instrument> parseSpotMeta(String json) throws ProtocolException {
    try {
      JsonElement root = new JsonParser().parse(json);
      if (root == null || !root.isJsonObject()) {
        throw new ProtocolException("spotMeta response must be an object");
      }
      JsonElement tokens = root.getAsJsonObject().get("tokens");
      if (tokens == null || !tokens.isJsonArray()) {
        throw new ProtocolException("tokens must be an array");
      }
      JsonElement universe = root.getAsJsonObject().get("universe");
      if (universe == null || !universe.isJsonArray()) {
        throw new ProtocolException("universe must be an array");
      }
      Map<Integer, SpotToken> tokensByIndex = new HashMap<Integer, SpotToken>();
      for (JsonElement element : tokens.getAsJsonArray()) {
        SpotToken token = parseToken(element);
        if (tokensByIndex.put(Integer.valueOf(token.index), token) != null) {
          throw new ProtocolException("tokens contains duplicate index " + token.index);
        }
      }
      List<Instrument> instruments = new ArrayList<Instrument>();
      Set<String> coins = new HashSet<String>();
      Set<String> symbols = new HashSet<String>();
      for (JsonElement element : universe.getAsJsonArray()) {
        if (!element.isJsonObject()) {
          throw new ProtocolException("universe entry must be an object");
        }
        JsonObject pair = element.getAsJsonObject();
        String coin = requiredString(pair, "name");
        if (coin.trim().isEmpty()) {
          throw new ProtocolException("name must be non-blank");
        }
        JsonElement pairTokens = pair.get("tokens");
        if (pairTokens == null
            || !pairTokens.isJsonArray()
            || pairTokens.getAsJsonArray().size() != 2) {
          throw new ProtocolException("pair tokens must hold exactly two indices");
        }
        SpotToken base = resolveToken(tokensByIndex, pairTokens.getAsJsonArray().get(0));
        SpotToken quote = resolveToken(tokensByIndex, pairTokens.getAsJsonArray().get(1));
        String symbol = base.name + "/" + quote.name;
        if (!coins.add(coin)) {
          throw new ProtocolException("universe contains duplicate name " + coin);
        }
        if (!symbols.add(symbol)) {
          throw new ProtocolException("universe contains duplicate pair " + symbol);
        }
        instruments.add(Instrument.spot(symbol, coin, base.sizeDecimals));
      }
      return instruments;
    } catch (ProtocolException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ProtocolException("invalid metadata JSON", failure);
    }
  }

  /**
   * Concatenates perpetual and spot instruments, rejecting any symbol or coin that appears twice.
   * Perp names never contain a slash and spot coins are {@code @index}, so a collision means the
   * exchange changed its naming; failing login is safer than guessing which market a name means.
   */
  public static List<Instrument> combine(List<Instrument> perpetuals, List<Instrument> spots)
      throws ProtocolException {
    List<Instrument> combined = new ArrayList<Instrument>(perpetuals.size() + spots.size());
    combined.addAll(perpetuals);
    combined.addAll(spots);
    Set<String> symbols = new HashSet<String>();
    Set<String> coins = new HashSet<String>();
    for (Instrument instrument : combined) {
      if (!symbols.add(instrument.symbol())) {
        throw new ProtocolException("instrument symbol is not unique: " + instrument.symbol());
      }
      if (!coins.add(instrument.coin())) {
        throw new ProtocolException("instrument coin is not unique: " + instrument.coin());
      }
    }
    return combined;
  }

  private SpotToken parseToken(JsonElement element) throws ProtocolException {
    if (!element.isJsonObject()) {
      throw new ProtocolException("token entry must be an object");
    }
    JsonObject object = element.getAsJsonObject();
    String name = requiredString(object, "name");
    if (name.trim().isEmpty()) {
      throw new ProtocolException("token name must be non-blank");
    }
    return new SpotToken(
        requiredIndex(object.get("index"), "token index"),
        name,
        requiredSizeDecimals(object, Market.SPOT.maxDecimals()));
  }

  private SpotToken resolveToken(Map<Integer, SpotToken> tokensByIndex, JsonElement indexElement)
      throws ProtocolException {
    int index = requiredIndex(indexElement, "pair token index");
    SpotToken token = tokensByIndex.get(Integer.valueOf(index));
    if (token == null) {
      throw new ProtocolException("unknown token index " + index);
    }
    return token;
  }

  private int requiredIndex(JsonElement element, String field) throws ProtocolException {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      throw new ProtocolException(field + " must be an integer");
    }
    String raw = element.getAsJsonPrimitive().toString();
    if (!raw.matches("0|[1-9][0-9]{0,8}")) {
      throw new ProtocolException(field + " must be a non-negative integer");
    }
    return Integer.parseInt(raw);
  }

  private int requiredSizeDecimals(JsonObject object, int maxDecimals) throws ProtocolException {
    JsonElement sizeElement = object.get("szDecimals");
    if (sizeElement == null
        || !sizeElement.isJsonPrimitive()
        || !sizeElement.getAsJsonPrimitive().isNumber()) {
      throw new ProtocolException("szDecimals must be an integer");
    }
    String raw = sizeElement.getAsJsonPrimitive().toString();
    if (!raw.matches("[0-9]") || Integer.parseInt(raw) > maxDecimals) {
      throw new ProtocolException("szDecimals must be between 0 and " + maxDecimals);
    }
    return Integer.parseInt(raw);
  }

  private static final class SpotToken {
    private final int index;
    private final String name;
    private final int sizeDecimals;

    private SpotToken(int index, String name, int sizeDecimals) {
      this.index = index;
      this.name = name;
      this.sizeDecimals = sizeDecimals;
    }
  }
```

`parseEntry` の `szDecimals` 検証ブロック(`JsonElement sizeElement = ...` から `rawSizeDecimals` の検証まで)を `int sizeDecimals = requiredSizeDecimals(object, Market.PERPETUAL.maxDecimals());` に置き換え、`MetadataEntry` の生成で `sizeDecimals` を渡す。既存メッセージ "szDecimals must be between 0 and 6" は `maxDecimals` 経由で同文になる。クラス Javadoc を "Parses and validates Hyperliquid instrument metadata: every perp dex and the spot universe." に更新。import に `com.bookmap.plugins.layer0.hyperliquid.model.Market` を追加。

- [ ] **Step 5: テストを通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests '*HyperliquidMetaParserTest'
```

期待: PASS。

- [ ] **Step 6: コミット**

```bash
git add -A src
git commit -m "feat: parse spotMeta into BASE/QUOTE instruments keyed by their wire coin

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 4: コネクタが `allPerpMetas` と `spotMeta` を並行取得する

**Files:**
- Modify: `HyperliquidConnector.java`(フィールド、`startOnStateLane`、`completeMetadata`、`closeOnStateLane`)
- Modify: `src/test/.../FakeHyperliquidTransport.java`
- Test: `HyperliquidConnectorTest.java`

**Interfaces:**
- Consumes: `HyperliquidMetaParser.parseSpotMeta`、`HyperliquidMetaParser.combine`(Task 3)。
- Produces:
  - コネクタ: `Listener.onMetadata(List<Instrument>)` に perp + spot の結合リストが 1 回届く。
  - テスト用 fake: `completeMeta(int, String)` は allPerpMetas 応答を完了させる。その時点で spotMeta リクエストが未完了なら、先に `TestMetadata.emptySpotMeta()` を 200 で完了させる(perp 専用の既存テストはそのまま動く)。`completeSpotMeta(int, String)` は spotMeta 応答を明示的に完了させる(`completeMeta` の前に呼ぶ)。`failMeta(Throwable)` は allPerpMetas を失敗させる。`failSpotMeta(Throwable)` を追加。`httpBody()` は最初のリクエスト(allPerpMetas)の本文、`httpBodies()` は全本文。`pendingHttpCount()` は未完了・未取消のリクエスト数。

- [ ] **Step 1: `FakeHyperliquidTransport` を 2 リクエスト対応にする**

フィールド `httpBody`、`httpCallback`、`httpCallbacks`、`httpHandles` の代わりに、リクエストを 1 つの記録にまとめる。以下を適用する。

1. フィールド:

```java
  private final List<HttpRequest> httpRequests = new ArrayList<HttpRequest>();
```

(`httpUri`、`contentType`、`httpTimeoutMillis`、`httpCancelled` は残す。`httpBody`、`httpCallback`、`httpCallbacks`、`httpHandles` は削除。)

2. `postJson`:

```java
  @Override
  public Cancellable postJson(
      URI uri, String requestContentType, String body, long timeoutMillis, HttpCallback callback) {
    httpUri = uri;
    contentType = requestContentType;
    httpTimeoutMillis = timeoutMillis;
    TrackedHandle handle = new TrackedHandle(true);
    httpRequests.add(new HttpRequest(body, callback, handle));
    return handle;
  }
```

3. 参照系:

```java
  /** Returns the body of the first HTTP request, which is always the allPerpMetas request. */
  public String httpBody() {
    return httpRequests.isEmpty() ? null : httpRequests.get(0).body;
  }

  /** Returns every HTTP request body in the order posted. */
  public List<String> httpBodies() {
    List<String> bodies = new ArrayList<String>();
    for (HttpRequest request : httpRequests) {
      bodies.add(request.body);
    }
    return bodies;
  }

  /** Returns how many HTTP requests are neither completed nor cancelled. */
  public int pendingHttpCount() {
    int pending = 0;
    for (HttpRequest request : httpRequests) {
      if (!request.handle.completed && !request.handle.cancelled) {
        pending++;
      }
    }
    return pending;
  }
```

既存の `httpHandles` を走査しているメソッド(行 125 付近と 139 付近の集計)は `httpRequests` の `handle` を走査する形に書き換える(意味は不変)。

4. 完了操作:

```java
  public static final String PERP_META_REQUEST = "{\"type\":\"allPerpMetas\"}";
  public static final String SPOT_META_REQUEST = "{\"type\":\"spotMeta\"}";

  /**
   * Completes the allPerpMetas request. A still-pending spotMeta request is completed first with
   * an empty spot universe, so perp-only tests need no spot fixture.
   */
  public void completeMeta(int status, String body) {
    HttpRequest spot = pending(SPOT_META_REQUEST);
    if (spot != null) {
      spot.complete(200, TestMetadata.emptySpotMeta(), null);
    }
    requirePending(PERP_META_REQUEST).complete(status, body, null);
  }

  /** Completes the spotMeta request; call before {@link #completeMeta}. */
  public void completeSpotMeta(int status, String body) {
    requirePending(SPOT_META_REQUEST).complete(status, body, null);
  }

  public void failMeta(Throwable failure) {
    requirePending(PERP_META_REQUEST).complete(0, null, failure);
  }

  public void failSpotMeta(Throwable failure) {
    requirePending(SPOT_META_REQUEST).complete(0, null, failure);
  }

  /** Re-delivers a response to every HTTP callback ever posted, completed or cancelled. */
  public void lateCompleteMeta(int status, String body) {
    for (HttpRequest request : httpRequests) {
      request.callback.onComplete(status, body, null);
    }
  }

  private HttpRequest pending(String body) {
    for (HttpRequest request : httpRequests) {
      if (request.body.equals(body) && !request.handle.completed && !request.handle.cancelled) {
        return request;
      }
    }
    return null;
  }

  private HttpRequest requirePending(String body) {
    HttpRequest request = pending(body);
    if (request == null) {
      throw new AssertionError("no pending HTTP request with body " + body);
    }
    return request;
  }

  private static final class HttpRequest {
    private final String body;
    private final HttpCallback callback;
    private final TrackedHandle handle;

    private HttpRequest(String body, HttpCallback callback, TrackedHandle handle) {
      this.body = body;
      this.callback = callback;
      this.handle = handle;
    }

    private void complete(int status, String responseBody, Throwable failure) {
      handle.completed = true;
      callback.onComplete(status, responseBody, failure);
    }
  }
```

注意: `closeCancelsOutstandingMetadataAndSuppressesLateCallback`(コネクタテスト)は `close()` 後に `completeMeta` を呼ぶ。取り消し済みリクエストは `pending` に該当しないので `requirePending` が失敗する。このテストは Step 2 で `lateCompleteMeta` を使う形に直す。`HyperliquidSessionLifecycleTest` の `closeDuringMetadataCompletionSuppressesLateLoginAndConnect`(行 718 付近)も `session.close()` 後に `completeMeta` を呼ぶので、同じく `lateCompleteMeta(200, ...)` に置き換える(アサーションは不変)。同様に `AssetContextFeedTest` の `completeMeta` 呼び出しは、feed 接続がメタデータを要求しない(`startWithoutMetadata`)場合に該当リクエストがない。`grep -n completeMeta src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeedTest.java` で確認し、実際に allPerpMetas を発行していない文脈なら `lateCompleteMeta` に置き換える。発行している文脈ならそのままでよい。

- [ ] **Step 2: 失敗するテストを書く**

`HyperliquidConnectorTest.java`:

`startPostsMainnetMetadataBeforeOpeningTheWebSocket` の本文アサーションを置き換える:

```java
    assertEquals(
        Arrays.asList("{\"type\":\"allPerpMetas\"}", "{\"type\":\"spotMeta\"}"),
        fixture.transport.httpBodies());
    assertEquals(2, fixture.transport.pendingHttpCount());
```

`closeCancelsOutstandingMetadataAndSuppressesLateCallback` の `completeMeta` を `lateCompleteMeta(200, validMeta("BTC"))` に変え、`assertEquals(0, fixture.transport.pendingHttpCount());` を足す。

追加テスト:

```java
  /** Login waits for both metadata responses and hands the listener one combined list. */
  @Test
  public void spotMetadataJoinsPerpMetadataBeforeConnecting() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
    assertTrue(fixture.listener.instrumentNames.isEmpty());
    assertTrue(fixture.transport.connectCalls().isEmpty());

    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals(Arrays.asList("BTC", "HYPE/USDC"), fixture.listener.instrumentNames);
    assertEquals(1, fixture.transport.connectCalls().size());
  }

  /** The response order does not matter: perp first, then spot, also logs in exactly once. */
  @Test
  public void perpMetadataMayArriveBeforeSpotMetadata() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.requirePerpThenSpot();
    fixture.transport.completeMetaOnly(200, validMeta("BTC"));
    assertTrue(fixture.listener.instrumentNames.isEmpty());
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));

    assertEquals(Arrays.asList("BTC", "HYPE/USDC"), fixture.listener.instrumentNames);
    assertEquals(1, fixture.transport.connectCalls().size());
  }

  /** A failed spotMeta fails login, cancels the perp request, and never connects. */
  @Test
  public void failedSpotMetadataFailsLoginAndCancelsThePerpRequest() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeSpotMeta(503, "unavailable");

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(TransportFailure.Kind.REMOTE, fixture.listener.initialFailures.get(0).kind());
    assertTrue(fixture.transport.httpCancelled());
    assertEquals(0, fixture.transport.pendingHttpCount());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  /** Invalid spot JSON is a protocol failure, and a network failure is classified as such. */
  @Test
  public void invalidOrUnreachableSpotMetadataDoesNotConnect() {
    Fixture invalid = new Fixture();
    invalid.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    invalid.transport.completeSpotMeta(200, "[]");
    assertEquals(TransportFailure.Kind.PROTOCOL, invalid.listener.initialFailures.get(0).kind());
    assertTrue(invalid.transport.connectCalls().isEmpty());

    Fixture unreachable = new Fixture();
    unreachable.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    unreachable.transport.failSpotMeta(new IllegalStateException("spot unavailable"));
    assertEquals(
        TransportFailure.Kind.NETWORK, unreachable.listener.initialFailures.get(0).kind());
    assertEquals(1, unreachable.listener.initialFailures.size());
    assertTrue(unreachable.transport.connectCalls().isEmpty());
  }

  /** A failed perp request also cancels the pending spot request. */
  @Test
  public void failedPerpMetadataCancelsThePendingSpotRequest() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.failMeta(new IllegalStateException("perp unavailable"));

    assertEquals(1, fixture.listener.initialFailures.size());
    assertEquals(0, fixture.transport.pendingHttpCount());
    assertTrue(fixture.transport.httpCancelled());
  }

  /** A symbol that both lists claim is a protocol failure rather than a silent overwrite. */
  @Test
  public void collidingPerpAndSpotSymbolsFailLogin() {
    Fixture fixture = new Fixture();

    fixture.connector.start(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
    fixture.transport.completeSpotMeta(
        200,
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "X", 2),
            TestMetadata.spotPair("BTC", 1, 0)));
    fixture.transport.completeMeta(200, validMeta("BTC"));

    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.listener.initialFailures.get(0).kind());
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }
```

`perpMetadataMayArriveBeforeSpotMetadata` が使う fake の 2 メソッドを `FakeHyperliquidTransport` に追加する:

```java
  /** Completes only the allPerpMetas request, leaving a pending spotMeta request untouched. */
  public void completeMetaOnly(int status, String body) {
    requirePending(PERP_META_REQUEST).complete(status, body, null);
  }

  /** Asserts both metadata requests are pending, so a test can drive their order explicitly. */
  public void requirePerpThenSpot() {
    requirePending(PERP_META_REQUEST);
    requirePending(SPOT_META_REQUEST);
  }
```

`collidingPerpAndSpotSymbolsFailLogin` では、spot 側は `Instrument.spot("X/USDC", "BTC", 2)` となり(symbol は `X/USDC`、coin は `BTC`)、`combine` の coin 重複で PROTOCOL 失敗になる。

import に `com.bookmap.plugins.layer0.hyperliquid.TestMetadata` があることを確認(既存の `validMeta` が使っている)。

- [ ] **Step 3: 失敗を確認**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidConnectorTest'
```

期待: `spotMetadataJoinsPerpMetadataBeforeConnecting` などが `no pending HTTP request with body {"type":"spotMeta"}` で FAIL。

- [ ] **Step 4: コネクタを実装**

`HyperliquidConnector.java`:

1. 定数とフィールド(`metadataRequest` を置き換える):

```java
  static final String PERP_METADATA_REQUEST_JSON = "{\"type\":\"allPerpMetas\"}";
  static final String SPOT_METADATA_REQUEST_JSON = "{\"type\":\"spotMeta\"}";
```

```java
  private HyperliquidTransport.Cancellable perpMetadataRequest;
  private HyperliquidTransport.Cancellable spotMetadataRequest;
  private List<Instrument> perpInstruments;
  private List<Instrument> spotInstruments;
```

2. `startOnStateLane` の `try` ブロック内、`transport.start();` の後:

```java
      perpMetadataRequest = postMetadata(PERP_METADATA_REQUEST_JSON, true);
      spotMetadataRequest = postMetadata(SPOT_METADATA_REQUEST_JSON, false);
```

```java
  private HyperliquidTransport.Cancellable postMetadata(String requestJson, final boolean perp) {
    return transport.postJson(
        profile.infoUri(),
        "application/json",
        requestJson,
        METADATA_TIMEOUT_MILLIS,
        new HyperliquidTransport.HttpCallback() {
          @Override
          public void onComplete(final int statusCode, final String body, final Throwable failure) {
            stateSubmitter.accept(
                new Runnable() {
                  @Override
                  public void run() {
                    completeMetadata(perp, statusCode, body, failure);
                  }
                });
          }
        });
  }
```

`startOnStateLane` の `catch` は従来どおり `reportInitialFailure(classify(failure, NETWORK))` だが、1 本目の `postJson` が成功して 2 本目が例外を投げた場合に 1 本目が残るので、`catch` 内の先頭で `cancelMetadataRequests();` を呼ぶ。

3. `completeMetadata` を置き換える:

```java
  /**
   * Both metadata responses must arrive before login proceeds. The first failure of either request
   * fails login and cancels the other; a late callback for a request no longer tracked is ignored.
   */
  private void completeMetadata(boolean perp, int statusCode, String body, Throwable failure) {
    if (closed || (perp ? perpMetadataRequest : spotMetadataRequest) == null) {
      return;
    }
    if (perp) {
      perpMetadataRequest = null;
    } else {
      spotMetadataRequest = null;
    }
    if (failure != null) {
      failMetadata(classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    if (statusCode < 200 || statusCode >= 300) {
      failMetadata(
          new TransportFailure(
              TransportFailure.Kind.REMOTE,
              "metadata request returned HTTP " + statusCode,
              null));
      return;
    }
    try {
      if (perp) {
        perpInstruments = metaParser.parseAllPerpMetas(body);
      } else {
        spotInstruments = metaParser.parseSpotMeta(body);
      }
      if (perpInstruments == null || spotInstruments == null) {
        return;
      }
      List<Instrument> combined =
          HyperliquidMetaParser.combine(perpInstruments, spotInstruments);
      perpInstruments = null;
      spotInstruments = null;
      listener.onMetadata(combined);
    } catch (ProtocolException failureException) {
      failMetadata(classify(failureException, TransportFailure.Kind.PROTOCOL));
      return;
    }
    attemptConnection(true);
  }

  private void failMetadata(TransportFailure failure) {
    cancelMetadataRequests();
    perpInstruments = null;
    spotInstruments = null;
    reportInitialFailure(failure);
  }

  private void cancelMetadataRequests() {
    cancel(perpMetadataRequest);
    perpMetadataRequest = null;
    cancel(spotMetadataRequest);
    spotMetadataRequest = null;
  }
```

4. `closeOnStateLane` の `cancel(metadataRequest); metadataRequest = null;` を `cancelMetadataRequests();` に置き換える。

5. `grep -n metadataRequest src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java` が `perpMetadataRequest` / `spotMetadataRequest` 以外を返さないことを確認。

- [ ] **Step 5: テストを通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
```

期待: 全 PASS(既存のセッション・E2E テストは fake の自動補完で動く)。

- [ ] **Step 6: コミット**

```bash
git add -A src
git commit -m "feat: fetch allPerpMetas and spotMeta in parallel and fail login if either fails

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 5: セッションと Provider がスポットを coin で購読し、type を market と照合する

**Files:**
- Modify: `session/SubscriptionRecord.java`
- Modify: `session/HyperliquidSession.java`
- Modify: `Provider.java`
- Test: `session/HyperliquidSessionSubscriptionTest.java`、`session/HyperliquidSessionAssetContextTest.java`、`ProviderTest.java`

**Interfaces:**
- Consumes: `Instrument.coin()` / `market()` / `withReferencePrice(...)`、fake の `completeSpotMeta`、`TestMetadata.spotMetaWithUsdcPairs`。
- Produces:
  - `String SubscriptionRecord.coin()`(`records` のキー)
  - `HyperliquidSession.subscribe(symbol, exchange, type, tick)` は `type` が `instrument.market().bookmapType()` と一致しないと `onInstrumentNotFound`
  - Provider の `SubscribeInfo` / `InstrumentInfo` の type は `instrument.market().bookmapType()`

- [ ] **Step 1: 失敗するテストを書く**

`HyperliquidSessionSubscriptionTest.java` の `Fixture` に追加:

```java
    /** Logs in with BTC (perp) and HYPE/USDC (spot, coin @1). */
    void loginWithSpot() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC")));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }
```

テスト追加:

```java
  /** A spot symbol subscribes by its wire coin, and frames for that coin reach its alias. */
  @Test
  public void spotSymbolSubscribesByCoinAndPublishesUnderItsSymbol() {
    Fixture fixture = new Fixture();
    fixture.loginWithSpot();
    fixture.sink.events().clear();

    fixture.session.subscribe("HYPE/USDC", "", "SPOT");
    fixture.drain();
    fixture.completeSubscriptionSends();
    fixture.receive(book("@1", 1L, "82.940000", "1.5"));
    fixture.receive(ackFor("@1", SubscriptionType.L2_BOOK));
    fixture.receive(ackFor("@1", SubscriptionType.TRADES));
    fixture.drain();

    List<String> sent = fixture.transport.socket().successfulSendBodies();
    assertTrue(sent.toString(), sent.toString().contains("\"coin\":\"@1\""));
    assertFalse(sent.toString(), sent.toString().contains("HYPE/USDC"));
    assertEquals(Collections.singletonList("HYPE/USDC"), fixture.sink.addedAliases());
    assertTrue(fixture.sink.events().toString(), fixture.sink.events().contains("depth:HYPE/USDC:82940000:150"));
  }

  /** The Bookmap type must match the instrument's market; a mismatch is not found. */
  @Test
  public void typeMismatchIsNotFoundForBothMarkets() {
    Fixture fixture = new Fixture();
    fixture.loginWithSpot();
    fixture.sink.events().clear();

    fixture.session.subscribe("HYPE/USDC", "", "PERPETUAL");
    fixture.session.subscribe("BTC", "", "SPOT");
    fixture.drain();

    assertEquals(Arrays.asList("not-found:HYPE/USDC", "not-found:BTC"), fixture.sink.events());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  /** Unsubscribing by the BASE/QUOTE alias finds the record that is keyed by its coin. */
  @Test
  public void spotUnsubscribeByAliasReleasesBothSlots() {
    Fixture fixture = new Fixture();
    fixture.loginWithSpot();

    fixture.session.subscribe("HYPE/USDC", "", "SPOT");
    fixture.drain();
    fixture.completeSubscriptionSends();
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());

    fixture.session.unsubscribe("HYPE/USDC");
    fixture.drain();

    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }
```

`book(String coin, long time, String price, String size)` と `ackFor(String coin, SubscriptionType type)` はこのテストクラス既存の static ヘルパー(行 661〜675 付近)で、追加は不要。`sink.addedAliases()` が無ければ `RecordingSessionSink` に `List<String> addedAliases()` アクセサを追加する(フィールド `addedAliases` は既に存在)。depth イベントの期待値は `82.94` × 10^6 = `82940000`(spot HYPE は szDecimals 2 → priceDecimals 6、identity tick なので bucket = native)と size `1.5` × 100 = `150`。

`HyperliquidSessionAssetContextTest.java` に追加(フィクスチャの login 手順は既存のものを確認し、`completeMeta` の直前に `completeSpotMeta` を差し込む `loginWithSpot()` を同様に追加する):

```java
  /** The fastAssetCtxs entry for the spot coin becomes the reference price of BASE/QUOTE. */
  @Test
  public void spotMarkPriceIsLookedUpByCoin() {
    Fixture fixture = new Fixture();
    fixture.loginWithSpot();

    fixture.receive(TestMetadata.assetContextsFrame("{\"@1\":{\"markPx\":\"82.94\"}}"));

    assertEquals("82.94", fixture.sink.lastReferencePrice("HYPE/USDC"));
    assertNull(fixture.sink.lastReferencePrice("@1"));
  }
```

このフィクスチャの既存 `Fixture()` コンストラクタが `HYPE` perp でログインしているなら、`loginWithSpot()` は `HYPE` の代わりに `BTC` perp + `HYPE/USDC` spot でログインするヘルパーとして別に定義する(コンストラクタ内でログインしているなら、新しいテスト用に `Fixture(boolean withSpot)` を作る)。

`ProviderTest.java` に追加(既存の `factory.sink.onKnownInstruments(...)` を使うテストの形に合わせる。行 129〜141 付近の既存テストが参考):

```java
  /** Spot instruments are listed and added with the SPOT type and their BASE/QUOTE alias. */
  @Test
  public void spotInstrumentsUseTheSpotTypeInBookmap() {
    FakeSessionFactory factory = new FakeSessionFactory();
    Provider provider = new Provider(factory);
    Instrument hype = Instrument.spot("HYPE/USDC", "@107", 2);
    factory.sink.onKnownInstruments(Arrays.asList(Instrument.perpetual("BTC", 5), hype));
    RecordingInstrumentListener listener = new RecordingInstrumentListener();
    provider.addListener(listener);

    factory.sink.onInstrumentAdded("HYPE/USDC", hype, new BigDecimal("0.001"));

    assertEquals(
        Arrays.asList(
            new SubscribeInfo("BTC", "", "PERPETUAL"), new SubscribeInfo("HYPE/USDC", "", "SPOT")),
        provider.getSupportedFeatures().knownInstruments);
    assertEquals("HYPE/USDC", listener.alias);
    assertEquals("SPOT", listener.instrument.type);
    assertEquals("HYPE/USDC", listener.instrument.symbol);
  }
```

`FakeSessionFactory` と `RecordingInstrumentListener`(フィールド `alias`、`instrument`)は `ProviderTest` 既存の内部クラス。`SubscribeInfo` の比較は既存テスト `preservesBookmapInstrumentMetadataAndDefensiveKnownSnapshot` と同じ形。

- [ ] **Step 2: 失敗を確認**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*HyperliquidSessionSubscriptionTest' --tests '*HyperliquidSessionAssetContextTest' --tests '*ProviderTest'
```

期待: `spotSymbolSubscribesByCoin...`(type `SPOT` が not-found になる)、`spotMarkPriceIsLookedUpByCoin`、`spotInstrumentsUseTheSpotTypeInBookmap` が FAIL。

- [ ] **Step 3: `SubscriptionRecord` を coin ベースにする**

```java
    l2BookKey = new SubscriptionKey(instrument.coin(), SubscriptionType.L2_BOOK, l2BookParameters);
    tradesKey = new SubscriptionKey(instrument.coin(), SubscriptionType.TRADES);
```

```java
  /** Returns the exchange coin this record is keyed by in the session and on the wire. */
  public String coin() {
    return instrument.coin();
  }
```

- [ ] **Step 4: `HyperliquidSession` を変更する**

1. `handleSubscribe` の先頭を置き換える:

```java
    if (symbol == null || !metadataReceived) {
      sink.onInstrumentNotFound(symbol, exchange, type);
      return;
    }
    Instrument instrument = SymbolLookup.resolve(instruments, symbol);
    if (instrument == null) {
      sink.onDiagnostic("no unique instrument matches " + symbol);
      sink.onInstrumentNotFound(symbol, exchange, type);
      return;
    }
    if (!instrument.market().bookmapType().equals(type)) {
      sink.onInstrumentNotFound(symbol, exchange, type);
      return;
    }
    final String coin = instrument.coin();
```

2. `record.instrument().symbol()` を `record.coin()` に置き換える(5 箇所: `handleUnsubscribe`、`handleSubscriptionError`、`handleBook` の UNSUPPORTED_PRICE、`acceptDeltaResult`、`handleAcknowledgementTimeout`):

```bash
F=src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java
grep -c 'record.instrument().symbol()' $F   # 5 expected
sed -i 's/record\.instrument()\.symbol()/record.coin()/g' $F
grep -c 'record.coin()' $F   # 5 expected
```

3. `onAssetContexts` の再構築を coin にする:

```java
      entry.setValue(current.withReferencePrice(assetContexts.markPrice(current.coin())));
```

4. `shouldRepublish(Set<String> changed)` の「一覧にある銘柄か」判定を coin で行う。フィールドに `private final Set<String> knownCoins = new HashSet<String>();` を追加し、`onMetadata` で `knownCoins.clear(); ... knownCoins.add(instrument.coin());` を行い、判定を `knownCoins.contains(symbol)` にする(ループ変数名は `coin` に改名)。

5. Javadoc の "perpetual subscription" 表現を "subscription" に直す(`subscribe` の 2 メソッド)。

- [ ] **Step 5: `Provider` を変更する**

`ProviderSessionSink.onKnownInstruments`:

```java
            subscribeInfo.add(
                new SubscribeInfo(instrument.symbol(), "", instrument.market().bookmapType()));
```

`onInstrumentAdded` の `InstrumentInfo` 生成で `"PERPETUAL"` を `instrument.market().bookmapType()` にする。クラス Javadoc を "Bookmap Layer 0 provider for Hyperliquid perpetual and spot market data." にする。

- [ ] **Step 6: テストを通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test
```

期待: 全 PASS。`notFoundAndDuplicateRequestsDoNotReserveOrSend`(`BTC` を `SPOT` で要求 → `not-found:BTC`、追加 diagnostic なし)がそのまま通ること。

- [ ] **Step 7: コミット**

```bash
git add -A src
git commit -m "feat: subscribe spot pairs by their wire coin and match the Bookmap type to the market

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 6: エンドツーエンドでスポット銘柄が流れる

**Files:**
- Test: `ProviderEndToEndTest.java`

**Interfaces:**
- Consumes: Task 3〜5 のすべて。fake の `completeSpotMeta`、`TestMetadata.spotMetaWithUsdcPairs`。

- [ ] **Step 1: フィクスチャにヘルパーを追加**

`ProviderEndToEndTest.Fixture` に追加(`loginWithMetadata(HyperliquidEnvironment, String...)` の隣):

```java
    /** Logs in on Mainnet with the given perp symbols plus HYPE/USDC (spot, coin @1). */
    private void loginWithSpotMetadata(String... perpSymbols) {
      provider.login(mainnetLogin());
      drain();
      transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
      transport.completeMeta(200, metadata(perpSymbols));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    private void subscribeSpot(String symbol) {
      provider.subscribe(new SubscribeInfo(symbol, "", "SPOT"));
      drain();
    }
```

- [ ] **Step 2: テストを書く**

```java
  /** A spot pair lists as BASE/QUOTE, subscribes as @index, and prices from its own mark price. */
  @Test
  public void spotInstrumentFlowsEndToEnd() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSpotMetadata("BTC");
    fixture.frame(TestMetadata.assetContextsFrame("{\"@1\":{\"markPx\":\"82.94\"}}"));
    fixture.drain();

    fixture.subscribeSpot("HYPE/USDC");
    fixture.completeSends();
    fixture.ack("@1", "l2Book");
    fixture.ack("@1", "trades");
    fixture.book("@1", 1L, "82.939", "1.5", "82.941", "2.5");
    fixture.trade("@1", "B", "82.941", "1.0", 2L, 9L);
    fixture.drain();

    assertEquals(Collections.singletonList("HYPE/USDC"), fixture.instruments.added);
    assertEquals("SPOT", fixture.instruments.lastInfo.type);
    assertEquals(0.001d, fixture.instruments.lastInfo.pips, 1e-12d);
    String sent = fixture.transport.socket().successfulSendBodies().toString();
    assertTrue(sent, sent.contains("\"coin\":\"@1\""));
    assertFalse(sent, sent.contains("HYPE/USDC"));
    assertFalse(fixture.data.depths.toString(), fixture.data.depths.isEmpty());
    assertFalse(fixture.data.trades.toString(), fixture.data.trades.isEmpty());
    fixture.closeTwice();
    fixture.assertClosed();
  }

  /** Requesting a spot symbol under the PERPETUAL type is not found and reserves nothing. */
  @Test
  public void spotSymbolUnderPerpetualTypeIsNotFound() {
    Fixture fixture = new Fixture(budget(4, 20, 20, 4));
    fixture.loginWithSpotMetadata("BTC");

    fixture.subscribe("HYPE/USDC");
    fixture.completeSends();

    assertTrue(fixture.instruments.added.isEmpty());
    assertFalse(fixture.instruments.notFound.isEmpty());
    fixture.closeTwice();
    fixture.assertClosed();
  }
```

`fixture.instruments.notFound` が無い場合は `RecordingInstrument` の `onInstrumentNotFound` が記録するフィールド名を `grep -n 'onInstrumentNotFound' -A3 src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java` で確認して合わせる。無ければ `List<String> notFound` を追加して記録する。

期待する tick: HYPE spot は `szDecimals` 2 → `priceDecimals` 6、参照価格 82.94 は 2 桁の整数部 → 5 有効数字の刻みは 0.001。`TickSizePlan.defaultTick` は 0.001 を返す。

- [ ] **Step 3: テストを通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply test --tests '*ProviderEndToEndTest'
```

期待: PASS。失敗した場合は購読 JSON(`successfulSendBodies`)と `trace` を出力して、`coin` が `@1` になっているか、`ack` の coin と一致しているかを確認する。

- [ ] **Step 4: コミット**

```bash
git add -A src
git commit -m "test: cover a spot pair end to end through the provider

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

### Task 7: ドキュメントと品質ゲート

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/superpowers/specs/2026-09-10-spot-market-support-design.md`(状態行のみ)

- [ ] **Step 1: README を更新**

1. 冒頭の段落 "Read-only Bookmap Layer 0 market data for Hyperliquid perpetuals, including HIP-3 builder-deployed markets." を次にする:

```markdown
Read-only Bookmap Layer 0 market data for Hyperliquid perpetuals and spot pairs, including HIP-3
builder-deployed markets. Every live perpetual on every perp dex and every spot pair is listed and
available to subscribe to; the adapter publishes aggregate L2 order books and trades for the
instruments you subscribe to. It never requests credentials and never sends orders.
```

2. "HIP-3 markets keep their fully qualified `dex:coin` names, for example `xyz:CL`." の直後に追加:

```markdown
Spot pairs are listed as `BASE/QUOTE` under the `SPOT` type, for example `HYPE/USDC` or
`XAUT0/USDT0`. Names are the HyperCore token names, so the pair the Hyperliquid app shows as
`BTC/USDC` appears here as `UBTC/USDC`. On the wire the adapter subscribes by the exchange's own
pair id (`@107` for HYPE/USDC); that id never appears in Bookmap.
```

3. Scope 節:

```markdown
Supported: `PERPETUAL` subscriptions for every live perpetual on every perp dex, HIP-3 markets
included, and `SPOT` subscriptions for every spot pair.

Not supported: historical data, account data, credentials, order entry, and gap filling. Order
APIs fail closed with a read-only system message.
```

(現行の 2 文の改行位置は `git diff` で確認し、上記の意味になるよう置き換える。)

4. Limits 節の Testnet 行を置き換え、1 項目を追加:

```markdown
- On Testnet the instrument list holds roughly 630 perpetuals across about 200 perp dexes and
  roughly 1,300 spot pairs, most of them throwaway markets deployed by other developers.
- An instrument whose mark price has not arrived yet lists its full-precision grid as the default
  tick. For a high-priced spot pair that grid can exceed Bookmap's integer price range, and the
  subscription fails with an unsupported-price message until a mark price arrives; resubscribe
  then.
```

- [ ] **Step 2: `docs/development.md` を更新**

`| \`hyperliquid.model\` | Immutable value and domain types |` を次にする:

```markdown
| `hyperliquid.model` | Immutable value and domain types, including the perpetual/spot `Instrument` |
```

- [ ] **Step 3: 仕様の状態行を更新**

`docs/superpowers/specs/2026-09-10-spot-market-support-design.md` の `- 状態: 人間の設計承認済み、spec-review 待ち` を `- 状態: spec-review READY、人間の承認済み、実装済み` にする。

- [ ] **Step 4: 品質ゲートをすべて通す**

```bash
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline clean build
git diff --check
grep -rn 'PerpetualInstrument\|"PERPETUAL"' src/main/java   # Provider/Session に "PERPETUAL" 直書きが残っていないこと(Market enum 内の定義のみ)
```

期待: `BUILD SUCCESSFUL`、`build/libs/hyperliquid-adapter-1.3.0.jar` が生成される。SpotBugs の警告が出た場合は `build/reports/spotbugs/main.html` を確認し、指摘箇所を直す(抑制アノテーションではなくコードを直す)。

- [ ] **Step 5: コミット**

```bash
git add README.md docs/development.md docs/superpowers/specs/2026-09-10-spot-market-support-design.md
git commit -m "docs: describe spot market support and its naming

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Hfg7WBT3CyTQYUjcbVbT24"
```

---

## 実装後に実機で確認すべき事項(計画外・人間が行う)

- Bookmap の Subscribe ダイアログで `HYPE/USDC` を type `SPOT` で購読でき、ヒートマップと約定が出ること
- `/` を含む alias を保存したワークスペースを再起動後に復元できること
- `XAUT0/USDC` を既定 tick で購読できること
- Borsa / Hyperdash 選択時にスポットの板・約定が出ること
- base トークンの `szDecimals` が 8 の銘柄で数量表示が飽和しないか(既存契約どおり 21 付近で飽和する)
