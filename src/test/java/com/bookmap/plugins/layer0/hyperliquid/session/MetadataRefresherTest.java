package com.bookmap.plugins.layer0.hyperliquid.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bookmap.plugins.layer0.hyperliquid.FakeHyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.MetadataRequest;
import com.bookmap.plugins.layer0.hyperliquid.TestMetadata;
import com.bookmap.plugins.layer0.hyperliquid.concurrent.ManualScheduler;
import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.junit.Test;

/**
 * Pins when the instrument list is re-fetched: every 30 minutes and on demand after a reconnect.
 */
public class MetadataRefresherTest {

  private static final long INTERVAL = 1_800_000L;

  @Test
  public void firstRefreshStartsThirtyMinutesAfterStart() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();

    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);

    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nextRefreshIsThirtyMinutesAfterASuccessfulCompletion() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(INTERVAL);
    fixture.advance(5_000L);
    fixture.transport.completeMeta(200, perps("BTC", "ETH"));

    assertEquals("[[BTC, ETH]]", fixture.refreshed.toString());
    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nextRefreshIsThirtyMinutesAfterAFailure() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(INTERVAL);
    fixture.transport.failMeta(new UnknownHostException("no dns"));

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, fixture.failures.get(0).kind());
    assertTrue(fixture.refreshed.isEmpty());
    fixture.advance(INTERVAL);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowFetchesImmediatelyAndReplacesThePendingTimer() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(600_000L);

    fixture.refresher.refreshNow();

    assertEquals(2, fixture.transport.pendingHttpCount());
    fixture.transport.completeMeta(200, perps("BTC"));
    // The timer armed by start() would have fired 20 minutes from here; it must be gone.
    fixture.advance(INTERVAL - 1L);
    assertEquals(0, fixture.transport.pendingHttpCount());
    fixture.advance(1L);
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowWithinSixtySecondsOfTheLastStartIsIgnored() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    fixture.transport.completeMeta(200, perps("BTC"));

    fixture.advance(59_999L);
    fixture.refresher.refreshNow();
    assertEquals(0, fixture.transport.pendingHttpCount());

    fixture.advance(1L);
    fixture.refresher.refreshNow();
    assertEquals(2, fixture.transport.pendingHttpCount());
  }

  @Test
  public void refreshNowWhileARefreshIsInFlightIsIgnored() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    fixture.advance(120_000L);

    fixture.refresher.refreshNow();

    assertEquals(2, fixture.transport.httpHandleCount());
  }

  @Test
  public void refreshNowBeforeStartIsIgnored() {
    Fixture fixture = new Fixture();

    fixture.refresher.refreshNow();

    assertEquals(0, fixture.transport.httpHandleCount());
  }

  @Test
  public void startIsIdempotent() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.advance(600_000L);
    fixture.refresher.start();

    fixture.advance(1_200_000L);

    assertEquals(2, fixture.transport.httpHandleCount());
  }

  @Test
  public void closeCancelsTheTimer() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();

    fixture.refresher.close();

    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
    fixture.refresher.start();
    fixture.refresher.refreshNow();
    assertEquals(0, fixture.transport.httpHandleCount());
  }

  @Test
  public void closeCancelsAnInFlightRefreshAndSilencesIt() {
    Fixture fixture = new Fixture();
    fixture.refresher.start();
    fixture.refresher.refreshNow();

    fixture.refresher.close();
    fixture.transport.lateCompleteMeta(200, perps("BTC"));

    assertTrue(fixture.transport.allHttpHandlesSettled());
    assertTrue(fixture.refreshed.isEmpty());
    assertTrue(fixture.failures.isEmpty());
    assertEquals(-1L, fixture.scheduler.nextDelayMillis());
  }

  private static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ManualScheduler scheduler = new ManualScheduler();
    private final MutableClock clock = new MutableClock();
    private final List<List<String>> refreshed = new ArrayList<List<String>>();
    private final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    private final Consumer<Runnable> lane =
        new Consumer<Runnable>() {
          @Override
          public void accept(Runnable task) {
            task.run();
          }
        };
    private final MetadataRefresher refresher =
        new MetadataRefresher(
            new MetadataRefresher.RequestFactory() {
              @Override
              public MetadataRequest create(MetadataRequest.Callback callback) {
                return new MetadataRequest(
                    transport,
                    new HyperliquidMetaParser(),
                    lane,
                    URI.create("https://api.hyperliquid.xyz/info"),
                    callback);
              }
            },
            scheduler,
            clock,
            lane,
            new MetadataRefresher.Listener() {
              @Override
              public void onMetadataRefreshed(List<Instrument> instruments) {
                List<String> symbols = new ArrayList<String>();
                for (Instrument instrument : instruments) {
                  symbols.add(instrument.symbol());
                }
                refreshed.add(symbols);
              }

              @Override
              public void onMetadataRefreshFailed(TransportFailure failure) {
                failures.add(failure);
              }
            });

    private void advance(long millis) {
      clock.now += millis;
      scheduler.advanceBy(millis);
    }
  }

  private static final class MutableClock implements LongSupplier {
    private long now;

    @Override
    public long getAsLong() {
      return now;
    }
  }
}
