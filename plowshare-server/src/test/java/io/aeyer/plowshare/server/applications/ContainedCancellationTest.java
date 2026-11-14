package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stopping a child's work: the explicit cancellation, and the revocation nobody asks for.
 *
 * <p>Three things must be able to stop a contained run and this class holds two of them. A
 * container cancelling its child is a call with a caller, and the tests below drive it against a
 * <em>real</em> {@code JobStore} over a real {@code JobRuntime}, because a mocked stop would prove
 * only that this service called a method. Revocation has no caller at all: nothing asks, and the
 * run has to notice for itself that the admission it holds no longer resolves. That is asserted
 * against the real {@link PinnedOperationAuthority} and the real turn loop, because the mechanism
 * being claimed <em>is</em> that loop's recheck and a mock of either would assert nothing.
 *
 * <p>The third way — a destruction stopping every descendant before collecting its source — is
 * ordering rather than reachability, and is asserted in {@code ContainedDestructionTest} beside the
 * cascade whose order it has to share.
 *
 * <p>Every repository here is a mocked interface or a small in-memory fake, per the database test
 * policy: nothing asserted is PostgreSQL behaviour.
 */
class ContainedCancellationTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  IdentityRepository identities = mock(IdentityRepository.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  Runs runs = mock(Runs.class);
  Allowances allowances = new Allowances();

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);
  AuthorityScope.Project parentScope =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 7);
  AllowanceId parentBudget = new AllowanceId(UUID.randomUUID());
  OperationAuthority.Admission parentAdmission =
      new OperationAuthority.Admission(
          UUID.randomUUID(),
          "@service/parent",
          account,
          parentScope.id(),
          Home.of("parent"),
          recipient,
          List.of(
              new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
              new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID())));

  UUID childId = UUID.randomUUID();
  ScopeId childScope = new ScopeId(UUID.randomUUID());
  String childProject = "contained:" + UUID.randomUUID();
  AllowanceId childAllowance = new AllowanceId(UUID.randomUUID());
  UUID childRevision = UUID.randomUUID();

  /** The grandchild beneath the child, so "its descendants" is a set with something in it. */
  UUID grandchildId = UUID.randomUUID();

  ScopeId grandchildScope = new ScopeId(UUID.randomUUID());
  AllowanceId grandchildAllowance = new AllowanceId(UUID.randomUUID());

  Path root;
  SourceContainedApplications service;

  /** Records which conversations were asked to stop, in order. */
  static final class Stops implements CancellableRuns {
    final List<String> asked = new ArrayList<>();
    final Map<String, Integer> running = new LinkedHashMap<>();

    @Override
    public int cancelIn(String conversation) {
      Objects.requireNonNull(conversation, "conversation");
      asked.add(conversation);
      return running.getOrDefault(conversation, 0);
    }
  }

  Stops stops = new Stops();

  /** Ceilings and spends are real, as in the sibling contained tests. */
  static final class Allowances implements AllowanceRepository {
    final Map<AllowanceId, AllowanceSnapshot> held = new LinkedHashMap<>();

    @Override
    public void retain(AllowanceSnapshot selected) {
      held.put(selected.id(), selected);
    }

    @Override
    public Optional<AllowanceSnapshot> find(AllowanceId id) {
      return Optional.ofNullable(held.get(id));
    }

    @Override
    public Optional<Budget.Claim> claim(AllowanceId id, int reserve, Integer expectedSpent) {
      return Optional.empty();
    }

    @Override
    public void accept(Budget.Claim claim) {}

    @Override
    public void ceiling(AllowanceId id, Integer total) {
      var snapshot = held.get(id);
      if (snapshot == null) throw new IllegalStateException("Missing allowance");
      held.put(id, new AllowanceSnapshot(id, total, snapshot.spent()));
    }

    @Override
    public void increase(AllowanceId id, int total, int observedSpent) {
      ceiling(id, total);
    }

    @Override
    public void add(AllowanceId id, int calls) {
      var snapshot = held.get(id);
      ceiling(id, snapshot.ceiling() == null ? null : snapshot.ceiling() + calls);
    }
  }

  static final class Work implements UnitOfWork {
    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> body) {
      return body.get();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    allowances.retain(new AllowanceSnapshot(parentBudget, 100, 10));
    allowances.retain(new AllowanceSnapshot(childAllowance, 5, 0));
    allowances.retain(new AllowanceSnapshot(grandchildAllowance, 5, 0));
    when(authority.conversation("c-1"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-1", "worker", parentAdmission, null, parentBudget, "0".repeat(64))));
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id())));
    when(records.find(grandchildId)).thenReturn(Optional.of(grandchild()));
    // Deepest first, which is the order both the cascade and the stop run in.
    when(records.subtree(childScope)).thenReturn(List.of(grandchild(), child(parentScope.id())));
    when(records.subtree(grandchildScope)).thenReturn(List.of(grandchild()));
    when(authority.conversationsIn(childScope)).thenReturn(List.of(envelope("cnv_CHILD")));
    when(authority.conversationsIn(grandchildScope))
        .thenReturn(List.of(envelope("cnv_GRANDCHILD")));
    service = service(stops);
  }

  SourceContainedApplications service(CancellableRuns jobs) {
    return new SourceContainedApplications(
        records,
        projects,
        validator,
        authority,
        identities,
        principals,
        allowances,
        new Work(),
        new ContainmentConfig(root.toString(), 3, 4, null),
        new InProcessContainedStaging(),
        runs,
        jobs);
  }

  OperationAuthority.Conversation envelope(String id) {
    return new OperationAuthority.Conversation(
        id, "worker", parentAdmission, null, childAllowance, "0".repeat(64));
  }

  ContainedApplicationRepository.Retained child(ScopeId container) {
    return new ContainedApplicationRepository.Retained(
        childId,
        childScope,
        123L,
        childProject,
        container,
        UUID.randomUUID(),
        "a".repeat(64),
        childRevision,
        childAllowance,
        5,
        true);
  }

  ContainedApplicationRepository.Retained grandchild() {
    return new ContainedApplicationRepository.Retained(
        grandchildId,
        grandchildScope,
        124L,
        "contained:" + UUID.randomUUID(),
        childScope,
        UUID.randomUUID(),
        "b".repeat(64),
        UUID.randomUUID(),
        grandchildAllowance,
        5,
        true);
  }

  // ---------------------------------------------------------------- the explicit cancellation

  /**
   * The real thing: a real {@code JobStore} runs the real turn loop in a job whose conversation is
   * the child's own, the container cancels, and the job reaches a cancelled terminal state without
   * spending a second model call.
   *
   * <p>Only the dispatcher and the transcript are stubbed. The cancellation itself travels the
   * whole way — {@code cancel} resolves the child's retained conversation envelopes, asks the store
   * to stop the runs in them, and the store's flag is honoured at the turn loop's next boundary. A
   * mocked {@code CancellableRuns} here would have proved only that this service calls a method.
   *
   * <p><strong>Why the job is submitted through {@code JobStore} and not through {@code
   * Turn}.</strong> {@code Runs.start}'s contained branch speaks into the conversation {@code
   * openContained} returns, and that row carries {@code Origin.CONTAINED} — which {@code
   * Turn.requireSpeakable} refuses by design, because a contained run's log is not a conversation
   * anybody speaks into. That refusal stands in front of {@code application.child.run}'s first turn
   * today and is recorded as a gate in the task report rather than widened here: adding {@code
   * CONTAINED} to the speakable set would let {@code agent.run} post into a child's log, which is
   * exactly what that guard's own javadoc forbids. {@code submitAdmitted} is the store door that
   * carries an origin directly, so the run below is the run a contained start will be once that
   * gate is opened by whoever owns it, and the cancellation asserted here is unaffected either way.
   */
  @Test
  void aContainerCancelsItsChildsRun() throws Exception {
    var dispatcher = mock(LlmDispatcher.class);
    var transcript = mock(Compaction.TurnTranscript.class);
    var budget = Budget.of(5);
    Home home = Home.of(childProject);
    var definition =
        new AgentDefinition(
            "worker",
            "worker",
            "fast",
            List.of("get_date"),
            List.of(),
            List.of(),
            4,
            8,
            "Answer clearly",
            true,
            false);
    when(transcript.conversationId()).thenReturn("cnv_CHILD");
    when(transcript.before()).thenReturn(List.of());
    when(transcript.speaker()).thenReturn(Speaker.contained(childId));
    when(transcript.usage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    when(transcript.parentUsage())
        .thenReturn(io.aeyer.plowshare.server.llm.accounting.UsageAttribution.LEGACY);
    AgentTool date = mock(AgentTool.class);
    when(date.schema()).thenReturn(ToolSchema.from("get_date", "date", Map.of("type", "object")));
    when(date.run("{}", home)).thenReturn("today");

    var modelCalls = new AtomicInteger();
    CountDownLatch dispatched = new CountDownLatch(1);
    CountDownLatch cancelled = new CountDownLatch(1);
    // One model call, held until the container has cancelled, answering with a tool call so the
    // loop has a next boundary to reach. The second call never happens, which is the assertion.
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              modelCalls.incrementAndGet();
              dispatched.countDown();
              assertTrue(cancelled.await(5, TimeUnit.SECONDS), "the container cancelled");
              return new Completion(
                  null,
                  "tool_calls",
                  TokenUsage.UNKNOWN,
                  List.of(new ToolCall("one", "get_date", "{}")));
            });

    var ended = new CountDownLatch(1);
    try (JobStore jobs = new JobStore(new JobRuntime(dispatcher, List.of(date)))) {
      String job =
          jobs.submitAdmitted(
              definition,
              "Summarise the notes",
              home,
              budget,
              transcript,
              Origin.CONTAINED,
              // The store tells this before it publishes the outcome; the poll below waits for the
              // publication itself, as AdmittedTurnTest's own helper does.
              outcome -> ended.countDown(),
              TurnCap.of(8),
              new RunAdmission(() -> {}, id -> {}, () -> {}, "@service/parent", (at, refs) -> {}));
      assertTrue(dispatched.await(5, TimeUnit.SECONDS), "the run reached its first model call");
      assertEquals(Job.State.RUNNING, jobs.get(job).state());
      assertEquals(
          "cnv_CHILD",
          jobs.get(job).conversation(),
          "the job is reachable by the conversation a container can name");

      // The cancellation under test, through the service and with a real store behind it.
      service(jobs).cancel("@service/parent", "c-1", childId);

      assertTrue(jobs.get(job).cancelRequested(), "the store was the one asked to stop this run");
      cancelled.countDown();
      assertTrue(ended.await(5, TimeUnit.SECONDS), "the run settled");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (jobs.get(job).state() != Job.State.DONE && System.nanoTime() < deadline)
        TimeUnit.MILLISECONDS.sleep(10);

      assertEquals(Job.State.DONE, jobs.get(job).state(), "the job published its outcome");
      assertEquals(
          Outcome.Ending.CANCELLED,
          jobs.get(job).outcome().orElseThrow().ending(),
          "a cancelled terminal state, not merely a flag nobody read");
      assertEquals(1, modelCalls.get(), "no further turn is spent after the cancellation");
      assertEquals(1, budget.spent(), "and no further call is charged to the child's allowance");
    } finally {
      cancelled.countDown();
    }
  }

  /**
   * Cancelling nothing is not an error, which is the idiom {@code ContainedPrincipals.revoke}
   * already sets: a caller holding a correct scope that happens to hold no live run must not have
   * to tell that apart from a refusal.
   */
  @Test
  void cancellingNothingIsNotAnError() {
    assertDoesNotThrow(() -> service.cancel("@service/parent", "c-1", childId));
    // Not vacuous: the subtree really was walked and every conversation in it really was asked.
    // Without this the assertion above would also pass for a cancel that did nothing at all.
    assertEquals(List.of("cnv_GRANDCHILD", "cnv_CHILD"), stops.asked);
    // And twice is the same answer, because a stop is a request and not a state change here.
    assertDoesNotThrow(() -> service.cancel("@service/parent", "c-1", childId));
    assertEquals(
        List.of("cnv_GRANDCHILD", "cnv_CHILD", "cnv_GRANDCHILD", "cnv_CHILD"), stops.asked);
  }

  /**
   * Container-only, exactly as {@code run} and {@code ceiling} are, and deliberately not the
   * ancestor gate the two reads use: stopping a grandchild's work is the intermediate container's
   * call, because the allowance being spent is the one that container lent.
   */
  @Test
  void aGrandparentCannotCancel() {
    var intermediate = new ScopeId(UUID.randomUUID());
    when(records.find(childId)).thenReturn(Optional.of(child(intermediate)));
    when(records.ofScope(intermediate)).thenReturn(Optional.of(child(parentScope.id())));

    assertEquals(
        "This Application does not contain that Application",
        assertThrows(CallerFault.class, () -> service.cancel("@service/parent", "c-1", childId))
            .getMessage());
    assertEquals(List.of(), stops.asked, "nothing was asked to stop");
    // Control, and it is what makes this a test of the rule rather than of a broken chain: the
    // very same caller and child are admitted by trajectory's ancestor gate, so the chain really
    // does reach this grandparent and cancel applies the stricter rule on purpose.
    assertEquals(
        1,
        service.trajectory("@service/parent", "c-1", childId).size(),
        "the ancestor gate admits the same caller for the same child");
  }

  @Test
  void aSiblingCannotCancel() {
    var sibling = new ScopeId(UUID.randomUUID());
    when(records.find(childId)).thenReturn(Optional.of(child(sibling)));
    when(records.ofScope(sibling)).thenReturn(Optional.empty());

    assertThrows(CallerFault.class, () -> service.cancel("@service/parent", "c-1", childId));
    assertEquals(List.of(), stops.asked);
    // Same-call positive control: the identical call, differing only in whose container the stored
    // row names, stops the subtree.
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id())));
    assertDoesNotThrow(() -> service.cancel("@service/parent", "c-1", childId));
    assertEquals(List.of("cnv_GRANDCHILD", "cnv_CHILD"), stops.asked);
  }

  /**
   * Retained evidence is not a live permission, here as everywhere else on this path: a container
   * whose own authority has since been revoked stops nothing by asking.
   */
  @Test
  void aCancellationRefusesWhenTheContainerAuthorityIsNoLongerLive() {
    doThrow(new CallerFault("denied")).when(authority).require(parentAdmission);

    assertThrows(CallerFault.class, () -> service.cancel("@service/parent", "c-1", childId));
    assertEquals(List.of(), stops.asked);
    verify(records, never()).find(any());
    // Control: with the admission live again, the same call stops the same subtree.
    doNothing().when(authority).require(parentAdmission);
    assertDoesNotThrow(() -> service.cancel("@service/parent", "c-1", childId));
    assertEquals(List.of("cnv_GRANDCHILD", "cnv_CHILD"), stops.asked);
  }

  /**
   * Cancellation stops work; it does not erase it. {@code Origin.CONTAINED} carries {@code
   * DELEGATION}'s retention for exactly this reason, and a container must still be able to read
   * back what it stopped.
   */
  @Test
  void cancellationLeavesTheRetainedEnvelopesReadable() {
    // Non-empty first: an absence assertion over an empty set proves nothing.
    assertEquals(1, service.trajectory("@service/parent", "c-1", childId).size());

    service.cancel("@service/parent", "c-1", childId);

    var steps = service.trajectory("@service/parent", "c-1", childId);
    assertEquals(1, steps.size(), "history is not what a cancellation collects");
    assertEquals("cnv_CHILD", steps.getFirst().conversation());
    assertEquals(childAllowance.value(), steps.getFirst().budget());
  }

  // ---------------------------------------------------------------- the revocation nobody asks for

  /**
   * The one with no caller. Nothing asks for this: the container's grant is revoked somewhere else
   * entirely, and the run already in flight has to notice for itself that the admission it holds no
   * longer resolves.
   *
   * <p><strong>Where it is noticed, and it is not the turn loop's top.</strong> Every model call
   * carries the operation's own {@code CallAuthorization} — {@code () ->
   * operations.require(operation)} — and {@code JobRuntime.streamed} invokes it <em>after the
   * completion comes back and before that completion's output is published or any further effect is
   * started</em>, which is what the comment at that line says it is for. That is the recheck {@code
   * OperationAuthority.Conversation} documents as the caller's duty, it already exists, and nothing
   * here polls, sweeps or schedules. The turn loop's own {@code operations.require(operation)} at
   * the top of each pass and after each tool result are later boundaries; whichever a run reaches
   * first after the grant is withdrawn is the one that fires, and for a revocation landing during
   * inference that is the post-inference check.
   *
   * <p>The refusal then <em>escapes</em> rather than being answered: the loop records it as {@code
   * Ending.UNAVAILABLE} carrying the fault as detail, so nothing the model produced is published,
   * and the post-settle {@code operations.require(operation)} rethrows it. Removing that
   * post-settle recheck makes this test fail with nothing thrown, which is how the two halves were
   * told apart.
   *
   * <p>{@code PinnedOperationAuthority.require} resolves a contained admission's live authority by
   * walking to the nearest enclosing <em>uncontained</em> scope, because {@code contain} copies the
   * container's own grant rows rather than issuing any; revoking the container's grant therefore
   * kills the descendant's admission with nothing about the child's row changing.
   *
   * <p>Both halves are real, and that is the test: the real {@link PinnedOperationAuthority}
   * decides the authority and the real turn loop asks it. The revocation lands between two model
   * calls, and the second call is never made.
   */
  @Test
  void aRevokedContainerStopsItsDescendantsWork() {
    var grants = mock(ScopedGrantRepository.class);
    var scoped = mock(ScopedAuthority.class);
    var members = mock(ProjectMembers.class);
    var operations = mock(OperationRecords.class);
    var resources = mock(ResourceScopeRepository.class);
    var policy =
        new PinnedOperationAuthority(
            identities,
            grants,
            scoped,
            members,
            operations,
            resources,
            AccountKnowledgePermissions.DENY,
            new ContainmentBounds(2, 4));
    var container = new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
    var confined = new AuthorityScope.Contained(childScope, 123L, container.id());
    Home confinedHome = Home.of(childProject);
    UUID grant = UUID.randomUUID();
    var holder = new AccountId(UUID.randomUUID());
    var subject = GrantRecipient.account(holder);
    var actions = Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL);
    when(identities.account("alice"))
        .thenReturn(
            new IdentityRepository.Account(
                holder, "alice", IdentityRepository.AccountKind.USER, false));
    when(identities.project("one")).thenReturn(container);
    when(identities.home(container)).thenReturn(Home.of("one"));
    when(identities.scope(container.id())).thenReturn(container);
    when(identities.containedHome(confined, container.id())).thenReturn(confinedHome);
    when(identities.contained(confined.id())).thenReturn(Optional.of(confined));
    // Faithful to JdbcIdentityRepository: a contained scope is never resolvable from a project
    // name, so the live-authority path cannot reach one through that read.
    doThrow(new CallerFault("A contained scope has no addressable project home"))
        .when(identities)
        .project(
            org.mockito.ArgumentMatchers.argThat(
                name -> name != null && name.startsWith("contained:")));
    when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
    when(members.role("one", "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
    var binding = mock(ScopedGrantRepository.Binding.class);
    when(binding.id()).thenReturn(grant);
    when(grants.live(subject)).thenReturn(List.of(binding));
    for (var action : actions)
      when(scoped.allowsUsing(eq(subject), eq(container), eq(action), eq(grant), anyList()))
          .thenReturn(true);

    var containerAdmission = policy.admit("alice", container, actions);
    var descendant = policy.contain(containerAdmission, confined, actions);
    when(operations.parent(descendant.id())).thenReturn(Optional.of(containerAdmission.id()));
    when(operations.find(containerAdmission.id())).thenReturn(Optional.of(containerAdmission));
    assertDoesNotThrow(() -> policy.require(descendant), "the descendant starts out live");

    var dispatcher = mock(LlmDispatcher.class);
    var transcript = mock(Transcript.class, CALLS_REAL_METHODS);
    when(transcript.conversationId()).thenReturn("cnv_CHILD");
    AgentTool date = mock(AgentTool.class);
    when(date.schema()).thenReturn(ToolSchema.from("get_date", "date", Map.of("type", "object")));
    when(date.run("{}", confinedHome)).thenReturn("today");
    var modelCalls = new AtomicInteger();
    when(dispatcher.stream(any(), any(), any()))
        .thenAnswer(
            call -> {
              modelCalls.incrementAndGet();
              // MID-RUN, and from outside: the container's own grant stops allowing the actions it
              // lent. Nothing about the child's stored row changes and nobody tells the run.
              for (var action : actions)
                when(scoped.allowsUsing(
                        eq(subject), eq(container), eq(action), eq(grant), anyList()))
                    .thenReturn(false);
              return new Completion(
                  null,
                  "tool_calls",
                  TokenUsage.UNKNOWN,
                  List.of(new ToolCall("one", "get_date", "{}")));
            });
    var definition =
        new AgentDefinition(
            "worker",
            "worker",
            "fast",
            List.of("get_date"),
            List.of(),
            List.of(),
            4,
            8,
            "Answer clearly",
            true,
            false);

    var refusal =
        assertThrows(
            CallerFault.class,
            () ->
                new JobRuntime(policy, dispatcher, List.of(date))
                    .runOperation(
                        definition,
                        "Summarise the notes",
                        confinedHome,
                        Budget.of(5),
                        () -> false,
                        null,
                        JobWatch.UNWATCHED,
                        transcript,
                        TurnCap.of(8),
                        List.of(),
                        "alice",
                        false,
                        descendant));

    // The refusal names the admission and not the child: it is the operation that has gone outside
    // its retained authority, and a message naming the child would say the child did something.
    assertEquals("Operation is outside its retained scoped authority", refusal.getMessage());
    assertFalse(refusal.getMessage().contains(childProject), refusal.getMessage());
    assertFalse(refusal.getMessage().contains(childId.toString()), refusal.getMessage());
    assertEquals(1, modelCalls.get(), "the run stopped rather than completing its next turn");
  }
}
