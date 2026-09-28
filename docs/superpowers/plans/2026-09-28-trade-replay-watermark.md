# Trade Replay Watermark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop publishing the recent trades Hyperliquid (and Borsa) replay on every `trades` subscription as if they were new, and after a reconnect publish only the trades newer than the last one published.

**Architecture:** Each `SubscriptionRecord` owns a `TradeWatermark`: the exchange time of the last published trade plus the trade ids published at exactly that time. It is anchored at activation to the exchange time of the book activation publishes, survives reconnects, and dies with the record. Every trade path in `HyperliquidSession` (pending buffer, activation, live, recovery buffer, restore) filters through it, replacing the global TTL-based `BoundedTradeDeduplicator`. `SubscriptionRecord.beginGeneration` also drops a pending book from the previous connection so the anchor always comes from the new connection.

**Tech Stack:** Java 8 source level, JUnit 4, Gson 2.4, Bookmap api-core 7.8.0.13, Gradle wrapper.

**Spec:** `docs/superpowers/specs/2026-09-28-trade-replay-watermark-design.md`

## Global Constraints

- Work directly on `main`; commit after every task. End every commit message with exactly these two
  lines, after a blank line, character for character. Do not substitute any other model name:

  ```text
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RyvRoc8Sfk5aSQ2mYFgfBm
  ```

- Gradle only starts with the JDK passed explicitly, and dependencies are cached offline. The shell
  is fish, so use `env`:
  `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline <tasks>`
- Full gate before every commit:
  `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline spotlessApply check verifyJava8Bytecode`
  (Spotless, Checkstyle with zero warnings, SpotBugs at LOW confidence, Java 8 bytecode).
- Checkstyle requires Javadoc on every public and package-private type and on public methods, and
  forbids non-private fields in classes (`VisibilityModifier`) — test fixtures included.
- All mutable session state is touched only on the state lane.
- Comparisons use exchange times only (`TradeEvent.time`, `l2Book.time`), never the local clock.
- Watermark rule, verbatim from the spec: `tradeTime > time` → admit; `tradeTime == time` → admit
  only if `tid` is not in `tidsAtTime`; `tradeTime < time` → reject. The anchor starts with an empty
  `tidsAtTime`, so a trade at exactly the book's time is published.
- One diagnostic line per `handleTrades` call and per `publishBuffered` call, only when at least one
  trade was dropped by the watermark. Never one line per trade.
- `TradeExecutions`, `TRADE_GAP_POSSIBLE` / `TRADE_RESUMED` triggers, `pendingTradeKeys` and the
  1,024-trade buffer limit do not change.
- Large-file rule: `HyperliquidSession.java` exceeds 1,000 lines. Edit it with targeted `Edit` calls
  only; never rewrite it whole. After each edit run `git diff --check` and read `git diff`.

## File Structure

| File | Action | Responsibility |
| --- | --- | --- |
| `src/main/java/.../session/TradeWatermark.java` | Create | The admit/advance/anchor rule for one subscription |
| `src/test/java/.../session/TradeWatermarkTest.java` | Create | Unit tests for the rule |
| `src/main/java/.../model/TradeKey.java` | Modify | Adds `time()` and `tid()` |
| `src/main/java/.../session/SubscriptionRecord.java` | Modify | Owns a `TradeWatermark`; `beginGeneration` drops `pendingBook` (Task 3) |
| `src/main/java/.../session/HyperliquidSession.java` | Modify | Every trade path goes through the watermark; deduplicator removed |
| `src/main/java/.../trade/BoundedTradeDeduplicator.java` | Delete | Replaced by the watermark |
| `src/test/java/.../trade/BoundedTradeDeduplicatorTest.java` | Delete | Tests the deleted class |
| `src/test/java/.../session/HyperliquidSessionTradeReplayTest.java` | Create | Session behavior for replays |
| `src/test/java/.../session/HyperliquidSessionDeltaBookTest.java` | Modify | Borsa anchor case |
| `src/test/java/.../session/HyperliquidSessionSubscriptionTest.java` | Modify | Three fixtures whose trades predated the book |
| `src/test/java/.../ProviderEndToEndTest.java` | Modify | The TTL/capacity test becomes a never-again test |
| `README.md`, `docs/development.md` | Modify | User-visible behavior, package table |
| `docs/superpowers/specs/2026-09-28-trade-replay-watermark-design.md` | Modify | Status line at the end |

