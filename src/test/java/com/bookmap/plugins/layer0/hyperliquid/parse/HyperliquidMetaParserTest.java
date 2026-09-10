package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.Market;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests strict Hyperliquid metadata parsing across every perp dex. */
public class HyperliquidMetaParserTest {

  private final HyperliquidMetaParser parser = new HyperliquidMetaParser();

  @Test
  public void parsesValidUniverseAndFiltersOnlyAfterValidation() throws Exception {
    String json =
        TestMetadata.allPerpMetas(
            "{\"universe\":["
                + "{\"name\":\"BTC\",\"szDecimals\":5},"
                + "{\"name\":\"ETH\",\"szDecimals\":4,\"isDelisted\":false},"
                + "{\"name\":\"OLD\",\"szDecimals\":2,\"isDelisted\":true}]}");

    List<Instrument> result = parser.parseAllPerpMetas(json);

    assertEquals(Arrays.asList("BTC", "ETH"), symbols(result));
  }

  /** Reads live instruments from every perp dex, keeping HIP-3 names fully qualified. */
  @Test
  public void readsEveryPerpDexUniverse() throws Exception {
    List<Instrument> instruments =
        parser.parseAllPerpMetas(
            TestMetadata.allPerpMetas(
                TestMetadata.universe("BTC", "ETH"), TestMetadata.universe("xyz:CL")));

    assertEquals(3, instruments.size());
    assertEquals("BTC", instruments.get(0).symbol());
    assertEquals("xyz:CL", instruments.get(2).symbol());
    assertNull(instruments.get(2).referencePrice());
  }

  /** A perp dex whose whole universe is delisted contributes nothing. */
  @Test
  public void skipsFullyDelistedPerpDexes() throws Exception {
    List<Instrument> instruments =
        parser.parseAllPerpMetas(
            "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]},"
                + "{\"universe\":[{\"name\":\"flx:OIL\",\"szDecimals\":2,\"isDelisted\":true}]}]");

    assertEquals(1, instruments.size());
    assertEquals("BTC", instruments.get(0).symbol());
  }

  /**
   * Zero perp dexes is never a real response, and letting it through would log in with an empty
   * universe and then fail every subscription with an opaque "instrument not found".
   */
  @Test
  public void rejectsAnEmptyRootArray() {
    assertRejected("[]", "perp dex");
  }

  /** All entries delisted is a legitimate response and still logs in, with nothing to list. */
  @Test
  public void acceptsAResponseWhoseEntriesAreAllDelisted() throws Exception {
    List<Instrument> instruments =
        parser.parseAllPerpMetas(
            "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2,\"isDelisted\":true}]},"
                + "{\"universe\":[]}]");

    assertTrue(instruments.toString(), instruments.isEmpty());
  }

  /** Names must be unique across every perp dex, not only inside one. */
  @Test
  public void rejectsDuplicateNamesAcrossPerpDexes() {
    assertRejected(
        TestMetadata.allPerpMetas(TestMetadata.universe("BTC"), TestMetadata.universe("BTC")),
        "duplicate");
  }

  /** Validation happens before delisted entries are dropped. */
  @Test
  public void validatesDelistedEntriesBeforeDroppingThem() {
    assertRejected(
        "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":9,\"isDelisted\":true}]}]", "szDecimals");
  }

  /** A perp dex element must be an object carrying a universe array. */
  @Test
  public void rejectsMalformedPerpDexElements() {
    assertRejected("[[]]", "object");
    assertRejected("[{\"marginTables\":[]}]", "universe");
    assertRejected("{\"universe\":[]}", "array");
  }

  @Test
  public void rejectsMissingOrNonArrayUniverse() {
    assertRejected(TestMetadata.allPerpMetas("{}"));
    assertRejected(TestMetadata.allPerpMetas("{\"universe\":{}}"));
  }

