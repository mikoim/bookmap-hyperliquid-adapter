package com.bookmap.plugins.layer0.hyperliquid.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;

/** Pins how raw failures are sorted into the kinds that drive login and reconnect policy. */
public class TransportFailureTest {

  @Test
  public void networkCauseAnywhereInTheChainWinsOverTheFallback() {
    Throwable failure =
        new ExecutionException(new IllegalStateException(new UnknownHostException("no dns")));

    TransportFailure classified =
        TransportFailure.classify(failure, TransportFailure.Kind.PROTOCOL);

    assertEquals(TransportFailure.Kind.NETWORK, classified.kind());
    assertSame(failure, classified.cause());
    assertEquals(failure.getMessage(), classified.message());
  }

  @Test
  public void otherFailuresKeepTheFallbackKind() {
    TransportFailure classified =
        TransportFailure.classify(
            new IllegalArgumentException("bad json"), TransportFailure.Kind.PROTOCOL);

    assertEquals(TransportFailure.Kind.PROTOCOL, classified.kind());
    assertEquals("bad json", classified.message());
  }

  @Test
  public void missingFailureKeepsTheFallbackKindWithoutAMessage() {
    TransportFailure classified = TransportFailure.classify(null, TransportFailure.Kind.NETWORK);

    assertEquals(TransportFailure.Kind.NETWORK, classified.kind());
    assertNull(classified.message());
    assertNull(classified.cause());
  }
}