`...` is `com/bookmap/plugins/layer0/hyperliquid` throughout.

Verified before writing this plan: the code below was applied to a throwaway copy of `c1c442c`; the
full gate passed with 422 tests, the new session tests failed on the unmodified code for the reasons
listed in each task, and removing only the Task 3 line failed only the Task 3 test.

---

### Task 1: `TradeWatermark`

**Files:**
- Create: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermark.java`
- Test: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermarkTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: package-private `final class TradeWatermark` in `...session` with
  `void anchor(long bookTime)`, `boolean admits(long tradeTime, long tid)`,
  `void advance(long tradeTime, long tid)` (throws `IllegalArgumentException` when `tradeTime` is
  below the current time). A new instance has time `-1`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermarkTest.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The rule that decides whether a trade is newer than everything already published. */
public class TradeWatermarkTest {

  @Test
  public void beforeTheAnchorEveryTradeIsAdmitted() {
    TradeWatermark watermark = new TradeWatermark();

    assertTrue(watermark.admits(0L, 1L));
    assertTrue(watermark.admits(5L, 1L));
  }

  @Test
  public void anchorRejectsOlderTradesAndAdmitsTradesAtOrAfterIt() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);

    assertFalse(watermark.admits(9L, 1L));
    assertTrue(watermark.admits(10L, 1L));
    assertTrue(watermark.admits(11L, 1L));
  }

  @Test
  public void sameTimeIsAdmittedOnlyForAnUnseenTradeId() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);

    assertFalse(watermark.admits(10L, 7L));
    assertTrue(watermark.admits(10L, 8L));
  }

  @Test
  public void advancingTheTimeForgetsTheTradeIdsOfTheOlderTime() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);
    watermark.advance(11L, 8L);

    assertFalse(watermark.admits(10L, 9L));
    assertFalse(watermark.admits(11L, 8L));
    assertTrue(watermark.admits(11L, 7L));
  }

  @Test
  public void advancingWithinTheSameTimeRemembersEveryTradeId() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(11L, 1L);
    watermark.advance(11L, 2L);

    assertFalse(watermark.admits(11L, 1L));
    assertFalse(watermark.admits(11L, 2L));
    assertTrue(watermark.admits(11L, 3L));
  }

  @Test
  public void anchoringAgainClearsTheTradeIds() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);
    watermark.advance(10L, 7L);

    watermark.anchor(10L);

    assertTrue(watermark.admits(10L, 7L));
  }

  @Test(expected = IllegalArgumentException.class)
  public void advancingBackwardsIsRejected() {
    TradeWatermark watermark = new TradeWatermark();
    watermark.anchor(10L);

    watermark.advance(9L, 1L);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TradeWatermarkTest'`
Expected: FAIL — compilation error, `cannot find symbol: class TradeWatermark`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermark.java`:

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import java.util.HashSet;
import java.util.Set;

/**
 * The exchange time of the last trade published for one subscription. Trades older than it are
 * replays of trades already published, or of trades from before the subscription's first book, and
 * are never published again. Trades at exactly that time are told apart by their trade id.
 */
final class TradeWatermark {

  private final Set<Long> tidsAtTime = new HashSet<Long>();
  private long time = -1L;

  /** Starts the watermark at the exchange time of the first book the subscription publishes. */
  void anchor(long bookTime) {
    time = bookTime;
    tidsAtTime.clear();
  }

  /** Returns whether a trade is newer than everything already published. */
  boolean admits(long tradeTime, long tid) {
    if (tradeTime != time) {
      return tradeTime > time;
    }
    return !tidsAtTime.contains(Long.valueOf(tid));
  }

  /** Records a published trade; a trade older than the watermark is a caller error. */
  void advance(long tradeTime, long tid) {
    if (tradeTime < time) {
      throw new IllegalArgumentException("trade time moved backwards");
    }
    if (tradeTime > time) {
      time = tradeTime;
      tidsAtTime.clear();
    }
    tidsAtTime.add(Long.valueOf(tid));
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TradeWatermarkTest'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Run the full gate and commit**

Run the full gate from Global Constraints. Expected: `BUILD SUCCESSFUL`.

```bash
git add src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermark.java \
  src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/TradeWatermarkTest.java
