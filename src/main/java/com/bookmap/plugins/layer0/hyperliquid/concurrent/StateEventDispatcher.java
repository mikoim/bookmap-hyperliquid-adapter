package com.bookmap.plugins.layer0.hyperliquid.concurrent;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/** Serializes state work while bounding complete market-data frames independently from controls. */
public final class StateEventDispatcher implements AutoCloseable {

  private final Object lock = new Object();
  private final Executor executor;
  private final int marketCapacity;
  private final Runnable onFirstOverflow;
  private final ArrayDeque<Runnable> controls = new ArrayDeque<Runnable>();
  private final ArrayDeque<MarketTask> market = new ArrayDeque<MarketTask>();
  private final Runnable drainTask =
      new Runnable() {
        @Override
        public void run() {
          drain();
        }
      };

  private int queuedMarketEvents;
  private boolean drainScheduled;
  private boolean overflowSignalled;
  private boolean closed;

  /** Creates a dispatcher with a bounded market lane and an unbounded priority control lane. */
  public StateEventDispatcher(Executor executor, int marketCapacity, Runnable onFirstOverflow) {
    if (executor == null) {
      throw new NullPointerException("executor");
    }
    if (onFirstOverflow == null) {
      throw new NullPointerException("onFirstOverflow");
    }
    if (marketCapacity <= 0) {
      throw new IllegalArgumentException("marketCapacity must be positive");
    }
    this.executor = executor;
    this.marketCapacity = marketCapacity;
    this.onFirstOverflow = onFirstOverflow;
  }

  /** Queues control work with priority over all queued market frames. */
  public void submitControl(Runnable task) {
    requireTask(task);
    boolean schedule;
    synchronized (lock) {
      if (closed) {
        return;
      }
      controls.addLast(task);
      schedule = markDrainScheduledLocked();
    }
    if (schedule) {
      executor.execute(drainTask);
    }
  }

  /**
   * Atomically queues a whole market frame when its event count fits the remaining market capacity.
   *
   * @return whether the complete frame was accepted
   */
  public boolean submitMarketFrame(int eventCount, Runnable wholeFrameTask) {
    if (eventCount <= 0) {
      throw new IllegalArgumentException("eventCount must be positive");
    }
    requireTask(wholeFrameTask);
    boolean accepted;
    boolean schedule = false;
    synchronized (lock) {
      if (closed) {
        return false;
      }
      if (eventCount > marketCapacity - queuedMarketEvents) {
        if (!overflowSignalled) {
          overflowSignalled = true;
          controls.addLast(onFirstOverflow);
          schedule = markDrainScheduledLocked();
        }
        accepted = false;
      } else {
        market.addLast(new MarketTask(eventCount, wholeFrameTask));
        queuedMarketEvents += eventCount;
        schedule = markDrainScheduledLocked();
        accepted = true;
      }
    }
    if (schedule) {
      executor.execute(drainTask);
    }
    return accepted;
  }

  /** Discards queued market frames while retaining queued control work. */
  public void discardMarketFrames() {
    synchronized (lock) {
      market.clear();
      queuedMarketEvents = 0;
    }
  }

  /** Allows a subsequent rejected market frame to enqueue one new overflow callback. */
  public void resetOverflowSignal() {
    synchronized (lock) {
      overflowSignalled = false;
    }
  }

  /** Marks the dispatcher closed and synchronously clears all queued work. */
  @Override
  public void close() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      controls.clear();
      market.clear();
      queuedMarketEvents = 0;
    }
  }

  private boolean markDrainScheduledLocked() {
    if (!drainScheduled) {
      drainScheduled = true;
      return true;
    }
    return false;
  }

  private void drain() {
    while (true) {
      Runnable next;
      synchronized (lock) {
        next = pollNextLocked();
      }
      if (next == null) {
        return;
      }
      next.run();
    }
  }

  private Runnable pollNextLocked() {
    Runnable control = controls.pollFirst();
    if (control != null) {
      return control;
    }
    MarketTask next = market.pollFirst();
    if (next != null) {
      queuedMarketEvents -= next.eventCount;
      return next.task;
    }
    drainScheduled = false;
    return null;
  }

  private static void requireTask(Runnable task) {
    if (task == null) {
      throw new NullPointerException("task");
    }
  }

  private static final class MarketTask {

    private final int eventCount;
    private final Runnable task;

    private MarketTask(int eventCount, Runnable task) {
      this.eventCount = eventCount;
      this.task = task;
    }
  }
}
