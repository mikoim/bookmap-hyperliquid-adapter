package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.util.List;

/** Receives validated Hyperliquid session lifecycle and market-data output. */
public interface SessionSink {

  /** Reports the complete available perpetual-instrument list. */
  void onKnownInstruments(List<PerpetualInstrument> instruments);

  /** Reports an instrument that completed subscription activation. */
  void onInstrumentAdded(PerpetualInstrument instrument);

  /** Reports an active instrument that has been removed. */
  void onInstrumentRemoved(String alias);

  /** Reports an unavailable or unsupported instrument request. */
  void onInstrumentNotFound(String symbol, String exchange, String type);

  /** Reports a duplicate subscription request. */
  void onInstrumentAlreadySubscribed(String symbol, String exchange, String type);

  /** Reports one normalized depth update. */
  void onDepth(String alias, DepthUpdate update);

  /** Reports one normalized trade. */
  void onTrade(String alias, double priceUnits, int sizeUnits, boolean isBuyAggressor);

  /** Reports a user-visible system message. */
  void onSystemMessage(String message, MessageKind kind);

  /** Reports a non-fatal diagnostic. */
  void onDiagnostic(String message);

  /** Reports a successfully opened initial connection. */
  void onLoginSuccessful();

  /** Reports an unrecoverable initial-login failure. */
  void onLoginFailed(LoginFailure failure, String message);

  /** Reports that a previously opened connection was lost. */
  void onConnectionLost(ConnectionFailure failure, String message);

  /** Reports that a lost connection was restored. */
  void onConnectionRestored();
}