git commit -m "feat: add the per-subscription trade watermark

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyvRoc8Sfk5aSQ2mYFgfBm"
```

---

### Task 2: Publish trades through the watermark

Replaces `BoundedTradeDeduplicator` on all five trade paths. After this task, trades older than the
activation book are never published, and a reconnect publishes only the trades newer than the last
published one.

**Files:**
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/TradeKey.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`
- Delete: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/trade/BoundedTradeDeduplicator.java`
- Delete: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/trade/BoundedTradeDeduplicatorTest.java`
- Create: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTradeReplayTest.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionDeltaBookTest.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionSubscriptionTest.java`
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`
- Modify: `README.md`, `docs/development.md`

**Interfaces:**
- Consumes: `TradeWatermark` from Task 1 (`anchor`, `admits`, `advance`).
- Produces: `public long time()` and `public long tid()` on `TradeKey`; package-private
  `TradeWatermark tradeWatermark()` on `SubscriptionRecord`; the new test class
  `HyperliquidSessionTradeReplayTest` with a `Fixture` that Task 3 extends (methods used by Task 3:
  `login()`, `subscribe(String)`, `book(String coin, long time)`, `drain()`, `reconnect()`,
  `completeAllSends()`, `trades(String...)`, `acks(String)`, `tids()`, field `sink`; static helper
  `trade(long time, long tid)`).

- [ ] **Step 1: Write the new session tests**

Create `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTradeReplayTest.java`.
Its fixture is the one in `HyperliquidSessionTradeExecutionTest`, adapted: books take an explicit
time, and published trades are identified by their price (`100 + tid`):

