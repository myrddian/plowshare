package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
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
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryRecord;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a conversation compaction is actually admitted against, and whether that
 * depends on the conversation's {@link Home}.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>{@code Compaction.summaryRequest} reads the home from the conversation's own row — {@code
 * conversations.find(conversation).orElseThrow().home()} — and hands it to {@link
 * SystemOperations#authorize}, a {@code default} whose body is the five-argument {@link
 * SystemOperations#admit(SystemOperations.Purpose, Home, AgentDefinition, String, Runnable)}. That
 * overload carries no {@code selected} scope, so {@link BoundSystemOperations} resolves the missing
 * argument from the home. {@code CONVERSATION_COMPACTION} is not one of the deployment purposes
 * that short-circuit to {@code ADMINISTRATION}, so the home is the only input left.
 *
 * <p>None of that is asserted from the source here. The real {@link BoundSystemOperations} runs
 * over mocked repositories; a {@link Recording} wrapper delegates every {@code admit} to it so the
 * overload that was reached can be seen, and a mock of {@link SystemOperations} delegating to that
 * wrapper lets an {@link ArgumentCaptor} read the home {@code authorize} was handed. The scope the
 * operation is actually admitted against is read off the {@link SystemOperationRecords.Envelope}
 * admission retains — which is the scope every later {@code require} is re-checked against.
 *
 * <h2>The seam, and why it is the in-turn fold</h2>
 *
 * <p>{@code CONVERSATION_COMPACTION} has exactly one call site in the server: {@code
 * Compaction.TurnTranscript.summarisingCall}. It is reached synchronously through {@code
 * transcriptFor(...).stepEnded(...)} — the fold at a tool-call boundary — which is the narrowest
 * entry that exercises the real admission without the background executor. {@code
 * Compaction.summaryForSkill} is a lighter door onto the same {@code summaryRequest}, but it
 * carries the {@code SKILL_SUMMARY} purpose, so pinning compaction's own purpose needs this path.
 *
 * <h2>Which homes a fold actually sees in production</h2>
 *
 * <p>A person's conversation is never global: {@code ConversationStore.open} admits against {@code
 * operations.scope(home)}, and {@code PinnedOperationAuthority.scope} refuses a global home
 * outright. Machine logs of {@code Origin.SUBMISSION} or {@code EVENT} are refused by the same
 * resolution through {@code logInternal} — which {@code ConversationStoreAdmissionHomeTest} pins —
 * and {@code Compaction.logFor} degrades to {@code Transcript.NONE} when they are. What reaches the
 * global branch is {@code Origin.MEMORY} and {@code Origin.CURATOR}, which fall outside {@code
 * logInternal}'s admitted origins and so write a global-home row: a global memory digest or
 * navigation ({@code Digests.start(null)}) and a curator pass. Those logs are not confined, so they
 * fold.
 *
 * <h2>Stubbing</h2>
 *
 * <p>The log, the turn thresholds and the token tally are stubbed so a fold becomes due; those feed
 * the decision to fold, not the decision this test reads. The SYSTEM grant is deliberately
 * <b>not</b> narrowed by scope: the question is which scope admission chooses, not whether a grant
 * refuses one. Repositories are mocked, per the database test policy; nothing here needs
 * PostgreSQL, because the question is which scope identifier admission settles on, not how a row
 * maps.
 */
class CompactionSystemScopeTest {

  private static final String CONVERSATION = "conversation";
  private static final String PROJECT = "atlas";

