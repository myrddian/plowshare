package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization pins for {@link LiveAccountKnowledgePermissions}, the ADMIN gate on an account's
 * knowledge scope — in practice Global, whose account id is the fixed {@link
 * BuiltInIdentities#GLOBAL}.
 *
 * <p><b>These assert what the gate does today, not what it should do.</b> They exist because the
 * gate is scheduled to be touched and nothing currently describes its behaviour. Changing it should
 * start by reading these, and any pin that has to change is a behaviour change that needs saying
 * out loud.
 *
 * <p><b>This is only the Java half.</b> The implementation's own comment records that "resource
 * audiences remain an additional SQL gate", and the knowledge predicates have been revised in place
 * across several migrations (V157 was superseded by V163, then V172 and V178). Passing every pin
 * here therefore does <b>not</b> establish that a write is permitted end to end, and a
 * current-state view of those SQL functions is the other half of the picture.
 *
 * <p><b>The gate is very likely unexercised in production.</b> As of 22 October 2026 every route
 * into Global knowledge is closed: an ACCOUNT-scoped information write resolves to {@code
 * Home.global()} and is refused at admission ({@code InformationAccess.executionHome} maps {@code
 * ACCOUNT} to the global memory tier, and {@code admit} admits only {@code Project} and {@code
 * Personal} scopes), and the Curator that promotes into Global knowledge throws on its first
 * candidate because its judge declares a tool. The live server's {@code global_read} listing is
 * empty. So these pins describe intent-as-implemented; they are not a record of observed production
 * behaviour, and they cannot be cross-checked against it until a route opens.
 *
 * <p><b>A wrong diagnosis to not repeat.</b> This class was once read as contradicting the SQL
 * policy and blocking personal writes. That was wrong three times over: {@code Scope.ACCOUNT} is
 * the Global knowledge destination and not an account's own space ({@code
 * InformationContext.Selection.account(UUID)} throws unless the id is {@link
 * BuiltInIdentities#GLOBAL}); the migration being read had been superseded; and the ADMIN/SELF
 * grant this gate looks for <b>is</b> issued, by {@code V156:276-283} for {@code
 * s.builtin='GLOBAL_KNOWLEDGE'}, with ADMIN holding {@code WRITE} in that profile ({@code
 * V156:57}). Acting on that reading would have deleted a working half of a two-layer gate.
 */
class LiveAccountKnowledgePermissionsPinTest {
  IdentityRepository identities = mock(IdentityRepository.class);
  ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  ScopedAuthority authority = mock(ScopedAuthority.class);
  ScopedGrantRepository.Binding binding = mock(ScopedGrantRepository.Binding.class);

  UUID grant = UUID.randomUUID();
  AccountId actor = new AccountId(UUID.randomUUID());
  AuthorityScope.AccountKnowledge global =
      new AuthorityScope.AccountKnowledge(new ScopeId(UUID.randomUUID()), BuiltInIdentities.GLOBAL);
  GrantRecipient subject = GrantRecipient.account(actor);

  LiveAccountKnowledgePermissions gate =
      new LiveAccountKnowledgePermissions(identities, grants, authority);

  /**
   * An actor of the given kind, resolved by the legacy handle the gate is called with. {@link
   * IdentityRepository.Account} enforces that a built-in account is locked and no other kind is, so
   * the flag follows the kind rather than being a free parameter.
   */
  private void actorIs(IdentityRepository.AccountKind kind) {
    when(identities.account("human"))
        .thenReturn(
            new IdentityRepository.Account(
                actor, "human", kind, kind == IdentityRepository.AccountKind.BUILTIN));
  }

  @BeforeEach
  void setup() {
    actorIs(IdentityRepository.AccountKind.USER);
    when(identities.knowledge(BuiltInIdentities.GLOBAL)).thenReturn(global);
    when(grants.live(subject)).thenReturn(List.of(binding));
    // A grant that satisfies every one of the write path's four conditions. Each pin below
    // removes exactly one, so the conjunction is pinned condition by condition rather than
    // as a single all-or-nothing case.
    when(binding.id()).thenReturn(grant);
    when(binding.scope()).thenReturn(global.id());
    when(binding.role()).thenReturn(StandardRole.ADMIN);
    when(binding.coverage()).thenReturn(ScopedGrantRepository.Coverage.SELF);
    when(authority.allowsUsing(subject, global, AuthorityAction.WRITE, grant)).thenReturn(true);
  }

  private boolean allows(AuthorityAction action) {
    return gate.allows("human", BuiltInIdentities.GLOBAL, action);
  }

  // ---------------------------------------------------------------- the READ path

  /**
   * READ asks {@link ScopedAuthority#allows} and nothing else. The grant repository is not even
   * consulted, which is the gate's "public readers need no content-write grant" rule made testable:
   * it is a property of which collaborators are touched, not only of the answer.
   */
  @Test
  void read_is_decided_by_scoped_authority_alone_and_never_reads_grants() {
    when(authority.allows(subject, global, AuthorityAction.READ)).thenReturn(true);
    assertTrue(allows(AuthorityAction.READ));
    verify(grants, never()).live(any());
    verify(authority, never()).allowsUsing(any(), any(), any(), any());
  }

  @Test
  void read_is_refused_when_scoped_authority_refuses_it() {
    when(authority.allows(subject, global, AuthorityAction.READ)).thenReturn(false);
    assertFalse(allows(AuthorityAction.READ));
    verify(grants, never()).live(any());
  }

  /**
   * The actor-kind check sits <b>after</b> the READ return, so a service or built-in principal
   * reads Global knowledge on the same terms a person does. Pinned because it is the kind of
   * asymmetry that looks like an oversight and may well be deliberate: Global is a public
   * destination, and the class comment calls it "a destination, never an authenticated actor" — a
   * statement about writing, which this shows does not extend to reading.
   */
  @Test
  void a_service_principal_may_still_read_because_the_kind_check_follows_the_read_return() {
    when(authority.allows(subject, global, AuthorityAction.READ)).thenReturn(true);
    for (var kind :
        List.of(
            IdentityRepository.AccountKind.SERVICE,
            IdentityRepository.AccountKind.SERVICE_TOKEN,
            IdentityRepository.AccountKind.BUILTIN)) {
      actorIs(kind);
      assertTrue(allows(AuthorityAction.READ), "READ should not depend on the actor kind: " + kind);
    }
  }

  // --------------------------------------------------------------- the write path

  @Test
  void a_user_with_an_admin_self_grant_on_this_scope_may_write() {
    assertTrue(allows(AuthorityAction.WRITE));
  }

  /** The gate resolves the <b>destination</b> account's knowledge scope, not the actor's. */
  @Test
  void the_scope_checked_is_the_destination_accounts_knowledge_scope() {
    assertTrue(allows(AuthorityAction.WRITE));
    verify(identities, atLeastOnce()).knowledge(BuiltInIdentities.GLOBAL);
  }

  @Test
  void a_write_needs_the_admin_role_and_no_lesser_role_substitutes() {
    for (var role : List.of(StandardRole.MANAGER, StandardRole.CONTRIBUTOR, StandardRole.READER)) {
      when(binding.role()).thenReturn(role);
      assertFalse(allows(AuthorityAction.WRITE), "only ADMIN may write; " + role + " must not");
    }
    when(binding.role()).thenReturn(StandardRole.ADMIN);
    assertTrue(allows(AuthorityAction.WRITE));
  }

  @Test
  void a_write_needs_the_grant_to_name_this_very_scope() {
    when(binding.scope()).thenReturn(new ScopeId(UUID.randomUUID()));
    assertFalse(allows(AuthorityAction.WRITE));
  }

  /** A grant that reaches this scope by breadth rather than by naming it does not count. */
  @Test
  void a_write_needs_self_coverage_and_a_wider_coverage_does_not_count() {
    for (var coverage :
        List.of(
            ScopedGrantRepository.Coverage.ORDINARY_PROJECT_CONTROL,
            ScopedGrantRepository.Coverage.ALL_SCOPES)) {
      when(binding.coverage()).thenReturn(coverage);
      assertFalse(allows(AuthorityAction.WRITE), coverage + " must not satisfy the write gate");
    }
    when(binding.coverage()).thenReturn(ScopedGrantRepository.Coverage.SELF);
    assertTrue(allows(AuthorityAction.WRITE));
  }

  /**
   * Holding a matching ADMIN/SELF grant is not enough: the grant must also still authorize this
   * action through {@link ScopedAuthority#allowsUsing}. A grant row is not live authority.
   */
  @Test
  void a_write_needs_the_grant_to_still_authorize_the_action() {
    when(authority.allowsUsing(subject, global, AuthorityAction.WRITE, grant)).thenReturn(false);
    assertFalse(allows(AuthorityAction.WRITE));
  }

  /** Global is a destination, never an authenticated actor: no non-person writes it. */
  @Test
  void no_non_user_principal_may_write_however_complete_its_grant() {
    for (var kind :
        List.of(
            IdentityRepository.AccountKind.SERVICE,
            IdentityRepository.AccountKind.SERVICE_TOKEN,
            IdentityRepository.AccountKind.BUILTIN)) {
      actorIs(kind);
      assertFalse(allows(AuthorityAction.WRITE), kind + " must not write account knowledge");
    }
  }

  /** With no live grant at all there is nothing to satisfy the conjunction. */
  @Test
  void a_write_is_refused_when_the_recipient_holds_no_live_grant() {
    when(grants.live(subject)).thenReturn(List.of());
    assertFalse(allows(AuthorityAction.WRITE));
  }

  /**
   * Every non-READ action takes the write path, so the ADMIN/SELF conjunction governs them all
   * rather than WRITE alone. Pinned so that adding an action cannot quietly fall through to the
   * reader rule.
   */
  @Test
  void actions_other_than_read_take_the_admin_path_and_not_the_reader_rule() {
    when(authority.allows(subject, global, AuthorityAction.READ)).thenReturn(true);
    for (var action : AuthorityAction.values()) {
      if (action == AuthorityAction.READ) continue;
      when(binding.role()).thenReturn(StandardRole.READER);
      assertFalse(
          gate.allows("human", BuiltInIdentities.GLOBAL, action),
          action + " must not be granted by the reader rule");
    }
  }
}