```java
package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TaskFailures;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/**
 * Session behavior for the recent trades Hyperliquid replays on every trades subscription: trades
 * older than the first published book are never published, and after a reconnect only trades newer
 * than the last published one are.
 */
public class HyperliquidSessionTradeReplayTest {

  @Test
  public void replayBufferedBeforeActivationDropsTradesOlderThanTheBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");

    fixture.trades(trade(1L, 1L), trade(2L, 2L), trade(10L, 3L));
    fixture.bookAndAcks(10L);

    assertEquals("[3]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void replayArrivingAfterActivationDropsTradesOlderThanTheBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);

    fixture.trades(trade(1L, 1L), trade(2L, 2L), trade(10L, 3L), trade(11L, 4L));

    assertEquals("[3, 4]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void reconnectPublishesOnlyTradesNewerThanTheLastPublishedOne() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L), trade(12L, 2L));
    fixture.clock.now += 11L * 60_000L;

    fixture.reconnect();
    fixture.completeAllSends();
    fixture.trades(trade(11L, 1L), trade(12L, 2L), trade(13L, 3L));
    fixture.bookAndAcks(14L);

    assertEquals("[1, 2, 3]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 2 trades").size());
  }

  @Test
  public void sameTimeIsPublishedForANewTradeIdOnly() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L));
    assertEquals(0, fixture.diagnostics("discarded").size());

    fixture.trades(trade(11L, 1L), trade(11L, 2L));

    assertEquals("[1, 2]", fixture.tids().toString());
    assertEquals(1, fixture.diagnostics("discarded 1 trades").size());
  }

  @Test
  public void resubscribingNeverPublishesTheEarlierSubscriptionsTradesAgain() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.bookAndAcks(10L);
    fixture.trades(trade(11L, 1L));
    fixture.unsubscribe("BTC");

    fixture.subscribe("BTC");
    fixture.trades(trade(11L, 1L), trade(12L, 2L));
    fixture.bookAndAcks(13L);

    assertEquals("[1]", fixture.tids().toString());
  }

  /** One BTC buy fill whose price, 100 + tid, identifies it in the published trades. */
  private static String trade(long time, long tid) {
    return "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\""
        + (100L + tid)
        + ".000\",\"sz\":\"1\",\"time\":"
        + time
        + ",\"tid\":"
        + tid
        + "}";
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Long.TYPE);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (Exception failure) {
      throw new AssertionError(failure);
    }
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final Clock clock = new Clock();
    private final Scheduler scheduler = new Scheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                // overflow is not exercised here
              }
            },
            TaskFailures::rethrow);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            new AssetContextConnectorFactory() {
              @Override
              public HyperliquidConnector create() {
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
            new MetadataRequestFactory() {
              @Override
              public MetadataRequest create(
                  SourceProfile profile, MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    dispatcher::submitControl,
                    profile.infoUri(),
                    callback);
              }
            },
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });
    private long generation = 1L;

    private void login() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("BTC", "ETH")));
      drain();
      transport.openSocket();
      drain();
      completeAllSends();
    }

    private void subscribe(String coin) {
      session.subscribe(coin, "", "PERPETUAL");
      drain();
      completeAllSends();
    }

    private void unsubscribe(String coin) {
      session.unsubscribe(coin);
      drain();
      completeAllSends();
    }

    private void bookAndAcks(long time) {
      book("BTC", time);
      acks("BTC");
    }

    private void acks(String coin) {
      ack(coin, SubscriptionType.L2_BOOK);
      ack(coin, SubscriptionType.TRADES);
      drain();
    }

    private void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation++;
      drain();
    }

    private void completeAllSends() {
      while (transport.socket().pendingSendCount() > 0) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void trades(String... fills) {
      StringBuilder frame = new StringBuilder("{\"channel\":\"trades\",\"data\":[");
      for (int index = 0; index < fills.length; index++) {
        if (index > 0) {
          frame.append(',');
        }
        frame.append(fills[index]);
      }
      session.onFrame(generation, frame.append("]}").toString());
      drain();
    }

    private void book(String coin, long time) {
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
              + coin
              + "\",\"time\":"
              + time
              + ",\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"}],[]]}}");
    }

    private void ack(String coin, SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\""
              + coin
              + "\"}}}");
    }

    /** Returns the tid of every published trade, recovered from its price of 100 + tid. */
    private List<Long> tids() {
      List<Long> tids = new ArrayList<Long>();
      for (RecordingSessionSink.Trade trade : sink.trades()) {
        tids.add(Long.valueOf(Math.round(trade.price() / 10_000d) - 100L));
      }
      return tids;
    }

    private List<String> diagnostics(String prefix) {
      List<String> matching = new ArrayList<String>();
      for (String event : sink.events()) {
        if (event.startsWith("diagnostic:" + prefix)) {
          matching.add(event);
        }
      }
      return matching;
    }

    private void drain() {
      executor.drain();
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    private void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }

  private static final class Clock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class Scheduler implements CancellableScheduler {
    private final Clock clock;
    private final List<Task> tasks = new ArrayList<Task>();

    private Scheduler(Clock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis));
      tasks.add(scheduled);
      return scheduled;
    }

    private void advanceBy(long millis) {
      long target = clock.now + millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= target && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          clock.now = target;
          return;
        }
        tasks.remove(due);
        clock.now = Math.max(clock.now, due.due);
        due.task.run();
      }
    }

    private static final class Task implements Cancellable {
      private final Runnable task;
      private final long due;
      private boolean cancelled;

      private Task(Runnable task, long due) {
        this.task = task;
        this.due = due;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }
    }
  }
}
```

- [ ] **Step 2: Add the Borsa case**

In `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionDeltaBookTest.java`,
insert immediately before `  private static String ack(SubscriptionType type) {`:

```java
  @Test
  public void tradesOlderThanTheBookPublishedAtActivationAreDropped() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);

    fixture.receive(
        trades(
            trade(5L, "99.000"),
            trade(11L, "100.000"),
            trade(12L, "101.000"),
            trade(15L, "102.000")));
    fixture.receive(book("BTC", 10L, bids(level("100.000", "1")), asks()));
    fixture.receive(book("BTC", 12L, bids(level("101.000", "2")), asks()));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    // The seed at 10 and the delta at 12 are published together, so the watermark starts at 12.
    assertEquals(2, fixture.sink.trades().size());
    assertEquals(1_010_000d, fixture.sink.trades().get(0).price(), 0d);
    assertEquals(1_020_000d, fixture.sink.trades().get(1).price(), 0d);
  }

  private static String trades(String... fills) {
    return "{\"channel\":\"trades\",\"data\":[" + String.join(",", fills) + "]}";
  }

  private static String trade(long time, String price) {
    return "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\""
        + price
        + "\",\"sz\":\"1\",\"time\":"
        + time
        + ",\"tid\":"
        + time
        + "}";
  }

```

