package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Seed-then-delta order book for one alias: a per-generation staged book that seeds and deltas are
 * folded into, and the book currently published to Bookmap. Timestamp freshness is the caller's
 * responsibility.
 */
public final class DeltaOrderBook {

  /** Classifies one applied frame. */
  public enum Status {
    APPLIED,
    INVALID,
    UNSUPPORTED_PRICE
  }

  /** Immutable outcome of applying one seed or delta frame. */
  public static final class Result {
    private final Status status;
    private final List<DepthUpdate> updates;
    private final String diagnostic;

    private Result(Status status, List<DepthUpdate> updates, String diagnostic) {
      this.status = status;
      this.updates = Collections.unmodifiableList(new ArrayList<DepthUpdate>(updates));
      this.diagnostic = diagnostic;
    }

    /** Returns whether the frame was applied. */
    public Status status() {
      return status;
    }

    /** Returns the depth updates implied by an applied delta; empty for seeds and failures. */
    public List<DepthUpdate> updates() {
      return updates;
    }

    /** Returns a concise diagnostic for a non-applied result. */
    public String diagnostic() {
      return diagnostic;
    }
  }

  private final PerpetualInstrument instrument;
  private final SortedMap<Integer, Integer> publishedBids = new TreeMap<Integer, Integer>();
  private final SortedMap<Integer, Integer> publishedAsks = new TreeMap<Integer, Integer>();
  private SortedMap<Integer, Integer> stagedBids = new TreeMap<Integer, Integer>();
  private SortedMap<Integer, Integer> stagedAsks = new TreeMap<Integer, Integer>();
  private boolean seeded;

