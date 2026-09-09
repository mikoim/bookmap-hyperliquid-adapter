# ドキュメントの読者別再編

日付: 2026-09-10
状態: 人間の承認待ち
起点: ローカル main `09b7216`

## 目的と範囲

`README.md` にエンドユーザー向けの説明、内部実装の詳細仕様、ビルド手順、品質ゲートが
混在しており、どの読者にとっても過不足のある資料になっている。
これを読者別に 3 つの資料へ再編する。

対象は Markdown 文書のみ。コード、ビルド設定、テスト、`.codex/` や `.grok/` の設定は
変更しない。

## 成果物

| ファイル | 読者 | 状態 |
| --- | --- | --- |
| `README.md` | Bookmap 利用者と導入検討者 | 書き直し |
| `docs/development.md` | このリポジトリで作業する開発者 | 新規 |
| `AGENTS.md` | LLM エージェント | 新規（`CLAUDE.md` から移設） |
| `CLAUDE.md` | Claude Code | `@AGENTS.md` の 1 行に縮退 |

記述言語は 3 文書とも英語とする（現行 `README.md`・`CLAUDE.md` と同じ）。
本仕様書および `docs/superpowers/` 以下は既存の慣習どおり日本語のままとする。

## README.md

読み終えた時点で「導入して接続でき、採用してよいか判断できる」状態を目標とする。

構成:

1. 導入文とヒートマップ画像 — 読み取り専用の L2 板と約定、HIP-3 を含む全 perp dex、
   認証情報を要求せず発注もしないこと
2. Requirements — Bookmap 7.8 以降（`InstrumentInfo.requestedSymbol` を使うため）。
   JAR を配置するだけの利用に JDK は不要
3. Install — GitHub Releases から JAR をダウンロードし `API/Layer0ApiModules` に配置する。
   Linux の実パスを例示する。ソースからビルドする方法は書かず `docs/development.md`
   へのリンクにとどめる
4. Connect — Connectivity ダイアログの画像、`Order book source` と
   `Use Hyperliquid testnet`、Subscribe ダイアログの Tick size
5. Order book sources — 3 ソースの比較表。エンドポイント、片側の板深さ
   （Hyperliquid 20 / Borsa 400 / Hyperdash 20）、testnet 可否、mark price の取得元
6. Scope — 対応は全 perp dex の `PERPETUAL` 購読のみ。Spot、履歴、口座データ、
   認証情報、発注、ギャップ補填は非対応
7. Data-health messages — 7 メッセージ（`BOOK_STALE`、`BOOK_RESYNCING` / `BOOK_RESUMED`、
   `TRADE_GAP_POSSIBLE` / `TRADE_RESUMED`、`MARK_PRICE_UNAVAILABLE` / `MARK_PRICE_RESUMED`）の表。
   何が起きたかと、利用者が取るべき対応。表の前に、全メッセージが `data-status` で始まり
   選択中のソース・環境・銘柄・UTC 時刻を含むことを 1 文で述べる。個々のフィールド書式
   （`*`、generation 番号、`feedSource`）は書かない
8. Limits — 同一 JVM 内の全プロバイダで共有される接続予算、リレー使用時の
   5 プロバイダ上限、切断中の約定は補填されないこと、Testnet の銘柄一覧が
   約 200 の perp dex にまたがる約 630 銘柄でその大半が他の開発者の試験用市場であること
9. For developers — `docs/development.md` へのリンク

README から削除する記述（実装の詳細であり、コードと既存 spec が正典）:

- `10^-(6-szDecimals)` の内部価格グリッド
- Borsa デルタのローカル折り畳みと自己修復しない性質
- `nSigFigs` / `mantissa` / `nLevels` の送信仕様
- メタデータを完全な universe として検証してから上場廃止銘柄を除外する順序
- tick 候補の導出アルゴリズムと、大文字化された記号の照合規則

短縮して残す記述:

- Tick size は「Hyperliquid がその価格帯で実際に提示する刻みが並び、粗くすると
  同じレベル数でより広い価格帯を覆う」という利用者の判断に効く範囲まで
- HIP-3 銘柄が `dex:coin` 形式（例 `xyz:CL`）で表示されること

