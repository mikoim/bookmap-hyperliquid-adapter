# 約定の実行単位グルーピング — 設計書

- 作成日: 2026-09-19
- 状態: spec-review READY、人間の承認待ち
- 起点: ローカル main `08a6317`(メタデータ再取得の実装完了)
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

Hyperliquid の `trades` は (買い手, 売り手) の組ごとに 1 件届く。1 回の成行注文が板の 5 つの
指値を食えば 5 件の `WsTrade` になり、それらは同じ `hash`(L1 トランザクションハッシュ)を持つ。
現行アダプターは parser が `hash` を読まず、`Provider` が常に
`new TradeInfo(false, isBuyAggressor)` を渡す。この 2 引数コンストラクタは
`isExecutionStart` と `isExecutionEnd` を両方 `true` にするので、Bookmap には 5 件の独立した
実行として伝わり、1 回の大口成行が 5 つの小さな約定に見える。本設計は同じトランザクションの
fill を 1 つの実行として Bookmap に渡す。

要求の要約(ユーザー決定済み):

- グルーピングの単位は「連続する、同じ coin・同じ side・同じ非ゼロ hash の約定」。先頭に
  `isExecutionStart`、末尾に `isExecutionEnd` を立てる
- Bookmap の `TradeAggregator` は使わない。自前のタイマースレッドから `onTrade` を発火するため、
  出力をすべて state lane から出す現行設計と、時計を手動で進める決定的なテストを崩す
- フラグは session の公開時点で、重複排除の後に、リスト単位で計算する

非目的(スコープ外):

- `TradeInfo.aggressorOrderId` / `passiveOrderId` の設定(`hash` は注文 ID ではない)
- フレームをまたぐ実行をライブ経路でつなぐこと(タイマーか遅延が要る)
- 同一トランザクション内の複数注文(バッチ注文)の区別(WebSocket の約定に `oid` が無い)
- 重複排除キー `TradeKey(coin, time, tid)` の変更
- 約定の `users`、清算・TWAP の種別などの公開
- バージョン更新とリリース作業

## 前提となる事実

- 公式ドキュメント(WebSocket Subscriptions)の `WsTrade`:
  `coin, side, px, sz, hash, time, tid, users`。`hash` の説明は L1 data schemas の例にある
  トランザクションハッシュ(`0x` + 64 桁の 16 進)のみで、グルーピング上の意味は定義されていない
- 全ゼロの `hash` を持つ約定が実在する(2026-09-06 に Hyperdash で観測、
  `2026-09-06-borsa-hyperdash-data-sources-design.md` の比較表)。清算や TWAP の約定が
  そうである可能性が高いが、ドキュメントでは未確認
- `velox.api.layer1.data.TradeInfo`(api-core 7.8.0.13)のコンストラクタ:
  `(isOtc, isBidAggressor)` は `(isOtc, isBidAggressor, true, true)` に委譲する(バイトコードで
  確認)。`(boolean, boolean, boolean, boolean)` が `isExecutionStart` / `isExecutionEnd` を受ける
- `trades` の 1 フレームは `WsTrade[]` で、parser は 1 フレームの有効な約定を
  `ParsedFrame.marketEvents()` に元の順序で並べる
- session の約定経路(`HyperliquidSession.handleTrade`)は 1 件ずつ処理し、状態に応じて
  (a) 起動待ちバッファ、(b) 回復バッファ、(c) 重複排除して即時公開、のいずれかへ送る。
  バッファは `SubscriptionRecord.pendingTrades`(上限 1,024 件)で、`activateIfReady` と
  `maybeRestore` が `publishIfNew` で 1 件ずつ排出する
- リレー(Borsa / Hyperdash)の `trades` は同じ parser と session を通る

## アーキテクチャ

### 基本方針

- `hash` は「実行 ID」として parser から公開直前まで運ぶだけで、約定の受理・重複排除・
  バッファリングの判定には一切使わない
