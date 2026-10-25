package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.auth.ServerAdministration;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Account revocation covers event and file channels, including other server processes. */
@Component
public class SocketAuthorization {
  private final SessionAuthority accounts;
  private final java.util.Set<WebSocketSession> sockets = ConcurrentHashMap.newKeySet();

  private final io.aeyer.plowshare.server.reads.ReadAdmission admission;
  private final java.util.concurrent.ConcurrentMap<
          WebSocketSession, io.aeyer.plowshare.server.reads.ReadAdmission.Lease>
      leases = new ConcurrentHashMap<>();

  /** Standalone legacy fixtures use the same narrow adapter as production composition. */
  public SocketAuthorization(io.aeyer.plowshare.server.auth.AdminStore accounts) {
    this(new io.aeyer.plowshare.server.auth.StoredSessionAuthority(accounts));
  }

  public SocketAuthorization(SessionAuthority accounts) {
    this(accounts, io.aeyer.plowshare.server.reads.ReadAdmission.direct());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SocketAuthorization(
      SessionAuthority accounts, io.aeyer.plowshare.server.reads.ReadAdmission admission) {
    this.accounts = accounts;
    this.admission = admission;
  }

  public boolean attach(WebSocketSession socket) {
    if (leases.containsKey(socket)) return current(socket);
    Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
    try {
      var lease =
          admission.admit(
              handle instanceof String value ? value : null,
              io.aeyer.plowshare.server.reads.ReadAdmission.Work.SOCKET);
      io.aeyer.plowshare.server.reads.ReadAdmission.Lease previous;
      synchronized (sockets) {
        previous = leases.putIfAbsent(socket, lease);
        sockets.add(socket);
      }
      if (previous != null) lease.close();
      return current(socket);
    } catch (io.aeyer.plowshare.server.reads.ReadUnavailableException busy) {
      try {
        socket.close(CloseStatus.POLICY_VIOLATION.withReason("Socket capacity is busy"));
      } catch (java.io.IOException ignored) {
      }
      return false;
    }
  }

  public void detach(WebSocketSession socket) {
    io.aeyer.plowshare.server.reads.ReadAdmission.Lease lease;
    synchronized (sockets) {
      sockets.remove(socket);
      lease = leases.remove(socket);
    }
    if (lease != null) lease.close();
  }

  public boolean current(WebSocketSession socket) {
    Object version = socket.getAttributes().get("plowshare.sessionVersion");
    Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
    if (version instanceof Long v && handle instanceof String h && !accounts.sessionCurrent(h, v)) {
      detach(socket);
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
    // One fresh fact set serves every event/file socket for these account versions. This is only
    // the scheduled sweep; admission and physical disclosure keep their current checks.
    var selected = new java.util.HashMap<WebSocketSession, SessionAuthority.Session>();
    for (var socket : sockets) {
      Object version = socket.getAttributes().get("plowshare.sessionVersion");
      Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
      if (version instanceof Long v && handle instanceof String h)
        selected.put(socket, new SessionAuthority.Session(h, v));
    }
    var current = accounts.currentSessions(new java.util.HashSet<>(selected.values()));
    selected.forEach(
        (socket, session) -> {
          if (!current.contains(session)) {
            detach(socket);
            try {
              socket.close(CloseStatus.POLICY_VIOLATION.withReason("Account session revoked"));
            } catch (java.io.IOException ignored) {
            }
          }
        });
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
