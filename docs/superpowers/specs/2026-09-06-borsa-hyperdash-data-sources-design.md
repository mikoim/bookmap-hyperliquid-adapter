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

- メタデータの取得先はソースに従う: **Hyperliquid 選択時は選択された環境の REST(Testnet 選択時は testnet の
  infoUri、既存挙動を維持。退行させない)**。**Borsa/Hyperdash 選択時は常に Hyperliquid Mainnet REST**
  (`HyperliquidEnvironment.MAINNET.infoUri()`)。両ミラーが中継するのは Mainnet の板であるため。
  コネクタ内の「メタデータ URI = 環境固定」の結合を分離し、WS エンドポイントとメタデータ URI を
  個別に渡せるようにする。

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
   SEED_THEN_DELTA モードの `SubscriptionRecord` は「現世代の初回フレーム受信済みか」のフラグと
   **世代別のステージド板**(シードに差分を畳み込んだ作業中の板状態)を持つ。
   SNAPSHOT モードのレコードは現行 `OrderBookSnapshotDiff` 経路のまま変更しない。

   SEED_THEN_DELTA モードのフレーム処理(状態別、現行の PENDING_BOOK/回復/ACTIVE 経路と整合):
   - **現世代の初回フレーム**(シード): ステージド板を新規作成し全レベルを載せる。
     タイムスタンプの古さ評価は行わず無条件に受理し、`lastAcceptedBookTime` をシード時刻に更新する
     (差分モードには SNAPSHOT のような「次の全量フレームによる自然回復」がなく、世代間の時刻逆行は
     リレー再起動等で現実に起こるため、シードは世代の権威として常に受理する)。
   - **2 回目以降のフレーム**(差分): 直近受理時刻より古いフレームは破棄(等号は許容)。
     受理した差分は、
     - まだアクティベーション前(PENDING_BOOK)ならステージド板に畳み込むのみで発行しない
       (シード後・両 ACK 揃う前の差分を全量と誤認して発行する欠落を防ぐ)。
     - 回復中ならステージド板に畳み込むのみで発行しない(回復完了時にまとめて全量置換するため)。
     - アクティブかつ非回復なら 1 レベルずつその場で `DepthUpdate` へ変換して発行する
       (`sz>0` 更新、`sz=0` 除去)。
   - **アクティベーション**(SNAPSHOT モードの `activateIfReady` 相当): ステージド板の全レベルを
     `DepthUpdate` として発行し、発行済み価格集合を初期化する。
   - **回復完了時**(SNAPSHOT モードの `maybeRestore` 相当): ステージド板と発行済み価格集合を突き合わせ、
     新シードに含まれない旧レベルは `DepthUpdate size=0` で除去、差異のあるレベルは更新として発行する。
8. 各 `SubscriptionRecord` — Borsa モードのみ「発行済み価格集合」を保持し、世代境界で上記の
   置換・除去ルールを適用する。SNAPSHOT モードには既存の `OrderBookSnapshotDiff` ベースラインがあり
   追加状態は不要。

## データフロー

### ログインから購読成立

```
Provider.login(LoginData)
  └─ ドロップダウン + testnet チェックボックス → SourceProfile 解決(不明値は Hyperliquid)
       └─ session.login(SourceProfile)
            └─ Connector: メタデータ REST(Borsa/Hyperdash 選択時は Mainnet info、Hyperliquid 選択時は選択環境の info)POST {"type":"meta"} → 銘柄ユニバース確立
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
- タイムスタンプの鮮度判定は現行コードが既に `time < lastAcceptedBookTime` のみを STALE として棄却し
  等号を受理する(`OrderBookSnapshotDiff.validate` / `acceptRecoveryBook` 実測)。この規則は全ソース共通で変更しない。
  Borsa がシードと直後の差分に**同一タイムスタンプ**を付けること(実測済み)とも整合する。
  SEED_THEN_DELTA でのみ追加が必要な規則は「**世代初回フレーム(シード)は鮮度判定を挟まず常に受理し、
  `lastAcceptedBookTime` をシード時刻に再設定する**」こと(既述の項目7)。

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
