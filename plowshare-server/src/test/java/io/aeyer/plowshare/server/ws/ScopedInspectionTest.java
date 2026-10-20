package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.events.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Project selection permits service inspection, never global or foreign resource enumeration. */
class ScopedInspectionTest {
  @Test
  void serviceReadsNeedExplicitProjectAndRecheckLiveRole() {
    var members = mock(ProjectMembers.class);
    var authorizer =
        new ProjectAuthorization(
            mock(ResourceScopeRepository.class), members, mock(AdminStore.class));
    String principal = "@service/fixture";
    for (String operation : List.of("schedule.files", "schedule.list")) {
      assertThrows(
          CallerFault.class,
          () -> authorizer.require(operation, AccessRequest.project(null), principal));
      authorizer.require(operation, AccessRequest.project("app"), principal);
      doThrow(new CallerFault("revoked"))
          .when(members)
          .requireRole("app", principal, ProjectRole.VIEWER);
      assertThrows(
          CallerFault.class,
          () -> authorizer.require(operation, AccessRequest.project("app"), principal));
      reset(members);
    }
  }

  @Test
  void malformedSelectionDoesNotReadScheduleProjections() {
    var schedules = mock(ScheduleStore.class);
    var definitions = mock(ScheduleDefinitions.class);
    var handler = new ScheduleListHandler(schedules, () -> definitions);
    var invalid = new HashMap<String, Object>();
    invalid.put("project", null);
    for (Map<String, Object> payload :
        List.<Map<String, Object>>of(
            invalid, Map.of("project", "personal:owner"), Map.of("project", " app")))
      assertThrows(
          CallerFault.class, () -> handler.handle(payload, new Asking("session", "owner")));
    verifyNoInteractions(schedules, definitions);
  }

  @Test
  void scopedInspectionCannotEnumerateAnotherProjectsProjection() {
    var schedules = mock(ScheduleStore.class);
    var definitions = mock(ScheduleDefinitions.class);
    when(schedules.page("owner", "app", new SchedulePage(null, 50))).thenReturn(List.of());
    assertEquals(
        List.of(),
        new ScheduleListHandler(schedules, () -> definitions)
            .handle(Map.of("project", "app"), new Asking("session", "owner"))
            .payload());
    verify(definitions, times(2)).requireRead("owner", "app");
    verify(definitions, never()).list(anyString(), anyString());
    verify(schedules, never()).list();
  }

  @Test
  void documentOutlineScopeIsClosedAndValidatedBeforeResolution() {
    String document = UUID.randomUUID().toString();
    DocumentDetailHandler.validate(
        Map.of(
            "document",
            document,
            "scope",
            Map.of("kind", "project", "project", "app", "includeGlobal", false)));
    for (Map<String, Object> scope :
        List.<Map<String, Object>>of(
            Map.of("kind", "project"),
            Map.of("kind", "personal", "project", "app"),
            Map.of("kind", "project", "project", " app"),
            Map.of("kind", "project", "project", "app", "extra", true)))
      assertThrows(
          CallerFault.class,
          () -> DocumentDetailHandler.validate(Map.of("document", document, "scope", scope)));
    assertThrows(
        RuntimeException.class,
        () ->
            DocumentDetailHandler.validate(
                Map.of("document", "invalid", "scope", Map.of("kind", "global_read"))));
  }
}
