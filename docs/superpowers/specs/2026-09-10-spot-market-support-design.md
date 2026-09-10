# スポット市場サポート — 設計書

- 作成日: 2026-09-10
- 状態: spec-review READY、人間の承認済み、実装済み
- 起点: ローカル main `f27aa0a`(1.3.0 リリース)
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

現行アダプターは perp(HIP-3 を含む全 perp dex)だけを Bookmap の銘柄一覧に載せる。
Hyperliquid のスポット市場(HIP-1 トークンの `BASE/QUOTE` 板)は一覧に現れず、README でも
「Not supported」と明記している。本設計はスポット市場を perp と同じ品質で購読可能にする。

要求の要約(ユーザー決定済み):

- Bookmap 上の銘柄名は `BASE/QUOTE`(例 `HYPE/USDC`、`XAUT0/USDT0`)。取引所の生の名前
  (`@107`)は内部の購読キーとしてだけ使う
- 板のネイティブ価格単位を `int` から `long` にし、8 桁格子で `int` を溢れる銘柄
  (Mainnet では XAUT0 の 2 ペア)を根本的に解消する。この変更を本仕様に含める
- 銘柄モデルは `PerpetualInstrument` を `Instrument` に改名して perp とスポットを統合する
- `spotMeta` の取得失敗は `allPerpMetas` と同じくログイン失敗とする

非目的(スコープ外):

- スポット固有のメタデータ(`weiDecimals`、`tokenId`、`evmContract`、供給量、出来高)の公開
- スポット残高、口座情報、注文
- トークンの UI 上のリマップ名(`BTC/USDC` ≒ HyperCore の `UBTC/USDC`)の追随。L1 名を使う
- リレー 2 社(Borsa / Hyperdash)のスポット板が Hyperliquid 本家と一致するかの検証
- バージョン更新とリリース作業

## 実測による検証済みプロトコル事実(2026-09-10 観測)

Hyperliquid 公式ドキュメント(Info endpoint、Spot、Tick and lot size、WebSocket Subscriptions)と、
Mainnet / Testnet への実接続で確認した。

### メタデータ

`{"type":"spotMeta"}`(重み 20)は `{"tokens":[...],"universe":[...]}` を返す。

- `tokens[]`: `name`、`szDecimals`、`weiDecimals`、`index`、`tokenId`、`isCanonical`、
  `evmContract`、`fullName`、`deployerTradingFeeShare`。本設計が使うのは `index`・`name`・
  `szDecimals` のみ
- `universe[]`: `name`、`tokens: [baseIndex, quoteIndex]`、`index`、`isCanonical`。
  `isDelisted` は存在しない
- `universe[].name` は `PURR/USDC` を除きすべて `@{index}` 形式
- 規模: Mainnet 500 トークン・326 ペア(応答 136 KB)、Testnet 1657 トークン・1319 ペア
- クォートトークンは USDC のほか USDT0、USDH、USDE が存在する
- Mainnet の `baseName/quoteName` は重複なし、大文字化しても衝突なし。perp 名(HIP-3 は
  `dex:coin`)に `/` は現れないため、perp とスポットの表示名は構造的に衝突しない
- Mainnet の base トークンの `szDecimals` は 0〜5 と 8(USDC)。スポットの価格桁は
  `8 − szDecimals`(perp は `6 − szDecimals`)。価格の有効数字は最大 5 桁、整数は常に許可

### WebSocket

板・約定の購読 coin は `universe[].name`(`@107`、`PURR/USDC`)でなければならない。
REST `l2Book` に `HYPE/USDC` を渡すと `null`、`PURR` を渡すと **perp の PURR** の板が返る。

| ソース | `l2Book` `@107` | `trades` `@107` | frame 内の `coin` |
|---|---|---|---|
| Hyperliquid(`wss://api.hyperliquid.xyz/ws`) | 正常(20 段、`nSigFigs`/`mantissa` 可) | 正常 | `@107` |
| Borsa(`wss://ws.borsa.cc/`) | 正常(400 段) | 正常 | `@107` |
| Hyperdash(`wss://api.hyperdash.com/ws/orderbook`) | 正常(20 段) | 正常 | `@107` |

Testnet 本家でも `l2Book` `@1035`(HYPE/USDC)を確認した。

