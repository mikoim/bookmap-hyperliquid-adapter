# HIP-3 市場サポート — 設計書

- 作成日: 2026-09-07
- 状態: レビュー待ち
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

現行アダプターはログイン時に `{"type":"metaAndAssetCtxs"}` を 1 回だけ発行する。この情報リクエストは
`dex` を省略すると「最初の perp dex」、すなわちバリデータが運用する市場だけを返す。したがって
HIP-3(builder-deployed perpetuals)で展開された市場、たとえば `xyz:CL`(原油、Mainnet 実在)は
Bookmap の銘柄一覧に一切現れない。

本設計では次を実現する。

- HIP-3 市場を含む全 perp dex の銘柄を Bookmap の銘柄一覧に載せる
- HIP-3 銘柄でも既存銘柄と同じ品質の Tick size 候補(参照価格に基づく)を提示する
- Mainnet / Testnet を区別せず、dex 数の増加に対して破綻しない取得方式にする

要求の要約(ユーザー決定済み):

- ライブ銘柄を持つ dex を自動検出する。ユーザーに dex を選ばせる UI は追加しない
- マーク価格は WebSocket の `fastAssetCtxs` から取得する(案 2 を採用)
- リレー(Borsa / Hyperdash)選択時は Hyperliquid Mainnet へ ctx 専用の WebSocket 接続を 1 本追加する

非目的(スコープ外):

- HIP-3 固有のメタデータ(collateralToken、marginTable、growthMode、open interest cap)の公開
- HIP-3 deployer アクション、取引、口座情報
- スポット市場(`sac` サブスクリプションが返す内容)
- リレー 2 社が HIP-3 の板を Hyperliquid 本家と一致させているかの検証(現行方針どおり各ソースを信頼する)

## 実測による検証済みプロトコル事実(2026-09-07 観測)

Hyperliquid 公式ドキュメントと、Mainnet / Testnet への実接続で確認した。

### 銘柄名と WebSocket

HIP-3 銘柄の名前は必ず `{dex}:{coin}` 形式で、メタデータ応答の時点で完全修飾されている
(例 `xyz:CL`、`para:AVGO`、`io:OAI`)。dex 名は 6 文字以内。

`l2Book` / `trades` の購読は 3 ソースすべてが完全修飾名を受け付ける。

| ソース | `l2Book` `xyz:CL` | `trades` `xyz:CL` | `fastAssetCtxs` | `pac` |
|---|---|---|---|---|
| Hyperliquid(`wss://api.hyperliquid.xyz/ws`) | 正常 | 正常 | 正常 | 正常 |
| Borsa(`wss://ws.borsa.cc/`) | 正常(400 段) | — | 拒否(パースエラー) | 拒否 |
| Hyperdash(`wss://api.hyperdash.com/ws/orderbook`) | 正常(20 段) | 正常 | 拒否(`invalid subscription`) | 拒否 |

したがって板・約定の経路は銘柄名を通すだけでよく、**変更を要しない**。

### メタデータ情報リクエスト

| リクエスト | 応答 | 重み |
|---|---|---|
| `{"type":"metaAndAssetCtxs","dex":"<name>"}` | `[meta, ctxs]`。1 dex 分のみ | 20 |
| `{"type":"allPerpMetas"}` | **全 dex の meta のみの配列**。`ctxs` は含まない | 20 |
| `{"type":"perpDexs"}` | dex 名一覧(先頭は `null` = バリデータ dex) | 20 |
| `{"type":"allDexsAssetCtxs"}` | HTTP 422。REST では提供されない | — |

`allPerpMetas` の実応答要素は `{"universe": [...], "marginTables": [...], "collateralToken": N}` の
3 キーで、公式ドキュメントの記載(`[meta, ctxs]` のペア)とは異なる。`universe` の各要素は
`name` / `szDecimals` / `isDelisted` を持ち、既存の検証規則(`szDecimals` は 0〜6、名前は非空・重複なし)
がそのまま適用できる。応答サイズは Mainnet 79 KB、Testnet 644 KB。

