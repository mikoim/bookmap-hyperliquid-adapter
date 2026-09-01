package com.bookmap.plugins.layer0.hyperliquid.transport;

import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/** Forwards Jetty annotated socket events to the connector transport callback. */
@WebSocket(maxTextMessageSize = Integer.MAX_VALUE)
public final class SocketAdapter {

  private final HyperliquidTransport.SocketCallback callback;
  private final AtomicReference<Session> session = new AtomicReference<Session>();

  SocketAdapter(HyperliquidTransport.SocketCallback callback) {
    this.callback = callback;
  }

  @OnWebSocketConnect
  public void onConnect(Session connectedSession) {
    session.set(connectedSession);
    callback.onOpen(new JettySocket(connectedSession));
  }

  @OnWebSocketMessage
  public void onMessage(String text) {
    callback.onText(text);
  }

  @OnWebSocketClose
  public void onClose(int code, String reason) {
    callback.onClose(code, reason);
  }

  @OnWebSocketError
  public void onError(Throwable failure) {
    callback.onFailure(failure);
  }

  void closeSession() {
    Session opened = session.get();
    if (opened != null && opened.isOpen()) {
      opened.close();
    }
  }
}