### マーク価格

既に採用している `fastAssetCtxs` にスポット銘柄が含まれる。キーは購読 coin と同じ
(`@107`、`PURR/USDC`)で、値は `{"markPx":..., "midPx":...}`。

- Mainnet: 2632 キー中 637 がスポット
- Testnet: 4560 キー中 2012 がスポット、スナップショット 45 KB(HIP-3 対応で緩和済みの
  Jetty 上限内)

`activeAssetCtx` に `@107` を渡すと `activeSpotAssetCtx` チャネルで同じ `markPx` が届くが、
銘柄ごとの購読が必要なので採用しない。

### 現行実装で溢れる銘柄

ネイティブ格子 `10^-priceDecimals` 上の `int` 単位では、Mainnet 326 ペア中 2 件
(`XAUT0/USDC` `@182`、`XAUT0/USDT0` `@209`。`szDecimals` 2 → 6 桁、価格 ≈ 4400 → 4.4 × 10^9)が
`Integer.MAX_VALUE` を超える。参照価格に基づく既定 tick(5 有効数字の刻み)で割った値は
全ペアで `int` に収まる。

## アーキテクチャ

### 基本方針

perp とスポットを同じ `Instrument` 型・同じ購読/板/約定/マーク価格経路に流す。違いは次の 3 点だけで、
すべてモデル(`Instrument` と `Market`)に閉じ込める。

1. 表示名(Bookmap symbol / alias)と購読 coin(WebSocket キー、マーク価格キー)が別
2. 価格桁の上限が 6 か 8
3. Bookmap の instrument type 文字列が `PERPETUAL` か `SPOT`

板・約定・`fastAssetCtxs` は 3 ソースともスポットを無変更で通すため、経路の分岐は追加しない。

代替案として「スポット専用のパーサー・一覧・セッション状態を別に持つ」は、変換ロジックが同一で
重複しか増えないため採らない。「取引所の生の名前(`@107`)をそのまま symbol にする」は、
ユーザーが銘柄を探せないため採らない。

### モデル(`model` パッケージ)

#### `Instrument`(`PerpetualInstrument` を改名)

不変。フィールドと契約:

| フィールド | 意味 |
|---|---|
| `symbol` | Bookmap の symbol / alias。perp は取引所名(`BTC`、`xyz:CL`)、スポットは `baseName/quoteName` |
| `coin` | WebSocket 購読キー兼 `fastAssetCtxs` のキー。perp は `symbol` と同一、スポットは `universe[].name` |
| `market` | `Market.PERPETUAL` または `Market.SPOT` |
| `sizeDecimals` | 0 以上 `market.maxDecimals()` 以下。範囲外は `IllegalArgumentException` |
| `referencePrice` | ログイン後に `fastAssetCtxs` から得たマーク価格、または null(非正は null 化) |

導出値 `priceDecimals = market.maxDecimals() − sizeDecimals`、`pips = 10^-priceDecimals`、
`sizeMultiplier = 10^sizeDecimals` は現行の定義を踏襲する。

変換:

- `long toDepthPriceUnits(BigDecimal)`: 戻り値を `int` から `long` にする。非正・格子外の例外契約は
  不変。`long` の上限(約 9.2 × 10^18)は 8 桁格子でも価格 9 × 10^10 に相当するため、
  `DEPTH_PRICE_OUT_OF_RANGE` はこの関数からは実質的に出ないが、契約としては残す
- `double toTradePriceUnits(BigDecimal)`(2^53 上限)、`toSizeUnits`、`toDepthSizeUnits` は不変

コンストラクタは `(symbol, coin, market, sizeDecimals, referencePrice)`。参照価格なしの
簡略コンストラクタも同様に置き換える。既存の全参照箇所は機械的に改名する。

#### `Market`(新規 enum)

```java
public enum Market {
  PERPETUAL(6, "PERPETUAL"),
  SPOT(8, "SPOT");
  int maxDecimals();      // 価格小数桁の上限(Tick and lot size の MAX_DECIMALS)
  String bookmapType();   // SubscribeInfo / InstrumentInfo の type 文字列
}
```

Bookmap API 側に type 定数はなく自由文字列である(api-core 7.8.0.13 のクラスを確認)。

#### 変更しないもの

