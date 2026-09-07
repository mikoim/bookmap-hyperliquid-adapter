package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/**
 * Exercises tick selection through the real session: parameters on the wire and bucketed output.
 */
public class HyperliquidSessionTickSizeTest {

  @Test
  public void hyperliquidCoarseTickRequestsMatchingSigFigsAndPublishesBucketTotals() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.01"));
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":4"));
    assertFalse(l2Book, l2Book.contains("nLevels"));
    assertFalse(l2Book, l2Book.contains("mantissa"));

    fixture.receive(
        book(
            "HYPE",
            1L,
            levels(level("87.784", "2.36"), level("87.783", "1.32")),
            levels(level("87.785", "161.16"), level("87.786", "24.1"))));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:8778:368", "depth:HYPE:8779:18526"),
        fixture.sink.events());
    assertEquals(0, new BigDecimal("0.01").compareTo(fixture.sink.addedTicks().get(0)));

    fixture.sink.events().clear();
    fixture.receive(trade("HYPE", "B", "87.784", "1.5", 2L, 9L));
    fixture.drain();
    assertEquals(Arrays.asList("trade:HYPE:8778.4:150"), fixture.sink.events());
  }

  @Test
  public void defaultTickIsTheFinestQuotedQuantumWhenNoTickIsGiven() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribeDefault("HYPE");
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertFalse(l2Book, l2Book.contains("nSigFigs"));

    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:87784:100"), fixture.sink.events());
    assertEquals(0, new BigDecimal("0.001").compareTo(fixture.sink.addedTicks().get(0)));
  }

  @Test
  public void unsupportedTickFallsBackToTheDefaultWithADiagnostic() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERLIQUID, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.00005"));
    fixture.completeSends(2);
    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();

    assertTrue(fixture.sink.events().get(0).startsWith("diagnostic:"));
    assertEquals(0, new BigDecimal("0.001").compareTo(fixture.sink.addedTicks().get(0)));
  }

  @Test
  public void borsaAlwaysRequestsFourHundredLevelsAndAggregatesDeltasPerBucket() {
    Fixture fixture = new Fixture(MarketDataSource.BORSA, "87.785");
    fixture.subscribe("HYPE", new BigDecimal("0.002"));
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":5"));
    assertTrue(l2Book, l2Book.contains("\"nLevels\":400"));
    assertTrue(l2Book, l2Book.contains("\"mantissa\":2"));

    fixture.receive(book("HYPE", 1L, levels(level("87.784", "1"), level("87.785", "1")), levels()));
    fixture.receive(ack(SubscriptionType.L2_BOOK, "HYPE"));
    fixture.receive(ack(SubscriptionType.TRADES, "HYPE"));
    fixture.drain();
    assertEquals(
        Arrays.asList("instrument-added:HYPE", "depth:HYPE:43892:200"), fixture.sink.events());

    fixture.sink.events().clear();
    fixture.receive(book("HYPE", 2L, levels(level("87.785", "0")), levels()));
    fixture.drain();
    assertEquals(Arrays.asList("depth:HYPE:43892:100"), fixture.sink.events());
  }

  @Test
  public void hyperdashNoLongerForcesFiveSignificantFigures() {
    Fixture fixture = new Fixture(MarketDataSource.HYPERDASH, null);
    fixture.subscribeDefault("HYPE");
    fixture.completeSends(2);

    String l2Book = fixture.sentBody("l2Book");
    assertFalse(l2Book, l2Book.contains("nSigFigs"));
    assertFalse(l2Book, l2Book.contains("nLevels"));
  }

  private static String ack(SubscriptionType type, String coin) {
    return "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
        + "\"subscription\":{\"type\":\""
        + type.wireName()
        + "\",\"coin\":\""
        + coin
        + "\"}}}";
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

  private static String trade(
      String coin, String side, String price, String size, long time, long tid) {
    return "{\"channel\":\"trades\",\"data\":[{\"coin\":\""
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
        + "}]}";
  }

  private static String levels(String... levels) {
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
    private final MarketDataSource source;
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionTickSizeTest::noop);
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
            new AssetContextConnectorFactory() {
              @Override
              public HyperliquidConnector create() {
                return new HyperliquidConnector(
                    transport,
                    new HyperliquidMetaParser(),
                    budget,
                    scheduler,
                    clock,
                    dispatcher::submitControl,
                    false);
              }
            },
            HyperliquidSessionTickSizeTest::noop);

    /**
     * Logs in with one HYPE instrument (szDecimals 2) whose markPx is the given string or absent.
     */
    Fixture(MarketDataSource source, String markPx) {
      this.source = source;
      session.login(SourceProfile.of(source, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.allPerpMetas(TestMetadata.universe("HYPE")));
      drain();
      transport.openConnection(0);
      drain();
      if (source == MarketDataSource.HYPERLIQUID) {
        transport.socket().succeedNextSend();
        drain();
      }
      if (markPx != null) {
        session.onFrame(
            1L, TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"" + markPx + "\"}}"));
        drain();
      }
      sink.events().clear();
    }

    void subscribe(String symbol, BigDecimal tick) {
      session.subscribe(symbol, "", "PERPETUAL", tick);
      drain();
    }

    void subscribeDefault(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
      drain();
    }

    String sentBody(String type) {
      List<String> bodies = transport.socket().successfulSendBodies();
      for (String body : bodies) {
        if (body.contains("\"type\":\"" + type + "\"")) {
          return body;
        }
      }
      throw new AssertionError("no " + type + " subscription sent: " + bodies);
    }

    void receive(String frame) {
      session.onFrame(1L, frame);
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
