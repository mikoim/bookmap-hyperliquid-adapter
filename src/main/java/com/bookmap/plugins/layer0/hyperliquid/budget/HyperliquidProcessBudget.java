package com.bookmap.plugins.layer0.hyperliquid.budget;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Thread-safe, process-wide reservations for Hyperliquid connection, send, and subscription limits.
 */
public final class HyperliquidProcessBudget {

  private final Object lock = new Object();
  private final int maxConnections;
  private final int maxConnectionAttempts;
  private final int maxFrames;
  private final int maxSubscriptions;
  private final long windowMillis;
  private final Deque<Long> connectionAttempts = new ArrayDeque<Long>();
  private final Deque<Long> sentFrameTimestamps = new ArrayDeque<Long>();

  private int openConnections;
  private int heldFrames;
  private int reservedSubscriptions;

  HyperliquidProcessBudget(
      int connections, int attempts, int frames, int subscriptions, long windowMillis) {
    if (connections <= 0 || attempts <= 0 || frames <= 0 || subscriptions <= 0) {
      throw new IllegalArgumentException("budget limits must be positive");
    }
    if (windowMillis <= 0L) {
      throw new IllegalArgumentException("windowMillis must be positive");
    }
    this.maxConnections = connections;
    this.maxConnectionAttempts = attempts;
    this.maxFrames = frames;
    this.maxSubscriptions = subscriptions;
    this.windowMillis = windowMillis;
  }

  /** Returns the singleton budget shared by all providers in this JVM. */
  public static HyperliquidProcessBudget shared() {
    return SharedBudget.INSTANCE;
  }

  /**
   * Atomically reserves one concurrent connection, one rolling connection attempt, and outbound
   * frame capacity for a connection attempt.
   *
   * @param nowMillis caller-supplied current time in milliseconds
   * @param reservedFrames number of outbound frames to hold until they are sent or released
   * @return an acquired connection permit or a nonblocking retry decision
   */
  public Decision<ConnectionPermit> tryAcquireConnection(long nowMillis, int reservedFrames) {
    synchronized (lock) {
      requireNonNegative(reservedFrames, "reservedFrames");
      purge(nowMillis);
      if (openConnections >= maxConnections) {
        return Decision.retryAt(nowMillis);
      }
      long retryAt = earliestRetryForConnection(nowMillis, reservedFrames);
      if (retryAt > nowMillis || !hasFrameCapacity(reservedFrames)) {
        return Decision.retryAt(retryAt);
      }
      if (reservedFrames > maxFrames) {
        return Decision.retryAt(nowMillis);
      }
      openConnections++;
      connectionAttempts.addLast(Long.valueOf(nowMillis));
      heldFrames += reservedFrames;
      return Decision.acquired(new ConnectionPermit(this, reservedFrames), nowMillis);
    }
  }

  /**
   * Reserves outbound frame capacity without sending a frame or starting its rolling-window clock.
   *
   * @param nowMillis caller-supplied current time in milliseconds
   * @param frames number of outbound frames to reserve
   * @return an acquired frame reservation or a nonblocking retry decision
   */
  public Decision<FrameReservation> tryAcquireFrames(long nowMillis, int frames) {
    synchronized (lock) {
      requireNonNegative(frames, "frames");
      purge(nowMillis);
      if (frames > maxFrames) {
        return Decision.retryAt(nowMillis);
      }
      long retryAt = earliestFrameRetryAt(nowMillis, frames);
      if (retryAt > nowMillis || !hasFrameCapacity(frames)) {
        return Decision.retryAt(retryAt);
      }
      heldFrames += frames;
      return Decision.acquired(new FrameReservation(this, frames), nowMillis);
    }
  }

  /**
   * Reserves the two subscription slots required for one perpetual alias's book and trades feeds.
   *
   * @param nowMillis caller-supplied current time in milliseconds
   * @return an acquired two-slot subscription permit or a nonblocking retry decision
   */
  public Decision<SubscriptionPermit> tryReserveSubscriptionPair(long nowMillis) {
    synchronized (lock) {
      if (maxSubscriptions - reservedSubscriptions < 2) {
        return Decision.retryAt(nowMillis);
      }
      reservedSubscriptions += 2;
      return Decision.acquired(new SubscriptionPermit(this), nowMillis);
    }
  }

