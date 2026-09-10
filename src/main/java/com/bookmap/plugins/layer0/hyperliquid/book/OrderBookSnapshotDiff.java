package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Validates complete order-book snapshots on the native grid and emits deterministic incremental
 * depth changes at the subscription's tick.
 */
public final class OrderBookSnapshotDiff {

  private final Instrument instrument;
  private final PriceBucketer bucketer;
  private SortedMap<Integer, BigDecimal> bids = new TreeMap<Integer, BigDecimal>();
  private SortedMap<Integer, BigDecimal> asks = new TreeMap<Integer, BigDecimal>();

  /** Creates a differ that publishes on the native grid (identity bucketing). */
  public OrderBookSnapshotDiff(Instrument instrument) {
    this(instrument, PriceBucketer.identity(instrument));
  }

  /** Creates a differ that publishes bucket totals at the bucketer's tick. */
  public OrderBookSnapshotDiff(Instrument instrument, PriceBucketer bucketer) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.bucketer = Objects.requireNonNull(bucketer, "bucketer");
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
      SortedMap<Long, BigDecimal> normalizedBids = normalize(snapshot.bids(), true);
      SortedMap<Long, BigDecimal> normalizedAsks = normalize(snapshot.asks(), false);
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
    SortedMap<Integer, BigDecimal> newBids = bucketize(snapshot.bids(), true);
    SortedMap<Integer, BigDecimal> newAsks = bucketize(snapshot.asks(), false);
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendUpdates(updates, true, bids, newBids, forceFullResync);
    appendUpdates(updates, false, asks, newAsks, forceFullResync);

    bids = newBids;
    asks = newAsks;
    return Collections.unmodifiableList(updates);
  }

  /** Sums native levels into buckets of the selected tick; bids floor, asks ceil. */
  private SortedMap<Integer, BigDecimal> bucketize(
      SortedMap<Long, BigDecimal> nativeLevels, boolean bid) {
    SortedMap<Integer, BigDecimal> buckets = new TreeMap<Integer, BigDecimal>();
    for (Map.Entry<Long, BigDecimal> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketOfValidated(bid, level.getKey().longValue()));
      BigDecimal total = buckets.get(bucket);
      buckets.put(bucket, total == null ? level.getValue() : total.add(level.getValue()));
    }
    return buckets;
  }

  /** Every normalized price was bucketed once during validation, so this cannot fail. */
  private int bucketOfValidated(boolean bid, long nativeUnits) {
    try {
      return bucketer.bucket(bid, nativeUnits);
    } catch (ValueConversionException impossible) {
      throw new IllegalStateException("normalized price cannot be bucketed", impossible);
    }
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

  private SortedMap<Long, BigDecimal> normalize(List<BookLevel> levels, boolean bid)
      throws InvalidSnapshotException, UnsupportedPriceException {
    if (levels == null) {
      throw new InvalidSnapshotException("Book side is missing");
    }

    SortedMap<Long, BigDecimal> normalized = new TreeMap<Long, BigDecimal>();
    for (BookLevel level : levels) {
      if (level == null) {
        throw new InvalidSnapshotException("Book level is missing");
      }
      if (level.size() != null && level.size().signum() == 0) {
        // A zero-size level in a complete snapshot is simply absent.
        continue;
      }
      final long price;
      try {
        price = instrument.toDepthPriceUnits(level.price());
        // Result discarded: this call only validates that the bucket fits an int; the actual
        // bucket value is recomputed by bucketize().
        bucketer.bucket(bid, price);
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
      if (normalized.containsKey(Long.valueOf(price))) {
        throw new InvalidSnapshotException("Duplicate normalized price");
      }
      normalized.put(Long.valueOf(price), level.size());
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

  /** Immutable snapshot whose prices are normalized to native long depth units. */
  public static final class NormalizedBookSnapshot {

    private final long time;
    private final SortedMap<Long, BigDecimal> bids;
    private final SortedMap<Long, BigDecimal> asks;

    /** Creates an immutable normalized snapshot from defensive copies of both sides. */
    public NormalizedBookSnapshot(
        long time, SortedMap<Long, BigDecimal> bids, SortedMap<Long, BigDecimal> asks) {
      this.time = time;
      this.bids = immutableCopy(bids, "bids");
      this.asks = immutableCopy(asks, "asks");
    }

    /** Returns the exchange snapshot time in milliseconds. */
    public long time() {
      return time;
    }

    /** Returns normalized bid prices and their original exact sizes in ascending price order. */
    public SortedMap<Long, BigDecimal> bids() {
      return immutableCopy(bids, "bids");
    }

    /** Returns normalized ask prices and their original exact sizes in ascending price order. */
    public SortedMap<Long, BigDecimal> asks() {
      return immutableCopy(asks, "asks");
    }

    private static SortedMap<Long, BigDecimal> immutableCopy(
        SortedMap<Long, BigDecimal> levels, String name) {
      return Collections.unmodifiableSortedMap(
          new TreeMap<Long, BigDecimal>(Objects.requireNonNull(levels, name)));
    }
  }
}
