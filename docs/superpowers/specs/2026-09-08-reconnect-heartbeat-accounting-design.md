# 再接続後の heartbeat フレーム会計

日付: 2026-09-08
状態: spec-review READY、人間の承認待ち
起点: ローカル main `cbbd8c1`（HIP-3 対応マージ済み）

## 目的と範囲

再接続後に PING の予約が枯渇し、マーケットデータ接続が FATAL 停止する不具合を修正する。
同じコネクタを使う asset-context 専用接続の再接続チャーンも解消する。
一回限りの接続時送信と、接続中に無期限に繰り返す送信の会計契約を明確にするため、
architectural として仕様承認・計画レビューを経て実装する。

本仕様は HIP-3 仕様の再接続予約数に関する記述を更新する。履歴資料そのものは書き換えない。
取引所の制限値、公開 API、セッションの障害分類、バックオフ、購読の復旧条件は変更しない。

## 原因の再確認

現行コードで以下の経路を確認した。実行による再現は実装フェーズ冒頭の失敗テストで記録する。

- `attemptConnection(false)` は `reservedFrameCount()` 枠を connection permit に確保する。
- 現在の式は `desired.size() + (sendsAssetContextFeed() ? 2 : 1)`。
  `socketOpened` が購読と必要な feed フレームを送ると、PING 用の1枠が残る。
- `scheduleHeartbeat` は再接続後の PING 全てにその同じ permit を渡す。
- 30秒後の PING が最後の枠を消費する。PONG が正常でも60秒後の PING は
  `startSend` の予約取得に失敗し、PROTOCOL 障害で切断する。
- `HyperliquidSession.connectionFailure` は PROTOCOL を FATAL に分類し、
  `onDisconnected` がセッションを停止する。
- 初回接続は `sendWhenPossible` の独立した `FrameReservation` 経路を使うため、
  この一回限りの予約枯渇を起こさない。

## 比較した方式と採用案

1. **全 PING を都度予約する（採用案）**。
   既存の初回接続の経路を共通化し、接続時予約から PING 分を除く。
   永続的な予約数や補充機構を必要としない。最初の PING も予算競合時は待機する。
2. 最初の PING だけ接続時予約を使い、以降は都度予約する。
   最初の1回分を確保できるが、一時的な保証のために会計の分岐が残る。
3. connection permit を継続的に補充する。
   補充・解放・競合時の責務と API が増える。既存の都度予約経路で実現できるため採用しない。

予約数を増やすだけの方式は有限回の延命にしかならず、要件を満たさない。

## 会計と送信の契約

- 再接続時の予約は、その接続が開いたときに送る `desired` の購読フレームと、
  `sendsAssetContextFeed()` が真の場合の `SUBSCRIBE_FEED` 1本だけを対象とする。
  新しい式は `desired.size() + (sendsAssetContextFeed() ? 1 : 0)`。
- 初回接続は従来どおり接続時のフレーム予約を行わない。
- 全世代の PING は `sendWhenPossible` に connection permit を渡さず、
  `budget.tryAcquireFrames(now, 1)` を通す。レート制限を迂回しない。
- 枠を確保した PING は `startSend` の送信開始時に一度だけ枠を消費し、
  既存の rolling window に送信開始時刻を計上する。
- 接続 permit はフレームを全て消費した後も同時接続スロットを保持する。
  枠を消費したことを理由に permit を close してはならない。
- 再接続の handshake 中の購読追加・削除・期限切れに対する予約の取り直しと検算も、
  同じ `reservedFrameCount()` を使用する。購読再送の原子的な予約は維持する。

## 予算不足とライフサイクル

- PING の枠が空いていなければ、既存の retryAt と RETRY_FLOOR に従って待機・再試行する。
  予算不足それ自体は PROTOCOL/FATAL 障害にしない。