The `drain()` between the books and the acks matters: the dispatcher runs control events (acks)
before market frames, so without it activation happens at the seed (10) instead of after the delta
(12), and the test would not distinguish the two anchors.

- [ ] **Step 3: Update the existing tests whose trades predate the book or that pin the old TTL**

These tests keep their purpose; only book times move to or before their trades' times, because a
trade older than the activation book is now a replay by definition.

In `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionSubscriptionTest.java`:

1. `pendingTradesStayFifoAndCommitOnlyWhenActivationPublishes` (trades at 1 and 2): change
   `fixture.receive(book("BTC", 3L, "102.000", "1"));` to
   `fixture.receive(book("BTC", 1L, "102.000", "1"));`
2. `pendingTradeBufferDropsOnlyAfterIts1024thUniqueTrade` (trades at 1..1025): change
   `fixture.receive(book("BTC", 2_000L, "102.000", "1"));` to
   `fixture.receive(book("BTC", 1L, "102.000", "1"));`
3. `pendingDuplicateKeyCanBePublishedAfterRemovalAndResubscription` (trade at 9): change
   `fixture.receive(book("BTC", 10L, "100.000", "1"));` to
   `fixture.receive(book("BTC", 9L, "100.000", "1"));`

Each of these strings occurs exactly once in the file; check with `grep -c` before editing.

In `src/test/java/com/bookmap/plugins/layer0/hyperliquid/ProviderEndToEndTest.java`, the test
`tradeDeduplicationUsesTenMinuteTtlAndTenThousandEntryCapacity` asserts the defect this plan removes
(a trade buried under 10,000 newer ones is published again). Rename it and invert its last step:

```java
  @Test
  public void aTradeIsNeverPublishedAgainHoweverLateOrBuriedItsReplay() {
```

and replace

```java
    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();
    assertEquals(10_002, fixture.data.trades.size());
```

with

```java
    fixture.clock.now = 60_000L + 11L * 60_000L;
    fixture.trade("BTC", "B", "100", "1", 2L, 7L);
    fixture.drain();
    assertEquals(10_001, fixture.data.trades.size());
```

(The earlier block of that test, from `fixture.trade("BTC", "B", "100", "1", 2L, 7L);` at clock
`60_000L` through `assertEquals(10_001, ...)`, stays unchanged.)

- [ ] **Step 4: Run the tests to verify the new ones fail**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: FAIL with exactly these 7 failures, everything else passing:
- `HyperliquidSessionTradeReplayTest.replayBufferedBeforeActivationDropsTradesOlderThanTheBook` —
  expected `[3]` but was `[1, 2, 3]`
- `...replayArrivingAfterActivationDropsTradesOlderThanTheBook` — expected `[3, 4]` but was
  `[1, 2, 3, 4]`
- `...reconnectPublishesOnlyTradesNewerThanTheLastPublishedOne` — expected `[1, 2, 3]` but was
  `[1, 2, 1, 2, 3]` (the TTL expired)
- `...sameTimeIsPublishedForANewTradeIdOnly` — diagnostic count expected 1 but was 0
- `...resubscribingNeverPublishesTheEarlierSubscriptionsTradesAgain` — expected `[1]` but was `[1, 2]`
- `HyperliquidSessionDeltaBookTest.tradesOlderThanTheBookPublishedAtActivationAreDropped` — expected 2
  but was 4
- `ProviderEndToEndTest.aTradeIsNeverPublishedAgainHoweverLateOrBuriedItsReplay` — expected 10001 but
  was 10002

- [ ] **Step 5: Add accessors to `TradeKey`**

