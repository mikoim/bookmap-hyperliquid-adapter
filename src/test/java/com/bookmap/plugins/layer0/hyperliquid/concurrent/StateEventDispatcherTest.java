package com.bookmap.plugins.layer0.hyperliquid.concurrent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Tests serialized control work, atomically bounded market work, and deterministic scheduling. */
public class StateEventDispatcherTest {

  @Test
  public void admitsOrRejectsAWholeFrameByEventCount() {
    ManualExecutor executor = new ManualExecutor();
    List<String> calls = new ArrayList<String>();
    AtomicInteger overflowCalls = new AtomicInteger();
    StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 3, () -> overflowCalls.incrementAndGet());

    assertTrue(dispatcher.submitMarketFrame(2, () -> calls.add("frame-a")));
    assertFalse(dispatcher.submitMarketFrame(2, () -> calls.add("frame-b")));

    executor.drain();

    assertEquals(Collections.singletonList("frame-a"), calls);
    assertEquals(1, overflowCalls.get());
  }

  @Test
  public void controlRunsBeforeQueuedMarketAndIsNeverRejected() {
    ManualExecutor executor = new ManualExecutor();
    List<String> calls = new ArrayList<String>();
    StateEventDispatcher dispatcher = new StateEventDispatcher(executor, 1, () -> calls.size());

    assertTrue(dispatcher.submitMarketFrame(1, () -> calls.add("market")));
    dispatcher.submitControl(() -> calls.add("control"));

    executor.drain();

    assertEquals(Arrays.asList("control", "market"), calls);
  }

  @Test
  public void admitsExactlyTheConfiguredMarketCapacity() {
    ManualExecutor executor = new ManualExecutor();
    AtomicInteger calls = new AtomicInteger();
    StateEventDispatcher dispatcher = new StateEventDispatcher(executor, 4_096, () -> calls.get());

    assertTrue(dispatcher.submitMarketFrame(4_096, () -> calls.incrementAndGet()));
    assertFalse(dispatcher.submitMarketFrame(1, () -> calls.incrementAndGet()));

    executor.drain();

    assertEquals(1, calls.get());
  }

  @Test
  public void rejectsOneFrameLargerThanCapacityWithoutConsumingCapacity() {
    ManualExecutor executor = new ManualExecutor();
    List<String> calls = new ArrayList<String>();
    StateEventDispatcher dispatcher = new StateEventDispatcher(executor, 3, () -> calls.size());

    assertFalse(dispatcher.submitMarketFrame(4, () -> calls.add("oversized")));
    assertTrue(dispatcher.submitMarketFrame(3, () -> calls.add("fits")));

    executor.drain();

    assertEquals(Collections.singletonList("fits"), calls);
  }

  @Test
  public void queuesOneOverflowCallbackUntilTheSignalIsReset() {
    ManualExecutor executor = new ManualExecutor();
    AtomicInteger overflowCalls = new AtomicInteger();
    StateEventDispatcher dispatcher =
        new StateEventDispatcher(executor, 1, () -> overflowCalls.incrementAndGet());

    assertFalse(dispatcher.submitMarketFrame(2, () -> overflowCalls.get()));
    assertFalse(dispatcher.submitMarketFrame(2, () -> overflowCalls.get()));
    assertEquals(0, overflowCalls.get());
    executor.drain();
    assertEquals(1, overflowCalls.get());

    dispatcher.resetOverflowSignal();
    assertFalse(dispatcher.submitMarketFrame(2, () -> overflowCalls.get()));
    executor.drain();

    assertEquals(2, overflowCalls.get());
  }

  @Test
  public void discardMarketFramesRetainsControlsAndReleasesTheirCapacity() {
    ManualExecutor executor = new ManualExecutor();
    List<String> calls = new ArrayList<String>();
    StateEventDispatcher dispatcher = new StateEventDispatcher(executor, 2, () -> calls.size());

    assertTrue(dispatcher.submitMarketFrame(2, () -> calls.add("discarded")));
    dispatcher.submitControl(() -> calls.add("control"));
    dispatcher.discardMarketFrames();
    assertTrue(dispatcher.submitMarketFrame(2, () -> calls.add("replacement")));

    executor.drain();

    assertEquals(Arrays.asList("control", "replacement"), calls);
  }

  @Test
  public void closeIsIdempotentAndClearsQueuedWork() {
    ManualExecutor executor = new ManualExecutor();
    List<String> calls = new ArrayList<String>();
    StateEventDispatcher dispatcher = new StateEventDispatcher(executor, 1, () -> calls.size());

    dispatcher.submitControl(() -> calls.add("control"));
    assertTrue(dispatcher.submitMarketFrame(1, () -> calls.add("market")));
    dispatcher.close();
    dispatcher.close();
    dispatcher.submitControl(() -> calls.add("late-control"));
    assertFalse(dispatcher.submitMarketFrame(1, () -> calls.add("late-market")));

    executor.drain();

    assertTrue(calls.isEmpty());
  }

  @Test
  public void manualSchedulerRunsDueTasksWithoutRunningCancelledTasks() {
    ManualScheduler scheduler = new ManualScheduler();
    List<String> calls = new ArrayList<String>();
    CancellableScheduler.Cancellable cancelled =
        scheduler.schedule(() -> calls.add("cancelled"), 10);
    scheduler.schedule(() -> calls.add("due"), 10);

    cancelled.cancel();
    cancelled.cancel();
    assertEquals(10L, scheduler.nextDelayMillis());
    scheduler.advanceBy(9);
    assertTrue(calls.isEmpty());
    scheduler.advanceBy(1);

    assertEquals(Collections.singletonList("due"), calls);
  }

  @Test
  public void executorSchedulerCancelsOutstandingHandlesAndOwnsItsExecutor() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
    ExecutorScheduler scheduler = new ExecutorScheduler(executor);
    AtomicInteger calls = new AtomicInteger();

    CancellableScheduler.Cancellable handle =
        scheduler.schedule(() -> calls.incrementAndGet(), 60_000);
    handle.cancel();
    handle.cancel();
    scheduler.close();

    assertTrue(executor.isShutdown());
    assertEquals(0, calls.get());
  }

  private static final class ManualExecutor implements java.util.concurrent.Executor {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<Runnable>();

    @Override
    public void execute(Runnable command) {
      tasks.addLast(command);
    }

    void drain() {
      while (!tasks.isEmpty()) {
        tasks.removeFirst().run();
      }
    }
  }
}
