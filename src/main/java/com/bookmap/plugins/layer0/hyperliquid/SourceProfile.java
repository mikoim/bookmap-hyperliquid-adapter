package com.bookmap.plugins.layer0.hyperliquid;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable connection characteristics of one selected market-data source. */
public final class SourceProfile {

  /** How a source delivers l2Book frames. */
  public enum FeedMode {
    /** Every frame is a complete snapshot. */
    SNAPSHOT,
    /** The first frame per generation is a complete seed; later frames carry changed levels. */
    SEED_THEN_DELTA
  }

  static final URI BORSA_WEB_SOCKET_URI = URI.create("wss://ws.borsa.cc/");
  static final URI HYPERDASH_WEB_SOCKET_URI = URI.create("wss://api.hyperdash.com/ws/orderbook");
  static final String HYPERDASH_ORIGIN = "https://hyperdash.com";
  static final String BROWSER_USER_AGENT =
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
          + "Chrome/128.0.0.0 Safari/537.36";
  static final int BORSA_LEVELS = 400;

  private final MarketDataSource source;
  private final HyperliquidEnvironment environment;
  private final URI webSocketUri;
  private final Map<String, String> handshakeHeaders;
  private final Integer nLevels;
  private final FeedMode feedMode;

  private SourceProfile(
      MarketDataSource source,
      HyperliquidEnvironment environment,
      URI webSocketUri,
      Map<String, String> handshakeHeaders,
      Integer nLevels,
      FeedMode feedMode) {
    this.source = source;
    this.environment = environment;
    this.webSocketUri = webSocketUri;
    this.handshakeHeaders =
        Collections.unmodifiableMap(new LinkedHashMap<String, String>(handshakeHeaders));
    this.nLevels = nLevels;
    this.feedMode = feedMode;
  }

  /**
   * Resolves the profile for a source. Hyperliquid follows the selected environment; Borsa and
   * Hyperdash relay the Mainnet book, so their metadata always comes from Mainnet. Borsa serves up
   * to {@link #BORSA_LEVELS} levels per side; Hyperdash accepts {@code nSigFigs} but rejects {@code
   * nLevels}.
   */
  public static SourceProfile of(MarketDataSource source, HyperliquidEnvironment environment) {
    if (source == null || environment == null) {
      throw new IllegalArgumentException("source and environment must not be null");
    }
    Map<String, String> noHeaders = Collections.emptyMap();
    switch (source) {
      case BORSA:
        return new SourceProfile(
            source,
            HyperliquidEnvironment.MAINNET,
            BORSA_WEB_SOCKET_URI,
            noHeaders,
            Integer.valueOf(BORSA_LEVELS),
            FeedMode.SEED_THEN_DELTA);
      case HYPERDASH:
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Origin", HYPERDASH_ORIGIN);
        headers.put("User-Agent", BROWSER_USER_AGENT);
        return new SourceProfile(
            source,
            HyperliquidEnvironment.MAINNET,
            HYPERDASH_WEB_SOCKET_URI,
            headers,
            null,
            FeedMode.SNAPSHOT);
      default:
        return new SourceProfile(
            source, environment, environment.webSocketUri(), noHeaders, null, FeedMode.SNAPSHOT);
    }
  }

  /** Returns the selected source. */
  public MarketDataSource source() {
    return source;
  }

  /** Returns the Hyperliquid environment whose REST metadata describes the instruments. */
  public HyperliquidEnvironment environment() {
    return environment;
  }

  /** Returns the REST metadata endpoint. */
  public URI infoUri() {
    return environment.infoUri();
  }

  /** Returns the market-data WebSocket endpoint. */
  public URI webSocketUri() {
    return webSocketUri;
  }

  /** Returns the extra WebSocket handshake headers; empty when none are required. */
  public Map<String, String> handshakeHeaders() {
    return handshakeHeaders;
  }

  /** Returns the l2Book depth to request per side, or null when the source fixes it. */
  public Integer nLevels() {
    return nLevels;
  }

  /** Returns how this source delivers l2Book frames. */
  public FeedMode feedMode() {
    return feedMode;
  }
}
