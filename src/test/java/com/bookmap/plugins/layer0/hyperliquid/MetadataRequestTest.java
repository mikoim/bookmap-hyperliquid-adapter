package com.bookmap.plugins.layer0.hyperliquid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;

/** Pins the single-use metadata fetch shared by login and the periodic refresh. */
public class MetadataRequestTest {

  private static final URI INFO_URI = URI.create("https://api.hyperliquid.xyz/info");

  @Test
  public void bothResponsesYieldOneCombinedList() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    assertEquals(INFO_URI, fixture.transport.httpUri());
    assertEquals(10_000L, fixture.transport.httpTimeoutMillis());
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
    fixture.transport.completeMetaOnly(200, perps("BTC"));
    fixture.drain();

    assertEquals("[BTC, HYPE/USDC]", fixture.symbols.toString());
    assertTrue(fixture.failures.isEmpty());
    assertEquals(1, fixture.instrumentCallbacks);
  }

  @Test
  public void responsesMayArriveInEitherOrder() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(200, perps("BTC"));
    fixture.drain();
    assertEquals(0, fixture.instrumentCallbacks);
    fixture.transport.completeSpotMeta(200, TestMetadata.emptySpotMeta());
    fixture.drain();

    assertEquals("[BTC]", fixture.symbols.toString());
  }

  @Test
  public void transportCallbacksWaitForTheStateLane() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMeta(200, perps("BTC"));

    assertEquals(0, fixture.instrumentCallbacks);
    fixture.drain();
    assertEquals(1, fixture.instrumentCallbacks);
  }

  @Test
  public void perpFailureCancelsSpotAndReportsOnce() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.failMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, fixture.failures.get(0).kind());
    assertEquals(0, fixture.transport.pendingHttpCount());
    assertTrue(fixture.transport.httpCancelled());
  }

  @Test
  public void spotFailureCancelsPerpAndReportsOnce() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.failSpotMeta(new UnknownHostException("no dns"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(0, fixture.transport.pendingHttpCount());
  }

  @Test
  public void nonSuccessStatusIsARemoteFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(503, "unavailable");
    fixture.drain();

    assertEquals(TransportFailure.Kind.REMOTE, fixture.failures.get(0).kind());
    assertEquals("metadata request returned HTTP 503", fixture.failures.get(0).message());
  }

  @Test
  public void unparsableBodyIsAProtocolFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.transport.completeMetaOnly(200, "{}");
    fixture.drain();

    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.failures.get(0).kind());
  }

  @Test
  public void aCoinListedTwiceAcrossMarketsIsAProtocolFailure() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    // The spot pair's wire coin is "@1"; a perp with the same name collides in combine().
    fixture.transport.completeSpotMeta(200, TestMetadata.spotMetaWithUsdcPairs("HYPE"));
    fixture.transport.completeMetaOnly(200, perps("@1"));
    fixture.drain();

    assertEquals(1, fixture.failures.size());
    assertEquals(TransportFailure.Kind.PROTOCOL, fixture.failures.get(0).kind());
    assertEquals(0, fixture.instrumentCallbacks);
  }

  @Test
  public void cancelSilencesLateResponses() {
    Fixture fixture = new Fixture();
    fixture.request.start();

    fixture.request.cancel();
    fixture.transport.lateCompleteMeta(200, perps("BTC"));
    fixture.drain();

    assertEquals(0, fixture.instrumentCallbacks);
    assertTrue(fixture.failures.isEmpty());
    assertEquals(0, fixture.transport.pendingHttpCount());
  }

  @Test
  public void responsesAfterCompletionAreIgnored() {
    Fixture fixture = new Fixture();
    fixture.request.start();
    fixture.transport.completeMeta(200, perps("BTC"));
    fixture.drain();

    fixture.transport.lateCompleteMeta(503, "late");
    fixture.drain();

    assertEquals(1, fixture.instrumentCallbacks);
    assertTrue(fixture.failures.isEmpty());
  }

  @Test
  public void aThrowingTransportFailsSynchronouslyAndOnce() {
    final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    MetadataRequest request =
        new MetadataRequest(
            new ThrowingTransport(),
            new HyperliquidMetaParser(),
            new Consumer<Runnable>() {
              @Override
              public void accept(Runnable task) {
                task.run();
              }
            },
            INFO_URI,
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                fail("no instruments expected");
              }

              @Override
              public void onFailure(TransportFailure failure) {
                failures.add(failure);
              }
            });

    request.start();

    assertEquals(1, failures.size());
    assertEquals(TransportFailure.Kind.NETWORK, failures.get(0).kind());
  }

  @Test(expected = IllegalStateException.class)
  public void startingTwiceIsRejected() {
    Fixture fixture = new Fixture();
    fixture.request.start();
    fixture.request.start();
  }

  private static String perps(String... symbols) {
    return TestMetadata.allPerpMetas(TestMetadata.universe(symbols));
  }

  private static final class Fixture {
    private final FakeHyperliquidTransport transport = new FakeHyperliquidTransport();
    private final ArrayDeque<Runnable> lane = new ArrayDeque<Runnable>();
    private final List<String> symbols = new ArrayList<String>();
    private final List<TransportFailure> failures = new ArrayList<TransportFailure>();
    private int instrumentCallbacks;
    private final MetadataRequest request =
        new MetadataRequest(
            transport,
            new HyperliquidMetaParser(),
            new Consumer<Runnable>() {
              @Override
              public void accept(Runnable task) {
                lane.addLast(task);
              }
            },
            INFO_URI,
            new MetadataRequest.Callback() {
              @Override
              public void onInstruments(List<Instrument> instruments) {
                instrumentCallbacks++;
                for (Instrument instrument : instruments) {
                  symbols.add(instrument.symbol());
                }
              }

              @Override
              public void onFailure(TransportFailure failure) {
                failures.add(failure);
              }
            });

    private void drain() {
      while (!lane.isEmpty()) {
        lane.removeFirst().run();
      }
    }
  }

  /** A transport whose HTTP client is not running: every post throws. */
  private static final class ThrowingTransport implements HyperliquidTransport {
    @Override
    public void start() {
      // nothing to start
    }

    @Override
    public Cancellable postJson(
        URI uri, String contentType, String body, long timeoutMillis, HttpCallback callback) {
      throw new IllegalStateException("client stopped", new UnknownHostException("no dns"));
    }

    @Override
    public Cancellable connect(
        URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
      // nothing to close
    }
  }
}