In `src/main/java/com/bookmap/plugins/layer0/hyperliquid/model/TradeKey.java`, insert after the
constructor (after the line `    this.tid = tid;` and its closing `  }`):

```java

  /** Returns the exchange time of the trade in milliseconds. */
  public long time() {
    return time;
  }

  /** Returns the exchange trade id. */
  public long tid() {
    return tid;
  }
```

`equals` and `hashCode` do not change.

- [ ] **Step 6: Give `SubscriptionRecord` a watermark**

In `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`:

After `  private final Set<TradeKey> pendingTradeKeys = new HashSet<TradeKey>();` add:

```java
  private final TradeWatermark tradeWatermark = new TradeWatermark();
```

Insert immediately before `  /** Stores a converted pending trade when its bounded queue has room. */`:

```java
  /** Returns the exchange time of the last published trade, anchored at activation. */
  TradeWatermark tradeWatermark() {
    return tradeWatermark;
  }

```

- [ ] **Step 7: Route every trade path in `HyperliquidSession` through the watermark**

All edits are in `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSession.java`.
Use one targeted `Edit` per item; each `old_string` below occurs exactly once.

7a. Imports: add `import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;` after
`import com.bookmap.plugins.layer0.hyperliquid.model.TradeEvent;`, and delete
`import com.bookmap.plugins.layer0.hyperliquid.trade.BoundedTradeDeduplicator;`.

7b. Delete the constants

```java
  private static final int TRADE_DEDUPLICATION_CAPACITY = 10_000;
  private static final long TRADE_DEDUPLICATION_TTL_MILLIS = 600_000L;
```

and the field

```java
  private final BoundedTradeDeduplicator tradeDeduplicator =
      new BoundedTradeDeduplicator(TRADE_DEDUPLICATION_CAPACITY, TRADE_DEDUPLICATION_TTL_MILLIS);
```

and, in `stop`, the line `    tradeDeduplicator.clear();`.

7c. In `handleTrades`, after `    List<PendingTrade> publishable = new ArrayList<PendingTrade>();`
add `    int stale = 0;`. Replace

```java
      boolean pendingBook = record.state() == SubscriptionRecord.State.PENDING_BOOK;
      if (pendingBook || (recovering && connectionState == ConnectionState.RECONNECTING)) {
        bufferTrade(record, converted, pendingBook ? "pending" : "recovery");
        continue;
      }
      if (!tradeDeduplicator.markIfNew(converted.key(), clock.getAsLong())) {
        continue;
      }
```

with

```java
      boolean pendingBook = record.state() == SubscriptionRecord.State.PENDING_BOOK;
      if (pendingBook) {
        bufferTrade(record, converted, "pending");
        continue;
      }
      if (recovering && connectionState == ConnectionState.RECONNECTING) {
        if (admits(record, converted)) {
          bufferTrade(record, converted, "recovery");
        } else {
          stale++;
        }
        continue;
      }
      if (!admit(record, converted)) {
        stale++;
        continue;
      }
```

and at the end of `handleTrades` replace

```java
      publishable.add(converted);
    }
    publishTrades(publishing, publishable);
  }
```

with

```java
      publishable.add(converted);
    }
    publishTrades(publishing, publishable);
    reportStaleTrades(stale, "");
  }
```

7d. In `bufferTrade`, replace

```java
    if (tradeDeduplicator.contains(trade.key(), clock.getAsLong())
        || record.containsPendingTradeKey(trade.key())) {
```

with

```java
    if (record.containsPendingTradeKey(trade.key())) {
```

7e. In `activateIfReady`, replace

```java
    dataHealth.bookPublished(record.alias(), currentGeneration);
    publishBuffered(record, record.takePendingTrades());
  }
```

with

```java
    dataHealth.bookPublished(record.alias(), currentGeneration);
    record.tradeWatermark().anchor(record.lastAcceptedBookTime());
    publishBuffered(record, record.takePendingTrades());
  }
```

At this point `lastAcceptedBookTime()` is the exchange time of the book just published: snapshot mode
sets it in `acceptPendingBook`, delta mode in `acceptActiveBook` for the seed and every delta.

7f. Replace `publishBuffered` entirely:

