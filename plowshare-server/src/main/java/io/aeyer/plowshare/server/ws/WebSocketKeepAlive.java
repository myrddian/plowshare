package io.aeyer.plowshare.server.ws;

import org.springframework.web.socket.WebSocketHandler;

/** Adds transport liveness to a channel without application operations or session renewal. */
public interface WebSocketKeepAlive extends AutoCloseable {
  /**
   * Preserves the handler's subprotocols and serializes control frames with its ordinary writes.
   */
  WebSocketHandler decorate(WebSocketHandler handler);

  /** Stop probes and close retained transports; durable work keeps its existing close semantics. */
  @Override
  void close();
}
