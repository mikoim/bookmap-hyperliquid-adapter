# セッション中のメタデータ再取得 — 設計書

- 作成日: 2026-09-19
- 状態: spec-review READY、人間の承認待ち
- 起点: ローカル main `9b2f697`(TLS ホスト名検証の修正)
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

現行アダプターは `allPerpMetas` と `spotMeta` をログイン時に一度だけ取得する
(`HyperliquidConnector.startOnStateLane`)。再接続でも取り直さないため、セッション中に
上場・上場廃止された銘柄は、ユーザーが Bookmap の接続をやり直すまで Subscribe ダイアログに
反映されない。HIP-3 の perp dex とスポットペアは頻繁に増えるので、長時間つなぎっぱなしの
セッションほど一覧が古くなる。本設計は銘柄一覧をセッション中に更新する。

要求の要約(ユーザー決定済み):

- 再取得のきっかけは「30 分ごとの定期実行」と「WebSocket の再接続成功」の両方
- 購読中の銘柄が新しい一覧から消えても購読は維持する。一覧からだけ外し、システムメッセージを
  1 回出す
- 一回分の取得を `MetadataRequest` に切り出してログインと再取得で共用し、周期管理は session
  パッケージの `MetadataRefresher` に置く(`HyperliquidConnector` にタイマーを足さない)

非目的(スコープ外):

- 一覧から消えた銘柄の購読を強制解除すること
- 再取得結果の銘柄数に基づく妥当性判定(急減の検知など)
- 稼働中の購読の `szDecimals` や tick を再取得結果で更新すること
- 再取得失敗時の即時リトライ、およびユーザー向けシステムメッセージ
- マーク価格用の第 2 接続(リレー選択時)の再接続を再取得のきっかけにすること
- 再取得間隔のユーザー設定
- バージョン更新とリリース作業

## 前提となる事実

- レート制限(公式): REST は IP あたり毎分 weight 1200。`allPerpMetas` と `spotMeta` は
  「その他の info リクエスト」で各 weight 20。再取得 1 回は weight 40
- `HyperliquidProcessBudget` が管理するのは WebSocket の接続数、接続試行、送信フレーム、購読枠で、
  REST は対象外。本変更は予算に触れない
- 銘柄一覧を Bookmap へ再公開する経路は既にある。`HyperliquidSession.onAssetContexts` が
  マーク価格の更新ごとに(最短 5 秒間隔で)`sink.onKnownInstruments` を呼び、`Provider` が
  `knownSubscribeInfo` を差し替える
- `SubscriptionRecord` は購読時点の `Instrument` を自分で保持する。session の `instruments`
  マップを差し替えても、稼働中の板と約定の単位換算は変わらない
- `HyperliquidConnector` のメタデータ取得部(`postMetadata`、`completeMetadata`、`failMetadata`、
  `cancelMetadataRequests` と 4 フィールド)は、`transport`、`metaParser`、`stateSubmitter`、
  `profile.infoUri()` にしか依存しない
- `HyperliquidConnector.classify` と `isNetworkFailure` は connector の状態を参照しない純粋関数

## アーキテクチャ

### 基本方針

- 一回分の取得は使い捨ての `MetadataRequest` が担う。ログインも再取得も同じ部品を使い、
  2 要求の待ち合わせ・解析・失敗分類を二重に持たない
- 周期とトリガーは `MetadataRefresher` が担う。取得結果の適用(一覧の差し替え、通知)は
  `HyperliquidSession` が担う
- `HyperliquidConnector` の外から見える挙動は変えない。`HyperliquidConnectorTest` を無変更で
  通すことを移設の合格条件とする
- 可変状態はすべて既存の state lane(`dispatcher::submitControl`)上でだけ触る

### 新規コンポーネント

#### `MetadataRequest`(ルートパッケージ、public final)

一回分のメタデータ取得。インスタンスは再利用しない。

```java
public final class MetadataRequest {
  public interface Callback {
    void onInstruments(List<Instrument> instruments);
    void onFailure(TransportFailure failure);
  }

  public MetadataRequest(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      Consumer<Runnable> stateSubmitter,
      URI infoUri,
      Callback callback);

  /** state lane 上で呼ぶ。2 つの POST を発行する。 */
  public void start();

  /** state lane 上で呼ぶ。以後コールバックは呼ばれない。 */
  public void cancel();
}
```

契約:

- `start()` は `{"type":"allPerpMetas"}` と `{"type":"spotMeta"}` を `infoUri` へ並行に POST する。
  タイムアウトは各 10 秒(現行の `METADATA_TIMEOUT_MILLIS`)。リクエスト JSON の定数は
  `HyperliquidConnector` から `MetadataRequest` へ移す
