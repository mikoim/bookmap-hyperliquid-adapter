package com.bookmap.plugins.layer0.hyperliquid;

import java.net.URI;

/** Hyperliquid API environments and their public market-data endpoints. */
public enum HyperliquidEnvironment {
  MAINNET("https://api.hyperliquid.xyz/info", "wss://api.hyperliquid.xyz/ws"),
  TESTNET("https://api.hyperliquid-testnet.xyz/info", "wss://api.hyperliquid-testnet.xyz/ws");

  private final URI infoUri;
  private final URI webSocketUri;

  HyperliquidEnvironment(String infoUri, String webSocketUri) {
    this.infoUri = URI.create(infoUri);
    this.webSocketUri = URI.create(webSocketUri);
  }

  /** Returns the REST information endpoint. */
  public URI infoUri() {
    return infoUri;
  }

  /** Returns the market-data WebSocket endpoint. */
  public URI webSocketUri() {
    return webSocketUri;
  }
}