  /** The SYSTEM account's own live intrinsic grant; the only authority a fold carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope the control conversation must reach through the very same machinery. */
  private static final AuthorityScope ATLAS =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /**
   * The kind of scope a personal conversation's fold would have to be admitted against to be
   * bounded by the account that owns it. Stubbed only so the negative assertion names something
   * real.
   */
  private static final AuthorityScope SOMEONE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  /**
   * CONFIRMED DEFECT, pinned as it stands.
   *
   * <p>A conversation whose home is global has its compaction admitted against the built-in {@code
   * GLOBAL_KNOWLEDGE} scope — the whole global knowledge tier — rather than against any narrower
   * scope belonging to whoever or whatever owns the conversation. {@code Compaction} reaches {@link
   * SystemOperations#authorize}, whose body is the five-argument {@code admit}; that overload has
   * no {@code selected} scope to prefer, so {@link BoundSystemOperations} reads the global home as
   * the global knowledge tier and admits a fold with {@code READ}, {@code WRITE}, {@code
   * CONVERSATION_CREATE} and {@code RUN_MODEL} there.
   *
   * <h2>A wrong ceiling, and deliberately not called more than that</h2>
   *
   * <p>Compaction's own sandbox is its input and tool fence, not this scope, and ADR 0030 says so
   * for this operation by name: the summariser executes as SYSTEM because the fold pays from its
   * own admitted allowance rather than the turn's, and "the task's supplied conversation inputs and
   * bound tools restrict which content it can actually obtain and where it can persist its result".
   * That fence is enforced in code, not merely intended — {@code
   * BoundSystemOperations.claimed(ChatRequest, Budget.Claim)} refuses the call unless {@code
   * request.tools()} is empty and the model matches, then pins the prompt by hash. So this scope
   * cannot be spent as reach: {@code Compaction} never hands a scope to a repository, and the only
   * thing the scope gates is whether this one fixed transformation's own model call proceeds.
   *
   * <p>It is still the wrong scope. An admitted unit of work is supposed to carry an explicit one;
   * this one is reached by the five-argument overload having nowhere to put it, and the global
   * <em>knowledge</em> tier is not a description of a conversation fold's sandbox. The bound here
   * is correct only because a second, unrelated mechanism holds — which is why this is pinned as a
   * conformance and defence-in-depth defect rather than as live over-granted reach.
   *
   * <p><b>When this is fixed, invert the assertions</b> — a global-home conversation's fold must be
   * admitted against a scope the conversation itself names, and the six-argument {@code admit} must
   * be the overload reached.
   */
  @Test
  void a_global_home_conversation_folds_against_global_knowledge() {
    Fixture fixture = new Fixture(Home.global());
    try {
      fixture.fold();

      // The omission itself: the overload that cannot be told its scope is the one called.
      assertEquals(
          List.of(Home.global()),
          fixture.recording.withoutAScope,
          "the fold reached the five-argument admit, which carries no selected scope");
      assertTrue(
          fixture.recording.withAScope.isEmpty(), "no caller supplied a scope for this fold");

      Home home = fixture.capturedHome();
      assertTrue(home.isGlobal(), "the conversation's row carried the global home");
      assertEquals(Home.global(), home);

      // And the scope admission actually retained, which every later require() re-checks.
      assertEquals(
          BuiltInIdentities.GLOBAL_KNOWLEDGE,
          fixture.capturedScope(),
          "a global-home conversation's compaction is admitted against GLOBAL_KNOWLEDGE");
      assertNotEquals(SOMEONE_PERSONAL.id(), fixture.capturedScope());
      assertNotEquals(ATLAS.id(), fixture.capturedScope());
    } finally {
      fixture.compaction.close();
    }
  }

  /**
   * The control, and it runs through the identical machinery: the same {@code summaryRequest}, the
   * same {@code authorize} default, the same five-argument {@code admit} and the same {@link
   * BoundSystemOperations}. A conversation whose row names a project carries that project in its
   * home, so admission resolves the project's own scope — which proves the test above exercised the
   * live path rather than a stub, and that the five-argument overload is not simply broken for
   * every caller.
   *
   * <p>So the scope a compaction is admitted against <b>does</b> depend on the conversation's home,
   * and the global tier is the only home that loses its bound.
   */
  @Test
  void a_project_home_conversation_folds_against_that_project_scope() {
    Fixture fixture = new Fixture(Home.of(PROJECT));
    try {
      fixture.fold();

      // The same overload, with nothing selected: the home alone decides.
      assertEquals(List.of(Home.of(PROJECT)), fixture.recording.withoutAScope);
      assertTrue(fixture.recording.withAScope.isEmpty());

      Home home = fixture.capturedHome();
      assertEquals(Home.of(PROJECT), home);
      assertFalse(home.isGlobal());

      assertEquals(
          ATLAS.id(),
          fixture.capturedScope(),
          "a project-home conversation's compaction reaches that project's scope");
      assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, fixture.capturedScope());
    } finally {
      fixture.compaction.close();
    }
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
    final ConversationStore conversations = mock(ConversationStore.class);
    final ConversationRecord source = mock(ConversationRecord.class);
    final TurnStore turns = mock(TurnStore.class);
    final EntryStore entries = mock(EntryStore.class);
    final CompactionStore compactions = mock(CompactionStore.class);
    final Recording recording;
    final SystemOperations system;
    final Compaction compaction;

