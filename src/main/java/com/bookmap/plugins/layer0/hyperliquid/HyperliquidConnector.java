package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.ConnectionPermit;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.Decision;
import com.bookmap.plugins.layer0.hyperliquid.budget.HyperliquidProcessBudget.FrameReservation;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.CancellableScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.ProtocolException;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Owns the Hyperliquid metadata and WebSocket lifecycle while observing the process-wide limits.
 * All mutable state is confined to the supplied state lane.
 */
public final class HyperliquidConnector implements AutoCloseable {

  private static final long METADATA_TIMEOUT_MILLIS = 10_000L;
  private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;
  private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;
  private static final long PONG_TIMEOUT_MILLIS = 15_000L;
  private static final long RETRY_FLOOR_MILLIS = 10L;
  private static final long[] RECONNECT_DELAYS = {
    1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L
  };

  /** Receives lifecycle events emitted by the connector. */
  public interface Listener {

    /** Reports validated perpetual metadata. */
    void onMetadata(List<PerpetualInstrument> instruments);

    /** Reports an unrecoverable initial-login failure. */
    void onInitialFailure(TransportFailure failure);

    /** Reports an opened generation. */
    void onSocketOpened(long generation);

    /** Reports a raw immutable WebSocket frame on the transport callback thread. */
    void onFrame(long generation, String json);

    /** Reports a successful outbound frame acknowledgment. */
    void onFrameSent(long generation, OutboundMessage message, long sentAtMillis);

    /** Reports a disconnected generation. */
    void onDisconnected(long generation, TransportFailure failure);
  }

  private final HyperliquidTransport transport;
  private final HyperliquidMetaParser metaParser;
  private final HyperliquidProcessBudget budget;
  private final CancellableScheduler scheduler;
  private final LongSupplier clock;
  private final Consumer<Runnable> stateSubmitter;
  private final TreeMap<SubscriptionKey, SubscriptionKey> desired =
      new TreeMap<SubscriptionKey, SubscriptionKey>();
  private final TreeMap<SubscriptionKey, Long> activationDeadlines =
      new TreeMap<SubscriptionKey, Long>();
  private final List<PendingSend> pendingSends = new ArrayList<PendingSend>();

  private Listener listener;
  private HyperliquidEnvironment environment;
  private HyperliquidTransport.Cancellable metadataRequest;
  private HyperliquidTransport.Cancellable connectRequest;
  private ConnectionPermit connectionPermit;
  private HyperliquidTransport.Socket socket;
  private CancellableScheduler.Cancellable handshakeDeadline;
  private CancellableScheduler.Cancellable connectionRetry;
  private CancellableScheduler.Cancellable heartbeat;
  private CancellableScheduler.Cancellable pongDeadline;
  private long generation;
  private final FrameGenerationGate frameListenerGeneration = new FrameGenerationGate();
  private long initialConnectionSlotBlockedSinceMillis = -1L;
  private long lastPingAtMillis = -1L;
  private long lastPongAtMillis = -1L;
  private int reconnectAttempt;
  private boolean started;
  private boolean closed;
  private boolean initialConnection;
  private boolean generationActive;
  private boolean socketOpened;
  private boolean initialFailureReported;

  /** Creates a connector with explicitly injected asynchronous boundaries. */
  public HyperliquidConnector(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      HyperliquidProcessBudget budget,
      CancellableScheduler scheduler,
      LongSupplier clock,
      Consumer<Runnable> stateSubmitter) {
    if (transport == null
        || metaParser == null
        || budget == null
        || scheduler == null
        || clock == null
        || stateSubmitter == null) {
      throw new IllegalArgumentException("connector dependencies must not be null");
    }
    this.transport = transport;
    this.metaParser = metaParser;
    this.budget = budget;
    this.scheduler = scheduler;
    this.clock = clock;
    this.stateSubmitter = stateSubmitter;
  }

