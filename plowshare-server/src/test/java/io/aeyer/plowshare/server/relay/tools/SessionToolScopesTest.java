package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SessionToolScopesTest {
  @TempDir Path root;
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ToolGrants definitions = mock(ToolGrants.class);
  final AtomicBoolean live = new AtomicBoolean(true);
  SessionToolScopes scopes;
  boolean application;

  @BeforeEach
  void setup() {
    when(projects.id("fixture")).thenReturn(1L);
    when(projects.personalOwner(anyString())).thenReturn(Optional.empty());
    when(members.mayWork(eq("fixture"), anyString())).thenReturn(true);
    when(definitions.acceptsDynamic(eq(1L), anyString(), eq("ticketer"), anyString()))
        .thenReturn(true);
    scopes =
        new SessionToolScopes(
            id -> application ? Optional.of(root) : Optional.empty(),
            projects,
            members,
            definitions,
            (a, s, c) -> live.get(),
            () -> ScopedTools.reservedNames(Set.of("memory_read")));
  }

  ToolScopes.Connect request(String grants) {
    return new ToolScopes.Connect(
        "fixture", "linear", "linear", "linear_", List.of(grants), List.of("ticketer"), 30);
  }

  void manifest(String grant) throws Exception {
    application = true;
    Files.writeString(
        root.resolve("plowshare.json"),
        """
        {"executionAccount":"worker","toolScopes":[{"scope":"linear","provider":"linear","grants":["%s"]}],
         "toolGrants":[{"toolScope":"linear","agent":"ticketer"}]}
        """
            .formatted(grant));
  }

  @Test
  void interactive_owner_automatically_assigns_only_the_selected_agent_and_session() {
    var connection = scopes.connect("alice", "session", "socket", request("*"));
    assertEquals(connection, scopes.connect("alice", "session", "socket", request("*")));
    assertTrue(scopes.permits(connection, "alice", "ticketer", "session", "linear_new_tool"));
    assertFalse(scopes.permits(connection, "bob", "ticketer", "session", "linear_new_tool"));
    assertFalse(scopes.permits(connection, "alice", "reviewer", "session", "linear_new_tool"));
    assertFalse(scopes.permits(connection, "alice", "ticketer", "another", "linear_new_tool"));
    assertEquals(List.of(), scopes.connections("fixture", "bob"));
    assertEquals(List.of(), scopes.connections("another", "alice"));
    var other = scopes.connect("bob", "session", "socket", request("*"));
    assertNotEquals(connection.provider(), other.provider());
  }

  @Test
  void app_connect_requires_execution_identity_and_explicit_assignments() throws Exception {
    manifest("linear_read");
    assertThrows(
        CallerFault.class, () -> scopes.connect("alice", "s", "c", request("linear_read")));
    assertThrows(CallerFault.class, () -> scopes.connect("worker", "s", "c", request("*")));
    var connection = scopes.connect("worker", "s", "c", request("linear_read"));
    assertTrue(scopes.permits(connection, "worker", "ticketer", "durable-job", "linear_read"));
    assertFalse(scopes.permits(connection, "worker", "ticketer", "durable-job", "linear_write"));
    manifest("linear_write");
    assertFalse(scopes.permits(connection, "worker", "ticketer", "durable-job", "linear_read"));
    assertTrue(scopes.connections("fixture", "worker").isEmpty());
  }

  @Test
  void disconnect_or_new_socket_cannot_revive_the_previous_catalogue_namespace() {
    var first = scopes.connect("alice", "s", "c", request("*"));
    assertThrows(CallerFault.class, () -> scopes.connect("alice", "s", "new", request("*")));
    assertThrows(
        CallerFault.class,
        () -> scopes.disconnect("alice", "other", new ToolScopes.Disconnect("fixture", "linear")));
    scopes.disconnect("alice", "s", new ToolScopes.Disconnect("fixture", "linear"));
    var second = scopes.connect("alice", "s", "c", request("*"));
    assertNotEquals(first.provider(), second.provider());
    assertFalse(scopes.permits(first, "alice", "ticketer", "s", "linear_read"));
    live.set(false);
    assertEquals(List.of(), scopes.connections("fixture", "alice"));
    assertThrows(CallerFault.class, () -> scopes.connect("alice", "s", "c", request("*")));
  }

  @Test
  void work_access_dynamic_opt_in_and_reserved_prefixes_are_independent_guards() {
    when(definitions.acceptsDynamic(any(), any(), any(), any())).thenReturn(false);
    assertThrows(CallerFault.class, () -> scopes.connect("alice", "s", "c", request("*")));
    when(definitions.acceptsDynamic(any(), any(), any(), any())).thenReturn(true);
    assertThrows(
        CallerFault.class,
        () ->
            scopes.connect(
                "alice",
                "s",
                "c",
                new ToolScopes.Connect(
                    "fixture", "board", "board", "board_", List.of("*"), List.of("ticketer"), 30)));
    when(members.mayWork("fixture", "alice")).thenReturn(false);
    assertThrows(CallerFault.class, () -> scopes.connect("alice", "s", "c", request("*")));
  }

  @Test
  void personal_scope_cannot_be_claimed_by_another_account() {
    when(projects.personalOwner("fixture")).thenReturn(Optional.of("alice"));
    assertThrows(CallerFault.class, () -> scopes.connect("bob", "s", "c", request("*")));
  }
}
