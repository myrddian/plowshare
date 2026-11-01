package io.aeyer.plowshare.server.ws;

import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.files.WorkspaceChanges;
import io.aeyer.plowshare.server.session.*;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class FileChangeHintTest {
  private final SessionRegistry sessions = new SessionRegistry();
  private final PresenceRegistry presences = new PresenceRegistry();
  private final WorkspaceChanges changes = mock(WorkspaceChanges.class);
  private final FileChannelHandler handler =
      new FileChannelHandler(
          sessions,
          presences,
          mock(ProjectRoots.class),
          TestProjectMembers.allowed(),
          Duration.ofSeconds(1));

  private WebSocketSession connect(String session) throws Exception {
    when(changes.enabled()).thenReturn(true);
    handler.useWorkspaceChanges(changes);
    var socket = mock(WebSocketSession.class);
    when(socket.getUri())
        .thenReturn(URI.create("ws://example.invalid/v1/files?session=" + session + "&ready=1"));
    var attributes = new ConcurrentHashMap<String, Object>();
    attributes.put(EventChannelHandler.HANDLE, "alice");
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    handler.afterConnectionEstablished(socket);
    presences.declare(new Presence(session, "fixture", "/workspace", "project"));
    return socket;
  }

  @Test
  void hintsUseOnlyTheAuthenticatedSocketsCurrentRootAndRejectExtraClaims() throws Exception {
    var socket = connect("session");
    clearInvocations(changes);
    handler.handleTextMessage(
        socket, new TextMessage("{\"type\":\"workspace.changed\",\"project\":\"other\"}"));
    verifyNoInteractions(changes);
    handler.handleTextMessage(socket, new TextMessage("{\"type\":\"workspace.changed\"}"));
    verify(changes).changed(new WorkspaceChanges.Change(Home.of("project"), "alice", "session"));
  }

  @Test
  void aDisplacedSocketCannotSubmitHintsUnderTheReplacementSession() throws Exception {
    var before = connect("session");
    var after = connect("session");
    clearInvocations(changes);
    handler.handleTextMessage(before, new TextMessage("{\"type\":\"workspace.changed\"}"));
    verifyNoInteractions(changes);
    handler.handleTextMessage(after, new TextMessage("{\"type\":\"workspace.changed\"}"));
    verify(changes).changed(new WorkspaceChanges.Change(Home.of("project"), "alice", "session"));
  }
}
