package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.OutboundMessage;
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.SnapshotValidation;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.Decision;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.SubscriptionPermit;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.StateEventDispatcher;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.MarketDataEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.model.TradeEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.session.SubscriptionRecord.PendingTrade;
import com.bookmap.plugins.layer0.hyperliquid.trade.BoundedTradeDeduplicator;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Bridges connector callbacks to Bookmap session output. Mutable subscription state is serialized
 * through the supplied dispatcher; frame parsing remains on the connector callback thread.
 */
public final class HyperliquidSession
    implements HyperliquidSessionApi, HyperliquidConnector.Listener {

  private static final long ACTIVATION_TIMEOUT_MILLIS = 10_000L;
  private static final int TRADE_DEDUPLICATION_CAPACITY = 100_000;
  private static final long TRADE_DEDUPLICATION_TTL_MILLIS = 60_000L;

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
  private final Map<String, PerpetualInstrument> instruments =
      new TreeMap<String, PerpetualInstrument>();
  private final Map<String, SubscriptionRecord> records = new TreeMap<String, SubscriptionRecord>();

  private boolean closed;
  private boolean metadataReceived;
  private boolean connectedOnce;
  private long currentGeneration = -1L;

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
  public void login(final HyperliquidEnvironment environment) {
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleLogin(environment);
          }
        });
  }

  /** Enqueues an asynchronous perpetual subscription command. */
  @Override
  public void subscribe(final String symbol, final String exchange, final String type) {
    final long activationDeadlineMillis = clock.getAsLong() + ACTIVATION_TIMEOUT_MILLIS;
    dispatcher.submitControl(
        new Runnable() {
          @Override
          public void run() {
            handleSubscribe(symbol, exchange, type, activationDeadlineMillis);
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
            if (!closed) {
              sink.onDiagnostic("market-data frame queue overflow");
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
    if (!closed) {
      sink.onLoginFailed(loginFailure(failure), failure == null ? null : failure.message());
    }
  }

  /** Receives a newly opened connector generation on the serialized state lane. */
  @Override
  public void onSocketOpened(long generation) {
    if (closed) {
      return;
    }
    currentGeneration = generation;
    for (SubscriptionRecord record : records.values()) {
      record.beginGeneration(generation);
    }
    if (connectedOnce) {
      sink.onConnectionRestored();
    } else {
      connectedOnce = true;
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
              if (!closed) {
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
                || message.kind() != OutboundMessage.Kind.SUBSCRIBE) {
              return;
            }
            SubscriptionRecord record = recordForKey(message.subscription());
            if (record != null && record.state() != SubscriptionRecord.State.REMOVED) {
              record.markSent(generation, message.subscription());
            }
          }
        });
  }

  /** Receives a disconnected connector generation on the serialized state lane. */
  @Override
  public void onDisconnected(long generation, TransportFailure failure) {
    if (!closed && generation == currentGeneration) {
      currentGeneration = -1L;
      dispatcher.discardMarketFrames();
      sink.onConnectionLost(connectionFailure(failure), failure == null ? null : failure.message());
    }
  }

  private void handleLogin(HyperliquidEnvironment environment) {
    if (!closed && environment != null) {
      connector.start(environment);
    }
  }

  private void handleSubscribe(
      String symbol, String exchange, String type, long activationDeadlineMillis) {
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
    final SubscriptionRecord record =
        new SubscriptionRecord(symbol, instrument, decision.permit(), activationDeadlineMillis);
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
    if (closed) {
      return;
    }
    closed = true;
    for (String alias : new ArrayList<String>(records.keySet())) {
      removeRecord(alias, RemovalCause.CLOSE, null);
    }
    connector.close();
    afterClose.run();
  }

  private void handleControls(long generation, List<ControlEvent> controls) {
    if (closed || generation != currentGeneration) {
      return;
    }
    for (ControlEvent event : controls) {
      if (event.kind() == ControlEvent.Kind.PONG) {
        connector.acceptPong(generation);
      } else if (event.kind() == ControlEvent.Kind.SUBSCRIPTION_ACK) {
        handleAcknowledgement(generation, event.target());
      } else if (event.kind() == ControlEvent.Kind.SUBSCRIPTION_ERROR) {
        handleSubscriptionError(event.target());
      }
    }
  }

  private void handleAcknowledgement(long generation, SubscriptionKey key) {
    SubscriptionRecord record = recordForKey(key);
    if (record == null || record.state() != SubscriptionRecord.State.PENDING_BOOK) {
      return;
    }
    if (record.acceptAcknowledgement(generation, key)) {
      activateIfReady(record);
    }
  }

  private void handleSubscriptionError(SubscriptionKey target) {
    SubscriptionRecord record = recordForKey(target);
    if (record == null) {
      connector.stopFatal("untargetable Hyperliquid subscription error");
      return;
    }
    removeRecord(record.alias(), RemovalCause.REJECTION, "Hyperliquid rejected subscription");
  }

  private void handleMarketFrame(long generation, List<MarketDataEvent> events) {
    if (closed || generation != currentGeneration) {
      return;
    }
    for (MarketDataEvent event : events) {
      if (closed || generation != currentGeneration) {
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
    publishDepth(record, record.diff().apply(validation.snapshot(), record.takeForceFullResync()));
    record.acceptActiveBook(validation.snapshot().time());
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
    publishIfNew(record, converted);
  }

  private PendingTrade convertTrade(SubscriptionRecord record, TradeEvent trade) {
    try {
      return new PendingTrade(
          trade.key(),
          record.instrument().toTradePriceUnits(trade.price()),
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
        || record.pendingBook() == null
        || clock.getAsLong() >= record.activationDeadlineMillis()) {
      return;
    }
    record.transitionToActive();
    sink.onInstrumentAdded(record.instrument());
    publishDepth(record, record.diff().apply(record.takePendingBook(), false));
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
    if (wasActive && (cause == RemovalCause.UNSUPPORTED_PRICE || cause == RemovalCause.REJECTION)) {
      publishDepth(record, record.diff().clear());
    }
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
    CLOSE
  }
}
