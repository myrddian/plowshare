package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class RequestSessionValidityTest {
  private static final Duration BOUND = Duration.ofSeconds(2);
  private final SessionAuthority accounts = mock(SessionAuthority.class);
  private final SocketAuthorization authorization = new SocketAuthorization(accounts);
  private final WebSocketSession socket = mock(WebSocketSession.class);
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);

  RequestSessionValidityTest() {
    var attributes = new HashMap<String, Object>();
    attributes.put("plowshare.sessionVersion", 1L);
    attributes.put(EventChannelHandler.HANDLE, "alice");
    when(socket.getAttributes()).thenReturn(attributes);
    when(accounts.sessionCurrent(anyString(), anyLong())).thenReturn(true);
  }

  private RequestSessionValidity validity() {
    return new RequestSessionValidity(authorization, socket, clock::get, BOUND, clock.get());
  }

  @Test
  void oneCheckForARequestInsideTheBound() {
    var validity = validity();
    clock.addAndGet(Duration.ofMillis(100).toNanos());
    for (int i = 0; i < 4; i++) validity.run();
    clock.addAndGet(Duration.ofMillis(800).toNanos());
    validity.run();
    // The seeding check happened at the caller; nothing inside the bound asks again.
    verify(accounts, times(0)).sessionCurrent(anyString(), anyLong());
  }

  @Test
  void recheckAfterTheBound() {
    var validity = validity();
    clock.addAndGet(BOUND.toNanos() + 1);
    validity.run();
    validity.run();
    verify(accounts, times(1)).sessionCurrent("alice", 1L);
    clock.addAndGet(BOUND.toNanos() + 1);
    validity.run();
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
  }

  @Test
  void revokedSessionStopsDeliveryWithinBound() {
    var validity = validity();
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(false);
    assertDoesNotThrow(validity::run);
    clock.addAndGet(BOUND.toNanos() + 1);
    assertThrows(CallerFault.class, validity::run);
  }

  @Test
  void refusalIsNeverRemembered() {
    var validity = validity();
    clock.addAndGet(BOUND.toNanos() + 1);
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(false);
    assertThrows(CallerFault.class, validity::run);
    assertThrows(CallerFault.class, validity::run);
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
    // A later success does restart the bound, but only a success.
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(true);
    assertDoesNotThrow(validity::run);
    assertDoesNotThrow(validity::run);
    verify(accounts, times(3)).sessionCurrent("alice", 1L);
  }
}
