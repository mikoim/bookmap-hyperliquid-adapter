# Tick size 選択とオーダーブック集約 — 設計書

- 作成日: 2026-09-07
- 状態: レビュー待ち
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

Hyperliquid の `l2Book` は片側の深さがレベル数で固定されており(本家 20、Borsa 400)、価格のグルーピング
(`nSigFigs` / `mantissa`)を粗くするほど広い価格帯をカバーできる。現行アダプターはグルーピングを固定し
(Hyperliquid/Borsa は無し、Hyperdash は `nSigFigs: 5`)、Bookmap の Tick size も 1 候補
(`10^-(6-szDecimals)`)しか提示しない。その結果、たとえば HYPE(≈88 USDC、`szDecimals=2`)では pips が
0.0001 なのに取引所は 0.001 刻みでしか気配を出さないため、10 行中 9 行が空の板になっている。

本設計では次を実現する。

- Bookmap の Subscribe ダイアログで、Hyperliquid の UI と同等の Tick size 候補を提示する
- 選択された Tick size に合わせてサーバ側グルーピングを要求し、同じレベル数でより広い価格帯の板を得る
- サーバの刻みと Tick size が一致しない状況(価格の桁またぎ、保存済みワークスペースの古い Tick)でも
  板が常に正しく表示されるよう、公開時にアダプター側で集約する

要求の要約(ユーザー決定済み):

- 対象は Hyperliquid、Borsa、Hyperdash の 3 ソースすべて
- 既定の Tick size は「現在の価格帯で取引所が実際に気配を出す最も細かい刻み」に変更する
  (HYPE なら 0.0001 → 0.001)
- Borsa には常に `nLevels: 400` を付けて 400 レベルを受ける
- 桁またぎ時の再購読は行わない(ローカル集約で正しさを担保し、深さの劣化は許容する)

非目的(スコープ外):

- 価格の桁またぎに追従してサーバ側グルーピングを動的に変更する再購読
- Bookmap の Size granularity の候補追加
- Borsa/Hyperdash の集約結果が Hyperliquid 本家と一致するかの検証(現行方針どおり各ソースを信頼する)
- 板以外(trades)のサーバ側集約

## 実測による検証済みプロトコル事実(2026-09-07 観測)

Hyperliquid 公式ドキュメント(WebSocket Subscriptions / Info endpoint / Tick and lot size)と実接続で確認した。

| 項目 | Hyperliquid | Borsa | Hyperdash |
|---|---|---|---|
| `nSigFigs`(2〜5、省略=フル精度) | 有効 | 有効(本家と同じ意味) | 有効 |
| `mantissa`(1/2/5、`nSigFigs=5` のときのみ) | 有効 | 有効 | 未検証(本家互換として送る) |
| `nLevels` | 無視(常に 20) | 有効。上限 400(1000 は `Invalid subscription`)。省略時も 400 | 拒否(`Invalid subscription`) |
| 集約の丸め | bid 切り捨て、ask 切り上げ、サイズ合算(同時刻フレーム比較で確認) | 同じ刻みを観測 | 同じ刻みを観測 |
| 購読 ack | `nSigFigs`/`mantissa`/`fast` をエコー | 送った全フィールドをエコー | 送った全フィールドをエコー |

価格ルール(公式): 最大 5 有効数字、小数は `6 - szDecimals` 桁まで、整数価格は常に許可。したがって
参照価格の整数桁数を `d`(`P ≥ 1` なら桁数、`P < 1` なら `floor(log10 P) + 1 ≤ 0`)としたとき、
取引所が実際に出し得る最も細かい刻みは `q_native = max(min(10^(d-5), 1), 10^-(6-szDecimals))`。

`metaAndAssetCtxs`(Mainnet/Testnet 双方で確認): 応答は `[meta, ctxs]` の 2 要素配列。`ctxs` は
`meta.universe` と同じ長さ・同じ順序で、各要素に文字列 `markPx` を持つ。上場廃止銘柄にも `markPx` があり、
null は観測されなかった。レート制限の重みは `meta` と同じ 20。

Hyperliquid UI の HYPE ドロップダウン(0.001 / 0.002 / 0.005 / 0.01 / 0.1 / 1)は、この価格帯で
省略 / `5,m=2` / `5,m=5` / `4` / `3` / `2` に対応する。

## アーキテクチャ

### 基本方針

1. **内部の板はネイティブ格子で保持し、公開時に選択 Tick へ丸める。** ネイティブ格子とは
   `10^-(6-szDecimals)` 単位の int(現行 `PerpetualInstrument.toDepthPriceUnits` の結果)で、Hyperliquid
   の全価格を無損失で表現できる。公開時の丸めは bid 切り捨て・ask 切り上げ、同じ枠に落ちたサイズは合算
   (サーバ集約と同じ規則)。
