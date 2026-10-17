package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.IdentityRepository.AccountKind;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.*;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScopedRolePolicyTest {
  private final ScopedGrantRepository repository = mock(ScopedGrantRepository.class);
  private final ApplicationPolicy applications = mock(ApplicationPolicy.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final GrantLineage lineage = mock(GrantLineage.class);
  private final LiveScopedAuthority authority =
      new LiveScopedAuthority(repository, applications, lineage, identities);

  @org.junit.jupiter.api.BeforeEach
  void liveLineage() {
    when(lineage.valid(any())).thenReturn(true);
    when(identities.scope(scope)).thenReturn(project);
  }

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final GrantRecipient human = GrantRecipient.account(account);
  private final ScopeId scope = new ScopeId(UUID.randomUUID());
  private final AuthorityScope.Project project = new AuthorityScope.Project(scope, 42);

  @Test
  void agent_manager_is_narrowed_unless_human_explicitly_forces_and_children_get_no_exception() {
    assertEquals(
        StandardRole.CONTRIBUTOR,
        RoleGrantPolicy.effective(
            StandardRole.MANAGER, true, false, AccountKind.USER, false, StandardRole.MANAGER));
    assertEquals(
        StandardRole.MANAGER,
        RoleGrantPolicy.effective(
            StandardRole.MANAGER, true, true, AccountKind.USER, false, StandardRole.MANAGER));
    assertEquals(
        StandardRole.CONTRIBUTOR,
        RoleGrantPolicy.effective(
            StandardRole.MANAGER, true, false, AccountKind.USER, true, StandardRole.MANAGER));
    for (var role : List.of(StandardRole.READER, StandardRole.CONTRIBUTOR))
      assertEquals(
          role,
          RoleGrantPolicy.effective(
              role, true, false, AccountKind.SERVICE, false, StandardRole.MANAGER));
    // A direct Application service is not automatically an agent.
    assertEquals(
        StandardRole.MANAGER,
        RoleGrantPolicy.effective(
            StandardRole.MANAGER, false, false, AccountKind.USER, false, StandardRole.ADMIN));
  }

  @Test
  void agent_admin_root_and_universal_audience_assignments_are_rejected_even_with_force() {
    for (var role : List.of(StandardRole.ADMIN, StandardRole.SYSTEM, StandardRole.GLOBAL_READ))
      for (boolean forced : List.of(false, true))
        assertThrows(
            CallerFault.class,
            () ->
                RoleGrantPolicy.effective(
                    role, true, forced, AccountKind.USER, false, StandardRole.SYSTEM));
    assertThrows(
        CallerFault.class,
        () ->
            RoleGrantPolicy.effective(
                StandardRole.MANAGER,
                true,
                true,
                AccountKind.SERVICE,
                false,
                StandardRole.MANAGER));
    assertThrows(
        CallerFault.class,
        () ->
            RoleGrantPolicy.effective(
                StandardRole.MANAGER, true, true, AccountKind.USER, true, StandardRole.MANAGER));
    assertThrows(
        CallerFault.class,
        () ->
            RoleGrantPolicy.effective(
                StandardRole.ADMIN, false, false, AccountKind.USER, false, StandardRole.MANAGER));
    assertThrows(
        CallerFault.class,
        () ->
            RoleGrantPolicy.effective(
                StandardRole.READER,
                false,
                false,
                AccountKind.USER,
                false,
                StandardRole.CONTRIBUTOR));
  }

  @ParameterizedTest
  @EnumSource(AuthorityAction.class)
  void admin_control_never_supplies_project_data_or_execution(AuthorityAction action) {
    var binding =
        binding(
            human,
            StandardRole.ADMIN,
            Coverage.ORDINARY_PROJECT_CONTROL,
            BuiltInIdentities.ADMINISTRATION,
            Set.of(
                AuthorityAction.ADMINISTER,
                AuthorityAction.MANAGE_ACCESS,
                AuthorityAction.MANAGE_SCOPE,
                AuthorityAction.SET_AUDIENCE));
    when(repository.live(human)).thenReturn(List.of(binding));
    boolean control =
        Set.of(
                AuthorityAction.ADMINISTER,
                AuthorityAction.MANAGE_ACCESS,
                AuthorityAction.MANAGE_SCOPE,
                AuthorityAction.SET_AUDIENCE)
            .contains(action);
    assertEquals(control, authority.allows(human, project, action));
    var administrative =
        new AuthorityScope.Administration(
            BuiltInIdentities.ADMINISTRATION, BuiltInIdentities.SYSTEM);
    when(identities.scope(administrative.id())).thenReturn(administrative);
    assertEquals(control, authority.allows(human, administrative, action));
    assertFalse(
        authority.allows(
            human,
            new AuthorityScope.Personal(new ScopeId(UUID.randomUUID()), account, 43),
            action));
    assertFalse(
        authority.allows(
            human,
            new AuthorityScope.AccountKnowledge(new ScopeId(UUID.randomUUID()), account),
            action));
  }

  @Test
  void exact_scope_recipient_and_live_source_are_mandatory_and_errors_propagate() {
    var binding =
        binding(
            human,
            StandardRole.CONTRIBUTOR,
            Coverage.SELF,
            scope,
            Set.of(AuthorityAction.READ, AuthorityAction.WRITE));
    when(repository.live(human)).thenReturn(List.of(binding));
    external();
    assertTrue(authority.allows(human, project, AuthorityAction.WRITE));
    assertFalse(
        authority.allows(
            human,
            new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 43),
            AuthorityAction.READ));
    assertFalse(
        authority.allows(GrantRecipient.agent(account, "child"), project, AuthorityAction.WRITE));
    when(repository.live(human)).thenReturn(List.of());
    assertFalse(authority.allows(human, project, AuthorityAction.READ));
    when(repository.live(human)).thenThrow(new IllegalStateException("store unavailable"));
    assertThrows(
        IllegalStateException.class, () -> authority.allows(human, project, AuthorityAction.READ));
  }

  @Test
  void application_limits_are_live_and_specific_issuing_grants_cannot_borrow_other_authority() {
    var manager =
        binding(
            human,
            StandardRole.MANAGER,
            Coverage.SELF,
            scope,
            Set.of(AuthorityAction.READ, AuthorityAction.MANAGE_ACCESS));
    var reader =
        binding(human, StandardRole.READER, Coverage.SELF, scope, Set.of(AuthorityAction.READ));
    when(repository.live(human)).thenReturn(List.of(manager, reader));
    when(applications.read("fixture"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("human", ProjectRole.VIEWER)));
    assertTrue(authority.allows(human, project, AuthorityAction.READ));
    assertFalse(authority.allows(human, project, AuthorityAction.MANAGE_ACCESS));
    external();
    assertTrue(authority.allowsUsing(human, project, AuthorityAction.MANAGE_ACCESS, manager.id()));
    assertFalse(authority.allowsUsing(human, project, AuthorityAction.MANAGE_ACCESS, reader.id()));
    when(applications.read("fixture"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.INVALID, Map.of()));
    assertFalse(authority.allows(human, project, AuthorityAction.READ));
  }

  @Test
  void denied_issuance_has_no_partial_effect_and_does_not_offer_an_arbitrary_action_grant() {
    var identities = mock(IdentityRepository.class);
    var checks = mock(ScopedAuthority.class);
    var roles = new AuthorizedRoleGrants(repository, checks, identities);
    when(identities.scope(scope)).thenReturn(project);
    var issue =
        new Issue(
            UUID.randomUUID(),
            GrantRecipient.agent(account, "worker"),
            scope,
            StandardRole.ADMIN,
            human,
            UUID.randomUUID(),
            Optional.empty(),
            false);
    assertThrows(CallerFault.class, () -> roles.issue(issue));
    verify(repository, never()).issue(any());
  }

  @Test
  void reserved_system_identity_without_a_live_policy_binding_supplies_no_authority() {
    var system = GrantRecipient.account(BuiltInIdentities.SYSTEM);
    when(repository.live(system)).thenReturn(List.of());
    assertFalse(authority.allows(system, project, AuthorityAction.READ));
    verify(repository).live(system);
  }

  private void external() {
    when(applications.read("fixture"))
        .thenReturn(new ApplicationPolicy.Boundary(ApplicationPolicy.Kind.EXTERNAL, Map.of()));
  }

  private Binding binding(
      GrantRecipient recipient,
      StandardRole role,
      Coverage coverage,
      ScopeId target,
      Set<AuthorityAction> actions) {
    return new Binding(
        UUID.randomUUID(),
        recipient,
        target,
        role,
        role,
        coverage,
        human,
        Optional.of(UUID.randomUUID()),
        Optional.empty(),
        1,
        1,
        false,
        "ROLE",
        OffsetDateTime.now(),
        Optional.empty(),
        Optional.empty(),
        actions,
        "human",
        Optional.of("fixture"));
  }
}
