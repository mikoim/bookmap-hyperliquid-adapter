package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import java.math.BigDecimal;

/** Public commands accepted by one Hyperliquid market-data session. */
public interface HyperliquidSessionApi extends AutoCloseable {

  /** Starts metadata discovery and the WebSocket connection for a source profile. */
  void login(SourceProfile profile);

  /** Starts market data for one instrument at the default tick for its reference price. */
  void subscribe(String symbol, String exchange, String type);

  /**
   * Starts market data for one instrument at the Bookmap tick chosen in the subscribe dialog. A
   * null or unsupported tick falls back to the default tick.
   */
  void subscribe(String symbol, String exchange, String type, BigDecimal tick);

  /** Stops market-data delivery for one subscribed alias. */
  void unsubscribe(String alias);

  /** Permanently closes this session and its connector. */
  @Override
  void close();
}
