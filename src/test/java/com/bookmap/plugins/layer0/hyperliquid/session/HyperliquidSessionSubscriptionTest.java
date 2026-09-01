package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Exercises subscription activation and market delivery through the real connector boundary. */
public class HyperliquidSessionSubscriptionTest {

  @Test
  public void addsOnlyAfterBothAcknowledgementsAndTheLatestValidBook() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();

    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();

    fixture.receive(book("BTC", 1L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.drain();
    assertTrue(fixture.sink.addedAliases().isEmpty());

    fixture.receive(book("BTC", 2L, "101.000", "2"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(Arrays.asList("BTC"), fixture.sink.addedAliases());
    assertEquals(
        Arrays.asList("instrument-added:BTC", "depth:BTC:1010000:200"), fixture.sink.events());
  }

  @Test
  public void invalidTradeDoesNotSuppressLaterValidTradeInTheSameFrame() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();

    fixture.receive(
        trades(
            trade("BTC", "B", "100.000", "0", 1L, 1L), trade("BTC", "A", "101.000", "1", 2L, 2L)));
    fixture.drain();

    assertEquals(1, fixture.sink.trades().size());
    assertFalse(fixture.sink.trades().get(0).isBuyAggressor());
    assertEquals(1010000d, fixture.sink.trades().get(0).price(), 0d);
  }

  @Test
  public void activationDeadlineNeverResetsAndReleasesBothSlots() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();

    fixture.advanceBy(9_000L);
    fixture.completeSubscriptionSends();
    fixture.transport.clearSuccessfulSendBodies();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.advanceBy(1_000L);
    fixture.drain();
    fixture.completeSubscriptionSends();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
    assertEquals(
        Arrays.asList(
            new SubscriptionKey("BTC", SubscriptionType.L2_BOOK).unsubscribeJson(),
            new SubscriptionKey("BTC", SubscriptionType.TRADES).unsubscribeJson()),
        fixture.transport.socket().successfulSendBodies());
  }

  @Test
  public void activationAtExactDeadlineIsRejectedAndReleasesBothSlots() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();

    fixture.clock.now = 10_000L;
    fixture.receive(book("BTC", 1L, "100.000", "1"));
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertTrue(fixture.sink.addedAliases().isEmpty());
    assertEquals(2, fixture.budget.reservedSubscriptionSlots());
    fixture.scheduler.advanceBy(0L);
    fixture.drain();
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  @Test
  public void frameFromDisconnectedGenerationCannotDeliverMarketData() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.session.onDisconnected(
        fixture.generation,
        new TransportFailure(TransportFailure.Kind.NETWORK, "connection lost", null));
    fixture.drain();

    fixture.receive(trades(trade("BTC", "A", "101.000", "1", 2L, 2L)));
    fixture.drain();

    assertEquals(0, fixture.sink.trades().size());
  }

  @Test
  public void pendingTradesStayFifoAndCommitOnlyWhenActivationPublishes() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.sink.events().clear();
    fixture.session.subscribe("BTC", "", "PERPETUAL");
    fixture.drain();
    fixture.completeSubscriptionSends();

    String first = trade("BTC", "B", "100.000", "1", 1L, 1L);
    String second = trade("BTC", "A", "101.000", "1", 2L, 2L);
    fixture.receive(trades(first, first, second));
    fixture.drain();
    assertTrue(fixture.sink.trades().isEmpty());

    fixture.receive(book("BTC", 3L, "102.000", "1"));
    fixture.drain();
    fixture.receive(ack(SubscriptionType.L2_BOOK));
    fixture.receive(ack(SubscriptionType.TRADES));
    fixture.drain();

    assertEquals(2, fixture.sink.trades().size());
    assertEquals(1_000_000d, fixture.sink.trades().get(0).price(), 0d);
    assertEquals(1_010_000d, fixture.sink.trades().get(1).price(), 0d);
    assertFalse(fixture.sink.trades().get(1).isBuyAggressor());
  }

  @Test
  public void unsupportedActiveDepthClearsBookAndRemovesOnlyAlias() {
    Fixture fixture = new Fixture();
    fixture.activateBtc();
    fixture.sink.events().clear();
    fixture.receive(book("BTC", 2L, "999999999999.000", "1"));
    fixture.drain();

    assertEquals(
        Arrays.asList("depth:BTC:1000000:0", "instrument-removed:BTC"), fixture.sink.events());
    assertEquals(1, fixture.sink.systemMessages().size());
    assertEquals(0, fixture.budget.reservedSubscriptionSlots());
  }

  private static String ack(SubscriptionType type) {
    String prefix =
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"";
    return prefix + type.wireName() + "\",\"coin\":\"BTC\"}}}";
  }

  private static String book(String coin, long time, String price, String size) {
    return "{\"channel\":\"l2Book\",\"data\":{\"coin\":\""
        + coin
        + "\",\"time\":"
        + time
        + ",\"levels\":[[{\"px\":\""
        + price
        + "\",\"sz\":\""
        + size
        + "\"}],[]]}}";
  }

  private static String trades(String... trades) {
    return "{\"channel\":\"trades\",\"data\":[" + String.join(",", trades) + "]}";
  }

  private static String trade(
      String coin, String side, String price, String size, long time, long tid) {
    return "{\"coin\":\""
        + coin
        + "\",\"side\":\""
        + side
        + "\",\"px\":\""
        + price
        + "\",\"sz\":\""
        + size
        + "\",\"time\":"
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

  private static void noop() {
    // The fixture does not need overflow handling.
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionSubscriptionTest::noop);
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser(),
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
            HyperliquidSessionSubscriptionTest::noop);
    private long generation = 1L;

    void login() {
      session.login(HyperliquidEnvironment.MAINNET);
      drain();
      transport.completeMeta(200, "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]}");
      drain();
      transport.openSocket();
      drain();
    }

    void activateBtc() {
      login();
      session.subscribe("BTC", "", "PERPETUAL");
      drain();
      completeSubscriptionSends();
      receive(book("BTC", 1L, "100.000", "1"));
      receive(ack(SubscriptionType.L2_BOOK));
      receive(ack(SubscriptionType.TRADES));
      drain();
    }

    void receive(String frame) {
      connectorListenerFrame(generation, frame);
    }

    void connectorListenerFrame(long callbackGeneration, String frame) {
      session.onFrame(callbackGeneration, frame);
    }

    void completeSubscriptionSends() {
      transport.socket().succeedNextSend();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    void advanceBy(long elapsed) {
      clock.now += elapsed;
      scheduler.advanceBy(elapsed);
      drain();
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