- transport のコールバックは `stateSubmitter` を通して state lane に載せてから処理する
- 両方が成功したら `parseAllPerpMetas`、`parseSpotMeta`、`combine` を通し、`onInstruments` を
  ちょうど 1 回呼ぶ
- 次のいずれかで、もう片方の要求を中断し、`onFailure` をちょうど 1 回呼ぶ
  - transport が失敗を返した → `TransportFailure.classify(failure, NETWORK)`
  - HTTP ステータスが 2xx 以外 → `Kind.REMOTE`、メッセージ `metadata request returned HTTP <code>`
  - 解析または `combine` が `ProtocolException` を投げた → `TransportFailure.classify(e, PROTOCOL)`
  - `postJson` 自体が例外を投げた → `TransportFailure.classify(e, NETWORK)`
- 完了後、または `cancel()` 後に届いた transport のコールバックは破棄する
- `start()` を 2 回呼ぶと `IllegalStateException`
- `postJson` が同期的に例外を投げた場合、`onFailure` は `start()` の中から同期的に呼ばれる。
  呼び出し側は `start()` より前に自分の「進行中」の状態(connector の `metadataRequest`
  フィールド、`MetadataRefresher` の進行中の印)を設定し、コールバック内でそれを解除する

#### `TransportFailure.classify`(既存クラスへの static 追加)

`HyperliquidConnector.classify` と `isNetworkFailure` をそのまま移す。原因チェーンに
`UnknownHostException`、`NoRouteToHostException`、`SocketException`、`ConnectException`、
`SocketTimeoutException`、`TimeoutException` のいずれかがあれば `NETWORK`、なければ引数の
fallback を種別とする。connector 内の呼び出しはすべてこの static に置き換える。

#### `MetadataRefresher`(session パッケージ、package-private final)

```java
final class MetadataRefresher {
  interface Listener {
    void onMetadataRefreshed(List<Instrument> instruments);
    void onMetadataRefreshFailed(TransportFailure failure);
  }

  interface RequestFactory {
    MetadataRequest create(MetadataRequest.Callback callback);
  }

  MetadataRefresher(
      RequestFactory requests,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter,
      Listener listener);

  void start();       // 初回接続成功時
  void refreshNow();  // 再接続成功時
  void close();
}
```

定数: `REFRESH_INTERVAL_MILLIS = 1_800_000`(30 分)、`MIN_REFRESH_GAP_MILLIS = 60_000`。

契約(すべて state lane 上):

- `start()` は 30 分後のタイマーを張る。2 回目以降の呼び出しと `close()` 後の呼び出しは何もしない
- タイマーが発火したら取得を開始する。タイマーは `scheduler` で張り、発火時に `stateSubmitter`
  へ載せ直す(`HyperliquidConnector.scheduleState` と同じ形)
- `refreshNow()` は、`start()` 前、`close()` 後、取得が進行中、または前回の取得開始から
  60 秒未満のいずれかなら何もしない。それ以外は保留中のタイマーを取り消して取得を開始する
- 取得の開始時刻を記録し、`RequestFactory` から `MetadataRequest` を作って `start()` する。
  同時に進行する取得は常に 1 つ
- 取得が完了したら(成功でも失敗でも)`Listener` に渡し、その時点から 30 分後に次のタイマーを
  張る
- `close()` は保留中のタイマーを取り消し、進行中の `MetadataRequest` を `cancel()` する。
  以後 `Listener` は呼ばれない

ログイン時の取得は `MetadataRefresher` を通らない。ログインの取得が初回接続より前に終わるので、
最初の再取得は初回接続の 30 分後になる。

### 既存コンポーネントの変更(最小差分)

#### `HyperliquidConnector`

- フィールド `perpMetadataRequest`、`spotMetadataRequest`、`perpInstruments`、`spotInstruments` を
  `MetadataRequest metadataRequest` 1 つに置き換える
- `startOnStateLane` は `transport.start()` の後に `MetadataRequest` を作って開始する。
  `onInstruments` では `metadataRequest = null`、`listener.onMetadata(list)`、
  `attemptConnection(true)`。`onFailure` では `metadataRequest = null`、`reportInitialFailure`
- `closeOnStateLane` は `metadataRequest` を `cancel()` する
- `postMetadata`、`completeMetadata`、`failMetadata`、`cancelMetadataRequests`、`classify`、
  `isNetworkFailure`、メタデータ JSON 定数、`METADATA_TIMEOUT_MILLIS` を削除する
