package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Tracks data-health transitions on the session state lane; never changes market data. */
final class DataHealthMonitor implements AutoCloseable {
  private static final long STALE_MILLIS = 30_000L;

  private final CancellableScheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<Runnable> stateLane;
  private final SessionSink sink;
  private final Map<String, Health> symbols = new HashMap<String, Health>();
  private SourceProfile profile;
  private boolean closed;
  private Long markUnavailableSince;

  DataHealthMonitor(
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateLane,
      SessionSink sink) {
    this.scheduler = scheduler;
    this.clock = clock;
    this.stateLane = stateLane;
    this.sink = sink;
  }

  void start(SourceProfile sourceProfile) {
    profile = sourceProfile;
  }

  void bookPublished(String symbol, long generation) {
    if (closed) {
      return;
    }
    Health health = health(symbol);
    long now = clock.getAsLong();
    if (health.stale || health.resyncSince != null) {
      emit(
          symbol,
          generation,
          "BOOK_RESUMED",
          false,
          "lastBookReceivedAt="
              + timestamp(health.lastBook)
              + " elapsedMillis="
              + elapsed(now, health.lastBook)
              + " resyncStartedAt="
              + timestamp(health.resyncSince));
    }
    health.stale = false;
    health.resyncSince = null;
    health.lastBook = now;
    health.generation = generation;
    if (health.timer == null) {
      scheduleCheck(symbol, health, STALE_MILLIS);
    }
  }

  /**
   * Reports one incident that invalidates the books of several symbols at once. A reconnect touches
   * every subscription, so each state is reported as a single message naming the affected symbols
   * rather than two messages per symbol. Per-symbol timings stay in the recovery messages.
   */
  void resync(Collection<String> resyncSymbols, long generation, String reason) {
    if (closed) {
      return;
    }
    long now = clock.getAsLong();
    List<String> resyncing = new ArrayList<String>();
    List<String> gapped = new ArrayList<String>();
    for (String symbol : resyncSymbols) {
      Health health = health(symbol);
      if (health.resyncSince == null) {
        health.resyncSince = now;
        cancelTimer(health);
        resyncing.add(symbol);
      }
      if (beginGap(health, now)) {
        gapped.add(symbol);
      }
    }
    if (!resyncing.isEmpty()) {
      emit(
          String.join(",", resyncing),
          generation,
          "BOOK_RESYNCING",
          true,
          "detectedAt=" + timestamp(now) + " reason=" + reason);
    }
    if (!gapped.isEmpty()) {
      emit(
          String.join(",", gapped),
          generation,
          "TRADE_GAP_POSSIBLE",
          true,
          "detectedAt="
              + timestamp(now)
              + " reason="
              + reason
              + "; historical trades are not backfilled");
    }
  }

  void tradeGap(String symbol, long generation, String reason) {
    if (closed) {
      return;
    }
    Health health = health(symbol);
    long now = clock.getAsLong();
    if (beginGap(health, now)) {
      emit(
          symbol,
          generation,
          "TRADE_GAP_POSSIBLE",
          true,
          "possibleGapFrom="
              + timestamp(health.gapSince)
              + " detectedAt="
              + timestamp(now)
              + " reason="
              + reason
              + "; historical trades are not backfilled");
    }
  }

  /** Opens a possible-gap interval, conservatively starting at the last published trade. */
  private boolean beginGap(Health health, long now) {
    if (health.gapSince != null) {
      return false;
    }
    health.gapSince = health.lastTrade == null ? Long.valueOf(now) : health.lastTrade;
    return true;
  }

  void tradePublished(String symbol, long generation) {
    if (closed) {
      return;
    }
    Health health = health(symbol);
    long now = clock.getAsLong();
    if (health.gapSince != null) {
      emit(
          symbol,
          generation,
          "TRADE_RESUMED",
          false,
          "possibleGapFrom="
              + timestamp(health.gapSince)
              + " possibleGapTo="
              + timestamp(now)
              + "; historical trades are not backfilled");
      health.gapSince = null;
    }
    health.lastTrade = now;
  }

  void markUnavailable(long generation, String reason) {
    if (!closed && markUnavailableSince == null) {
      markUnavailableSince = clock.getAsLong();
      emit(
          "*",
          generation,
          "MARK_PRICE_UNAVAILABLE",
          true,
          "feedSource=HYPERLIQUID reason=" + reason + "; tick candidates use last known prices");
    }
  }

  void markResumed(long generation) {
    if (!closed && markUnavailableSince != null) {
      emit(
          "*",
          generation,
          "MARK_PRICE_RESUMED",
          false,
          "feedSource=HYPERLIQUID unavailableSince="
              + timestamp(markUnavailableSince)
              + " elapsedMillis="
              + elapsed(clock.getAsLong(), markUnavailableSince));
      markUnavailableSince = null;
    }
  }

  void remove(String symbol) {
    Health health = symbols.remove(symbol);
    if (health != null) {
      cancelTimer(health);
    }
  }

  @Override
  public void close() {
    closed = true;
    for (Health health : symbols.values()) {
      cancelTimer(health);
    }
    symbols.clear();
  }

  private Health health(String symbol) {
    Health health = symbols.get(symbol);
    if (health == null) {
      health = new Health();
      symbols.put(symbol, health);
    }
    return health;
  }

  private void scheduleCheck(String symbol, Health health, long delay) {
    long checkId = ++health.checkId;
    health.timer =
        scheduler.schedule(() -> stateLane.accept(() -> check(symbol, health, checkId)), delay);
  }

  private void check(String symbol, Health health, long checkId) {
    if (closed
        || symbols.get(symbol) != health
        || health.resyncSince != null
        || health.checkId != checkId) {
      return;
    }
    health.timer = null;
    long age = elapsed(clock.getAsLong(), health.lastBook);
    if (age >= STALE_MILLIS) {
      if (!health.stale) {
        health.stale = true;
        emit(
            symbol,
            health.generation,
            "BOOK_STALE",
            true,
            "lastBookReceivedAt="
                + timestamp(health.lastBook)
                + " elapsedMillis="
                + age
                + "; no valid book received for 30 seconds;"
                + " inactivity alone does not prove a feed failure");
      }
    } else {
      scheduleCheck(symbol, health, STALE_MILLIS - age);
    }
  }

  private void cancelTimer(Health health) {
    health.checkId++;
    if (health.timer != null) {
      health.timer.cancel();
      health.timer = null;
    }
  }

  private void emit(String symbol, long generation, String state, boolean warning, String detail) {
    if (profile == null) {
      throw new IllegalStateException("data health monitor must be started before reporting");
    }
    sink.onDataStatus(
        "data-status source="
            + profile.source()
            + " environment="
            + profile.environment()
            + " symbol="
            + symbol
            + " generation="
            + generation
            + " at="
            + timestamp(clock.getAsLong())
            + " state="
            + state
            + " "
            + detail,
        warning);
  }

  private static long elapsed(long now, Long then) {
    return then == null ? 0L : Math.max(0L, now - then);
  }

  private static String timestamp(Long millis) {
    return millis == null ? "unknown" : Instant.ofEpochMilli(millis).toString();
  }

  private static final class Health {
    private Long lastBook;
    private Long lastTrade;
    private Long resyncSince;
    private Long gapSince;
    private long generation;
    private long checkId;
    private boolean stale;
    private CancellableScheduler.Cancellable timer;
  }
}
