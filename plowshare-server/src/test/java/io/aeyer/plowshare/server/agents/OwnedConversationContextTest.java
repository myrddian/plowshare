package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ConversationContextRepository;
import io.aeyer.plowshare.server.archive.ConversationContextRepository.Ownership;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.auth.AccountAdministrationRepository;
import io.aeyer.plowshare.server.auth.ServerAdministration.Account;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OwnedConversationContextTest {
  private static final String SERVICE = "@service/fixture-token";
  final ConversationContextRepository conversations = mock(ConversationContextRepository.class);
  final AccountAdministrationRepository accounts = mock(AccountAdministrationRepository.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ConversationContextAccess access =
      new OwnedConversationContext(conversations, accounts, members);

  @Test
  void active_service_token_reads_its_own_project_context_with_viewer_permission() {
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.of("application-account"));
    when(conversations.ownership("conversation"))
        .thenReturn(Optional.of(new Ownership(SERVICE, "7", "application", false)));
    when(members.role("application", SERVICE)).thenReturn(Optional.of(ProjectRole.VIEWER));
    var owner = access.owner(SERVICE, "conversation");
    assertEquals(SERVICE, owner.accountHandle());
    assertEquals("7", owner.projectId());
    verify(members).role("application", SERVICE);
    verifyNoInteractions(accounts);
  }

  @Test
  void revoked_expired_or_disabled_service_identity_cannot_read_content() {
    // The durable membership boundary resolves only currently active tokens/accounts.
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> access.owner(SERVICE, "conversation"));
    verifyNoInteractions(conversations, accounts);
  }

  @Test
  void service_cannot_read_a_sibling_token_or_owning_accounts_conversation() {
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.of("application-account"));
    for (String owner : new String[] {"@service/sibling-token", "application-account", "alice"}) {
      when(conversations.ownership("conversation"))
          .thenReturn(Optional.of(new Ownership(owner, "7", "application", false)));
      assertThrows(CallerFault.class, () -> access.owner(SERVICE, "conversation"));
    }
    verify(members, never()).role(anyString(), anyString());
  }

  @Test
  void removing_project_permission_or_application_grant_refuses_the_next_read() {
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.of("application-account"));
    when(conversations.ownership("conversation"))
        .thenReturn(Optional.of(new Ownership(SERVICE, "7", "application", false)));
    when(members.role("application", SERVICE))
        .thenReturn(Optional.of(ProjectRole.CONTRIBUTOR))
        .thenReturn(Optional.empty());
    assertNotNull(access.owner(SERVICE, "conversation"));
    assertThrows(CallerFault.class, () -> access.owner(SERVICE, "conversation"));
  }

  @Test
  void token_project_ceiling_cannot_be_replaced_by_its_owner_membership() {
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.of("application-account"));
    when(conversations.ownership("conversation"))
        .thenReturn(Optional.of(new Ownership(SERVICE, "7", "other-project", false)));
    when(members.role("other-project", SERVICE)).thenReturn(Optional.empty());
    when(members.role("other-project", "application-account"))
        .thenReturn(Optional.of(ProjectRole.MANAGER));
    assertThrows(CallerFault.class, () -> access.owner(SERVICE, "conversation"));
    verify(members, never()).role("other-project", "application-account");
  }

  @Test
  void service_context_does_not_admit_global_or_personal_homes() {
    when(members.authorityAccount(SERVICE)).thenReturn(Optional.of("application-account"));
    for (var home :
        new Ownership[] {
          new Ownership(SERVICE, null, null, false),
          new Ownership(SERVICE, "7", "personal-project", true)
        }) {
      when(conversations.ownership("conversation")).thenReturn(Optional.of(home));
      assertThrows(CallerFault.class, () -> access.owner(SERVICE, "conversation"));
    }
    verify(members, never()).role(anyString(), anyString());
  }

  @Test
  void human_owner_keeps_global_and_personal_context_access() {
    enabledHuman();
    when(conversations.ownership("global"))
        .thenReturn(Optional.of(new Ownership("alice", null, null, false)));
    assertNull(access.owner("alice", "global").projectId());
    when(conversations.ownership("personal"))
        .thenReturn(Optional.of(new Ownership("alice", "7", "personal-project", true)));
    when(members.role("personal-project", "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    assertEquals("7", access.owner("alice", "personal").projectId());
  }

  @Test
  void human_admin_authority_does_not_override_exact_ownership_or_project_grants() {
    enabledHuman();
    when(conversations.ownership("conversation"))
        .thenReturn(Optional.of(new Ownership("bob", "7", "project", false)));
    assertThrows(CallerFault.class, () -> access.owner("alice", "conversation"));
    when(conversations.ownership("conversation"))
        .thenReturn(Optional.of(new Ownership("alice", "7", "project", false)));
    when(members.role("project", "alice")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> access.owner("alice", "conversation"));
  }

  @Test
  void disabled_or_missing_human_and_missing_conversation_are_refused() {
    when(accounts.account("alice"))
        .thenReturn(new Account("alice", false, false, false, OffsetDateTime.now()));
    assertThrows(CallerFault.class, () -> access.owner("alice", "conversation"));
    verifyNoInteractions(conversations);
    enabledHuman();
    when(conversations.ownership("missing")).thenReturn(Optional.empty());
    assertThrows(CallerFault.class, () -> access.owner("alice", "missing"));
    when(accounts.account("missing")).thenThrow(new CallerFault("unavailable"));
    assertThrows(CallerFault.class, () -> access.owner("missing", "conversation"));
    assertThrows(CallerFault.class, () -> access.owner(null, "conversation"));
  }

  private void enabledHuman() {
    when(accounts.account("alice"))
        .thenReturn(new Account("alice", true, true, false, OffsetDateTime.now()));
  }
}
