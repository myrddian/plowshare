package io.aeyer.plowshare.server.agents.scribe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.MemoryProposal;
import io.aeyer.plowshare.protocol.MemoryState;
import io.aeyer.plowshare.protocol.Provenance;
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
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.llm.dispatch.ChatRequest;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which authority scope a scribe judgement is actually admitted against, and whether the work that
 * scope admits has any latitude to spend it.
 *
 * <h2>What is executed here, and why it is not an inference</h2>
 *
 * <p>{@code Scribe.authorize} hands the destination {@link Home} to {@link
 * SystemOperations#authorize}, a {@code default} whose body is the five-argument {@link
 * SystemOperations#admit(SystemOperations.Purpose, Home, AgentDefinition, String, Runnable)}. That
 * overload carries no {@code selected} scope, so {@link BoundSystemOperations} resolves the missing
 * argument from the home alone; {@code SCRIBE} is not one of the deployment purposes that
 * short-circuit to {@code ADMINISTRATION}.
 *
 * <p>None of that is asserted from the source. The real {@link BoundSystemOperations} runs over
 * mocked repositories, a {@link Recording} wrapper notes which {@code admit} overload was reached,
 * a mock of {@link SystemOperations} delegating to it lets an {@link ArgumentCaptor} read the home
 * {@code authorize} was handed, and the scope is read off the {@link
 * SystemOperationRecords.Envelope} admission retains — the scope every later {@code require} is
 * re-checked against.
 *
 * <h2>Which homes a judgement actually sees in production</h2>
 *
 * <p>Both tiers are reachable, and the global one directly from a person's request: {@code
 * MemoryController.write} resolves the body's {@code project} through {@code RequestedHome.in},
 * which returns {@link Home#global()} for an absent project and hands it straight to {@code
 * Scribe.judge}. {@code MemoryTools} (the agent-facing {@code memory_write}) and {@code
 * Learner.file} reach the same method with their own homes. So unlike a conversation fold, whose
 * global branch is only reachable through machine-origin logs, this caller's global branch is the
 * ordinary shape of a write that named no project.
 *
 * <p><b>This home is a memory tier, not a conversation.</b> The global <em>conversation</em> is a
 * removed concept — {@code docs/personal-space.md} records that writing or resuming one is refused
 * — but the global memory tier is first class and is what the global archive is. The global branch
 * here is therefore not a holdover from that removal and must not be deleted as one.
 *
 * <h2>Stubbing</h2>
 *
 * <p>The archive is mocked to hold one near candidate, because an empty candidate list short
 * circuits before any model call and so before the authorization is exercised. The SYSTEM grant is
 * deliberately <b>not</b> narrowed by scope: the question is which scope admission chooses, not
 * whether a grant refuses one. Repositories are mocked, per the database test policy; nothing here
 * needs PostgreSQL, because the question is which scope identifier admission settles on.
 */
class ScribeSystemScopeTest {

  private static final String PROJECT = "payments";
  private static final Instant FORMED_AT = Instant.parse("2026-09-05T09:00:00Z");

  private static final MemoryProposal PROPOSAL =
      new MemoryProposal(
          "The retry budget is 4 attempts",
          "Calling the payments API",
          "Four attempts since the timeout change.",
          "claude",
          "during the mTLS migration");

  /** The SYSTEM account's own live intrinsic grant; the only authority a judgement carries. */
  private static final UUID GRANT = UUID.randomUUID();

  private static final GrantRecipient SYSTEM_RECIPIENT =
      GrantRecipient.account(BuiltInIdentities.SYSTEM);

  /** The built-in account knowledge scope the shorter overload falls back to for a global home. */
  private static final AuthorityScope GLOBAL_KNOWLEDGE =
      new AuthorityScope.AccountKnowledge(
          BuiltInIdentities.GLOBAL_KNOWLEDGE, BuiltInIdentities.GLOBAL);

