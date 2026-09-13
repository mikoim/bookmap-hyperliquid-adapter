# ライセンスの選定と明示 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** リポジトリに MIT ライセンスを設定し、`LICENSE` ファイルと README で明示する。

**Architecture:** コード変更はない。ルートに標準 MIT 条文の `LICENSE` を追加し、`README.md` 末尾に `## License` セクションを追記する。成果物は thin JAR で第三者コードを同梱しないため、NOTICE や THIRD-PARTY ファイル、ソースヘッダは作らない。

**Tech Stack:** git、Markdown、SPDX MIT 参照テキスト

**Spec:** `docs/superpowers/specs/2026-09-10-license-selection-design.md`

## Global Constraints

- ライセンスは MIT。条文は OSI / SPDX の標準テキストを一字も変えない。
- `LICENSE` の 1 行目は `MIT License`、著作権行は `Copyright (c) 2026 Eshin Kunishima`。
- README の追記は英語、末尾の `## For developers` の後に `## License` として 2 文。
- 変更するのは `LICENSE`（新規）と `README.md`（追記）のみ。ソース、`build.gradle`、`docs/development.md` は触らない。
- 作業は main に直接コミットする（worktree は使わない）。
- コミットメッセージ末尾に次を付ける:

  ```text
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WwLrHW4nQQnuETUpK4MnvY
  ```

---

### Task 1: `LICENSE` ファイルの追加

**Files:**
- Create: `LICENSE`

**Interfaces:**
- Consumes: なし
- Produces: ルートの `LICENSE`。Task 2 の README がこのファイル名を参照する。

- [ ] **Step 1: ファイルが存在しないことを確認する**

Run: `test ! -e LICENSE && echo absent`
Expected: `absent`

- [ ] **Step 2: `LICENSE` を書く**

次の内容をそのまま書く。末尾は改行 1 つで終える。

```text
MIT License

Copyright (c) 2026 Eshin Kunishima

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 3: SPDX の参照テキストと一致することを確認する**

SPDX は著作権行を `Copyright (c) <year> <copyright holders>` のプレースホルダで持つため、
両者の著作権行を除いた本文を、空白を正規化した上で比較する。

Run:

```bash
curl -fsS -m 20 https://raw.githubusercontent.com/spdx/license-list-data/main/text/MIT.txt -o /tmp/spdx-mit.txt \
  && diff <(grep -v '^Copyright' /tmp/spdx-mit.txt | tr -s '[:space:]' ' ') \
          <(grep -v '^Copyright' LICENSE | tr -s '[:space:]' ' ') \
  && echo MATCH
```

Expected: `MATCH`（diff の出力なし）

ネットワークが使えず curl が失敗した場合は、Step 2 のテキストが SPDX 参照テキストそのものである
ことを根拠に、この Step を「オフラインのため未実施」と記録して先へ進む。

- [ ] **Step 4: 空白の問題がないことを確認する**

Run: `git add LICENSE && git diff --cached --check && echo ok`
Expected: `ok`

- [ ] **Step 5: コミット**

```bash
git commit -m "chore: add MIT license

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WwLrHW4nQQnuETUpK4MnvY"
```

---

### Task 2: README に License セクションを追記する

**Files:**
- Modify: `README.md`（末尾、`## For developers` セクションの後）

**Interfaces:**
- Consumes: Task 1 の `LICENSE`（リンク先）
- Produces: なし

- [ ] **Step 1: 追記位置を確認する**

Run: `tail -n 4 README.md`
Expected: 最後のセクションが `## For developers` で、その本文 2 行（`docs/development.md` へのリンク）で
ファイルが終わっている。

- [ ] **Step 2: セクションを追記する**

`README.md` の末尾に、空行 1 つを挟んで次を追加する。

```markdown
## License

This adapter is released under the MIT License; the full text is in [LICENSE](LICENSE). The
license covers only the source in this repository. Bookmap itself and the Bookmap Layer 0 API
remain subject to Bookmap's own terms.
```

- [ ] **Step 3: 差分が意図どおりであることを確認する**

Run: `git diff --stat README.md && git diff README.md | grep '^+' | grep -v '^+++'`
Expected: `README.md` のみ、追加行は空行 1 つと上記 4 行（見出し 1 行と本文 3 行）だけ。削除行なし。

- [ ] **Step 4: リンク先が存在し、空白の問題がないことを確認する**

Run: `test -f LICENSE && git add README.md && git diff --cached --check && echo ok`
Expected: `ok`

- [ ] **Step 5: コミット**

```bash
git commit -m "docs: state the MIT license in the README

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WwLrHW4nQQnuETUpK4MnvY"
```

---

### Task 3: 最終確認と push 判断

**Files:**
- なし（確認のみ）

**Interfaces:**
- Consumes: Task 1、Task 2 のコミット
- Produces: なし

- [ ] **Step 1: 変更範囲が spec のとおりであることを確認する**

Run: `git diff --name-only 3ae9984..HEAD`
Expected: `LICENSE` と `README.md` の 2 ファイルのみ。

- [ ] **Step 2: 作業ツリーがクリーンであることを確認する**

Run: `git status --porcelain && echo clean`
Expected: `clean` のみ（porcelain 出力なし）。

- [ ] **Step 3: push するか著者に確認する**

push は外部公開の操作なので、著者に「push するか、手元に留めるか」を尋ねる。
push を選んだ場合のみ、次で GitHub の自動検出を確認する。

Run（push 後、反映に数十秒かかることがある）:

```bash
git push origin main
sleep 30
curl -s -m 15 https://api.github.com/repos/mikoim/bookmap-hyperliquid-adapter \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["license"]["spdx_id"])'
```

Expected: `MIT`
