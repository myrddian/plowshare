package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** WebSocket admission checks the actual schedule scope before its Application policy runs. */
class ServiceScheduleAuthorizationTest {
  private final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final AdminStore accounts = mock(AdminStore.class);
  private final ProjectAuthorization access =
      new ProjectAuthorization(resources, members, accounts);
  private final AccessRequest.Resource schedule =
      new AccessRequest.Resource(ResourceScopeRepository.Kind.SCHEDULE, "scheduled-1-daily");
  private static final String SERVICE = "@service/fixture";

  @Test
  void pauseAndResumeAdmitServiceProjectWorkWithoutManagerOrAdminElevation() {
    when(resources.projects(schedule)).thenReturn(List.of("application"));
    for (boolean paused : List.of(true, false)) {
      access.require(
          "schedule.pause",
          AccessRequestDecoder.decode(
              "schedule.pause", Map.of("schedule", schedule.id(), "paused", paused)),
          SERVICE);
    }
    verify(members, times(2)).requireRole("application", SERVICE, ProjectRole.CONTRIBUTOR);
    verifyNoInteractions(accounts);
  }

  @Test
  void missingOrGlobalScheduleNeverBorrowsAnExplicitProjectOrConversation() {
    for (List<String> scope : List.of(List.<String>of(), Arrays.asList((String) null))) {
      when(resources.projects(schedule)).thenReturn(scope);
      for (boolean paused : List.of(true, false)) {
        assertThrows(
            CallerFault.class,
            () ->
                access.require(
                    "schedule.pause",
                    AccessRequestDecoder.decode(
                        "schedule.pause",
                        Map.of(
                            "schedule",
                            schedule.id(),
                            "paused",
                            paused,
                            "project",
                            "application",
                            "conversation",
                            "owned-conversation")),
                    SERVICE));
      }
    }
    verifyNoInteractions(members, accounts);
  }

  @Test
  void currentTokenCeilingAndForeignProjectScopesRefuseBothDirections() {
    when(resources.projects(schedule)).thenReturn(List.of("foreign"));
    doThrow(new CallerFault("Scope revoked"))
        .when(members)
        .requireRole("foreign", SERVICE, ProjectRole.CONTRIBUTOR);
    for (boolean paused : List.of(true, false)) {
      assertFalse(
          access.allowed(
              "schedule.pause",
              AccessRequestDecoder.decode(
                  "schedule.pause", Map.of("schedule", schedule.id(), "paused", paused)),
              SERVICE));
    }
    verifyNoInteractions(accounts);
  }

  @Test
  void definitionEnrollmentAndRemovalRemainOutsideServiceOperations() {
    for (String operation :
        List.of("schedule.define", "schedule.save", "schedule.sync", "schedule.forget")) {
      assertFalse(
          access.allowed(
              operation,
              AccessRequestDecoder.decode(
                  operation, Map.of("project", "application", "schedule", schedule.id())),
              SERVICE));
    }
    verifyNoInteractions(resources, members, accounts);
  }

  @Test
  void globalHumanResumeStillRequiresServerAdministrationDespiteInjectedScope() {
    doThrow(new CallerFault("Administrator required")).when(accounts).requireServerAdmin("human");
    assertFalse(
        access.allowed(
            "schedule.pause",
            AccessRequestDecoder.decode(
                "schedule.pause",
                Map.of("schedule", "legacy", "paused", false, "project", "application")),
            "human"));
    verify(accounts).requireServerAdmin("human");
    verifyNoInteractions(members);
  }
}
