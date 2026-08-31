package com.bookmap.plugins.layer0.hyperliquid.parse;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.ControlEvent;
import com.bookmap.plugins.layer0.hyperliquid.model.ParsedFrame;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionKey;
import com.bookmap.plugins.layer0.hyperliquid.model.SubscriptionType;
import com.bookmap.plugins.layer0.hyperliquid.model.TradeEvent;
import java.math.BigDecimal;
import org.junit.Test;

/** Tests strict Hyperliquid WebSocket frame parsing. */
public class HyperliquidMessageParserTest {

  private final HyperliquidMessageParser parser = new HyperliquidMessageParser();

  @Test
  public void expandsTradeArrayInWireOrderAndKeepsLargeTidExact() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"trades\",\"data\":["
                + "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"100.10\",\"sz\":\"0.01\",\"time\":7,"
                + "\"tid\":1125899906842623},{\"coin\":\"BTC\",\"side\":\"A\",\"px\":\"100.20\","
                + "\"sz\":\"0.02\",\"time\":8,\"tid\":2}]}");

    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    assertEquals(2, frame.marketEvents().size());
    TradeEvent first = (TradeEvent) frame.marketEvents().get(0);
    TradeEvent second = (TradeEvent) frame.marketEvents().get(1);
    assertEquals(1125899906842623L, first.tid());
    assertTrue(first.isBuyAggressor());
    assertFalse(second.isBuyAggressor());
    assertEquals(new BigDecimal("100.10"), first.price());
    assertEquals(new BigDecimal("0.02"), second.size());
  }

  @Test
  public void discardsInvalidTradeElementsAndRetainsLaterWireOrder() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"trades\",\"data\":["
                + "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"100\",\"sz\":\"1\",\"time\":1,"
                + "\"tid\":1},"
                + "{\"coin\":\"BTC\",\"side\":\"X\",\"px\":\"100\",\"sz\":\"1\",\"time\":2,"
                + "\"tid\":2},"
                + "{\"coin\":\"ETH\",\"side\":\"A\",\"px\":\"200\",\"sz\":\"2\",\"time\":3,"
                + "\"tid\":3}]}");

    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    assertEquals(2, frame.marketEvents().size());
    assertEquals("BTC", ((TradeEvent) frame.marketEvents().get(0)).coin());
    assertEquals("ETH", ((TradeEvent) frame.marketEvents().get(1)).coin());
    assertEquals(1, frame.diagnostics().size());
  }

  @Test
  public void rejectsInvalidTradeFieldFormsWithoutThrowing() {
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"X\",\"px\":\"1\",\"sz\":\"1\",\"time\":1,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"1\",\"time\":1.5,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"1\",\"time\":-1,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"1\",\"time\":1,\"tid\":1.5}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"1\",\"time\":1,\"tid\":-1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"1\",\"time\":1,"
            + "\"tid\":1125899906842624}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":1,\"sz\":\"1\",\"time\":1,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":1,\"time\":1,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"0\",\"sz\":\"1\",\"time\":1,\"tid\":1}");
    assertInvalidTrade(
        "{\"coin\":\"BTC\",\"side\":\"B\",\"px\":\"1\",\"sz\":\"0\",\"time\":1,\"tid\":1}");
  }

  @Test
  public void parsesBookSnapshotWithAllLevelsAndBothSides() {
    ParsedFrame frame = parser.parse(l2Book("99.10", "101.20", 20, 42));

    assertEquals(ParsedFrame.Disposition.ACCEPTED, frame.disposition());
    assertEquals(1, frame.marketEvents().size());
    BookSnapshot snapshot = (BookSnapshot) frame.marketEvents().get(0);
    assertEquals("BTC", snapshot.coin());
    assertEquals(42L, snapshot.time());
    assertEquals(20, snapshot.bids().size());
    assertEquals(20, snapshot.asks().size());
    assertEquals(new BigDecimal("99.10"), snapshot.bids().get(0).price());
    assertEquals(new BigDecimal("101.20"), snapshot.asks().get(0).price());
  }

  @Test
  public void rejectsWholeSnapshotWhenAnyBookLevelIsInvalid() {
    String json =
        "{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":42,\"levels\":["
            + "[{\"px\":\"99\",\"sz\":\"1\"}],[{\"px\":\"101\",\"sz\":0}] ]}}";

    ParsedFrame frame = parser.parse(json);

    assertEquals(ParsedFrame.Disposition.INVALID, frame.disposition());
    assertTrue(frame.marketEvents().isEmpty());
    assertFalse(frame.diagnostics().isEmpty());
  }

  @Test
  public void rejectsInvalidBookTimeCoinAndLevelStructures() {
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1.5,\"levels\":[[],[]]"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":-1,\"levels\":[[],[]]"));
    assertInvalid(l2BookWith("\"coin\":1,\"time\":1,\"levels\":[[],[]]"));
    assertInvalid(l2BookWith("\"coin\":\"  \",\"time\":1,\"levels\":[[],[]]"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1,\"levels\":{}"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1,\"levels\":[[]]"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1,\"levels\":[[],[],[]]"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1,\"levels\":[{},[]]"));
    assertInvalid(l2BookWith("\"coin\":\"BTC\",\"time\":1,\"levels\":[[1],[]]"));
  }

  @Test
  public void returnsSubscriptionAckAsControlEvent() {
    ParsedFrame frame =
        parser.parse(
            "{\"channel\":\"subscriptionResponse\",\"data\":{"
                + "\"method\":\"subscribe\",\"subscription\":{\"type\":\"l2Book\","
                + "\"coin\":\"BTC\"}}}");

    assertTrue(frame.marketEvents().isEmpty());
    assertEquals(ControlEvent.Kind.SUBSCRIPTION_ACK, frame.controlEvents().get(0).kind());
    assertEquals(
        new SubscriptionKey("BTC", SubscriptionType.L2_BOOK),
        frame.controlEvents().get(0).target());
  }

  @Test
  public void rejectsAckThatCannotMatchSentSubscriptionKey() {
    assertInvalid(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"unsubscribe\","
            + "\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\"}}}");
    assertInvalid(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"unknown\",\"coin\":\"BTC\"}}}");
    assertInvalid(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"l2Book\"}}}");
    assertInvalid(
        "{\"channel\":\"subscriptionResponse\",\"data\":{\"method\":\"subscribe\","
            + "\"subscription\":{\"type\":\"l2Book\",\"coin\":\"BTC\",\"dex\":\"x\"}}}");
  }

  @Test
  public void parsesPongOnlyWhenItIsTheEntireFrame() {
    ParsedFrame frame = parser.parse("{\"channel\":\"pong\"}");

    assertEquals(ControlEvent.Kind.PONG, frame.controlEvents().get(0).kind());
    assertInvalid("{\"channel\":\"pong\",\"data\":{}}");
  }

  @Test
  public void returnsTargetableSubscriptionErrorOnlyForStructuredExactSubscription() {
    ParsedFrame targetable =
        parser.parse(
            "{\"channel\":\"error\",\"data\":{\"subscription\":{"
                + "\"type\":\"trades\",\"coin\":\"BTC\"}}}");
    ParsedFrame unstructured =
        parser.parse("{\"channel\":\"error\",\"data\":\"failed to subscribe BTC trades\"}");

    assertEquals(ControlEvent.Kind.SUBSCRIPTION_ERROR, targetable.controlEvents().get(0).kind());
    assertEquals(
        new SubscriptionKey("BTC", SubscriptionType.TRADES),
        targetable.controlEvents().get(0).target());
    assertEquals(ControlEvent.Kind.SUBSCRIPTION_ERROR, unstructured.controlEvents().get(0).kind());
    assertNull(unstructured.controlEvents().get(0).target());
  }

  @Test
  public void leavesStructuredErrorUntargetedWhenSubscriptionDefinitionIsNotExact() {
    ParsedFrame extraField =
        parser.parse(
            "{\"channel\":\"error\",\"data\":{\"subscription\":{"
                + "\"type\":\"trades\",\"coin\":\"BTC\",\"dex\":\"x\"}}}");
    ParsedFrame unsupportedType =
        parser.parse(
            "{\"channel\":\"error\",\"data\":{\"subscription\":{"
                + "\"type\":\"candle\",\"coin\":\"BTC\"}}}");

    assertEquals(ControlEvent.Kind.SUBSCRIPTION_ERROR, extraField.controlEvents().get(0).kind());
    assertNull(extraField.controlEvents().get(0).target());
    assertEquals(
        ControlEvent.Kind.SUBSCRIPTION_ERROR, unsupportedType.controlEvents().get(0).kind());
    assertNull(unsupportedType.controlEvents().get(0).target());
  }

  @Test
  public void treatsUnknownAndMalformedFramesAsNonThrowingResults() {
    ParsedFrame unknown = parser.parse("{\"channel\":\"futureChannel\",\"data\":{}}");
    ParsedFrame malformed = parser.parse("{");

    assertEquals(ParsedFrame.Disposition.IGNORED, unknown.disposition());
    assertEquals(ParsedFrame.Disposition.INVALID, malformed.disposition());
    assertTrue(unknown.marketEvents().isEmpty());
    assertTrue(malformed.marketEvents().isEmpty());
    assertFalse(unknown.diagnostics().isEmpty());
    assertFalse(malformed.diagnostics().isEmpty());
  }

  @Test
  public void rejectsMalformedTopLevelAndChannelDataShapesWithoutThrowing() {
    assertInvalid(null);
    assertInvalid("null");
    assertInvalid("[]");
    assertInvalid("true");
    assertInvalid("{\"channel\":true}");
    assertInvalid("{\"channel\":\"trades\",\"data\":null}");
    assertInvalid("{\"channel\":\"l2Book\",\"data\":[]}");
    assertInvalid("{\"channel\":\"subscriptionResponse\",\"data\":\"ack\"}");
  }

  private void assertInvalidTrade(String trade) {
    assertInvalid("{\"channel\":\"trades\",\"data\":[" + trade + "]}");
  }

  private void assertInvalid(String json) {
    ParsedFrame frame = parser.parse(json);
    assertEquals(ParsedFrame.Disposition.INVALID, frame.disposition());
    assertTrue(frame.marketEvents().isEmpty());
    assertFalse(frame.diagnostics().isEmpty());
  }

  private String l2Book(String bidPrice, String askPrice, int count, long time) {
    StringBuilder json = new StringBuilder();
    json.append("{\"channel\":\"l2Book\",\"data\":{\"coin\":\"BTC\",\"time\":");
    json.append(time);
    json.append(",\"levels\":[[");
    appendLevels(json, bidPrice, count);
    json.append("],[");
    appendLevels(json, askPrice, count);
    json.append("]]}}");
    return json.toString();
  }

  private String l2BookWith(String dataFields) {
    return "{\"channel\":\"l2Book\",\"data\":{" + dataFields + "}}";
  }

  private void appendLevels(StringBuilder json, String price, int count) {
    for (int index = 0; index < count; index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append("{\"px\":\"").append(price).append("\",\"sz\":\"1\",\"n\":1}");
    }
  }
}
