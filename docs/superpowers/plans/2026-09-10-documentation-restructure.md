# Documentation Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** README.md に混在している利用者向け説明・内部仕様・ビルド手順を、読者別の 3 文書（`README.md` / `docs/development.md` / `AGENTS.md`）へ再編する。

**Architecture:** ドキュメントのみの変更。`docs/development.md` を先に作り、`AGENTS.md` へ規約を移設して `CLAUDE.md` を `@AGENTS.md` の 1 行に縮退させ、最後に `README.md` を書き直す。この順序ならどのコミット時点でもリンク切れが発生しない。

**Tech Stack:** Markdown のみ。コード、ビルド設定、テストは変更しない。

## Global Constraints

- 記述言語は 4 文書とも英語。本計画と `docs/superpowers/` 以下は日本語のまま。
- 4 文書のいずれにも `references/` への言及を含めない（ローカル開発用の資料と SDK であり公開文書の対象外）。
- コード、Javadoc、テスト、`build.gradle`、`.codex/`、`.grok/`、`.claude/` を変更しない。
- 既存の `docs/superpowers/specs/` と `plans/` を書き換えない。
- 画像 `docs/eth.webp` と `docs/connector.webp` の参照を `README.md` から維持する。
- 相対リンクは各ファイルの位置から解決される。`docs/development.md` からルートのファイルを指すときは `../` を付ける。
- 仕様書: `docs/superpowers/specs/2026-09-10-documentation-restructure-design.md`

---

### Task 1: docs/development.md

**Files:**
- Create: `docs/development.md`

**Interfaces:**
- Consumes: なし
- Produces: `docs/development.md` — Task 3 の `README.md` がこのパスへリンクする。

- [ ] **Step 1: ファイルを作成する**

`docs/development.md` を次の内容で作成する。

````markdown
# Development

## Prerequisites

- JDK 21 through JDK 25. The build rejects JDK 20 and earlier and JDK 26 and later; those
  environments need a Gradle and quality-tool upgrade first.
- A shell that can run the Gradle wrapper.

Use the system JDK.

## Build

```bash
./gradlew clean build
```

The artifact is `build/libs/hyperliquid-adapter-1.2.0.jar`. It is a thin JAR: Bookmap supplies the
Layer 0 API, Gson, and Jetty at runtime, so they are `compileOnly` here.

The adapter compiles to Java 8 bytecode. The `verifyJava8Bytecode` task checks that compiled classes
carry major version 52.

## Quality gates

Run these before distributing the JAR:

```bash
./gradlew --no-daemon spotlessCheck
./gradlew --no-daemon checkstyleMain checkstyleTest
./gradlew --no-daemon spotbugsMain
./gradlew --no-daemon test
./gradlew --no-daemon check
./gradlew --no-daemon clean build
```

SpotBugs writes HTML and XML reports under `build/reports/spotbugs`.

## Code layout

All sources live under `src/main/java/com/bookmap/plugins/layer0/hyperliquid`. Class-level
documentation is in the Javadoc; this table is only a map of where to start reading.

| Package | Responsibility |
| --- | --- |
| `hyperliquid` | Layer 0 entry point: provider, connectivity fields, source selection, connection lifecycle |
| `hyperliquid.book` | Holding and publishing the book: folding seeds and deltas, aggregating onto the subscription's tick |
| `hyperliquid.budget` | Process-wide reservations for connection, send, and subscription limits |
| `hyperliquid.concurrent` | Serializing state work and bounding market-data frames |
| `hyperliquid.model` | Immutable value and domain types |
| `hyperliquid.parse` | Turning WebSocket and REST JSON into domain types |
| `hyperliquid.session` | Bridging to Bookmap session output: subscription state, data-health monitoring, and the extra mark-price connection a relay source needs |
| `hyperliquid.trade` | Duplicate suppression for trade notifications |
| `hyperliquid.transport` | Asynchronous WebSocket boundary, implemented on Jetty |

Tests mirror this layout under `src/test/java`.

## Specs and plans