- 公開 API、`Listener`、コンストラクタは変えない

#### `HyperliquidSession`

- コンストラクタに `MetadataRefresher.RequestFactory` を生成するための
  `MetadataRequestFactory`(`SourceProfile -> MetadataRequest.Callback -> MetadataRequest`)を
  追加する。`AssetContextConnectorFactory` に倣い、session パッケージの public interface とする。
  `infoUri` はログイン時の `SourceProfile` で決まるため、`MetadataRefresher` は `handleLogin` で
  `profile` が確定した時点で生成する
- `onSocketOpened`: 初回接続(`!connectedOnce` の分岐)で `refresher.start()`、再接続の分岐で
  `refresher.refreshNow()`
- `MetadataRefresher.Listener` を実装する
  - `onMetadataRefreshed(list)`: `closed` なら何もしない。`instruments` と `knownCoins` を
    差し替え、各銘柄に `assetContexts.markPrice(coin)` で参照価格を付け直し、
    `sink.onKnownInstruments` で再公開する。続けて一覧から消えた購読の通知(下記)を行う
  - `onMetadataRefreshFailed(failure)`: `closed` なら何もしない。
    `sink.onDiagnostic("metadata refresh failed: " + failure.message())` を 1 行出す。一覧は
    据え置く
- 一覧から消えた購読の通知: `Set<String> unlistedNotified`(alias)を持つ
  - `records` のうち状態が `REMOVED` でなく、alias(`instrument.symbol()`)が新しい
    `instruments` に無く、`unlistedNotified` にも無いものを集める。1 件以上あれば alias を
    昇順に並べて 1 通のシステムメッセージにまとめ(`MessageKind.UNCLASSIFIED`)、
    `unlistedNotified` に加える。文面:
    `<alias>[, <alias>...] no longer listed by Hyperliquid; open subscriptions stay active until removed`
  - 新しい `instruments` に含まれる alias は `unlistedNotified` から外す(復活後の再通知を可能に
    する)
  - `removeRecord` は、その alias を `unlistedNotified` から外す
- 既存の `onMetadata`(ログイン時)と `onMetadataRefreshed` は、`instruments`/`knownCoins` の
  差し替えと再公開を 1 つの private メソッドで共有する
- `stop()` は `refresher.close()` を呼ぶ(`assetContextFeed.close()` の隣)。ログイン前に
  停止した場合 `refresher` は未生成なので、null なら何もしない

#### `Provider.ProductionSessionFactory`

`MetadataRequestFactory` を既存の `transport`、`new HyperliquidMetaParser()`、
`dispatcher::submitControl` から組み立てて session に渡す。テスト用ファクトリ
(`HyperliquidSessionFactory` 実装、session テストのヘルパー)も同じ引数を受け取るよう更新する。

## データフロー

```
30 分タイマー / 再接続成功
  → MetadataRefresher: 取得開始(進行中・60 秒以内なら無視)
  → MetadataRequest.start()
       POST allPerpMetas ─┐
       POST spotMeta ─────┤  transport スレッド
  ← stateSubmitter ←──────┘
  → 両方成功: HyperliquidSession.onMetadataRefreshed(list)
       → instruments / knownCoins 差し替え
       → AssetContextStore の現在値で参照価格を付け直し
       → sink.onKnownInstruments → Provider.knownSubscribeInfo 差し替え
       → 一覧から消えた購読中銘柄があれば system message(1 回)
  → 失敗: sink.onDiagnostic、一覧は据え置き
  → MetadataRefresher: 完了時点から 30 分後に次のタイマー
```

一覧から消えた銘柄は `instruments` に無いので、新規の購読は既存の経路で
`onInstrumentNotFound` になる。

## エラー処理と運用制約

| 状況 | 挙動 |
| --- | --- |
| 再取得の片方または両方が失敗(転送失敗、非 2xx、解析失敗、`combine` の重複) | 一覧を据え置き、診断を 1 行出し、30 分後に再試行。ログイン失敗、接続断、システムメッセージにはしない |
| 再取得結果が空、または大幅に減った | そのまま採用する。perp dex が 0 件の応答は既存 parser が解析失敗として弾く |
| 取得中に session が停止 | `close()` が要求を中断し、遅着コールバックは破棄 |
| 取得中に再接続 | 進行中の取得をそのまま使う |
| 再接続が連続 | 前回の取得開始から 60 秒未満の `refreshNow()` は無視。REST 負荷は最大でも毎分 weight 40 |
| 購読中の銘柄が一覧から消えた | 購読は維持。システムメッセージを 1 回。フィードが止まれば既存の `BOOK_STALE` が知らせる |
| 消えた銘柄が一覧に復活 | 通知済みの印を外す。再び消えたら改めて 1 回通知 |
| 購読中の銘柄の `szDecimals` が変わった | 稼働中の購読は購読時の値のまま。次回の購読から新しい値 |
| リレー選択時 | メタデータは Mainnet の REST から取る(現行どおり)。再取得のきっかけは板側の接続の再接続だけ |

