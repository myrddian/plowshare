package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Central admission preserves token scope before Relay checks its own port/lease authority. */
class ServiceRelayAuthorizationTest {
  private static final String SERVICE = "@service/00000000-0000-0000-0000-000000000001";

  @Test
  void service_scope_connections_use_project_work_authority_and_list_is_read_only() {
    assertEquals(ProjectRole.CONTRIBUTOR, ProjectAuthorization.required("tool.scope.connect"));
    assertEquals(ProjectRole.CONTRIBUTOR, ProjectAuthorization.required("tool.scope.disconnect"));
    assertEquals(ProjectRole.VIEWER, ProjectAuthorization.required("tool.scope.list"));
  }

  @Test
  void service_relay_operations_require_the_explicit_project_and_current_role() {
    var resources = mock(ResourceScopeRepository.class);
    var members = mock(ProjectMembers.class);
    var accounts = mock(AdminStore.class);
    var access = new ProjectAuthorization(resources, members, accounts);
    for (String operation :
        List.of(
            "relay.topics",
            "relay.log",
            "relay.process",
            "relay.operate",
            "relay.publish",
            "relay.consume",
            "relay.ack")) {
      var request =
          AccessRequestDecoder.decode(
              operation, Map.of("project", "privacy", "topic", "privacy.scan.requests"));
      assertTrue(request.resources().isEmpty());
      access.require(operation, request, SERVICE);
      var role =
          operation.equals("relay.operate")
              ? ProjectRole.MANAGER
              : operation.equals("relay.log") || operation.equals("relay.topics")
                  ? ProjectRole.VIEWER
                  : ProjectRole.CONTRIBUTOR;
      verify(members).requireRole("privacy", SERVICE, role);
      clearInvocations(members);
      assertThrows(
          CallerFault.class,
          () ->
              access.require(
                  operation,
                  AccessRequestDecoder.decode(operation, Map.of("system", true)),
                  SERVICE));
    }
    verifyNoInteractions(resources, accounts);
    doThrow(new CallerFault("No current token scope"))
        .when(members)
        .requireRole("other", SERVICE, ProjectRole.CONTRIBUTOR);
    assertFalse(
        access.allowed(
            "relay.publish",
            AccessRequestDecoder.decode(
                "relay.publish", Map.of("project", "other", "topic", "privacy.scan.requests")),
            SERVICE));
    assertFalse(
        access.allowed(
            "relay.future",
            AccessRequestDecoder.decode("relay.future", Map.of("project", "privacy")),
            SERVICE));
  }
}
