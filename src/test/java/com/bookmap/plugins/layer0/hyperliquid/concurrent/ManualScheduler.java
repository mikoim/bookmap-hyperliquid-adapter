package com.bookmap.plugins.layer0.hyperliquid.concurrent;

import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic test scheduler with a manually advanced millisecond clock. */
public final class ManualScheduler implements CancellableScheduler {

  private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<ScheduledTask>();
  private long nowMillis;
  private long sequence;

  @Override
  public CancellableScheduler.Cancellable schedule(Runnable task, long delayMillis) {
    ScheduledTask scheduled =
        new ScheduledTask(task, nowMillis + Math.max(0L, delayMillis), sequence++);
    tasks.add(scheduled);
    return scheduled;
  }

  /** Advances the clock and runs every task due at or before the resulting time. */
  public void advanceBy(long elapsedMillis) {
    if (elapsedMillis < 0L) {
      throw new IllegalArgumentException("elapsedMillis must be non-negative");
    }
    nowMillis += elapsedMillis;
    runDueTasks();
  }

  /** Returns the delay until the next non-cancelled task, or {@code -1} when none is scheduled. */
  public long nextDelayMillis() {
    discardCancelledHead();
    return tasks.isEmpty() ? -1L : tasks.peek().dueMillis - nowMillis;
  }

  private void runDueTasks() {
    while (!tasks.isEmpty() && tasks.peek().dueMillis <= nowMillis) {
      ScheduledTask task = tasks.remove();
      if (!task.cancelled.get()) {
        task.task.run();
      }
    }
  }

  private void discardCancelledHead() {
    while (!tasks.isEmpty() && tasks.peek().cancelled.get()) {
      tasks.remove();
    }
  }

  private static final class ScheduledTask
      implements CancellableScheduler.Cancellable, Comparable<ScheduledTask> {

    private final Runnable task;
    private final long dueMillis;
    private final long sequence;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private ScheduledTask(Runnable task, long dueMillis, long sequence) {
      this.task = task;
      this.dueMillis = dueMillis;
      this.sequence = sequence;
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public int compareTo(ScheduledTask other) {
      int dueComparison = Long.compare(dueMillis, other.dueMillis);
      return dueComparison != 0 ? dueComparison : Long.compare(sequence, other.sequence);
    }
  }
}
