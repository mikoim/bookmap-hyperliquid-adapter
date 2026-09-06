package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Exercises the Borsa seed-then-delta book path through the real session and connector. */
public class HyperliquidSessionDeltaBookTest {

  @Test
  public void seedIsPublishedOnActivationAndDeltasStreamLive() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 1L, bids(level("100.000", "1"), level("99.000", "2")), asks()));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:BTC", "depth:BTC:990000:200", "depth:BTC:1000000:100"),
        fixture.sink.events());
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 1L, bids(level("100.000", "0")), asks(level("102.000", "3"))));
    fixture.drain();
    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "depth:BTC:1020000:300"), fixture.sink.events());
  }

  @Test
  public void deltaBeforeActivationIsFoldedIntoTheSeedNotPublished() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
    fixture.receive(book("BTC", 1L, bids(level("101.000", "2")), asks()));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.drain();
    assertTrue(fixture.sink.events().isEmpty());
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(
        Arrays.asList("instrument-added:BTC", "depth:BTC:1000000:100", "depth:BTC:1010000:200"),
        fixture.sink.events());
  }

  @Test
  public void staleDeltasAndUnknownRemovalsAreIgnored() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 0L, bids(level("105.000", "1")), asks()));
    fixture.receive(book("BTC", 2L, bids(level("999.000", "0")), asks()));
    fixture.drain();

    assertEquals(
        Collections.singletonList("diagnostic:discarded stale book delta for BTC"),
        fixture.sink.events());
  }

  @Test
  public void overflowReconnectResynchronizesFromAFreshSeed() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    int connectCallsBefore = fixture.transport.connectCalls().size();

    fixture.session.onMarketOverflow();
    fixture.drain();
    fixture.clock.now += 1_000L;
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.completeSends(2);

    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    fixture.receive(book("BTC", 2L, bids(level("101.000", "2")), asks()));
    fixture.drain();

    assertEquals(connectCallsBefore + 1, fixture.transport.connectCalls().size());
    assertTrue(fixture.sink.events().contains("depth:BTC:1000000:0"));
    assertTrue(fixture.sink.events().contains("depth:BTC:1010000:200"));
    assertEquals(1, Collections.frequency(fixture.sink.events(), "connection-restored"));
  }

  @Test
  public void seedArrivingAfterRestoreReplacesThePublishedBook() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertEquals(Collections.singletonList("connection-restored"), fixture.sink.events());
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 0L, bids(level("101.000", "2")), asks()));
    fixture.drain();

    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "depth:BTC:1010000:200"), fixture.sink.events());
  }

  @Test
  public void seedAndDeltasBeforeRestoreArePublishedAtTheBarrier() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(book("BTC", 5L, bids(level("101.000", "2")), asks()));
    fixture.receive(book("BTC", 5L, bids(), asks(level("102.000", "3"))));
    fixture.drain();
    assertTrue(fixture.sink.events().isEmpty());
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(
        Arrays.asList(
            "connection-restored",
            "depth:BTC:1000000:0",
            "depth:BTC:1010000:200",
            "depth:BTC:1020000:300"),
        fixture.sink.events());
  }

  @Test
  public void generationChangeWhilePendingWaitsForTheNewSeed() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
    fixture.drain();
    fixture.beginRecovery();
    fixture.completeSends(2);
    fixture.sink.events().clear();

    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());

    fixture.receive(book("BTC", 1L, bids(level("101.000", "2")), asks()));
    fixture.drain();

    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    assertTrue(fixture.sink.events().contains("depth:BTC:1010000:200"));
    assertTrue(!fixture.sink.events().contains("depth:BTC:1000000:100"));
  }

  @Test
  public void seedWithUnsupportedPriceRemovesTheAlias() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);

    fixture.receive(book("BTC", 1L, bids(level("99999999999.000", "1")), asks()));
    fixture.drain();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  private static String ack(SubscriptionType type) {
    return "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
        + "\"subscription\":{\"type\":\""
        + type.wireName()
        + "\",\"coin\":\"BTC\"}}}";
  }

  private static String book(String coin, long time, String bids, String asks) {
    return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
        + coin
        + "\",\"time\":"
        + time
        + ",\"levels\":[["
        + bids
        + "],["
        + asks
        + "]]}}";
  }

  private static String bids(String... levels) {
    return String.join(",", levels);
  }

  private static String asks(String... levels) {
    return String.join(",", levels);
  }

  private static String level(String price, String size) {
    return "{\"px\":\"" + price + "\",\"sz\":\"" + size + "\",\"n\":1}";
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

  private static void noop() {
    // no-op
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionDeltaBookTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            HyperliquidSessionDeltaBookTest::noop);
    private long generation = 1L;

    void login() {
      session.login(SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]}");
      drain();
      transport.openSocket();
      drain();
    }

    void subscribe(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
      drain();
    }

    void activateBtc() {
      login();
      subscribe("BTC");
      completeSends(2);
      receive(book("BTC", 1L, bids(level("100.000", "1")), asks()));
      receive(ack(SubscriptionType.L2_BOOK));
      receive(ack(SubscriptionType.TRADES));
      drain();
    }

    void beginRecovery() {
      transport.remoteClose(1006, "lost");
      drain();
      clock.now += 1_000L;
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      generation = 2L;
      drain();
    }

    void receive(String frame) {
      session.onFrame(generation, frame);
    }

    void completeSends(int count) {
      for (int index = 0; index < count; index++) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    void drain() {
      executor.drain();
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }

  private static final class TestScheduler implements CancellableScheduler {
    private final MutableClock clock;
    private final java.util.PriorityQueue<Task> tasks = new java.util.PriorityQueue<Task>();
    private long sequence;

    TestScheduler(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMillis) {
      Task scheduled = new Task(task, clock.now + Math.max(0L, delayMillis), sequence++);
      tasks.add(scheduled);
      return scheduled;
    }

    void advanceBy(long elapsed) {
      while (!tasks.isEmpty() && tasks.peek().due <= clock.now) {
        Task next = tasks.remove();
        if (!next.cancelled) {
          next.task.run();
        }
      }
    }

    private static final class Task implements Cancellable, Comparable<Task> {
      private final Runnable task;
      private final long due;
      private final long sequence;
      private boolean cancelled;

      Task(Runnable task, long due, long sequence) {
        this.task = task;
        this.due = due;
        this.sequence = sequence;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }

      @Override
      public int compareTo(Task other) {
        int compareDue = Long.compare(due, other.due);
        return compareDue != 0 ? compareDue : Long.compare(sequence, other.sequence);
      }
    }
  }

  private static final class ManualExecutor implements Executor {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable task) {
      tasks.addLast(task);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }
}
