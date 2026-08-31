package com.bookmap.plugins.layer0.hyperliquid.parse;

/** Indicates a Hyperliquid protocol payload that does not meet the parser contract. */
public final class ProtocolException extends Exception {

  /** Creates a protocol-validation failure with a diagnostic message. */
  public ProtocolException(String message) {
    super(message);
  }

  /** Creates a protocol-validation failure with a diagnostic message and cause. */
  public ProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