`SymbolLookup`(大文字化された要求名の解決。`/` は大文字化の影響を受けない)、`TickSizePlan`
(`priceDecimals` を引数に取るだけ)、`SubscriptionKey`(coin 文字列を受けるだけ)、
`SubscriptionType`。

### メタデータ取得(`parse` / `HyperliquidConnector`)

#### `HyperliquidMetaParser.parseSpotMeta(String)`

`spotMeta` 応答を検証して `List<Instrument>`(`Market.SPOT`)を返す。

検証規則(違反は `ProtocolException`):

- ルートはオブジェクトで、`tokens` と `universe` は配列。`universe` が空配列なのは正常
  (スポット 0 件を返す)。`tokens` が空で `universe` が非空なら解決不能として拒否
- `tokens[]`: `index` は非負整数、`name` は非空文字列、`szDecimals` は 0〜8 の整数。
  `index` の重複は拒否
- `universe[]`: `name` は非空文字列、`tokens` は長さ 2 の整数配列で、両方が `tokens[]` の
  `index` に解決できること。`name`(coin)の重複、表示名 `baseName/quoteName` の重複は拒否
- `isDelisted` は存在しないため扱わない。`isCanonical` は使わない

`Instrument` への導出(`universe[]` の 1 要素につき 1 件、順序は `universe[]` のまま):

- `symbol` = `tokens[tokens[0]].name + "/" + tokens[tokens[1]].name`(base はインデックス 0、quote は 1)
- `coin` = `universe[].name`
- `market` = `Market.SPOT`
- `sizeDecimals` = base トークンの `szDecimals`(quote トークンの `szDecimals` は使わない)
- `referencePrice` = null(ログイン後に `fastAssetCtxs` から補う)

既存の `parseAllPerpMetas` は不変(生成する型が `Instrument`/`Market.PERPETUAL` になるだけ)。

#### コネクタのログイン手順

現行は `allPerpMetas` 1 リクエスト。これを 2 リクエストの並行発行に変える。

1. `start` 時に `allPerpMetas` と `spotMeta` を同じ `infoUri` へ同時に `postJson` する
   (各 10 秒タイムアウト、REST 重み 20 + 20、ログイン時 1 回限り)
2. 両方の応答を検証し、perp リスト + スポットリストを結合して `listener.onMetadata` に渡す
3. どちらかが失敗(ネットワーク、HTTP 非 2xx、`ProtocolException`)したら初期失敗として
   報告し、未完了のもう一方を取り消す。失敗の分類は現行と同じ
4. 結合時に `symbol` と `coin` の一意性を再確認し、衝突は `ProtocolException`(PROTOCOL 失敗)
   とする。perp 名に `/` は現れないため実際には起きないが、取引所側の変化に対して fail-closed にする
5. `close` は両リクエストを取り消す

`startWithoutMetadata`(asset-context 専用接続)は不変。

### セッションと Provider

- `HyperliquidSession.instruments` は `symbol` キー(一覧と `SymbolLookup` の対象)、
  `records` は `coin` キー(受信 frame の `coin` から引く現行の仕組み)
- `handleSubscribe(symbol, exchange, type, ...)`: `type` の事前判定(`"PERPETUAL".equals(type)`)
  を外し、まず `SymbolLookup.resolve` で銘柄を解決し、`type` が
  `instrument.market().bookmapType()` と一致しない場合は `onInstrumentNotFound`。
  メタデータ未着、解決不能、重複購読、予算不足の扱いは現行どおり
- `SubscriptionRecord` の `l2BookKey` / `tradesKey` は `instrument.coin()` から作る。
  `alias()` は `instrument.symbol()`
- `handleUnsubscribe(alias)` は現行どおり `records` を走査して alias(または要求名)で record を
  見つけ、削除は `records` のキーである `instrument.coin()` で行う
- `onAssetContexts`: 参照価格の取得を `assetContexts.markPrice(instrument.coin())` にする。
  `shouldRepublish` の「一覧にある銘柄が動いたか」判定も `coin` で行う。スナップショット/差分/
  間引きの規則は不変