2. **サーバ側グルーピングは深さの最適化にすぎず、正しさには関与しない。** サーバの刻みが Tick より粗ければ
   各レベルはそのまま 1 枠に写り、細かければ合算される。どちらでも板は正しい。
3. **Tick 候補とサーバパラメータの決定は純粋関数に閉じ込める。** 入力は参照価格・`priceDecimals`・
   選択 Tick、出力は候補リスト・既定値・`L2BookParameters`。

### 新規コンポーネント

#### `model.TickSizePlan`(純粋関数、`model` パッケージ)

- `static List<BigDecimal> candidates(BigDecimal referencePrice, int priceDecimals)`
  - `q_native` を最小 Tick とし、`{1,2,5}×10^(d-5)`、`10^(d-4)`、`10^(d-3)`、`10^(d-2)` のうち `q_native` の
    整数倍であるものを加え、重複除去して昇順に返す
  - `referencePrice` が null または非正のときは `[10^-priceDecimals]`(現行と同じ 1 候補)
  - 例: HYPE(87.8, `priceDecimals=4`)→ `0.001, 0.002, 0.005, 0.01, 0.1, 1`。
    ETH(2519, `priceDecimals=2`)→ `0.1, 0.2, 0.5, 1, 10, 100`。
    BTC(80203, `priceDecimals=1`)→ `1, 10, 20, 50, 100, 1000, 10000`。
    低価格銘柄(0.0012345, `priceDecimals=6`)→ `0.000001, 0.00001, 0.0001`
- `static BigDecimal defaultTick(BigDecimal referencePrice, int priceDecimals)` — `candidates` の先頭
- `static L2BookParameters parametersFor(BigDecimal tick, BigDecimal referencePrice, int priceDecimals, Integer nLevels)`
  - サーバ量子の候補 `{省略: q_native, (5,null): 10^(d-5), (5,2): 2×10^(d-5), (5,5): 5×10^(d-5), (4): 10^(d-4), (3): 10^(d-3), (2): 10^(d-2)}`
    のうち、`q_native` の整数倍であり、かつ `tick` を割り切る**最も粗い**ものを選ぶ。該当が無ければ省略。
    量子が同値のものが複数ある場合(例: HYPE で省略と `(5,null)` がともに 0.001)は省略を優先する
  - `d` は `BigDecimal` の `precision() - scale()` で求める(`87.785` → 2、`0.0012345` → -2、`80203.0` → 5)
  - `nLevels` は与えられたときそのまま付ける(Borsa のみ 400)
  - 参照価格が無い場合は `nSigFigs`/`mantissa` を省略する

#### `book.PriceBucketer`(公開時の丸め、`book` パッケージ)

- コンストラクタ `PriceBucketer(PerpetualInstrument instrument, BigDecimal tick)`。`tick` はネイティブ格子の
  整数倍でなければならない(`ratio = tick / 10^-priceDecimals` を正の整数として保持)
- `int bidBucket(int nativeUnits)` = `floorDiv(nativeUnits, ratio)`、
  `int askBucket(int nativeUnits)` = `ceilDiv(nativeUnits, ratio)`
- `int[] nativeRange(int bucket, boolean bid)` — その枠に属するネイティブ単位の閉区間。
  bid: `[bucket×ratio, (bucket+1)×ratio - 1]`、ask: `[(bucket-1)×ratio + 1, bucket×ratio]`
- `double tradePriceUnits(BigDecimal price) throws ValueConversionException` = `price / tick` を
  `BigDecimal` で正確に割って double 化。`tick = m×10^k`(`m ∈ {1,2,5}`)なので割り切れる。端数は許容する
  (Bookmap の trade 価格は double)。null・非正は `NON_POSITIVE`、`2^53` 超は `TRADE_PRICE_OUT_OF_RANGE`
  で失敗し、現行 `toTradePriceUnits` と同じ例外契約を保つ
- `ratio` は `long` で計算し、`Integer.MAX_VALUE` を超える Tick は不正として拒否する
  (`IllegalArgumentException`。`Provider` が事前に検証するため通常は到達しない)
- `ratio == 1` のとき丸めは恒等写像となり、現行の挙動と一致する

#### `PerpetualInstrument` の拡張

- `BigDecimal referencePrice`(nullable)を追加し、`metaAndAssetCtxs` の `markPx` を保持する
- `pips()` は「ネイティブ格子」を意味するものとして名前も挙動も変えない(Javadoc で意味を明記する)

### 既存コンポーネントの変更(最小差分)