  private long earliestRetryForConnection(long nowMillis, int reservedFrames) {
    synchronized (lock) {
      if (reservedFrames > maxFrames) {
        return nowMillis;
      }
      long connectionRetry =
          earliestDequeRetryAt(connectionAttempts, maxConnectionAttempts, 1, nowMillis);
      long frameRetry = earliestFrameRetryAt(nowMillis, reservedFrames);
      return Math.max(connectionRetry, frameRetry);
    }
  }

  private long earliestFrameRetryAt(long nowMillis, int frames) {
    synchronized (lock) {
      return earliestDequeRetryAt(sentFrameTimestamps, maxFrames - heldFrames, frames, nowMillis);
    }
  }

  private boolean hasFrameCapacity(int frames) {
    synchronized (lock) {
      return sentFrameTimestamps.size() + heldFrames + frames <= maxFrames;
    }
  }

  private long earliestDequeRetryAt(
      Deque<Long> timestamps, int capacity, int requested, long nowMillis) {
    int entriesToExpire = timestamps.size() + requested - capacity;
    if (entriesToExpire <= 0) {
      return nowMillis;
    }
    if (entriesToExpire > timestamps.size()) {
      return nowMillis;
    }
    int index = 1;
    for (Long timestamp : timestamps) {
      if (index == entriesToExpire) {
        return timestamp.longValue() + windowMillis;
      }
      index++;
    }
    return nowMillis;
  }

  private void purge(long nowMillis) {
    synchronized (lock) {
      purgeExpired(connectionAttempts, nowMillis);
      purgeExpired(sentFrameTimestamps, nowMillis);
    }
  }

  private void purgeExpired(Deque<Long> timestamps, long nowMillis) {
    synchronized (lock) {
      long cutoff = nowMillis - windowMillis;
      while (!timestamps.isEmpty() && timestamps.peekFirst().longValue() <= cutoff) {
        timestamps.removeFirst();
      }
    }
  }

  private void takeFrame(HeldFrameState reservation, long sentAtMillis) {
    synchronized (lock) {
      if (reservation.closed || reservation.framesRemaining == 0) {
        return;
      }
      reservation.framesRemaining--;
      heldFrames--;
      sentFrameTimestamps.addLast(Long.valueOf(sentAtMillis));
    }
  }

  private void closeConnection(ConnectionPermit permit) {
    synchronized (lock) {
      if (permit.reservation.closed) {
        return;
      }
      permit.reservation.closed = true;
      heldFrames -= permit.reservation.framesRemaining;
      permit.reservation.framesRemaining = 0;
      openConnections--;
    }
  }

  private void closeFrameReservation(FrameReservation permit) {
    synchronized (lock) {
      if (permit.reservation.closed) {
        return;
      }
      permit.reservation.closed = true;
      heldFrames -= permit.reservation.framesRemaining;
      permit.reservation.framesRemaining = 0;
    }
  }

  private void closeSubscription(SubscriptionPermit permit) {
    synchronized (lock) {
      if (permit.closed) {
        return;
      }
      permit.closed = true;
      reservedSubscriptions -= 2;
    }
  }

