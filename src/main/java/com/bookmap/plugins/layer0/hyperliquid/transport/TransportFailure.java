package com.bookmap.plugins.layer0.hyperliquid.transport;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Throwable identity is part of the transport failure contract.")
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
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Throwable identity is part of the transport failure contract.")
  public Throwable cause() {
    return cause;
  }
}
