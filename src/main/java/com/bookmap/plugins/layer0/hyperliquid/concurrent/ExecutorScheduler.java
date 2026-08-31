package com.bookmap.plugins.layer0.hyperliquid.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A cancellable scheduler that owns a {@link ScheduledExecutorService}. */
public final class ExecutorScheduler implements CancellableScheduler, AutoCloseable {

  private final Object lock = new Object();
  private final ScheduledExecutorService executor;
  private final List<Handle> handles = new ArrayList<Handle>();
  private boolean closed;

  /** Creates a scheduler backed by and responsible for the supplied executor. */
  public ExecutorScheduler(ScheduledExecutorService executor) {
    if (executor == null) {
      throw new NullPointerException("executor");
    }
    this.executor = executor;
  }

  /** Schedules a task after a non-negative delay and returns its idempotent cancellation handle. */
  @Override
  public Cancellable schedule(Runnable task, long delayMillis) {
    if (task == null) {
      throw new NullPointerException("task");
    }
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("scheduler is closed");
      }
      Handle handle = new Handle(this);
      ScheduledFuture<?> future =
          executor.schedule(
              new ScheduledTask(task, handle), Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
      handle.setFuture(future);
      handles.add(handle);
      return handle;
    }
  }

  /** Cancels outstanding tasks and immediately shuts down the owned executor. */
  @Override
  public void close() {
    List<Handle> outstanding;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      outstanding = new ArrayList<Handle>(handles);
      handles.clear();
    }
    for (Handle handle : outstanding) {
      handle.cancel();
    }
    executor.shutdownNow();
  }

  private void removeHandle(Handle handle) {
    synchronized (lock) {
      handles.remove(handle);
    }
  }

  private static final class ScheduledTask implements Runnable {

    private final Runnable task;
    private final Handle handle;

    private ScheduledTask(Runnable task, Handle handle) {
      this.task = task;
      this.handle = handle;
    }

    @Override
    public void run() {
      try {
        task.run();
      } finally {
        handle.complete();
      }
    }
  }

  private static final class Handle implements Cancellable {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ExecutorScheduler scheduler;
    private volatile ScheduledFuture<?> future;

    private Handle(ExecutorScheduler scheduler) {
      this.scheduler = scheduler;
    }

    private void setFuture(ScheduledFuture<?> future) {
      this.future = future;
    }

    private void complete() {
      scheduler.removeHandle(this);
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        ScheduledFuture<?> scheduled = future;
        if (scheduled != null) {
          scheduled.cancel(false);
        }
        complete();
      }
    }
  }
}
