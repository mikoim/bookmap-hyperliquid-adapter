package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic transport double for connector boundary tests. */
public final class FakeHyperliquidTransport implements HyperliquidTransport {

  private int startCount;
  private URI httpUri;
  private String contentType;
  private long httpTimeoutMillis;
  private final List<HttpRequest> httpRequests = new ArrayList<HttpRequest>();
  private final List<URI> connectCalls = new ArrayList<URI>();
  private final List<Map<String, String>> connectHeaders = new ArrayList<Map<String, String>>();
  private long connectTimeoutMillis;
  private SocketCallback socketCallback;
  private final List<SocketCallback> socketCallbacks = new ArrayList<SocketCallback>();
  private final List<TrackedHandle> connectHandles = new ArrayList<TrackedHandle>();
  private final FakeSocket socket = new FakeSocket();
  private boolean httpCancelled;
  private boolean connectCancelled;
  private boolean closed;
  private int closeCount;

  @Override
  public void start() {
    startCount++;
  }

  public int startCount() {
    return startCount;
  }

  @Override
  public Cancellable postJson(
      URI uri, String requestContentType, String body, long timeoutMillis, HttpCallback callback) {
    httpUri = uri;
    contentType = requestContentType;
    httpTimeoutMillis = timeoutMillis;
    TrackedHandle handle = new TrackedHandle(true);
    httpRequests.add(new HttpRequest(body, callback, handle));
    return handle;
  }

  @Override
  public Cancellable connect(
      URI uri, Map<String, String> headers, long timeoutMillis, SocketCallback callback) {
    connectCalls.add(uri);
    connectHeaders.add(new LinkedHashMap<String, String>(headers));
    connectTimeoutMillis = timeoutMillis;
    socketCallback = callback;
    socketCallbacks.add(callback);
    TrackedHandle handle = new TrackedHandle(false);
    connectHandles.add(handle);
    return handle;
  }

  public List<Map<String, String>> connectHeaders() {
    return new ArrayList<Map<String, String>>(connectHeaders);
  }

  @Override
  public void close() {
    closeCount++;
    closed = true;
    socket.close(1000, "transport closed");
  }

  public int closeCount() {
    return closeCount;
  }

  public URI httpUri() {
    return httpUri;
  }

  public String contentType() {
    return contentType;
  }

  /** Returns the body of the first HTTP request, which is always the allPerpMetas request. */
  public String httpBody() {
    return httpRequests.isEmpty() ? null : httpRequests.get(0).body;
  }

  /** Returns every HTTP request body in the order posted. */
  public List<String> httpBodies() {
    List<String> bodies = new ArrayList<String>();
    for (HttpRequest request : httpRequests) {
      bodies.add(request.body);
    }
    return bodies;
  }

  /** Returns how many HTTP requests are neither completed nor cancelled. */
  public int pendingHttpCount() {
    int pending = 0;
    for (HttpRequest request : httpRequests) {
      if (!request.handle.completed && !request.handle.cancelled) {
        pending++;
      }
    }
    return pending;
  }

  public long httpTimeoutMillis() {
    return httpTimeoutMillis;
  }

  public List<URI> connectCalls() {
    return new ArrayList<URI>(connectCalls);
  }

  public long connectTimeoutMillis() {
    return connectTimeoutMillis;
  }

  public FakeSocket socket() {
    return socket;
  }

  public boolean httpCancelled() {
    return httpCancelled;
  }

  public boolean connectCancelled() {
    return connectCancelled;
  }

  public boolean closed() {
    return closed;
  }

  public boolean allHttpHandlesSettled() {
    for (HttpRequest request : httpRequests) {
      if (!request.handle.cancelled && !request.handle.completed) {
        return false;
      }
    }
    return true;
  }

  public int httpHandleCount() {
    return httpRequests.size();
  }

  public int settledHttpHandleCount() {
    int settled = 0;
    for (HttpRequest request : httpRequests) {
      if (request.handle.cancelled || request.handle.completed) {
        settled++;
      }
    }
    return settled;
  }

  public boolean allConnectHandlesSettled() {
    for (TrackedHandle handle : connectHandles) {
      if (!handle.cancelled && !handle.completed) {
        return false;
      }
    }
    return true;
  }

  public int settledConnectHandleCount() {
    int settled = 0;
    for (TrackedHandle handle : connectHandles) {
      if (handle.cancelled || handle.completed) {
        settled++;
      }
    }
    return settled;
  }

  public int connectionHandleCount() {
    return connectHandles.size();
  }

  public void emitTextFromConnection(int connectionIndex, String text) {
    socketCallbacks.get(connectionIndex).onText(text);
  }

  public static final String PERP_META_REQUEST = "{\"type\":\"allPerpMetas\"}";
  public static final String SPOT_META_REQUEST = "{\"type\":\"spotMeta\"}";

  /**
   * Completes the allPerpMetas request. A still-pending spotMeta request is completed first with an
   * empty spot universe, so perp-only tests need no spot fixture.
   */
  public void completeMeta(int status, String body) {
    HttpRequest spot = pending(SPOT_META_REQUEST);
    if (spot != null) {
      spot.complete(200, TestMetadata.emptySpotMeta(), null);
    }
    requirePending(PERP_META_REQUEST).complete(status, body, null);
  }

  /** Completes the spotMeta request; call before {@link #completeMeta}. */
  public void completeSpotMeta(int status, String body) {
    requirePending(SPOT_META_REQUEST).complete(status, body, null);
  }

  /** Completes only the allPerpMetas request, leaving a pending spotMeta request untouched. */
  public void completeMetaOnly(int status, String body) {
    requirePending(PERP_META_REQUEST).complete(status, body, null);
  }

