package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.auth.AdminStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class ApplicationStorageAuthorizationTest {
  @Test
  void placement_is_server_administration_and_service_tokens_cannot_admit_it() {
    var scopes = mock(ResourceScopeRepository.class);
    var members = mock(ProjectMembers.class);
    var accounts = mock(AdminStore.class);
    var authorization = new ProjectAuthorization(scopes, members, accounts);
    for (String operation : new String[] {"application.create", "application.storage.set"}) {
      assertThrows(
          CallerFault.class,
          () -> authorization.require(operation, AccessRequest.project("app"), "@service/fixture"));
      authorization.require(operation, AccessRequest.project("app"), "operator");
      verify(accounts, atLeastOnce()).requireServerAdmin("operator");
      doThrow(new CallerFault("Server administrator required"))
          .when(accounts)
          .requireServerAdmin("reader");
      assertThrows(
          CallerFault.class,
          () -> authorization.require(operation, AccessRequest.project("app"), "reader"));
    }
    verifyNoInteractions(scopes, members);
  }
}