dex 数の実測: Mainnet 11 dex(ライブ銘柄を持つのは 5、ライブ銘柄計 315)、
Testnet 260 dex(ライブは 206、ライブ銘柄計 628)。したがって dex ごとに `metaAndAssetCtxs` を
発行する方式は Testnet で 207 リクエスト・重み 4140 となり、IP あたり 1200/分 の制限を超える。

### `fastAssetCtxs`(採用する マーク価格の供給元)

購読メッセージ `{"method":"subscribe","subscription":{"type":"fastAssetCtxs"}}`。公開エンドポイントで
動作し、Mainnet / Testnet の双方で確認した。

- ペイロードは base64 文字列。デコード後は **raw DEFLATE(RFC 1951)**。zlib(RFC 1950)や gzip の
  ラッパーは付かない
- 展開後は銘柄名をキーとする辞書:
  `{"BTC":{"markPx":"79394.0","midPx":"79381.5"}, "xyz:CL":{"markPx":"92.283","midPx":"92.282"}}`
- 初回メッセージが全銘柄のスナップショット、以降は**更新のあった銘柄のみ**を含む差分。更新のない
  フィールドは省略される。したがって受信側でマージが必要
- 規模と頻度: Mainnet 1934 銘柄(うち HIP-3 281)、展開後 85 KB、回線 23 KB。
  Testnet 5541 銘柄(うち HIP-3 714)、回線 **50 KB**。更新は毎秒 1 回、回線 2.3 KB/s

`pac` も同じ符号化で全 dex の資産コンテキストを返すが、公式ドキュメントに記載がなく、内容が
`[[dex名, [ctx, ...]], ...]` の**インデックス整合**に依存する。`fastAssetCtxs` は銘柄名キーで、かつ
ドキュメント記載済みであるため、こちらを採用する。

### 転送層の既存の上限(本変更で顕在化する)

Jetty 9.3 の `WebSocketPolicy` 既定値は `maxTextMessageSize` = 65536 バイト(バイトコードで確認)。
`JettyHyperliquidTransport` はポリシーを設定していないため、この既定値が有効になっている。

実測フレームサイズ: Testnet の `fastAssetCtxs` スナップショット **50 KB**、Borsa の 400 段 BTC 板
**28.8 KB**。既に余裕が小さく、Testnet の dex 増加で確実に上限を踏む。

## アーキテクチャ

### 基本方針

メタデータ取得を「静的属性」と「参照価格」に分離する。

- 静的属性(名前、`szDecimals`、上場状態)は REST `allPerpMetas` 1 回で全 dex 分を取得する
- 参照価格(`markPx`)は WebSocket `fastAssetCtxs` から取得し、セッション中は差分でライブに保つ

この分離により、dex ごとの REST ファンアウト、dex の順位付け、取得上限、部分失敗ポリシーが
すべて不要になる。Mainnet と Testnet で挙動が分岐しない。

Bookmap の symbol には完全修飾名 `xyz:CL` をそのまま使う。素名(`CL`)を symbol、dex 名(`xyz`)を
exchange に分ける案は採らない。Mainnet だけでも `STX` `SNDK` `AVGO` `NBIS` `UNITREE` `IREN` `NET`
`CRWD` `RDDT` `AAOI` の 10 件が素名で衝突しており、現行の
`setExchangeUsedForSubscription(false)` では区別できない。これを反転すると保存済みワークスペースの
互換が壊れる。完全修飾名なら購読・パース・エイリアスの全経路が無変更で済む。

### 新規コンポーネント

#### `parse.HyperliquidMetaParser.parseAllPerpMetas(String)`

`allPerpMetas` 応答を検証して `List<PerpetualInstrument>` を返す。既存の `parse(String)` は
使われなくなるため削除する。

