package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests strict Hyperliquid metadata parsing. */
public class HyperliquidMetaParserTest {

  @Test
  public void parsesValidUniverseAndFiltersOnlyAfterValidation() throws Exception {
    String json =
        "{\"universe\":["
            + "{\"name\":\"BTC\",\"szDecimals\":5},"
            + "{\"name\":\"ETH\",\"szDecimals\":4,\"isDelisted\":false},"
            + "{\"name\":\"OLD\",\"szDecimals\":2,\"isDelisted\":true}]}";

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(Arrays.asList("BTC", "ETH"), symbols(result));
  }

  @Test
  public void rejectsMissingOrNonArrayUniverse() {
    assertRejected("{}");
    assertRejected("{\"universe\":{}}");
  }

  @Test
  public void rejectsMissingBlankOrDuplicateNames() {
    assertRejected("{\"universe\":[{\"szDecimals\":1}]}");
    assertRejected("{\"universe\":[{\"name\":\"  \",\"szDecimals\":1}]}");
    assertRejected(
        "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
            + "{\"name\":\"BTC\",\"szDecimals\":2}]}");
  }

  @Test
  public void rejectsNonIntegralOrOutOfRangeSizeDecimals() {
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1.5}]}");
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":\"1\"}]}");
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":-1}]}");
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":7}]}");
  }

  @Test
  public void rejectsNonBooleanDelistedFlag() {
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1,\"isDelisted\":\"false\"}]}");
  }

  @Test
  public void validatesDelistedMembersBeforeFiltering() {
    assertRejected(
        "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
            + "{\"name\":\"OLD\",\"szDecimals\":7,\"isDelisted\":true}]}");
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
      new HyperliquidMetaParser().parse(json);
      fail("Expected ProtocolException");
    } catch (ProtocolException expected) {
      // Expected invalid metadata rejection.
    }
  }
}