`docs/superpowers/specs/` and `docs/superpowers/plans/` hold the design history: one spec and one
plan per architectural change. Read the spec for a subsystem before changing it.

The workflow that produces those documents is described in [AGENTS.md](../AGENTS.md).
````

- [ ] **Step 2: 内容を検証する**

Run:

```bash
grep -c "references/" docs/development.md; \
grep -n "hyperliquid.transport\|verifyJava8Bytecode\|build/reports/spotbugs" docs/development.md
```

Expected: `grep -c` は `0`。3 つの語がそれぞれ 1 行以上ヒットする。

- [ ] **Step 3: パッケージ表が実ディレクトリと一致することを確認する**

Run:

```bash
diff <(ls -d src/main/java/com/bookmap/plugins/layer0/hyperliquid/*/ | xargs -n1 basename | sort) \
     <(grep -oE '^\| `hyperliquid\.[a-z]+`' docs/development.md | sed 's/.*hyperliquid\.//;s/`//' | sort)
```

Expected: 差分なし（出力なし）。

- [ ] **Step 4: コミット**

```bash
git add docs/development.md
git commit -m "docs: add a developer guide for build, quality gates, and code layout"
```

---

### Task 2: AGENTS.md と CLAUDE.md

**Files:**
- Create: `AGENTS.md`
- Modify: `CLAUDE.md`（全体を 1 行に置換）

**Interfaces:**
- Consumes: Task 1 の `docs/development.md`（`Where things are` から参照する）
- Produces: `AGENTS.md` — Task 1 の `docs/development.md` が `../AGENTS.md` として参照済み。

- [ ] **Step 1: CLAUDE.md をそのまま AGENTS.md へ複製する**

文言の一致を構成上保証するため、手で書き写さず複製する。

```bash
cp CLAUDE.md AGENTS.md
```

- [ ] **Step 2: 参照先の一覧を追記する**

`AGENTS.md` の末尾に次を追記する。

````markdown

## Where things are

- User-visible behavior and limits -> README.md
- Build, quality gates, code layout -> docs/development.md
- Design history -> docs/superpowers/specs/ and docs/superpowers/plans/
- Class-level detail -> Javadoc in src/main/java
````

追記のみで、既存 4 節（Development Workflow / Review gates / Human approval / Scope）の文言は変更しない。

- [ ] **Step 3: 移設が逐語であることを検証する**

Run:

```bash
diff <(git show 09b7216:CLAUDE.md) <(head -c $(git show 09b7216:CLAUDE.md | wc -c) AGENTS.md)
```

Expected: 差分なし（出力なし）。差分が出たら Step 1 からやり直す。

- [ ] **Step 4: CLAUDE.md を 1 行に縮退させる**

```bash
printf '@AGENTS.md\n' > CLAUDE.md
```

- [ ] **Step 5: 縮退を検証する**

Run:

```bash
cat CLAUDE.md; wc -l < CLAUDE.md
```

Expected: `@AGENTS.md` の 1 行のみ、行数は `1`。

- [ ] **Step 6: コミット**

```bash
git add AGENTS.md CLAUDE.md
git commit -m "docs: move the agent workflow into AGENTS.md and reduce CLAUDE.md to an import"
```

---

### Task 3: README.md

**Files:**
- Modify: `README.md`（全体を書き直す）

**Interfaces:**
- Consumes: Task 1 の `docs/development.md`
- Produces: なし

- [ ] **Step 1: README.md を書き直す**

既存の内容を全て置き換える。

````markdown
# Bookmap Hyperliquid Adapter

Read-only Bookmap Layer 0 market data for Hyperliquid perpetuals, including HIP-3
builder-deployed markets. The adapter publishes aggregate L2 order books and trades for every live
perpetual on every perp dex. It never requests credentials and never sends orders.

![Bookmap heatmap for the ETH/USDC perpetual delivered by the adapter](docs/eth.webp)

## Requirements

- Bookmap 7.8 or newer.
- No JDK. Bookmap supplies everything the adapter depends on at runtime.

## Install

1. Download `hyperliquid-adapter-1.2.0.jar` from the
   [Releases page](https://github.com/mikoim/bookmap-hyperliquid-adapter/releases).
2. Copy it into the `API/Layer0ApiModules` directory of your Bookmap installation. On Linux:

   ```text
   $HOME/.bookmap/API/Layer0ApiModules/hyperliquid-adapter-1.2.0.jar
   ```

3. Restart Bookmap.

To build the JAR yourself, see [docs/development.md](docs/development.md).

## Connect

Open Bookmap's Connectivity configuration dialog and select the adapter.

![The Order book source dropdown in Bookmap's Connectivity configuration dialog](docs/connector.webp)

- **Order book source** selects where the book comes from: Hyperliquid (default), Borsa, or
  Hyperdash.
- **Use Hyperliquid testnet** applies to the Hyperliquid source only. Borsa and Hyperdash always
  serve Mainnet.

There are no credentials to enter.

Subscribe from Bookmap's Subscribe dialog. **Tick size** lists the ticks Hyperliquid actually quotes
at the instrument's current price magnitude — for HYPE near 88: 0.001, 0.002, 0.005, 0.01, 0.1, 1 —
and defaults to the finest of them. A coarser tick covers a wider price range with the same number
of levels, because the exchange aggregates before it sends.

HIP-3 markets keep their fully qualified `dex:coin` names, for example `xyz:CL`.

## Order book sources

| | Hyperliquid | Borsa | Hyperdash |
| --- | --- | --- | --- |
| Endpoint | `wss://api.hyperliquid.xyz/ws` | `wss://ws.borsa.cc/` | `wss://api.hyperdash.com/ws/orderbook` |
| Testnet | `wss://api.hyperliquid-testnet.xyz/ws` | Not available | Not available |
| Levels per side | 20 | 400 | 20 |
| Mark prices | Same connection | Extra Hyperliquid Mainnet connection | Extra Hyperliquid Mainnet connection |

