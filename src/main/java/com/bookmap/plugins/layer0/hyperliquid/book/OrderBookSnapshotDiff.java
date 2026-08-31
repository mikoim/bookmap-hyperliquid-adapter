package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Validates complete order-book snapshots and emits deterministic incremental depth changes. */
public final class OrderBookSnapshotDiff {

  private final PerpetualInstrument instrument;
  private SortedMap<Integer, BigDecimal> bids = new TreeMap<Integer, BigDecimal>();
  private SortedMap<Integer, BigDecimal> asks = new TreeMap<Integer, BigDecimal>();

  /** Creates a snapshot differ that uses the supplied instrument's exact conversions. */
  public OrderBookSnapshotDiff(PerpetualInstrument instrument) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
  }

  /**
   * Validates and normalizes a snapshot without changing this differ's saved baseline.
   *
   * @param snapshot complete source snapshot
   * @param lastAcceptedTime time of the last accepted snapshot
   * @return a validation result with a normalized snapshot only when valid
   */
  public SnapshotValidation validate(BookSnapshot snapshot, long lastAcceptedTime) {
    if (snapshot == null) {
      return invalid("Snapshot is missing");
    }
    if (snapshot.time() < 0) {
      return invalid("Snapshot time is invalid");
    }
    if (snapshot.time() < lastAcceptedTime) {
      return new SnapshotValidation(SnapshotValidation.Status.STALE, null, "Snapshot is older");
    }

    try {
      SortedMap<Integer, BigDecimal> normalizedBids = normalize(snapshot.bids());
      SortedMap<Integer, BigDecimal> normalizedAsks = normalize(snapshot.asks());
      return new SnapshotValidation(
          SnapshotValidation.Status.VALID,
          new NormalizedBookSnapshot(snapshot.time(), normalizedBids, normalizedAsks),
          "");
    } catch (UnsupportedPriceException exception) {
      return new SnapshotValidation(
          SnapshotValidation.Status.UNSUPPORTED_PRICE, null, exception.getMessage());
    } catch (InvalidSnapshotException exception) {
      return invalid(exception.getMessage());
    }
  }

  /**
   * Diffs a previously validated snapshot against the saved baseline and replaces that baseline.
   *
   * @param snapshot validated normalized snapshot
   * @param forceFullResync whether every old level must be deleted and every current level replayed
   * @return deterministic deletion and upsert updates
   */
  public List<DepthUpdate> apply(NormalizedBookSnapshot snapshot, boolean forceFullResync) {
    Objects.requireNonNull(snapshot, "snapshot");
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendUpdates(updates, true, bids, snapshot.bids(), forceFullResync);
    appendUpdates(updates, false, asks, snapshot.asks(), forceFullResync);

    bids = new TreeMap<Integer, BigDecimal>(snapshot.bids());
    asks = new TreeMap<Integer, BigDecimal>(snapshot.asks());
    return Collections.unmodifiableList(updates);
  }

  /** Deletes every saved level and empties the baseline. */
  public List<DepthUpdate> clear() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendDeletes(updates, true, bids);
    appendDeletes(updates, false, asks);
    bids = new TreeMap<Integer, BigDecimal>();
    asks = new TreeMap<Integer, BigDecimal>();
    return Collections.unmodifiableList(updates);
  }

  /** Drops the saved baseline without emitting depth updates. */
  public void reset() {
    bids = new TreeMap<Integer, BigDecimal>();
    asks = new TreeMap<Integer, BigDecimal>();
  }

  private SortedMap<Integer, BigDecimal> normalize(List<BookLevel> levels)
      throws InvalidSnapshotException, UnsupportedPriceException {
    if (levels == null) {
      throw new InvalidSnapshotException("Book side is missing");
    }

    SortedMap<Integer, BigDecimal> normalized = new TreeMap<Integer, BigDecimal>();
    for (BookLevel level : levels) {
      if (level == null) {
        throw new InvalidSnapshotException("Book level is missing");
      }
      final int price;
      try {
        price = instrument.toDepthPriceUnits(level.price());
      } catch (ValueConversionException exception) {
        if (exception.reason() == ValueConversionException.Reason.DEPTH_PRICE_OUT_OF_RANGE) {
          throw new UnsupportedPriceException("Price cannot be represented as depth units");
        }
        throw new InvalidSnapshotException("Price is invalid");
      }
      try {
        instrument.toSizeUnits(level.size());
      } catch (ValueConversionException exception) {
        throw new InvalidSnapshotException("Size is invalid");
      }
      if (normalized.containsKey(Integer.valueOf(price))) {
        throw new InvalidSnapshotException("Duplicate normalized price");
      }
      normalized.put(Integer.valueOf(price), level.size());
    }
    return normalized;
  }

  private void appendUpdates(
      List<DepthUpdate> updates,
      boolean bid,
      SortedMap<Integer, BigDecimal> oldLevels,
      SortedMap<Integer, BigDecimal> newLevels,
      boolean forceFullResync) {
    if (forceFullResync) {
      appendDeletes(updates, bid, oldLevels);
      appendUpserts(updates, bid, newLevels, null);
      return;
    }

    for (Map.Entry<Integer, BigDecimal> oldLevel : oldLevels.entrySet()) {
      if (!newLevels.containsKey(oldLevel.getKey())) {
        updates.add(new DepthUpdate(bid, oldLevel.getKey().intValue(), 0));
      }
    }
    appendUpserts(updates, bid, newLevels, oldLevels);
  }

  private void appendDeletes(
      List<DepthUpdate> updates, boolean bid, SortedMap<Integer, BigDecimal> levels) {
    for (Integer price : levels.keySet()) {
      updates.add(new DepthUpdate(bid, price.intValue(), 0));
    }
  }

  private void appendUpserts(
      List<DepthUpdate> updates,
      boolean bid,
      SortedMap<Integer, BigDecimal> newLevels,
      SortedMap<Integer, BigDecimal> oldLevels) {
    for (Map.Entry<Integer, BigDecimal> newLevel : newLevels.entrySet()) {
      BigDecimal oldSize = oldLevels == null ? null : oldLevels.get(newLevel.getKey());
      if (oldSize == null || oldSize.compareTo(newLevel.getValue()) != 0) {
        try {
          updates.add(
              new DepthUpdate(
                  bid, newLevel.getKey().intValue(), instrument.toSizeUnits(newLevel.getValue())));
        } catch (ValueConversionException exception) {
          throw new IllegalStateException("Validated snapshot has an invalid size", exception);
        }
      }
    }
  }

  private SnapshotValidation invalid(String diagnostic) {
    return new SnapshotValidation(SnapshotValidation.Status.INVALID, null, diagnostic);
  }

  private static final class InvalidSnapshotException extends Exception {

    private InvalidSnapshotException(String message) {
      super(message);
    }
  }

  private static final class UnsupportedPriceException extends Exception {

    private UnsupportedPriceException(String message) {
      super(message);
    }
  }

  /** Immutable outcome of validating a complete snapshot. */
  public static final class SnapshotValidation {

    /** Classifies whether a snapshot can be applied. */
    public enum Status {
      VALID,
      INVALID,
      STALE,
      UNSUPPORTED_PRICE
    }

    private final Status status;
    private final NormalizedBookSnapshot snapshot;
    private final String diagnostic;

    /** Creates an immutable validation result. */
    public SnapshotValidation(Status status, NormalizedBookSnapshot snapshot, String diagnostic) {
      this.status = Objects.requireNonNull(status, "status");
      this.snapshot = snapshot;
      this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    /** Returns the validation status. */
    public Status status() {
      return status;
    }

    /** Returns the normalized snapshot when {@link #status()} is {@link Status#VALID}. */
    public NormalizedBookSnapshot snapshot() {
      return snapshot;
    }

    /** Returns a concise diagnostic for a non-valid result. */
    public String diagnostic() {
      return diagnostic;
    }
  }

  /** Immutable snapshot whose prices are normalized to Bookmap depth units. */
  public static final class NormalizedBookSnapshot {

    private final long time;
    private final SortedMap<Integer, BigDecimal> bids;
    private final SortedMap<Integer, BigDecimal> asks;

    /** Creates an immutable normalized snapshot from defensive copies of both sides. */
    public NormalizedBookSnapshot(
        long time, SortedMap<Integer, BigDecimal> bids, SortedMap<Integer, BigDecimal> asks) {
      this.time = time;
      this.bids = immutableCopy(bids, "bids");
      this.asks = immutableCopy(asks, "asks");
    }

    /** Returns the exchange snapshot time in milliseconds. */
    public long time() {
      return time;
    }

    /** Returns normalized bid prices and their original exact sizes in ascending price order. */
    public SortedMap<Integer, BigDecimal> bids() {
      return immutableCopy(bids, "bids");
    }

    /** Returns normalized ask prices and their original exact sizes in ascending price order. */
    public SortedMap<Integer, BigDecimal> asks() {
      return immutableCopy(asks, "asks");
    }

    private static SortedMap<Integer, BigDecimal> immutableCopy(
        SortedMap<Integer, BigDecimal> levels, String name) {
      return Collections.unmodifiableSortedMap(
          new TreeMap<Integer, BigDecimal>(Objects.requireNonNull(levels, name)));
    }
  }
}
