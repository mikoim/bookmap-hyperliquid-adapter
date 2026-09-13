# ライセンスの選定と明示

日付: 2026-09-10
状態: 承認済み（2026-09-14）
起点: ローカル main `0473dc1`

## 目的と範囲

リポジトリにライセンスが設定されておらず、GitHub 上で public でありながら法的には
「全著作権留保」の状態にある。依存ライブラリと成果物の形態を調査した上で MIT ライセンスを
採用し、`LICENSE` ファイルと README で明示する。

対象は `LICENSE` の新規追加と `README.md` の追記のみ。ソースコード、ビルド設定、テスト、
`docs/development.md` は変更しない。

## 調査結果

### 依存ライブラリ

| 依存 | スコープ | ライセンス | 根拠 |
| --- | --- | --- | --- |
| `com.bookmap.api:api-core:7.8.0.13` | compileOnly / test | 記載なし（プロプライエタリ扱い） | POM に `<licenses>` なし。Bookmap 公式 GitHub（`BookmapAPI/bitmex-adapter`、`BookmapAPI/DemoStrategies`）にも LICENSE なし |
| `com.google.code.gson:gson:2.4` | compileOnly / test | Apache-2.0 | POM |
| `org.eclipse.jetty.aggregate:jetty-all:9.3.8.v20160314` | compileOnly / test | Apache-2.0 / EPL-1.0 デュアル | 親 POM `jetty-parent:25` |
| `junit:junit:4.13.2` | testImplementation | EPL-1.0 | POM |

### 成果物と出自

- 成果物は thin JAR で、第三者コードを同梱しない。Gson と Jetty は Bookmap が実行時に供給する。
  したがって配布物に第三者ライセンスの表示義務は生じない。
- git 上の著者は Eshin Kunishima のみ。
- `references/bitmex-adapter`（Bookmap 公式、ライセンス未設定）は gitignore 下の参照資料であり、
  本プロジェクトのソースにそこからの複製・翻案は含まれない（著者確認済み）。
- ソースファイルにコピーライトヘッダはなく、README と docs にライセンスの言及はない。

## 選定

**MIT ライセンス**を採用する。

理由:

- 寛容ライセンスであり、商用・改変・再配布を制限しない。著者の方針に合う。
- Bookmap というプロプライエタリなホストにロードされるプラグインである。強いコピーレフト
  （GPL 系）は「プロプライエタリと結合するプラグイン」という解釈上の摩擦を生むため不適。
- 依存ライブラリ（Apache-2.0、EPL-1.0）との衝突はない。そもそも配布物に同梱しない。
- 単独著者の小規模プラグインであり、条文が最短で利用者の負担が最も少ない。
- 特許許諾（Apache-2.0 が持つ）は本プロジェクトに関わる要素が見当たらないため不要。
  必要になれば単独著者のため切り替えは容易。

検討した代替: Apache-2.0（特許許諾とコントリビューション条項が利点だが条文が長く、
慣例として NOTICE やヘッダを求められやすい）、BSD-2-Clause（MIT と同等で知名度が劣る）。

## 成果物

### `LICENSE`（新規、ルート）

OSI 標準の MIT 条文を一字も変えずに使う。

- 1 行目: `MIT License`
- 著作権行: `Copyright (c) 2026 Eshin Kunishima`
- 本文: SPDX の MIT 参照テキスト

標準テキストのままにする理由は、GitHub の自動検出（licensee）が MIT と認識し、
リポジトリにライセンス表示が出るようにするため。

### `README.md`（追記）

末尾の「For developers」の後に `## License` セクションを追加する。英語で 2 文:

1. このアダプタは MIT ライセンスで公開しており、全文は `LICENSE` にある。
2. このライセンスが対象とするのはこのリポジトリのソースのみで、Bookmap 本体と
   Bookmap Layer 0 API は Bookmap 側の利用条件に従う。

2 文目は、利用者が「MIT だから Bookmap API も自由に使える」と誤解しないための範囲の明示。

### 変更しないもの

- ソースファイルへのコピーライトヘッダ: ルートの `LICENSE` で足りる。
- NOTICE / THIRD-PARTY ファイル: thin JAR のため同梱物がなく不要。
- `build.gradle`: Maven 公開をしておらず POM もないため、ライセンスを書く場所がない。
- `docs/development.md`: README の記述で足りる。

## 検証

- `LICENSE` の本文が SPDX の MIT 参照テキストと一致することを確認する。
- `git diff --check` で空白の問題がないことを確認する。
- push した場合、GitHub API（`repos/mikoim/bookmap-hyperliquid-adapter` の `license.spdx_id`）が
  `MIT` を返すことを確認する。

## 運用

main に直接コミットする。コミット後、push するか手元に留めるかを著者に確認する。
