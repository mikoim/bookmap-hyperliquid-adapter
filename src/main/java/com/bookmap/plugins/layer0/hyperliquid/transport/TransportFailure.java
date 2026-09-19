package com.bookmap.plugins.layer0.hyperliquid.transport;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/** A classified, credential-free failure from Hyperliquid transport or protocol handling. */
public final class TransportFailure {

  /** Broad failure origin used by reconnect and initial-login policy. */
  public enum Kind {
    NETWORK,
    REMOTE,
    PROTOCOL
  }

  private final Kind kind;
  private final String message;
  private final Throwable cause;

  /** Creates a classified failure. */
  public TransportFailure(Kind kind, String message, Throwable cause) {
    if (kind == null) {
      throw new IllegalArgumentException("kind must not be null");
    }
    this.kind = kind;
    this.message = message;
    this.cause = cause;
  }

  /**
   * Classifies a raw failure. A network exception anywhere in the cause chain makes it {@link
   * Kind#NETWORK}; anything else keeps the caller's fallback kind.
   */
  public static TransportFailure classify(Throwable failure, Kind fallback) {
    Kind kind = isNetworkFailure(failure) ? Kind.NETWORK : fallback;
    String message = failure == null ? null : failure.getMessage();
    return new TransportFailure(kind, message, failure);
  }

  private static boolean isNetworkFailure(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof UnknownHostException
          || current instanceof NoRouteToHostException
          || current instanceof SocketException
          || current instanceof ConnectException
          || current instanceof SocketTimeoutException
          || current instanceof TimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  /** Returns the broad failure origin. */
  public Kind kind() {
    return kind;
  }

  /** Returns the credential-free diagnostic message. */
  public String message() {
    return message;
  }

  /** Returns the underlying failure when one exists. */
  public Throwable cause() {
    return cause;
  }
}