- `Provider.ProviderSessionSink`: `SubscribeInfo(symbol, "", market.bookmapType())`、
  `InstrumentInfo(alias=symbol, "", market.bookmapType(), tick, 1d, null, false,
  sizeMultiplier, true)`。tick 候補・既定 tick・サイズ倍率の解決は現行どおり `Instrument` の
  `priceDecimals` / `referencePrice` を使う
- データヘルス通知の銘柄名は alias(= symbol)。変更なし
- `SourceProfile` / Borsa / Hyperdash 経路: 変更なし

### 板のネイティブ単位の `long` 化(`book` パッケージ)

対象は `PriceBucketer`、`DeltaOrderBook`、`OrderBookSnapshotDiff`。Bookmap に渡す bucket 単位と
`DepthUpdate` は `int` のまま。

- `PriceBucketer`
  - `int bidBucket(long nativeUnits)` / `int askBucket(long nativeUnits)` / `int bucket(boolean, long)`:
    丸め規則(bid は切り下げ、ask は切り上げ)は不変。結果が `Integer.MAX_VALUE` を超える場合は
    `ValueConversionException(DEPTH_PRICE_OUT_OF_RANGE)` を投げる。`askBucket` の加算は `long` で
    行うため溢れない
  - `long[] nativeRange(int bucket, boolean bid)`: 戻り値を `long[]` にし、下限 0 のクランプは残し、
    上限の `Integer.MAX_VALUE` クランプは外す
  - `ratio` は既に `long`。`tradePriceUnits` は不変
- `DeltaOrderBook` / `OrderBookSnapshotDiff`
  - ネイティブ単位で価格を保持する map(`publishedBids/Asks`、`stagedBids/Asks`、正規化
    スナップショット)のキーを `Long` にする。bucket 単位の map(`published*Buckets`)は `Integer`
  - `toDepthPriceUnits` の `long` 戻り値を受け、bucket 計算で `DEPTH_PRICE_OUT_OF_RANGE` が出た
    場合に従来どおり `UnsupportedPriceException` を投げる。つまり `UnsupportedPriceException` の
    意味は「ネイティブ単位が `int` に収まらない」から「bucket が `int` に収まらない」に変わる
  - 枠合計の `long` 加算と `Integer.MAX_VALUE` 飽和、ゼロサイズの扱いは不変
- bucket が `int` を超えるのは、参照価格が無く既定 tick が `10^-priceDecimals` 格子に落ちた
  状態で高値の銘柄を購読した場合だけである。スポットのマーク価格は `fastAssetCtxs` に含まれる
  ため、通常運用では起きない。この条件は README の Limits に記載する

## データフロー

1. ログイン: `allPerpMetas` と `spotMeta` を並行取得 → 検証・結合 → `onMetadata(List<Instrument>)`
   → セッションが `symbol` キーで保持し `onKnownInstruments` で Bookmap の一覧を更新
   (`SubscribeInfo.type` は `PERPETUAL` / `SPOT`)
2. マーク価格: `fastAssetCtxs` のスナップショット/差分 → `AssetContextStore`(coin キー)→
   各 `Instrument` の `referencePrice` を `coin` で引いて再構築 → 一覧の tick 候補を更新
3. 購読: Bookmap から `(symbol 大文字化, "", type)` → `SymbolLookup` で `Instrument` 解決 → `type`
   一致を確認 → `SubscriptionKey(coin, ...)` で `l2Book` / `trades` を送信 → 受信 frame の `coin`
   で record を引き、alias(= symbol)で Bookmap に公開
4. 板: `BigDecimal` 価格 → `long` ネイティブ単位 → `PriceBucketer` で `int` bucket → `DepthUpdate`

## エラー処理と運用制約

- 新しいデータヘルス種別やシステムメッセージは追加しない。スポットの購読拒否・タイムアウト・
  stale・再同期・約定ギャップはすべて既存経路
- `spotMeta` 失敗はログイン失敗。部分成功(perp のみ)の状態は作らない
- 一覧の規模は Mainnet 約 315 perp + 326 spot、Testnet 約 630 perp + 1319 spot になる
- 購読スロット・接続・送信の予算(`HyperliquidProcessBudget`)は不変。スポットも 1 銘柄あたり
  `l2Book` + `trades` の 2 スロット
- Bookmap の Subscribe ダイアログや保存済みワークスペースが `/` を含む symbol を扱えるかは本設計時点で
  未検証。実装後の実機確認事項とする