  /** Creates an empty book that normalizes prices and sizes with the instrument's rules. */
  public DeltaOrderBook(PerpetualInstrument instrument) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
  }

  /** Returns whether the current generation's seed has been staged. */
  public boolean seeded() {
    return seeded;
  }

  /** Starts a new generation: forgets the staged book and waits for a fresh seed. */
  public void beginGeneration() {
    seeded = false;
    stagedBids = new TreeMap<Integer, Integer>();
    stagedAsks = new TreeMap<Integer, Integer>();
  }

  /** Stages a complete seed for this generation; zero-size levels are absent. */
  public Result applySeed(BookSnapshot seed) {
    List<DepthUpdate> levels;
    try {
      levels = normalize(seed, false);
    } catch (UnsupportedPriceException failure) {
      return new Result(
          Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    SortedMap<Integer, Integer> bids = new TreeMap<Integer, Integer>();
    SortedMap<Integer, Integer> asks = new TreeMap<Integer, Integer>();
    for (DepthUpdate level : levels) {
      (level.bid() ? bids : asks)
          .put(Integer.valueOf(level.price()), Integer.valueOf(level.size()));
    }
    stagedBids = bids;
    stagedAsks = asks;
    seeded = true;
    return new Result(Status.APPLIED, Collections.<DepthUpdate>emptyList(), "");
  }

  /**
   * Folds a delta into the staged book and, when {@code live}, into the published book as well.
   * Removals of prices the book does not hold are ignored and produce no update.
   */
  public Result applyDelta(BookSnapshot delta, boolean live) {
    List<DepthUpdate> levels;
    try {
      levels = normalize(delta, true);
    } catch (UnsupportedPriceException failure) {
      return new Result(
          Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (DepthUpdate level : levels) {
      SortedMap<Integer, Integer> staged = level.bid() ? stagedBids : stagedAsks;
      SortedMap<Integer, Integer> published = level.bid() ? publishedBids : publishedAsks;
      Integer price = Integer.valueOf(level.price());
      if (level.size() == 0) {
        if (staged.remove(price) == null) {
          continue;
        }
        if (live) {
          published.remove(price);
        }
      } else {
        staged.put(price, Integer.valueOf(level.size()));
        if (live) {
          published.put(price, Integer.valueOf(level.size()));
        }
      }
      updates.add(level);
    }
    return new Result(Status.APPLIED, updates, "");
  }

  /** Publishes every staged level for activation and records the staged book as published. */
  public List<DepthUpdate> publishStaged() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendUpserts(updates, true, stagedBids);
    appendUpserts(updates, false, stagedAsks);
    copyStagedToPublished();
    return Collections.unmodifiableList(updates);
  }

  /**
   * Replaces the published book with the staged book: deletes every published price the staged book
   * lacks, then re-emits every staged level.
   */
  public List<DepthUpdate> replacePublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendVanished(updates, true, publishedBids, stagedBids);
    appendUpserts(updates, true, stagedBids);
    appendVanished(updates, false, publishedAsks, stagedAsks);
    appendUpserts(updates, false, stagedAsks);
    copyStagedToPublished();
    return Collections.unmodifiableList(updates);
  }

  /** Deletes every published level and empties the published book. */
  public List<DepthUpdate> clearPublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (Integer price : publishedBids.keySet()) {
      updates.add(new DepthUpdate(true, price.intValue(), 0));
    }
    for (Integer price : publishedAsks.keySet()) {
      updates.add(new DepthUpdate(false, price.intValue(), 0));
    }
    publishedBids.clear();
    publishedAsks.clear();
    return Collections.unmodifiableList(updates);
  }

  /** Drops all state without emitting updates. */
  public void reset() {
    beginGeneration();
    publishedBids.clear();
    publishedAsks.clear();
  }

  private void copyStagedToPublished() {
    publishedBids.clear();
    publishedBids.putAll(stagedBids);
    publishedAsks.clear();
    publishedAsks.putAll(stagedAsks);
  }

  private static void appendUpserts(
      List<DepthUpdate> updates, boolean bid, SortedMap<Integer, Integer> levels) {
    for (Map.Entry<Integer, Integer> level : levels.entrySet()) {
      updates.add(new DepthUpdate(bid, level.getKey().intValue(), level.getValue().intValue()));
    }
  }

  private static void appendVanished(
      List<DepthUpdate> updates,
      boolean bid,
      SortedMap<Integer, Integer> published,
      SortedMap<Integer, Integer> staged) {
    for (Integer price : published.keySet()) {
      if (!staged.containsKey(price)) {
        updates.add(new DepthUpdate(bid, price.intValue(), 0));
      }
    }
  }

  private List<DepthUpdate> normalize(BookSnapshot snapshot, boolean keepZeroSizes)
      throws UnsupportedPriceException, InvalidLevelException {
    if (snapshot == null) {
      throw new InvalidLevelException("Book is missing");
    }
    List<DepthUpdate> levels = new ArrayList<DepthUpdate>();
    normalizeSide(snapshot.bids(), true, keepZeroSizes, levels);
    normalizeSide(snapshot.asks(), false, keepZeroSizes, levels);
    return levels;
  }

  private void normalizeSide(
      List<BookLevel> side, boolean bid, boolean keepZeroSizes, List<DepthUpdate> out)
      throws UnsupportedPriceException, InvalidLevelException {
    if (side == null) {
      throw new InvalidLevelException("Book side is missing");
    }
    for (BookLevel level : side) {
      if (level == null || level.size() == null) {
        throw new InvalidLevelException("Book level is missing");
      }
      final int price;
      try {
        price = instrument.toDepthPriceUnits(level.price());
      } catch (ValueConversionException failure) {
        if (failure.reason() == ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE) {
          throw new UnsupportedPriceException("Price cannot be represented as depth units");
        }
        throw new InvalidLevelException("Price is invalid");
      }
      final int size;
      try {
        size = instrument.toDepthSizeUnits(level.size());
      } catch (ValueConversionException failure) {
        throw new InvalidLevelException("Size is invalid");
      }
      if (size == 0 && !keepZeroSizes) {
        continue;
      }
      out.add(new DepthUpdate(bid, price, size));
    }
  }

  private static final class InvalidLevelException extends Exception {
    private InvalidLevelException(String message) {
      super(message);
    }
  }

  private static final class UnsupportedPriceException extends Exception {
    private UnsupportedPriceException(String message) {
      super(message);
    }
  }
}