```java
  /** Publishes a drained buffer, dropping whatever was published in the meantime. */
  private void publishBuffered(SubscriptionRecord record, ArrayDeque<PendingTrade> buffered) {
    List<PendingTrade> fresh = new ArrayList<PendingTrade>(buffered.size());
    for (PendingTrade trade : buffered) {
      if (tradeDeduplicator.markIfNew(trade.key(), clock.getAsLong())) {
        fresh.add(trade);
      }
    }
    publishTrades(record, fresh);
  }
```

with

```java
  /** Publishes a drained buffer, dropping whatever is not newer than the trade watermark. */
  private void publishBuffered(SubscriptionRecord record, ArrayDeque<PendingTrade> buffered) {
    List<PendingTrade> fresh = new ArrayList<PendingTrade>(buffered.size());
    int stale = 0;
    for (PendingTrade trade : buffered) {
      if (admit(record, trade)) {
        fresh.add(trade);
      } else {
        stale++;
      }
    }
    publishTrades(record, fresh);
    reportStaleTrades(stale, " for " + record.alias());
  }

  /** Returns whether a trade is newer than the last one published for its subscription. */
  private static boolean admits(SubscriptionRecord record, PendingTrade trade) {
    TradeKey key = trade.key();
    return record.tradeWatermark().admits(key.time(), key.tid());
  }

  /** Advances the watermark past a trade that will be published, or returns false for a replay. */
  private static boolean admit(SubscriptionRecord record, PendingTrade trade) {
    if (!admits(record, trade)) {
      return false;
    }
    TradeKey key = trade.key();
    record.tradeWatermark().advance(key.time(), key.tid());
    return true;
  }

  /**
   * Reports, in one line, trades dropped because they are not newer than the watermark. Every
   * subscription start and every reconnect replays recent trades, so this is routine.
   */
  private void reportStaleTrades(int stale, String scope) {
    if (stale > 0) {
      sink.onDiagnostic(
          "discarded " + stale + " trades not newer than the last published trade" + scope);
    }
  }
```

7g. Confirm nothing else refers to the deduplicator:
`grep -n "tradeDeduplicator\|TRADE_DEDUPLICATION\|BoundedTradeDeduplicator" src/main/java -r`
Expected: no output.

- [ ] **Step 8: Delete the deduplicator**

```bash
git rm -r src/main/java/com/bookmap/plugins/layer0/hyperliquid/trade \
  src/test/java/com/bookmap/plugins/layer0/hyperliquid/trade
```

- [ ] **Step 9: Run the tests to verify they pass**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS (421 tests; the 7 deleted `BoundedTradeDeduplicatorTest` cases are gone).

- [ ] **Step 10: Update the documentation**

`README.md` — insert a new paragraph between the paragraph ending `are delivered one by one, as
before.` and the paragraph starting `Not supported:`:

```markdown
Every trades subscription starts with the exchange replaying its most recent trades. The adapter
never delivers a trade older than the instrument's first book, so nothing from before the
subscription appears at the moment you subscribe. After a reconnect the replay is used to recover
what the disconnect missed: trades newer than the last one delivered for that instrument are
delivered, at the time of recovery, and older ones are dropped. The replay is short — at the time of
writing 30 trades from Hyperliquid, 100 from Borsa and none from Hyperdash — and anything the
disconnect lost beyond it is not recovered.
```

`README.md` — in the data-health table, the `TRADE_GAP_POSSIBLE` row's last cell: replace
`Treat trades in that interval as incomplete. They are not backfilled.` with
`Treat trades in that interval as incomplete. Only trades the exchange replays on reconnect are recovered; nothing else is backfilled.`

`README.md` — in Limits, replace

```markdown
- During a disconnect the adapter reconnects and resynchronizes, but there is no historical gap fill
  and trades can be missed.
```

with

```markdown
- During a disconnect the adapter reconnects and resynchronizes. Trades are recovered only from the
  exchange's short replay on reconnect; there is no historical gap fill and trades can be missed.
```

`docs/development.md` — delete the table row
`| `hyperliquid.trade` | Duplicate suppression for trade notifications |`.

- [ ] **Step 11: Run the full gate and commit**