- 応答はオブジェクトの配列でなければならない。各要素は `universe` 配列を持つ
- 各 `universe` 要素の検証は現行の `parseEntry` と同一(名前は非空、`szDecimals` は 0〜6 の整数、
  `isDelisted` は真偽値)
- 名前の重複検査は**全 dex 横断**で行う。HIP-3 名の接頭辞により本来重複しないが、検証は維持する
- `isDelisted` の除外は全要素の検証が済んだ後に行う(現行と同じ「完全な universe として検証してから
  除外する」規則)
- 参照価格は付けない(`PerpetualInstrument` の `referencePrice` は null)。マーク価格は
  `fastAssetCtxs` から後で与える
- `marginTables` / `collateralToken` は読まない

#### `parse.AssetContextCodec`

`fastAssetCtxs` の `data` 文字列を銘柄名 → `markPx` の写像に復号する純粋関数。

- `java.util.Base64.getDecoder()`(Java 8 で利用可能)でデコード
- `java.util.zip.Inflater(true)` で raw DEFLATE を展開する
- 展開後サイズが上限(8 MB)を超えたら中断し、`ProtocolException` を投げる
- UTF-8 として解釈し Gson で解析する。値は `{"markPx": "<decimal>"}` を持つオブジェクト。
  `markPx` が無い、文字列でない、正の 10 進数でない、あるいは桁数が異常(`precision > 20` または
  `|scale| > 20`)な項目は**その銘柄だけ**捨てる。既存の `referencePrice` 判定規則と同一にする
- 復号・展開・解析のいずれかが失敗した場合はフレーム全体を無効として診断のみを出す

#### `session.AssetContextStore`

銘柄名 → `markPx` のマージ済み写像を保持する。

- `applySnapshot(Map)`: 写像を置き換える
- `applyDelta(Map)`: 与えられた銘柄のみ更新する。含まれない銘柄は前値を保持する
- `markPrice(String symbol)`: 現在値、未知なら null
- 差分に含まれるが既知銘柄一覧に無い銘柄(スポット、上場廃止など)は保持してよい。参照時に
  銘柄一覧側で無視される

スナップショットと差分の区別は、購読直後の最初の `fastAssetCtxs` フレームをスナップショットとして
扱うことで行う。判定は `fastAssetCtxs` を運んでいる接続の世代に紐づけてリセットする。

判定と `applySnapshot` / `applyDelta` の選択は、その接続を持つ側が行う。Hyperliquid 選択時は
`HyperliquidSession.onSocketOpened` が、リレー選択時は `AssetContextFeed.onSocketOpened` が
フラグをリセットし、どちらも `onAssetContexts(map, snapshot)` の `snapshot` 引数で結果を伝える。
`AssetContextStore` 自身は世代を持たない。

#### `session.AssetContextFeed`(リレー選択時のみ)

Hyperliquid Mainnet の WebSocket に `fastAssetCtxs` 専用の接続を 1 本張る。

- 自身の `HyperliquidConnector` を保持し、`SourceProfile.of(HYPERLIQUID, MAINNET)` で起動する。
  メタデータ REST は発行しない(下記「既存コンポーネントの変更」参照)
- 転送層(`HyperliquidTransport`)は本接続のものを**共有する**。Jetty の `HttpClient` /
  `WebSocketClient` は複数接続を扱えるため、専用の転送層を作ると Bookmap の JVM に不要なスレッド
  プールが増えるだけになる。共有に伴う所有権の扱いは下記「既存コンポーネントの変更」で定める
- **この接続の失敗はログイン成否とマーケットデータに一切影響させない**。接続できない、あるいは
  切断された場合は既存の再接続バックオフに任せ、診断を 1 回出す。復旧までは参照価格が固定される
- プロセス共有予算の同時接続枠(10)と接続試行枠(30/分)を 1 本分消費する。`fastAssetCtxs` は
  `SubscriptionKey` を持たないため、購読枠(1000)は消費しない

