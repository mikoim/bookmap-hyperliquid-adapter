package com.bookmap.plugins.layer0.hyperliquid.book;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.model.ValueConversionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

  private final Instrument instrument;
  private final PriceBucketer bucketer;
  private final TreeMap<Long, Integer> publishedBids = new TreeMap<Long, Integer>();
  private final TreeMap<Long, Integer> publishedAsks = new TreeMap<Long, Integer>();
  private final TreeMap<Integer, Integer> publishedBidBuckets = new TreeMap<Integer, Integer>();
  private final TreeMap<Integer, Integer> publishedAskBuckets = new TreeMap<Integer, Integer>();
  private TreeMap<Long, Integer> stagedBids = new TreeMap<Long, Integer>();
  private TreeMap<Long, Integer> stagedAsks = new TreeMap<Long, Integer>();
  private boolean seeded;

  /** Creates an empty book published on the native grid. */
  public DeltaOrderBook(Instrument instrument) {
    this(instrument, PriceBucketer.identity(instrument));
  }

  /** Creates an empty book whose published levels are bucket totals at the bucketer's tick. */
  public DeltaOrderBook(Instrument instrument, PriceBucketer bucketer) {
    this.instrument = Objects.requireNonNull(instrument, "instrument");
    this.bucketer = Objects.requireNonNull(bucketer, "bucketer");
  }

  /** Returns whether the current generation's seed has been staged. */
  public boolean seeded() {
    return seeded;
  }

  /** Starts a new generation: forgets the staged book and waits for a fresh seed. */
  public void beginGeneration() {
    seeded = false;
    stagedBids = new TreeMap<Long, Integer>();
    stagedAsks = new TreeMap<Long, Integer>();
  }

  /** Stages a complete seed for this generation; zero-size levels are absent. */
  public Result applySeed(BookSnapshot seed) {
    List<NativeLevel> levels;
    try {
      levels = normalize(seed, false);
    } catch (UnsupportedPriceException failure) {
      return new Result(
          Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    TreeMap<Long, Integer> bids = new TreeMap<Long, Integer>();
    TreeMap<Long, Integer> asks = new TreeMap<Long, Integer>();
    for (NativeLevel level : levels) {
      (level.bid ? bids : asks).put(Long.valueOf(level.price), Integer.valueOf(level.size));
    }
    stagedBids = bids;
    stagedAsks = asks;
    seeded = true;
    return new Result(Status.APPLIED, Collections.<DepthUpdate>emptyList(), "");
  }

  /**
   * Folds a delta into the staged book and, when {@code live}, into the published book as well.
   * Removals of prices the book does not hold are ignored and produce no update. Bucket updates are
   * emitted in the order their buckets were first touched by the delta.
   */
  public Result applyDelta(BookSnapshot delta, boolean live) {
    List<NativeLevel> levels;
    try {
      levels = normalize(delta, true);
    } catch (UnsupportedPriceException failure) {
      return new Result(
          Status.UNSUPPORTED_PRICE, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    } catch (InvalidLevelException failure) {
      return new Result(Status.INVALID, Collections.<DepthUpdate>emptyList(), failure.getMessage());
    }
    Set<Integer> touchedBidBuckets = new LinkedHashSet<Integer>();
    Set<Integer> touchedAskBuckets = new LinkedHashSet<Integer>();
    for (NativeLevel level : levels) {
      TreeMap<Long, Integer> staged = level.bid ? stagedBids : stagedAsks;
      TreeMap<Long, Integer> published = level.bid ? publishedBids : publishedAsks;
      Long price = Long.valueOf(level.price);
      if (level.size == 0) {
        if (staged.remove(price) == null) {
          continue;
        }
        if (live) {
          published.remove(price);
        }
      } else {
        staged.put(price, Integer.valueOf(level.size));
        if (live) {
          published.put(price, Integer.valueOf(level.size));
        }
      }
      if (live) {
        (level.bid ? touchedBidBuckets : touchedAskBuckets).add(Integer.valueOf(level.bucket));
      }
    }
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    appendBucketChanges(updates, true, touchedBidBuckets);
    appendBucketChanges(updates, false, touchedAskBuckets);
    return new Result(Status.APPLIED, updates, "");
  }

  /** Recomputes each touched bucket from the published native book and emits only real changes. */
  private void appendBucketChanges(List<DepthUpdate> updates, boolean bid, Set<Integer> touched) {
    TreeMap<Long, Integer> published = bid ? publishedBids : publishedAsks;
    TreeMap<Integer, Integer> buckets = bid ? publishedBidBuckets : publishedAskBuckets;
    for (Integer bucket : touched) {
      int total = bucketTotal(published, bucket.intValue(), bid);
      Integer previous = buckets.get(bucket);
      if (total == 0) {
        if (previous != null) {
          buckets.remove(bucket);
          updates.add(new DepthUpdate(bid, bucket.intValue(), 0));
        }
      } else if (previous == null || previous.intValue() != total) {
        buckets.put(bucket, Integer.valueOf(total));
        updates.add(new DepthUpdate(bid, bucket.intValue(), total));
      }
    }
  }

  private int bucketTotal(TreeMap<Long, Integer> nativeLevels, int bucket, boolean bid) {
    long[] range = bucketer.nativeRange(bucket, bid);
    Collection<Integer> sizes =
        nativeLevels.subMap(Long.valueOf(range[0]), true, Long.valueOf(range[1]), true).values();
    long total = 0L;
    for (Integer size : sizes) {
      total += size.longValue();
    }
    return PriceBucketer.saturate(total);
  }

  private TreeMap<Integer, Integer> bucketize(TreeMap<Long, Integer> nativeLevels, boolean bid) {
    TreeMap<Integer, Long> totals = new TreeMap<Integer, Long>();
    for (Map.Entry<Long, Integer> level : nativeLevels.entrySet()) {
      Integer bucket = Integer.valueOf(bucketOfValidated(bid, level.getKey().longValue()));
      Long total = totals.get(bucket);
      totals.put(
          bucket,
          Long.valueOf((total == null ? 0L : total.longValue()) + level.getValue().longValue()));
    }
    TreeMap<Integer, Integer> buckets = new TreeMap<Integer, Integer>();
    for (Map.Entry<Integer, Long> entry : totals.entrySet()) {
      buckets.put(
          entry.getKey(), Integer.valueOf(PriceBucketer.saturate(entry.getValue().longValue())));
    }
    return buckets;
  }

  /** Every stored native price was bucketed once during normalization, so this cannot fail. */
  private int bucketOfValidated(boolean bid, long nativeUnits) {
    try {
      return bucketer.bucket(bid, nativeUnits);
    } catch (ValueConversionException impossible) {
      throw new IllegalStateException("stored native price cannot be bucketed", impossible);
    }
  }

  /** Publishes every staged level for activation and records the staged book as published. */
  public List<DepthUpdate> publishStaged() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    TreeMap<Integer, Integer> bidBuckets = bucketize(stagedBids, true);
    TreeMap<Integer, Integer> askBuckets = bucketize(stagedAsks, false);
    appendUpserts(updates, true, bidBuckets);
    appendUpserts(updates, false, askBuckets);
    copyStagedToPublished(bidBuckets, askBuckets);
    return Collections.unmodifiableList(updates);
  }

  /**
   * Replaces the published book with the staged book: deletes every published price the staged book
   * lacks, then re-emits every staged level.
   */
  public List<DepthUpdate> replacePublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    TreeMap<Integer, Integer> bidBuckets = bucketize(stagedBids, true);
    TreeMap<Integer, Integer> askBuckets = bucketize(stagedAsks, false);
    appendVanished(updates, true, publishedBidBuckets, bidBuckets);
    appendUpserts(updates, true, bidBuckets);
    appendVanished(updates, false, publishedAskBuckets, askBuckets);
    appendUpserts(updates, false, askBuckets);
    copyStagedToPublished(bidBuckets, askBuckets);
    return Collections.unmodifiableList(updates);
  }

  /** Deletes every published level and empties the published book. */
  public List<DepthUpdate> clearPublished() {
    List<DepthUpdate> updates = new ArrayList<DepthUpdate>();
    for (Integer bucket : publishedBidBuckets.keySet()) {
      updates.add(new DepthUpdate(true, bucket.intValue(), 0));
    }
    for (Integer bucket : publishedAskBuckets.keySet()) {
      updates.add(new DepthUpdate(false, bucket.intValue(), 0));
    }
    publishedBids.clear();
    publishedAsks.clear();
    publishedBidBuckets.clear();
    publishedAskBuckets.clear();
    return Collections.unmodifiableList(updates);
  }

  /** Drops all state without emitting updates. */
  public void reset() {
    beginGeneration();
    publishedBids.clear();
    publishedAsks.clear();
    publishedBidBuckets.clear();
    publishedAskBuckets.clear();
  }

  private void copyStagedToPublished(
      TreeMap<Integer, Integer> bidBuckets, TreeMap<Integer, Integer> askBuckets) {
    publishedBids.clear();
    publishedBids.putAll(stagedBids);
    publishedAsks.clear();
    publishedAsks.putAll(stagedAsks);
    publishedBidBuckets.clear();
    publishedBidBuckets.putAll(bidBuckets);
    publishedAskBuckets.clear();
    publishedAskBuckets.putAll(askBuckets);
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

  private List<NativeLevel> normalize(BookSnapshot snapshot, boolean keepZeroSizes)
      throws UnsupportedPriceException, InvalidLevelException {
    if (snapshot == null) {
      throw new InvalidLevelException("Book is missing");
    }
    List<NativeLevel> levels = new ArrayList<NativeLevel>();
    normalizeSide(snapshot.bids(), true, keepZeroSizes, levels);
    normalizeSide(snapshot.asks(), false, keepZeroSizes, levels);
    return levels;
  }

  private void normalizeSide(
      List<BookLevel> side, boolean bid, boolean keepZeroSizes, List<NativeLevel> out)
      throws UnsupportedPriceException, InvalidLevelException {
    if (side == null) {
      throw new InvalidLevelException("Book side is missing");
    }
    for (BookLevel level : side) {
      if (level == null || level.size() == null) {
        throw new InvalidLevelException("Book level is missing");
      }
      if (!keepZeroSizes && level.size().signum() == 0) {
        // A zero-size level in a seed is simply absent; skip before price conversion so an
        // unrepresentable price on an absent level does not fail the seed.
        continue;
      }
      final long price;
      final int bucket;
      try {
        price = instrument.toDepthPriceUnits(level.price());
        bucket = bucketer.bucket(bid, price);
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
      out.add(new NativeLevel(bid, price, size, bucket));
    }
  }

  /** One validated level on the native grid, with the bucket it maps onto. */
  private static final class NativeLevel {
    private final boolean bid;
    private final long price;
    private final int size;
    private final int bucket;

    private NativeLevel(boolean bid, long price, int size, int bucket) {
      this.bid = bid;
      this.price = price;
      this.size = size;
      this.bucket = bucket;
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
