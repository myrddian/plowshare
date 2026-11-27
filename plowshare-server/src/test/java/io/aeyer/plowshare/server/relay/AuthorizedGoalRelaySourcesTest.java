package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.AuthorityWithdrawnException;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationAuthority.Admission;
import io.aeyer.plowshare.server.access.OperationAuthority.Support;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Search;
import io.aeyer.plowshare.server.relay.GoalRelaySources.Selection;
import io.aeyer.plowshare.server.relay.RelayPayload.Kind;
import io.aeyer.plowshare.server.relay.RelayTopicOwnership.Owner;
import io.aeyer.plowshare.server.relay.RelayTopicRegistry.Source;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthorizedGoalRelaySourcesTest {
  private static final long PROJECT = 7;
  private static final Home HOME = Home.of("source-project");
  private static final AccountId ACCOUNT = new AccountId(UUID.randomUUID());
  private static final ScopeId SCOPE = new ScopeId(UUID.randomUUID());
  private static final Relay.TopicKey TOPIC = new Relay.TopicKey(PROJECT, "news.release");
  private static final Source SOURCE =
      new Source(
          TOPIC, UUID.randomUUID(), Kind.TEXT, RelayTopicOwnership.internal(Owner.GENERAL_PURPOSE));
  private static final Search SEARCH = new Search("news.", Kind.TEXT, null, 10);
  private static final Selection CHOICE = new Selection(TOPIC.name(), SOURCE.generation());
  private static final Admission GOAL =
      new Admission(
          UUID.randomUUID(),
          "launcher",
          ACCOUNT,
          SCOPE,
          HOME,
          GrantRecipient.account(ACCOUNT),
          List.of(new Support(AuthorityAction.READ, UUID.randomUUID())));

  private final OperationAuthority authority = mock(OperationAuthority.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayTopicRegistry registry = mock(RelayTopicRegistry.class);
  private final GoalRelaySources sources =
      new AuthorizedGoalRelaySources(
          authority, identities, RelayProjectReadAccess.existing(members, projects), registry);

  @Test
  void discovers_in_the_retained_scope_and_revalidates_the_exact_choice_for_binding() {
    allow();
    when(registry.eligible(TOPIC.scope(), "news.", Kind.TEXT, null, 10))
        .thenReturn(List.of(SOURCE));
    when(registry.lockEligible(TOPIC, SOURCE.generation())).thenReturn(Optional.of(SOURCE));
    assertEquals(List.of(SOURCE), sources.discover(GOAL, SEARCH));
    assertEquals(SOURCE, sources.requireForBinding(GOAL, CHOICE));
    assertThrows(
        CallerFault.class,
        () -> sources.requireForBinding(GOAL, new Selection(TOPIC.name(), UUID.randomUUID())));
  }

  @Test
  void refuses_both_paths_before_disclosing_anything_when_relay_access_is_withdrawn() {
    allow();
    when(members.mayUse(HOME.project(), GOAL.executor())).thenReturn(false);
    assertThrows(CallerFault.class, () -> sources.discover(GOAL, SEARCH));
    assertThrows(CallerFault.class, () -> sources.requireForBinding(GOAL, CHOICE));
    verifyNoInteractions(registry);
  }

  @Test
  void refuses_another_accounts_personal_topic_even_with_project_membership() {
    allow();
    when(projects.personalOwner(HOME.project())).thenReturn(Optional.of("someone-else"));
    assertThrows(CallerFault.class, () -> sources.discover(GOAL, SEARCH));
    assertThrows(CallerFault.class, () -> sources.requireForBinding(GOAL, CHOICE));
    verifyNoInteractions(registry);
  }

  @Test
  void refuses_both_paths_when_the_exact_retained_admission_is_withdrawn() {
    allow();
    doThrow(new AuthorityWithdrawnException("revoked"))
        .when(authority)
        .requireReadTargets(GOAL, HOME, List.of());
    assertThrows(AuthorityWithdrawnException.class, () -> sources.discover(GOAL, SEARCH));
    assertThrows(AuthorityWithdrawnException.class, () -> sources.requireForBinding(GOAL, CHOICE));
    verifyNoInteractions(registry);
  }

  @Test
  void checks_relay_access_again_after_waiting_for_the_generation_lock() {
    allow();
    when(members.mayUse(HOME.project(), GOAL.executor())).thenReturn(true, false);
    when(registry.lockEligible(TOPIC, SOURCE.generation())).thenReturn(Optional.of(SOURCE));
    assertThrows(CallerFault.class, () -> sources.requireForBinding(GOAL, CHOICE));
  }

  private void allow() {
    when(members.mayUse(HOME.project(), GOAL.executor())).thenReturn(true);
    when(identities.scope(SCOPE)).thenReturn(new AuthorityScope.Project(SCOPE, PROJECT));
  }
}
