# 購読時の約定再送の扱い(約定の水位線) — 設計書

- 作成日: 2026-09-28
- 状態: spec-review READY、人間の承認済み
- 起点: ローカル main `6725969`(購読送信の記録順の修正)
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

公式ドキュメント(WebSocket Subscriptions)は「購読の ack は時系列データについて過去データの
スナップショットを伴う」と定め、WebSocket のページは「再接続中に取りこぼしたデータは再接続時の
スナップショット ack に含まれる」と述べる。`trades` もこれに従い、購読のたびに直近の約定を
まとめて送ってくる。

現行アダプターはこの再送を区別しない。

- 初回購読: 再送された約定は `PENDING_BOOK` 中にバッファされ、有効化の時点で新しい約定として
  Bookmap に公開される(`HyperliquidSession.activateIfReady` → `publishBuffered`)。Bookmap の
  `onTrade` は時刻を取らないため、過去の約定が現在の時刻・現在の価格帯に描かれる。2026-09-28 の
  実測では Mainnet `@3` で 34 日前、Testnet `@20` で約 2.4 年前の約定がこうして公開される
- 再接続: 重複は `BoundedTradeDeduplicator`(全 coin 共有、TTL 10 分、最大 10,000 件)だけで
  防いでいる。閑散銘柄の再送(10 分より古い約定を含む)や、活発な銘柄に押し出された後の再送は
  再び公開される
- 性能: `BoundedTradeDeduplicator.purgeExpired` はアクセス順の `LinkedHashMap` を全走査するため、
  約定 1 件ごとに最大 10,000 件を走査する

本設計は、coin ごとに「最後に公開した約定の取引所時刻」を水位線として持ち、それより古い約定を
捨てることで、初回購読の再送を公開せず、再接続時は取りこぼした分だけを公開する。

要求の要約(ユーザー決定済み):

- 初回購読時の再送約定は捨てる。README の「履歴データ非対応」と整合させる
- 再接続時の再送約定は、その coin で最後に公開した約定より新しいものだけを取りこぼし分として
  公開する(公開時刻は復旧時点になる)
- 判定の基準は取引所時刻とする(ローカル時計のずれに依存しない)。初期値は有効化で公開する板の
  取引所時刻
- `BoundedTradeDeduplicator` は水位線で置き換えて削除する

非目的(スコープ外):

- 再送の範囲を超えた欠落を info エンドポイントで補うこと(gap fill)
- `TRADE_GAP_POSSIBLE` / `TRADE_RESUMED` の発火条件の変更
- 約定のグルーピング規則(`TradeExecutions`)の変更
- 監査で見つかった他の改善(接続予算、購読の競合、パーサーの許可リスト、構造分割)。ただし
  「`PENDING_BOOK` のまま再接続すると切断前の板が残る」不具合は水位線の初期値の正しさに直結する
  ため本設計に含める
- バージョン更新とリリース作業

## 前提となる事実

2026-09-28 に `trades` と `l2Book` を実際に購読して確認した(スクリプトは使い捨て)。

| ソース | ack 後の再送 | 再送の形 |
| --- | --- | --- |
| Hyperliquid Mainnet / Testnet | あり。最大 30 件、約定が無い coin は空配列 1 フレーム | ack 直後の独立した `trades` フレーム 1 つ。`isSnapshot` は付かない |
| Borsa | あり。100 件 | 同上 |
| Hyperdash | なし | 最初のフレームからライブ |

- 1 フレーム内の約定は `time` の昇順。`tid` には順序が無い(再送フレームでは常に不規則)
- 購読直後に届く最初の `l2Book` の `time` は、閑散銘柄を含めて常に現在の取引所時刻
  (ローカル時計との差 0.3〜0.9 秒)。直近の変更時刻ではない
- 再送の最新約定は最初の板より古かった(Hyperliquid ETH で 125 ms、BTC で 2.4 秒)。Borsa は
  再送が板より先に届き、最初の板の `time` は直後のライブ約定と同じ値だった
- 重複排除キー `(coin, time, tid)` は公式の推奨 `(block_time, coin, tid)` と一致する。`time` は
  ブロック時刻(ms)で、同じブロックの約定は同じ `time` を持ちうる

## アーキテクチャ

### 基本方針

- 水位線は購読(`SubscriptionRecord`)ごとに持つ。寿命は購読と同じで、再接続や market frame の
  あふれをまたいで保持し、unsubscribe で record ごと捨てる。再購読は新しい record になるので、
  過去の約定を再び公開することは無い
- 水位線の初期値(アンカー)は、有効化で Bookmap に公開する板の取引所時刻とする。アダプターは
  「最初の板が出た瞬間から約定の公開を始める」と定義する
- 同じ coin の約定は `time` の昇順で届くことを前提とする。これより古い約定は、ライブでも再送でも
  公開しない

### 水位線: `TradeWatermark`(`session`、package-private final、新規)

状態:

- `long time`: 最後に公開した約定の取引所時刻。アンカー前は `-1`
- `Set<Long> tidsAtTime`: `time` と同じ時刻に公開済みの `tid`

操作:

