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

  @Test
  void children_retain_the_exact_parent_grants_and_cannot_inherit_management_actions() {
    for (var action :
        Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL))
      when(authority.allowsUsing(recipient, target, action, grant)).thenReturn(true);
    var parent =
        policy.admit(
            "alice",
            Home.of("one"),
            Set.of(
                AuthorityAction.READ,
                AuthorityAction.WRITE,
                AuthorityAction.RUN_MODEL,
                AuthorityAction.CONVERSATION_CREATE));
    doAnswer(
            call -> {
              OperationAuthority.Admission child = call.getArgument(0);
              when(records.parent(child.id())).thenReturn(Optional.of(parent.id()));
              return null;
            })
        .when(records)
        .retainChild(any(), eq(parent.id()));
    when(records.find(parent.id())).thenReturn(Optional.of(parent));
    var child =
        policy.derive(
            parent, Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
    assertNotEquals(parent.id(), child.id());
    assertEquals(parent.executor(), child.executor());
    assertEquals(parent.scope(), child.scope());
    assertEquals(parent.source(), child.source());
    assertTrue(parent.support().containsAll(child.support()));
    verify(records).retainChild(child, parent.id());
    for (var action :
        Set.of(
            AuthorityAction.ADMINISTER,
            AuthorityAction.MANAGE_ACCESS,
            AuthorityAction.MANAGE_SCOPE,
            AuthorityAction.SET_AUDIENCE))
      assertThrows(CallerFault.class, () -> policy.derive(parent, Set.of(action)));
    // An ancestor action not used by the leaf still constrains the whole tree.
    when(authority.allowsUsing(recipient, target, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(false);
    assertThrows(CallerFault.class, () -> policy.require(child));
    verify(grants, times(1)).live(recipient);
  }

  @Test
  void missing_and_cyclic_parent_evidence_never_falls_back_to_ambient_authority() {
    var admitted =
        policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    UUID unknown = UUID.randomUUID();
    when(records.parent(admitted.id())).thenReturn(Optional.of(unknown));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    when(records.parent(admitted.id())).thenReturn(Optional.of(admitted.id()));
    when(records.find(admitted.id())).thenReturn(Optional.of(admitted));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
  }

  @Test
  void builtin_system_identity_cannot_be_minted_as_an_ordinary_child() {
    var parent = policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.BUILTIN, true));
    assertThrows(CallerFault.class, () -> policy.derive(parent, parent.actions()));
    verify(records, never()).retainChild(any(), any());
  }

  @Test
  void missing_or_relocated_retained_input_refuses_even_when_grants_remain_live() {
    var admitted =
        policy.admit("alice", Home.of("one"), Set.of(AuthorityAction.CONVERSATION_CREATE));
    var target =
        new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "retained-input");
    when(records.targets(admitted.id())).thenReturn(List.of(target));
    when(resources.projects(target)).thenReturn(List.of("one"));
    assertDoesNotThrow(() -> policy.require(admitted));
    when(resources.projects(target)).thenReturn(List.of());
    assertThrows(CallerFault.class, () -> policy.require(admitted));
    when(resources.projects(target)).thenReturn(List.of("other"));
    assertThrows(CallerFault.class, () -> policy.require(admitted));
  }

  @Test
  void capturedGlobalReadIsReadOnlyInheritedAndLiveWithoutWideningOldAdmissions() {
    var knowledge = mock(io.aeyer.plowshare.server.information.AccountKnowledgePermissions.class);
    when(knowledge.allows("alice", BuiltInIdentities.GLOBAL, AuthorityAction.READ))
        .thenReturn(true);
    var live =
        new PinnedOperationAuthority(
            identities, grants, authority, members, records, resources, knowledge);
    when(authority.allowsUsing(recipient, target, AuthorityAction.READ, grant)).thenReturn(true);
    when(authority.allowsUsing(recipient, target, AuthorityAction.WRITE, grant)).thenReturn(true);
    var root =
        live.admit("alice", Home.of("one"), Set.of(AuthorityAction.READ, AuthorityAction.WRITE));
    assertTrue(root.globalRead());
    var memory = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "global-memory");
    when(resources.projects(memory)).thenReturn(java.util.Collections.singletonList(null));
    assertDoesNotThrow(() -> live.requireReadTargets(root, Home.of("one"), List.of(memory)));
    assertThrows(
        CallerFault.class, () -> live.requireTargets(root, Home.of("one"), List.of(memory)));
    assertThrows(CallerFault.class, () -> live.requireTargets(root, Home.global(), List.of()));
    assertThrows(
        CallerFault.class, () -> live.requireReadTargets(root, Home.of("other"), List.of()));
    var conversation =
        new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "old-null");
    when(resources.projects(conversation)).thenReturn(java.util.Collections.singletonList(null));
    assertThrows(
        CallerFault.class,
        () -> live.requireReadTargets(root, Home.of("one"), List.of(conversation)));
    var old =
        new OperationAuthority.Admission(
            root.id(),
            root.executor(),
            root.account(),
            root.scope(),
            root.home(),
            root.source(),
            root.support());
    assertFalse(old.globalRead());
    assertThrows(
        CallerFault.class, () -> live.requireReadTargets(old, Home.of("one"), List.of(memory)));
    when(knowledge.allows("alice", BuiltInIdentities.GLOBAL, AuthorityAction.READ))
        .thenReturn(false);
    assertThrows(
        CallerFault.class, () -> live.requireReadTargets(root, Home.of("one"), List.of(memory)));
  }
}
