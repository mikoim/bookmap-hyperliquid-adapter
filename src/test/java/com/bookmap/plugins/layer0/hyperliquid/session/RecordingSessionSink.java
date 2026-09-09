package com.bookmap.plugins.layer0.hyperliquid.session;

import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Records session output for focused session integration tests. */
final class RecordingSessionSink implements SessionSink {

  private final List<String> events = new ArrayList<String>();
  private final List<String> addedAliases = new ArrayList<String>();
  private final List<BigDecimal> addedTicks = new ArrayList<BigDecimal>();
  private final List<String> removedAliases = new ArrayList<String>();
  private final List<Trade> trades = new ArrayList<Trade>();
  private final List<String> systemMessages = new ArrayList<String>();
  private final List<String> dataStatuses = new ArrayList<String>();

  List<String> dataStatuses() {
    return dataStatuses;
  }

  @Override
  public void onDataStatus(String message, boolean warning) {
    dataStatuses.add((warning ? "WARN:" : "INFO:") + message);
  }

  private int knownInstrumentPublications;
  private final Map<String, String> lastReferencePrices = new HashMap<String, String>();

  List<String> events() {
    return events;
  }

  int knownInstrumentPublications() {
    return knownInstrumentPublications;
  }

  String lastReferencePrice(String symbol) {
    return lastReferencePrices.get(symbol);
  }

  List<String> addedAliases() {
    return addedAliases;
  }

  List<BigDecimal> addedTicks() {
    return addedTicks;
  }

  List<String> removedAliases() {
    return removedAliases;
  }

  List<Trade> trades() {
    return trades;
  }

  List<String> systemMessages() {
    return systemMessages;
  }

  @Override
  public void onKnownInstruments(List<PerpetualInstrument> instruments) {
    knownInstrumentPublications++;
    lastReferencePrices.clear();
    for (PerpetualInstrument instrument : instruments) {
      if (instrument.referencePrice() != null) {
        lastReferencePrices.put(instrument.symbol(), instrument.referencePrice().toPlainString());
      }
    }
    events.add("known:" + instruments.size());
  }

  @Override
  public void onInstrumentAdded(String alias, PerpetualInstrument instrument, BigDecimal tick) {
    addedAliases.add(alias);
    addedTicks.add(tick);
    events.add("instrument-added:" + alias);
  }

  @Override
  public void onInstrumentRemoved(String alias) {
    removedAliases.add(alias);
    events.add("instrument-removed:" + alias);
  }

  @Override
  public void onInstrumentNotFound(String symbol, String exchange, String type) {
    events.add("not-found:" + symbol);
  }

  @Override
  public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
    events.add("already-subscribed:" + symbol);
  }

  @Override
  public void onDepth(String alias, DepthUpdate update) {
    events.add("depth:" + alias + ":" + update.price() + ":" + update.size());
  }

  @Override
  public void onTrade(String alias, double priceUnits, int sizeUnits, boolean isBuyAggressor) {
    trades.add(new Trade(alias, priceUnits, sizeUnits, isBuyAggressor));
    events.add("trade:" + alias + ":" + priceUnits + ":" + sizeUnits);
  }

  @Override
  public void onSystemMessage(String message, MessageKind kind) {
    systemMessages.add(kind + ":" + message);
  }

  @Override
  public void onDiagnostic(String message) {
    events.add("diagnostic:" + message);
  }

  @Override
  public void onLoginSuccessful() {
    events.add("login-successful");
  }

  @Override
  public void onLoginFailed(LoginFailure failure, String message) {
    events.add("login-failed:" + failure);
  }

  @Override
  public void onConnectionLost(ConnectionFailure failure, String message) {
    events.add("connection-lost:" + failure);
  }

  @Override
  public void onConnectionRestored() {
    events.add("connection-restored");
  }

  static final class Trade {
    private final String alias;
    private final double price;
    private final int size;
    private final boolean isBuyAggressor;

    double price() {
      return price;
    }

    boolean isBuyAggressor() {
      return isBuyAggressor;
    }

    Trade(String alias, double price, int size, boolean isBuyAggressor) {
      this.alias = alias;
      this.price = price;
      this.size = size;
      this.isBuyAggressor = isBuyAggressor;
    }
  }
}