  /** Fails the allPerpMetas request. */
  public void failMeta(Throwable failure) {
    requirePending(PERP_META_REQUEST).complete(0, null, failure);
  }

  /** Fails the spotMeta request. */
  public void failSpotMeta(Throwable failure) {
    requirePending(SPOT_META_REQUEST).complete(0, null, failure);
  }

  /** Re-delivers a response to every HTTP callback ever posted, completed or cancelled. */
  public void lateCompleteMeta(int status, String body) {
    for (HttpRequest request : httpRequests) {
      request.callback.onComplete(status, body, null);
    }
  }

  /** Asserts both metadata requests are pending, so a test can drive their order explicitly. */
  public void requirePerpThenSpot() {
    requirePending(PERP_META_REQUEST);
    requirePending(SPOT_META_REQUEST);
  }

  private HttpRequest pending(String body) {
    for (HttpRequest request : httpRequests) {
      if (request.body.equals(body) && !request.handle.completed && !request.handle.cancelled) {
        return request;
      }
    }
    return null;
  }

  private HttpRequest requirePending(String body) {
    HttpRequest request = pending(body);
    if (request == null) {
      throw new AssertionError("no pending HTTP request with body " + body);
    }
    return request;
  }

  public void openSocket() {
    connectHandles.get(connectHandles.size() - 1).completed = true;
    socket.open = true;
    socketCallback.onOpen(socket);
  }

  /** Opens one specific connection so tests can drive two connectors on one transport. */
  public void openConnection(int connectionIndex) {
    connectHandles.get(connectionIndex).completed = true;
    socket.open = true;
    socketCallbacks.get(connectionIndex).onOpen(socket);
  }

  /** Fails one specific connection without disturbing the others. */
  public void failConnection(int connectionIndex, Throwable failure) {
    connectHandles.get(connectionIndex).completed = true;
    socketCallbacks.get(connectionIndex).onFailure(failure);
  }

  /** Closes one specific connection without disturbing the others. */
  public void remoteCloseConnection(int connectionIndex, int code, String reason) {
    connectHandles.get(connectionIndex).completed = true;
    socket.open = false;
    socketCallbacks.get(connectionIndex).onClose(code, reason);
  }

  public void remoteClose(int code, String reason) {
    connectHandles.get(connectHandles.size() - 1).completed = true;
    socket.open = false;
    socketCallback.onClose(code, reason);
  }

  public void failSocket(Throwable failure) {
    connectHandles.get(connectHandles.size() - 1).completed = true;
    socket.open = false;
    socketCallback.onFailure(failure);
  }

  public void lateOpenConnections() {
    for (SocketCallback callback : socketCallbacks) {
      socket.open = true;
      callback.onOpen(socket);
    }
  }

  public void clearSuccessfulSendBodies() {
    socket.successfulSendBodies.clear();
  }

  /** Socket double that permits tests to explicitly release write callbacks. */
  public static final class FakeSocket implements Socket {

    private boolean open;
    private final List<PendingSend> pending = new ArrayList<PendingSend>();
    private final List<SendCallback> allSendCallbacks = new ArrayList<SendCallback>();
    private final List<String> successfulSendBodies = new ArrayList<String>();
    private String closeReason;
    private int closeCode;
    private Throwable nextSendFailure;

    @Override
    public void send(String body, SendCallback callback) {
      if (nextSendFailure != null) {
        Throwable failure = nextSendFailure;
        nextSendFailure = null;
        throwUnchecked(failure);
      }
      pending.add(new PendingSend(body, callback));
      allSendCallbacks.add(callback);
    }

    @Override
    public void close(int code, String reason) {
      closeCode = code;
      closeReason = reason;
      open = false;
      pending.clear();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    public void succeedNextSend() {
      PendingSend send = pending.remove(0);
      successfulSendBodies.add(send.body);
      send.callback.onSuccess();
    }

    public void failNextSend(Throwable failure) {
      pending.remove(0).callback.onFailure(failure);
    }

    public int pendingSendCount() {
      return pending.size();
    }

    public void lateSucceedAllSends() {
      for (SendCallback callback : allSendCallbacks) {
        callback.onSuccess();
      }
    }

    public List<String> successfulSendBodies() {
      return new ArrayList<String>(successfulSendBodies);
    }

    public String closeReason() {
      return closeReason;
    }

    public int closeCode() {
      return closeCode;
    }

    public void throwOnNextSend(Throwable failure) {
      nextSendFailure = failure;
    }

    private static void throwUnchecked(Throwable failure) {
      FakeSocket.<RuntimeException>throwAs(failure);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void throwAs(Throwable failure) throws T {
      throw (T) failure;
    }

    private static final class PendingSend {
      private final String body;
      private final SendCallback callback;

      private PendingSend(String body, SendCallback callback) {
        this.body = body;
        this.callback = callback;
      }
    }
  }

  private static final class HttpRequest {
    private final String body;
    private final HttpCallback callback;
    private final TrackedHandle handle;

    private HttpRequest(String body, HttpCallback callback, TrackedHandle handle) {
      this.body = body;
      this.callback = callback;
      this.handle = handle;
    }

    private void complete(int status, String responseBody, Throwable failure) {
      handle.completed = true;
      callback.onComplete(status, responseBody, failure);
    }
  }

  private final class TrackedHandle implements Cancellable {
    private final boolean http;
    private boolean cancelled;
    private boolean completed;

    private TrackedHandle(boolean http) {
      this.http = http;
    }

    @Override
    public void cancel() {
      cancelled = true;
      if (http) {
        httpCancelled = true;
      } else {
        connectCancelled = true;
      }
    }
  }
}
