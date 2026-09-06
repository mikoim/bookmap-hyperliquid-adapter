package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.google.gson.JsonObject;
import org.junit.Test;

/** Tests optional l2Book subscription parameters. */
public class L2BookParametersTest {

  @Test
  public void appendsOnlyNonNullParametersInWireOrder() {
    JsonObject subscription = new JsonObject();
    new L2BookParameters(Integer.valueOf(5), null, Integer.valueOf(2)).appendTo(subscription);

    assertEquals("{\"nSigFigs\":5,\"mantissa\":2}", subscription.toString());
  }

  @Test
  public void noneAppendsNothingAndValuesAreComparable() {
    JsonObject subscription = new JsonObject();
    L2BookParameters.NONE.appendTo(subscription);

    assertEquals("{}", subscription.toString());
    assertEquals(new L2BookParameters(null, null, null), L2BookParameters.NONE);
    assertNotEquals(new L2BookParameters(Integer.valueOf(5), null, null), L2BookParameters.NONE);
  }
}
