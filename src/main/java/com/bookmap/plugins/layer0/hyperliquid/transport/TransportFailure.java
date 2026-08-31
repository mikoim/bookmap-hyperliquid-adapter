package com.bookmap.plugins.layer0.hyperliquid.transport;

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
