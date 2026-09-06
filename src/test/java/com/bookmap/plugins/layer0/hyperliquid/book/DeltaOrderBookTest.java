package com.bookmap.plugins.layer0.hyperliquid.book;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.model.BookLevel;
import com.bookmap.plugins.layer0.hyperliquid.model.BookSnapshot;
import com.bookmap.plugins.layer0.hyperliquid.model.DepthUpdate;
import com.bookmap.plugins.layer0.hyperliquid.model.PerpetualInstrument;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Tests the seed-then-delta book used for Borsa. Prices use szDecimals=0, so 1 = 1e6 units. */
public class DeltaOrderBookTest {

  private final DeltaOrderBook book = new DeltaOrderBook(new PerpetualInstrument("BTC", 0));

  @Test
  public void seedIsStagedUntilPublishedAndDropsZeroSizeLevels() {
    DeltaOrderBook.Result seed =
        book.applySeed(
            snapshot(1L, levels(level("100", "1"), level("99", "0")), levels(level("101", "2"))));

    assertEquals(DeltaOrderBook.Status.APPLIED, seed.status());
    assertTrue(seed.updates().isEmpty());
    assertTrue(book.seeded());
    assertEquals(
        Arrays.asList(depth(true, 100000000, 1), depth(false, 101000000, 2)), book.publishStaged());
  }

  @Test
  public void liveDeltaUpdatesRemovesAndIgnoresUnknownRemovals() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels(level("101", "2"))));
    book.publishStaged();

    DeltaOrderBook.Result delta =
        book.applyDelta(
            snapshot(
                1L,
                levels(level("100", "0"), level("98", "5"), level("97", "0")),
                levels(level("101", "3"))),
            true);

    assertEquals(DeltaOrderBook.Status.APPLIED, delta.status());
    assertEquals(
        Arrays.asList(
            depth(true, 100000000, 0), depth(true, 98000000, 5), depth(false, 101000000, 3)),
        delta.updates());
    assertEquals(
        Arrays.asList(depth(true, 98000000, 0), depth(false, 101000000, 0)), book.clearPublished());
  }

  @Test
  public void stagedDeltaDuringRecoveryIsAppliedByReplacePublished() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels(level("101", "2"))));
    book.publishStaged();
    book.beginGeneration();
    assertFalse(book.seeded());

    book.applySeed(snapshot(5L, levels(level("100", "1")), levels(level("102", "4"))));
    DeltaOrderBook.Result delta =
        book.applyDelta(snapshot(5L, levels(level("100", "0"), level("99", "7")), levels()), false);

    assertEquals(
        Arrays.asList(
            depth(true, 100000000, 0),
            depth(true, 99000000, 7),
            depth(false, 101000000, 0),
            depth(false, 102000000, 4)),
        book.replacePublished());
    assertEquals(
        Arrays.asList(depth(true, 99000000, 0), depth(false, 102000000, 0)), book.clearPublished());
    assertEquals(DeltaOrderBook.Status.APPLIED, delta.status());
  }

  @Test
  public void newGenerationSeedReplacesPublishedBookEvenWhenUnchanged() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels()));
    book.publishStaged();
    book.beginGeneration();
    book.applySeed(snapshot(0L, levels(level("100", "1")), levels()));

    assertEquals(Collections.singletonList(depth(true, 100000000, 1)), book.replacePublished());
  }

  @Test
  public void unsupportedPriceAndInvalidSizeAreReported() {
    DeltaOrderBook.Result unsupported =
        book.applySeed(snapshot(1L, levels(level("99999999999", "1")), levels()));
    assertEquals(DeltaOrderBook.Status.UNSUPPORTED_PRICE, unsupported.status());
    assertFalse(book.seeded());

    DeltaOrderBook.Result invalid =
        book.applySeed(snapshot(1L, levels(level("100", "0.5")), levels()));
    assertEquals(DeltaOrderBook.Status.INVALID, invalid.status());
    assertFalse(book.seeded());
  }

  @Test
  public void seedSkipsZeroSizeLevelWithUnrepresentablePriceBeforeConversion() {
    DeltaOrderBook.Result seed =
        book.applySeed(
            snapshot(1L, levels(level("99999999999", "0"), level("100", "1")), levels()));

    assertEquals(DeltaOrderBook.Status.APPLIED, seed.status());
    assertTrue(book.seeded());
    assertEquals(Collections.singletonList(depth(true, 100000000, 1)), book.publishStaged());
  }

  @Test
  public void resetForgetsEverythingWithoutUpdates() {
    book.applySeed(snapshot(1L, levels(level("100", "1")), levels()));
    book.publishStaged();

    book.reset();

    assertFalse(book.seeded());
    assertTrue(book.clearPublished().isEmpty());
  }

  private static BookSnapshot snapshot(long time, List<BookLevel> bids, List<BookLevel> asks) {
    return new BookSnapshot("BTC", time, bids, asks);
  }

  private static List<BookLevel> levels(BookLevel... levels) {
    return Arrays.asList(levels);
  }

  private static BookLevel level(String price, String size) {
    return new BookLevel(new BigDecimal(price), new BigDecimal(size));
  }

  private static DepthUpdate depth(boolean bid, int price, int size) {
    return new DepthUpdate(bid, price, size);
  }
}
