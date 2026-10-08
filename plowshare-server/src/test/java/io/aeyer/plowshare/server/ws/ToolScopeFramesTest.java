package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ToolScopes;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.ToolScopeConnections;
import java.util.*;
import org.junit.jupiter.api.Test;

/** DTO conversion completes before authenticated connection authority is consulted. */
class ToolScopeFramesTest {
  @Test
  void forwards_authenticated_socket_identity_and_never_accepts_an_account_field() {
    var connections = mock(ToolScopeConnections.class);
    var frames = new ToolScopeFrames(connections).frames();
    var asking = new Asking("session", "alice", "socket");
    var payload =
        Map.<String, Object>of(
            "project",
            "fixture",
            "scope",
            "linear",
            "provider",
            "linear",
            "prefix",
            "linear_",
            "grants",
            List.of("*"),
            "agents",
            List.of("ticketer"),
            "leaseSeconds",
            30);
    var request =
        new ToolScopes.Connect(
            "fixture", "linear", "linear", "linear_", List.of("*"), List.of("ticketer"), 30);
    var reply =
        new ToolScopes.Connection(
            "fixture",
            "linear",
            "linear",
            "p-abc",
            "alice",
            "linear_",
            List.of("*"),
            List.of("ticketer"),
            30);
    when(connections.connect("alice", "session", "socket", request)).thenReturn(reply);
    frames.get(FrameTypes.TOOL_SCOPE_CONNECT).handle(payload, asking);
    verify(connections).connect("alice", "session", "socket", request);
    clearInvocations(connections);
    var invalid = new HashMap<>(payload);
    invalid.put("account", "intruder");
    assertThrows(
        CallerFault.class, () -> frames.get(FrameTypes.TOOL_SCOPE_CONNECT).handle(invalid, asking));
    assertThrows(
        CallerFault.class,
        () -> frames.get(FrameTypes.TOOL_SCOPE_LIST).handle(Map.of("project", 123), asking));
    assertThrows(
        CallerFault.class,
        () ->
            frames
                .get(FrameTypes.TOOL_SCOPE_LIST)
                .handle(Map.of("project", "fixture"), new Asking("s")));
    verifyNoInteractions(connections);
  }
}
