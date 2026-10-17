package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.*;
import org.junit.jupiter.api.*;

/**
 * Later grants, foreign targets and token/Application role increases cannot widen admitted work.
 */
class PinnedOperationAuthorityTest {
  IdentityRepository identities = mock(IdentityRepository.class);
  ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  ScopedAuthority authority = mock(ScopedAuthority.class);
  ProjectMembers members = mock(ProjectMembers.class);
  OperationRecords records = mock(OperationRecords.class);
  ResourceScopeRepository resources = mock(ResourceScopeRepository.class);
  AccountId account = new AccountId(UUID.randomUUID());
  AuthorityScope target = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
  UUID grant = UUID.randomUUID();
  GrantRecipient recipient = GrantRecipient.account(account);
  PinnedOperationAuthority policy =
      new PinnedOperationAuthority(identities, grants, authority, members, records, resources);

  @BeforeEach
  void setup() {
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(target);
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(grant);
    when(grants.live(recipient)).thenReturn(List.of(binding));
    when(authority.allowsUsing(recipient, target, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(true);
  }

  @Test
  void empty_creation_never_implies_model_admission() {
    var admitted =
        policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    assertEquals(Set.of(AuthorityAction.CONVERSATION_CREATE), admitted.actions());
    verify(records).retain(admitted);
    assertThrows(
        CallerFault.class,
        () -> policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.RUN_MODEL)));
    assertThrows(
        CallerFault.class,
        () -> policy.admit("alice", Home.global(), Set.of(AuthorityAction.CONVERSATION_CREATE)));
  }

  @Test
  void replacement_grant_cannot_repair_revocation_and_current_role_can_only_narrow() {
    var admitted =
        policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    when(authority.allowsUsing(recipient, target, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(false);
    var replacement = mock(ScopedGrantRepository.Binding.class);
    when(grants.live(recipient)).thenReturn(List.of(replacement));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    when(authority.allowsUsing(recipient, target, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(true);
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.VIEWER));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    verify(records, never()).started(any(), any());
  }

  @Test
  void resource_relocation_and_other_personal_scopes_refuse() {
    var admitted =
        policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    var resource = new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "cnv");
    when(resources.projects(resource)).thenReturn(List.of("two"));
    assertThrows(
        CallerFault.class,
        () -> policy.requireTargets(admitted, Home.of("one"), List.of(resource)));
    assertThrows(
        CallerFault.class, () -> policy.requireTargets(admitted, Home.of("two"), List.of()));
    when(identities.project("personal:bob"))
        .thenReturn(
            new AuthorityScope.Personal(
                new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 2));
    assertThrows(
        CallerFault.class,
        () ->
            policy.admit(
                "alice", Home.of("personal:bob"), Set.of(AuthorityAction.CONVERSATION_CREATE)));
  }
}
