package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityCheckTelemetry;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.auth.StoredSessionAuthority;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityConfiguration;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class SocketAuthorizationBatchTest {
  @Test
  void production_composition_bounds_both_channels_and_keeps_live_revocation() {
    var accounts = mock(AdminStore.class);
    when(accounts.currentSessions(any())).thenAnswer(call -> Set.copyOf(call.getArgument(0)));
    new ApplicationContextRunner()
        .withUserConfiguration(
            SocketSessionConfiguration.class,
            SocketAuthorization.class,
            StoredSessionAuthority.class,
            ReadCapacityConfiguration.class)
        .withBean(AdminStore.class, () -> accounts)
        .withBean(AuthoritySnapshots.class, AuthoritySnapshots::none)
        .withBean(AuthorityCheckTelemetry.class, AuthorityCheckTelemetry::none)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .run(
            context -> {
              assertInstanceOf(QueuedSocketSessions.class, context.getBean(SessionAuthority.class));
              var authorization = context.getBean(SocketAuthorization.class);
              var events = socket("alice", 1);
              var files = socket("alice", 1);
              assertTrue(authorization.attach(events));
              assertTrue(authorization.attach(files));
              assertTrue(authorization.current(events));
              doReturn(Set.of()).when(accounts).currentSessions(any());
              assertFalse(authorization.current(files));
              assertFalse(authorization.current(events));
              verify(accounts, never()).sessionCurrent(anyString(), anyLong());
              var registry = context.getBean(MeterRegistry.class);
              assertEquals(0, registry.get("plowshare.socket.sessions.pending").gauge().value());
              assertTrue(
                  registry.get("plowshare.socket.sessions.batches").functionCounter().count() >= 5);
            });
  }

  @Test
  void busy_sweep_does_not_treat_unchecked_sessions_as_revoked() throws Exception {
    var accounts = mock(SessionAuthority.class);
    when(accounts.sessionCurrent(anyString(), anyLong())).thenReturn(true);
    var authorization = new SocketAuthorization(accounts);
    var socket = socket("alice", 1);
    assertTrue(authorization.attach(socket));
    when(accounts.currentSessions(any()))
        .thenThrow(new ReadUnavailableException())
        .thenThrow(ReadFailedException.invalidated());
    authorization.sweep();
    authorization.sweep();
    verify(socket, never()).close(any());
    when(accounts.sessionCurrent("alice", 1)).thenReturn(false);
    assertFalse(authorization.current(socket));
    verify(socket).close(any(CloseStatus.class));
  }

  @Test
  void aBusySessionCheckClosesTemporarilyAndReleasesItsSocketSeat() throws Exception {
    var accounts = mock(SessionAuthority.class);
    when(accounts.sessionCurrent("alice", 1))
        .thenThrow(new ReadUnavailableException())
        .thenReturn(true);
    var admission =
        new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 4, 2, 1, 1, 4, 2));
    var authorization = new SocketAuthorization(accounts, admission);
    var first = socket("alice", 1);
    assertFalse(authorization.attach(first));
    verify(first).close(CloseStatus.SERVICE_OVERLOAD.withReason("Socket capacity is busy"));
    assertEquals(0, admission.active(ReadAdmission.Work.SOCKET));
    assertTrue(
        authorization.attach(socket("alice", 1)), "A new connection can use the released seat");
  }

  @Test
  void aFailedSessionReadIsTemporaryAndDoesNotPretendCapacityWasExhausted() throws Exception {
    var accounts = mock(SessionAuthority.class);
    when(accounts.sessionCurrent("alice", 1)).thenThrow(ReadFailedException.deadlineExceeded());
    var admission =
        new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 4, 2, 1, 1, 4, 2));
    var authorization = new SocketAuthorization(accounts, admission);
    var socket = socket("alice", 1);
    assertFalse(authorization.attach(socket));
    verify(socket)
        .close(
            CloseStatus.SERVICE_OVERLOAD.withReason(
                "Socket session check is temporarily unavailable"));
    assertEquals(0, admission.active(ReadAdmission.Work.SOCKET));
    assertEquals(0, admission.rejected(), "A failed session read is not admission backpressure");
  }

  @Test
  void socketsSharePrincipalCapacityAndReleaseItOnDetachOrRevocation() throws Exception {
    var accounts = mock(SessionAuthority.class);
    when(accounts.sessionCurrent(anyString(), anyLong())).thenReturn(true);
    var admission =
        new BoundedReadAdmission(new ReadCapacityProperties(4, 2, 2, 1, 4, 2, 3, 2, 4, 2));
    var authorization = new SocketAuthorization(accounts, admission);
    var first = socket("alice", 1L);
    var second = socket("alice", 1L);
    var refused = socket("alice", 1L);
    var other = socket("bob", 1L);
    assertTrue(authorization.attach(first));
    assertTrue(authorization.attach(first), "Attaching twice must not charge twice");
    assertTrue(authorization.attach(second));
    clearInvocations(accounts);
    assertFalse(authorization.attach(refused));
    verify(refused).close(CloseStatus.SERVICE_OVERLOAD.withReason("Socket capacity is busy"));
    verifyNoInteractions(accounts);
    assertTrue(authorization.attach(other), "One principal must leave room for another");
    authorization.detach(first);
    authorization.detach(first);
    assertTrue(authorization.attach(refused));
    when(accounts.currentSessions(any()))
        .thenReturn(Set.of(new SessionAuthority.Session("bob", 1)));
    authorization.sweep();
    assertEquals(1, admission.active(ReadAdmission.Work.SOCKET));
    assertTrue(authorization.attach(socket("alice", 1L)), "Revoked sockets release their capacity");
    authorization.detach(other);
    verify(second).close(any(CloseStatus.class));
  }

  private WebSocketSession socket(String handle, long version) {
    var socket = mock(WebSocketSession.class);
    when(socket.getAttributes())
        .thenReturn(
            Map.of("plowshare.sessionVersion", version, EventChannelHandler.HANDLE, handle));
    return socket;
  }

  @Test
  void aThousandSocketsShareOneFreshBatchButKeepIndependentSessionVersions() throws Exception {
    var accounts = mock(SessionAuthority.class);
    when(accounts.sessionCurrent(anyString(), anyLong())).thenReturn(true);
    var current = new SessionAuthority.Session("alice", 2);
    var old = new SessionAuthority.Session("alice", 1);
    when(accounts.currentSessions(any())).thenReturn(Set.of(current));
    var authorization = new SocketAuthorization(accounts);
    var sockets = new ArrayList<WebSocketSession>();
    for (int i = 0; i < 1000; i++) {
      var socket = mock(WebSocketSession.class);
      when(socket.getAttributes())
          .thenReturn(
              Map.of(
                  "plowshare.sessionVersion",
                  i % 2 == 0 ? 2L : 1L,
                  EventChannelHandler.HANDLE,
                  "alice"));
      assertTrue(authorization.attach(socket));
      sockets.add(socket);
    }
    clearInvocations(accounts);
    authorization.sweep();
    verify(accounts).currentSessions(Set.of(current, old));
    verifyNoMoreInteractions(accounts);
    for (int i = 0; i < sockets.size(); i++)
      verify(sockets.get(i), times(i % 2 == 0 ? 0 : 1)).close(any(CloseStatus.class));
    doReturn(Set.of()).when(accounts).currentSessions(any());
    authorization.sweep();
    for (var socket : sockets) verify(socket).close(any(CloseStatus.class));
  }
}
