package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.proc.ProcAudit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProcFramesTest {
  @Test
  void metadataSelectorsReachTheOwningAuditWithoutGrantingContentAccess() {
    var accounts = mock(io.aeyer.plowshare.server.auth.AdminStore.class);
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    var resources = mock(io.aeyer.plowshare.server.access.ResourceScopeRepository.class);
    when(accounts.isEnabled("alice")).thenReturn(true);
    var repository = mock(io.aeyer.plowshare.server.proc.ProcRepository.class);
    var live =
        new io.aeyer.plowshare.server.proc.ProcRepository.Live(java.time.Instant.now(), List.of());
    var query =
        new io.aeyer.plowshare.server.proc.ProcQuery(
            "server", "personal:bob", null, null, null, null, null, null);
    when(repository.conversations("alice", query, live))
        .thenReturn(
            new io.aeyer.plowshare.server.proc.ProcViews.Conversations(
                live.at(), query, List.of(), 0, null, List.of()));
    var router = new FrameRouter(new ProcFrames(new ProcAudit(repository, () -> live)).frames());
    router.useAuthorization(
        new io.aeyer.plowshare.server.access.ProjectAuthorization(resources, members, accounts));
    var outcome =
        router.route(
            new io.aeyer.plowshare.protocol.frames.Envelope(
                "proc",
                "proc.list",
                io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
                Map.of("visibility", "server", "project", "personal:bob")),
            new Asking("session", "alice"));
    assertEquals(io.aeyer.plowshare.protocol.frames.Code.OK, outcome.code());
    verify(repository).conversations("alice", query, live);
    verifyNoInteractions(members, resources);
    when(repository.conversations("alice", query, live))
        .thenThrow(new CallerFault("fleet audit refused"));
    assertEquals(
        io.aeyer.plowshare.protocol.frames.Code.BAD_REQUEST,
        router
            .route(
                new io.aeyer.plowshare.protocol.frames.Envelope(
                    "denied",
                    "proc.list",
                    io.aeyer.plowshare.protocol.frames.Envelope.CURRENT_VERSION,
                    Map.of("visibility", "server", "project", "personal:bob")),
                new Asking("session", "alice"))
            .code());
  }

  @Test
  void malformedOrUnauthenticatedRequestsNeverReachAuditReads() {
    var audit = mock(ProcAudit.class);
    var frame = new ProcFrames(audit).frames().get(FrameTypes.PROC_LIST);
    for (var input :
        List.of(
            Map.<String, Object>of("active", "true"),
            Map.<String, Object>of("limit", 1.5),
            Map.<String, Object>of("project", 5),
            Map.<String, Object>of("account", "bob"),
            Map.<String, Object>of("visibility", "admin")))
      assertThrows(CallerFault.class, () -> frame.handle(input, new Asking("session", "alice")));
    assertThrows(CallerFault.class, () -> frame.handle(Map.of(), new Asking("session")));
    verifyNoInteractions(audit);
  }
}