`AssetContextFeed` は ctx コネクタの `HyperliquidConnector.Listener` を**自分で実装する**。
`HyperliquidSession` は ctx コネクタのリスナーにはならない。両コネクタの `generation` は独立した
連番であり、`HyperliquidSession` の制御イベント経路(`handleControls`)は
`generation == currentGeneration` で本接続の世代を照合するため、ctx コネクタの世代をそこへ流すと
一致・不一致が偶然に決まる。リスナーの契約は次のとおり。

| コールバック | `AssetContextFeed` の動作 |
|---|---|
| `onMetadata` | 到達しない(`startWithoutMetadata` のため)。呼ばれたら診断のみ |
| `onInitialFailure` | 診断を 1 回出す。`sink.onLoginFailed` は呼ばない |
| `onSocketOpened` | スナップショット判定をリセットし、`fastAssetCtxs` 購読フレームを 1 通送る |
| `onFrame` | `HyperliquidMessageParser` で解析する。`PONG` は **`connector.acceptPong(generation)` へ返す**。`ASSET_CONTEXTS` は **ctx コネクタ自身の**世代が現行世代であることを確かめてセッションへ引き渡す。`SUBSCRIPTION_ACK` と診断は捨てる。`SUBSCRIPTION_ERROR` は診断のみ(本接続の `handleSubscriptionError` には流さない) |
| `onFrameSent` | 何もしない |
| `onDisconnected` | 診断を 1 回出す。`sink.onConnectionLost` は呼ばない |

`PONG` の返送は必須である。ctx コネクタも既存のハートビートを送るため、`acceptPong` を呼ばないと
`schedulePongDeadline` が毎回発火し、この接続は無限に切断と再接続を繰り返す。

`ASSET_CONTEXTS` の引き渡しは `AssetContextFeed` が本接続の状態レーン
(`StateEventDispatcher.submitControl`、両コネクタで共有)へ投入し、セッションの専用入口
`HyperliquidSession.onAssetContexts(Map<String, BigDecimal>, boolean snapshot)` を呼ぶ形で行う。
本接続の世代照合は適用しない(ctx 接続と本接続の生存期間は独立しているため)。

### 既存コンポーネントの変更(最小差分)

#### `model.SubscriptionType` — 変更しない

`fastAssetCtxs` は銘柄に紐づかないため `SubscriptionType` にも `SubscriptionKey` にも追加しない。
`SubscriptionKey` は非空の `coin` を要求し、`desired` / `activationDeadlines` の突合や ack タイム
アウトによる「銘柄未検出」通知の対象になるため、そこへ載せると既存の不変条件が壊れる。購読 JSON は
`{"method":"subscribe","subscription":{"type":"fastAssetCtxs"}}` の定数として持つ。

#### `OutboundMessage`

接続スコープの購読を表す `Kind.SUBSCRIBE_FEED` を追加する。`subscription` は null を許す。
不変条件は「`PING` と `SUBSCRIBE_FEED` のみ `subscription` を省略できる」に更新する。

#### `HyperliquidConnector`

- `start(SourceProfile)` は `{"type":"allPerpMetas"}` を発行するよう変更する
- `startWithoutMetadata(SourceProfile)` を追加する。メタデータ REST を発行せず、ただちに接続を試みる。
  `Listener.onMetadata` は呼ばれない。`AssetContextFeed` が使う
- 接続世代が開いたときに、`fastAssetCtxs` 購読フレームを 1 通送る経路を設ける。この 1 通は
  再接続時のフレーム予約数(現行 `desired.size() + 1`)に含める。`socketOpened` の予約検算
  (`reservedFramesRemaining() > desired.size() + 1`)も同じ数に揃える
- `SubscriptionKey` を持たないため `desired` には登録せず、ack タイムアウト監視も行わない。
  `desired` は `TreeMap` で自然順序のため null キーの照会は NPE になる。`sendWhenPossible` と
  `isStillDesired` の `desired` 参照は `SUBSCRIBE_FEED`(`subscription == null`)を先に除外する
