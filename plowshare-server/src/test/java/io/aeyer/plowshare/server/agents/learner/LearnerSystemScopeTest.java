package io.aeyer.plowshare.server.agents.learner;

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
import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.access.OperationInference;
import io.aeyer.plowshare.server.access.ScopedAuthority;
import io.aeyer.plowshare.server.access.ScopedGrantRepository;
import io.aeyer.plowshare.server.access.StandardRole;
import io.aeyer.plowshare.server.access.SystemOperationRecords;
import io.aeyer.plowshare.server.access.SystemOperations;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.EntryKind;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a learning pass is actually admitted against, and whether the work that
 * scope admits has any latitude to spend it.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>{@code Learner.pass} hands {@code LearningWindow.home()} — the home on the row of whichever
 * conversation the window provider chose — to {@code Learner.authorize}, which calls {@link
 * SystemOperations#authorize}. That {@code default}'s body is the five-argument {@link
 * SystemOperations#admit(SystemOperations.Purpose, Home, AgentDefinition, String, Runnable)}, which
 * carries no {@code selected} scope, so {@link BoundSystemOperations} resolves the missing argument
 * from the home alone; {@code LEARNING} is not one of the deployment purposes that short-circuit to
 * {@code ADMINISTRATION}.
 *
 * <p>None of that is asserted from the source. The real {@link BoundSystemOperations} runs over
 * mocked repositories, a {@link Recording} wrapper notes which {@code admit} overload was reached,
 * a mock of {@link SystemOperations} delegating to it lets an {@link ArgumentCaptor} read the home
 * {@code authorize} was handed, and the scope is read off the {@link
 * SystemOperationRecords.Envelope} admission retains — the scope every later {@code require} is
 * re-checked against.
 *
 * <h2>Which homes a pass actually sees in production</h2>
 *
 * <p>A person's conversation is never global: {@code ConversationStore.open} admits against {@code
 * operations.scope(home)}, and that resolution refuses a global home outright, which {@code
 * ConversationStoreAdmissionHomeTest} pins. {@code docs/personal-space.md} states the same
 * migration outcome in as many words — "writing/resuming a global conversation is refused" — so the
 * person-facing global conversation is a removed concept.
 *
 * <p><b>The global branch here is nonetheless reachable, and is not a holdover.</b> Two live routes
 * reach it. First, {@code ConversationStore.logInternal} resolves a scope only for {@code
 * Origin.SUBMISSION} and {@code Origin.EVENT}; {@code Origin.MEMORY} falls outside that pair, so a
 * global memory operation's own log — {@code Digests.start(null)}, through {@code
 * Compaction.logFor(Origin.MEMORY, Home.global(), ..)} and {@code ConversationStore.log} — still
 * writes a global-home row today. Second, the same migration note says "ownerless global logs
 * remain available as history", so a deployed database holds global-home rows regardless. The
 * window provider does not filter by origin — {@code EntryStore.CONVERSATIONS_AWAITING_THE_LEARNER}
 * selects on {@code learned_at} and foldedness and nothing else — so either kind becomes a
 * candidate once its rows are folded and unlearned. Deleting the global branch as dead would
 * therefore be wrong; what is left of the person-facing feature is the refusal, not the rows.
 *
 * <h2>Stubbing</h2>
 *
 * <p>The window provider's two reads are mocked so that one conversation is chosen with one folded
 * entry waiting; those feed which window is taken, not the decision this test reads. The model
 * answers with no proposals, so nothing is filed and the pass ends on its own note. The SYSTEM
 * grant is deliberately <b>not</b> narrowed by scope: the question is which scope admission
 * chooses, not whether a grant refuses one. Repositories are mocked, per the database test policy.
 */
class LearnerSystemScopeTest {

  private static final String CONVERSATION = "conversation";
  private static final String PROJECT = "atlas";