## docs/development.md

新規参入者がビルドでき、どのファイルを開けばよいか分かるところまでを目標とする。
データフローやスレッドモデルの解説は書かない。クラス単位の説明は Javadoc が担う。

構成:

1. Prerequisites — JDK 21 から 25。20 以下と 26 以上はビルドが拒否する。
   システム JDK を使う。代替 JDK の指定方法は書かない
2. Build — `./gradlew clean build`。成果物は
   `build/libs/hyperliquid-adapter-1.2.0.jar`。API・Gson・Jetty は Bookmap 側が供給する。
   Java 8 バイトコードを出力し `verifyJava8Bytecode` が major version 52 を検証する
3. Quality gates — 既存の 6 コマンドと SpotBugs レポートの出力先
4. Code layout — パッケージ単位の 1 行表
5. Specs and plans — `docs/superpowers/specs/` と `docs/superpowers/plans/` が設計の履歴であること、
   変更時のワークフローは `AGENTS.md` を参照すること

Code layout の表に載せるパッケージと責務:

| パッケージ | 責務 |
| --- | --- |
| `hyperliquid` | Bookmap Layer 0 の入口。provider、接続設定フィールド、ソース選択、接続ライフサイクル |
| `hyperliquid.book` | 板の保持と公開。seed と delta の折り畳み、tick への丸め集約 |
| `hyperliquid.budget` | プロセス全体で共有する接続・送信・購読数の予約 |
| `hyperliquid.concurrent` | state レーンの直列化と市場データフレームの境界付け |
| `hyperliquid.model` | 不変の値型とドメイン型 |
| `hyperliquid.parse` | WebSocket と REST の JSON からドメイン型への変換 |
| `hyperliquid.session` | Bookmap セッション出力への橋渡し、購読状態、データ健全性監視、リレー使用時の mark price 用接続 |
| `hyperliquid.trade` | 約定通知の重複抑制 |
| `hyperliquid.transport` | Jetty による非同期 WebSocket 境界 |

## AGENTS.md と CLAUDE.md

`AGENTS.md` は現行 `CLAUDE.md` の内容（Development Workflow、Review gates、
Human approval、Scope）を文言を変えずに移設し、末尾に参照先の一覧を追加する。

```
## Where things are
- User-visible behavior and limits -> README.md
- Build, quality gates, code layout -> docs/development.md
- Design history -> docs/superpowers/specs/ and docs/superpowers/plans/
- Class-level detail -> Javadoc in src/main/java
```

ビルド手順やアーキテクチャの実体を `AGENTS.md` に複製しない。
複製すれば人間向け資料と必ずどちらかが古くなるため、リンクのみを持つ。

`CLAUDE.md` は実体を持たず `@AGENTS.md` のインポート 1 行にする。
Claude Code は `@` インポートを解決し、Codex と Grok は `AGENTS.md` を直接読むため、
規約の実体が 1 箇所に収まる。シンボリックリンクは Windows チェックアウトで
壊れうるため採用しない。

## 検証

ドキュメントのみの変更のため自動テストは追加しない。以下を確認する。

- `README.md`、`docs/development.md`、`AGENTS.md`、`CLAUDE.md` のすべての内部リンクと
  `@` インポートが存在するパスを指すこと
- `docs/eth.webp` と `docs/connector.webp` の参照が `README.md` から維持されていること
- 削除対象として列挙した記述が `README.md` に残っていないこと
- 4 文書のいずれにも `references/` への言及がないこと（ローカル開発用の資料と SDK であり
  公開文書の対象外）
- `AGENTS.md` のワークフロー規約 4 節が `09b7216` 時点の `CLAUDE.md` と文言レベルで
  一致すること（末尾に追加する `Where things are` 節は比較対象外）
- `./gradlew --no-daemon check` が従来どおり成功すること（回帰がないことの確認）

## 非目標

- `.codex/config.toml`、`.grok/config.toml`、`.claude/skills/` の変更
- 日本語版 README の追加
- `docs/superpowers/` 以下の既存 spec と plan の書き換え
- `CONTRIBUTING.md` や `LICENSE` の新設
- コード、Javadoc、テストの変更
