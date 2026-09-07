package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Tests strict Hyperliquid metadata parsing. */
public class HyperliquidMetaParserTest {

  @Test
  public void parsesValidUniverseAndFiltersOnlyAfterValidation() throws Exception {
    String json =
        TestMetadata.wrap(
            "{\"universe\":["
                + "{\"name\":\"BTC\",\"szDecimals\":5},"
                + "{\"name\":\"ETH\",\"szDecimals\":4,\"isDelisted\":false},"
                + "{\"name\":\"OLD\",\"szDecimals\":2,\"isDelisted\":true}]}",
            "80203.0",
            "2519.3",
            "0.5");

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(Arrays.asList("BTC", "ETH"), symbols(result));
    assertEquals(new BigDecimal("80203.0"), result.get(0).referencePrice());
    assertEquals(new BigDecimal("2519.3"), result.get(1).referencePrice());
  }

  @Test
  public void referencePriceIsNullWhenMarkPxIsUnusable() throws Exception {
    String meta =
        "{\"universe\":[{\"name\":\"A\",\"szDecimals\":1},{\"name\":\"B\",\"szDecimals\":1},"
            + "{\"name\":\"C\",\"szDecimals\":1},{\"name\":\"D\",\"szDecimals\":1},"
            + "{\"name\":\"E\",\"szDecimals\":1}]}";
    String json =
        "["
            + meta
            + ",[{},{\"markPx\":null},{\"markPx\":\"abc\"},{\"markPx\":\"0\"},\"not-an-object\"]]";

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(5, result.size());
    for (PerpetualInstrument instrument : result) {
      assertNull(instrument.referencePrice());
    }
  }

  @Test
  public void referencePriceIsNullForAbsurdMagnitudes() throws Exception {
    String json =
        TestMetadata.wrap(
            TestMetadata.universe("HUGE", "TINY", "OK"), "1E+999999996", "1E-999999996", "87.785");

    List<PerpetualInstrument> result = new HyperliquidMetaParser().parse(json);

    assertEquals(3, result.size());
    assertNull(result.get(0).referencePrice());
    assertNull(result.get(1).referencePrice());
    assertEquals(new BigDecimal("87.785"), result.get(2).referencePrice());
  }

  @Test
  public void rejectsNonArrayRootWrongLengthAndContextMismatch() {
    assertRejected("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]}");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]}]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},[],[]]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},[]]");
    assertRejected("[{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1}]},{}]");
    assertRejected("[[],[]]");
  }

  @Test
  public void rejectsMissingOrNonArrayUniverse() {
    assertRejected(TestMetadata.wrap("{}"));
    assertRejected(TestMetadata.wrap("{\"universe\":{}}"));
  }

  @Test
  public void rejectsMissingBlankOrDuplicateNames() {
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"szDecimals\":1}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"  \",\"szDecimals\":1}]}"));
    assertRejected(
        TestMetadata.wrap(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1},"
                + "{\"name\":\"BTC\",\"szDecimals\":2}]}"));
  }

  @Test
  public void rejectsNonIntegralOrOutOfRangeSizeDecimals() {
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1.5}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":\"1\"}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":-1}]}"));
    assertRejected(TestMetadata.wrap("{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":7}]}"));
  }

  @Test
  public void rejectsNonBooleanDelistedFlag() {
    assertRejected(
        TestMetadata.wrap(
            "{\"universe\":[{\"name\":\"BTC\",\"szDecimals\":1,\"isDelisted\":\"false\"}]}"));
  }

  @Test
  public void validatesDelistedMembersBeforeFiltering() {
    assertRejected(
        TestMetadata.wrap(
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
      new HyperliquidMetaParser().parse(json);
      fail("Expected ProtocolException");
    } catch (ProtocolException expected) {
      // Expected invalid metadata rejection.
    }
  }
}
