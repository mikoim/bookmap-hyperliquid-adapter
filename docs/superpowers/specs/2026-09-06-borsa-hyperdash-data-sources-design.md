# Borsa / Hyperdash オーダーブックソース追加 — 設計書

- 作成日: 2026-09-06
- 状態: レビュー待ち
- 対象: bookmap-hyperliquid-adapter(Bookmap Layer 0 マーケットデータアダプター)

## 背景と目的

現行アダプターは Hyperliquid Mainnet/Testnet の WebSocket のみから `l2Book` + `trades` を購読する。
本設計では、Hyperliquid プロトコル互換のサードパーティリレー **Borsa** (`wss://ws.borsa.cc/`) と
**Hyperdash** (`wss://api.hyperdash.com/ws/orderbook`) を、ログイン時に選択可能な
**代替オーダーブック情報源**として追加する。

要求の要約(ユーザー決定済み):

- ソースは「選択式の代替」。同時集約や自動フェイルオーバーは行わない
- ログイン画面には `CredentialsDropdown` でソース選択を追加(既定: Hyperliquid)
- trades も両ソースで購読する(実挙動を検証し、両ソースが Hyperliquid 互換の trades を配信することを確認済み)

非目的(スコープ外):

- 複数ソースの同時購読・集約
- 自動フェイルオーバー
- 既存 testnet チェックボックスの既定値(現状 true = testnet 既定)の変更
- Borsa/Hyperdash 側の認証・有償機能・データ正確性の検証(「そのソースを信頼した範囲」のデータを流すのみ)

## 実測による検証済みプロトコル事実(2026-09-06 観測)

| 項目 | Hyperliquid(現行) | Borsa | Hyperdash |
|---|---|---|---|
| l2Book 形式 | 毎フレーム全量スナップショット(既定 20 レベル/側) | **初回のみ全量スナップショット**(`nLevels` 指定で depth 変更、例: 400/10)。以降は**増分差分**(変更レベルのみ連続配信) | 毎フレーム 20×20 全量スナップショット(現行と同型) |
| レベル除去 | —(全量なので消失で除去) | 差分フレームに `sz:"0", n:0` エントリで表現 | —(全量なので消失で除去) |
| 差分の範囲 | — | 初期スナップショットの窓(nLevels)外の価格も差分に現れ得る(実測: nLevels=10 で窓外価格が差分配信) | — |
| trades | `coin/side/px/sz/hash/time/tid/users` | 互換。`tid` 有効 | 互換。`tid` 有効(`hash` が全ゼロのトレードあり→現行の重複排除キー coin+time+tid には影響なし) |
| 接続要件 | 追加ヘッダー不要 | 追加ヘッダー不要 | **ヘッダー必須**。`Origin: https://hyperdash.com` + ブラウザ風 `User-Agent` がないと HTTP 403(Cloudflare/Render 経由、実測) |
| ping | `{"method":"ping"}` → `{"channel":"pong"}` | 同左(実測) | 同左(実測) |

Context7 備考: Hyperliquid 公式ドキュメントは確認できたが、Borsa/Hyperdash の公式 API ドキュメントは
Context7 上に存在しない。上表は実観測に基づく。

## アーキテクチャ

### ソースモデル

- 新しい列挙体 **`MarketDataSource { HYPERLIQUID, BORSA, HYPERDASH }`** を導入。
  `HyperliquidEnvironment`(Mainnet/Testnet)とは直交する別軸。
  Hyperliquid 選択時のみ testnet チェックボックスが意味を持つ。
- 新しい値オブジェクト **`SourceProfile`** がソースごとの接続特性を保持する:

  | 項目 | Hyperliquid | Borsa | Hyperdash |
  |---|---|---|---|
  | WS URI | `HyperliquidEnvironment.webSocketUri()` | `wss://ws.borsa.cc/` | `wss://api.hyperdash.com/ws/orderbook` |
  | ハンドシェイクヘッダー | なし | なし | `Origin: https://hyperdash.com`、ブラウザ風 `User-Agent`(定数) |
  | l2Book 購読パラメータ | なし(現行の最小 JSON) | フィールド省略(null 明示と省略は同一挙動を実測) | `nSigFigs: 5` のみ |
  | フィードモード | SNAPSHOT(毎回全量) | SEED_THEN_DELTA(初回全量→差分) | SNAPSHOT |
  | trades | 購読 | 購読 | 購読 |

- **メタデータは常に Hyperliquid Mainnet REST**(`HyperliquidEnvironment.MAINNET.infoUri()`)から読む。
  両ミラーが中継するのは Mainnet の板であるため。コネクタ内の「メタデータ URI = 環境固定」の結合を分離する。

### 選択 UI

- `HyperliquidFieldManager` に `CredentialsDropdown`(Bookmap API 提供)を追加し、
  ソース選択(Hyperliquid 既定 / Borsa / Hyperdash)を提供する。
- 既存の testnet チェックボックスは保持。Hyperliquid 選択時のみ環境(Mainnet/Testnet)を決める。
- `Provider` 側の解決では、ドロップダウン値不明・欠落時は Hyperliquid にフォールバック。

## コンポーネント変更(最小差分)

1. `HyperliquidFieldManager` — ドロップダウン追加。
2. `Provider` — ログイン解決を拡張し、`session.login(...)` に `SourceProfile` を渡す形へ。
3. `HyperliquidSessionApi` / `HyperliquidSession` / `HyperliquidConnector` — `login` 引数を profile 化。
   コネクタはメタデータ REST(Mainnet info)と WS URI を分離して使用。
