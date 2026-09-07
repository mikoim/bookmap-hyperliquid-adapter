package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.NormalizedBookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.book.OrderBookSnapshotDiff.SnapshotValidation;
import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests atomic validation and deterministic diffs of complete book snapshots. */
public class OrderBookSnapshotDiffTest {

  private final OrderBookSnapshotDiff diff =
      new OrderBookSnapshotDiff(new PerpetualInstrument("BTC", 0));

  @Test
  public void emitsBidThenAskDeleteThenUpsertAtAscendingPrices() {
    diff.apply(
        valid(
            snapshot(
                10,
                bids(level("100", "1"), level("101", "2")),
                asks(level("102", "3"), level("103", "4")))),
        false);

    List<DepthUpdate> updates =
        diff.apply(
            valid(
                snapshot(
                    11,
                    bids(level("99", "5"), level("101", "7")),
                    asks(level("103", "8"), level("104", "9")))),
            false);

    assertEquals(
        Arrays.asList(
            depth(true, 100000000, 0),
            depth(true, 99000000, 5),
            depth(true, 101000000, 7),
            depth(false, 102000000, 0),
            depth(false, 103000000, 8),
            depth(false, 104000000, 9)),
        updates);
  }

  @Test
  public void emitsInitialAdditionsAndOmitsUnchangedLevels() {
    NormalizedBookSnapshot initial =
        valid(snapshot(10, bids(level("100", "1")), asks(level("101", "2"))));

    assertEquals(
        Arrays.asList(depth(true, 100000000, 1), depth(false, 101000000, 2)),
        diff.apply(initial, false));
    assertEquals(Collections.<DepthUpdate>emptyList(), diff.apply(initial, false));
  }

  @Test
  public void keepsBidAndAskBaselinesSeparateAtTheSamePrice() {
    diff.apply(valid(snapshot(10, bids(level("100", "1")), asks(level("100", "2")))), false);

    assertEquals(
        Arrays.asList(depth(true, 100000000, 3), depth(false, 100000000, 4)),
        diff.apply(valid(snapshot(11, bids(level("100", "3")), asks(level("100", "4")))), false));
  }

  @Test
  public void acceptsEqualTimestampsAndRejectsOlderSnapshots() {
    BookSnapshot equal = snapshot(10, bids(level("100", "1")), asks());

    assertEquals(SnapshotValidation.Status.VALID, diff.validate(equal, 10).status());
    SnapshotValidation stale = diff.validate(snapshot(9, bids(level("100", "1")), asks()), 10);
    assertEquals(SnapshotValidation.Status.STALE, stale.status());
    assertNull(stale.snapshot());
  }

  @Test
  public void rejectsNegativeSnapshotTimesEvenWithTheAcceptedTimeSentinel() {
    SnapshotValidation validation =
        diff.validate(snapshot(-1, bids(level("100", "1")), asks()), -1);

    assertEquals(SnapshotValidation.Status.INVALID, validation.status());
    assertNull(validation.snapshot());
  }

  @Test
  public void rejectsTrailingZeroPricesThatNormalizeToTheSameInteger() {
    SnapshotValidation validation =
        diff.validate(snapshot(10, bids(level("100", "1"), level("100.0", "2")), asks()), -1);

    assertEquals(SnapshotValidation.Status.INVALID, validation.status());
  }

  @Test
  public void rejectsZeroNegativeAndNonIntegralSizesWithoutChangingTheBaseline() {
    diff.apply(valid(snapshot(10, bids(level("100", "1")), asks())), false);

    // Negative and non-integral sizes are rejected and don't change the baseline.
    assertInvalidSize("-1");
    assertInvalidSize("1.5");
    assertEquals(
        Collections.<DepthUpdate>emptyList(),
        diff.apply(valid(snapshot(11, bids(level("100", "1")), asks())), false));
  }

  @Test
  public void rejectsPricesOutsideBookmapIntegerRangeWithoutChangingTheBaseline() {
    diff.apply(valid(snapshot(10, bids(level("100", "1")), asks())), false);

    SnapshotValidation validation =
        diff.validate(snapshot(11, bids(level("2147483648", "1")), asks()), 10);
    assertEquals(SnapshotValidation.Status.UNSUPPORTED_PRICE, validation.status());
    assertEquals(
        Collections.<DepthUpdate>emptyList(),
        diff.apply(valid(snapshot(12, bids(level("100", "1")), asks())), false));
  }

  /** A zero-size level in a full snapshot means the level is absent. */
  @Test
  public void treatsZeroSizeSnapshotLevelsAsAbsent() {
    List<DepthUpdate> initial =
        diff.apply(
            valid(snapshot(10, bids(level("100", "1"), level("99", "0")), asks(level("101", "0")))),
            false);
    assertEquals(Collections.singletonList(depth(true, 100000000, 1)), initial);

    List<DepthUpdate> next =
        diff.apply(valid(snapshot(11, bids(level("100", "0")), asks(level("101", "2")))), false);
    assertEquals(Arrays.asList(depth(true, 100000000, 0), depth(false, 101000000, 2)), next);
  }