Borsa and Hyperdash relay the Mainnet book. The adapter does not verify either relay against
Hyperliquid itself.

## Scope

Supported: `PERPETUAL` subscriptions for every live perpetual on every perp dex, HIP-3 markets
included.

Not supported: spot markets, historical data, account data, credentials, order entry, and gap
filling. Order APIs fail closed with a read-only system message.

## Data-health messages

Data-health transitions appear as Bookmap system messages and in the application log — WARN for
anomalies, INFO for recovery, subject to Bookmap's configured log level. Every message starts with
`data-status` and names the selected source, environment, symbol, and UTC time.

| Message | Meaning | What to do |
| --- | --- | --- |
| `BOOK_STALE` | No valid book has been accepted for this symbol for 30 seconds. Reception has gone quiet; this is not proof that the feed failed, and no reconnect is forced. | Wait. If it persists, reconnect or try another source. |
| `BOOK_RESYNCING` | A disconnect or an internal queue overflow invalidated the book. One incident invalidates every subscription at once and is reported once, naming all affected symbols. | Wait for `BOOK_RESUMED`. |
| `BOOK_RESUMED` | A replacement book has been published. This also clears a stale warning. | Nothing. |
| `TRADE_GAP_POSSIBLE` | A disconnect or a buffer overflow may have lost trades. The reported interval uses local processing times and is deliberately conservative; it is not an exact exchange-side range. | Treat trades in that interval as incomplete. They are not backfilled. |
| `TRADE_RESUMED` | A trade has been published again, ending the possible-gap interval. | Nothing. |
| `MARK_PRICE_UNAVAILABLE` | The mark-price connection failed or its subscription was rejected. Tick-size candidates keep their last known prices. | Wait. Reopening the socket alone does not clear this. |
| `MARK_PRICE_RESUMED` | Usable mark-price data has arrived again. | Nothing. |

The same ongoing condition is reported once; recovery re-arms the warning for a later incident.
Mark-price messages carry `feedSource=HYPERLIQUID` whatever the selected order book source is,
because that connection always goes to Hyperliquid.

## Limits

