package io.aeyer.plowshare.server.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.auth.AccountActivity;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.tools.HostedInvocationAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolAuthority;
import io.aeyer.plowshare.server.relay.tools.RelayToolRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactAuthorityTest {
  @Test
  void
      derives_owner_and_project_and_rechecks_current_provider_contract_membership_and_personal_fences() {
    var ledger = mock(RelayToolRepository.class);
    var members = mock(ProjectMembers.class);
    var projects = mock(ProjectWorkspaces.class);
    var accounts = mock(AccountActivity.class);
    var tools = mock(RelayToolAuthority.class);
    var hosting = mock(HostedInvocationAuthority.class);
    var intent = ArtifactAdmissionsTest.intent(UUID.randomUUID());
    var stored = new RelayToolRepository.Stored(intent, null);
    when(ledger.find(intent.id())).thenReturn(Optional.of(stored));
    when(accounts.isEnabled("producer")).thenReturn(true);
    when(accounts.isEnabled("owner")).thenReturn(true);
    when(accounts.isEnabled("foreign")).thenReturn(true);
    when(projects.id("atlas")).thenReturn(1L);
    when(projects.personalOwner("atlas")).thenReturn(Optional.empty());
    when(members.mayWork("atlas", "owner")).thenReturn(true);
    when(members.mayWork("atlas", "producer")).thenReturn(true);
    when(tools.permitsArtifact(intent.binding())).thenReturn(true);
    var authority =
        new InvocationArtifactAuthority(ledger, members, projects, accounts, tools, hosting);
    assertEquals(stored, authority.require("producer", intent.id()));
    assertThrows(CallerFault.class, () -> authority.require("foreign", intent.id()));
    when(tools.permitsArtifact(intent.binding())).thenReturn(false);
    assertThrows(CallerFault.class, () -> authority.require("producer", intent.id()));
    when(tools.permitsArtifact(intent.binding())).thenReturn(true);
    when(members.mayWork("atlas", "owner")).thenReturn(false);
    assertThrows(CallerFault.class, () -> authority.require("producer", intent.id()));
    when(members.mayWork("atlas", "owner")).thenReturn(true);
    when(projects.personalOwner("atlas")).thenReturn(Optional.of("owner"));
    assertThrows(CallerFault.class, () -> authority.require("producer", intent.id()));
    verifyNoInteractions(hosting);
  }
}
