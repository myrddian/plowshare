package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.access.AccessRequest;
import io.aeyer.plowshare.server.access.ProjectAuthorization;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the handler's wire projection together with the router's current-access filter. */
class FiringListAuthorizationTest {
  private static final Asking ASKING = new Asking("session-1", "viewer");
  private final FiringStore firings = mock(FiringStore.class);
  private final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private FrameRouter router;

  @BeforeEach
  void setUp() {
    router = new FrameRouter(Map.of(FrameTypes.FIRING_LIST, new FiringListHandler(firings)));
    router.useAuthorization(new ProjectAuthorization(resources, members, mock(AdminStore.class)));
  }

  @Test
  void populated_history_keeps_only_owned_triggers_with_current_project_access() throws Exception {
    var own = firing("fir_own", "own");
    when(resources.triggerOwnedBy("own", "viewer")).thenReturn(true);
    when(resources.projects(trigger("own"))).thenReturn(List.of("project-a"));
    when(resources.triggerOwnedBy("revoked", "viewer")).thenReturn(true);
    when(resources.projects(trigger("revoked"))).thenReturn(List.of("project-b"));
    doThrow(new CallerFault("Project access was revoked"))
        .when(members)
        .requireRole("project-b", "viewer", ProjectRole.VIEWER);
    when(firings.list(null, null, 0, 50))
        .thenReturn(
            List.of(
                firing("fir_foreign", "foreign"),
                own,
                firing("fir_revoked", "revoked"),
                firing("fir_deleted", "deleted"),
                firing("fir_unmatched", null)));

    var outcome = list();

    assertEquals(Code.OK, outcome.code());
    assertEquals(List.of(FiringView.of(own)), outcome.payload());
    var json = FrameJson.answering();
    var wire = json.valueToTree(outcome.payload()).get(0);
    assertEquals("fir_own", wire.get("id").textValue());
    assertEquals(
        "observation", json.readTree(wire.get("data").textValue()).get("text").textValue());
  }

  @Test
  void history_rechecks_access_on_each_request() {
    var own = firing("fir_own", "own");
    when(resources.triggerOwnedBy("own", "viewer")).thenReturn(true);
    when(resources.projects(trigger("own"))).thenReturn(List.of("project-a"));
    when(firings.list(null, null, 0, 50)).thenReturn(List.of(own));
    assertEquals(List.of(FiringView.of(own)), list().payload());

    doThrow(new CallerFault("Project access was revoked"))
        .when(members)
        .requireRole("project-a", "viewer", ProjectRole.VIEWER);

    var outcome = list();
    assertEquals(Code.OK, outcome.code());
    assertEquals(List.of(), outcome.payload());
  }

  @Test
  void empty_history_remains_successful() {
    when(firings.list(null, null, 0, 50)).thenReturn(List.of());
    var outcome = list();
    assertEquals(Code.OK, outcome.code());
    assertEquals(List.of(), outcome.payload());
  }

  private Outcome list() {
    return router.route(FrameParity.frame(FrameTypes.FIRING_LIST, "{}"), ASKING);
  }

  private static AccessRequest.Resource trigger(String name) {
    return new AccessRequest.Resource(ResourceScopeRepository.Kind.TRIGGER, name);
  }

  private static FiringRecord firing(String id, String trigger) {
    return new FiringRecord(
        id,
        "manual",
        new EventPayload.Text("observation"),
        null,
        null,
        trigger,
        trigger == null ? null : "bot",
        trigger == null ? "unmatched" : "queued",
        null,
        null,
        null,
        Instant.parse("2026-10-06T00:00:00Z"),
        null,
        null,
        null);
  }
}
