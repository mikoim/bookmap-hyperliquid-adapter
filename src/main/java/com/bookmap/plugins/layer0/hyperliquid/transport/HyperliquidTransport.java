package com.bookmap.plugins.layer0.hyperliquid.transport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;

/** Asynchronous HTTP and WebSocket boundary used by the Hyperliquid connector. */
public interface HyperliquidTransport extends AutoCloseable {

  /** Starts resources owned by this transport. */
  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
      justification = "The public transport contract deliberately exposes startup failures.")
  void start() throws Exception;

  /** Posts JSON and reports the completed HTTP response. */
  Cancellable postJson(
      URI uri, String contentType, String body, long timeoutMillis, HttpCallback callback);

  /** Opens a WebSocket and reports its lifecycle. */
  Cancellable connect(URI uri, long timeoutMillis, SocketCallback callback);

  /** Closes all transport resources. */
  @Override
  void close();

  /** Cancels one asynchronous operation. */
  interface Cancellable {

    /** Cancels the operation. */
    void cancel();
  }

  /** Receives one completed HTTP operation. */
  interface HttpCallback {

    /** Reports status, body, and a possible transport failure. */
    void onComplete(int statusCode, String body, Throwable failure);
  }

  /** Receives WebSocket lifecycle callbacks. */
  interface SocketCallback {

    /** Reports an opened socket. */
    void onOpen(Socket socket);

    /** Reports an immutable text frame. */
    void onText(String text);

    /** Reports a remote or local close. */
    void onClose(int code, String reason);

    /** Reports a transport failure. */
    void onFailure(Throwable failure);
  }

  /** Receives completion of one asynchronous WebSocket write. */
  interface SendCallback {

    /** Reports a successful write. */
    void onSuccess();

    /** Reports a failed write. */
    void onFailure(Throwable failure);
  }

  /** A connected WebSocket. */
  interface Socket {

    /** Starts a nonblocking text-frame write. */
    void send(String body, SendCallback callback);

    /** Closes the WebSocket with the supplied close information. */
    void close(int code, String reason);

    /** Returns whether the underlying socket remains open. */
    boolean isOpen();
  }
}
