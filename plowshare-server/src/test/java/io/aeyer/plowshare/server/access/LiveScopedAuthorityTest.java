package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Coverage;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The live-list overload must decide exactly as the id-only overload does for the same inputs; it
 * only changes who reads {@code grants.live}.
 */
class LiveScopedAuthorityTest {
  private final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  private final ApplicationPolicy applications = mock(ApplicationPolicy.class);
  private final GrantLineage lineage = mock(GrantLineage.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final LiveScopedAuthority authority =
      new LiveScopedAuthority(grants, applications, lineage, identities);

  private final GrantRecipient human = GrantRecipient.account(new AccountId(UUID.randomUUID()));
  private final ScopeId scope = new ScopeId(UUID.randomUUID());
  private final AuthorityScope.Project project = new AuthorityScope.Project(scope, 1);

  @BeforeEach
  void setup() {
    when(lineage.valid(any())).thenReturn(true);
    when(identities.scope(scope)).thenReturn(project);
  }

  private Binding binding(GrantRecipient recipient, ScopeId target, Set<AuthorityAction> actions) {
    return binding(recipient, target, actions, StandardRole.ADMIN, Optional.empty());
  }

  private Binding binding(
      GrantRecipient recipient,
      ScopeId target,
      Set<AuthorityAction> actions,
      StandardRole role,
      Optional<String> project) {
    return new Binding(
        UUID.randomUUID(),
        recipient,
        target,
        role,
        role,
        Coverage.SELF,
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
        project);
  }

  /** Asks both overloads, the list overload with the very list the id overload will read. */
  private boolean both(
      GrantRecipient recipient,
      AuthorityScope target,
      AuthorityAction action,
      UUID grant,
      List<Binding> live,
      boolean expected) {
    when(grants.live(recipient)).thenReturn(live);
    boolean byId = authority.allowsUsing(recipient, target, action, grant);
    boolean byList = authority.allowsUsing(recipient, target, action, grant, live);
    assertEquals(byId, byList);
    assertEquals(expected, byList);
    return byList;
  }

  @Test
  void bindingOverloadMatchesIdOverload() {
    var held = binding(human, scope, Set.of(AuthorityAction.READ));
    // Allowed.
    assertTrue(both(human, project, AuthorityAction.READ, held.id(), List.of(held), true));
    // Revoked: the grant is no longer in the live list.
    assertFalse(both(human, project, AuthorityAction.READ, held.id(), List.of(), false));
    // A different grant in the list cannot repair the named one.
    var other = binding(human, scope, Set.of(AuthorityAction.READ));
    assertFalse(both(human, project, AuthorityAction.READ, held.id(), List.of(other), false));
    // Wrong recipient: the binding belongs to someone else.
    var stranger = GrantRecipient.account(new AccountId(UUID.randomUUID()));
    assertFalse(both(stranger, project, AuthorityAction.READ, held.id(), List.of(held), false));
    // Wrong action.
    assertFalse(both(human, project, AuthorityAction.WRITE, held.id(), List.of(held), false));
    // Wrong scope: the target resolves, but the grant covers another scope.
    var elsewhere = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
    when(identities.scope(elsewhere.id())).thenReturn(elsewhere);
    assertFalse(both(human, elsewhere, AuthorityAction.READ, held.id(), List.of(held), false));
    // Unresolvable target.
    var unknown = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
    assertFalse(both(human, unknown, AuthorityAction.READ, held.id(), List.of(held), false));
    // Invalid lineage refuses an otherwise matching grant.
    var broken = binding(human, scope, Set.of(AuthorityAction.READ));
    when(lineage.valid(broken.id())).thenReturn(false);
    assertFalse(both(human, project, AuthorityAction.READ, broken.id(), List.of(broken), false));
  }

  /** A project grant is further limited by the Application manifest, in both overloads alike. */
  @Test
  void bindingOverloadMatchesIdOverloadWhenTheManifestNarrowsTheRole() {
    when(applications.read("fixture"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("human", ProjectRole.VIEWER)));
    for (var role : List.of(StandardRole.CONTRIBUTOR, StandardRole.MANAGER)) {
      var held =
          binding(
              human,
              scope,
              Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.MANAGE_ACCESS),
              role,
              Optional.of("fixture"));
      // The manifest caps the stored role at viewer: reading survives, writing and managing do not.
      assertTrue(both(human, project, AuthorityAction.READ, held.id(), List.of(held), true));
      assertFalse(both(human, project, AuthorityAction.WRITE, held.id(), List.of(held), false));
      assertFalse(
          both(human, project, AuthorityAction.MANAGE_ACCESS, held.id(), List.of(held), false));
    }
  }

  @Test
  void walkReadsLineageOncePerDistinctGrant() {
    var first = binding(human, scope, Set.of(AuthorityAction.READ, AuthorityAction.WRITE));
    var second = binding(human, scope, Set.of(AuthorityAction.READ));
    var walk = authority.walk(human, List.of(first, second));
    for (var action : List.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.READ))
      assertTrue(walk.allows(project, action, first.id()));
    verify(lineage, times(1)).valid(first.id());
    assertTrue(walk.allows(project, AuthorityAction.READ, second.id()));
    verify(lineage, times(1)).valid(second.id());
    verify(lineage, times(2)).valid(any());
    // A new walk memoises nothing from the last one.
    authority.walk(human, List.of(first)).allows(project, AuthorityAction.READ, first.id());
    verify(lineage, times(2)).valid(first.id());
  }

  @Test
  void walkReadsEachApplicationManifestOncePerProject() {
    when(applications.read("fixture"))
        .thenReturn(
            new ApplicationPolicy.Boundary(
                ApplicationPolicy.Kind.APPLICATION, Map.of("human", ProjectRole.MANAGER)));
    var first =
        binding(
            human,
            scope,
            Set.of(AuthorityAction.READ),
            StandardRole.CONTRIBUTOR,
            Optional.of("fixture"));
    var second =
        binding(
            human,
            scope,
            Set.of(AuthorityAction.READ),
            StandardRole.MANAGER,
            Optional.of("fixture"));
    var walk = authority.walk(human, List.of(first, second));
    assertTrue(walk.allows(project, AuthorityAction.READ, first.id()));
    assertTrue(walk.allows(project, AuthorityAction.READ, first.id()));
    assertTrue(walk.allows(project, AuthorityAction.READ, second.id()));
    verify(applications, times(1)).read("fixture");
  }

  @Test
  void aFailedLineageReadIsNotMemoised() {
    var held = binding(human, scope, Set.of(AuthorityAction.READ));
    when(lineage.valid(held.id())).thenThrow(new IllegalStateException("boom")).thenReturn(true);
    var walk = authority.walk(human, List.of(held));
    assertThrows(
        IllegalStateException.class, () -> walk.allows(project, AuthorityAction.READ, held.id()));
    assertTrue(walk.allows(project, AuthorityAction.READ, held.id()));
  }

  @Test
  void listOverloadNeverReadsLiveGrantsItself() {
    var held = binding(human, scope, Set.of(AuthorityAction.READ));
    assertTrue(
        authority.allowsUsing(human, project, AuthorityAction.READ, held.id(), List.of(held)));
    verify(grants, times(0)).live(any());
    when(grants.live(human)).thenReturn(List.of(held));
    assertTrue(authority.allowsUsing(human, project, AuthorityAction.READ, held.id()));
    verify(grants, times(1)).live(human);
  }
}