    final AgentDefinition folder =
        new AgentDefinition(
            Compaction.FOLDER,
            "folder",
            "model",
            List.of(),
            List.of(),
            List.of(),
            1,
            2,
            "Summarise");

    private final List<ChatMessage> history = new ArrayList<>();

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

      // The SYSTEM account holds one live SYSTEM-role grant that allows every action a
      // fold needs, against whichever scope admission settles on. Nothing here narrows
      // by scope: the test is about which scope is chosen, not about a grant refusing one.
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
      // A mock of the interface delegating to the recorder, so a captor can see what
      // Compaction handed authorize. The admission decision itself is the production one.
      system = mock(SystemOperations.class, withSettings().defaultAnswer(delegatesTo(recording)));

      Budget task = Budget.of(1);
      assertTrue(task.trySpend(), "the turn's own allowance is spent; a fold spends neither");
      when(source.home()).thenReturn(home);
      when(source.budget()).thenReturn(task);
      when(conversations.find(CONVERSATION)).thenReturn(Optional.of(source));
      when(conversations.lifecycleOf(CONVERSATION))
          .thenReturn(Optional.of(ConversationLifecycle.ACTIVE));

      // A window the turn has outgrown, so a fold is due at the tool-call boundary.
      when(models.contextLength(any(), anyInt())).thenReturn(OptionalInt.of(100_000));
      when(models.compactionThreshold(anyString())).thenReturn(OptionalInt.of(60_000));
      when(models.compactionNowThreshold(anyString())).thenReturn(OptionalInt.of(80_000));
      when(models.streamFold(any(), any()))
          .thenAnswer(
              call -> {
                ChatRequest asked = call.getArgument(0);
                asked.authorization().requireRequest(asked);
                return new Completion("summary", "stop", TokenUsage.UNKNOWN, List.of());
              });

      // One turn of four folded steps and their results, in the log and in the run's
      // history, so the fold has something to summarise and the two agree call for call.
      List<EntryRecord> log = new ArrayList<>();
      history.add(ChatMessage.system("worker instructions"));
      history.add(ChatMessage.user("current request"));
      log.add(entry(1, EntryKind.UTTERANCE, "current request", null, List.of()));
      for (int step = 0; step < 4; step++) {
        ToolCall call = new ToolCall("call-" + step, "read", "{}");
        history.add(ChatMessage.assistant("working", List.of(call)));
        history.add(ChatMessage.tool(call.id(), "result"));
        log.add(entry(2 + step * 2, EntryKind.ANSWER, "working", null, List.of(call)));
        log.add(entry(3 + step * 2, EntryKind.TOOL_RESULT, "result", call.id(), List.of()));
      }
      when(entries.thatProjectFor(CONVERSATION)).thenReturn(log);

      compaction =
          new Compaction(
              models,
              () -> folder,
              turns,
              compactions,
              entries,
              4096,
              conversations,
              Learning.NONE,
              Citing.NONE,
              system,
              Budget::none);
    }

    /** The in-turn fold: the one synchronous door onto {@code CONVERSATION_COMPACTION}. */
    void fold() {
      Compaction.TurnTranscript transcript =
          compaction.transcriptFor(CONVERSATION, folder, Speaker.person(null));
      transcript.promptMeasured(90_000);
      transcript.stepEnded(history, 1);
    }

    /** The home the five-argument {@code admit} was actually reached with, through authorize. */
    Home capturedHome() {
      ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
      verify(system)
          .authorize(
              eq(SystemOperations.Purpose.CONVERSATION_COMPACTION),
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
      assertEquals(SystemOperations.Purpose.CONVERSATION_COMPACTION, envelope.getValue().purpose());
      return envelope.getValue().scope();
    }

    private static EntryRecord entry(
        int ordinal, EntryKind kind, String text, String callId, List<ToolCall> calls) {
      return new EntryRecord(
          CONVERSATION, ordinal, kind, text, callId, calls, null, null, 1, null, null, null, null);
    }
  }
}