  @Test
  public void preservesLargeValidSizesUntilApplySaturatesThem() {
    NormalizedBookSnapshot snapshot = valid(snapshot(10, bids(level("100", "2147483648")), asks()));

    assertEquals(new BigDecimal("2147483648"), snapshot.bids().get(Integer.valueOf(100000000)));
    assertEquals(
        Collections.singletonList(depth(true, 100000000, Integer.MAX_VALUE)),
        diff.apply(snapshot, false));
  }

  @Test
  public void fullResyncClearsAndReplaysAnIdenticalBook() {
    NormalizedBookSnapshot book =
        valid(snapshot(10, bids(level("100", "1")), asks(level("101", "2"))));
    diff.apply(book, false);

    assertEquals(
        Arrays.asList(
            depth(true, 100000000, 0),
            depth(true, 100000000, 1),
            depth(false, 101000000, 0),
            depth(false, 101000000, 2)),
        diff.apply(book, true));
  }

  @Test
  public void clearDeletesOnceAndResetDropsTheBaselineSilently() {
    NormalizedBookSnapshot book =
        valid(snapshot(10, bids(level("100", "1")), asks(level("101", "2"))));
    diff.apply(book, false);

    assertEquals(
        Arrays.asList(depth(true, 100000000, 0), depth(false, 101000000, 0)), diff.clear());
    assertEquals(Collections.<DepthUpdate>emptyList(), diff.clear());
    diff.apply(book, false);
    diff.reset();
    assertEquals(
        Arrays.asList(depth(true, 100000000, 1), depth(false, 101000000, 2)),
        diff.apply(book, false));
  }

  /** Matches the aggregation Hyperliquid itself produced for nSigFigs=4 at the same instant. */
  @Test
  public void coarseTickSumsLevelsWithBidsFlooredAndAsksCeiled() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(
                        1,
                        bids(
                            level("87.784", "2.36"),
                            level("87.783", "1.32"),
                            level("87.779", "4.96"),
                            level("87.777", "20.63")),
                        asks(
                            level("87.785", "161.16"),
                            level("87.786", "24.1"),
                            level("87.789", "17.08"))),
                    0)
                .snapshot(),
            false);

    assertEquals(
        Arrays.asList(depth(true, 8777, 2559), depth(true, 8778, 368), depth(false, 8779, 20234)),
        updates);
  }

  @Test
  public void coarseTickEmitsOnlyChangedBucketsAndDeletesEmptiedOnes() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));
    coarse.apply(
        coarse
            .validate(
                snapshot(
                    1,
                    bids(level("87.784", "1"), level("87.783", "1")),
                    asks(level("87.785", "1"))),
                0)
            .snapshot(),
        false);

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(
                        2,
                        bids(level("87.782", "1"), level("87.781", "1")),
                        asks(level("87.791", "1"))),
                    1)
                .snapshot(),
            false);

    assertEquals(Arrays.asList(depth(false, 8779, 0), depth(false, 8780, 100)), updates);
    assertEquals(Arrays.asList(depth(true, 8778, 0), depth(false, 8780, 0)), coarse.clear());
  }

  @Test
  public void bucketSumsSaturateAtIntegerMax() {
    PerpetualInstrument hype = new PerpetualInstrument("HYPE", 2);
    OrderBookSnapshotDiff coarse =
        new OrderBookSnapshotDiff(hype, new PriceBucketer(hype, new BigDecimal("0.01")));

    List<DepthUpdate> updates =
        coarse.apply(
            coarse
                .validate(
                    snapshot(
                        1,
                        bids(level("87.784", "21474836.47"), level("87.783", "21474836.47")),
                        asks()),
                    0)
                .snapshot(),
            false);

    assertEquals(Arrays.asList(depth(true, 8778, Integer.MAX_VALUE)), updates);
  }

  private void assertInvalidSize(String size) {
    SnapshotValidation validation =
        diff.validate(snapshot(11, bids(level("100", size)), asks()), 10);
    assertEquals(SnapshotValidation.Status.INVALID, validation.status());
  }

  private NormalizedBookSnapshot valid(BookSnapshot snapshot) {
    SnapshotValidation validation = diff.validate(snapshot, -1);
    assertEquals(SnapshotValidation.Status.VALID, validation.status());
    return validation.snapshot();
  }

  private static BookSnapshot snapshot(long time, List<BookLevel> bids, List<BookLevel> asks) {
    return new BookSnapshot("BTC", time, bids, asks);
  }

  private static List<BookLevel> bids(BookLevel... levels) {
    return Arrays.asList(levels);
  }

  private static List<BookLevel> asks(BookLevel... levels) {
    return Arrays.asList(levels);
  }

  private static BookLevel level(String price, String size) {
    return new BookLevel(new BigDecimal(price), new BigDecimal(size));
  }

  private static DepthUpdate depth(boolean bid, int price, int size) {
    return new DepthUpdate(bid, price, size);
  }
}
