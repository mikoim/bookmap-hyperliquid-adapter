package com.bookmap.plugins.layer0.hyperliquid.transport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;

/** Jetty 9.3 implementation of the asynchronous Hyperliquid transport boundary. */
public final class JettyHyperliquidTransport implements HyperliquidTransport {

  private final HttpClient httpClient;
  private final WebSocketClient webSocketClient;

  /** Creates a transport with separately owned HTTP and WebSocket TLS clients. */
  public JettyHyperliquidTransport() {
    httpClient = new HttpClient(new SslContextFactory());
    webSocketClient = new WebSocketClient(new SslContextFactory());
  }

  @Override
  public void start() throws Exception {
    httpClient.start();
    webSocketClient.start();
  }

  @Override
  public Cancellable postJson(
      URI uri, String contentType, String body, long timeoutMillis, final HttpCallback callback) {
    final Request request =
        httpClient
            .POST(uri)
            .method(HttpMethod.POST)
            .header(HttpHeader.CONTENT_TYPE, contentType)
            .content(new StringContentProvider(contentType, body, StandardCharsets.UTF_8))
            .timeout(timeoutMillis, TimeUnit.MILLISECONDS);
    request.send(
        new BufferingResponseListener() {
          @Override
          public void onComplete(Result result) {
            int status = result.getResponse() == null ? 0 : result.getResponse().getStatus();
            callback.onComplete(status, getContentAsString(), result.getFailure());
          }
        });
    return new Cancellable() {
      @Override
      public void cancel() {
        request.abort(new CancellationException("metadata request cancelled"));
      }
    };
  }

  @Override
  public Cancellable connect(
      URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
    webSocketClient.setConnectTimeout(timeoutMillis);
    final SocketAdapter adapter = new SocketAdapter(callback);
    final Future<Session> future;
    try {
      future = webSocketClient.connect(adapter, uri, upgradeRequest(headers));
    } catch (Exception failure) {
      callback.onFailure(failure);
      return new Cancellable() {
        @Override
        public void cancel() {
          // The synchronous connect failure was already delivered to the callback.
        }
      };
    }
    return new Cancellable() {
      @Override
      public void cancel() {
        future.cancel(true);
        adapter.closeSession();
      }
    };
  }

  /** Builds the upgrade request carrying the supplied handshake headers verbatim. */
  static ClientUpgradeRequest upgradeRequest(Map<String, String> headers) {
    ClientUpgradeRequest request = new ClientUpgradeRequest();
    for (Map.Entry<String, String> header : headers.entrySet()) {
      request.setHeader(header.getKey(), header.getValue());
    }
    return request;
  }

  @Override
  public void close() {
    Exception failure = null;
    try {
      webSocketClient.stop();
    } catch (Exception stopFailure) {
      failure = stopFailure;
    }
    try {
      httpClient.stop();
    } catch (Exception stopFailure) {
      if (failure == null) {
        failure = stopFailure;
      } else {
        failure.addSuppressed(stopFailure);
      }
    }
    if (failure != null) {
      throw new IllegalStateException("unable to close Jetty transport", failure);
    }
  }
}

/** Adapts the small Jetty Session API used by the transport boundary. */
final class JettySocket implements HyperliquidTransport.Socket {

  private final Session session;

  JettySocket(Session session) {
    this.session = session;
  }

  @Override
  public void send(String body, final HyperliquidTransport.SendCallback callback) {
    final AtomicBoolean completed = new AtomicBoolean();
    try {
      RemoteEndpoint remote = session.getRemote();
      remote.sendString(
          body,
          new WriteCallback() {
            @Override
            public void writeFailed(Throwable failure) {
              if (completed.compareAndSet(false, true)) {
                callback.onFailure(failure);
              }
            }

            @Override
            public void writeSuccess() {
              if (completed.compareAndSet(false, true)) {
                callback.onSuccess();
              }
            }
          });
    } catch (Throwable failure) {
      if (completed.compareAndSet(false, true)) {
        callback.onFailure(failure);
      }
    }
  }

  @Override
  public void close(int code, String reason) {
    session.close(code, reason);
  }

  @Override
  public boolean isOpen() {
    return session.isOpen();
  }
}