4. `JettyHyperliquidTransport` — `WebSocketClient.connect(adapter, uri, ClientUpgradeRequest)` に切り替え、
   プロファイルのヘッダーを `ClientUpgradeRequest` に設定。トランスポート IF にヘッダーマップを追加。
5. `SubscriptionKey` — l2Book 購読 JSON 生成にオプションパラメータ(`nSigFigs`/`nLevels`/`mantissa`)対応。
   null は省略。等価性は `coin+type` のまま変更しない(ACK 照合は coin+type のみ参照で確認済み)。
6. `HyperliquidMessageParser` — 購読応答の許容フィールドに `nLevels` を追加(現状は ProtocolException)。
   l2Book レベルの `sz` 検証を「非負(0 許容)」に緩和。
7. **板の新規差分経路** — パーサーは l2Book フレームを従来どおり `BookSnapshot`(レベル列)として生成する
   (`sz=0` も受理)。**シード/差分の区別はワイヤ形式では判別不能なため、セッション側で行う**:
   SEED_THEN_DELTA モードの `SubscriptionRecord` は「現世代の初回フレーム受信済みか」のフラグを持ち、
   初回フレームは全量シード(置換)として、2 回目以降は増分差分として処理する。
   SNAPSHOT モードのレコードは現行 `OrderBookSnapshotDiff` 経路のまま。
8. 各 `SubscriptionRecord` — Borsa モードのみ「発行済み価格集合」を保持し、
   新しい世代のシード受信時に、新シードに含まれない旧レベルを `DepthUpdate size=0` として除去する。

## データフロー

### ログインから購読成立

```
Provider.login(LoginData)
  └─ ドロップダウン + testnet チェックボックス → SourceProfile 解決(不明値は Hyperliquid)
       └─ session.login(SourceProfile)
            └─ Connector: メタデータ REST(Mainnet info)POST {"type":"meta"} → 銘柄ユニバース確立
                 └─ WS open(SourceProfile の URI + ヘッダー; Jetty ClientUpgradeRequest)
                      └─ l2Book + trades 購読送信(プロファイルのパラメータ付き JSON)
                           └─ ACK(coin+type 照合、現行まま)→ アクティベーション
```

### 板イベント

| ソース | 処理 |
|---|---|
| Hyperliquid/Hyperdash (SNAPSHOT) | 現行どおり: 全量スナップショット → `OrderBookSnapshotDiff` → `DepthUpdate` 群 |
| Borsa 初回フレーム | 全量シードとして採用(PENDING_BOOK)→ アクティベート時に全レベルを発行 |
| Borsa 2 回目以降(差分) | 各レベルを直接 `DepthUpdate` へ: sz>0 更新、sz=0 除去。`OrderBookSnapshotDiff` 経由なし |

### 再接続・回復

- 現行の世代(generation)再購読リプレイ機構をそのまま使用。
- 新規世代の初回 l2Book フレームは必ずシードとして扱い、旧世代発行済みの残存レベルは全除去。
- タイムスタンプ単調性チェックはモード別: SNAPSHOT は現行の厳密増加(新しいフレームのみ受理)。
  SEED_THEN_DELTA は Borsa がシードと直後の差分に**同一タイムスタンプ**を付けることを実測で確認済みのため、
  同世代内では `time >= lastAcceptedBookTime`(等しい時刻を許容)で判定する。

### trades

両ソースとも現行パースに互換(必須フィールド確認済み)。重複排除は `TradeKey(coin,time,tid)` で機能。

## エラー処理と運用制約

- Hyperdash の未認証 403 はハンドシェイクタイムアウト系の既存再試行経路に乗るが、
  SourceProfile の固定ヘッダーが正しく適用されることを単体テストで担保する。
- プロセス予算(`HyperliquidProcessBudget`)は既存共有プール(connections=10/attempts=30/frames=2000/subscriptions=1000/60s)を継続使用。
  Borsa/Hyperdash の独自レート制限は不明のため、Hyperliquid 向け保守制限をそのまま適用する。
- 差分モードで「発行済み集合にない sz=0 除去」は黙殺する。
- データの正確性はソースの信頼に依存し、アダプターは Hyperliquid 本体との一致を検証しない。

## テスト戦略

1. ログイン解決(ソース選択 + testnet 組み合わせ、既定/未知値フォールバック)
2. パーサー: sz=0 許容、ACK 応答の `nLevels`/`nSigFigs` 許容、不正値拒否
3. 差分モード: シード→差分反映、世代変更時の旧レベル除去、古いタイムスタンプ破棄、未知除去黙殺
4. トランスポート: Jetty ヘッダー送信の確認、403 時の既存エラーハンドリング/再試行
5. エンドツーエンド: Borsa 系ソースでの provider→session→parser→DepthUpdate 到達、切断→回復

## 参照

- Hyperliquid 公式 WebSocket: https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket/subscriptions.md
- Context7 調査(2026-09-06): Borsa/Hyperdash の公式ドキュメントは Context7 上に存在せず、上表の実測事実はウェブソケット直接観測による
- 現行設計の前提: README.md 記載の稼働範囲(20 レベル/側集約、Hyperliquid Mainnet/Testnet のみ)
