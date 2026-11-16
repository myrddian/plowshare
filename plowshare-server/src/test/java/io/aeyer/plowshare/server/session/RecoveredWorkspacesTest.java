package io.aeyer.plowshare.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class RecoveredWorkspacesTest {
  private record Binding(
      String session, String account, String machine, String root, String project) {}

  private final Map<String, Binding> saved = new ConcurrentHashMap<>();
  private final WorkspaceBindingRepository bindings =
      (hash, session, account, machine, root, project) -> {
        Binding first =
            saved.computeIfAbsent(
                hash, ignored -> new Binding(session, account, machine, root, project));
        if (!first.account().equals(account)
            || !first.machine().equals(machine)
            || !first.root().equals(root)
            || !first.project().equals(project)) throw new CallerFault("Attachment mismatch");
        return first.session();
      };
  private final SessionRegistry sessions = new SessionRegistry();
  private final RecoveredWorkspaces recovery = new RecoveredWorkspaces(bindings, sessions);
  private final String key = "a".repeat(64);

  @Test
  void reopening_with_a_new_transport_recovers_the_original_session_and_listener() {
    sessions.attach("first", Role.LISTENER, new Object(), "alice");
    String original = recovery.attach(key, "first", "alice", "machine", "/work", "project");
    assertTrue(original.startsWith(WorkspaceRecovery.LOGICAL_PREFIX));
    Object listener = new Object();
    sessions.attach("reopened", Role.LISTENER, listener, "alice");
    assertEquals(
        original, recovery.attach(key, "reopened", "alice", "machine", "/work", "project"));
    assertSame(
        listener,
        sessions.find(original).orElseThrow().attached(Role.LISTENER, Object.class).orElseThrow());
    assertSame(sessions.find(original).orElseThrow(), sessions.find("reopened").orElseThrow());
    assertTrue(saved.keySet().stream().noneMatch(key::equals));
  }

  @Test
  void
      changing_workspaces_does_not_retarget_the_old_file_provider_or_transfer_scope_by_public_id() {
    Object listener = new Object();
    sessions.attach("transport", Role.LISTENER, listener, "alice");
    String first = recovery.attach(key, "transport", "alice", "machine", "/first", "first");
    Object provider = new Object();
    sessions.attach(first, Role.FILE_PROVIDER, provider, "alice");
    String second =
        recovery.attach("b".repeat(64), "transport", "alice", "machine", "/second", "second");
    assertSame(
        provider,
        sessions
            .find(first)
            .orElseThrow()
            .attached(Role.FILE_PROVIDER, Object.class)
            .orElseThrow());
    assertTrue(
        sessions.find(second).orElseThrow().attached(Role.FILE_PROVIDER, Object.class).isEmpty());
    assertTrue(sessions.logicalSessions("transport").containsAll(List.of(first, second)));
    assertThrows(
        CallerFault.class,
        () -> recovery.attach(null, "transport", "alice", "machine", "/second", "second"));
    sessions.attach("transport", Role.LISTENER, new Object(), "alice");
    assertEquals("transport", sessions.canonical("transport"));
    assertTrue(sessions.logicalSessions("transport").stream().noneMatch(first::equals));
    assertEquals(first, recovery.attach(key, "transport", "alice", "machine", "/first", "first"));
  }

  @Test
  void the_key_cannot_authorize_a_different_account_machine_root_or_project() {
    recovery.attach(key, "first", "alice", "machine", "/work", "project");
    assertThrows(
        CallerFault.class, () -> recovery.attach(key, "new", "bob", "machine", "/work", "project"));
    assertThrows(
        CallerFault.class, () -> recovery.attach(key, "new", "alice", "other", "/work", "project"));
    assertThrows(
        CallerFault.class,
        () -> recovery.attach(key, "new", "alice", "machine", "/other", "project"));
    assertThrows(
        CallerFault.class, () -> recovery.attach(key, "new", "alice", "machine", "/work", "other"));
    assertTrue(sessions.find("new").isEmpty());
  }

  @Test
  void missing_proof_does_not_recover_by_matching_location_or_account() {
    recovery.attach(key, "first", "alice", "machine", "/work", "project");
    assertEquals("new", recovery.attach(null, "new", "alice", "machine", "/work", "project"));
    assertThrows(
        CallerFault.class,
        () -> recovery.attach("short", "new", "alice", "machine", "/work", "project"));
  }
}