- `startWithoutMetadata` も `transport.start()` を呼ぶ。共有した転送層は 2 回開始されるため、
  `HyperliquidTransport.start()` は**冪等であること**を契約とし、Javadoc に明記する
  (Jetty の `AbstractLifeCycle.start()` は開始済みなら何もしないので現行実装は満たしている。
  `FakeHyperliquidTransport` も同じ契約に合わせる)
- 転送層の所有権を明示する。現行の `close()` は無条件に `transport.close()` を呼ぶため、
  1 つの転送層を 2 つのコネクタで共有できない。コンストラクタで所有の有無を受け取り、
  所有しないコネクタは `close()` で転送層を閉じない。`AssetContextFeed` のコネクタを非所有として
  生成し、セッションは **ctx コネクタを先に、本コネクタを後に**閉じる

#### `parse.HyperliquidMessageParser`

- `channel` が `fastAssetCtxs` のフレームを受け付け、`AssetContextCodec` の結果を
  `ControlEvent` として返す
- `subscriptionResponse` の `subscription` に `coin` が無い場合(`fastAssetCtxs` の ack)は
  `ParsedFrame.ignored` を返す。現在は無効フレーム扱いになる
- `error` チャネルの本文が `fastAssetCtxs` を含む場合は、銘柄に紐づかない購読エラーとして
  診断のみを出す。既存の銘柄別購読エラー経路には流さない

#### `model.ControlEvent`

`Kind.ASSET_CONTEXTS` を追加し、`Map<String, BigDecimal> markPrices()` を持たせる
(他の種別では空写像)。生成は静的ファクトリで行う。

#### `session.HyperliquidSession`

- `onMetadata` で受け取った銘柄をそのまま `onKnownInstruments` に流す(現行と同じタイミング。
  この時点では参照価格なし)
- 資産コンテキストの入口は 1 つにする。Hyperliquid 選択時は本接続の `handleControls` が
  `ASSET_CONTEXTS` 制御イベント(本接続の世代照合を通過したもの)から、リレー選択時は
  `AssetContextFeed` が直接、いずれも `onAssetContexts(Map<String, BigDecimal>, boolean snapshot)`
  を状態レーンで呼ぶ
- `onAssetContexts` は写像を `AssetContextStore` に適用したうえで、**セッションの `instruments`
  マップを `markPrice` 付きの `PerpetualInstrument` で置き換える**。`sink.onKnownInstruments` に
  渡すだけでは不十分である。`handleSubscribe` は `instruments.get(symbol)` の `referencePrice()`
  を `TickSizePlan.defaultTick` と `TickSizePlan.parametersFor` の双方に渡しており、ここが null の
  ままだとサーバ側グルーピングが常に省略され、既存の Tick size 設計が無言で退行する
- 作り直しは `symbol` / `szDecimals` を変えず `referencePrice` だけを差し替える。`pips()` と
  `priceDecimals()` は不変なので、購読済み `SubscriptionRecord` が保持する
  `PerpetualInstrument` と `PriceBucketer` は据え置いてよい(再購読も再計算も行わない)
- 置き換え後に `onKnownInstruments` を再発行する
- 再発行の条件は次の 2 つだけとする。
  1. スナップショット受信時(接続世代ごとに 1 回)は必ず再発行する
  2. 差分受信時は、既知銘柄のいずれかの `markPx` が変化しており、かつ前回の再発行から 5 秒以上
     経過している場合に再発行する
  Tick size 候補が変わるのは価格が 10 の冪をまたいだときだけなので、毎秒の再構築には価値がない。
  この間引きにより、Testnet の 628 銘柄でも再構築は 5 秒に 1 回に収まる
- リレー選択時は `AssetContextFeed` を生成・所有し、`close()` で確実に閉じる。Hyperliquid 選択時は
  本接続に `fastAssetCtxs` を載せ、追加接続は作らない

#### `transport.JettyHyperliquidTransport`

