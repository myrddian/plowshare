package io.aeyer.plowshare.server.agents.digests;

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
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Compaction;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a system memory operation — a digest pass or a navigation — is actually
 * admitted against, and how much latitude the admission leaves it.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>{@code DigestModel.operation} calls the five-argument {@link
 * SystemOperations#admit(SystemOperations.Purpose, Home, AgentDefinition, String, Runnable)}
 * directly, with the tier being digested or navigated as the home. That overload carries no {@code
 * selected} scope, so {@link BoundSystemOperations} resolves the missing argument from the home
 * alone; {@code MEMORY_DIGEST} is not one of the deployment purposes that short-circuit to {@code
 * ADMINISTRATION}.
 *
 * <p>None of that is asserted from the source. The real {@link BoundSystemOperations} runs over
 * mocked repositories, a mock of {@link SystemOperations} delegates to it so an {@link
 * ArgumentCaptor} can see which overload was reached and with what, and the scope is read off the
 * {@link SystemOperationRecords.Envelope} admission retains — the scope every later {@code require}
 * is re-checked against.
 *
 * <h2>Which homes an operation actually sees in production</h2>
 *
 * <p>Both tiers are reachable, and the global one routinely: {@code Digester.folded} loops over
 * {@code DigestStore.homes()} and passes each one, the global tier among them, and {@code
 * Navigator.navigate} is called with whichever tier is being recalled from — {@code
 * Digests.start(null)} is the global pass. Nothing on this path refuses a global home the way
 * {@code ConversationStore.open} does, so this caller's global branch is ordinary rather than
 * indirect.
 *
 * <p><b>This home is a memory tier, not a conversation.</b> The global <em>conversation</em> is a
 * removed concept — {@code docs/personal-space.md} records that writing or resuming one is refused
 * — but the global memory tier is first class and is what the global archive is. So the global
 * branch here is not a holdover from that removal and must not be deleted as one. What it does
 * produce is a global-home conversation row, because {@code Compaction.logFor(Origin.MEMORY, ..)}
 * reaches {@code ConversationStore.logInternal}, which resolves a scope only for {@code
 * Origin.SUBMISSION} and {@code Origin.EVENT}.
 *
 * <h2>Stubbing</h2>
 *
 * <p>The log is {@link Transcript#NONE}, which is what {@code Compaction.logFor} degrades to for a
 * home it will not log; it is also what makes the binding visible for what it is (see the latitude
 * test below). The SYSTEM grant is deliberately <b>not</b> narrowed by scope: the question is which
 * scope admission chooses, not whether a grant refuses one. Repositories are mocked, per the
 * database test policy.
 */
class DigestSystemScopeTest {

  private static final String PROJECT = "atlas";

  /** The SYSTEM account's own live intrinsic grant; the only authority an operation carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope the control operation must reach through the very same machinery. */
  private static final AuthorityScope ATLAS =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /**
   * The kind of scope an operation over somebody's own tier would have to be admitted against to be
   * bounded by the account behind it. Stubbed only so the negative assertion names something real.
   */
  private static final AuthorityScope SOMEONE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  /**
   * CONFIRMED DEFECT, pinned as it stands.
   *
   * <p>A global digest pass or global navigation is admitted against the built-in {@code
   * GLOBAL_KNOWLEDGE} scope — the whole global knowledge tier — rather than against any scope the
   * operation itself names. {@code DigestModel.operation} calls the five-argument {@code admit},
   * which has no {@code selected} scope to prefer, so {@link BoundSystemOperations} reads the
   * global home as the global knowledge tier and admits the operation with {@code READ}, {@code
   * WRITE}, {@code CONVERSATION_CREATE} and {@code RUN_MODEL} there.
   *
   * <h2>A wrong ceiling, and unusable — but less tightly bound than a conversation fold</h2>
   *
   * <p>The operation cannot spend the scope as reach. The definition is built in code with {@code
   * List.of()} for tools, callees and scopes, so {@code SystemModelTasks.requireModelOnly} admits
   * it and {@code BoundSystemOperations.claimed(ChatRequest, Budget.Claim)} refuses any call whose
   * {@code request.tools()} is non-empty or whose model differs. {@code DigestModel} never mentions
   * {@code ScopeId} or {@code AuthorityScope}, and what the operation's callers write — {@code
   * DigestStore.refresh}, {@code PassageIndex.capture}, {@code OperationEmbeddings.query} — takes a
   * {@link Home} and never a scope. So, as with compaction, the only thing the scope decides is
   * whether this work's own model calls proceed.
   *
   * <p><b>The resemblance stops at the prompt.</b> Compaction and the two write-path judges reach
   * {@code SystemOperations.authorize}, which passes {@code request.messages().toString()} as the
   * binding and so pins one prompt by hash for one call. This caller passes the trace's
   * conversation id instead, and {@code claimed} checks only the tool list and the model — so one
   * admission covers as many calls, with as many different prompts, as the operation's budget
   * allows. {@code one_admission_covers_every_prompt_the_operation_asks} executes that. It is still
   * not reach: the calls are tool-less text transformations over material the operation's own Java
   * walk selected.
   *
   * <p><b>When this is fixed, invert the assertions</b> — a global operation must be admitted
   * against a scope it names itself, and the six-argument {@code admit} must be the overload
   * reached.
   */
  @Test
  void a_global_operation_is_admitted_against_global_knowledge() {
    Fixture fixture = new Fixture();
    fixture.digest(Home.global());

    // The omission itself: the overload that cannot be told its scope is the one called.
    Home home = fixture.capturedHome();
    assertTrue(home.isGlobal(), "the global tier is the home a global pass is opened on");
    assertEquals(Home.global(), home);
    verify(fixture.system, never())
        .admit(any(), any(), any(ScopeId.class), any(), anyString(), any(Runnable.class));

    // And the scope admission actually retained, which every later require() re-checks.
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE,
        fixture.capturedScope(),
        "a global memory operation is admitted against GLOBAL_KNOWLEDGE");
    assertNotEquals(SOMEONE_PERSONAL.id(), fixture.capturedScope());
    assertNotEquals(ATLAS.id(), fixture.capturedScope());
  }

  /**
   * The control, through the identical machinery: the same {@code DigestModel.operation}, the same
   * five-argument {@code admit} and the same {@link BoundSystemOperations}. An operation over a
   * named project carries that project in its home, so admission resolves the project's own scope —
   * which proves the test above exercised the live path rather than a stub, and that the
   * five-argument overload is not simply broken for every caller.
   *
   * <p>So the scope an operation is admitted against <b>does</b> depend on the tier it runs over,
   * and the global tier is the only home that loses its bound.
   */
  @Test
  void a_project_operation_is_admitted_against_that_project_scope() {
    Fixture fixture = new Fixture();
    fixture.digest(Home.of(PROJECT));

    Home home = fixture.capturedHome();
    assertEquals(Home.of(PROJECT), home);
    assertFalse(home.isGlobal());

    assertEquals(
        ATLAS.id(),
        fixture.capturedScope(),
        "a project memory operation reaches that project's scope");
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, fixture.capturedScope());
  }

  /**
   * How much latitude the admission actually leaves, pinned because it is more than the write-path
   * judges have and less than a tool-using agent's.
   *
   * <p>One admission, two calls, two different instructions and two different bodies of evidence —
   * and both are authorized. The binding {@code DigestModel} retains is {@code
   * String.valueOf(trace.conversationId())}, not the prompt, so the prompt hash {@code
   * claimed(ChatRequest, ..)} computes is the one it was just handed rather than one pinned at
   * admission. What still holds is the tool list and the model: every request carries an empty tool
   * list, so the ceiling cannot be spent on a tool call.
   *
   * <p>This is recorded as the shape of the bound, not as a second defect. The prompts are composed
   * in Java by {@code Navigator} and {@code Digester} from material their own walk selected; the
   * model never chooses what to read.
   */
  @Test
  void one_admission_covers_every_prompt_the_operation_asks() {
    Fixture fixture = new Fixture();
    Budget budget = Budget.of(2);
    try (var operation = fixture.model.operation("memory_digester", Home.global(), budget)) {
      operation.call(
          "memory_digester", "summarise this", "the first branch", Home.global(), budget);
      operation.call("memory_digester", "pick a child", "the second branch", Home.global(), budget);

      // Inside the operation, because an authorization's own validity check is the
      // operation's: once close() has run, every require() fails for that reason
      // instead of for the scope's.
      for (ChatRequest request : fixture.asked) {
        assertNotNull(request.authorization(), "each call is authorized by the admitted work");
        assertTrue(request.tools().isEmpty(), "a system memory operation is offered no tool");
        assertDoesNotThrow(() -> request.authorization().requireRequest(request));
      }
      assertThrows(
          RuntimeException.class,
          () ->
              fixture
                  .asked
                  .getFirst()
                  .authorization()
                  .requireRequest(
                      ChatRequest.of("fast", List.of(ChatMessage.user("something else")))),
          "a claim, once made, still pins the prompt it was made for");
    }

    verify(fixture.system, times(1))
        .admit(
            eq(SystemOperations.Purpose.MEMORY_DIGEST),
            any(Home.class),
            any(AgentDefinition.class),
            anyString(),
            any(Runnable.class));
    verify(fixture.evidence, times(1)).retain(any());
    assertEquals(2, fixture.asked.size(), "both calls reached the dispatcher");
    assertNotEquals(
        fixture.asked.get(0).messages().toString(),
        fixture.asked.get(1).messages().toString(),
        "the two calls asked different things under the one admission");
  }

  // --- the fixture ----------------------------------------------------------

  private static final class Fixture {
    final SystemOperationRecords evidence = mock(SystemOperationRecords.class);
    final LlmDispatcher models = mock(LlmDispatcher.class);
    final Compaction logs = mock(Compaction.class);
    final SystemOperations system;
    final DigestModel model;

    /** Every request the dispatcher was handed, in order, after authorization. */
    final List<ChatRequest> asked = new ArrayList<>();

    Fixture() {
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

      BoundSystemOperations bound =
          new BoundSystemOperations(
              identities,
              grants,
              authority,
              evidence,
              new OperationInference(
                  mock(OperationAuthority.class), mock(InferenceAdmissions.class)));
      // A mock of the interface delegating to the real one, so a captor can see which
      // overload was reached. The admission decision itself is the production one.
      system = mock(SystemOperations.class, withSettings().defaultAnswer(delegatesTo(bound)));

      when(logs.logFor(any(), any(), any(), isNull(), any())).thenReturn(Transcript.NONE);
      when(models.complete(any()))
          .thenAnswer(
              call -> {
                ChatRequest request = call.getArgument(0);
                request.authorization().requireRequest(request);
                asked.add(request);
                return new Completion("an answer", "stop", TokenUsage.UNKNOWN, List.of());
              });

      model = new DigestModel(models, () -> logs, new MemoryProperties(), () -> "fast", system);
    }

    /** One operation over one tier, with one call in it. */
    void digest(Home home) {
      Budget budget = Budget.of(1);
      try (var operation = model.operation("memory_digester", home, budget)) {
        operation.call("memory_digester", "summarise this", "the evidence", home, budget);
      }
      assertEquals(1, asked.size(), "the operation must have called to have admitted anything");
    }

    /** The home the five-argument {@code admit} was actually handed. */
    Home capturedHome() {
      ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
      verify(system)
          .admit(
              eq(SystemOperations.Purpose.MEMORY_DIGEST),
              home.capture(),
              any(AgentDefinition.class),
              anyString(),
              any(Runnable.class));
      return home.getValue();
    }

    /** The scope identifier the real admission retained for that SYSTEM operation. */
    ScopeId capturedScope() {
      ArgumentCaptor<SystemOperationRecords.Envelope> envelope =
          ArgumentCaptor.forClass(SystemOperationRecords.Envelope.class);
      verify(evidence).retain(envelope.capture());
      assertEquals(SystemOperations.Purpose.MEMORY_DIGEST, envelope.getValue().purpose());
      return envelope.getValue().scope();
    }
  }
}
