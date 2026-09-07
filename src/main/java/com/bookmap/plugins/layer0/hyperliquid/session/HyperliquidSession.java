package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.OutboundMessage;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.book.DeltaOrderBook;
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.SnapshotValidation;
import com.bookmap.plugins.layer0.hyperliquid.book.PriceBucketer;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.Decision;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.SubscriptionPermit;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import com.bookmap.plugins.layer0.hyperliquid.model.MarketDataEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.model.TickSizePlan;
import com.bookmap.plugins.layer0.hyperliquid.model.TradeEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import com.bookmap.plugins.layer0.hyperliquid.trade.BoundedTradeDeduplicator;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Bridges connector callbacks to Bookmap session output. Mutable subscription state is serialized
 * through the supplied dispatcher; frame parsing remains on the connector callback thread.
 */
public final class HyperliquidSession
    implements HyperliquidSessionApi, HyperliquidConnector.Listener {

  private static final long ACTIVATION_TIMEOUT_MILLIS = 10_000L;
  private static final long ACK_TIMEOUT_MILLIS = 10_000L;
  private static final int TRADE_DEDUPLICATION_CAPACITY = 10_000;
  private static final long TRADE_DEDUPLICATION_TTL_MILLIS = 600_000L;
  private static final long ASSET_CONTEXT_REPUBLISH_INTERVAL_MILLIS = 5_000L;

  private final HyperliquidConnector connector;
  private final HyperliquidMessageParser parser;
  private final HyperliquidProcessBudget budget;
  private final CancellableScheduler scheduler;
  private final LongSupplier clock;
  private final StateEventDispatcher dispatcher;
  private final SessionSink sink;
  private final Runnable afterClose;
  private final BoundedTradeDeduplicator tradeDeduplicator =
      new BoundedTradeDeduplicator(TRADE_DEDUPLICATION_CAPACITY, TRADE_DEDUPLICATION_TTL_MILLIS);
  private final AssetContextStore assetContexts = new AssetContextStore();
  private final Map<String, PerpetualInstrument> instruments =
      new TreeMap<String, PerpetualInstrument>();
  private final Map<String, SubscriptionRecord> records = new TreeMap<String, SubscriptionRecord>();
  private final Map<SubscriptionKey, CancellableScheduler.Cancellable> acknowledgementTasks =
      new TreeMap<SubscriptionKey, CancellableScheduler.Cancellable>();
  private final Map<SubscriptionKey, Long> acknowledgementDeadlines =
      new TreeMap<SubscriptionKey, Long>();
  private final Set<SubscriptionKey> timedOutAcknowledgements = new HashSet<SubscriptionKey>();

  private boolean closed;
  private boolean metadataReceived;
  private boolean connectedOnce;
  private boolean loginNotified;
  private boolean loginFailureNotified;
  private boolean lossNotifiedForIncident;
  private boolean restoreNotifiedForIncident;
  private boolean recovering;
  private boolean generationInvalidated;
  private boolean assetContextSnapshotPending;
  private boolean assetContextPublished;
  private long lastAssetContextPublishMillis;
  private ConnectionState connectionState = ConnectionState.STARTING;
  private long currentGeneration = -1L;
  private SourceProfile profile;

  /**
   * Creates a session using explicitly supplied connector, scheduling, and state-lane boundaries.
   */
  public HyperliquidSession(
      HyperliquidConnector connector,
      HyperliquidMessageParser parser,
      HyperliquidProcessBudget budget,
      CancellableScheduler scheduler,
      LongSupplier clock,
      StateEventDispatcher dispatcher,
      SessionSink sink,
      Runnable afterClose) {
    if (connector == null
        || parser == null
        || budget == null
        || scheduler == null
        || clock == null
        || dispatcher == null
        || sink == null
        || afterClose == null) {
      throw new IllegalArgumentException("session dependencies must not be null");
    }
    this.connector = connector;
    this.parser = parser;
    this.budget = budget;
    this.scheduler = scheduler;
    this.clock = clock;
    this.dispatcher = dispatcher;
    this.sink = sink;
    this.afterClose = afterClose;
    connector.setListener(this);
  }

  /** Enqueues an asynchronous login command. */
  @Override
  public void login(final SourceProfile profile) {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleLogin(profile);
          }
        });
  }

  /** Enqueues an asynchronous perpetual subscription command at the default tick. */
  @Override
  public void subscribe(final String symbol, final String exchange, final String type) {
    subscribe(symbol, exchange, type, null);
  }

  /** Enqueues an asynchronous perpetual subscription command at the chosen tick. */
  @Override
  public void subscribe(
      final String symbol, final String exchange, final String type, final BigDecimal tick) {
    final long activationDeadlineMillis = clock.getAsLong() + ACTIVATION_TIMEOUT_MILLIS;
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleSubscribe(symbol, exchange, type, tick, activationDeadlineMillis);
          }
        });
  }

  /** Enqueues an asynchronous alias-removal command. */
  @Override
  public void unsubscribe(final String alias) {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleUnsubscribe(alias);
          }
        });
  }

  /** Enqueues permanent session shutdown. */
  @Override
  public void close() {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleClose();
          }
        });
  }

  /**
   * Handles the dispatcher's first rejected market frame without touching callback-thread state.
   */
  public void onMarketOverflow() {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            if (!closed && connectionState != ConnectionState.STOPPED) {
              sink.onSystemMessage("market-data frame queue overflow", MessageKind.UNCLASSIFIED);
              dispatcher.discardMarketFrames();
              for (SubscriptionRecord record : records.values()) {
                if (record.state() == SubscriptionRecord.State.ACTIVE) {
                  record.requestFullResync();
                  record.clearRecoveryBook();
                  record.clearRecoveryTrades();
                }
              }
              boolean reconnect =
                  connectionState == ConnectionState.CONNECTED
                      || connectionState == ConnectionState.RECONNECTING;
              generationInvalidated = reconnect;
              if (reconnect) {
                connectionState = ConnectionState.RECONNECTING;
                recovering = true;
                connector.reconnect(
                    new TransportFailure(
                        TransportFailure.Kind.REMOTE, "market-data frame queue overflow", null));
              }
            }
          }
        });
  }

  /** Receives validated metadata on the connector's serialized state lane. */
  @Override
  public void onMetadata(List<PerpetualInstrument> metadata) {
    if (closed) {
      return;
    }
    instruments.clear();
    for (PerpetualInstrument instrument : metadata) {
      instruments.put(instrument.symbol(), instrument);
    }
    metadataReceived = true;
    sink.onKnownInstruments(new ArrayList<PerpetualInstrument>(instruments.values()));
  }

  /** Receives initial connector failure on the serialized state lane. */
  @Override
  public void onInitialFailure(TransportFailure failure) {
    if (!closed && !loginFailureNotified) {
      loginFailureNotified = true;
      sink.onLoginFailed(loginFailure(failure), failure == null ? null : failure.message());
      stop(StopCause.INITIAL_FAILURE);
    }
  }

  /** Receives a newly opened connector generation on the serialized state lane. */
  @Override
  public void onSocketOpened(long generation) {
    if (closed || connectionState == ConnectionState.STOPPED) {
      return;
    }
    boolean reconnecting = connectedOnce;
    currentGeneration = generation;
    generationInvalidated = false;
    assetContextSnapshotPending = true;
    dispatcher.resetOverflowSignal();
    for (SubscriptionRecord record : records.values()) {
      record.beginGeneration(generation);
    }
    if (reconnecting) {
      connectionState = ConnectionState.RECONNECTING;
      recovering = true;
      if (records.isEmpty()) {
        maybeRestore(generation);
      }
    } else {
      connectionState = ConnectionState.CONNECTED;
      connectedOnce = true;
      loginNotified = true;
      sink.onLoginSuccessful();
    }
  }

  /** Parses an immutable raw frame on the callback thread and schedules its output atomically. */
  @Override
  public void onFrame(final long generation, String json) {
    final ParsedFrame frame = parser.parse(json);
    for (final String diagnostic : frame.diagnostics()) {
      dispatcher.submitControl(
          new Runnable() {
            @Override
            public void run() {
              if (!closed && generation == currentGeneration && !generationInvalidated) {
                sink.onDiagnostic(diagnostic);
              }
            }
          });
    }
    if (!frame.controlEvents().isEmpty()) {
      dispatcher.submitControl(
          new Runnable() {
            @Override
            public void run() {
              handleControls(generation, frame.controlEvents());
            }
          });
    }
    if (!frame.marketEvents().isEmpty()) {
      dispatcher.submitMarketFrame(
          frame.marketEvents().size(),
          new Runnable() {
            @Override
            public void run() {
              handleMarketFrame(generation, frame.marketEvents());
            }
          });
    }
  }

  /**
   * Records the successful send-time needed to reject unsolicited subscription acknowledgements.
   */
  @Override
  public void onFrameSent(final long generation, final OutboundMessage message, long sentAtMillis) {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            if (closed
                || generation != currentGeneration
                || generationInvalidated
                || message.kind() != OutboundMessage.Kind.SUBSCRIBE) {
              return;
            }
            SubscriptionRecord record = recordForKey(message.subscription());
            if (record != null && record.state() != SubscriptionRecord.State.REMOVED) {
              record.markSent(generation, message.subscription());
              scheduleAcknowledgementTimeout(generation, message, sentAtMillis);
            }
          }
        });
  }

  /** Receives a disconnected connector generation on the serialized state lane. */
  @Override
  public void onDisconnected(long generation, TransportFailure failure) {
    if (!closed && generation == currentGeneration) {
      currentGeneration = -1L;
      cancelAcknowledgementTasks();
      dispatcher.discardMarketFrames();
      for (SubscriptionRecord record : records.values()) {
        if (record.state() == SubscriptionRecord.State.ACTIVE) {
          record.requestFullResync();
          record.clearRecoveryBook();
          record.clearRecoveryTrades();
        }
      }
      ConnectionFailure classified = connectionFailure(failure);
      if (!lossNotifiedForIncident) {
        lossNotifiedForIncident = true;
        sink.onConnectionLost(classified, failure == null ? null : failure.message());
      }
      if (classified == ConnectionFailure.FATAL) {
        stop(StopCause.FATAL);
      } else {
        connectionState = ConnectionState.RECONNECTING;
        recovering = true;
        restoreNotifiedForIncident = false;
      }
    }
  }

  private void handleLogin(SourceProfile newProfile) {
    if (!closed && newProfile != null) {
      if (profile == null) {
        profile = newProfile;
      }
      connectionState = ConnectionState.STARTING;
      connector.start(newProfile);
    }
  }

  private void handleSubscribe(
      String symbol, String exchange, String type, BigDecimal tick, long activationDeadlineMillis) {
    if (closed) {
      return;
    }
    if (symbol == null || !"PERPETUAL".equals(type) || !metadataReceived) {
      sink.onInstrumentNotFound(symbol, exchange, type);
      return;
    }
    if (records.containsKey(symbol)) {
      sink.onInstrumentAlreadySubscribed(symbol, exchange, type);
      return;
    }
    PerpetualInstrument instrument = instruments.get(symbol);
    if (instrument == null) {
      sink.onInstrumentNotFound(symbol, exchange, type);
      return;
    }
    Decision<SubscriptionPermit> decision = budget.tryReserveSubscriptionPair(clock.getAsLong());
    if (!decision.acquired()) {
      sink.onSystemMessage(
          "Hyperliquid subscription limit reached", MessageKind.SUBSCRIPTION_LIMIT);
      return;
    }
    BigDecimal defaultTick =
        TickSizePlan.defaultTick(instrument.referencePrice(), instrument.priceDecimals());
    BigDecimal resolvedTick = defaultTick;
    if (tick != null) {
      if (TickSizePlan.isSupportedTick(tick, instrument.priceDecimals())) {
        resolvedTick = tick;
      } else {
        sink.onDiagnostic(
            "unsupported tick " + tick.toPlainString() + " for " + symbol + "; using default");
      }
    }
    L2BookParameters parameters =
        TickSizePlan.parametersFor(
            resolvedTick,
            instrument.referencePrice(),
            instrument.priceDecimals(),
            profile.nLevels());
    final SubscriptionRecord record =
        new SubscriptionRecord(
            symbol,
            instrument,
            decision.permit(),
            activationDeadlineMillis,
            profile.feedMode(),
            parameters,
            new PriceBucketer(instrument, resolvedTick));
    records.put(symbol, record);
    record.setActivationTask(
        scheduler.schedule(
            new Runnable() {
              @Override
              public void run() {
                dispatcher.submitControl(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (records.get(record.alias()) == record
                            && record.state() == SubscriptionRecord.State.PENDING_BOOK) {
                          removeRecord(
                              record.alias(),
                              RemovalCause.TIMEOUT,
                              "subscription activation timed out");
                        }
                      }
                    });
              }
            },
            Math.max(0L, activationDeadlineMillis - clock.getAsLong())));
    connector.subscribe(record.l2BookKey(), activationDeadlineMillis);
    connector.subscribe(record.tradesKey(), activationDeadlineMillis);
  }

  private void handleUnsubscribe(String alias) {
    if (!closed && alias != null && records.containsKey(alias)) {
      removeRecord(alias, RemovalCause.USER, null);
    }
  }

  private void handleClose() {
    stop(StopCause.CLOSE);
  }

  private void handleControls(long generation, List<ControlEvent> controls) {
    if (closed || generation != currentGeneration || generationInvalidated) {
      return;
    }
    for (ControlEvent event : controls) {
      if (event.kind() == ControlEvent.Kind.PONG) {
        connector.acceptPong(generation);
      } else if (event.kind() == ControlEvent.Kind.SUBSCRIPTION_ACK) {
        handleAcknowledgement(generation, event.target());
      } else if (event.kind() == ControlEvent.Kind.SUBSCRIPTION_ERROR) {
        handleSubscriptionError(event.target());
      } else if (event.kind() == ControlEvent.Kind.ASSET_CONTEXTS) {
        boolean snapshot = assetContextSnapshotPending;
        assetContextSnapshotPending = false;
        onAssetContexts(event.markPrices(), snapshot);
      }
    }
  }

  /**
   * Applies decoded mark prices and refreshes the known-instrument list. Callers must already run
   * on the state lane; the Hyperliquid source enters through {@link #handleControls} and a relay
   * enters through its dedicated asset-context feed.
   */
  void onAssetContexts(Map<String, BigDecimal> markPrices, boolean snapshot) {
    if (closed || !metadataReceived) {
      return;
    }
    if (snapshot) {
      assetContexts.applySnapshot(markPrices);
    } else if (!shouldRepublish(assetContexts.applyDelta(markPrices))) {
      return;
    }
    assetContextPublished = true;
    lastAssetContextPublishMillis = clock.getAsLong();
    for (Map.Entry<String, PerpetualInstrument> entry : instruments.entrySet()) {
      PerpetualInstrument current = entry.getValue();
      entry.setValue(
          new PerpetualInstrument(
              current.symbol(), current.sizeDecimals(), assetContexts.markPrice(current.symbol())));
    }
    sink.onKnownInstruments(new ArrayList<PerpetualInstrument>(instruments.values()));
  }

  /** Rebuilds only when a listed instrument moved and the throttle window has elapsed. */
  private boolean shouldRepublish(Set<String> changed) {
    boolean touchesKnownInstrument = false;
    for (String symbol : changed) {
      if (instruments.containsKey(symbol)) {
        touchesKnownInstrument = true;
        break;
      }
    }
    if (!touchesKnownInstrument) {
      return false;
    }
    return !assetContextPublished
        || clock.getAsLong() - lastAssetContextPublishMillis
            >= ASSET_CONTEXT_REPUBLISH_INTERVAL_MILLIS;
  }

  private void handleAcknowledgement(long generation, SubscriptionKey key) {
    SubscriptionRecord record = recordForKey(key);
    if (record == null || record.state() == SubscriptionRecord.State.REMOVED) {
      return;
    }
    if (record.state() == SubscriptionRecord.State.PENDING_BOOK
        && clock.getAsLong() >= record.activationDeadlineMillis()) {
      // The activation timer owns this earlier (or equal) deadline and will release the permit.
      return;
    }
    if (timedOutAcknowledgements.contains(key)) {
      return;
    }
    CancellableScheduler.Cancellable task = acknowledgementTasks.remove(key);
    if (task != null) {
      task.cancel();
    }
    Long deadline = acknowledgementDeadline(generation, key);
    acknowledgementDeadlines.remove(key);
    if (deadline != null && clock.getAsLong() >= deadline.longValue()) {
      handleAcknowledgementTimeout(generation, key);
      return;
    }
    if (record.acceptAcknowledgement(generation, key)) {
      if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
        activateIfReady(record);
      }
      maybeRestore(generation);
    }
  }

  private void handleSubscriptionError(SubscriptionKey target) {
    SubscriptionRecord record = recordForKey(target);
    if (record == null) {
      stop(StopCause.FATAL);
      return;
    }
    removeRecord(record.alias(), RemovalCause.REJECTION, "Hyperliquid rejected subscription");
  }

  private void handleMarketFrame(long generation, List<MarketDataEvent> events) {
    if (closed || generation != currentGeneration || generationInvalidated) {
      return;
    }
    for (MarketDataEvent event : events) {
      if (closed || generation != currentGeneration || generationInvalidated) {
        return;
      }
      if (event instanceof BookSnapshot) {
        handleBook((BookSnapshot) event);
      } else if (event instanceof TradeEvent) {
        handleTrade((TradeEvent) event);
      }
    }
  }

  private void handleBook(BookSnapshot snapshot) {
    SubscriptionRecord record = records.get(snapshot.coin());
    if (record == null || record.state() == SubscriptionRecord.State.REMOVED) {
      sink.onDiagnostic("discarded book for unsubscribed coin: " + snapshot.coin());
      return;
    }
    if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
      handleDeltaBook(record, snapshot);
      return;
    }
    SnapshotValidation validation = record.diff().validate(snapshot, record.lastAcceptedBookTime());
    if (validation.status() == SnapshotValidation.Status.UNSUPPORTED_PRICE) {
      removeRecord(
          record.alias(),
          RemovalCause.UNSUPPORTED_PRICE,
          "Hyperliquid book price is unsupported: " + validation.diagnostic());
      return;
    }
    if (validation.status() != SnapshotValidation.Status.VALID) {
      return;
    }
    if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
      record.acceptPendingBook(validation.snapshot());
      activateIfReady(record);
      return;
    }
    if (recovering && connectionState == ConnectionState.RECONNECTING) {
      record.acceptRecoveryBook(currentGeneration, validation.snapshot());
      return;
    }
    publishDepth(record, record.diff().apply(validation.snapshot(), record.takeForceFullResync()));
    record.acceptActiveBook(validation.snapshot().time());
  }

  /**
   * Seed-then-delta path. The first frame of a generation is the seed and is always accepted; later
   * frames are deltas that must not be older than the last accepted frame.
   */
  private void handleDeltaBook(SubscriptionRecord record, BookSnapshot snapshot) {
    DeltaOrderBook book = record.deltaBook();
    boolean recoveringNow = recovering && connectionState == ConnectionState.RECONNECTING;
    if (!book.seeded()) {
      DeltaOrderBook.Result seed = book.applySeed(snapshot);
      if (!acceptDeltaResult(record, seed)) {
        return;
      }
      record.acceptActiveBook(snapshot.time());
      if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
        activateIfReady(record);
      } else if (!recoveringNow) {
        publishDepth(record, book.replacePublished());
      }
      return;
    }
    if (snapshot.time() < record.lastAcceptedBookTime()) {
      sink.onDiagnostic("discarded stale book delta for " + record.alias());
      return;
    }
    boolean live = record.state() == SubscriptionRecord.State.ACTIVE && !recoveringNow;
    DeltaOrderBook.Result delta = book.applyDelta(snapshot, live);
    if (!acceptDeltaResult(record, delta)) {
      return;
    }
    record.acceptActiveBook(snapshot.time());
    if (live) {
      publishDepth(record, delta.updates());
    }
  }

  private boolean acceptDeltaResult(SubscriptionRecord record, DeltaOrderBook.Result result) {
    if (result.status() == DeltaOrderBook.Status.UNSUPPORTED_PRICE) {
      removeRecord(
          record.alias(),
          RemovalCause.UNSUPPORTED_PRICE,
          "Hyperliquid book price is unsupported: " + result.diagnostic());
      return false;
    }
    if (result.status() != DeltaOrderBook.Status.APPLIED) {
      sink.onDiagnostic(
          "discarded invalid book for " + record.alias() + ": " + result.diagnostic());
      return false;
    }
    return true;
  }

  private void handleTrade(TradeEvent trade) {
    SubscriptionRecord record = records.get(trade.coin());
    if (record == null || record.state() == SubscriptionRecord.State.REMOVED) {
      sink.onDiagnostic("discarded trade for unsubscribed coin: " + trade.coin());
      return;
    }
    PendingTrade converted = convertTrade(record, trade);
    if (converted == null) {
      return;
    }
    if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
      if (tradeDeduplicator.contains(converted.key(), clock.getAsLong())
          || record.containsPendingTradeKey(converted.key())) {
        return;
      }
      if (!record.addPendingTrade(converted) && !record.pendingTradeOverflowWarned()) {
        record.markPendingTradeOverflowWarned();
        sink.onDiagnostic("pending trade buffer full for " + record.alias());
      }
      return;
    }
    if (recovering && connectionState == ConnectionState.RECONNECTING) {
      if (tradeDeduplicator.contains(converted.key(), clock.getAsLong())
          || record.containsPendingTradeKey(converted.key())) {
        return;
      }
      if (!record.addPendingTrade(converted) && !record.pendingTradeOverflowWarned()) {
        record.markPendingTradeOverflowWarned();
        sink.onDiagnostic("recovery trade buffer full for " + record.alias());
      }
      return;
    }
    publishIfNew(record, converted);
  }

  private PendingTrade convertTrade(SubscriptionRecord record, TradeEvent trade) {
    try {
      return new PendingTrade(
          trade.key(),
          record.bucketer().tradePriceUnits(trade.price()),
          record.instrument().toSizeUnits(trade.size()),
          trade.isBuyAggressor());
    } catch (ValueConversionException failure) {
      sink.onDiagnostic("discarded invalid trade for " + record.alias());
      return null;
    }
  }

  private void activateIfReady(SubscriptionRecord record) {
    if (record.state() != SubscriptionRecord.State.PENDING_BOOK
        || !record.hasAcknowledgement(SubscriptionType.L2_BOOK)
        || !record.hasAcknowledgement(SubscriptionType.TRADES)
        || !record.hasActivationBook()
        || connectionState == ConnectionState.RECONNECTING
        || clock.getAsLong() >= record.activationDeadlineMillis()) {
      return;
    }
    record.transitionToActive();
    sink.onInstrumentAdded(record.instrument(), record.bucketer().tick());
    if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
      publishDepth(record, record.deltaBook().publishStaged());
    } else {
      publishDepth(record, record.diff().apply(record.takePendingBook(), false));
    }
    ArrayDeque<PendingTrade> pending = record.takePendingTrades();
    while (!pending.isEmpty()) {
      publishIfNew(record, pending.removeFirst());
    }
  }

  private void publishDepth(SubscriptionRecord record, List<DepthUpdate> updates) {
    for (DepthUpdate update : updates) {
      sink.onDepth(record.alias(), update);
    }
  }

  private void publishIfNew(SubscriptionRecord record, PendingTrade trade) {
    if (tradeDeduplicator.markIfNew(trade.key(), clock.getAsLong())) {
      sink.onTrade(record.alias(), trade.priceUnits(), trade.sizeUnits(), trade.buyAggressor());
    }
  }

  private SubscriptionRecord recordForKey(SubscriptionKey key) {
    if (key == null) {
      return null;
    }
    SubscriptionRecord record = records.get(key.coin());
    if (record == null) {
      return null;
    }
    return record.l2BookKey().equals(key) || record.tradesKey().equals(key) ? record : null;
  }

  private void removeRecord(String alias, RemovalCause cause, String message) {
    SubscriptionRecord record = records.remove(alias);
    if (record == null || record.state() == SubscriptionRecord.State.REMOVED) {
      return;
    }
    boolean wasActive = record.state() == SubscriptionRecord.State.ACTIVE;
    if (wasActive
        && (cause == RemovalCause.UNSUPPORTED_PRICE
            || cause == RemovalCause.REJECTION
            || cause == RemovalCause.FATAL
            || cause == RemovalCause.CLOSE)) {
      publishDepth(record, record.clearPublishedBook());
    }
    cancelAcknowledgement(record.l2BookKey());
    cancelAcknowledgement(record.tradesKey());
    record.remove();
    if (wasActive) {
      sink.onInstrumentRemoved(alias);
    }
    if (cause != RemovalCause.CLOSE) {
      connector.unsubscribe(record.l2BookKey());
      connector.unsubscribe(record.tradesKey());
    }
    if (message != null) {
      sink.onSystemMessage(message, MessageKind.UNCLASSIFIED);
    }
    maybeRestore(currentGeneration);
  }

  private void scheduleAcknowledgementTimeout(
      final long generation, final OutboundMessage message, long sentAtMillis) {
    if (message.subscription() == null) {
      return;
    }
    CancellableScheduler.Cancellable previous = acknowledgementTasks.remove(message.subscription());
    if (previous != null) {
      previous.cancel();
    }
    final long deadline = sentAtMillis + ACK_TIMEOUT_MILLIS;
    acknowledgementDeadlines.put(message.subscription(), Long.valueOf(deadline));
    acknowledgementTasks.put(
        message.subscription(),
        scheduler.schedule(
            new Runnable() {
              @Override
              public void run() {
                dispatcher.submitControl(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (!closed && generation == currentGeneration) {
                          handleAcknowledgementTimeout(generation, message.subscription());
                        }
                      }
                    });
              }
            },
            Math.max(0L, deadline - clock.getAsLong())));
  }

  private Long acknowledgementDeadline(long generation, SubscriptionKey key) {
    return acknowledgementDeadlines.containsKey(key) && generation == currentGeneration
        ? acknowledgementDeadlines.get(key)
        : null;
  }

  private void handleAcknowledgementTimeout(long generation, SubscriptionKey key) {
    SubscriptionRecord record = recordForKey(key);
    if (record == null
        || generation != currentGeneration
        || record.hasAcknowledgement(key.type())) {
      acknowledgementTasks.remove(key);
      acknowledgementDeadlines.remove(key);
      return;
    }
    if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
      if (clock.getAsLong() > record.activationDeadlineMillis()) {
        removeRecord(record.alias(), RemovalCause.TIMEOUT, "subscription activation timed out");
      }
    } else if (record.state() == SubscriptionRecord.State.ACTIVE) {
      acknowledgementTasks.remove(key);
      acknowledgementDeadlines.remove(key);
      timedOutAcknowledgements.add(key);
      connector.reconnect(
          new TransportFailure(
              TransportFailure.Kind.NETWORK,
              "Hyperliquid subscription acknowledgement timed out",
              new TimeoutException("subscription acknowledgement timed out")));
    }
  }

  private void cancelAcknowledgementTasks() {
    for (CancellableScheduler.Cancellable task : acknowledgementTasks.values()) {
      task.cancel();
    }
    acknowledgementTasks.clear();
    acknowledgementDeadlines.clear();
    timedOutAcknowledgements.clear();
  }

  private void cancelAcknowledgement(SubscriptionKey key) {
    CancellableScheduler.Cancellable task = acknowledgementTasks.remove(key);
    if (task != null) {
      task.cancel();
    }
    acknowledgementDeadlines.remove(key);
    timedOutAcknowledgements.remove(key);
  }

  private boolean allDesiredSubscriptionsAcked(long generation) {
    for (SubscriptionRecord record : records.values()) {
      if (record.state() == SubscriptionRecord.State.REMOVED
          || !record.hasAcknowledgement(SubscriptionType.L2_BOOK)
          || !record.hasAcknowledgement(SubscriptionType.TRADES)) {
        return false;
      }
    }
    return true;
  }

  private void maybeRestore(long generation) {
    if (connectionState != ConnectionState.RECONNECTING
        || generation != currentGeneration
        || !allDesiredSubscriptionsAcked(generation)) {
      return;
    }
    connectionState = ConnectionState.CONNECTED;
    recovering = false;
    connector.markHealthy(generation);
    if (!restoreNotifiedForIncident) {
      restoreNotifiedForIncident = true;
      sink.onConnectionRestored();
    }
    lossNotifiedForIncident = false;
    for (SubscriptionRecord record : records.values()) {
      if (record.state() == SubscriptionRecord.State.ACTIVE) {
        if (record.feedMode() == SourceProfile.FeedMode.SEED_THEN_DELTA) {
          if (record.deltaBook().seeded()) {
            publishDepth(record, record.deltaBook().replacePublished());
          }
        } else {
          com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.NormalizedBookSnapshot
              book = record.takeRecoveryBook(generation);
          if (book != null) {
            publishDepth(record, record.diff().apply(book, true));
            record.acceptActiveBook(book.time());
            record.takeForceFullResync();
          }
        }
        ArrayDeque<PendingTrade> pending = record.takePendingTrades();
        while (!pending.isEmpty()) {
          publishIfNew(record, pending.removeFirst());
        }
      } else if (record.state() == SubscriptionRecord.State.PENDING_BOOK) {
        activateIfReady(record);
      }
    }
  }

  private enum ConnectionState {
    STARTING,
    CONNECTED,
    RECONNECTING,
    STOPPED
  }

  private enum StopCause {
    INITIAL_FAILURE,
    FATAL,
    CLOSE
  }

  private void stop(StopCause cause) {
    if (connectionState == ConnectionState.STOPPED) {
      return;
    }
    connectionState = ConnectionState.STOPPED;
    closed = true;
    currentGeneration++;
    generationInvalidated = true;
    recovering = false;
    cancelAcknowledgementTasks();
    dispatcher.discardMarketFrames();
    if (cause == StopCause.FATAL && loginNotified && !lossNotifiedForIncident) {
      lossNotifiedForIncident = true;
      sink.onConnectionLost(ConnectionFailure.FATAL, "Hyperliquid session stopped fatally");
    }
    connector.close();
    for (String alias : new ArrayList<String>(records.keySet())) {
      removeRecord(alias, cause == StopCause.FATAL ? RemovalCause.FATAL : RemovalCause.CLOSE, null);
    }
    tradeDeduplicator.clear();
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            dispatcher.close();
            afterClose.run();
          }
        });
  }

  private LoginFailure loginFailure(TransportFailure failure) {
    return failure != null && failure.kind() == TransportFailure.Kind.NETWORK
        ? LoginFailure.NO_INTERNET_CONNECTION
        : LoginFailure.FATAL;
  }

  private ConnectionFailure connectionFailure(TransportFailure failure) {
    if (failure == null) {
      return ConnectionFailure.UNKNOWN;
    }
    if (failure.kind() == TransportFailure.Kind.NETWORK) {
      return ConnectionFailure.NO_INTERNET;
    }
    return failure.kind() == TransportFailure.Kind.PROTOCOL
        ? ConnectionFailure.FATAL
        : ConnectionFailure.UNKNOWN;
  }

  private enum RemovalCause {
    USER,
    TIMEOUT,
    REJECTION,
    UNSUPPORTED_PRICE,
    FATAL,
    CLOSE
  }
}
