package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ArchiveUnavailableException;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.session.*;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.*;

class ProjectMembershipTest {
  private final SessionRegistry sessions = new SessionRegistry();
  private final PresenceRegistry presences = new PresenceRegistry();
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectRoots roots = mock(ProjectRoots.class);
  private final FileChannelHandler channel =
      new FileChannelHandler(sessions, presences, roots, members);

  private WebSocketSession socket(String id, String handle) {
    WebSocketSession socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(
            URI.create(
                "ws://localhost/v1/files?session="
                    + id
                    + "&project=ledger&machine=new-machine&root=/repo&ready=1"));
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(EventChannelHandler.HANDLE, handle);
    when(socket.getAttributes()).thenReturn(attributes);
    return socket;
  }

  @Test
  void permitted_account_roots_and_passes_its_handle_to_durable_store() throws Exception {
    when(members.mayUse("ledger", "alice")).thenReturn(true);
    var socket = socket("desk", "alice");
    channel.afterConnectionEstablished(socket);
    assertTrue(presences.serving("ledger").isPresent());
    verify(roots).rootOn("ledger", "new-machine", "/repo", "alice");
    ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
    verify(socket).sendMessage(frame.capture());
    assertTrue(frame.getValue().getPayload().toString().contains("\"project\":\"ledger\""));
  }

  @Test
  void non_member_is_closed_1008_without_rooting() throws Exception {
    var socket = socket("desk", "bob");
    channel.afterConnectionEstablished(socket);
    ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
    verify(socket).close(status.capture());
    assertEquals(1008, status.getValue().getCode());
    assertEquals("this account may not root 'ledger'", status.getValue().getReason());
    assertTrue(presences.serving("ledger").isEmpty());
    verifyNoInteractions(roots);
    assertFalse(channel.isConnected("desk"));
  }

  @Test
  void outage_keeps_global_files_connected_but_ready_names_no_project() throws Exception {
    when(members.mayUse("ledger", "alice"))
        .thenThrow(new ArchiveUnavailableException("down", new IllegalStateException()));
    var socket = socket("desk", "alice");
    channel.afterConnectionEstablished(socket);
    verify(socket, never()).close(any());
    assertTrue(channel.isConnected("desk"));
    assertTrue(presences.serving("ledger").isEmpty());
    verifyNoInteractions(roots);
    ArgumentCaptor<TextMessage> frame = ArgumentCaptor.forClass(TextMessage.class);
    verify(socket).sendMessage(frame.capture());
    assertEquals("{\"ready\":true}", frame.getValue().getPayload());
  }

  @Test
  void conflict_reason_hides_other_account_but_names_own_terminal() throws Exception {
    when(members.mayUse("ledger", "alice")).thenReturn(true);
    sessions.claim("private-session", "bob");
    Presence held = new Presence("private-session", "private-machine", "/private-root", "ledger");
    presences.declare(held);
    var other = socket("desk", "alice");
    channel.afterConnectionEstablished(other);
    ArgumentCaptor<CloseStatus> status = ArgumentCaptor.forClass(CloseStatus.class);
    verify(other).close(status.capture());
    String reason = status.getValue().getReason();
    assertFalse(reason.contains("private-session"));
    assertFalse(reason.contains("private-machine"));
    assertFalse(reason.contains("private-root"));
    presences.withdraw("private-session");
    sessions.claim("own-terminal", "alice");
    Presence own = new Presence("own-terminal", "own-machine", "/own-root", "ledger");
    presences.declare(own);
    var same = socket("second-desk", "alice");
    channel.afterConnectionEstablished(same);
    verify(same).close(status.capture());
    assertTrue(status.getValue().getReason().contains(own.canonicalName()));
  }

  @Test
  void membership_handlers_delegate_authenticated_actor_and_role_to_the_authoritative_service() {
    var add = new ProjectMemberHandler(members, new AuthProperties(), true);
    var remove = new ProjectMemberHandler(members, new AuthProperties(), false);
    Map<String, Object> payload = Map.of("project", "ledger", "handle", "bob");
    doThrow(new CallerFault("MANAGER access required"))
        .when(members)
        .assign(
            "ledger",
            "bob",
            io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR,
            "bob",
            true);
    assertThrows(CallerFault.class, () -> add.handle(payload, new Asking("desk", "bob")));
    when(members.members("ledger")).thenReturn(List.of("alice", "bob"));
    assertEquals(
        new ProjectMemberHandler.Changed("ledger", List.of("alice", "bob")),
        add.handle(payload, new Asking("desk", "alice")).payload());
    verify(members)
        .assign(
            "ledger",
            "bob",
            io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR,
            "alice",
            true);
    when(members.members("ledger")).thenReturn(List.of("alice"));
    assertEquals(
        new ProjectMemberHandler.Changed("ledger", List.of("alice")),
        remove.handle(payload, new Asking("desk", "alice")).payload());
    verify(members).remove("ledger", "bob", "alice");
    assertThrows(
        CallerFault.class,
        () ->
            add.handle(
                Map.of("project", "ledger", "handle", "bob", "role", true),
                new Asking("desk", "alice")));
    add.handle(
        Map.of("project", "ledger", "handle", "bob", "role", "VIEWER"),
        new Asking("desk", "alice"));
    verify(members)
        .assign(
            "ledger", "bob", io.aeyer.plowshare.server.archive.ProjectRole.VIEWER, "alice", true);
  }
}