- フラグは、実際に Bookmap へ公開する約定の並びが確定した後に計算する。重複排除や
  バッファ上限で約定が落ちても、公開される実行は必ず整形される(先頭が start、末尾が end。
  start だけで end の無い実行は渡らない)
- グルーピングの規則は状態を持たない小さな単位に閉じ込め、1,150 行ある
  `HyperliquidSession` をこれ以上膨らませない
- 単独約定の出力は現状と同じ(start / end とも `true`)

### モデルと解析

#### `TradeEvent`(`model`)

フィールド `String executionId`(null 可)とアクセサ `executionId()` を追加する。
コンストラクタは
`TradeEvent(String coin, long time, long tid, boolean isBuyAggressor, BigDecimal price, BigDecimal size, String executionId)`
の 1 本にする(既存の 6 引数コンストラクタは置き換える。呼び出し元は parser とテスト)。
`key()` は変えない。

#### `HyperliquidMessageParser.parseTrade`(`parse`)

`hash` を省略可能として読む。次のすべてを満たすときだけその文字列を `executionId` にし、
それ以外は `null` にする。

- 要素が JSON の文字列プリミティブである
- 正規表現 `0x[0-9a-fA-F]+` に全体一致する
- `0x` より後に `0` 以外の文字を 1 つ以上含む

`hash` が欠落、`null`、数値、空文字、形式違い、全ゼロのいずれでも、約定は従来どおり受理し、
診断も出さない。正規表現は `static final Pattern` として 1 回だけコンパイルする。

#### `SubscriptionRecord.PendingTrade`(`session`)

フィールド `String executionId`(null 可)とアクセサを追加する。コンストラクタは
`PendingTrade(TradeKey key, double priceUnits, int sizeUnits, boolean buyAggressor, String executionId)`。

### グルーピング規則: `TradeExecutions`(`session`、package-private final、新規)

```java
final class TradeExecutions {
  interface Output {
    void onTrade(PendingTrade trade, boolean executionStart, boolean executionEnd);
  }

  /** trades は 1 つの購読(1 つの coin)の、公開する順に並んだ約定。 */
  static void publish(List<PendingTrade> trades, Output output);
}
```

契約:

- 隣り合う 2 件 `a`, `b` が「同じ実行」であるのは、`a.executionId() != null`、
  `a.executionId().equals(b.executionId())`、`a.buyAggressor() == b.buyAggressor()` を
  すべて満たすときだけ
- `trades[i]` の `executionStart` は、`i == 0` または `trades[i-1]` と同じ実行でないとき `true`
- `trades[i]` の `executionEnd` は、`i == last` または `trades[i+1]` と同じ実行でないとき `true`
- `output.onTrade` を `trades` の順に、各要素につきちょうど 1 回呼ぶ。空リストでは何もしない
- coin の一致は検査しない。呼び出し側が購読(record)ごとにリストを分けて渡す

### `HyperliquidSession` の変更

- `handleMarketFrame`: イベント列を先頭から走査し、連続する `TradeEvent` を 1 つのリストに集めて
  `handleTrades(List<TradeEvent>)` に渡す。`BookSnapshot` は従来どおり 1 件ずつ `handleBook` に
  渡し、そこでリストを区切る。イベントごとに行っている
  `closed || generation != currentGeneration || generationInvalidated` の検査は、リストを渡す前と
  各 `handleBook` の前に行う
