package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.auth.AuthFilter;
import java.util.Map;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Copies the account AuthFilter resolved for this upgrade onto the socket. It authenticates nothing
 * and refuses nothing: the filter already ran.
 */
public final class HandleInterceptor implements HandshakeInterceptor {

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attributes) {
    if (request instanceof ServletServerHttpRequest servlet) {
      Object version = servlet.getServletRequest().getAttribute("plowshare.sessionVersion");
      if (version instanceof Long) attributes.put("plowshare.sessionVersion", version);
      Object handle = servlet.getServletRequest().getAttribute(AuthFilter.HANDLE_ATTRIBUTE);
      if (handle instanceof String account && !account.isBlank()) {
        attributes.put(EventChannelHandler.HANDLE, account);
      }
    }
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception failure) {}
}
