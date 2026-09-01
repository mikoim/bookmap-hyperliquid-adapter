package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;

/** Public commands accepted by one Hyperliquid market-data session. */
public interface HyperliquidSessionApi extends AutoCloseable {

  /** Starts metadata discovery and the WebSocket connection for an environment. */
  void login(HyperliquidEnvironment environment);

  /** Starts perpetual book and trade delivery for one symbol. */
  void subscribe(String symbol, String exchange, String type);

  /** Stops market-data delivery for one subscribed alias. */
  void unsubscribe(String alias);

  /** Permanently closes this session and its connector. */
  @Override
  void close();
}