  /** Sets the listener once, before the connector is started. */
  public void setListener(final Listener newListener) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (newListener == null || listener != null || started) {
              throw new IllegalStateException("listener must be set exactly once before start");
            }
            listener = newListener;
          }
        });
  }

  /** Starts metadata discovery for the supplied environment. */
  public void start(final HyperliquidEnvironment newEnvironment) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            startOnStateLane(newEnvironment);
          }
        });
  }

  /** Adds a desired subscription and sends it before its activation deadline when possible. */
  public void subscribe(final SubscriptionKey key, final long activationDeadlineMillis) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (closed || key == null) {
              return;
            }
            desired.put(key, key);
            activationDeadlines.put(key, Long.valueOf(activationDeadlineMillis));
            if (isReconnectOpening()) {
              replaceReconnectOpening();
              return;
            }
            if (socketOpened) {
              sendWhenPossible(
                  new OutboundMessage(OutboundMessage.Kind.SUBSCRIBE, key, key.subscribeJson()),
                  generation,
                  activationDeadlineMillis,
                  null);
            }
          }
        });
  }

  /** Removes a desired subscription and sends an unsubscribe when the socket remains open. */
  public void unsubscribe(final SubscriptionKey key) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (closed || key == null) {
              return;
            }
            desired.remove(key);
            activationDeadlines.remove(key);
            cancelSubscriptionSends(key);
            if (isReconnectOpening()) {
              replaceReconnectOpening();
              return;
            }
            if (socketOpened) {
              sendWhenPossible(
                  new OutboundMessage(OutboundMessage.Kind.UNSUBSCRIBE, key, key.unsubscribeJson()),
                  generation,
                  Long.MAX_VALUE,
                  null);
            }
          }
        });
  }

  /** Accepts a pong parsed from a raw frame for the current generation. */
  public void acceptPong(final long pongGeneration) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (isCurrentOpenGeneration(pongGeneration)) {
              lastPongAtMillis = clock.getAsLong();
              cancel(pongDeadline);
              pongDeadline = null;
            }
          }
        });
  }

  /** Declares the current reconnect generation restored and resets reconnect backoff. */
  public void markHealthy(final long healthyGeneration) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (isCurrentOpenGeneration(healthyGeneration)) {
              reconnectAttempt = 0;
            }
          }
        });
  }

  /** Closes the current generation and starts its reconnect policy. */
  public void reconnect(final TransportFailure failure) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (!closed && started) {
              disconnectCurrent(
                  failure == null ? protocolFailure("reconnect requested", null) : failure, true);
            }
          }
        });
  }

  /** Stops the connector permanently and reports an initial fatal failure when applicable. */
  public void stopFatal(final String message) {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            if (!closed) {
              reportInitialFailure(protocolFailure(message, null));
              closeOnStateLane();
            }
          }
        });
  }

  /** Cancels outstanding operations, releases permits, and closes the owned transport. */
  @Override
  public void close() {
    stateSubmitter.accept(
        new Runnable() {
          @Override
          public void run() {
            closeOnStateLane();
          }
        });
  }

  private void startOnStateLane(HyperliquidEnvironment newEnvironment) {
    if (closed || started) {
      return;
    }
    if (listener == null || newEnvironment == null) {
      throw new IllegalStateException("listener and environment must be set before start");
    }
    started = true;
    environment = newEnvironment;
    try {
      transport.start();
      metadataRequest =
          transport.postJson(
              environment.infoUri(),
              "application/json",
              "{\"type\":\"meta\"}",
              METADATA_TIMEOUT_MILLIS,
              new HyperliquidTransport.HttpCallback() {
                @Override
                public void onComplete(
                    final int statusCode, final String body, final Throwable failure) {
                  stateSubmitter.accept(
                      new Runnable() {
                        @Override
                        public void run() {
                          completeMetadata(statusCode, body, failure);
                        }
                      });
                }
              });
    } catch (Exception failure) {
      reportInitialFailure(classify(failure, TransportFailure.Kind.NETWORK));
    }
  }

  private void completeMetadata(int statusCode, String body, Throwable failure) {
    if (closed || metadataRequest == null) {
      return;
    }
    metadataRequest = null;
    if (failure != null) {
      reportInitialFailure(classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    if (statusCode < 200 || statusCode >= 300) {
      reportInitialFailure(
          new TransportFailure(
              TransportFailure.Kind.REMOTE, "metadata request returned HTTP " + statusCode, null));
      return;
    }
    try {
      listener.onMetadata(metaParser.parse(body));
    } catch (ProtocolException failureException) {
      reportInitialFailure(classify(failureException, TransportFailure.Kind.PROTOCOL));
      return;
    }
    attemptConnection(true);
  }

  private void attemptConnection(final boolean initial) {
    if (closed || !started || environment == null || generationActive || connectionPermit != null) {
      return;
    }
    long now = clock.getAsLong();
    int reservedFrames = initial ? 0 : desired.size() + 1;
    Decision<ConnectionPermit> decision = budget.tryAcquireConnection(now, reservedFrames);
    if (!decision.acquired()) {
      boolean socketSlotBlocked = initial && reservedFrames == 0 && decision.retryAtMillis() == now;
      if (socketSlotBlocked) {
        if (initialConnectionSlotBlockedSinceMillis < 0L) {
          initialConnectionSlotBlockedSinceMillis = now;
        }
        if (now - initialConnectionSlotBlockedSinceMillis >= HANDSHAKE_TIMEOUT_MILLIS) {
          reportInitialFailure(
              new TransportFailure(
                  TransportFailure.Kind.PROTOCOL,
                  "Hyperliquid concurrent connection limit unavailable for 10 seconds",
                  null));
          return;
        }
      } else if (initial) {
        initialConnectionSlotBlockedSinceMillis = -1L;
      }
      scheduleConnectionAttempt(
          initial, Math.max(now + RETRY_FLOOR_MILLIS, decision.retryAtMillis()));
      return;
    }
    initialConnectionSlotBlockedSinceMillis = -1L;
    connectionPermit = decision.permit();
    generation++;
    generationActive = true;
    socketOpened = false;
    initialConnection = initial;
    final long openingGeneration = generation;
    try {
      HyperliquidTransport.Cancellable openedRequest =
          transport.connect(
              environment.webSocketUri(),
              HANDSHAKE_TIMEOUT_MILLIS,
              socketCallback(openingGeneration, initial));
      if (isCurrentGeneration(openingGeneration)) {
        connectRequest = socketOpened ? null : openedRequest;
        if (!socketOpened) {
          handshakeDeadline =
              scheduleState(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (isCurrentGeneration(openingGeneration) && !socketOpened) {
                        disconnectCurrent(
                            new TransportFailure(
                                TransportFailure.Kind.NETWORK,
                                "WebSocket handshake timed out",
                                new TimeoutException("WebSocket handshake timed out")),
                            !initial);
                      }
                    }
                  },
                  HANDSHAKE_TIMEOUT_MILLIS);
        }
      } else {
        openedRequest.cancel();
      }
    } catch (RuntimeException failure) {
      disconnectCurrent(classify(failure, TransportFailure.Kind.NETWORK), !initial);
    }
  }

  private HyperliquidTransport.SocketCallback socketCallback(
      final long callbackGeneration, final boolean callbackInitial) {
    final Listener callbackListener = listener;
    return new HyperliquidTransport.SocketCallback() {
      @Override
      public void onOpen(final HyperliquidTransport.Socket openedSocket) {
        stateSubmitter.accept(
            new Runnable() {
              @Override
              public void run() {
                socketOpened(callbackGeneration, callbackInitial, openedSocket);
              }
            });
      }

      @Override
      public void onText(String text) {
        if (frameListenerGeneration.accepts(callbackGeneration)) {
          callbackListener.onFrame(callbackGeneration, text);
        }
      }

      @Override
      public void onClose(final int code, final String reason) {
        stateSubmitter.accept(
            new Runnable() {
              @Override
              public void run() {
                socketClosed(callbackGeneration, code, reason);
              }
            });
      }

      @Override
      public void onFailure(final Throwable failure) {
        stateSubmitter.accept(
            new Runnable() {
              @Override
              public void run() {
                socketFailed(callbackGeneration, failure);
              }
            });
      }
    };
  }

  private void socketOpened(
      long openingGeneration, boolean openingInitial, HyperliquidTransport.Socket openedSocket) {
    if (!isCurrentGeneration(openingGeneration) || socketOpened || closed) {
      openedSocket.close(1000, "stale connector generation");
      return;
    }
    removeExpiredSubscriptions(clock.getAsLong());
    if (!openingInitial && connectionPermit.reservedFramesRemaining() > desired.size() + 1) {
      openedSocket.close(1000, "reconnect reservation changed");
      replaceReconnectOpening();
      return;
    }
    socket = openedSocket;
    socketOpened = true;
    frameListenerGeneration.open(openingGeneration);
    cancel(handshakeDeadline);
    handshakeDeadline = null;
    connectRequest = null;
    listener.onSocketOpened(openingGeneration);
    if (!openingInitial) {
      for (SubscriptionKey key : desired.keySet()) {
        sendWhenPossible(
            new OutboundMessage(OutboundMessage.Kind.SUBSCRIBE, key, key.subscribeJson()),
            openingGeneration,
            activationDeadlineFor(key),
            connectionPermit);
      }
      if (desired.isEmpty()) {
        reconnectAttempt = 0;
      }
    } else {
      for (SubscriptionKey key : desired.keySet()) {
        sendWhenPossible(
            new OutboundMessage(OutboundMessage.Kind.SUBSCRIBE, key, key.subscribeJson()),
            openingGeneration,
            activationDeadlineFor(key),
            null);
      }
    }
    scheduleHeartbeat(openingGeneration);
  }

  private void socketClosed(long callbackGeneration, int code, String reason) {
    if (isCurrentGeneration(callbackGeneration)) {
      disconnectCurrent(
          new TransportFailure(
              TransportFailure.Kind.REMOTE, "WebSocket closed " + code + ": " + reason, null),
          !initialConnection || socketOpened);
    }
  }

  private void socketFailed(long callbackGeneration, Throwable failure) {
    if (isCurrentGeneration(callbackGeneration)) {
      disconnectCurrent(
          classify(failure, TransportFailure.Kind.NETWORK), !initialConnection || socketOpened);
    }
  }

  private void sendWhenPossible(
      OutboundMessage message,
      long sendGeneration,
      long activationDeadlineMillis,
      ConnectionPermit reservedConnection) {
    if (!isCurrentOpenGeneration(sendGeneration) || !isMessageRelevant(message)) {
      return;
    }
    long now = clock.getAsLong();
    if (now >= activationDeadlineMillis) {
      if (message.kind() == OutboundMessage.Kind.SUBSCRIBE) {
        desired.remove(message.subscription());
        activationDeadlines.remove(message.subscription());
      }
      return;
    }
    if (reservedConnection != null) {
      PendingSend pending =
          new PendingSend(
              message, sendGeneration, activationDeadlineMillis, null, reservedConnection);
      pendingSends.add(pending);
      startSend(pending);
      return;
    }
    Decision<FrameReservation> decision = budget.tryAcquireFrames(now, 1);
    if (!decision.acquired()) {
      final PendingSend retry =
          new PendingSend(message, sendGeneration, activationDeadlineMillis, null, null);
      pendingSends.add(retry);
      long retryAt = Math.max(now + RETRY_FLOOR_MILLIS, decision.retryAtMillis());
      retry.retryTask =
          scheduleState(
              new Runnable() {
                @Override
                public void run() {
                  pendingSends.remove(retry);
                  if (!retry.cancelled) {
                    sendWhenPossible(
                        retry.message, retry.generation, retry.activationDeadlineMillis, null);
                  }
                }
              },
              retryAt - now);
      return;
    }
    PendingSend pending =
        new PendingSend(message, sendGeneration, activationDeadlineMillis, decision.permit(), null);
    pendingSends.add(pending);
    startSend(pending);
  }

  private void startSend(final PendingSend pending) {
    if (pending.cancelled
        || !isCurrentOpenGeneration(pending.generation)
        || !isMessageRelevant(pending.message)) {
      pending.close();
      pendingSends.remove(pending);
      return;
    }
    long sendStartedAtMillis = clock.getAsLong();
    boolean taken =
        pending.reservedConnection != null
            ? pending.reservedConnection.takeReservedFrame(sendStartedAtMillis)
            : pending.frameReservation.takeFrame(sendStartedAtMillis);
    if (!taken) {
      pending.close();
      pendingSends.remove(pending);
      disconnectCurrent(protocolFailure("outbound frame reservation was unavailable", null), true);
      return;
    }
    final long capturedSentAt = sendStartedAtMillis;
    try {
      socket.send(
          pending.message.body(),
          new HyperliquidTransport.SendCallback() {
            @Override
            public void onSuccess() {
              stateSubmitter.accept(
                  new Runnable() {
                    @Override
                    public void run() {
                      sendSucceeded(pending, capturedSentAt);
                    }
                  });
            }

            @Override
            public void onFailure(final Throwable failure) {
              stateSubmitter.accept(
                  new Runnable() {
                    @Override
                    public void run() {
                      sendFailed(pending, failure);
                    }
                  });
            }
          });
    } catch (Throwable failure) {
      sendFailed(pending, failure);
    }
  }

  private void sendSucceeded(PendingSend pending, long sentAtMillis) {
    if (pending.completed) {
      return;
    }
    pending.completed = true;
    pendingSends.remove(pending);
    pending.close();
    if (pending.cancelled || !isCurrentOpenGeneration(pending.generation)) {
      return;
    }
    if (pending.message.kind() == OutboundMessage.Kind.PING) {
      lastPingAtMillis = sentAtMillis;
      schedulePongDeadline(pending.generation);
    } else if (pending.message.kind() == OutboundMessage.Kind.SUBSCRIBE) {
      activationDeadlines.remove(pending.message.subscription());
    }
    listener.onFrameSent(pending.generation, pending.message, sentAtMillis);
  }

  private void sendFailed(PendingSend pending, Throwable failure) {
    if (pending.completed) {
      return;
    }
    pending.completed = true;
    pendingSends.remove(pending);
    pending.close();
    if (!pending.cancelled && isCurrentGeneration(pending.generation)) {
      disconnectCurrent(classify(failure, TransportFailure.Kind.NETWORK), true);
    }
  }

  private void scheduleHeartbeat(final long heartbeatGeneration) {
    cancel(heartbeat);
    heartbeat =
        scheduleState(
            new Runnable() {
              @Override
              public void run() {
                if (!isCurrentOpenGeneration(heartbeatGeneration)) {
                  return;
                }
                sendWhenPossible(
                    new OutboundMessage(OutboundMessage.Kind.PING, null, "{\"method\":\"ping\"}"),
                    heartbeatGeneration,
                    Long.MAX_VALUE,
                    initialConnection ? null : connectionPermit);
                scheduleHeartbeat(heartbeatGeneration);
              }
            },
            HEARTBEAT_INTERVAL_MILLIS);
  }

  private void schedulePongDeadline(final long pingGeneration) {
    cancel(pongDeadline);
    pongDeadline =
        scheduleState(
            new Runnable() {
              @Override
              public void run() {
                if (isCurrentOpenGeneration(pingGeneration)
                    && lastPongAtMillis < lastPingAtMillis) {
                  disconnectCurrent(
                      new TransportFailure(
                          TransportFailure.Kind.NETWORK, "Hyperliquid pong timed out", null),
                      true);
                }
              }
            },
            PONG_TIMEOUT_MILLIS);
  }

  private void disconnectCurrent(TransportFailure failure, boolean reconnect) {
    if (!generationActive) {
      return;
    }
    long disconnectedGeneration = generation;
    boolean wasOpened = socketOpened;
    generationActive = false;
    socketOpened = false;
    frameListenerGeneration.clear();
    cancel(connectRequest);
    connectRequest = null;
    cancel(handshakeDeadline);
    handshakeDeadline = null;
    cancel(heartbeat);
    heartbeat = null;
    cancel(pongDeadline);
    pongDeadline = null;
    cancelPendingSends();
    if (socket != null && socket.isOpen()) {
      socket.close(1001, "connector reconnecting");
    }
    socket = null;
    if (connectionPermit != null) {
      connectionPermit.close();
      connectionPermit = null;
    }
    if (wasOpened) {
      listener.onDisconnected(disconnectedGeneration, failure);
    }
    if (closed) {
      return;
    }
    if (!reconnect) {
      reportInitialFailure(failure);
      return;
    }
    scheduleReconnect(failure);
  }

  private void scheduleReconnect(TransportFailure ignored) {
    if (closed || !started || connectionRetry != null) {
      return;
    }
    int index = Math.min(reconnectAttempt, RECONNECT_DELAYS.length - 1);
    reconnectAttempt++;
    connectionRetry =
        scheduleState(
            new Runnable() {
              @Override
              public void run() {
                connectionRetry = null;
                attemptConnection(false);
              }
            },
            RECONNECT_DELAYS[index]);
  }

  private boolean isReconnectOpening() {
    return generationActive && !socketOpened && !initialConnection && connectionPermit != null;
  }

  private void replaceReconnectOpening() {
    if (!isReconnectOpening()) {
      return;
    }
    generationActive = false;
    frameListenerGeneration.clear();
    cancel(connectRequest);
    connectRequest = null;
    cancel(handshakeDeadline);
    handshakeDeadline = null;
    connectionPermit.close();
    connectionPermit = null;
    attemptConnection(false);
  }

  private void scheduleConnectionAttempt(final boolean initial, long retryAtMillis) {
    cancel(connectionRetry);
    long now = clock.getAsLong();
    connectionRetry =
        scheduleState(
            new Runnable() {
              @Override
              public void run() {
                connectionRetry = null;
                attemptConnection(initial);
              }
            },
            Math.max(0L, retryAtMillis - now));
  }

  private CancellableScheduler.Cancellable scheduleState(final Runnable task, long delayMillis) {
    return scheduler.schedule(
        new Runnable() {
          @Override
          public void run() {
            stateSubmitter.accept(task);
          }
        },
        delayMillis);
  }

  private boolean isCurrentGeneration(long candidate) {
    return !closed && generationActive && candidate == generation;
  }

  private boolean isCurrentOpenGeneration(long candidate) {
    return isCurrentGeneration(candidate) && socketOpened && socket != null && socket.isOpen();
  }

  private boolean isMessageRelevant(OutboundMessage message) {
    if (message.kind() == OutboundMessage.Kind.SUBSCRIBE) {
      return desired.containsKey(message.subscription());
    }
    return true;
  }

  private long activationDeadlineFor(SubscriptionKey key) {
    Long deadline = activationDeadlines.get(key);
    return deadline == null ? Long.MAX_VALUE : deadline.longValue();
  }

  private void removeExpiredSubscriptions(long nowMillis) {
    List<SubscriptionKey> expired = new ArrayList<SubscriptionKey>();
    for (Map.Entry<SubscriptionKey, Long> entry : activationDeadlines.entrySet()) {
      if (nowMillis >= entry.getValue().longValue()) {
        expired.add(entry.getKey());
      }
    }
    for (SubscriptionKey key : expired) {
      desired.remove(key);
      activationDeadlines.remove(key);
      cancelSubscriptionSends(key);
    }
  }

  private void cancelSubscriptionSends(SubscriptionKey key) {
    List<PendingSend> snapshot = new ArrayList<PendingSend>(pendingSends);
    for (PendingSend pending : snapshot) {
      if (key.equals(pending.message.subscription())) {
        pending.cancelled = true;
        pending.close();
        pendingSends.remove(pending);
      }
    }
  }

  private void cancelPendingSends() {
    List<PendingSend> snapshot = new ArrayList<PendingSend>(pendingSends);
    pendingSends.clear();
    for (PendingSend pending : snapshot) {
      pending.cancelled = true;
      pending.close();
    }
  }

  private void reportInitialFailure(TransportFailure failure) {
    if (!initialFailureReported && listener != null) {
      initialFailureReported = true;
      listener.onInitialFailure(failure);
    }
  }

  private void closeOnStateLane() {
    if (closed) {
      return;
    }
    closed = true;
    cancel(metadataRequest);
    metadataRequest = null;
    cancel(connectionRetry);
    connectionRetry = null;
    disconnectCurrent(protocolFailure("connector closed", null), false);
    transport.close();
  }

  private void cancel(HyperliquidTransport.Cancellable cancellable) {
    if (cancellable != null) {
      cancellable.cancel();
    }
  }

  private void cancel(CancellableScheduler.Cancellable cancellable) {
    if (cancellable != null) {
      cancellable.cancel();
    }
  }

  private TransportFailure classify(Throwable failure, TransportFailure.Kind fallback) {
    TransportFailure.Kind kind =
        isNetworkFailure(failure) ? TransportFailure.Kind.NETWORK : fallback;
    String message = failure == null ? null : failure.getMessage();
    return new TransportFailure(kind, message, failure);
  }

  private boolean isNetworkFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof UnknownHostException
          || current instanceof NoRouteToHostException
          || current instanceof SocketException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private TransportFailure protocolFailure(String message, Throwable cause) {
    return new TransportFailure(TransportFailure.Kind.PROTOCOL, message, cause);
  }

  /** Tracks a held frame reservation until the send starts or the operation becomes irrelevant. */
  private static final class PendingSend {
    private final OutboundMessage message;
    private final long generation;
    private final long activationDeadlineMillis;
    private final FrameReservation frameReservation;
    private final ConnectionPermit reservedConnection;
    private CancellableScheduler.Cancellable retryTask;
    private boolean cancelled;
    private boolean completed;

    private PendingSend(
        OutboundMessage message,
        long generation,
        long activationDeadlineMillis,
        FrameReservation frameReservation,
        ConnectionPermit reservedConnection) {
      this.message = message;
      this.generation = generation;
      this.activationDeadlineMillis = activationDeadlineMillis;
      this.frameReservation = frameReservation;
      this.reservedConnection = reservedConnection;
    }

    private void close() {
      if (retryTask != null) {
        retryTask.cancel();
      }
      if (frameReservation != null) {
        frameReservation.close();
      }
    }
  }

  /** Safely publishes only the immutable-generation frame-delivery gate across callback threads. */
  private static final class FrameGenerationGate {
    private final AtomicLong acceptedGeneration = new AtomicLong(-1L);

    private boolean accepts(long candidate) {
      return acceptedGeneration.get() == candidate;
    }

    private void open(long accepted) {
      acceptedGeneration.set(accepted);
    }

    private void clear() {
      acceptedGeneration.set(-1L);
    }
  }
}
