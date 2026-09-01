package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.junit.Test;

/** Focused lifecycle tests for session state transitions and reconnect barriers. */
public class HyperliquidSessionLifecycleTest {

  @Test
  public void invalidMetadataFailsFatallyWithoutOpeningWebSocket() {
    Fixture fixture = new Fixture();
    fixture.startLogin();
    fixture.transport.completeMeta(200, "{\"universe\":[{\"name\":\"BTC\"}]}");
    fixture.drain();

    assertEquals(1, count(fixture.sink.events(), "login-failed:FATAL"));
    assertTrue(fixture.transport.connectCalls().isEmpty());
    fixture.scheduler.advanceBy(120_000L);
    fixture.drain();
    assertTrue(fixture.transport.connectCalls().isEmpty());
  }

  @Test
  public void restorationWaitsForEveryCurrentGenerationAck() {
    Fixture fixture = new Fixture();
    fixture.login();
    fixture.subscribe("BTC");
    fixture.completeSends(2);
    fixture.book(1L);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    fixture.sink.events().clear();

    fixture.transport.remoteClose(1006, "lost");
    fixture.drain();
    fixture.scheduler.advanceBy(1_000L);
    fixture.drain();
    fixture.transport.openSocket();
    fixture.generation = 2L;
    fixture.drain();
    fixture.completeSends(1);
    fixture.ack(SubscriptionType.L2_BOOK);
    fixture.drain();
    assertEquals(0, count(fixture.sink.events(), "connection-restored"));
    fixture.completeSends(1);
    fixture.ack(SubscriptionType.TRADES);
    fixture.drain();
    assertEquals(1, count(fixture.sink.events(), "connection-restored"));
  }

  private static int count(List<String> values, String expected) {
    int count = 0;
    for (String value : values) {
      if (expected.equals(value)) {
        count++;
      }
    }
    return count;
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
    private final StateEventDispatcher dispatcher =
        new StateEventDispatcher(
            executor,
            4_096,
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });
    private final HyperliquidConnector connector =
        new HyperliquidConnector(
            transport,
            new com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser(),
            budget,
            scheduler,
            clock,
            dispatcher::submitControl);
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private long generation = 1L;
    private final HyperliquidSession session =
        new HyperliquidSession(
            connector,
            new HyperliquidMessageParser(),
            budget,
            scheduler,
            clock,
            dispatcher,
            sink,
            new Runnable() {
              @Override
              public void run() {
                // no-op
              }
            });

    private void login() {
      startLogin();
      transport.completeMeta(200, "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]}");
      drain();
      transport.openSocket();
      drain();
    }

    private void startLogin() {
      session.login(HyperliquidEnvironment.MAINNET);
      drain();
    }

    private void subscribe(String symbol) {
      session.subscribe(symbol, "", "PERPETUAL");
      drain();
    }

    private void completeSends(int count) {
      for (int index = 0; index < count; index++) {
        transport.socket().succeedNextSend();
        drain();
      }
    }

    private void book(long time) {
      session.onFrame(
          generation,
          "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":"
              + time
              + ",\"levels\":[[{\"px\":\"100.000\",\"sz\":\"1\"}],[]]}}");
    }

    private void ack(SubscriptionType type) {
      session.onFrame(
          generation,
          "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
              + "\"subscription\":{\"type\":\""
              + type.wireName()
              + "\",\"coin\":\"BTC\"}}}");
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
      clock.now += millis;
      while (true) {
        Task due = null;
        for (Task task : tasks) {
          if (!task.cancelled && task.due <= clock.now && (due == null || task.due < due.due)) {
            due = task;
          }
        }
        if (due == null) {
          return;
        }
        tasks.remove(due);
        due.task.run();
      }
    }

    private static final class Task implements Cancellable, Comparable<Task> {
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

      @Override
      public int compareTo(Task other) {
        return Long.compare(due, other.due);
      }
    }
  }
}
