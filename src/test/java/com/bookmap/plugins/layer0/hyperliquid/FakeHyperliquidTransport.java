package com.bookmap.plugins.layer0.hyperliquid;

import com.bookmap.plugins.layer0.hyperliquid.transport.HyperliquidTransport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Deterministic transport double for connector boundary tests. */
public final class FakeHyperliquidTransport implements HyperliquidTransport {

  private URI httpUri;
  private String contentType;
  private String httpBody;
  private long httpTimeoutMillis;
  private HttpCallback httpCallback;
  private final List<URI> connectCalls = new ArrayList<URI>();
  private long connectTimeoutMillis;
  private SocketCallback socketCallback;
  private final List<SocketCallback> socketCallbacks = new ArrayList<SocketCallback>();
  private final FakeSocket socket = new FakeSocket();
  private boolean httpCancelled;
  private boolean connectCancelled;

  @Override
  public void start() {
    // The fake owns no resources.
  }

  @Override
  public Cancellable postJson(
      URI uri, String requestContentType, String body, long timeoutMillis, HttpCallback callback) {
    httpUri = uri;
    contentType = requestContentType;
    httpBody = body;
    httpTimeoutMillis = timeoutMillis;
    httpCallback = callback;
    return new Cancellable() {
      @Override
      public void cancel() {
        httpCancelled = true;
      }
    };
  }

  @Override
  public Cancellable connect(URI uri, long timeoutMillis, SocketCallback callback) {
    connectCalls.add(uri);
    connectTimeoutMillis = timeoutMillis;
    socketCallback = callback;
    socketCallbacks.add(callback);
    return new Cancellable() {
      @Override
      public void cancel() {
        connectCancelled = true;
      }
    };
  }

  @Override
  public void close() {
    // The fake owns no resources.
  }

  public URI httpUri() {
    return httpUri;
  }

  public String contentType() {
    return contentType;
  }

  public String httpBody() {
    return httpBody;
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

  public void emitTextFromConnection(int connectionIndex, String text) {
    socketCallbacks.get(connectionIndex).onText(text);
  }

  public void completeMeta(int status, String body) {
    httpCallback.onComplete(status, body, null);
  }

  public void failMeta(Throwable failure) {
    httpCallback.onComplete(0, null, failure);
  }

  public void openSocket() {
    socket.open = true;
    socketCallback.onOpen(socket);
  }

  public void remoteClose(int code, String reason) {
    socket.open = false;
    socketCallback.onClose(code, reason);
  }

  public void failSocket(Throwable failure) {
    socket.open = false;
    socketCallback.onFailure(failure);
  }

  public void clearSuccessfulSendBodies() {
    socket.successfulSendBodies.clear();
  }

  /** Socket double that permits tests to explicitly release write callbacks. */
  public static final class FakeSocket implements Socket {

    private boolean open;
    private final List<PendingSend> pending = new ArrayList<PendingSend>();
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
    }

    @Override
    public void close(int code, String reason) {
      closeCode = code;
      closeReason = reason;
      open = false;
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
}
