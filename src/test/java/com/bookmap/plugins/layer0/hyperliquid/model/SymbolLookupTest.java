package com.bookmap.plugins.layer0.hyperliquid.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/** Covers resolving a requested symbol after Bookmap has changed its case. */
public class SymbolLookupTest {

  /** Two instruments differing only by case cannot be told apart, so neither is guessed at. */
  @Test
  public void ambiguousRequestResolvesToNothing() {
    Map<String, Instrument> bySymbol = new LinkedHashMap<String, Instrument>();
    bySymbol.put("knetiq:ES", Instrument.perpetual("knetiq:ES", 2));
    bySymbol.put("KNETIQ:ES", Instrument.perpetual("KNETIQ:ES", 2));

    assertNull(SymbolLookup.resolve(bySymbol, "KNETIQ:es"));
  }

  /** An exact match is never displaced by another entry that only differs in case. */
  @Test
  public void exactMatchWinsOverACaseInsensitiveOne() {
    Map<String, Instrument> bySymbol = new LinkedHashMap<String, Instrument>();
    bySymbol.put("knetiq:ES", Instrument.perpetual("knetiq:ES", 2));
    bySymbol.put("KNETIQ:ES", Instrument.perpetual("KNETIQ:ES", 2));

    assertEquals("KNETIQ:ES", SymbolLookup.resolve(bySymbol, "KNETIQ:ES").symbol());
  }
}
