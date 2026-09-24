package com.bookmap.plugins.layer0.hyperliquid;

/** Test handler for state-lane task failures: a failing task is a test failure. */
public final class TaskFailures {

  private TaskFailures() {
    // static utility
  }

  /** Fails the test that owns the dispatcher instead of swallowing the task failure. */
  public static void rethrow(Throwable failure) {
    throw new AssertionError("unexpected state task failure", failure);
  }
}