| コンポーネント | 変更 |
|---|---|
| `HyperliquidConnector` | メタデータ要求本文を `{"type":"metaAndAssetCtxs"}` に変更 |
| `HyperliquidMetaParser` | ルートが 2 要素配列であること、`ctxs` が `universe` と同じ長さであることを検証。各 `markPx` を文字列として読み、正の数なら `referencePrice` に設定、要素が非オブジェクト・欠落・null・非数・非正なら null(銘柄自体は受け入れる)。`universe` の検証ロジックは現行のまま |
| `SourceProfile` | `l2BookParameters()` を廃止し、代わりに `Integer nLevels()`(Borsa: 400、他: null)を提供。Hyperdash の固定 `nSigFigs: 5` を撤廃 |
| `HyperliquidSessionApi` / `HyperliquidSession.subscribe` | 引数に `BigDecimal tick` を追加。`TickSizePlan.parametersFor` で `L2BookParameters` を決め、`SubscriptionRecord` に `PriceBucketer` を渡す |
| `SubscriptionRecord` | `PriceBucketer` を保持し、`OrderBookSnapshotDiff` / `DeltaOrderBook` に渡す |
| `OrderBookSnapshotDiff` | 正規化はネイティブ単位のまま(重複価格の検出も現行どおり)。差分計算の前に `PriceBucketer` で枠へ合算し、枠単位の `DepthUpdate` を出す。ベースラインも枠単位で保持 |
| `DeltaOrderBook` | ステージ済み/公開済みの板をネイティブ単位で保持するのは現行どおり。公開済み板の枠合計を別途保持し、差分適用時は影響した枠の合計を `nativeRange` で再計算して枠単位の `DepthUpdate` を出す。`publishStaged` / `replacePublished` / `clearPublished` も枠単位で出す |
| `HyperliquidSession.convertTrade` | `record.instrument().toTradePriceUnits` を `record.bucketer().tradePriceUnits` に置き換え |
| `SessionSink.onInstrumentAdded` | 引数を `(PerpetualInstrument instrument, BigDecimal tick)` に変更 |
| `Provider.pipsFor` | `TickSizePlan.candidates` / `defaultTick` を `DefaultAndList<Double>` で返す |
| `Provider.subscribe` | `SubscribeInfoCrypto` なら `pips` を Tick として渡す。それ以外、または非正・ネイティブ格子の整数倍でない・`ratio` が int に収まらない値なら `defaultTick` にフォールバックし `Log.warn` に記録 |
| `Provider.onInstrumentAdded` | `InstrumentInfo.pips` に選択 Tick を使う。`formatPrice` はこの `InstrumentInfo.pips` を参照するため変更不要 |
| `L2BookParameters` | 変更なし(`nSigFigs`/`nLevels`/`mantissa` の送出は既存) |
| `HyperliquidMessageParser` | 変更なし(ack の `nSigFigs`/`nLevels`/`mantissa`/`fast` は既に許容) |

`double` と `BigDecimal` の往復: Bookmap は pips を `double` で扱う。`candidates` の各値は
`BigDecimal.doubleValue()` で渡し、`subscribe` で受けた `double` は `BigDecimal.valueOf(pips)` で戻す。
候補は `m×10^k` 形式なので往復で同値になる。

## データフロー

### ログインから候補提示

1. `login` → `metaAndAssetCtxs` → `HyperliquidMetaParser` が `referencePrice` 付きの `PerpetualInstrument` を返す
2. `Provider.onKnownInstruments` が銘柄表を保持
3. Bookmap が Subscribe ダイアログで `pipsFor` を呼ぶ → `TickSizePlan.candidates` / `defaultTick`

### 購読成立

1. Bookmap が `subscribe(SubscribeInfoCrypto)` を呼ぶ → `Provider` が Tick を検証し `session.subscribe(symbol, exchange, type, tick)`
2. `HyperliquidSession` が `TickSizePlan.parametersFor(tick, referencePrice, priceDecimals, profile.nLevels())`
   で `L2BookParameters` を決め、`SubscriptionRecord`(`PriceBucketer` 付き)を作って `l2Book` と `trades` を購読
3. 初回板の受理後 `onInstrumentAdded(instrument, tick)` → `InstrumentInfo.pips = tick`

### 板イベント(SNAPSHOT: Hyperliquid / Hyperdash)

1. `OrderBookSnapshotDiff.validate` がネイティブ単位に正規化(現行)
2. `PriceBucketer` で bid 切り捨て・ask 切り上げの枠に合算
3. 枠単位のベースラインと差分を取り `DepthUpdate` を出す

### 板イベント(SEED_THEN_DELTA: Borsa)