- `void anchor(long bookTime)`: `time = bookTime`、`tidsAtTime` を空にする
- `boolean admits(long tradeTime, long tid)`:
  - `tradeTime > time` → `true`
  - `tradeTime == time` → `tidsAtTime` に `tid` が無ければ `true`
  - `tradeTime < time` → `false`
- `void advance(long tradeTime, long tid)`: `tradeTime > time` なら `time = tradeTime` とし、
  `tidsAtTime` を `{tid}` に作り直す。`tradeTime == time` なら `tid` を追加する。
  `tradeTime < time` は呼び出し側の誤りであり `IllegalArgumentException`

アンカーは空集合で始まるので、板と同じ時刻の約定は公開する。購読直前と同じブロックの約定が
公開されうるが、実測では再送の最新約定は板より 125 ms 以上古く、実害は無い。

`tidsAtTime` の大きさは同じブロック・同じ coin の約定数で抑えられ、時刻が進むたびに作り直される。

### `TradeKey`(`model`)

`long time()` と `long tid()` を追加する。等価性とハッシュは変えない。

### `SubscriptionRecord` の変更

- `TradeWatermark tradeWatermark` を持ち、package-private の `tradeWatermark()` で返す
  (`TradeWatermark` が package-private のため)
- `beginGeneration(long)` で `pendingBook = null` とする。現行は消さないため、`PENDING_BOOK` の
  まま再接続すると `maybeRestore` → `activateIfReady` が切断前の板で有効化し、その古い
  `lastAcceptedBookTime` がアンカーになる(再送の一部が公開される)。消すことで、有効化は新しい
  世代の板を待つ。delta モードは既に `deltaBook.beginGeneration()` で同じことをしている
- 既存の `pendingTrades` / `pendingTradeKeys`(バッファ内の重複防止)と上限 1,024 件は変えない。
  再送は Hyperliquid 30 件、Borsa 100 件で上限に収まる

### `HyperliquidSession` の変更

約定の経路は 5 つある。

| 経路 | 現行 | 変更後 |
| --- | --- | --- |
| ① `PENDING_BOOK` のバッファ(`bufferTrade`) | `tradeDeduplicator.contains` と `pendingTradeKeys` で重複判定 | `pendingTradeKeys` だけで判定する。水位線はまだ無い |
| ② 有効化(`activateIfReady`) | 板を公開 → バッファを `markIfNew` でふるって公開 | 板を公開 → `tradeWatermark().anchor(record.lastAcceptedBookTime())` → バッファを水位線でふるって公開 |
| ③ ライブ(`handleTrades`) | `markIfNew` が `true` なら公開リストへ | `admits` が `true` なら `advance` して公開リストへ |
| ④ 再接続中のバッファ(`bufferTrade`) | `contains` と `pendingTradeKeys` | `admits` が `false` なら捨てる(`advance` はしない)。加えて `pendingTradeKeys` |
| ⑤ 復旧(`maybeRestore` → `publishBuffered`) | `markIfNew` でふるって公開 | 水位線でふるって公開 |

- 有効化の時点の `record.lastAcceptedBookTime()` は、公開する板の取引所時刻である。snapshot
  モードでは `acceptPendingBook` が、delta モードでは seed と差分の受理が `acceptActiveBook` で
  設定している
- 「水位線でふるう」は、バッファを到着順に走査し、`admits` が `true` のものについて `advance` して
  公開リストに加え、残りを捨てることを言う。`publishBuffered` はこの処理になる
- 有効化の後に届いた再送フレーム(ack と再送が有効化より後に処理される場合)は ③ で落ちる
- ② ③ ④ ⑤ で水位線により捨てた約定は、`handleTrades` の 1 回の呼び出し、または
  `publishBuffered` の 1 回の呼び出し(② と ⑤ で共用)ごとに、件数を 1 行の診断
  (`sink.onDiagnostic`)で出す。捨てた約定が 0 件なら出さない。購読のたびに再送を捨てるのは
  正常動作なので、約定 1 件ごとには出さない
- `ACTIVE` の record は必ずアンカー済みである(有効化でアンカーするため)
- `tradeDeduplicator` フィールド、定数 `TRADE_DEDUPLICATION_CAPACITY` /
  `TRADE_DEDUPLICATION_TTL_MILLIS`、`stop` の `tradeDeduplicator.clear()` を削除する

### 削除

- `trade/BoundedTradeDeduplicator.java` とそのテスト。`trade` パッケージは空になるので削除し、
  `docs/development.md` のコード配置表から `hyperliquid.trade` の行を消す

## データフロー

```
trades フレーム(再送 or ライブ)
  → handleTrades
       PENDING_BOOK         → record.pendingTrades へ(pendingTradeKeys で重複防止)   ①
       RECONNECTING 回復中  → admits なら record.pendingTrades へ                    ④
       ライブ               → admits なら advance して公開リストへ                  ③
  → publishTrades → TradeExecutions.publish → sink.onTrade

activateIfReady                                                                    ②
  → 板を公開 → watermark.anchor(lastAcceptedBookTime)
  → takePendingTrades → 水位線でふるう → publishTrades

maybeRestore                                                                       ⑤
  → 板を再公開 → takePendingTrades → 水位線でふるう → publishTrades
```

