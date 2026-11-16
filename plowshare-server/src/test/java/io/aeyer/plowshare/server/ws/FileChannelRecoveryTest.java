package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.files.SessionGoneException;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.RecoveredWorkspaces;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.session.WorkspaceBindingRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Actual file adapter and session/presence owners; only durable binding storage is mocked. */
class FileChannelRecoveryTest {
  @Test
  void a_reopened_file_client_recovers_its_private_project_without_reusing_a_bound_snapshot()
      throws Exception {
    var sessions = new SessionRegistry();
    var presences = new PresenceRegistry();
    var bindings = mock(WorkspaceBindingRepository.class);
    var original = new AtomicReference<String>();
    when(bindings.bind(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              original.compareAndSet(null, call.getArgument(1));
              return original.get();
            });
    var handler =
        new FileChannelHandler(
            sessions,
            presences,
            mock(ProjectRoots.class),
            TestProjectMembers.allowed(),
            TestProjectMembers.noCreation(),
            TestProjectMembers.allRegistered());
    var recovery = new RecoveredWorkspaces(bindings, sessions);
    String logical =
        recovery.attach("a".repeat(64), "original", "alice", "machine", "/fixture/work", "project");
    handler.useWorkspaceRecovery(recovery);
    String project = ClientProjects.key("project", logical, "alice");
    var first = socket("original", project);
    handler.afterConnectionEstablished(first);
    var previousSnapshot = handler.bound("original");
    previousSnapshot.requireCurrent("original");
    handler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
    assertFalse(handler.isConnected("original"));
    var reopened = socket("new-transport", project);
    handler.afterConnectionEstablished(reopened);
    assertTrue(handler.isConnected("original"));
    assertTrue(handler.isConnected("new-transport"));
    assertEquals(logical, sessions.canonical("new-transport"));
    assertEquals(project, presences.rootedBy(logical).orElseThrow().project());
    assertTrue(ClientProjects.visible(project, sessions.canonical("new-transport"), "alice"));
    assertFalse(ClientProjects.visible(project, "new-transport", "alice"));
    assertThrows(SessionGoneException.class, () -> previousSnapshot.requireCurrent("original"));
    handler.bound("new-transport").requireCurrent("new-transport");
    handler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
    assertTrue(
        handler.isConnected("original"), "a late close cannot withdraw the recovered connection");
    handler.afterConnectionClosed(reopened, CloseStatus.GOING_AWAY);
  }

  private static WebSocketSession socket(String session, String project) {
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(
            URI.create(
                "ws://fixture.example/v1/files?session="
                    + session
                    + "&project="
                    + URLEncoder.encode(project, StandardCharsets.UTF_8)
                    + "&machine=machine&root=%2Ffixture%2Fwork"));
    when(socket.getAcceptedProtocol()).thenReturn("plowshare-file-recovery-v1." + "a".repeat(64));
    var attributes = new ConcurrentHashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, "alice");
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    return socket;
  }
}
