package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Decides when the instrument list is fetched again: thirty minutes after the previous fetch
 * finished, and on demand after a reconnect. At most one fetch is in flight. Every method runs on
 * the state lane; the listener is called on it too.
 */
final class MetadataRefresher {

  static final long REFRESH_INTERVAL_MILLIS = 1_800_000L;

  /** Keeps a reconnect storm from turning into a REST storm. */
  static final long MIN_REFRESH_GAP_MILLIS = 60_000L;

  /** Receives the outcome of each refresh. */
  interface Listener {

    /** Reports a complete, validated replacement list. */
    void onMetadataRefreshed(List<Instrument> instruments);

    /** Reports a refresh that produced no list; the previous list stays in force. */
    void onMetadataRefreshFailed(TransportFailure failure);
  }

  /** Builds the single-use request for one refresh. */
  interface RequestFactory {

    /** Creates an unstarted request that reports to the callback. */
    MetadataRequest create(MetadataRequest.Callback callback);
  }

  private final RequestFactory requests;
  private final CancellableScheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<Runnable> stateSubmitter;
  private final Listener listener;

  private CancellableScheduler.Cancellable timer;
  private MetadataRequest inFlight;
  private long lastStartedAtMillis;
  private boolean refreshedOnce;
  private boolean started;
  private boolean closed;

  MetadataRefresher(
      RequestFactory requests,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter,
      Listener listener) {
    if (requests == null
        || scheduler == null
        || clock == null
        || stateSubmitter == null
        || listener == null) {
      throw new IllegalArgumentException("refresher dependencies must not be null");
    }
    this.requests = requests;
    this.scheduler = scheduler;
    this.clock = clock;
    this.stateSubmitter = stateSubmitter;
    this.listener = listener;
  }

  /** Arms the first timer. Login has just fetched the list, so nothing is fetched here. */
  void start() {
    if (started || closed) {
      return;
    }
    started = true;
    scheduleNext();
  }

  /** Fetches now unless a fetch is in flight or one started less than a minute ago. */
  void refreshNow() {
    if (!started || closed || inFlight != null) {
      return;
    }
    if (refreshedOnce && clock.getAsLong() - lastStartedAtMillis < MIN_REFRESH_GAP_MILLIS) {
      return;
    }
    refresh();
  }

  /** Cancels the timer and any fetch in flight; the listener is not called afterwards. */
  void close() {
    closed = true;
    cancelTimer();
    if (inFlight != null) {
      inFlight.cancel();
      inFlight = null;
    }
  }

  private void refresh() {
    cancelTimer();
    lastStartedAtMillis = clock.getAsLong();
    refreshedOnce = true;
    // Set before start(): a transport that throws reports its failure synchronously.
    inFlight =
        requests.create(
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                finished();
                listener.onMetadataRefreshed(instruments);
              }

              @Override
              public void onFailure(TransportFailure failure) {
                finished();
                listener.onMetadataRefreshFailed(failure);
              }
            });
    inFlight.start();
  }

  /**
   * A cancelled request never calls back, so reaching here means the refresher is still open. The
   * next timer is armed here, before the listener is called, so that a listener which closes the
   * refresher still leaves no timer behind: {@link #close()} cancels it.
   */
  private void finished() {
    inFlight = null;
    scheduleNext();
  }

  private void scheduleNext() {
    cancelTimer();
    timer =
        scheduler.schedule(
            new Runnable() {
              @Override
              public void run() {
                stateSubmitter.accept(
                    new Runnable() {
                      @Override
                      public void run() {
                        timer = null;
                        if (!closed && inFlight == null) {
                          refresh();
                        }
                      }
                    });
              }
            },
            REFRESH_INTERVAL_MILLIS);
  }

  private void cancelTimer() {
    if (timer != null) {
      timer.cancel();
      timer = null;
    }
  }
}