- `handleTrades`: 各約定について現行 `handleTrade` と同じ判定を行う
  - record が無い/`REMOVED` → 診断を出して捨てる(現行どおり)
  - `convertTrade` 失敗 → 診断を出して捨てる(現行どおり)。`PendingTrade` に `executionId` を渡す
  - `PENDING_BOOK`、または `recovering && RECONNECTING` → 現行どおりバッファへ
    (重複判定、あふれ時の `tradeGap` と診断も現行どおり)
  - それ以外(ライブ)→ `tradeDeduplicator.markIfNew` が `true` のものだけ、その record の
    公開リストに追加する
  - 公開リストに約定を追加しようとして、その record が溜まっている公開リストの record と
    異なるとき、およびリストの末尾で、溜まった公開リストを `publishTrades(record, list)` で
    排出する。捨てた約定とバッファへ送った約定は、公開リストを区切らない
  - 捨てた約定の診断は判定した時点で出す。したがって同じフレーム内では、診断が、それより前に
    届いた約定の公開より先に出ることがある。約定の公開順とフラグには影響しない
- `publishTrades(SubscriptionRecord record, List<PendingTrade> trades)`:
  `TradeExecutions.publish` を呼び、`Output` の中で
  `sink.onTrade(record.alias(), priceUnits, sizeUnits, buyAggressor, executionStart, executionEnd)` と
  `dataHealth.tradePublished(record.alias(), currentGeneration)` を約定ごとに呼ぶ
- `activateIfReady` と `maybeRestore` のバッファ排出: `takePendingTrades()` の結果を順に
  `markIfNew` でふるい、残ったリストを `publishTrades` に渡す。`publishIfNew` は削除する
- 単独の `handleTrade` は `handleTrades` に置き換えて削除する

バッファ内でフレームをまたいで隣り合った同じ `executionId` の約定は、排出時に 1 つの実行に
まとまる。これは意図した挙動である。

### `SessionSink` と `Provider`

- `SessionSink.onTrade` を
  `void onTrade(String alias, double priceUnits, int sizeUnits, boolean isBuyAggressor, boolean isExecutionStart, boolean isExecutionEnd)`
  に変える
- `Provider.ProviderSessionSink.onTrade` は
  `new TradeInfo(false, isBuyAggressor, isExecutionStart, isExecutionEnd)` を渡す
- `RecordingSessionSink`(テスト)は既存のイベント文字列 `trade:<alias>:<price>:<size>` を変えず、
  フラグは `Trade` オブジェクトに記録する

## データフロー

```
trades フレーム(WsTrade[])
  → parser: TradeEvent(..., executionId)  ※ hash 不備は null、約定は受理
  → dispatcher.submitMarketFrame → handleMarketFrame
  → 連続する TradeEvent をまとめて handleTrades
       起動待ち / 回復中 → record.pendingTrades へ(executionId を保持)
       ライブ           → markIfNew を通ったものを公開リストへ
  → publishTrades → TradeExecutions.publish → sink.onTrade(..., start, end)
  → Provider: TradeInfo(false, isBuyAggressor, start, end) → Layer1ApiDataListener.onTrade

activateIfReady / maybeRestore
  → takePendingTrades → markIfNew でふるう → publishTrades(同上)
```

## エラー処理と運用制約

| 状況 | 挙動 |
| --- | --- |
| `hash` が欠落、文字列でない、形式違い、全ゼロ | 単独の実行として公開。診断なし |
| 1 つのトランザクションに同じ coin・同じ side の成行が複数(バッチ注文) | 1 つの実行にまとまる。WebSocket の約定に `oid` が無く区別できない。README に明記する |
| 同じ `hash` の約定がフレームをまたぐ | ライブ経路では 2 つの実行に分かれる。バッファ排出時は 1 つにまとまる |
| 同じ `hash` の並びの途中に別の `hash` や `null` が挟まる | そこで実行を区切る(連続性だけを見る) |
| 同じ `hash` で side が変わる | そこで実行を区切る |
| 重複排除で並びの一部が落ちる | 残った並びに対してフラグを計算する。実行は常に整形される |
| バッファ上限(1,024)で約定が落ちる | 同上。`TRADE_GAP_POSSIBLE` は現行どおり |
| `ValueConversionException` で約定を捨てる | 同上 |
| リレー(Borsa / Hyperdash) | 同じ経路。全ゼロ hash の約定は単独の実行 |

## テスト戦略

