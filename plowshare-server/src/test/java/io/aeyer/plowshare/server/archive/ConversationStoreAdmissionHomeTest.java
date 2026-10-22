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
 * it is handed is global — the link between {@code InformationWriteGates} opening a personal
 * upload's pipeline log against {@code Home.global()} (see {@code InformationWriteGatesHomeTest})
 * and the live "Operation is outside its retained scoped authority" refusal.
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
   * <p>This is the defect's last link, not a rule being blessed: a person uploading a document with
   * {@code scope:{kind:"personal"}} is refused here because the gate handed this door {@code
   * Home.global()} for a write that is not a Global write. <b>When the gate is fixed to open a
   * personal selection's log against that account's own personal home, this test keeps standing — a
   * genuinely global home is still not an execution scope — but the live refusal disappears.</b>
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
}
