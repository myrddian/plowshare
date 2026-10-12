package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.HostedToolScopes.*;
import io.aeyer.plowshare.protocol.frames.*;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.HostedToolScopes;
import java.util.*;
import org.junit.jupiter.api.Test;

class HostedToolScopeFramesTest {
  static final String ID = "11111111-1111-1111-1111-111111111111", EXECUTOR = "@service/" + ID;
  final HostedToolScopes hosting = mock(HostedToolScopes.class);
  final FrameRouter router = new FrameRouter(new HostedToolScopeFrames(hosting).frames());
  final Asking asking = new Asking("worker-session", EXECUTOR, "worker-socket");

  Outcome send(String type, Map<String, Object> body) {
    return router.route(new Envelope("fixture", type, Envelope.CURRENT_VERSION, body), asking);
  }

  @Test
  void complete_typed_request_receives_authenticated_socket_and_payload_cannot_choose_an_owner() {
    var id = UUID.fromString(ID);
    when(hosting.workerStatus(EXECUTOR, "worker-session", "worker-socket", new WorkerQuery(id)))
        .thenReturn(new WorkerStatus(id, null));
    assertEquals(Code.OK, send("tool.scope.worker.status", Map.of("binding", ID)).code());
    assertEquals(
        Code.BAD_REQUEST,
        send("tool.scope.worker.status", Map.of("binding", ID, "owner", "alice")).code());
    assertEquals(
        Code.BAD_REQUEST, send("tool.scope.worker.status", Map.of("binding", "1-1-1-1-1")).code());
    verify(hosting, times(1)).workerStatus(anyString(), anyString(), anyString(), any());
  }

  @Test
  void malformed_nested_payload_and_missing_explicit_nulls_refuse_before_service() {
    var body = new HashMap<String, Object>();
    body.put("binding", ID);
    body.put("registration", ID);
    body.put("purpose", "DISCOVERY");
    assertEquals(Code.BAD_REQUEST, send("tool.scope.worker.credential", body).code());
    body.put("invocation", null);
    body.put("purpose", "EXECUTION");
    assertEquals(Code.BAD_REQUEST, send("tool.scope.worker.credential", body).code());
    var publication = new HashMap<String, Object>();
    publication.put("requestId", ID);
    publication.put("project", "personal:alice");
    publication.put("topic", "tool.fixture.catalog");
    publication.put("text", "fixture");
    publication.put("occurredAt", "2026-10-13T00:00:00Z");
    publication.put("correlationId", null);
    publication.put("parentTopic", null);
    publication.put("parentEventId", null);
    publication.put("owner", "alice");
    assertEquals(
        Code.BAD_REQUEST,
        send(
                "tool.scope.worker.publish",
                Map.of("binding", ID, "registration", ID, "publication", publication))
            .code());
    verifyNoInteractions(hosting);
  }

  @Test
  void
      finite_worker_operations_delegate_to_strict_service_without_granting_ordinary_personal_access() {
    var members = mock(ProjectMembers.class);
    var accounts = mock(AdminStore.class);
    when(accounts.isEnabled(EXECUTOR)).thenReturn(true);
    router.useAuthorization(
        new ProjectAuthorization(mock(ResourceScopeRepository.class), members, accounts));
    router.usePersonalAccess(
        new io.aeyer.plowshare.server.personal.PersonalAccess(mock(ConversationStore.class)));
    when(hosting.workerStatus(anyString(), anyString(), anyString(), any()))
        .thenReturn(new WorkerStatus(UUID.fromString(ID), null));
    assertEquals(Code.OK, send("tool.scope.worker.status", Map.of("binding", ID)).code());
    verifyNoInteractions(members);
    when(hosting.status(anyString(), any())).thenThrow(new CallerFault("Forbidden owner"));
    assertEquals(
        Code.BAD_REQUEST,
        send("tool.scope.host.status", Map.of("project", "personal:alice", "binding", ID)).code());
    assertEquals(Code.NOT_FOUND, send("tool.scope.worker.arbitrary", Map.of("binding", ID)).code());
    when(accounts.isEnabled(EXECUTOR)).thenReturn(false);
    assertEquals(Code.BAD_REQUEST, send("tool.scope.worker.status", Map.of("binding", ID)).code());
    verify(hosting, times(1)).workerStatus(anyString(), anyString(), anyString(), any());
  }
}