- Connection attempts, open connections, outbound frames, and subscription slots are budgeted per
  JVM and shared by every adapter provider in it: 10 open connections, 30 connection attempts per
  minute, 2000 outbound frames per minute, and 1000 subscription slots. Another Bookmap process, or
  another application behind the same public IP, is invisible to that budget, so leave external
  headroom for it and for Hyperliquid's own limits.
- Borsa and Hyperdash each need a second connection to Hyperliquid Mainnet for mark prices, which
  caps a single JVM at five relay-backed providers.
- During a disconnect the adapter reconnects and resynchronizes, but there is no historical gap fill
  and trades can be missed.
- On Testnet the instrument list holds roughly 630 symbols across about 200 perp dexes, most of them
  throwaway markets deployed by other developers.

## For developers

Build instructions, quality gates, and the code layout are in
[docs/development.md](docs/development.md).
````

- [ ] **Step 2: 削除対象の記述が残っていないことを検証する**

Run:

```bash
grep -nE 'szDecimals|nSigFigs|mantissa|nLevels|requestedSymbol|universe|delisted|gradlew|references/' README.md
```

Expected: 出力なし（終了コード 1）。いずれかがヒットしたら該当箇所を削除する。

- [ ] **Step 3: 画像参照が維持されていることを検証する**

Run:

```bash
grep -c "docs/eth.webp" README.md; grep -c "docs/connector.webp" README.md
```

Expected: どちらも `1`。

- [ ] **Step 4: data-health の 7 メッセージが揃っていることを検証する**

Run:

```bash
for s in BOOK_STALE BOOK_RESYNCING BOOK_RESUMED TRADE_GAP_POSSIBLE TRADE_RESUMED \
         MARK_PRICE_UNAVAILABLE MARK_PRICE_RESUMED; do \
  grep -q "$s" README.md || echo "MISSING $s"; done; echo done
```

Expected: `done` のみ。`MISSING` 行が出ないこと。

- [ ] **Step 5: コミット**

```bash
git add README.md
git commit -m "docs: rewrite README for Bookmap users and evaluators"
```

---

### Task 4: 全文書の相互検証

**Files:**
- Modify: なし（検証のみ。問題が見つかった場合のみ該当ファイルを修正する）

**Interfaces:**
- Consumes: Task 1〜3 の全成果物
- Produces: なし

- [ ] **Step 1: 内部リンクと `@` インポートの解決先を検証する**

Run:

```bash
python3 - <<'PY'
import pathlib, re
ok = True
for name in ["README.md", "docs/development.md", "AGENTS.md", "CLAUDE.md"]:
    p = pathlib.Path(name)
    text = p.read_text()
    targets = re.findall(r"\]\(([^)]+)\)", text) + re.findall(r"^@(\S+)$", text, re.M)
    for target in targets:
        if target.startswith(("http://", "https://")):
            continue
        resolved = p.parent / target.split("#")[0]
        if not resolved.exists():
            ok = False
            print("MISSING", name, "->", target)
print("OK" if ok else "BROKEN")
PY
```

Expected: `OK`。`MISSING` 行が出た場合は、そのリンクを書いたファイルの相対パスを修正する（`docs/` 配下からルートを指すには `../` が要る）。

- [ ] **Step 2: `references/` への言及がないことを検証する**

Run:

```bash
grep -rn "references/" README.md docs/development.md AGENTS.md CLAUDE.md; echo "exit=$?"
```

Expected: `exit=1`（ヒットなし）。

- [ ] **Step 3: コードとビルドに回帰がないことを確認する**

Run:

```bash
git diff --exit-code --stat 09b7216..HEAD -- src build.gradle settings.gradle; echo "exit=$?"
```

Expected: `exit=0` かつ差分の出力なし。ドキュメント以外を触っていないこと。

Run:

```bash
JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --no-daemon --offline check
```

Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 修正が発生した場合のみコミット**

Step 1〜2 で修正した場合のみ実行する。修正がなければこの Step は飛ばす。

```bash
git add -A README.md docs/development.md AGENTS.md CLAUDE.md
git commit -m "docs: fix cross-document links in the restructured docs"
```
