# Reconnect Heartbeat Accounting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Keep reconnected market-data and asset-context connections alive across repeated heartbeats without bypassing the process frame budget.

**Architecture:** Connection permits reserve only opening subscription frames. Every PING uses the existing per-send FrameReservation path, including its retry and cancellation behavior.

**Tech Stack:** Java, JUnit 4, Gradle, deterministic scheduler/executor fixtures.

**Spec:** `docs/superpowers/specs/2026-09-08-reconnect-heartbeat-accounting-design.md` (human approved).

## Global Constraints

- Work from local main `cbbd8c1`, on existing `fix/reconnect-heartbeat-accounting` branch.
- fish; `JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1`; all Gradle commands use `--offline`.
- Java 8 bytecode; no public API changes, new dependencies, or weakened checkstyle/SpotBugs checks.
- Preserve 30-second heartbeat, 15-second PONG timeout, generation checks, retry/cancellation, and failure classification.
- Drain the state executor after scheduler advancement and callbacks; advance deadlines separately from later reconnect backoff.

## Task 1: Correct accounting and prove session/feed survival

**Files:**
- Modify `src/main/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnector.java`.
- Test `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionLifecycleTest.java`.
- Test `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/AssetContextFeedTest.java`.
- Test `src/test/java/com/bookmap/plugins/layer0/hyperliquid/HyperliquidConnectorTest.java`.

**Interfaces:** Existing fixtures, `beginRecovery()`, `completeSends(int)`, `ack(SubscriptionType)`,
`emitTextFromConnection(int, String)`, `tryAcquireFrames(long, int)`, and `FrameReservation.close()`.
Produces no new production interface.

- [x] Run baseline `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`.
- [x] Add a lifecycle regression using `fixtureWithActiveBtc()`; restore the book and both ACKs, then execute ten heartbeat cycles. Core test body:

```java
Fixture fixture = fixtureWithActiveBtc();
fixture.beginRecovery();
fixture.completeSends(2);
fixture.book(2L);
fixture.ack(SubscriptionType.L2_BOOK);
fixture.ack(SubscriptionType.TRADES);
fixture.drain();
assertEquals(1, count(fixture.sink.events(), "connection-restored"));
for (int cycle = 0; cycle < 10; cycle++) {
  fixture.scheduler.advanceBy(30_000L);
  fixture.drain();
  assertEquals(0, count(fixture.sink.events(), "connection-lost:FATAL"));
  assertEquals(1, fixture.transport.socket().pendingSendCount());
  fixture.completeSends(1);
  fixture.transport.emitTextFromConnection(1, "{\"channel\":\"pong\"}");
  fixture.drain();
  assertEquals(2, fixture.transport.connectCalls().size());
  assertEquals(2, fixture.budget.reservedSubscriptionSlots());
}
fixture.scheduler.advanceBy(15_000L);
fixture.drain();
fixture.scheduler.advanceBy(1_000L);
fixture.drain();
fixture.trade(3L, 99L);
fixture.drain();
assertEquals(1, fixture.sink.trades().size());
assertEquals(2, fixture.transport.connectCalls().size());
fixture.session.close();
fixture.drain();
```

Also assert successful PING body count increments every cycle (not only pending sends).

- [x] Add a feed regression for a connection that **opened before disconnecting**. Use the existing BORSA fixture; close connection index 1, drain, advance 1000 ms, open connection 2 and complete its feed subscription. Run ten cycles, each advancing 30000 ms, asserting two pending PING sends (market + feed), completing them, delivering PONG to indices 0 and 2, and draining. Assert exactly three connection attempts and no new feed-disconnected diagnostic after each cycle. After the last cycle advance 15000 ms, then 1000 ms separately. Deliver a new `TestMetadata.assetContextsFrame` on index 2 and assert `sink.lastReferencePrice("HYPE")` equals its markPx. Close the session and drain. Update the old pre-open-retry test Javadoc so it no longer describes the bug as expected reconnect behavior.

```java
fixture.transport.remoteCloseConnection(1, 1006, "lost");
fixture.drain();
fixture.advance(1_000L);
assertEquals(3, fixture.transport.connectCalls().size());
fixture.open(2);
// Each cycle, after completing both PING sends:
fixture.transport.emitTextFromConnection(0, "{\"channel\":\"pong\"}");
fixture.transport.emitTextFromConnection(2, "{\"channel\":\"pong\"}");
fixture.drain();
assertEquals(3, fixture.transport.connectCalls().size());
assertEquals(1, countEvents(fixture.sink.events(), "diagnostic:asset-context feed disconnected"));
```

- [x] Run both new tests with `./gradlew --offline test --tests '*HyperliquidSessionLifecycleTest.reconnectedSessionSurvivesRepeatedHeartbeats' --tests '*AssetContextFeedTest.reconnectedFeedSurvivesRepeatedHeartbeats'` under the required JAVA_HOME. Expect failure on the second heartbeat, including FATAL on the session and feed disconnect on the dedicated connection; inspect XML failures. Do not modify production before this RED.
- [x] Add connector budget tests. With a private budget `(2, 10, 4, 2)`, open the Hyperliquid source and finish feed subscription, reconnect after 1000 ms and finish its subscription. At time 1000 two frames have been sent, so acquiring two held frames must succeed (proves no unused ping headroom). At 31000 advance heartbeat: pending count stays zero and socket stays open. Advance another 15001 ms and assert the unsent PING has not started a PONG timeout. Release the two frames, advance 10 ms, and assert one pending PING. Complete it and acknowledge PONG via `connector.acceptPong(listener.lastGeneration)`. Acquiring one remaining frame succeeds, acquiring another fails (proves PING consumed one rolling-window frame). Close reservation and connector.

