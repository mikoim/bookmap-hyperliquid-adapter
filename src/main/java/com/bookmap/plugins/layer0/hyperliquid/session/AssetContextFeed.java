package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;
import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.OutboundMessage;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMessageParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Owns the extra Hyperliquid Mainnet connection a relay source needs, because Borsa and Hyperdash
 * both reject the fastAssetCtxs subscription. Its failures never reach the session sink: mark
 * prices only refine tick-size candidates, so a broken feed leaves the last known values in place.
 *
 * <p>The feed is its own connector listener rather than a branch of the session's control path,
 * because the two connectors number their generations independently: a pong routed through the
 * session would be gated on the market-data generation, dropped, and the feed would reconnect on
 * every heartbeat.
 */
final class AssetContextFeed implements HyperliquidConnector.Listener, AutoCloseable {

  private final HyperliquidConnector connector;
  private final HyperliquidMessageParser parser;
  private final HyperliquidSession session;
  private final Consumer<Runnable> stateLane;
  private final Consumer<String> diagnostics;

  private long currentGeneration = -1L;
  private boolean snapshotPending;
  private boolean failureReported;
  private boolean closed;

  AssetContextFeed(
      HyperliquidConnector connector,
      HyperliquidMessageParser parser,
      HyperliquidSession session,
      Consumer<Runnable> stateLane,
      Consumer<String> diagnostics) {
    this.connector = connector;
    this.parser = parser;
    this.session = session;
    this.stateLane = stateLane;
    this.diagnostics = diagnostics;
  }

  /** Connects to Hyperliquid Mainnet without requesting metadata. */
  void start() {
    connector.setListener(this);
    connector.startWithoutMetadata(
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.MAINNET));
  }

  @Override
  public void close() {
    closed = true;
    connector.close();
  }

  @Override
  public void onMetadata(List<PerpetualInstrument> instruments) {
    diagnostics.accept("asset-context feed reported unexpected metadata");
  }

  @Override
  public void onInitialFailure(TransportFailure failure) {
    if (closed) {
      return;
    }
    // A connection that never opened is not fatal here, unlike on the market-data path where an
    // initial failure means login failed; keep trying on the connector's own backoff.
    reportOnce("asset-context feed could not connect", failure);
    connector.retryAfterInitialFailure();
  }

  @Override
  public void onSocketOpened(long generation) {
    currentGeneration = generation;
    snapshotPending = true;
    failureReported = false;
  }

  @Override
  public void onFrame(final long generation, String json) {
    // Runs on the WebSocket callback thread: parse here, but publish only through the state lane.
    ParsedFrame frame = parser.parse(json);
    for (final String diagnostic : frame.diagnostics()) {
      stateLane.accept(
          new Runnable() {
            @Override
            public void run() {
              diagnostics.accept("asset-context feed: " + diagnostic);
            }
          });
    }
    for (ControlEvent event : frame.controlEvents()) {
      if (event.kind() == ControlEvent.Kind.PONG) {
        connector.acceptPong(generation);
      } else if (event.kind() == ControlEvent.Kind.ASSET_CONTEXTS) {
        submit(generation, event.markPrices());
      } else if (event.kind() == ControlEvent.Kind.SUBSCRIPTION_ERROR) {
        // A rejection here only freezes mark prices, so it stays a diagnostic and never reaches
        // the market-data path's handleSubscriptionError, which treats an unknown target as fatal.
        reportOnceOnStateLane("asset-context feed subscription rejected");
      }
    }
  }

  @Override
  public void onFrameSent(long generation, OutboundMessage message, long sentAtMillis) {
    // The feed sends nothing whose acknowledgement matters.
  }

  @Override
  public void onDisconnected(long generation, TransportFailure failure) {
    reportOnce("asset-context feed disconnected", failure);
  }

  private void submit(final long generation, final Map<String, BigDecimal> markPrices) {
    stateLane.accept(
        new Runnable() {
          @Override
          public void run() {
            if (generation != currentGeneration) {
              return;
            }
            // Only a frame the session really consumed clears the pending snapshot, so a rejected
            // one leaves the next frame free to seed the store in full.
            if (session.onAssetContexts(markPrices, snapshotPending)) {
              snapshotPending = false;
            }
          }
        });
  }

  /** Hands one incident report to the state lane, since onFrame runs on the callback thread. */
  private void reportOnceOnStateLane(final String message) {
    stateLane.accept(
        new Runnable() {
          @Override
          public void run() {
            reportOnce(message, null);
          }
        });
  }

  private void reportOnce(String message, TransportFailure failure) {
    if (closed || failureReported) {
      return;
    }
    failureReported = true;
    diagnostics.accept(message + (failure == null ? "" : ": " + failure.message()));
  }
}