1. シードはネイティブ単位でステージ(現行)。公開時に枠へ合算して全レベルを出す
2. 差分はネイティブ単位の板を更新したうえで、影響した枠ごとに `nativeRange` の合計を再計算し、
   合計が変わった枠だけ `DepthUpdate`(0 なら削除)を出す

### 再接続・回復

現行のまま。再接続時も購読時に決めた `L2BookParameters` と `PriceBucketer` を再利用する
(参照価格は再取得しない)。

### trades

`PriceBucketer.tradePriceUnits` で `px / tick` を渡す。サイズ単位は現行どおり。

## エラー処理と運用制約

- `metaAndAssetCtxs` のルートが配列でない、長さが 2 でない、`ctxs` が `universe` と長さ不一致 →
  現行のメタデータ失敗と同じ経路(ログイン失敗)
- `markPx` の欠落・null・非数・非正 → その銘柄の `referencePrice` は null。候補は 1 つ(ネイティブ格子)、
  サーバ集約は無し。ログインは成功する
- `subscribe` の Tick が不正 → 既定 Tick にフォールバックし警告ログ。Bookmap 側には既定 Tick の
  `InstrumentInfo` が届く
- 購読 ack の照合は `SubscriptionKey.equals` が `coin` と `type` だけを比較するため、パラメータの有無に
  影響されない(現行 Hyperdash の `nSigFigs: 5` で実証済み)
- 価格が購読時の桁を上に越える → サーバの刻みが Tick より粗くなり、レベルは疎になるが正しい。
  下に越える → サーバの刻みが細かくなり、ローカル合算で正しさを保つが 20/400 レベルのカバー範囲は狭まる。
  いずれも再ログイン(参照価格の再取得)と再購読で回復する。README に明記する
- 既存の `UNSUPPORTED_PRICE`(ネイティブ単位が int を超える)は現行のまま
- Borsa の `nLevels=400` は購読 JSON のサイズと初回シードのサイズ(約 28KB)を増やすが、現行も省略時に
  400 レベル受けているため実質的な負荷増は無い

## テスト戦略

- `TickSizePlanTest`: HYPE / ETH / BTC / 低価格銘柄の候補と既定値。`parametersFor` の全分岐
  (省略、`5`、`5+mantissa`、`4`〜`2`、割り切れない Tick、参照価格なし、`nLevels` 付き)。
  桁またぎと古い Tick(HYPE に 0.0001)でも例外を出さないこと
- `PriceBucketerTest`: `ratio=1` の恒等性、bid/ask の丸め方向、`nativeRange`、`tradePriceUnits` の正確性
  (`87.9065 / 0.002 = 43953.25`)
- `OrderBookSnapshotDiffTest`: 実測値(bid 87.784/87.783 → 87.78 = 3.68、ask 87.785/87.786/87.789 → 87.79 = 202.34)
  と一致すること。枠の消滅で削除が出ること
- `DeltaOrderBookTest`: 差分で枠合計が増減・消滅するケース。窓外価格の差分が新しい枠を作るケース
- `HyperliquidMetaParserTest`: 2 要素配列の受理、長さ不一致の拒否、`markPx` 欠落時の null
- `ProviderTest`: `pipsFor` の候補と既定値、`SubscribeInfoCrypto.pips` の受け渡し、不正 Tick のフォールバック、
  `InstrumentInfo.pips` が選択 Tick になること
- `ProviderEndToEndTest` / セッションテスト: 粗い Tick で細かい価格が合算されて公開されること。
  Borsa プロファイルで `nLevels: 400` が購読 JSON に含まれること。Hyperdash が `nSigFigs: 5` 固定でなくなること
- 実機 Bookmap: HYPE の候補表示(0.001〜1)、0.01 選択時に板が約 ±4.5%(Borsa)/ 20 レベル(Hyperliquid)
  をカバーすること
- 既存の品質ゲート(spotless / checkstyle / spotbugs / test / verifyJava8Bytecode)を通すこと

## 参照

- Hyperliquid Docs: WebSocket Subscriptions(`l2Book` の `nSigFigs` / `mantissa` / `fast`)
- Hyperliquid Docs: Info endpoint(`l2Book` の `nSigFigs` 有効値、`mantissa` 制約)、Perpetuals(`metaAndAssetCtxs`)
- Hyperliquid Docs: Tick and lot size(有効数字・小数桁・整数価格のルール)
- Bookmap API javadoc: `SubscribeInfoCrypto.pips`、`InstrumentInfo.pips`、`Layer1ApiProviderSupportedFeatures.setPipsFunction`
- 前回設計: `docs/superpowers/specs/2026-09-06-borsa-hyperdash-data-sources-design.md`
