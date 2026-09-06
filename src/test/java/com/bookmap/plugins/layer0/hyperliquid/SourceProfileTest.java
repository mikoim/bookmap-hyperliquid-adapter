package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.L2BookParameters;
import org.junit.Test;

/** Pins the per-source connection characteristics resolved at login. */
public class SourceProfileTest {

  @Test
  public void hyperliquidFollowsTheSelectedEnvironmentWithoutExtras() {
    SourceProfile profile =
        SourceProfile.of(MarketDataSource.HYPERLIQUID, HyperliquidEnvironment.TESTNET);

    assertEquals(MarketDataSource.HYPERLIQUID, profile.source());
    assertEquals(HyperliquidEnvironment.TESTNET, profile.environment());
    assertEquals("https://api.hyperliquid-testnet.xyz/info", profile.infoUri().toString());
    assertEquals("wss://api.hyperliquid-testnet.xyz/ws", profile.webSocketUri().toString());
    assertTrue(profile.handshakeHeaders().isEmpty());
    assertEquals(L2BookParameters.NONE, profile.l2BookParameters());
    assertEquals(SourceProfile.FeedMode.SNAPSHOT, profile.feedMode());
  }

  @Test
  public void borsaAlwaysUsesMainnetMetadataAndSeedThenDeltaBooks() {
    SourceProfile profile =
        SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.TESTNET);

    assertEquals(HyperliquidEnvironment.MAINNET, profile.environment());
    assertEquals("https://api.hyperliquid.xyz/info", profile.infoUri().toString());
    assertEquals("wss://ws.borsa.cc/", profile.webSocketUri().toString());
    assertTrue(profile.handshakeHeaders().isEmpty());
    assertEquals(L2BookParameters.NONE, profile.l2BookParameters());
    assertEquals(SourceProfile.FeedMode.SEED_THEN_DELTA, profile.feedMode());
  }

  @Test
  public void hyperdashSendsBrowserHeadersAndFiveSignificantFigures() {
    SourceProfile profile =
        SourceProfile.of(MarketDataSource.HYPERDASH, HyperliquidEnvironment.TESTNET);

    assertEquals(HyperliquidEnvironment.MAINNET, profile.environment());
    assertEquals("https://api.hyperliquid.xyz/info", profile.infoUri().toString());
    assertEquals("wss://api.hyperdash.com/ws/orderbook", profile.webSocketUri().toString());
    assertEquals("https://hyperdash.com", profile.handshakeHeaders().get("Origin"));
    assertTrue(profile.handshakeHeaders().get("User-Agent").startsWith("Mozilla/5.0"));
    assertEquals(2, profile.handshakeHeaders().size());
    assertEquals(Integer.valueOf(5), profile.l2BookParameters().nSigFigs());
    assertNull(profile.l2BookParameters().nLevels());
    assertNull(profile.l2BookParameters().mantissa());
    assertEquals(SourceProfile.FeedMode.SNAPSHOT, profile.feedMode());
  }

  @Test
  public void dropdownValuesResolveCaseInsensitivelyAndFallBackToHyperliquid() {
    assertEquals(MarketDataSource.BORSA, MarketDataSource.fromFieldValue("borsa"));
    assertEquals(MarketDataSource.HYPERDASH, MarketDataSource.fromFieldValue("Hyperdash"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue("Hyperliquid"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue("unknown"));
    assertEquals(MarketDataSource.HYPERLIQUID, MarketDataSource.fromFieldValue(null));
    assertEquals("Hyperliquid", MarketDataSource.fieldValues()[0]);
    assertEquals(3, MarketDataSource.fieldValues().length);
  }
}
