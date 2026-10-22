package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationRecords;
import io.aeyer.plowshare.server.access.PinnedOperationAuthority;
import io.aeyer.plowshare.server.access.ResourceScopeRepository;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.access.ScopedGrantRepository;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.allowances.AllowanceRepository;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.personal.PersonalSpaces;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which {@link Home} {@link ConversationStore}'s log door can actually be admitted for.
 *
 * <p>{@code logInternal} ends, for a non-SYSTEM {@code SUBMISSION}, with {@code
 * operations.admit(owner, operations.scope(home), {CONVERSATION_CREATE})}. {@code
 * OperationAuthority.scope(Home)} documents that the global memory tier is no execution scope and
 * is refused there, and {@code admit} separately refuses any scope that is not {@code Project} or
 * {@code Personal}. Those two refusals are pinned directly in {@code PinnedOperationAuthorityTest};
 * what was never executed is that <em>this store's log path</em> reaches one of them when the home
 * it is handed is global — which was the link between {@code InformationWriteGates} opening a
 * personal upload's pipeline log against {@code Home.global()} and the live "Operation is outside
 * its retained scoped authority" refusal.
 *
 * <p>That gate now resolves a PERSONAL selection to its own account's personal home instead (see
 * {@code InformationWriteGatesHomeTest}), so the live refusal is gone. The global refusal pinned
 * here is unchanged and still correct: a global home remains a memory tier rather than an execution
 * scope. {@link #a_personal_home_is_admitted_at_the_same_link_that_refuses_the_global_one} closes
 * the chain from the other end, at this same door.
 *
 * <p>The authority here is the real {@link PinnedOperationAuthority} over mocked repositories — the
 * same fixture {@code PinnedOperationAuthorityTest} uses — so the refusal under test is production
 * policy, not a stub. The {@link JdbcTemplate} is a mock because admission runs strictly before the
 * INSERT: a refused log must never reach a row, and {@link
 * #a_global_home_is_refused_at_the_admission_link_of_the_log_path} asserts exactly that.
 */
class ConversationStoreAdmissionHomeTest {

  private static final String REFUSAL = "Operation is outside its retained scoped authority";

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  private final ScopedAuthority authority = mock(ScopedAuthority.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final OperationRecords records = mock(OperationRecords.class);
  private final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);

  private final AccountId account = new AccountId(UUID.randomUUID());
  private final AuthorityScope project =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);

  /**
   * Alice's own personal space: the scope the information path now resolves a PERSONAL write to.
   */
  private static final String PERSONAL = PersonalSpaces.name("alice");

  private final AuthorityScope.Personal personal =
      new AuthorityScope.Personal(new ScopeId(UUID.randomUUID()), account, 2);

  private final GrantRecipient recipient = GrantRecipient.account(account);
  private final UUID grant = UUID.randomUUID();

  private final PinnedOperationAuthority operations =
      new PinnedOperationAuthority(identities, grants, authority, members, records, resources);

  /**
   * The allowance side of {@code write} is mocked to hand each budget straight back. An admitted
   * log goes on to persist, and the point of this class is where admission lands, not how a budget
   * is retained; without this the project control would die on a mocked {@link JdbcTemplate}'s
   * missing allowance row <em>after</em> passing admission.
   */
  private final AllowanceRepository allowances = mock(AllowanceRepository.class);

  private final ConversationStore store = new ConversationStore(jdbc, operations, allowances);

  /**
   * An account that holds a contributor's role on project "one" and holds no Global authority at
   * all: nothing here grants {@code alice} anything outside that project's scope, which is the
   * condition the live personal upload is refused under.
   */
  @BeforeEach
  void anAccountWithOneProjectRoleAndNoGlobalAuthority() {
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                account, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(project);
    when(identities.home(project)).thenReturn(Home.of("one"));
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(grant);
    when(grants.live(recipient)).thenReturn(List.of(binding));
    when(authority.allowsUsing(recipient, project, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(true);
    when(allowances.attach(any(Budget.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Budget.class));
    // Alice's own personal space, as the live repositories answer for it: an ordinary project row
    // whose scope is PERSONAL and owned by her account, and on which JdbcProjectMembers.role
    // answers MANAGER for its owner. Nothing here is Global authority either.
    when(identities.project(PERSONAL)).thenReturn(personal);
    when(identities.home(personal)).thenReturn(Home.of(PERSONAL));
    when(members.role(PERSONAL, "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(authority.allowsUsing(recipient, personal, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(true);
  }

  /** The call the write gate makes for a document upload, with only the home varying. */
  private ConversationRecord logPipeline(Home home) {
    return store.log(
        Origin.SUBMISSION, home, "document_pipeline", null, Budget.of(1), "alice", null);
  }

  /**
   * CONFIRMED, pinned as it stands. The traced chain holds: {@code ConversationStore}'s log path
   * does reach admission for a global home, and admission refuses it with the exact message the
   * live server returns. The refusal comes out of {@code scope(Home.global())} — admission's own
   * refusal, raised where a legacy home is resolved — so it happens before any identity, grant or
   * record is read, and before the INSERT.
   *
   * <p>This was the defect's last link, not a rule being blessed: a person uploading a document
   * with {@code scope:{kind:"personal"}} was refused here because the gate handed this door {@code
   * Home.global()} for a write that is not a Global write. <b>The gate has since been fixed to open
   * a personal selection's log against that account's own personal home, and this test stands
   * unchanged — a genuinely global home is still not an execution scope. Global admission stays
   * closed; do not relax this.</b>
   */
  @Test
  void a_global_home_is_refused_at_the_admission_link_of_the_log_path() {
    CallerFault refused = assertThrows(CallerFault.class, () -> logPipeline(Home.global()));

    assertEquals(REFUSAL, refused.getMessage());
    // Refused at scope resolution: nothing about the account was consulted, nothing was retained,
    // and no row was written. A refusal that had reached the INSERT would be a different defect.
    verify(identities, never()).account(anyString());
    verify(records, never()).retain(any());
    verifyNoInteractions(grants);
    verifyNoInteractions(jdbc);
  }

  /**
   * The control, and the reason the test above is about the home rather than about a broken
   * fixture. The identical call with a project home and an account holding a role on that project
   * is admitted: the project's scope resolves, a live grant supports {@code CONVERSATION_CREATE},
   * and the admission is retained against the scope's own home. Admission is therefore reached and
   * satisfiable on this path; only the global home fails it.
   */
  @Test
  void the_same_log_call_with_a_project_home_is_admitted() {
    ConversationRecord logged = assertDoesNotThrow(() -> logPipeline(Home.of("one")));
    assertEquals(Home.of("one"), logged.home());

    var admitted = org.mockito.ArgumentCaptor.forClass(OperationAuthority.Admission.class);
    verify(records).retain(admitted.capture());
    assertEquals(Home.of("one"), admitted.getValue().home());
    assertEquals(project.id(), admitted.getValue().scope());
    assertEquals(Set.of(AuthorityAction.CONVERSATION_CREATE), admitted.getValue().actions());
  }

  /**
   * The fix, executed at the link that refused it. The information write gate now opens a PERSONAL
   * selection's {@code document_pipeline} log against {@code PersonalSpaces.name(account)} instead
   * of {@code Home.global()}, and that is the home handed to this door. It is admitted: the home
   * resolves to a {@code Personal} scope, {@code admit} own-checks that scope against the executor,
   * a live grant supports {@code CONVERSATION_CREATE}, {@code requireOwn} re-resolves the scope
   * from the retained project name and holds it to the owner's MANAGER ceiling, and the admission
   * is retained under that personal home.
   *
   * <p>This is the assertion the acceptance criterion asks for: not that the home changed, but that
   * the changed home passes the admission that the global home fails. Taken with {@link
   * #a_global_home_is_refused_at_the_admission_link_of_the_log_path}, which is unchanged, it also
   * shows the fix did not widen admission — the global tier is still refused.
   */
  @Test
  void a_personal_home_is_admitted_at_the_same_link_that_refuses_the_global_one() {
    ConversationRecord logged =
        assertDoesNotThrow(() -> logPipeline(Home.of(PersonalSpaces.name("alice"))));
    assertEquals(Home.of(PERSONAL), logged.home());

    var admitted = org.mockito.ArgumentCaptor.forClass(OperationAuthority.Admission.class);
    verify(records).retain(admitted.capture());
    assertEquals(Home.of(PERSONAL), admitted.getValue().home());
    assertFalse(admitted.getValue().home().isGlobal());
    assertEquals(personal.id(), admitted.getValue().scope());
    assertEquals(account, admitted.getValue().account());
    assertEquals(Set.of(AuthorityAction.CONVERSATION_CREATE), admitted.getValue().actions());
  }

  /**
   * The ownership fence this resolution depends on, executed rather than read. The identical call
   * for a home that names <em>another</em> account's personal space is refused: {@code admit}
   * compares the {@code Personal} scope's account against the executor's before any grant is
   * consulted. Resolving a personal home is therefore a destination, not an authority — which is
   * what makes it safe for the write gate to resolve one from the caller's own selection.
   */
  @Test
  void another_accounts_personal_home_is_refused_even_with_a_live_grant() {
    String other = PersonalSpaces.name("bob");
    var bobs =
        new AuthorityScope.Personal(
            new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 3);
    when(identities.project(other)).thenReturn(bobs);
    when(identities.home(bobs)).thenReturn(Home.of(other));
    when(members.role(other, "alice")).thenReturn(Optional.of(ProjectRole.MANAGER));
    when(authority.allowsUsing(recipient, bobs, AuthorityAction.CONVERSATION_CREATE, grant))
        .thenReturn(true);

    CallerFault refused = assertThrows(CallerFault.class, () -> logPipeline(Home.of(other)));

    assertEquals(REFUSAL, refused.getMessage());
    verify(records, never()).retain(any());
    verifyNoInteractions(jdbc);
  }
}
