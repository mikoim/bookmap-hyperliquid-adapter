package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBook;
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff;
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.NormalizedBookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.SubscriptionPermit;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.model.TradeKey;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mutable state owned by one alias while it waits for or receives market data. */
public final class SubscriptionRecord {

  /** The lifecycle state of the alias. */
  public enum State {
    PENDING_BOOK,
    ACTIVE,
    REMOVED
  }

  static final int MAX_PENDING_TRADES = 1_024;

  private final String alias;
  private final PerpetualInstrument instrument;
  private final SubscriptionPermit permit;
  private final SubscriptionKey l2BookKey;
  private final SubscriptionKey tradesKey;
  private final long activationDeadlineMillis;
  private final OrderBookSnapshotDiff diff;
  private final SourceProfile.FeedMode feedMode;
  private final DeltaOrderBook deltaBook;
  private final ArrayDeque<PendingTrade> pendingTrades = new ArrayDeque<PendingTrade>();
  private final Set<TradeKey> pendingTradeKeys = new HashSet<TradeKey>();
  private final EnumSet<SubscriptionType> sent = EnumSet.noneOf(SubscriptionType.class);
  private final EnumSet<SubscriptionType> acknowledgements = EnumSet.noneOf(SubscriptionType.class);

  private State state = State.PENDING_BOOK;
  private CancellableScheduler.Cancellable activationTask;
  private long subscriptionGeneration = -1L;
  private long lastAcceptedBookTime = -1L;
  private NormalizedBookSnapshot pendingBook;
  private long recoveryGeneration = -1L;
  private NormalizedBookSnapshot recoveryBook;
  private boolean pendingTradeOverflowWarned;
  private boolean forceFullResync;

  /** Creates an alias record with its two reserved provider subscription slots. */
  public SubscriptionRecord(
      String alias,
      PerpetualInstrument instrument,
      SubscriptionPermit permit,
      long activationDeadlineMillis,
      SourceProfile.FeedMode feedMode,
      L2BookParameters l2BookParameters) {
    this.alias = alias;
    this.instrument = instrument;
    this.permit = permit;
    this.activationDeadlineMillis = activationDeadlineMillis;
    this.feedMode = feedMode;
    l2BookKey =
        new SubscriptionKey(instrument.symbol(), SubscriptionType.L2_BOOK, l2BookParameters);
    tradesKey = new SubscriptionKey(instrument.symbol(), SubscriptionType.TRADES);
    diff = new OrderBookSnapshotDiff(instrument);
    deltaBook =
        feedMode == SourceProfile.FeedMode.SEED_THEN_DELTA ? new DeltaOrderBook(instrument) : null;
  }

  /** Returns how this alias's source delivers l2Book frames. */
  public SourceProfile.FeedMode feedMode() {
    return feedMode;
  }

  /** Returns the seed-then-delta book, or null in snapshot mode. */
  public DeltaOrderBook deltaBook() {
    return deltaBook;
  }

  /** Returns whether a current-generation book is available for activation. */
  public boolean hasActivationBook() {
    return deltaBook == null ? pendingBook != null : deltaBook.seeded();
  }

  /** Deletes every published level of this alias, whichever book mode it uses. */
  public List<DepthUpdate> clearPublishedBook() {
    return deltaBook == null ? diff.clear() : deltaBook.clearPublished();
  }

  /** Returns the Bookmap alias for this record. */
  public String alias() {
    return alias;
  }

  /** Returns the instrument metadata used to normalize provider values. */
  public PerpetualInstrument instrument() {
    return instrument;
  }

  /** Returns the l2-book provider subscription key. */
  public SubscriptionKey l2BookKey() {
    return l2BookKey;
  }

  /** Returns the trades provider subscription key. */
  public SubscriptionKey tradesKey() {
    return tradesKey;
  }

  /** Returns the fixed deadline measured when the API command was accepted. */
  public long activationDeadlineMillis() {
    return activationDeadlineMillis;
  }

  /** Returns the record lifecycle state. */
  public State state() {
    return state;
  }

  /** Returns this alias's order-book differ. */
  public OrderBookSnapshotDiff diff() {
    return diff;
  }

  /** Returns the last accepted provider book timestamp. */
  public long lastAcceptedBookTime() {
    return lastAcceptedBookTime;
  }

  /** Returns the latest valid candidate awaiting activation. */
  public NormalizedBookSnapshot pendingBook() {
    return pendingBook;
  }

  /** Updates the candidate and its monotonic timestamp while pending. */
  public void acceptPendingBook(NormalizedBookSnapshot book) {
    pendingBook = book;
    lastAcceptedBookTime = book.time();
  }

  /** Advances the accepted timestamp after an active snapshot was applied. */
  public void acceptActiveBook(long time) {
    lastAcceptedBookTime = time;
  }

  /** Returns the latest timestamp-valid book candidate captured during recovery. */
  public NormalizedBookSnapshot recoveryBook() {
    return recoveryBook;
  }

  /** Returns the recovery candidate timestamp, or {@code -1} when none is retained. */
  public long recoveryBookTime() {
    return recoveryBook == null ? -1L : recoveryBook.time();
  }

  /**
   * Retains a recovery candidate without changing the live diff baseline or accepted-book time.
   *
   * @return whether the candidate belongs to this active generation and is newer than both retained
   *     timestamps
   */
  public boolean acceptRecoveryBook(long generation, NormalizedBookSnapshot book) {
    if (state != State.ACTIVE
        || book == null
        || subscriptionGeneration != generation
        || book.time() < lastAcceptedBookTime
        || (recoveryBook != null && book.time() < recoveryBook.time())) {
      return false;
    }
    if (recoveryGeneration != generation) {
      clearRecoveryBook();
      recoveryGeneration = generation;
    }
    recoveryBook = book;
    return true;
  }