## エラー処理と運用制約

| 状況 | 挙動 |
| --- | --- |
| 初回購読の再送 | 有効化で公開する板より古い約定は捨てる。板と同じ時刻の約定は公開する |
| 再接続時の再送 | 最後に公開した約定より新しいものだけを、復旧の時点で公開する |
| 再送の範囲を超えて取りこぼした | 公開されない。`TRADE_GAP_POSSIBLE` は現行どおり |
| Hyperdash(再送なし) | 捨てる対象が無いだけで同じ経路。切断中の約定は欠落する |
| 同じ `time` で別の `tid` | 公開する |
| 同じ `time` で同じ `tid` | 捨てる |
| ライブの約定が水位線より古い(順序の前提が崩れた) | 捨てて診断を出す |
| 購読開始から最初の板までの約定 | 板より古ければ捨てる。公開は板から始まる |
| unsubscribe 後の再購読 | 新しい record・新しいアンカー。過去の約定は公開しない |
| `PENDING_BOOK` のまま再接続 | 新しい世代の板を待って有効化し、その時刻をアンカーにする |

## テスト戦略

すべて TDD。

### `TradeWatermarkTest`(新規)

- アンカー前は任意の約定を受理する(`time = -1`)
- `admits`: `<` は拒否、`==` は `tid` 未記録なら受理・記録済みなら拒否、`>` は受理
- `advance`: 時刻が進むと `tidsAtTime` を作り直す(古い時刻の `tid` は同時刻判定に残らない)。
  同時刻は追加する。後退は `IllegalArgumentException`
- `anchor`: 時刻を設定し `tidsAtTime` を空にする

### session(新規 `HyperliquidSessionTradeReplayTest`)

1. 初回購読で、再送が有効化の前に届く場合: 板より古い約定は公開されず、板と同じ時刻以降の約定は
   公開される
2. 初回購読で、再送が有効化の後に届く場合: 同上(③ の経路)
3. 再接続で、公開済みの約定と新しい約定が混ざった再送から、新しい約定だけが公開される。
   最後の公開から時計を 10 分以上進めた場合も、公開済みの約定は再公開されない
4. 同じ `time` で別の `tid` は公開され、同じ `tid` は公開されない
5. unsubscribe してから同じ銘柄を再購読しても、前の購読で公開した約定と再送は公開されない
6. `PENDING_BOOK` のまま再接続したとき、切断前の板で有効化せず、新しい板の時刻がアンカーになる
7. Borsa(`SEED_THEN_DELTA`)では有効化で公開する板の時刻(seed と、有効化前に受理した差分の
   うち最新のもの)がアンカーになる
8. 水位線で捨てた約定の診断は、1 回の呼び出しにつき 1 行。捨てた約定が無ければ出ない

### 既存テスト

- `BoundedTradeDeduplicatorTest` を削除する
- 約定の `time` が板の `time` より前になっているフィクスチャは新しい規則で公開されなくなる。
  現実的な時刻(板以降)に揃える。検証内容は弱めない。該当箇所は計画で洗い出す
- `SubscriptionRecord` 単体のテストは無いので、`beginGeneration` が `pendingBook` を消すことは
  session テスト 6 で担保する

### ビルドゲート

`docs/development.md` の品質ゲート(spotless / checkstyle / spotbugs / test / check /
clean build)をすべて通す。

## ドキュメント

README:

- Scope 節に 1 段落を加える: 購読を始めると取引所が直近の約定を再送するが、アダプターは最初の
  板より前の約定を公開しない。再接続時は、その銘柄で最後に公開した約定より新しい約定だけを
  復旧の時点で公開する。再送の件数は取引所次第(執筆時点で Hyperliquid 30 件、Borsa 100 件、
  Hyperdash なし)で、それを超える欠落は補わない
- `Not supported: ... gap filling` は残す(info エンドポイントによる補完はしない)
- データ健全性表の `TRADE_GAP_POSSIBLE` の対処欄「They are not backfilled.」を、再送の範囲内の
  約定だけが公開され、それ以上は補われない旨に改める
- Limits 節「there is no historical gap fill and trades can be missed」を同じ趣旨に揃える

`docs/development.md`: コード配置表から `hyperliquid.trade` の行を削除する。

実装完了後、本書の「状態」を実装済みに更新する。

## 実装後に実機で確認すべき事項

- Mainnet の閑散な spot(例 `@3`)を購読して、購読直後に過去の約定が描かれないこと
- 再接続(ネットワーク断)の後、取りこぼした約定だけが公開され、切断前の約定が重ねて描かれない
  こと
- Borsa と Hyperdash で同じ挙動になること

## 参照

- Hyperliquid Docs: WebSocket(再接続とスナップショット ack)、WebSocket Subscriptions
  (`isSnapshot`、`trades`、`WsTrade`)
- `docs/superpowers/specs/2026-09-19-trade-execution-grouping-design.md`(`TradeExecutions` と
  約定の経路)
- `docs/superpowers/specs/2026-09-06-borsa-hyperdash-data-sources-design.md`(リレーの trades)
