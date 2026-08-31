package com.bookmap.plugins.layer0.hyperliquid.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The immutable result of parsing one Hyperliquid WebSocket frame. */
public final class ParsedFrame {

  /** Indicates whether a frame was accepted, ignored, or invalid. */
  public enum Disposition {
    ACCEPTED,
    IGNORED,
    INVALID
  }

  private final Disposition disposition;
  private final List<MarketDataEvent> marketEvents;
  private final List<ControlEvent> controlEvents;
  private final List<String> diagnostics;

  /** Creates an immutable parsed-frame result. */
  public ParsedFrame(
      Disposition disposition,
      List<MarketDataEvent> marketEvents,
      List<ControlEvent> controlEvents,
      List<String> diagnostics) {
    this.disposition = disposition;
    this.marketEvents = Collections.unmodifiableList(new ArrayList<MarketDataEvent>(marketEvents));
    this.controlEvents = Collections.unmodifiableList(new ArrayList<ControlEvent>(controlEvents));
    this.diagnostics = Collections.unmodifiableList(new ArrayList<String>(diagnostics));
  }

  /** Creates an accepted frame result. */
  public static ParsedFrame accepted(
      List<MarketDataEvent> marketEvents,
      List<ControlEvent> controlEvents,
      List<String> diagnostics) {
    return new ParsedFrame(Disposition.ACCEPTED, marketEvents, controlEvents, diagnostics);
  }

  /** Creates an ignored frame result with no emitted events. */
  public static ParsedFrame ignored(List<String> diagnostics) {
    return new ParsedFrame(
        Disposition.IGNORED,
        Collections.<MarketDataEvent>emptyList(),
        Collections.<ControlEvent>emptyList(),
        diagnostics);
  }

  /** Creates an invalid frame result with no emitted events. */
  public static ParsedFrame invalid(List<String> diagnostics) {
    return new ParsedFrame(
        Disposition.INVALID,
        Collections.<MarketDataEvent>emptyList(),
        Collections.<ControlEvent>emptyList(),
        diagnostics);
  }

  /** Returns the disposition assigned to this frame. */
  public Disposition disposition() {
    return disposition;
  }

  /** Returns the immutable market-data events emitted by this frame. */
  public List<MarketDataEvent> marketEvents() {
    return marketEvents;
  }

  /** Returns the immutable control events emitted by this frame. */
  public List<ControlEvent> controlEvents() {
    return controlEvents;
  }

  /** Returns non-fatal parse diagnostics associated with this frame. */
  public List<String> diagnostics() {
    return diagnostics;
  }
}
