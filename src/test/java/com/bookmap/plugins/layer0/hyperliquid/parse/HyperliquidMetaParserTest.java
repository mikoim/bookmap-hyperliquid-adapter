package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
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

    List<PerpetualInstrument> result = parser.parseAllPerpMetas(json);

    assertEquals(Arrays.asList("BTC", "ETH"), symbols(result));
  }

  /** Reads live instruments from every perp dex, keeping HIP-3 names fully qualified. */
  @Test
  public void readsEveryPerpDexUniverse() throws Exception {
    List<PerpetualInstrument> instruments =
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
    List<PerpetualInstrument> instruments =
        parser.parseAllPerpMetas(
            "[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":2}]},"
                + "{\"universe\":[{\"name\":\"flx:OIL\",\"szDecimals\":2,\"isDelisted\":true}]}]");

    assertEquals(1, instruments.size());
    assertEquals("BTC", instruments.get(0).symbol());
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

  private List<String> symbols(List<PerpetualInstrument> instruments) {
    List<String> result = new ArrayList<String>();
    for (PerpetualInstrument instrument : instruments) {
      result.add(instrument.symbol());
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