- heartbeat の周期は30秒、PONG の待機時間は15秒のまま。
  待機中の未送信 PING を理由に、新しい PONG タイマーを開始しない。
  送信成功通知を受けたときに既存の PONG タイマーを設定する。
- 切断・close 時は既存の `cancelPendingSends` と世代判定で待機中の送信を無効化し、
  古い世代の PING が後の接続へ送られないようにする。
- 実際に送信した PING に PONG が返らなければ、従来どおり NETWORK 障害として再接続する。
  PROTOCOL の分類を弱めて本件を隠す変更は行わない。
- 長時間の予算逼迫で heartbeat が遅れる性質は初回接続と同じになる。
  PING 優先枠、待機 PING の合流、スケジューラやキューの再設計は本修正の範囲外。
  既存の周期的 enqueue と再試行の挙動を維持し、無期限の飢餓下で接続維持を保証しない。

## 検証と合格条件

実装前に、正常な PONG を返しても再接続から60秒で失敗する回帰テストを書き、
本番コードを変更せずに期待した理由で失敗することを記録する。

1. マーケットデータのセッションを接続・購読・再接続し、再送と ACK により復旧させる。
   30秒ごとに PING の実送信と成功通知を確認し、現世代の PONG を受信させる。
   少なくとも10周期（5分）継続し、接続数が増えず、FATAL がなく、購読が残り、
   復旧後のマーケットデータが sink に届くことを確認する。
2. asset-context 専用接続も実際に再接続させ、正常な PONG とともに複数周期を越え、
   不要な追加接続が発生せず、新しい context データを受信できることを確認する。
   fake transport がソケットを共有するため、接続インデックスと callback を区別し、
   market-data 側の PONG で feed 側を誤って成立させない。
3. コネクタの小さい共通予算で、再接続予約が購読・feed の必要数だけであること、
   PING が都度予算を消費することを外部からの取得可否・送信数で検証する。
   予算不足時に PING が待機し、枠の解放後に送信されることも確認する。
4. 待機中の PING を残して close または切断し、枠解放後に古い送信が走らないことを確認する。
   PONG 欠落による NETWORK 再接続の検出も維持する。
5. 初回接続と既存の購読変更・期限切れ・再接続予約テストを通す。
   旧 PING headroom に依存する期待値は、新契約に合わせる。

### 時計を使うテストの注意

`scheduleState` の期限タスクは state lane に処理を積むだけなので、
`advanceBy` 直後に状態を断定せず executor を drain する。
そこで新たにスケジュールされた期限済みタスクがあれば、時刻を進めず scheduler を再実行して
もう一度 drain する。特に大きな一括 advance で複数 heartbeat を処理したと見なさない。
PING の送信成功 callback と PONG の受付も drain し、タイマーが動いた証拠を明示的に assert する。

修正後は本番コードの会計変更を一時的に元へ戻し、同じ回帰テストが落ちることを確認する。
さらに PONG 受付を無効にした変異で、正常 PONG を必要とする継続テストが落ちることを確認する。
変異を全て復元した後に最終ゲートを実行する。

### ビルドゲート

fish で次を実行する。

```fish
env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode
```

Java 8 バイトコードを維持する。checkstyle・SpotBugs の失敗を許容せず、検査の無効化もしない。
この仕様の承認前には修正実装および実装計画の作成を行わない。

## Spec Review Result

**Verdict:** READY

**Comprehensive reviews:** 1  
**Remaining BLOCKERs:** 0  
**Remaining MAJORs:** 0  
**Remaining MINORs:** 0  
**Structural changes:** No  
**Targeted re-check:** No

**Human decisions required:**
- None（採用案の仕様承認は別途必要）

**Why review stops here:** 要件、整合性、範囲、責務、通常時と予算不足・切断時の挙動、
テストの観測点と変異検証を確認した。既存の FrameReservation 経路と解放処理を利用でき、
公開 API の変更は不要。既知の BLOCKER/MAJOR はないため、追加レビューを行わず人間の承認へ進む。