すべて TDD。

### `HyperliquidMessageParserTest`(追加)

- 有効な `hash` は `executionId` になる
- 全ゼロ(`0x000…0`)、欠落、JSON `null`、数値、空文字、`0x` のみ、16 進でない文字列は、いずれも
  `executionId == null` で、約定は受理され、診断は出ない

### `TradeExecutionsTest`(新規)

- 空リストでは `Output` が呼ばれない
- 単独の約定は start / end とも `true`(`executionId` が null でも非 null でも)
- 同じ id・同じ side の 3 件は `(T,F) (F,F) (F,T)`
- 同じ id で side が変わると区切られる
- id が null の約定は、前後が同じ id でも単独になり、前後も区切られる
- 異なる id が交互に並ぶと、すべて単独になる
- `Output` は入力順に、各要素につきちょうど 1 回呼ばれる

### session(新規 `HyperliquidSessionTradeExecutionTest`。既存の session テストは 1,000 行超のため)

- ライブ: 1 フレームに同じ hash の 3 件 → `(T,F) (F,F) (F,T)`
- ライブ: 全ゼロ hash の 3 件 → すべて `(T,T)`
- ライブ: 同じ hash の 3 件のうち先頭が既に公開済み(重複)→ 残り 2 件が `(T,F) (F,T)`
- ライブ: 1 フレームに 2 つの hash が続く → 2 つの実行
- 起動待ち: 2 フレームに分かれて届いた同じ hash の約定が、購読成立時の排出で 1 つの実行になる
- 回復: 再接続中にバッファされた同じ hash の約定が、復旧時の排出で 1 つの実行になる
- 板フレームと約定フレームが交互に届いても、板と約定の公開順が到着順のまま保たれる

### 既存テスト

- 単独約定の前提(start / end とも `true`)のまま通ること。`SessionSink.onTrade` の引数追加に
  伴うテスト用 sink の更新以外、既存のアサーションは変えない

### `ProviderTest` / `ProviderEndToEndTest`(追加)

- `SessionSink.onTrade` のフラグが `TradeInfo.isExecutionStart` / `isExecutionEnd` として
  `Layer1ApiDataListener.onTrade` に届く
- エンドツーエンド: 同じ hash の 2 件を 1 フレームで流すと `TradeInfo` が `(T,F)`、`(F,T)` になる

### ビルドゲート

`env JAVA_HOME=... ./gradlew --offline spotlessApply check verifyJava8Bytecode` が成功すること。

## ドキュメント

- `README.md`: Scope の節に 1 段落を追記する。同じトランザクションで約定した fill は 1 つの
  実行として Bookmap に渡ること、1 つのトランザクションに同じ銘柄・同じ side の注文が複数あれば
  それらもまとまること、ハッシュを持たない約定(全ゼロ)は 1 件ずつの実行になること
- `docs/development.md`: パッケージ表の `hyperliquid.session` 行に実行単位のグルーピングを追記する
- Javadoc: `TradeExecutions`、`TradeEvent.executionId`、`SessionSink.onTrade`

## 実装後に実機で確認すべき事項

- Bookmap の約定ドットと Time & Sales が実行単位で集約され、大口の成行 1 回が 1 つに見えること
- 全ゼロ hash の約定(清算など)が従来どおり 1 件ずつ表示されること

## 参照

- WebSocket Subscriptions(`WsTrade`): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions
- L1 data schemas(`hash` の例): https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/nodes/l1-data-schemas
- Bookmap API Core Javadoc(`TradeInfo`、`TradeAggregator`): https://javadoc.bookmap.com/maven2/releases/com/bookmap/api/api-core/7.6.0.30/
  (公開 Javadoc は 7.6.0.30。コンストラクタの挙動はビルドが使う 7.8.0.13 の JAR で確認した)
- 先行仕様: `2026-09-06-borsa-hyperdash-data-sources-design.md`(全ゼロ hash の観測)