  @Test
  public void rejectsMissingBlankOrDuplicateNames() {
    assertRejected(TestMetadata.allPerpMetas("{\"universe\":[{\"szDecimals\":1}]}"));
    assertRejected(
        TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"  \",\"szDecimals\":1}]}"));
    assertRejected(
        TestMetadata.allPerpMetas(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
                + "{\"name\":\"BTC\",\"szDecimals\":2}]}"));
  }

  @Test
  public void rejectsNonIntegralOrOutOfRangeSizeDecimals() {
    assertRejected(
        TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1.5}]}"));
    assertRejected(
        TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":\"1\"}]}"));
    assertRejected(
        TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":-1}]}"));
    assertRejected(
        TestMetadata.allPerpMetas("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":7}]}"));
  }

  @Test
  public void rejectsNonBooleanDelistedFlag() {
    assertRejected(
        TestMetadata.allPerpMetas(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1,\"isDelisted\":\"false\"}]}"));
  }

  @Test
  public void validatesDelistedMembersBeforeFiltering() {
    assertRejected(
        TestMetadata.allPerpMetas(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
                + "{\"name\":\"OLD\",\"szDecimals\":7,\"isDelisted\":true}]}"));
  }

  /** Spot pairs display as BASE/QUOTE, subscribe by the universe name, and size by the base. */
  @Test
  public void parsesSpotPairsFromTokensAndUniverse() throws Exception {
    String json =
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8)
                + ","
                + TestMetadata.spotToken(1, "PURR", 0)
                + ","
                + TestMetadata.spotToken(2, "HFUN", 2)
                + ","
                + TestMetadata.spotToken(360, "USDT0", 2)
                + ","
                + TestMetadata.spotToken(400, "XAUT0", 2),
            TestMetadata.spotPair("PURR/USDC", 1, 0)
                + ","
                + TestMetadata.spotPair("@1", 2, 0)
                + ","
                + TestMetadata.spotPair("@209", 400, 360));

    List<Instrument> result = parser.parseSpotMeta(json);

    assertEquals(Arrays.asList("PURR/USDC", "HFUN/USDC", "XAUT0/USDT0"), symbols(result));
    assertEquals(Arrays.asList("PURR/USDC", "@1", "@209"), coins(result));
    assertEquals(Market.SPOT, result.get(0).market());
    assertEquals(8, result.get(0).priceDecimals());
    assertEquals(6, result.get(1).priceDecimals());
    assertNull(result.get(2).referencePrice());
  }

  /** An exchange with no spot pairs is a legitimate empty list, not a protocol error. */
  @Test
  public void emptySpotUniverseYieldsNoInstruments() throws Exception {
    assertTrue(parser.parseSpotMeta(TestMetadata.emptySpotMeta()).isEmpty());
    assertTrue(
        parser
            .parseSpotMeta(TestMetadata.spotMeta(TestMetadata.spotToken(0, "USDC", 8), ""))
            .isEmpty());
  }

  @Test
  public void rejectsMalformedSpotMeta() {
    assertSpotRejected("[]", "must be an object");
    assertSpotRejected("{\"universe\":[]}", "tokens must be an array");
    assertSpotRejected("{\"tokens\":[]}", "universe must be an array");
    assertSpotRejected(
        TestMetadata.spotMeta("", TestMetadata.spotPair("@1", 1, 0)), "unknown token index");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(0, "PURR", 0), ""),
        "duplicate index");
    assertSpotRejected(
        TestMetadata.spotMeta(TestMetadata.spotToken(0, "USDC", 9), ""),
        "szDecimals must be between 0 and 8");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            "{\"name\":\"@1\",\"tokens\":[1],\"index\":1}"),
        "two indices");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            TestMetadata.spotPair("@1", 1, 0) + "," + TestMetadata.spotPair("@1", 1, 0)),
        "duplicate name");
    assertSpotRejected(
        TestMetadata.spotMeta(
            TestMetadata.spotToken(0, "USDC", 8) + "," + TestMetadata.spotToken(1, "PURR", 0),
            TestMetadata.spotPair("@1", 1, 0) + "," + TestMetadata.spotPair("@2", 1, 0)),
        "duplicate pair");
    assertSpotRejected("not json", "invalid metadata JSON");
  }

  /** Perp and spot lists merge in order; a repeated symbol or coin is a protocol error. */
  @Test
  public void combineRejectsRepeatedSymbolsAndCoins() throws Exception {
    List<Instrument> perps = Arrays.asList(Instrument.perpetual("BTC", 5));
    List<Instrument> spots = Arrays.asList(Instrument.spot("HYPE/USDC", "@107", 2));

    assertEquals(
        Arrays.asList("BTC", "HYPE/USDC"), symbols(HyperliquidMetaParser.combine(perps, spots)));
    try {
      HyperliquidMetaParser.combine(perps, Arrays.asList(Instrument.spot("BTC", "@5", 2)));
      fail("repeated symbol must be rejected");
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("BTC"));
    }
    try {
      HyperliquidMetaParser.combine(perps, Arrays.asList(Instrument.spot("BTC/USDC", "BTC", 2)));
      fail("repeated coin must be rejected");
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains("BTC"));
    }
  }

  private void assertSpotRejected(String json, String messageFragment) {
    try {
      parser.parseSpotMeta(json);
      fail("expected rejection: " + messageFragment);
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
    }
  }

  private List<String> symbols(List<Instrument> instruments) {
    List<String> result = new ArrayList<String>();
    for (Instrument instrument : instruments) {
      result.add(instrument.symbol());
    }
    return result;
  }

  private static List<String> coins(List<Instrument> instruments) {
    List<String> result = new ArrayList<String>();
    for (Instrument instrument : instruments) {
      result.add(instrument.coin());
    }
    return result;
  }

  private void assertRejected(String json) {
    try {
      parser.parseAllPerpMetas(json);
      fail("Expected ProtocolException");
    } catch (ProtocolException expected) {
      // Expected invalid metadata rejection.
    }
  }

  private void assertRejected(String json, String messageFragment) {
    try {
      parser.parseAllPerpMetas(json);
      fail("expected ProtocolException for " + messageFragment);
    } catch (ProtocolException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(messageFragment));
    }
  }
}