  /** The project scope the control judgement must reach through the very same machinery. */
  private static final AuthorityScope PAYMENTS_SCOPE =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 11);

  /**
   * The kind of scope a write a person made into their own tier would have to be admitted against
   * to be bounded by the account behind it. Stubbed only so the negative assertion names something
   * real.
   */
  private static final AuthorityScope SOMEONE_PERSONAL =
      new AuthorityScope.Personal(
          new ScopeId(UUID.randomUUID()), new AccountId(UUID.randomUUID()), 7);

  /**
   * CONFIRMED DEFECT, pinned as it stands.
   *
   * <p>A write that named no project — the ordinary global write, straight off {@code POST
   * /v1/memories} — has its scribe judgement admitted against the built-in {@code GLOBAL_KNOWLEDGE}
   * scope, the whole global knowledge tier, rather than against any scope the write itself names.
   * {@code Scribe} reaches {@link SystemOperations#authorize}, whose body is the five-argument
   * {@code admit}; that overload has no {@code selected} scope to prefer, so {@link
   * BoundSystemOperations} reads the global home as the global knowledge tier and admits the
   * judgement with {@code READ}, {@code WRITE}, {@code CONVERSATION_CREATE} and {@code RUN_MODEL}
   * there.
   *
   * <h2>A wrong ceiling, and unusable — the same shape as a conversation fold</h2>
   *
   * <p>The judgement has no latitude with which to spend the scope, and that is enforced rather
   * than intended. {@code the_judgement_is_bound_to_one_toolless_pinned_prompt} executes the fence:
   * {@code BoundSystemOperations.claimed(ChatRequest, Budget.Claim)} refuses the call unless {@code
   * request.tools()} is empty and the model matches, and because {@code authorize} passes {@code
   * request.messages().toString()} as the binding, the prompt is pinned by hash as well. {@code
   * Scribe} never mentions {@code ScopeId} or {@code AuthorityScope}, and the verdict it returns is
   * applied by its caller through {@code Archive.applyVerdict(.., Home, ..)}, which takes a home
   * and no scope. So the scope gates one fixed prompt-to-verdict transformation's own model call
   * and nothing else.
   *
   * <p>It is still the wrong scope, reached by the five-argument overload having nowhere to put
   * one, and the bound holds only because a second, unrelated mechanism holds. A conformance and
   * defence-in-depth defect, not live over-granted reach.
   *
   * <p><b>When this is fixed, invert the assertions</b> — a global write's judgement must be
   * admitted against a scope the write itself names, and the six-argument {@code admit} must be the
   * overload reached.
   */
  @Test
  void a_global_write_is_judged_against_global_knowledge() {
    Fixture fixture = new Fixture();
    fixture.judge(Home.global());

    // The omission itself: the overload that cannot be told its scope is the one called.
    assertEquals(
        List.of(Home.global()),
        fixture.recording.withoutAScope,
        "the judgement reached the five-argument admit, which carries no selected scope");
    assertTrue(fixture.recording.withAScope.isEmpty(), "no caller supplied a scope");

    Home home = fixture.capturedHome();
    assertTrue(home.isGlobal(), "a write that named no project is judged in the global tier");
    assertEquals(Home.global(), home);

    // And the scope admission actually retained, which every later require() re-checks.
    assertEquals(
        BuiltInIdentities.GLOBAL_KNOWLEDGE,
        fixture.capturedScope(),
        "a global write's judgement is admitted against GLOBAL_KNOWLEDGE");
    assertNotEquals(SOMEONE_PERSONAL.id(), fixture.capturedScope());
    assertNotEquals(PAYMENTS_SCOPE.id(), fixture.capturedScope());
  }

  /**
   * The control, through the identical machinery: the same {@code Scribe.authorize}, the same
   * {@code authorize} default, the same five-argument {@code admit} and the same {@link
   * BoundSystemOperations}. A write into a named project carries that project in its home, so
   * admission resolves the project's own scope — which proves the test above exercised the live
   * path rather than a stub, and that the five-argument overload is not simply broken for every
   * caller.
   *
   * <p>So the scope a judgement is admitted against <b>does</b> depend on the write's home, and the
   * global tier is the only home that loses its bound.
   */
  @Test
  void a_project_write_is_judged_against_that_project_scope() {
    Fixture fixture = new Fixture();
    fixture.judge(Home.of(PROJECT));

    assertEquals(List.of(Home.of(PROJECT)), fixture.recording.withoutAScope);
    assertTrue(fixture.recording.withAScope.isEmpty());

    Home home = fixture.capturedHome();
    assertEquals(Home.of(PROJECT), home);
    assertFalse(home.isGlobal());

    assertEquals(
        PAYMENTS_SCOPE.id(),
        fixture.capturedScope(),
        "a project write's judgement reaches that project's scope");
    assertNotEquals(BuiltInIdentities.GLOBAL_KNOWLEDGE, fixture.capturedScope());
  }

  /**
   * Why the wrong ceiling above is unusable rather than live reach: the admitted call is one
   * tool-less model call against a prompt pinned at admission.
   *
   * <p>Executed, not read. The dispatcher re-presents the exact request to {@code
   * CallAuthorization.requireRequest}, which is what the production dispatcher does, and it passes
   * — so the fence is live on this path. Then the same authorization is re-offered a request whose
   * messages differ, and it is refused. That is the whole of the latitude {@code SCRIBE} has: an
   * empty tool list, one prompt pinned by hash, and no scope handed to any repository.
   */
  @Test
  void the_judgement_is_bound_to_one_toolless_pinned_prompt() {
    Fixture fixture = new Fixture();
    fixture.judge(Home.global());

    ChatRequest asked = fixture.asked;
    assertNotNull(asked.authorization(), "the production path authorizes the request");
    assertTrue(asked.tools().isEmpty(), "the scribe is offered no tool");
    assertDoesNotThrow(() -> asked.authorization().requireRequest(asked));

    assertThrows(
        RuntimeException.class,
        () ->
            asked
                .authorization()
                .requireRequest(
                    ChatRequest.of(
                        asked.specifier(),
                        List.of(
                            io.aeyer.plowshare.server.llm.dispatch.ChatMessage.user(
                                "judge something else instead")))),
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
    final Archive archive = mock(Archive.class);
    final Recording recording;
    final SystemOperations system;

    /** The request the dispatcher was actually handed, after authorization. */
    ChatRequest asked;

    Fixture() {
      IdentityRepository identities = mock(IdentityRepository.class);
      ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
      ScopedAuthority authority = mock(ScopedAuthority.class);

      // Every scope admission could settle on, resolvable both ways: by identifier,
      // which the validity re-check reads, and by project, which the home reads.
      when(identities.scope(BuiltInIdentities.GLOBAL_KNOWLEDGE)).thenReturn(GLOBAL_KNOWLEDGE);
      when(identities.scope(PAYMENTS_SCOPE.id())).thenReturn(PAYMENTS_SCOPE);
      when(identities.scope(SOMEONE_PERSONAL.id())).thenReturn(SOMEONE_PERSONAL);
      when(identities.project(PROJECT)).thenReturn(PAYMENTS_SCOPE);

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

      when(archive.embedding(anyString()))
          .thenAnswer(call -> new Archive.Precomputed(new float[] {1.0f}, call.getArgument(0)));
      when(models.complete(any()))
          .thenAnswer(
              call -> {
                asked = call.getArgument(0);
                asked.authorization().requireRequest(asked);
                return new Completion(
                    "{\"verdict\":\"new\",\"target\":null,\"reason\":\"fixture\"}",
                    "stop",
                    TokenUsage.UNKNOWN,
                    List.of());
              });
    }

    void judge(Home home) {
      // One candidate in the destination home: an empty list short circuits before
      // any model call, so the authorization would never be exercised.
      when(archive.recall(any(Archive.Precomputed.class), eq(home), anyInt()))
          .thenReturn(new Archive.Recall(List.of(held(home)), 0));
      Scribe scribe =
          new Scribe(
              models, archive, () -> new AgentRegistry(Map.of(Scribe.AGENT, definition())), system);
      assertNotNull(scribe.judge(PROPOSAL, home), "the judgement must have run to have admitted");
    }

    /** The home the five-argument {@code admit} was reached with, through {@code authorize}. */
    Home capturedHome() {
      ArgumentCaptor<Home> home = ArgumentCaptor.forClass(Home.class);
      verify(system)
          .authorize(
              eq(SystemOperations.Purpose.SCRIBE),
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
      assertEquals(SystemOperations.Purpose.SCRIBE, envelope.getValue().purpose());
      return envelope.getValue().scope();
    }

    private static Memory held(Home home) {
      return new Memory(
          "mem_000001",
          "retry attempts",
          "Calling the payments API",
          new Provenance(FORMED_AT, "claude", "an earlier session"),
          MemoryState.ACTIVE,
          false,
          0,
          null,
          "The body of mem_000001.",
          null,
          null,
          null,
          home);
    }

    /** The scribe as it is shipped: no tools, no callees, one call, the {@code fast} class. */
    private static AgentDefinition definition() {
      return new AgentDefinition(
          Scribe.AGENT,
          "judges the shape of a write",
          "fast",
          List.of(),
          List.of(),
          List.of(),
          1,
          1,
          "You decide whether a write is new.");
    }
  }
}