  /** Removes the retained recovery candidate and its generation marker. */
  public void clearRecoveryBook() {
    recoveryBook = null;
    recoveryGeneration = -1L;
  }

  /** Takes the candidate for a matching generation, clearing it from this record. */
  public NormalizedBookSnapshot takeRecoveryBook(long generation) {
    if (recoveryGeneration != generation) {
      return null;
    }
    NormalizedBookSnapshot candidate = recoveryBook;
    clearRecoveryBook();
    return candidate;
  }

  /** Removes and returns the pending activation candidate. */
  public NormalizedBookSnapshot takePendingBook() {
    NormalizedBookSnapshot candidate = pendingBook;
    pendingBook = null;
    return candidate;
  }

  /** Advances this record to active delivery. */
  public void transitionToActive() {
    state = State.ACTIVE;
  }

  /** Adds one successful outbound subscription write for its connector generation. */
  public void markSent(long generation, SubscriptionKey key) {
    if (!matches(key)) {
      return;
    }
    if (subscriptionGeneration != generation) {
      subscriptionGeneration = generation;
      sent.clear();
      acknowledgements.clear();
    }
    sent.add(key.type());
  }

  /** Returns whether an acknowledgement matches a successful write in the same generation. */
  public boolean acceptAcknowledgement(long generation, SubscriptionKey key) {
    if (subscriptionGeneration != generation || !matches(key) || !sent.contains(key.type())) {
      return false;
    }
    acknowledgements.add(key.type());
    return true;
  }

  /** Returns whether the indicated subscription type was acknowledged for the active generation. */
  public boolean hasAcknowledgement(SubscriptionType type) {
    return acknowledgements.contains(type);
  }

  /** Clears current-generation acknowledgement state when a new socket opens. */
  public void beginGeneration(long generation) {
    subscriptionGeneration = generation;
    sent.clear();
    acknowledgements.clear();
    clearRecoveryBook();
    if (deltaBook != null) {
      deltaBook.beginGeneration();
    }
  }

  /** Stores a converted pending trade when its bounded queue has room. */
  public boolean addPendingTrade(PendingTrade trade) {
    if (pendingTrades.size() == MAX_PENDING_TRADES) {
      return false;
    }
    pendingTrades.addLast(trade);
    pendingTradeKeys.add(trade.key);
    return true;
  }

  /** Returns whether the temporary pending set already contains a trade key. */
  public boolean containsPendingTradeKey(TradeKey key) {
    return pendingTradeKeys.contains(key);
  }

  /** Drains pending trades in provider arrival order. */
  public ArrayDeque<PendingTrade> takePendingTrades() {
    ArrayDeque<PendingTrade> taken = new ArrayDeque<PendingTrade>(pendingTrades);
    pendingTrades.clear();
    pendingTradeKeys.clear();
    return taken;
  }

  /** Drops trades retained while recovering a generation without affecting pending activation. */
  void clearRecoveryTrades() {
    pendingTrades.clear();
    pendingTradeKeys.clear();
    pendingTradeOverflowWarned = false;
  }

  /** Returns whether the queue-overflow warning was already emitted. */
  public boolean pendingTradeOverflowWarned() {
    return pendingTradeOverflowWarned;
  }

  /** Records that the single queue-overflow warning has been emitted. */
  public void markPendingTradeOverflowWarned() {
    pendingTradeOverflowWarned = true;
  }

  /** Returns and clears the full-resynchronization flag. */
  public boolean takeForceFullResync() {
    boolean requested = forceFullResync;
    forceFullResync = false;
    return requested;
  }

  /** Requests full replay of a later valid active snapshot. */
  public void requestFullResync() {
    forceFullResync = true;
  }

  /** Installs the per-alias activation timer. */
  public void setActivationTask(CancellableScheduler.Cancellable task) {
    activationTask = task;
  }

  /** Cancels mutable record state and releases its subscription permit exactly once. */
  public void remove() {
    if (state == State.REMOVED) {
      return;
    }
    state = State.REMOVED;
    if (activationTask != null) {
      activationTask.cancel();
      activationTask = null;
    }
    sent.clear();
    acknowledgements.clear();
    pendingBook = null;
    clearRecoveryBook();
    pendingTrades.clear();
    pendingTradeKeys.clear();
    lastAcceptedBookTime = -1L;
    forceFullResync = false;
    diff.reset();
    if (deltaBook != null) {
      deltaBook.reset();
    }
    permit.close();
  }

  private boolean matches(SubscriptionKey key) {
    return l2BookKey.equals(key) || tradesKey.equals(key);
  }

  /** Converted trade held until pending activation reaches its output ordering point. */
  static final class PendingTrade {
    private final TradeKey key;
    private final double priceUnits;
    private final int sizeUnits;
    private final boolean buyAggressor;

    PendingTrade(TradeKey key, double priceUnits, int sizeUnits, boolean buyAggressor) {
      this.key = key;
      this.priceUnits = priceUnits;
      this.sizeUnits = sizeUnits;
      this.buyAggressor = buyAggressor;
    }

    TradeKey key() {
      return key;
    }

    double priceUnits() {
      return priceUnits;
    }

    int sizeUnits() {
      return sizeUnits;
    }

    boolean buyAggressor() {
      return buyAggressor;
    }
  }
}
