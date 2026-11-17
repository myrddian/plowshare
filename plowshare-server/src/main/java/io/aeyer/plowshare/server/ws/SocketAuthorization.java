package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.ServerAdministration;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.auth.StoredSessionAuthority;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Account revocation covers event and file channels, including other server processes. */
@Component
public class SocketAuthorization {
  private final SessionAuthority accounts;
  private final Set<WebSocketSession> sockets = ConcurrentHashMap.newKeySet();

  private final ReadAdmission admission;
  private final ConcurrentMap<WebSocketSession, ReadAdmission.Lease> leases =
      new ConcurrentHashMap<>();

  /** Standalone legacy fixtures use the same narrow adapter as production composition. */
  public SocketAuthorization(AdminStore accounts) {
    this(new StoredSessionAuthority(accounts));
  }

  public SocketAuthorization(SessionAuthority accounts) {
    this(accounts, ReadAdmission.direct());
  }

  @Autowired
  public SocketAuthorization(SessionAuthority accounts, ReadAdmission admission) {
    this.accounts = accounts;
    this.admission = admission;
  }

  public boolean attach(WebSocketSession socket) {
    if (leases.containsKey(socket)) return current(socket);
    Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
    try {
      var lease =
          admission.admit(handle instanceof String value ? value : null, ReadAdmission.Work.SOCKET);
      ReadAdmission.Lease previous;
      synchronized (sockets) {
        previous = leases.putIfAbsent(socket, lease);
        sockets.add(socket);
      }
      if (previous != null) lease.close();
      return current(socket);
    } catch (ReadUnavailableException | ReadFailedException busy) {
      detach(socket);
      try {
        // A transient capacity/session-read failure is not a revoked account. Clients
        // treat 1008 as a permanent policy refusal and stop reconnecting their file channel.
        String reason =
            busy instanceof ReadUnavailableException
                ? "Socket capacity is busy"
                : "Socket session check is temporarily unavailable";
        socket.close(CloseStatus.SERVICE_OVERLOAD.withReason(reason));
      } catch (IOException ignored) {
      }
      return false;
    }
  }

  public void detach(WebSocketSession socket) {
    ReadAdmission.Lease lease;
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
      } catch (IOException ignored) {
      }
      return false;
    }
    return true;
  }

  @Scheduled(fixedDelay = 1000)
  public void sweep() {
    // One fresh fact set serves every event/file socket for these account versions. This is only
    // the scheduled sweep; admission and physical disclosure keep their current checks.
    var selected = new HashMap<WebSocketSession, SessionAuthority.Session>();
    for (var socket : sockets) {
      Object version = socket.getAttributes().get("plowshare.sessionVersion");
      Object handle = socket.getAttributes().get(EventChannelHandler.HANDLE);
      if (version instanceof Long v && handle instanceof String h)
        selected.put(socket, new SessionAuthority.Session(h, v));
    }
    Set<SessionAuthority.Session> current;
    try {
      current = accounts.currentSessions(new HashSet<>(selected.values()));
    } catch (ReadUnavailableException | ReadFailedException busy) {
      // Capacity or a timed-out chunk is not a negative account fact. Existing sockets remain
      // subject to their fresh execution/disclosure checks and the next bounded sweep.
      return;
    }
    selected.forEach(
        (socket, session) -> {
          if (!current.contains(session)) {
            detach(socket);
            try {
              socket.close(CloseStatus.POLICY_VIOLATION.withReason("Account session revoked"));
            } catch (IOException ignored) {
            }
          }
        });
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void revoked(ServerAdministration.SessionsRevoked event) {
    sockets.stream()
        .filter(
            socket -> event.handle().equals(socket.getAttributes().get(EventChannelHandler.HANDLE)))
        .forEach(this::current);
  }
}
