package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.model.Instrument;
import com.bookmap.plugins.layer0.hyperliquid.parse.HyperliquidMetaParser;
import com.bookmap.plugins.layer0.hyperliquid.parse.ProtocolException;
import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import com.bookmap.plugins.layer0.hyperliquid.transport.TransportFailure;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;

/**
 * One fetch of the instrument universe: {@code allPerpMetas} and {@code spotMeta} in parallel,
 * joined into a single validated list. An instance is used once. Every method, and every callback,
 * runs on the supplied state lane.
 */
public final class MetadataRequest {

  private static final String PERP_METADATA_REQUEST_JSON = "{\"type\":\"allPerpMetas\"}";
  private static final String SPOT_METADATA_REQUEST_JSON = "{\"type\":\"spotMeta\"}";
  private static final long TIMEOUT_MILLIS = 10_000L;

  /** Receives the outcome exactly once, unless the request is cancelled first. */
  public interface Callback {

    /** Reports the combined perpetual and spot instruments. */
    void onInstruments(List<Instrument> instruments);

    /** Reports the first failure of either request. */
    void onFailure(TransportFailure failure);
  }

  private final HyperliquidTransport transport;
  private final HyperliquidMetaParser metaParser;
  private final Consumer<Runnable> stateSubmitter;
  private final URI infoUri;
  private final Callback callback;

  private HyperliquidTransport.Cancellable perpRequest;
  private HyperliquidTransport.Cancellable spotRequest;
  private List<Instrument> perpInstruments;
  private List<Instrument> spotInstruments;
  private boolean started;
  private boolean finished;

  /** Creates a request against one info endpoint. */
  public MetadataRequest(
      HyperliquidTransport transport,
      HyperliquidMetaParser metaParser,
      Consumer<Runnable> stateSubmitter,
      URI infoUri,
      Callback callback) {
    if (transport == null
        || metaParser == null
        || stateSubmitter == null
        || infoUri == null
        || callback == null) {
      throw new IllegalArgumentException("metadata request dependencies must not be null");
    }
    this.transport = transport;
    this.metaParser = metaParser;
    this.stateSubmitter = stateSubmitter;
    this.infoUri = infoUri;
    this.callback = callback;
  }

  /**
   * Posts both requests. A transport that throws fails the request synchronously, so a caller must
   * record that the request is in flight before calling this.
   */
  public void start() {
    if (started) {
      throw new IllegalStateException("metadata request already started");
    }
    started = true;
    try {
      perpRequest = post(PERP_METADATA_REQUEST_JSON, true);
      spotRequest = post(SPOT_METADATA_REQUEST_JSON, false);
    } catch (RuntimeException failure) {
      fail(TransportFailure.classify(failure, TransportFailure.Kind.NETWORK));
    }
  }

  /** Aborts both requests; no callback is delivered afterwards. */
  public void cancel() {
    finished = true;
    cancelRequests();
  }

  private HyperliquidTransport.Cancellable post(String requestJson, final boolean perp) {
    return transport.postJson(
        infoUri,
        "application/json",
        requestJson,
        TIMEOUT_MILLIS,
        new HyperliquidTransport.HttpCallback() {
          @Override
          public void onComplete(final int statusCode, final String body, final Throwable failure) {
            stateSubmitter.accept(
                new Runnable() {
                  @Override
                  public void run() {
                    complete(perp, statusCode, body, failure);
                  }
                });
          }
        });
  }

  /** The first failure of either side ends the request; a late or repeated response is ignored. */
  private void complete(boolean perp, int statusCode, String body, Throwable failure) {
    if (finished || (perp ? perpInstruments : spotInstruments) != null) {
      return;
    }
    if (failure != null) {
      fail(TransportFailure.classify(failure, TransportFailure.Kind.NETWORK));
      return;
    }
    if (statusCode < 200 || statusCode >= 300) {
      fail(
          new TransportFailure(
              TransportFailure.Kind.REMOTE, "metadata request returned HTTP " + statusCode, null));
      return;
    }
    List<Instrument> combined;
    try {
      if (perp) {
        perpInstruments = metaParser.parseAllPerpMetas(body);
      } else {
        spotInstruments = metaParser.parseSpotMeta(body);
      }
      if (perpInstruments == null || spotInstruments == null) {
        return;
      }
      combined = HyperliquidMetaParser.combine(perpInstruments, spotInstruments);
    } catch (ProtocolException invalid) {
      fail(TransportFailure.classify(invalid, TransportFailure.Kind.PROTOCOL));
      return;
    }
    finished = true;
    callback.onInstruments(combined);
  }

  private void fail(TransportFailure failure) {
    if (finished) {
      return;
    }
    finished = true;
    cancelRequests();
    callback.onFailure(failure);
  }

  private void cancelRequests() {
    if (perpRequest != null) {
      perpRequest.cancel();
      perpRequest = null;
    }
    if (spotRequest != null) {
      spotRequest.cancel();
      spotRequest = null;
    }
  }
}
