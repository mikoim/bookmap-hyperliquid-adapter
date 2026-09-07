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
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Drives mark prices through the session the way the fastAssetCtxs feed does. */
public class HyperliquidSessionAssetContextTest {

  /** A snapshot refreshes the known-instrument list with reference prices attached. */
  @Test
  public void snapshotRepublishesKnownInstrumentsWithReferencePrices() {
    Fixture fixture = new Fixture();

    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    assertEquals(2, fixture.sink.knownInstrumentPublications());
    assertEquals("87.785", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** The refreshed reference price reaches the subscribe path, not just the sink. */
  @Test
  public void refreshedReferencePriceDrivesServerSideGrouping() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    fixture.session.subscribe("HYPE", "", "PERPETUAL", new java.math.BigDecimal("0.01"));
    fixture.drain();
    fixture.transport.socket().succeedNextSend();
    fixture.drain();

    String l2Book = fixture.sentBody("l2Book");
    assertTrue(l2Book, l2Book.contains("\"nSigFigs\":4"));
  }

  /** A delta touching no known instrument never rebuilds the list. */
  @Test
  public void unrelatedDeltaDoesNotRepublish() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    int publications = fixture.sink.knownInstrumentPublications();

    fixture.clock.now += 60_000L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"BTC\":{\"markPx\":\"79394.0\"}}"));

    assertEquals(publications, fixture.sink.knownInstrumentPublications());
  }

  /** A known instrument that moves republishes at most once every five seconds. */
  @Test
  public void knownInstrumentDeltaIsThrottledToFiveSeconds() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));
    int publications = fixture.sink.knownInstrumentPublications();

    fixture.clock.now += 4_999L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"88.0\"}}"));
    assertEquals(publications, fixture.sink.knownInstrumentPublications());

    fixture.clock.now += 1L;
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"88.5\"}}"));
    assertEquals(publications + 1, fixture.sink.knownInstrumentPublications());
    assertEquals("88.5", fixture.sink.lastReferencePrice("HYPE"));
  }

  /** A reopened generation treats its first frame as a snapshot again. */
  @Test
  public void reconnectRestartsSnapshotDetection() {
    Fixture fixture = new Fixture();
    fixture.receive(TestMetadata.assetContextsFrame("{\"HYPE\":{\"markPx\":\"87.785\"}}"));

    fixture.reconnect();
    fixture.receiveOnGeneration(
        2L, TestMetadata.assetContextsFrame("{\"OTHER\":{\"markPx\":\"1\"}}"));

    assertEquals(null, fixture.sink.lastReferencePrice("HYPE"));
  }

  /** A rejected feed never fails login and never removes the instrument list. */
  @Test
  public void rejectedFeedIsOnlyADiagnostic() {
    Fixture fixture = new Fixture();

    fixture.receive(
        "{\"channel\":\"error\",\"data\":\"Invalid subscription {\\\"type\\\":"
            + "\\\"fastAssetCtxs\\\"}\"}");

    assertFalse(hasEvent(fixture.sink.events(), "login-failed"));
    assertEquals(1, fixture.sink.knownInstrumentPublications());
  }

  /** RecordingSessionSink appends the failure reason, so events are matched by prefix. */
  private static boolean hasEvent(List<String> events, String prefix) {
    for (String event : events) {
      if (event.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static final class Fixture {
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock();
    private final TestScheduler scheduler = new TestScheduler(clock);
    private final HyperliquidProcessBudget budget = budget();
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 4_096, HyperliquidSessionAssetContextTest::noop);
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
                throw new AssertionError("this fixture uses the Hyperliquid source only");
              }
            },
            HyperliquidSessionAssetContextTest::noop);

    Fixture() {
      session.login(SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
      drain();
      transport.completeMeta(200, TestMetadata.wrap(TestMetadata.universe("HYPE")));
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
      drain();
    }

    void receive(String frame) {
      receiveOnGeneration(1L, frame);
    }

    void receiveOnGeneration(long generation, String frame) {
      session.onFrame(generation, frame);
      drain();
    }

    void reconnect() {
      transport.remoteClose(1006, "lost");
      drain();
      clock.now += 1_000L;
      scheduler.advanceBy(1_000L);
      drain();
      transport.openSocket();
      drain();
      transport.socket().succeedNextSend();
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

    void drain() {
      executor.drain();
    }
  }

  private static void noop() {
    // Intentionally empty.
  }

  private static HyperliquidProcessBudget budget() {
    try {
      Constructor<HyperliquidProcessBudget> constructor =
          HyperliquidProcessBudget.class.getDeclaredConstructor(
              int.class, int.class, int.class, int.class, long.class);
      constructor.setAccessible(true);
      return constructor.newInstance(10, 30, 2_000, 1_000, 60_000L);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
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
    private final PriorityQueue<Task> tasks = new PriorityQueue<Task>();
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

    void advanceBy(long elapsedMillis) {
      long deadline = clock.now + elapsedMillis;
      while (!tasks.isEmpty() && tasks.peek().due <= deadline) {
        Task due = tasks.poll();
        if (!due.cancelled) {
          due.task.run();
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