## テスト戦略

既存テストは `Instrument` / `Market` への改名と `long` 化に追随させる。追加するテスト:

- `HyperliquidMetaParserTest`: 実応答に近い `spotMeta` フィクスチャ(`PURR/USDC`、`@1`、USDT0
  クォート)から表示名・coin・`szDecimals` を導出する。tokens 解決不能、`index` 重複、coin 重複、
  表示名重複、`szDecimals` 範囲外、空 `universe`(正常)、`tokens` 空で `universe` 非空(拒否)
- `InstrumentTest`(旧 `PerpetualInstrumentTest`): `Market.SPOT` で `priceDecimals = 8 − sz`、
  `sizeDecimals` の上限が market ごとに異なること、`toDepthPriceUnits` が `int` 超の値を返すこと
  (XAUT0 ケース: sz 2、価格 4378.7 → 4 378 700 000)
- `PriceBucketerTest`: `long` 入力で XAUT0 ケースが tick 0.1 で bucket 43 787 になること、
  bucket が `int` を超えると `DEPTH_PRICE_OUT_OF_RANGE`、`nativeRange` が `long` を返すこと
- `DeltaOrderBookTest` / `OrderBookSnapshotDiffTest`: `int` 超のネイティブ価格を含む板が
  bucket 単位で正しく公開されること。bucket 溢れで `UnsupportedPriceException`
- `HyperliquidConnectorTest`: 2 リクエストが並行に出ること、両方揃って結合リストが届くこと、
  片方の失敗(HTTP エラー / `ProtocolException` / ネットワーク)で初期失敗となりもう一方が
  取り消されること、`close` で両方取り消されること
- `HyperliquidSessionSubscriptionTest`: type `SPOT` で `HYPE/USDC` を購読すると
  `l2Book` / `trades` の coin が `@107` になり、`@107` の frame が alias `HYPE/USDC` に届くこと。
  type 不一致(`HYPE/USDC` を `PERPETUAL` で要求、`BTC` を `SPOT` で要求)は not-found
- `HyperliquidSessionAssetContextTest`: `fastAssetCtxs` の `@107` がスポット銘柄の参照価格になり、
  tick 候補に反映されること
- `ProviderTest`: 一覧の `SubscribeInfo.type` が perp/スポットで正しいこと、`InstrumentInfo.type`
  が `SPOT` になること
- `ProviderEndToEndTest`: スポット銘柄 1 件を Provider 経由で購読し depth / trade が出ること
- `TestMetadata`: `spotMeta` 応答を組み立てるヘルパーを追加

品質ゲート(`spotlessCheck`、`checkstyle`、`spotbugsMain`、`test`、`verifyJava8Bytecode`)は
現行どおりすべて通すこと。

## ドキュメント

- README: Scope を「`PERPETUAL` と `SPOT`」に更新。銘柄名の説明に `BASE/QUOTE` 命名、UI 名との
  差(`BTC/USDC` は HyperCore では `UBTC/USDC`)、`PURR/USDC` を追記。Limits に Testnet のスポット
  件数と「参照価格が無い状態での高値銘柄は購読できないことがある」を追記
- `docs/development.md`: `model` 行を「perp とスポットの銘柄モデル」に更新
- HIP-3 仕様の非目的にある「スポット市場」の記述は履歴として書き換えない

## 実装後に実機で確認すべき事項

- Bookmap の Subscribe ダイアログで `HYPE/USDC` を type `SPOT` で購読でき、ヒートマップと約定が出ること
- `/` を含む alias を保存したワークスペースを再起動後に復元できること
- `XAUT0/USDC` を既定 tick で購読できること(`long` 化の実地確認)
- Borsa / Hyperdash を選択した状態でスポットの板・約定が出ること

## 参照

- Info endpoint(スポットの coin 表記): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint
- Spot(`spotMeta` / `spotMetaAndAssetCtxs`): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint/spot
- Tick and lot size(`MAX_DECIMALS` 6 / 8): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/tick-and-lot-size
- WebSocket Subscriptions(`fastAssetCtxs`、`activeAssetCtx`): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions
- 先行仕様: `2026-09-07-hip3-market-support-design.md`、`2026-09-07-tick-size-book-aggregation-design.md`
