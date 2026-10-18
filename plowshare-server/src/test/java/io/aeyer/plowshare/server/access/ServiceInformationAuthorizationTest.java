package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Only reviewed read routes may resolve universal audiences in their typed information services.
 */
class ServiceInformationAuthorizationTest {
  @Test
  void general_read_delegation_never_admits_private_work_or_administration() {
    var members = mock(ProjectMembers.class);
    var resources = mock(ResourceScopeRepository.class);
    var access = new ProjectAuthorization(resources, members, mock(AdminStore.class));
    String account = "@service/00000000-0000-0000-0000-000000000001";
    for (String operation :
        List.of(
            "information.list",
            "information.documents.list",
            "information.read",
            "information.events",
            "information.interchange.export",
            "document.list",
            "document.detail",
            "document.chunk",
            "document.retrieve",
            "document.rank",
            "document.stance",
            "document.search",
            "document.citations"))
      assertDoesNotThrow(
          () ->
              access.require(
                  operation,
                  AccessRequestDecoder.decode(
                      operation, Map.of("scope", Map.of("kind", "global_read"))),
                  account));
    verifyNoInteractions(members, resources);
    for (String operation :
        List.of(
            "information.upload",
            "information.share",
            "information.policy.set",
            "information.policy.get",
            "information.job.status",
            "information.future",
            "admin.accounts"))
      assertThrows(
          CallerFault.class,
          () ->
              access.require(
                  operation,
                  AccessRequestDecoder.decode(
                      operation, Map.of("scope", Map.of("kind", "global_read"))),
                  account),
          operation);
  }
}