## テスト戦略

すべて TDD。`FakeHyperliquidTransport` と `ManualScheduler` を使う。

### `MetadataRequestTest`(新規)

- 両方成功で、結合した一覧が `onInstruments` に 1 回だけ届く(応答の到着順を入れ替えても同じ)
- perp が失敗すると spot の要求が中断され、`onFailure` が 1 回だけ届く(逆も同じ)
- 非 2xx は `Kind.REMOTE`、解析失敗は `Kind.PROTOCOL`、`UnknownHostException` は `Kind.NETWORK`
- perp と spot で symbol が重複すると `Kind.PROTOCOL` の失敗になる
- `cancel()` 後に届いた応答ではどちらのコールバックも呼ばれない
- transport のコールバックは `stateSubmitter` を経由してから処理される
- `start()` の 2 回目は `IllegalStateException`

### `TransportFailure`(既存テストに追加、無ければ新規)

- 原因チェーンの奥にあるネットワーク例外を `NETWORK` と分類する。それ以外は fallback

### `HyperliquidConnectorTest`(既存)

- 無変更で全件通ること

### `MetadataRefresherTest`(新規)

- `start()` から 30 分で取得が始まり、それより前には始まらない
- 取得の完了(成功・失敗それぞれ)から 30 分で次の取得が始まる
- `refreshNow()` は保留中のタイマーを取り消して即時に取得し、完了から 30 分後に次が来る
- 前回の取得開始から 60 秒未満の `refreshNow()` は無視される。60 秒ちょうどは実行される
- 取得が進行中の `refreshNow()` は無視される
- `start()` 前の `refreshNow()` は無視される
- `close()` は進行中の要求を中断し、以後タイマーも `Listener` も動かない

### `HyperliquidSessionLifecycleTest`(追加)

- 初回接続で `start`、再接続で `refreshNow` が呼ばれる
- 再取得成功で `onKnownInstruments` が新しい一覧を持ち、既知のマーク価格が参照価格として付く
- 再取得失敗で一覧は据え置かれ、診断が 1 行出て、システムメッセージも接続断も出ない
- 一覧から消えた購読中銘柄は購読が維持され、システムメッセージが 1 回出る。同じ状態の再取得を
  繰り返しても再通知されない
- 2 銘柄が同時に消えたら 1 通にまとまる
- 復活した後に再び消えると再通知される
- 購読を外した銘柄は、次に購読して再び消えたとき再通知される
- 一覧から消えた銘柄の新規購読は `onInstrumentNotFound`
- `close` 後の再取得結果は無視される

### `ProviderEndToEndTest`(追加 1 件)

- 再取得で増えた銘柄が `getSupportedFeatures()` の known instruments に現れ、購読して板が出る

### ビルドゲート

`env JAVA_HOME=... ./gradlew --offline spotlessApply check verifyJava8Bytecode` が成功すること。

## ドキュメント

- `README.md`: Scope の節に、銘柄一覧が 30 分ごとと再接続時に更新されること、購読中の銘柄が
  一覧から消えた場合の挙動を 1 段落で追記する
- `docs/development.md`: パッケージ表の `hyperliquid` 行と `hyperliquid.session` 行に
  メタデータ取得と再取得を追記する
- Javadoc: `MetadataRequest`、`MetadataRefresher`、`TransportFailure.classify`、
  `HyperliquidSession.onMetadataRefreshed`

## 実装後に実機で確認すべき事項

- セッション途中で銘柄数が増えた `knownInstruments` を、Bookmap の Subscribe ダイアログが
  再ログインなしで反映すること(マーク価格の更新で同じ経路は使用済みだが、銘柄の増減は未確認)
- 反映されない場合でも、アダプター内の `instruments` は更新されているので、新銘柄のシンボルを
  手入力して購読できること

## 参照

- Rate limits and user limits: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/rate-limits-and-user-limits
- Info endpoint: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint
- 先行仕様: `2026-09-07-hip3-market-support-design.md`、`2026-09-10-spot-market-support-design.md`