- `WebSocketClient` のポリシーで `maxTextMessageSize` と `maxBinaryMessageSize` を 1 MB に設定する
- `BufferingResponseListener` の応答サイズ上限を 4 MB として明示する(Testnet の `allPerpMetas` は
  644 KB、Jetty の既定は 2 MB)

#### `Provider.ProductionSessionFactory`

`AssetContextFeed` 用のコネクタを生成する手段をセッションに渡す。セッションが本番の転送層を直接
組み立てないという現行の方針(テスト容易性のため注入する)を守るため、ファクトリが供給側になる。
供給されたコネクタは本接続と同じ転送層・スケジューラ・時計・予算・状態レーンを使い、転送層は
所有しない。リレー選択時にのみ生成する。

#### 変更しないもの

`model.PerpetualInstrument`、`model.TickSizePlan`、`model.SubscriptionKey`、
`model.SubscriptionType`、`book` パッケージ、`trade` パッケージ、`budget` パッケージ、
`HyperliquidFieldManager`、`MarketDataSource`、`SourceProfile`、`Provider` の Layer 0 API 実装部分
(`login` / `subscribe` / `pipsFor` / `formatPrice` など)。

## データフロー

### ログインから候補提示

1. `Provider.login` → `SourceProfile` を解決(既存どおり。リレーは Mainnet 固定)
2. `HyperliquidConnector.start` → `POST {"type":"allPerpMetas"}`
3. `parseAllPerpMetas` → 全 dex のライブ銘柄 → `onMetadata` → `sink.onKnownInstruments`
   (参照価格なし。Tick size 候補はネイティブグリッドのみ)
4. WebSocket 接続確立 → `onLoginSuccessful`(現行と同じ)
5. `fastAssetCtxs` を購読(Hyperliquid なら本接続、リレーなら `AssetContextFeed` の専用接続)
6. スナップショット受信 → `AssetContextStore` を初期化 → `onKnownInstruments` を再発行。
   以降 Tick size 候補は参照価格に基づく完全なものになる

手順 3 と 6 の間に Subscribe ダイアログを開いた場合、候補はネイティブグリッドのみになる。通常は
接続確立の直後(1 秒以内)にスナップショットが届くため、実際に観測される窓は非常に短い。

### セッション中の更新

7. 毎秒の差分を `AssetContextStore` にマージ。既知銘柄の価格が変化しており、かつ前回の再発行から
   5 秒以上経過していれば `onKnownInstruments` を再発行する

これにより、現行 README に記載している「大きく動いた後は再ログインして候補を更新する」という
運用上の制約が解消される。

### 再接続

接続世代が開き直るたびに `fastAssetCtxs` を購読し直し、`AssetContextStore` のスナップショット
判定をリセットする。既存の板・約定の回復手順は変更しない。

## エラー処理と運用制約

- `allPerpMetas` の失敗(ネットワーク、HTTP 非 2xx、プロトコル違反)は現行の
  `metaAndAssetCtxs` 失敗と同じくログイン失敗として扱う
- `fastAssetCtxs` の購読エラー、復号失敗、展開上限超過、専用接続の失敗はいずれも**ログイン失敗に
  しない**。診断を出し、参照価格なし(または最後に成功した値)で動作を継続する。板と約定には
  影響しない
- 復号失敗が連続する場合でも自動的な再購読は行わない。接続世代が変わったときに再購読される
- リレー選択時は WebSocket 接続を 2 本使用する。プロセス共有予算の同時接続上限は 10 のままなので、
  同一 JVM でリレーを使うプロバイダーは最大 5 個までとなる。README に明記する
- Testnet の銘柄一覧は 628 件になる。大半は開発者が作った試験用 dex の銘柄である。README に明記する
- `fastAssetCtxs` の展開・解析は WebSocket コールバックスレッドで行う。スナップショット 85 KB は
  ログイン直後の 1 回のみ、以降の差分は毎秒 2〜5 KB であり、既存の板フレーム処理に比べて小さい

## テスト戦略

### `AssetContextCodec`

