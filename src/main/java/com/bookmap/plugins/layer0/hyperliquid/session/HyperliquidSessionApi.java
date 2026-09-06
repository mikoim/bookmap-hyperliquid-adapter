package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;

/** Public commands accepted by one Hyperliquid market-data session. */
public interface HyperliquidSessionApi extends AutoCloseable {

  /** Starts metadata discovery and the WebSocket connection for a source profile. */
  void login(SourceProfile profile);

  /** Starts perpetual book and trade delivery for one symbol. */
  void subscribe(String symbol, String exchange, String type);

  /** Stops market-data delivery for one subscribed alias. */
  void unsubscribe(String alias);

  /** Permanently closes this session and its connector. */
  @Override
  void close();
}