```java
FrameReservation held = budget.tryAcquireFrames(1_000L, 2).permit();
assertTrue(held != null);
fixture.clock.now = 31_000L;
fixture.scheduler.advanceBy(30_000L);
assertEquals(0, fixture.transport.socket().pendingSendCount());
assertTrue(fixture.transport.socket().isOpen());
fixture.clock.now = 46_001L;
fixture.scheduler.advanceBy(15_001L);
assertEquals(0, fixture.transport.socket().pendingSendCount());
assertTrue(fixture.transport.socket().isOpen());
held.close();
fixture.clock.now = 46_011L;
fixture.scheduler.advanceBy(10L);
assertEquals(1, fixture.transport.socket().pendingSendCount());
fixture.transport.socket().succeedNextSend();
fixture.connector.acceptPong(fixture.listener.lastGeneration);
FrameReservation remaining = budget.tryAcquireFrames(46_011L, 1).permit();
assertTrue(remaining != null);
assertFalse(budget.tryAcquireFrames(46_011L, 1).acquired());
remaining.close();
fixture.connector.close();
```

- [x] Add a cancellation variant with the same saturated budget setup. After heartbeat is deferred, close connector, release held reservation, advance 60000 ms, assert no pending sends, no additional connect calls, and scheduler next delay is -1. This exercises cancellation after reconnect through the per-send route.
- [x] Apply only the production changes below:

```java
// scheduleHeartbeat: last argument to sendWhenPossible is always null.
sendWhenPossible(
    new OutboundMessage(OutboundMessage.Kind.PING, null, "{\"method\":\"ping\"}"),
    heartbeatGeneration,
    Long.MAX_VALUE,
    null);

/** Reserves only the subscription frames sent when a reconnected socket opens. */
private int reservedFrameCount() {
  return desired.size() + (sendsAssetContextFeed() ? 1 : 0);
}
```

- [x] In `reconnectReservationIsReplacedWhenAConnectionSubscriptionIsAdded`, replace the old assertion denying one extra frame by acquiring that remaining frame, asserting non-null, and closing it. The subsequent heartbeat still has room to send. Preserve other reservation-change and expiry assertions; investigate any failure against exact frame arithmetic before altering expectations.
- [x] Run the three changed test classes, plus existing budget tests, and require GREEN. No failure-classification or budget implementation change is authorized by this plan.

## Task 2: Mutation proof and final gates

**Files:** Same production/test files; record evidence in this plan. Temporary mutations must not remain.

**Interfaces:** Task 1 tests and existing `acceptPong(long)` path.

- [x] Save the fixed connector outside the tracked sources. Restore just the two original accounting expressions (`initialConnection ? null : connectionPermit` and `desired.size() + (sendsAssetContextFeed() ? 2 : 1)`). Rerun both new session/feed regressions: both must fail. Restore the fixed file even if the test result is unexpected.
- [x] Temporarily make `HyperliquidConnector.acceptPong(long)` a no-op; rerun both regressions and require failure (proves deadlines and PONG handling are exercised). Restore the fixed source. Record the assertion failures and exit codes for both mutations.
- [x] Read final diff against approved spec; use requesting-code-review skill for completion review, fix any material findings and rerun affected checks.
- [x] Run `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`. Require success, no suppression changes, and `git diff --check` success. Run verification-before-completion before reporting success.
- [x] Commit production, tests, approved spec status, and completed verification evidence. Leave the fix branch ready for review; do not push or merge without instruction.

## Plan Review Result

**Verdict:** READY

**Comprehensive reviews:** 1  
**Remaining BLOCKERs:** 0  
**Remaining MAJORs:** 0  
**Remaining MINORs:** 0  
**SPEC_BLOCKERs:** 0  
**Spec coverage:** COMPLETE  
**Structural changes:** No  
**Targeted re-check:** No

**Human decisions required:** None

**Why review stops here:** The plan maps accounting, pressure/retry, cancellation, market/feed survival, deadline observability, both mutations and the final gate to concrete existing interfaces. No unresolved design choices or material findings remain.

## Execution evidence

- Baseline: existing Gradle test task successful before test changes.
- RED before production changes: both new session/feed regressions failed on heartbeat 2.
  Session recorded `connection-lost:FATAL`; feed recorded
  `outbound frame reservation was unavailable`. Exit code 1.
- Budget RED: all three new connector tests failed on the old reserved PING headroom.
  The empty-relay case additionally covers `sendsAssetContextFeed() == false`.
- GREEN: the connector, session lifecycle, asset-context feed, and process budget test
  classes passed after the two accounting changes and one existing capacity expectation update.
- Mutation 1: restore both original accounting expressions, rerun both survival regressions.
  Both fail on heartbeat 2 with the original FATAL / reservation-unavailable evidence. Exit code 1.
- Mutation 2: make `HyperliquidConnector.acceptPong(long)` a no-op.
  Both survival regressions fail (expected pending PING count 1/2, actual 0 after the
  PONG timeout disconnect). Exit code 1. This proves scheduler/state-lane work executes.
- Both mutations restored in a `finally` block before validation.
- Independent read-only code review: READY; no Critical, Important, or Minor findings.
- Additional pressure assertion holds the PING unsent beyond 15 seconds, confirming that
  the PONG deadline does not run before actual transmission.

- Final gate (including the extra pressure assertion): BUILD SUCCESSFUL, exit code 0.
  JUnit: 301 tests, 0 failures/errors/skips; checkstyle main/test: 0 errors; SpotBugs main:
  0 bug instances; verifyJava8Bytecode passed. No suppressions or build settings changed.
  The build still prints Java 8/deprecated-API compiler notices and a missing optional
  Checker Framework Nullable annotation during SpotBugs analysis; neither task fails.
- Final git diff --check passed. Implementation remains on fix/reconnect-heartbeat-accounting.