Run the full gate from Global Constraints. Expected: `BUILD SUCCESSFUL`. Then `git diff --check`
(no output) and read `git diff --stat`.

```bash
git add -A src README.md docs/development.md
git commit -m "fix: never publish replayed trades that are not newer than the last published one

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyvRoc8Sfk5aSQ2mYFgfBm"
```

---

### Task 3: Anchor a pending subscription on the new connection's book

`SubscriptionRecord.beginGeneration` keeps `pendingBook`, so a subscription still `PENDING_BOOK` when
the connection drops activates on the pre-disconnect book as soon as the new acks arrive, and that
old book's time becomes the watermark anchor (letting part of the replay through). Delta mode already
waits for a new seed.

**Files:**
- Modify: `src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTradeReplayTest.java`
- Modify: `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`
- Modify: `docs/superpowers/specs/2026-09-28-trade-replay-watermark-design.md`

**Interfaces:**
- Consumes: `HyperliquidSessionTradeReplayTest.Fixture` and `trade(long, long)` from Task 2.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

In `HyperliquidSessionTradeReplayTest.java`, add the imports

```java
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
```

after `import static org.junit.Assert.assertEquals;`, and insert immediately before
`  /** One BTC buy fill whose price, 100 + tid, identifies it in the published trades. */`:

```java
  @Test
  public void pendingSubscriptionAnchorsOnTheFirstBookOfTheNewConnection() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.book("BTC", 10L);
    fixture.drain();

    fixture.reconnect();
    fixture.completeAllSends();
    fixture.trades(trade(11L, 1L), trade(20L, 2L));
    fixture.acks("BTC");
    assertFalse(fixture.sink.events().contains("instrument-added:BTC"));

    fixture.book("BTC", 20L);
    fixture.drain();

    assertTrue(fixture.sink.events().contains("instrument-added:BTC"));
    assertEquals("[2]", fixture.tids().toString());
  }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test --tests '*TradeReplayTest'`
Expected: FAIL — `pendingSubscriptionAnchorsOnTheFirstBookOfTheNewConnection` fails at the
`assertFalse` (the instrument was added on the acks, using the book from before the disconnect).

- [ ] **Step 3: Drop the pending book when a new connection opens**

In `src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java`, inside
`beginGeneration`, replace

```java
    acknowledgements.clear();
    clearRecoveryBook();
    if (deltaBook != null) {
      deltaBook.beginGeneration();
```

with

```java
    acknowledgements.clear();
    pendingBook = null;
    clearRecoveryBook();
    if (deltaBook != null) {
      deltaBook.beginGeneration();
```

(`removeRecord`'s reset also contains `acknowledgements.clear();` followed by `pendingBook = null;`;
the `old_string` above, which includes `deltaBook.beginGeneration();`, occurs only once.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `env JAVA_HOME=/home/dev/Downloads/jdk-21.0.12.1 ./gradlew --offline test`
Expected: PASS, 422 tests.

- [ ] **Step 5: Mark the spec implemented, run the full gate and commit**

In `docs/superpowers/specs/2026-09-28-trade-replay-watermark-design.md`, replace
`- 状態: spec-review READY、人間の承認済み` with `- 状態: spec-review READY、人間の承認済み、実装済み`.

Run the full gate from Global Constraints. Expected: `BUILD SUCCESSFUL`.

```bash
git add src/test/java/com/bookmap/plugins/layer0/hyperliquid/session/HyperliquidSessionTradeReplayTest.java \
  src/main/java/com/bookmap/plugins/layer0/hyperliquid/session/SubscriptionRecord.java \
  docs/superpowers/specs/2026-09-28-trade-replay-watermark-design.md
git commit -m "fix: wait for the new connection's book before activating a pending subscription

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RyvRoc8Sfk5aSQ2mYFgfBm"
```

---

## After implementation (manual, on a live Bookmap)

From the spec's "実装後に実機で確認すべき事項":

- Subscribe to a quiet Mainnet spot pair (for example `@3`); no past trades appear at subscription.
- Drop the network briefly; after recovery only the missed trades appear, none from before the
  disconnect are drawn again.
- Repeat with Borsa and Hyperdash.
