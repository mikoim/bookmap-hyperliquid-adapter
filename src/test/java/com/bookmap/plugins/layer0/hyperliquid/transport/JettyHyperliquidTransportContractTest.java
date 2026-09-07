package com.bookmap.plugins.layer0.hyperliquid.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.junit.Test;

/** Pins the Jetty 9.3 remote-endpoint boundary without an external WebSocket server. */
public class JettyHyperliquidTransportContractTest {

  @Test
  public void jettyCanReflectivelyInvokeAnnotatedEndpoint() throws Exception {
    final AtomicReference<Throwable> delivered = new AtomicReference<Throwable>();
    SocketAdapter adapter =
        new SocketAdapter(
            new HyperliquidTransport.SocketCallback() {
              @Override
              public void onOpen(HyperliquidTransport.Socket socket) {
                // Not exercised by this reflection boundary test.
              }

              @Override
              public void onText(String text) {
                // Not exercised by this reflection boundary test.
              }

              @Override
              public void onClose(int code, String reason) {
                // Not exercised by this reflection boundary test.
              }

              @Override
              public void onFailure(Throwable failure) {
                delivered.set(failure);
              }
            });
    IllegalStateException failure = new IllegalStateException("socket failure");
    CallableMethod callable =
        new CallableMethod(
            SocketAdapter.class, SocketAdapter.class.getMethod("onError", Throwable.class));

    callable.call(adapter, failure);

    assertSame(failure, delivered.get());
  }

  @Test
  public void socketDelegatesExactBodyAndWriteCallbacksOnce() {
    final AtomicReference<String> sent = new AtomicReference<String>();
    final AtomicReference<WriteCallback> write = new AtomicReference<WriteCallback>();
    RemoteEndpoint remote =
        proxy(
            RemoteEndpoint.class,
            (proxy, method, arguments) -> {
              if ("sendString".equals(method.getName()) && arguments.length == 2) {
                sent.set((String) arguments[0]);
                write.set((WriteCallback) arguments[1]);
              }
              return defaultValue(method.getReturnType());
            });
    Session session =
        session(remote, true, new AtomicReference<Integer>(), new AtomicReference<String>());
    JettySocket socket = new JettySocket(session);
    final int[] successful = new int[1];
    final int[] failed = new int[1];

    socket.send(
        "{\"method\":\"ping\"}",
        new HyperliquidTransport.SendCallback() {
          @Override
          public void onSuccess() {
            successful[0]++;
          }

          @Override
          public void onFailure(Throwable failure) {
            failed[0]++;
          }
        });
    write.get().writeSuccess();
    write.get().writeSuccess();
    write.get().writeFailed(new IllegalStateException("late"));

    assertEquals("{\"method\":\"ping\"}", sent.get());
    assertEquals(1, successful[0]);
    assertEquals(0, failed[0]);
  }

  @Test
  public void socketDelegatesFailureCloseAndOpenState() {
    final AtomicReference<WriteCallback> write = new AtomicReference<WriteCallback>();
    final AtomicReference<Integer> closeCode = new AtomicReference<Integer>();
    final AtomicReference<String> closeReason = new AtomicReference<String>();
    RemoteEndpoint remote =
        proxy(
            RemoteEndpoint.class,
            (proxy, method, arguments) -> {
              if ("sendString".equals(method.getName()) && arguments.length == 2) {
                write.set((WriteCallback) arguments[1]);
              }
              return defaultValue(method.getReturnType());
            });
    JettySocket socket = new JettySocket(session(remote, false, closeCode, closeReason));
    final int[] failures = new int[1];

    socket.send(
        "body",
        new HyperliquidTransport.SendCallback() {
          @Override
          public void onSuccess() {
            // The failure path is the behavior under test.
          }

          @Override
          public void onFailure(Throwable failure) {
            failures[0]++;
          }
        });
    write.get().writeFailed(new IllegalArgumentException("bad"));
    write.get().writeFailed(new IllegalArgumentException("late"));
    socket.close(1001, "shutdown");

    assertEquals(1, failures[0]);
    assertEquals(Integer.valueOf(1001), closeCode.get());
    assertEquals("shutdown", closeReason.get());
    assertFalse(socket.isOpen());
  }

  /** Pins that profile headers reach the Jetty upgrade request verbatim. */
  @Test
  public void upgradeRequestCarriesEveryHandshakeHeader() {
    java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
    headers.put("Origin", "https://hyperdash.com");
    headers.put("User-Agent", "Mozilla/5.0 test");

    org.eclipse.jetty.websocket.client.ClientUpgradeRequest request =
        JettyHyperliquidTransport.upgradeRequest(headers);

    assertEquals("https://hyperdash.com", request.getHeader("Origin"));
    assertEquals("Mozilla/5.0 test", request.getHeader("User-Agent"));
    assertNull(
        JettyHyperliquidTransport.upgradeRequest(java.util.Collections.<String, String>emptyMap())
            .getHeader("Origin"));
  }

  @Test
  public void socketMirrorsOpenJettySession() {
    RemoteEndpoint remote =
        proxy(
            RemoteEndpoint.class,
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));

    assertTrue(
        new JettySocket(
                session(
                    remote, true, new AtomicReference<Integer>(), new AtomicReference<String>()))
            .isOpen());
  }

  /** Pins the Jetty default we are overriding and the limit the adapter needs instead. */
  @Test
  public void messageLimitsExceedTheLargestObservedFrames() {
    WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
    assertEquals(65_536, policy.getMaxTextMessageSize());

    JettyHyperliquidTransport.applyMessageLimits(policy);

    assertEquals(1_048_576, policy.getMaxTextMessageSize());
    assertEquals(1_048_576, policy.getMaxBinaryMessageSize());
    assertEquals(1_048_576, JettyHyperliquidTransport.MAX_WEB_SOCKET_MESSAGE_BYTES);
    assertEquals(4_194_304, JettyHyperliquidTransport.MAX_HTTP_RESPONSE_BYTES);
  }

  private static Session session(
      final RemoteEndpoint remote,
      final boolean open,
      final AtomicReference<Integer> closeCode,
      final AtomicReference<String> closeReason) {
    return proxy(
        Session.class,
        (proxy, method, arguments) -> {
          if ("getRemote".equals(method.getName())) {
            return remote;
          }
          if ("isOpen".equals(method.getName())) {
            return Boolean.valueOf(open);
          }
          if ("close".equals(method.getName()) && arguments != null && arguments.length == 2) {
            closeCode.set((Integer) arguments[0]);
            closeReason.set((String) arguments[1]);
          }
          return defaultValue(method.getReturnType());
        });
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == Boolean.TYPE) {
      return Boolean.FALSE;
    }
    if (type == Character.TYPE) {
      return Character.valueOf('\0');
    }
    return Integer.valueOf(0);
  }
}