- 実キャプチャの base64 + raw DEFLATE 文字列を復号し、期待する銘柄数と `xyz:CL` の値を得る
- base64 として不正、DEFLATE として不正、UTF-8 として不正、JSON として不正な入力を拒否する
- 展開後サイズが上限を超える入力を拒否する
- `markPx` が欠落・非文字列・非正・桁数異常の項目だけが落ち、他の銘柄は残る

### `HyperliquidMetaParser.parseAllPerpMetas`

- 実応答形(3 キーのオブジェクト配列)から全 dex のライブ銘柄を得る
- 全銘柄が `isDelisted` の dex は結果に寄与しない(Mainnet で 6 dex が該当)
- `szDecimals` が範囲外・非整数、名前が空・重複、`isDelisted` が非真偽値の場合に
  `ProtocolException` を投げる
- 検証は除外より先に行われる(不正な上場廃止銘柄でも失敗する)

### `AssetContextStore`

- スナップショット後に差分をマージし、差分に含まれない銘柄が前値を保つ
- 差分に含まれるが既知銘柄でない項目が既存の値を壊さない
- 接続世代のリセットで次のフレームが再びスナップショットとして扱われる

### `HyperliquidSession`

- 接続世代ごとに `fastAssetCtxs` の購読フレームが 1 通送られる
- スナップショット適用で `onKnownInstruments` が再発行され、`PerpetualInstrument` に参照価格が入る
- スナップショット適用**後**の `subscribe` が、その参照価格に基づく `nSigFigs`/`mantissa` 付きの
  `l2Book` 購読 JSON を送る(セッションの `instruments` マップが更新されていることの検証)
- ctx 接続の `pong` フレームがその接続の `acceptPong` に返り、ハートビート周期を越えても
  切断されない(擬似時計と `FakeHyperliquidTransport` で検証する)
- ctx 接続の世代と本接続の世代が食い違っていても、資産コンテキストが適用される
- 既知銘柄に無関係な差分では再発行されない
- 既知銘柄の価格が変化しても、前回の再発行から 5 秒未満なら再発行されない。5 秒経過後の最初の
  変化で再発行される(擬似時計で検証する)
- `fastAssetCtxs` の購読エラー、復号失敗、専用接続の失敗のいずれでもログイン失敗にならず、板と
  約定が継続する
- リレー選択時に `AssetContextFeed` が生成され、`close()` で ctx コネクタが本コネクタより先に
  閉じられ、転送層は 1 回だけ閉じられる
- Hyperliquid 選択時に追加接続が作られない

### エンドツーエンド

- `xyz:CL` を購読し、`l2Book` と `trades` が正しいエイリアスに届く
- `fastAssetCtxs` 由来の `markPx` から Tick size 候補が算出される
- 完全修飾名が購読 JSON に正しく載る(Gson によるエスケープ)

## 実装後に実機で確認すべき事項

- Bookmap のエイリアスに `:` が含まれる場合の挙動。特にワークスペース保存と録画ファイル名。
  Windows のファイル名では `:` が使えないため、Bookmap 側が名前をどう扱うかは実機でしか確認できない
- Testnet の 628 銘柄が Subscribe ダイアログで実用的に扱えるか

## 参照

- Hyperliquid Docs — Info endpoint / Perpetuals(`allPerpMetas`、`metaAndAssetCtxs` の `dex`、
  `perpDexs`)
- Hyperliquid Docs — WebSocket Subscriptions(`fastAssetCtxs` の符号化と差分セマンティクス)
- Hyperliquid Docs — Asset IDs(HIP-3 銘柄名は必ず `{dex}:{coin}`)
- Hyperliquid Docs — Rate limits(情報リクエストの重み 20、IP あたり 1200/分、同時 WebSocket 10)
- Hyperliquid Docs — HIP-3: Builder-deployed perpetuals
- 既存設計書 `2026-09-07-tick-size-book-aggregation-design.md`(Tick size 候補と板集約)
