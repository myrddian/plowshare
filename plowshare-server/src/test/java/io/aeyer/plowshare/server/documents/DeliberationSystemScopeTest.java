package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityAction;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.BoundSystemOperations;
import io.aeyer.plowshare.server.access.BuiltInIdentities;
import io.aeyer.plowshare.server.access.GrantRecipient;
import io.aeyer.plowshare.server.access.IdentityRepository;
import io.aeyer.plowshare.server.access.InferenceCalls;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.access.ScopedGrantRepository;
import io.aeyer.plowshare.server.access.StandardRole;
import io.aeyer.plowshare.server.access.SystemOperationRecords;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.JobRuntime;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.information.InformationAccess;
import io.aeyer.plowshare.server.information.InformationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a SYSTEM stage of {@code information.ask} is actually admitted against.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>{@link Deliberation#scoped} derives the pass's {@link Home} from {@link
 * InformationContext.Selection#project()} alone, and {@code Selection} forbids every non-PROJECT
 * scope from naming a project — so a PERSONAL selection reaches that expression with {@code project
 * == null} and falls to {@link Home#global()}. {@code Deliberation.run} then reaches {@link
 * SystemOperations#admit(SystemOperations.Purpose, Home, AgentDefinition, String, Runnable)} — the
 * five-argument overload, which carries no {@code selected} scope — and {@link
 * BoundSystemOperations} resolves the missing argument from the home: global, and not a deployment
 * purpose, means {@link BuiltInIdentities#GLOBAL_KNOWLEDGE}.
 *
 * <p>Each step above is readable in the source. None of them is asserted from the source here. The
 * real {@link BoundSystemOperations} runs over mocked repositories, a mock of the {@link
 * SystemOperations} interface delegates to it so the arguments can be captured, and the scope the
 * operation is actually admitted against is read off the {@link SystemOperationRecords.Envelope}
 * that admission retains — which is the scope every later {@code require} is checked against.
 *
 * <p>The database test policy: repositories are mocked. Nothing here needs PostgreSQL, because the
 * question is which scope identifier admission chooses, not how a row maps.
 */
class DeliberationSystemScopeTest {

  private static final String ACCOUNT = "alice";
  private static final String QUESTION = "is the bound tight";
  private static final BooleanSupplier NEVER = () -> false;
  private static final Instant AT = Instant.parse("2026-09-05T09:00:00Z");

  /** The SYSTEM account's own live intrinsic grant; the only authority a SYSTEM stage carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope the control selection must reach through the very same machinery. */
  private static final AuthorityScope ATLAS =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /** The scope an ask over a personal document would have to be admitted against to be bounded. */
  private static final AuthorityScope ALICE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  private final IdentityRepository identities = mock(IdentityRepository.class);
  private final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
  private final ScopedAuthority authority = mock(ScopedAuthority.class);
  private final SystemOperationRecords records = mock(SystemOperationRecords.class);
  private final InferenceCalls calls = mock(InferenceCalls.class);

  private final BoundSystemOperations bound =
      new BoundSystemOperations(identities, grants, authority, records, calls);

  /**
   * A mock of the interface that delegates every call to the real {@link BoundSystemOperations}.
   * The mock exists only so an {@link ArgumentCaptor} can see which overload {@code Deliberation}
   * calls and with what; the admission decision itself is the production one.
   */
  private final SystemOperations system =
      mock(SystemOperations.class, withSettings().defaultAnswer(delegatesTo(bound)));

  private final DocumentStore store = mock(DocumentStore.class);
  private final RetrievalService retrieval = mock(RetrievalService.class);
  private final CitationStore citations = mock(CitationStore.class);
  private final JobRuntime runtime = mock(JobRuntime.class);
  private final JobRuntime systemLane = mock(JobRuntime.class);
  private final AgentRegistry registry = mock(AgentRegistry.class);
  private final InformationAccess access = mock(InformationAccess.class);

  private final UUID document = UUID.randomUUID();

  DeliberationSystemScopeTest() {
    // The SYSTEM account holds one live SYSTEM-role grant that allows every action
    // the bounded model lane needs, against whichever scope admission settles on.
    // Nothing here narrows by scope: the test is about which scope is chosen, not
    // about a grant refusing one.
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(GRANT);
    when(binding.role()).thenReturn(StandardRole.SYSTEM);
    when(grants.live(SYSTEM_RECIPIENT)).thenReturn(List.of(binding));
    when(authority.allowsUsing(eq(SYSTEM_RECIPIENT), any(), any(AuthorityAction.class), eq(GRANT)))
        .thenReturn(true);

    when(identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE)).thenReturn(GLOBAL_KNOWLEDGE);
    when(identities.scope(ATLAS.id())).thenReturn(ATLAS);
    when(identities.scope(ALICE_PERSONAL.id())).thenReturn(ALICE_PERSONAL);
    when(identities.project("atlas")).thenReturn(ATLAS);

    // The scoped() copy constructor asks each store for its caller-scoped view.
    when(store.scoped(any(), any())).thenReturn(store);
    when(retrieval.scoped(any(), any())).thenReturn(retrieval);
    when(citations.scoped(any(), any())).thenReturn(citations);

    // A summarised document with nothing owed, which is the only state `ask` will
    // deliberate over. Retrieval returns no passage: `countUnembedded` of zero is
    // the ordinary "nothing close" path and does not stop the pass.
    when(store.find(document)).thenReturn(Optional.of(summarised(document)));
    when(store.hierarchy(document))
        .thenReturn(
            List.of(
                new DocumentStore.StoredChapter(
                    UUID.randomUUID(),
                    new StructuralRef.Named("Introduction"),
                    "What the introduction claims.",
                    List.of())));

    for (String name :
        List.of(
            Deliberation.PROPOSER,
            Deliberation.CRITIC,
            Deliberation.REVIEWER,
            Deliberation.SYNTHESISER)) {
      when(registry.get(name)).thenReturn(stage(name));
    }
    when(registry.names())
        .thenReturn(
            Set.of(
                Deliberation.PROPOSER,
                Deliberation.CRITIC,
                Deliberation.REVIEWER,
                Deliberation.SYNTHESISER));

    // The SYSTEM lane the admitted work is handed to. The proposer stops at its
    // turn cap, which ends the pass after exactly one admitted stage.
    when(runtime.forSystemWork(any())).thenReturn(systemLane);
    when(systemLane.run(any(), anyString(), any(), any(), any(), isNull(), any(), any()))
        .thenReturn(new Outcome(Ending.TURN_CAP, "stopped at one stage", 0, 1, ""));
  }

  /**
   * CONFIRMED DEFECT, pinned as it stands.
   *
   * <p>A SYSTEM stage of {@code information.ask} over a PERSONAL document selection is admitted
   * against the built-in {@code GLOBAL_KNOWLEDGE} scope — not against the asking account's own
   * personal scope. The pass's home is {@link Home#global()} because a PERSONAL {@code Selection}
   * is forbidden to name a project, and {@code Deliberation.run} calls the five-argument {@code
   * admit}, which has no {@code selected} scope to prefer, so {@link BoundSystemOperations} reads
   * the global home as the global knowledge tier.
   *
   * <p>SYSTEM is a real account that owns everything; the scope is what bounds its reach. A scope
   * arrived at by omission is that bound failing open rather than a narrower authority chosen.
   *
   * <p><b>When this is fixed, invert the assertions</b> — a personal selection's SYSTEM stage must
   * be admitted against that account's own personal scope, and the six-argument {@code admit} must
   * be the overload reached.
   */
  @Test
  void personal_selection_admits_its_system_stage_against_global_knowledge() {
    ask(InformationContext.Selection.personal());

    // The omission itself: the overload that cannot be told its scope is the one called.
    Home home = capturedHome();
    assertTrue(home.isGlobal(), "a personal selection fell to the global home in scoped()");
    assertEquals(Home.global(), home);
    verify(system, never())
        .admit(any(), any(), any(ScopeId.class), any(), anyString(), any(Runnable.class));

    // And the scope admission actually retained, which every later require() is checked against.
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE,
        capturedScope(),
        "a personal document's SYSTEM stage is admitted against GLOBAL_KNOWLEDGE");
    assertNotEquals(ALICE_PERSONAL.id(), capturedScope());
  }

  /**
   * The control, and it runs through the identical machinery: the same {@code Deliberation.scoped},
   * the same five-argument {@code admit} and the same {@link BoundSystemOperations}. A PROJECT
   * selection names a project, so the home carries one and admission resolves the project's own
   * scope — which proves the test above exercised the live path rather than a stub, and that the
   * five-argument overload is not simply broken for every caller.
   */
  @Test
  void project_selection_admits_its_system_stage_against_that_project_scope() {
    ask(InformationContext.Selection.project("atlas"));

    Home home = capturedHome();
    assertEquals(Home.of("atlas"), home);
    assertFalse(home.isGlobal());
    assertEquals(ATLAS.id(), capturedScope(), "a project selection reaches its project scope");
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, capturedScope());
  }

  // --- the fixture ----------------------------------------------------------

  private void ask(InformationContext.Selection selection) {
    Deliberation deliberation =
        new Deliberation(
            store,
            retrieval,
            citations,
            runtime,
            () -> registry,
            Clock.fixed(AT, ZoneOffset.UTC),
            null);
    deliberation.useSystemOperations(system);
    Deliberation pass =
        deliberation.scoped(access, new InformationContext(ACCOUNT, selection), "session-1");
    Outcome outcome = pass.ask(document, QUESTION, Budget.of(Deliberation.A_PASS), NEVER);
    assertNotNull(outcome, "the pass must have reached a stage to have admitted anything");
  }

  /** The home the five-argument {@code admit} was actually handed. */
  private Home capturedHome() {
    ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
    verify(system)
        .admit(
            eq(SystemOperations.Purpose.DOCUMENT_ASK),
            home.capture(),
            any(AgentDefinition.class),
            anyString(),
            any(Runnable.class));
    return home.getValue();
  }

  /** The scope identifier the real admission retained for that SYSTEM operation. */
  private ScopeId capturedScope() {
    ArgumentCaptor<SystemOperationRecords.Envelope> envelope =
        ArgumentCaptor.forClass(SystemOperationRecords.Envelope.class);
    verify(records).retain(envelope.capture());
    assertEquals(SystemOperations.Purpose.DOCUMENT_ASK, envelope.getValue().purpose());
    return envelope.getValue().scope();
  }

  private static DocumentStore.StoredDocument summarised(UUID id) {
    return new DocumentStore.StoredDocument(
        id,
        "paper.pdf",
        "A paper",
        "content-hash",
        "text-hash",
        1024L,
        AT,
        ACCOUNT,
        "What the whole paper argues.",
        Vocabulary.SECTION);
  }

  private static AgentDefinition stage(String name) {
    return new AgentDefinition(
        name, name, "fast", List.of(), List.of(), List.of(), 1, 1, "Deliberate.");
  }
}