  /** The SYSTEM account's own live intrinsic grant; the only authority a pass carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope the control pass must reach through the very same machinery. */
  private static final AuthorityScope ATLAS =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /**
   * The kind of scope a pass over somebody's own material would have to be admitted against to be
   * bounded by the account behind it. Stubbed only so the negative assertion names something real.
   */
  private static final AuthorityScope SOMEONE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  /**
   * CONFIRMED DEFECT, pinned as it stands.
   *
   * <p>A pass over a global-home conversation is admitted against the built-in {@code
   * GLOBAL_KNOWLEDGE} scope — the whole global knowledge tier — rather than against any scope the
   * window itself names. {@code Learner} reaches {@link SystemOperations#authorize}, whose body is
   * the five-argument {@code admit}; that overload has no {@code selected} scope to prefer, so
   * {@link BoundSystemOperations} reads the global home as the global knowledge tier and admits the
   * pass with {@code READ}, {@code WRITE}, {@code CONVERSATION_CREATE} and {@code RUN_MODEL} there.
   *
   * <h2>A wrong ceiling, and unusable — the same shape as a conversation fold</h2>
   *
   * <p>The pass has no latitude with which to spend the scope, and that is enforced rather than
   * intended. {@code the_pass_is_bound_to_one_toolless_pinned_prompt} executes the fence: {@code
   * BoundSystemOperations.claimed(ChatRequest, Budget.Claim)} refuses the call unless {@code
   * request.tools()} is empty and the model matches, and because {@code authorize} passes {@code
   * request.messages().toString()} as the binding, the prompt is pinned by hash as well. {@code
   * Learner} never mentions {@code ScopeId} or {@code AuthorityScope}; what it does with the answer
   * — {@code Archive.write}, {@code Scribe.judge}, {@code Archive.applyVerdict} — takes {@code
   * window.home()} and no scope, and none of those reads the admitted work at all. So the scope
   * gates one fixed span-to-proposals transformation's own model call and nothing else.
   *
   * <p>It is still the wrong scope, reached by the five-argument overload having nowhere to put
   * one, and the bound holds only because a second, unrelated mechanism holds. A conformance and
   * defence-in-depth defect, not live over-granted reach.
   *
   * <p><b>When this is fixed, invert the assertions</b> — a global-home pass must be admitted
   * against a scope the window itself names, and the six-argument {@code admit} must be the
   * overload reached.
   */
  @Test
  void a_global_home_window_is_mined_against_global_knowledge() {
    Fixture fixture = new Fixture(Home.global());
    fixture.mine();

    // The omission itself: the overload that cannot be told its scope is the one called.
    assertEquals(
        List.of(Home.global()),
        fixture.recording.withoutAScope,
        "the pass reached the five-argument admit, which carries no selected scope");
    assertTrue(fixture.recording.withAScope.isEmpty(), "no caller supplied a scope for this pass");

    Home home = fixture.capturedHome();
    assertTrue(home.isGlobal(), "the window carried the conversation row's global home");
    assertEquals(Home.global(), home);

    // And the scope admission actually retained, which every later require() re-checks.
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE,
        fixture.capturedScope(),
        "a global-home window's learning pass is admitted against GLOBAL_KNOWLEDGE");
    assertNotEquals(SOMEONE_PERSONAL.id(), fixture.capturedScope());
    assertNotEquals(ATLAS.id(), fixture.capturedScope());
  }

  /**
   * The control, through the identical machinery: the same {@code Learner.authorize}, the same
   * {@code authorize} default, the same five-argument {@code admit} and the same {@link
   * BoundSystemOperations}. A window whose conversation row names a project carries that project in
   * its home, so admission resolves the project's own scope — which proves the test above exercised
   * the live path rather than a stub, and that the five-argument overload is not simply broken for
   * every caller.
   *
   * <p>So the scope a pass is admitted against <b>does</b> depend on the window's home, and the
   * global tier is the only home that loses its bound.
   */
  @Test
  void a_project_home_window_is_mined_against_that_project_scope() {
    Fixture fixture = new Fixture(Home.of(PROJECT));
    fixture.mine();

    assertEquals(List.of(Home.of(PROJECT)), fixture.recording.withoutAScope);
    assertTrue(fixture.recording.withAScope.isEmpty());

    Home home = fixture.capturedHome();
    assertEquals(Home.of(PROJECT), home);
    assertFalse(home.isGlobal());

    assertEquals(
        ATLAS.id(),
        fixture.capturedScope(),
        "a project-home window's learning pass reaches that project's scope");
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, fixture.capturedScope());
  }

  /**
   * Why the wrong ceiling above is unusable rather than live reach: the admitted call is one
   * tool-less model call against a prompt pinned at admission.
   *
   * <p>Executed, not read. The dispatcher re-presents the exact request to {@code
   * CallAuthorization.requireRequest}, which is what the production dispatcher does, and it passes
   * — so the fence is live on this path. Then the same authorization is re-offered a request whose
   * messages differ, and it is refused. That is the whole of the latitude {@code LEARNING} has: an
   * empty tool list, one prompt pinned by hash, and no scope handed to any repository.
   */
  @Test
  void the_pass_is_bound_to_one_toolless_pinned_prompt() {
    Fixture fixture = new Fixture(Home.global());
    fixture.mine();

    ChatRequest asked = fixture.asked;
    assertNotNull(asked.authorization(), "the production path authorizes the request");
    assertTrue(asked.tools().isEmpty(), "the learner is offered no tool");
    assertDoesNotThrow(() -> asked.authorization().requireRequest(asked));

    assertThrows(
        RuntimeException.class,
        () ->
            asked
                .authorization()
                .requireRequest(
                    ChatRequest.of(
                        asked.specifier(), List.of(ChatMessage.user("read this span instead")))),
        "the prompt is pinned by hash at admission");
  }

  // --- the fixture ----------------------------------------------------------

  /**
   * A {@link SystemOperations} that records which {@code admit} overload a caller reached and
   * delegates the decision itself to the production {@link BoundSystemOperations}. It inherits the
   * interface's own {@code authorize} default, so the path from {@code authorize} to {@code admit}
   * is the shipped one.
   */
  private static final class Recording implements SystemOperations {
    private final SystemOperations delegate;
    final List<Home> withoutAScope = new CopyOnWriteArrayList<>();
    final List<Home> withAScope = new CopyOnWriteArrayList<>();

    Recording(SystemOperations delegate) {
      this.delegate = delegate;
    }

    @Override
    public Work admit(
        Purpose purpose, Home home, AgentDefinition definition, String binding, Runnable validity) {
      withoutAScope.add(home);
      return delegate.admit(purpose, home, definition, binding, validity);
    }

    @Override
    public Work admit(
        Purpose purpose,
        Home home,
        ScopeId scope,
        AgentDefinition definition,
        String binding,
        Runnable validity) {
      withAScope.add(home);
      return delegate.admit(purpose, home, scope, definition, binding, validity);
    }
  }

  private static final class Fixture {
    final SystemOperationRecords evidence = mock(SystemOperationRecords.class);
    final LlmDispatcher models = mock(LlmDispatcher.class);
    final EntryStore entries = mock(EntryStore.class);
    final ConversationStore conversations = mock(ConversationStore.class);
    final Archive archive = mock(Archive.class);
    final Scribe scribe = mock(Scribe.class);
    final Recording recording;
    final SystemOperations system;
    final Learner learner;

    /** The request the dispatcher was actually handed, after authorization. */
    ChatRequest asked;

    Fixture(Home home) {
      IdentityRepository identities = mock(IdentityRepository.class);
      ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
      ScopedAuthority authority = mock(ScopedAuthority.class);

      // Every scope admission could settle on, resolvable both ways: by identifier,
      // which the validity re-check reads, and by project, which the home reads.
      when(identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE)).thenReturn(GLOBAL_KNOWLEDGE);
      when(identities.scope(ATLAS.id())).thenReturn(ATLAS);
      when(identities.scope(SOMEONE_PERSONAL.id())).thenReturn(SOMEONE_PERSONAL);
      when(identities.project(PROJECT)).thenReturn(ATLAS);

      // One live SYSTEM-role grant allowing every action the bounded lane needs, against
      // whichever scope admission settles on. Nothing here narrows by scope.
      ScopedGrantRepository.Binding root = mock(ScopedGrantRepository.Binding.class);
      when(root.id()).thenReturn(GRANT);
      when(root.role()).thenReturn(StandardRole.SYSTEM);
      when(grants.live(SYSTEM_RECIPIENT)).thenReturn(List.of(root));
      when(authority.allowsUsing(
              eq(SYSTEM_RECIPIENT), any(), any(AuthorityAction.class), eq(GRANT)))
          .thenReturn(true);

      recording =
          new Recording(
              new BoundSystemOperations(
                  identities,
                  grants,
                  authority,
                  evidence,
                  new OperationInference(
                      mock(OperationAuthority.class), mock(InferenceAdmissions.class))));
      system = mock(SystemOperations.class, withSettings().defaultAnswer(delegatesTo(recording)));

      // One conversation in the queue, its row carrying the home under test, and one
      // folded unlearned entry for the window to hold.
      ConversationRecord row = mock(ConversationRecord.class);
      when(row.home()).thenReturn(home);
      when(row.lifecycle()).thenReturn(ConversationLifecycle.ACTIVE);
      when(entries.conversationsAwaitingTheLearner(anyInt())).thenReturn(List.of(CONVERSATION));
      when(conversations.rootOf(CONVERSATION)).thenReturn(Optional.of(row));
      when(conversations.find(CONVERSATION)).thenReturn(Optional.of(row));
      when(entries.awaitingTheLearner(eq(CONVERSATION), any(), anyInt()))
          .thenReturn(List.of(entry()));
      when(entries.countAwaitingTheLearner(eq(CONVERSATION), any())).thenReturn(1);

      when(models.complete(any()))
          .thenAnswer(
              call -> {
                asked = call.getArgument(0);
                asked.authorization().requireRequest(asked);
                return new Completion("{\"memories\": []}", "stop", TokenUsage.UNKNOWN, List.of());
              });

      learner =
          new Learner(
              models,
              entries,
              conversations,
              archive,
              scribe,
              () -> new AgentRegistry(Map.of(Learner.AGENT, definition())),
              system);
    }

    /** The fold's own trigger: the one door onto {@code LEARNING}. */
    void mine() {
      learner.thereIsMaterial();
      assertNotNull(asked, "the pass must have reached a model to have admitted anything");
    }

    /** The home the five-argument {@code admit} was reached with, through {@code authorize}. */
    Home capturedHome() {
      ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
      verify(system)
          .authorize(
              eq(SystemOperations.Purpose.LEARNING),
              home.capture(),
              any(AgentDefinition.class),
              any(ChatRequest.class),
              any(Budget.class),
              any(Runnable.class));
      return home.getValue();
    }

    /** The scope identifier the real admission retained for that SYSTEM operation. */
    ScopeId capturedScope() {
      ArgumentCaptor<SystemOperationRecords.Envelope> envelope =
          ArgumentCaptor.forClass(SystemOperationRecords.Envelope.class);
      verify(evidence).retain(envelope.capture());
      assertEquals(SystemOperations.Purpose.LEARNING, envelope.getValue().purpose());
      return envelope.getValue().scope();
    }

    private static EntryRecord entry() {
      return new EntryRecord(
          CONVERSATION,
          1,
          EntryKind.UTTERANCE,
          "we roll back on red",
          null,
          List.of(),
          null,
          null,
          1,
          null,
          null,
          null,
          null);
    }

    /** The learner as it is shipped: no tools, no callees, one call, the reasoning class. */
    private static AgentDefinition definition() {
      return new AgentDefinition(
          Learner.AGENT,
          "says whether anything here is worth remembering",
          "reasoning",
          List.of(),
          List.of(),
          List.of(),
          1,
          1,
          "You read a span and decide.");
    }
  }
}