  private static void requireNonNegative(int value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must be non-negative");
    }
  }

  /**
   * The result of an immediate budget reservation attempt.
   *
   * @param <T> reservation permit type
   */
  public static final class Decision<T> {

    private final T permit;
    private final long retryAtMillis;

    private Decision(T permit, long retryAtMillis) {
      this.permit = permit;
      this.retryAtMillis = retryAtMillis;
    }

    private static <T> Decision<T> acquired(T permit, long nowMillis) {
      return new Decision<T>(permit, nowMillis);
    }

    private static <T> Decision<T> retryAt(long retryAtMillis) {
      return new Decision<T>(null, retryAtMillis);
    }

    /** Returns whether the requested resources were reserved. */
    public boolean acquired() {
      return permit != null;
    }

    /** Returns the reservation permit, or null when this decision was not acquired. */
    public T permit() {
      return permit;
    }

    /** Returns the earliest theoretical millisecond at which the caller should retry. */
    public long retryAtMillis() {
      return retryAtMillis;
    }
  }

  /** A connection reservation that owns a concurrent socket slot and held outbound frames. */
  public static final class ConnectionPermit implements AutoCloseable {

    private final HeldFrameState reservation;

    private ConnectionPermit(HyperliquidProcessBudget budget, int framesRemaining) {
      reservation = new HeldFrameState(budget, framesRemaining);
    }

    /** Moves one held frame into the rolling sent-frame window. */
    public boolean takeReservedFrame(long sentAtMillis) {
      return reservation.budget.takeReservedFrame(this, sentAtMillis);
    }

    /** Returns how many reserved frames have not yet been sent or released. */
    public int reservedFramesRemaining() {
      return reservation.budget.connectionFramesRemaining(this);
    }

    /** Releases the concurrent connection slot and any held frames that were not sent. */
    @Override
    public void close() {
      reservation.budget.closeConnection(this);
    }
  }

  /** A held outbound-frame reservation independent of a connection reservation. */
  public static final class FrameReservation implements AutoCloseable {

    private final HeldFrameState reservation;

    private FrameReservation(HyperliquidProcessBudget budget, int framesRemaining) {
      reservation = new HeldFrameState(budget, framesRemaining);
    }

    /** Moves one held frame into the rolling sent-frame window. */
    public boolean takeFrame(long sentAtMillis) {
      return reservation.budget.takeReservedFrame(this, sentAtMillis);
    }

    /** Returns how many reserved frames have not yet been sent or released. */
    public int framesRemaining() {
      return reservation.budget.reservationFramesRemaining(this);
    }

    /** Releases every held frame that has not yet been sent. */
    @Override
    public void close() {
      reservation.budget.closeFrameReservation(this);
    }
  }

  /** A two-slot perpetual subscription reservation. */
  public static final class SubscriptionPermit implements AutoCloseable {

    private final HyperliquidProcessBudget budget;
    private boolean closed;

    private SubscriptionPermit(HyperliquidProcessBudget budget) {
      this.budget = budget;
    }

    /** Returns the two subscription slots represented by this permit. */
    public int slots() {
      return 2;
    }

    /** Releases this subscription pair exactly once. */
    @Override
    public void close() {
      budget.closeSubscription(this);
    }
  }

  private boolean takeReservedFrame(ConnectionPermit permit, long sentAtMillis) {
    synchronized (lock) {
      if (permit.reservation.closed || permit.reservation.framesRemaining == 0) {
        return false;
      }
      takeFrame(permit.reservation, sentAtMillis);
      return true;
    }
  }

  private boolean takeReservedFrame(FrameReservation permit, long sentAtMillis) {
    synchronized (lock) {
      if (permit.reservation.closed || permit.reservation.framesRemaining == 0) {
        return false;
      }
      takeFrame(permit.reservation, sentAtMillis);
      return true;
    }
  }

  private int connectionFramesRemaining(ConnectionPermit permit) {
    synchronized (lock) {
      return permit.reservation.framesRemaining;
    }
  }

  private int reservationFramesRemaining(FrameReservation permit) {
    synchronized (lock) {
      return permit.reservation.framesRemaining;
    }
  }

  private static final class SharedBudget {
    private static final HyperliquidProcessBudget INSTANCE =
        new HyperliquidProcessBudget(10, 30, 2_000, 1_000, 60_000L);
  }

  private static final class HeldFrameState {
    private final HyperliquidProcessBudget budget;
    private int framesRemaining;
    private boolean closed;

    private HeldFrameState(HyperliquidProcessBudget budget, int framesRemaining) {
      this.budget = budget;
      this.framesRemaining = framesRemaining;
    }
  }
}
