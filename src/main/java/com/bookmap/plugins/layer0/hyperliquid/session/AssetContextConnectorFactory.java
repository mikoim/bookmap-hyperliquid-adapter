package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidConnector;

/** Construction seam for the extra connector a relay source needs for the asset-context feed. */
public interface AssetContextConnectorFactory {

  /**
   * Creates a connector that shares the session's transport, scheduler, clock, budget and state
   * lane, and that does not own the transport.
   */
  HyperliquidConnector create();
}
