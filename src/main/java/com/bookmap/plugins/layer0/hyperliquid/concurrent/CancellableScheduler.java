package com.bookmap.plugins.layer0.hyperliquid.concurrent;

/** Schedules delayed work that callers can cancel. */
public interface CancellableScheduler {

  /** Schedules a task after the supplied delay in milliseconds. */
  Cancellable schedule(Runnable task, long delayMillis);

  /** A cancellation handle for one scheduled task. */
  interface Cancellable {

    /** Cancels the task. Repeated calls have no further effect. */
    void cancel();
  }
}
