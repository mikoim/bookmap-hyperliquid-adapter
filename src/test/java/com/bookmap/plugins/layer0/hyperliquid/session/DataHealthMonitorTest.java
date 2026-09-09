package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.HyperliquidEnvironment;
import com.bookmap.plugins.layer0.hyperliquid.MarketDataSource;
import com.bookmap.plugins.layer0.hyperliquid.SourceProfile;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import java.util.ArrayDeque;
import java.util.Collections;
import org.junit.Test;

/** Exercises health transitions and timer ownership independently of transport heartbeats. */
public class DataHealthMonitorTest {
  @Test
  public void validUnchangedBooksPostponeWarningAndSymbolsAreIndependent() {
    Fixture fixture = new Fixture();
    fixture.monitor.bookPublished("BTC", 1L);
    fixture.monitor.bookPublished("ETH", 1L);
    fixture.advance(20_000L);
    fixture.monitor.bookPublished("BTC", 1L);
    fixture.advance(10_000L);
    assertEquals(1, fixture.sink.dataStatuses().size());
    assertTrue(fixture.sink.dataStatuses().get(0).contains("symbol=ETH"));
    fixture.advance(20_000L);
    assertEquals(2, fixture.sink.dataStatuses().size());
    assertTrue(fixture.sink.dataStatuses().get(1).contains("symbol=BTC"));
    fixture.advance(90_000L);
    assertEquals(2, fixture.sink.dataStatuses().size());
  }

  @Test
  public void queuedOldTimerCannotLeakAfterRecoveryAndClose() {
    Fixture fixture = new Fixture();
    fixture.monitor.bookPublished("BTC", 1L);
    fixture.now = 30_000L;
    fixture.scheduler.advanceBy(30_000L); // Timer has queued work on the state lane.
    fixture.monitor.resync(Collections.singletonList("BTC"), 1L, "connection lost");
    fixture.monitor.bookPublished("BTC", 2L);
    fixture.drain(); // Old generation's queued callback must not take ownership of the new timer.
    fixture.monitor.close();
    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
    int before = fixture.sink.dataStatuses().size();
    fixture.advance(60_000L);
    assertEquals(before, fixture.sink.dataStatuses().size());
  }

  @Test
  public void outageRetriesAreCoalescedAndBookRecoveryDoesNotEraseTradeGap() {
    Fixture fixture = new Fixture();
    fixture.monitor.bookPublished("BTC", 1L);
    fixture.monitor.tradePublished("BTC", 1L);
    fixture.advance(1_000L);
    fixture.monitor.resync(Collections.singletonList("BTC"), 1L, "connection lost");
    fixture.monitor.resync(Collections.singletonList("BTC"), 2L, "retry failed");
    assertEquals(2, fixture.sink.dataStatuses().size());
    fixture.advance(1_000L);
    fixture.monitor.bookPublished("BTC", 3L);
    assertEquals(3, fixture.sink.dataStatuses().size());
    fixture.advance(1_000L);
    fixture.monitor.tradePublished("BTC", 3L);
    assertEquals(4, fixture.sink.dataStatuses().size());
    String resumed = fixture.sink.dataStatuses().get(3);
    assertTrue(resumed.startsWith("INFO:"));
    assertTrue(resumed.contains("state=TRADE_RESUMED"));
    assertTrue(resumed.contains("possibleGapFrom=1970-01-01T00:00:00Z"));
    assertTrue(resumed.contains("possibleGapTo=1970-01-01T00:00:03Z"));
    assertTrue(resumed.contains("historical trades are not backfilled"));
  }

  private static final class Fixture {
    private long now;
    private final ManualScheduler scheduler = new ManualScheduler();
    private final ArrayDeque<Runnable> stateLane = new ArrayDeque<Runnable>();
    private final RecordingSessionSink sink = new RecordingSessionSink();
    private final DataHealthMonitor monitor =
        new DataHealthMonitor(scheduler, () -> now, stateLane::addLast, sink);

    Fixture() {
      monitor.start(SourceProfile.of(MarketDataSource.BORSA, HyperliquidEnvironment.MAINNET));
    }

    void advance(long millis) {
      now += millis;
      scheduler.advanceBy(millis);
      drain();
    }

    void drain() {
      while (!stateLane.isEmpty()) {
        stateLane.removeFirst().run();
      }
    }
  }
}
