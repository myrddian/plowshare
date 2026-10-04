package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.ServerAdministration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Account revocation covers event and file channels, including other server processes. */
@Component
@org.springframework.scheduling.annotation.EnableScheduling
public class SocketAuthorization {
  private final AdminStore accounts;
  private final java.util.Set<WebSocketSession> sockets = ConcurrentHashMap.newKeySet();

  public SocketAuthorization(AdminStore accounts) {
    this.accounts = accounts;
  }

  public boolean attach(WebSocketSession socket) {
    sockets.add(socket);
    return current(socket);
  }

  public void detach(WebSocketSession socket) {
    sockets.remove(socket);
  }

  public boolean current(WebSocketSession socket) {
    Object version = socket.getAttributes().get("plowshare.sessionVersion");
    Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
    if (version instanceof Long v && handle instanceof String h && !accounts.sessionCurrent(h, v)) {
      sockets.remove(socket);
      try {
        socket.close(CloseStatus.POLICY_VIOLATION.withReason("Account session revoked"));
      } catch (java.io.IOException ignored) {
      }
      return false;
    }
    return true;
  }

  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public void sweep() {
    sockets.forEach(this::current);
  }

  @org.springframework.transaction.event.TransactionalEventListener(
      phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
  public void revoked(ServerAdministration.SessionsRevoked event) {
    sockets.stream()
        .filter(
            socket -> event.handle().equals(socket.getAttributes().get(EventChannelHandler.HANDLE)))
        .forEach(this::current);
  }
}
