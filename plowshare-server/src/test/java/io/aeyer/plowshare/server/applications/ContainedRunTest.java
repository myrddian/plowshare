package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.ApplicationDeployment.File;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.agents.Runs;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Starting work inside a contained Application: the first operation that executes a child.
 *
 * <p>Every repository is a mocked interface or a small in-memory fake, per the database test
 * policy: nothing asserted here is PostgreSQL behaviour. {@code Runs} is mocked too, which makes
 * the recorded {@link Runs.Ask} the subject — this operation's job is to decide <em>what</em> is
 * asked of the run service and to refuse before asking it, and that decision is exactly what a
 * captured {@code Ask} pins. What the live run service then does with a credential-less caller is
 * not this test's claim and is recorded as a gap in the task report.
 *
 * <p><strong>The authority proof comes first.</strong> Unlike {@code trajectory} and {@code
 * resources}, which look the child up before proving the caller, every refusal below is reached
 * with the child's package unread and its source area untouched; the assertions check that by
 * verifying {@code records.find} was never called on the refusals that precede it.
 */
class ContainedRunTest {
  @TempDir Path directory;

  ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
  ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  ApplicationPackageValidator validator = mock(ApplicationPackageValidator.class);
  OperationAuthority authority = mock(OperationAuthority.class);
  IdentityRepository identities = mock(IdentityRepository.class);
  ContainedPrincipals principals = mock(ContainedPrincipals.class);
  Runs runs = mock(Runs.class);
  Allowances allowances = new Allowances();
  Work work = new Work();

  AccountId account = new AccountId(UUID.randomUUID());
  GrantRecipient recipient = GrantRecipient.account(account);
  AuthorityScope.Project parentScope =
      new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 7);
  AllowanceId parentBudget = new AllowanceId(UUID.randomUUID());
  OperationAuthority.Admission parentAdmission = admission("@service/parent", parentScope.id());

  /** The child, its own scope, its principal and the opaque project its home names. */
  UUID childId = UUID.randomUUID();

  ScopeId childScope = new ScopeId(UUID.randomUUID());
  UUID childPrincipal = UUID.randomUUID();
  String childProject = "contained:" + UUID.randomUUID();
  AllowanceId childAllowance = new AllowanceId(UUID.randomUUID());
  UUID childRevision = UUID.randomUUID();

  Path root;
  Path parentWorkspace;
  SourceContainedApplications service;

  OperationAuthority.Admission admission(String executor, ScopeId scope) {
    return new OperationAuthority.Admission(
        UUID.randomUUID(),
        executor,
        account,
        scope,
        Home.of(scope.equals(parentScope.id()) ? "parent" : "other"),
        recipient,
        List.of(
            new OperationAuthority.Support(AuthorityAction.READ, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.WRITE, UUID.randomUUID()),
            new OperationAuthority.Support(AuthorityAction.RUN_MODEL, UUID.randomUUID()),
            new OperationAuthority.Support(
                AuthorityAction.CONVERSATION_CREATE, UUID.randomUUID())));
  }

  /** Spends and ceilings are real here, as in {@code ContainedApplicationCreationTest}. */
  static final class Allowances implements AllowanceRepository {
    final Map<AllowanceId, AllowanceSnapshot> held = new LinkedHashMap<>();

    @Override
    public void retain(AllowanceSnapshot selected) {
      if (held.putIfAbsent(selected.id(), selected) != null)
        throw new IllegalStateException("An existing allowance is never reset");
    }

    @Override
    public Optional<AllowanceSnapshot> find(AllowanceId id) {
      return Optional.ofNullable(held.get(id));
    }

    @Override
    public Optional<io.aeyer.plowshare.server.agents.Budget.Claim> claim(
        AllowanceId id, int reserve, Integer expectedSpent) {
      return Optional.empty();
    }

    @Override
    public void accept(io.aeyer.plowshare.server.agents.Budget.Claim claim) {}

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
    boolean rolledBack;

    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> body) {
      try {
        return body.get();
      } catch (RuntimeException failed) {
        rolledBack = true;
        throw failed;
      }
    }
  }

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectories(directory.resolve("containment"));
    parentWorkspace = Files.createDirectories(directory.resolve("parent"));
    manifest(parentWorkspace, "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
    allowances.retain(new AllowanceSnapshot(parentBudget, 100, 10));
    allowances.retain(new AllowanceSnapshot(childAllowance, 5, 0));
    when(projects.find("parent"))
        .thenReturn(
            Optional.of(
                new ProjectRecord(
                    "parent", parentWorkspace, List.of(), List.of(), "MANAGED", List.of())));
    when(projects.effectiveExclusions(any())).thenReturn(List.of());
    when(authority.conversation("c-1"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-1", "worker", parentAdmission, null, parentBudget, "0".repeat(64))));
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id(), 5, true)));
    when(identities.containedHome(any(), any())).thenReturn(Home.of(childProject));
    when(principals.issue(any()))
        .thenReturn(new ContainedPrincipals.ContainedPrincipal(childPrincipal, childScope));
    // The hop, as PinnedOperationAuthority performs it: the child's scope and home, and the
    // container's executor and account carried through unchanged, which is what makes the
    // accounting account the container's.
    when(authority.contain(any(), any(), any()))
        .thenAnswer(
            call -> {
              AuthorityScope.Contained child = call.getArgument(1);
              return new OperationAuthority.Admission(
                  UUID.randomUUID(),
                  parentAdmission.executor(),
                  parentAdmission.account(),
                  child.id(),
                  Home.of(childProject),
                  parentAdmission.source(),
                  parentAdmission.support());
            });
    // The nearest enclosing uncontained home above this child: its container's own project.
    when(authority.mediatingHome(any())).thenReturn(Home.of("parent"));
    when(runs.start(any(), any())).thenReturn(new Runs.Started("job-1", "worker", "child-conv"));
    // The child's own package, staged where the service derives it from the project name. The
    // agent is a file in it, which is what makes the "undeclared agent" refusal below non-vacuous.
    stage("worker");
    service = service(new ContainmentConfig(root.toString(), 2, 4, null));
  }

  /** Writes the child's staged source: a manifest and one declared agent of the given name. */
  void stage(String... agents) throws Exception {
    Path staged =
        root.resolve(childProject.substring("contained:".length()))
            .resolve("revisions")
            .resolve(childRevision.toString());
    Files.createDirectories(staged.resolve("agents"));
    manifest(staged, "{\"version\":1,\"name\":\"" + childProject + "\",\"nesting\":true}");
    for (String agent : agents)
      Files.writeString(
          staged.resolve("agents").resolve(agent + ".md"),
          "---\nname: " + agent + "\n---\nDo the work.\n");
  }

  ContainedApplicationRepository.Retained child(ScopeId container, Integer ceiling, boolean live) {
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
        ceiling,
        live);
  }

  SourceContainedApplications service(ContainmentConfig config) {
    return service(config, runs);
  }

  SourceContainedApplications service(ContainmentConfig config, Runs started) {
    return new SourceContainedApplications(
        records,
        projects,
        validator,
        authority,
        identities,
        principals,
        allowances,
        work,
        config,
        new InProcessContainedStaging(),
        started,
        // No run is going in any conversation here; stopping one is ContainedCancellationTest's.
        conversation -> 0);
  }

  static void manifest(Path workspace, String text) throws Exception {
    Files.writeString(workspace.resolve("plowshare.json"), text);
  }

  ContainedApplications.Run request() {
    return new ContainedApplications.Run("c-1", childId, "worker", "Summarise the notes", 3);
  }

  Runs.Ask asked() {
    var captured = ArgumentCaptor.forClass(Runs.Ask.class);
    verify(runs).start(captured.capture(), any());
    return captured.getValue();
  }

  @Test
  void aContainerRunsItsOwnChild() {
    var running = service.run("@service/parent", request());

    assertEquals(childId, running.child());
    assertEquals("job-1", running.job());
    assertEquals("child-conv", running.conversation());
    assertEquals("worker", running.agent());

    var ask = asked();
    // The run's project is the child's own contained home, resolved from the stored row and never
    // from a field of the request -- there is no such field.
    assertEquals(childProject, ask.project());
    assertNull(ask.conversation(), "the container's conversation is the authority, not the run's");
    assertEquals("worker", ask.agent());
    assertEquals("Summarise the notes", ask.task());
    assertEquals(3, ask.maxTurns());
    // Two axes, and this asserts both, because they are deliberately different identities.
    //
    // ACCOUNT: the container's. A hop keeps the parent's executor and account unchanged, so the
    // run's caller identity is the container's executor and NOT the contained principal. This
    // reverses what an earlier draft asserted: the principal is not an authority_accounts subject
    // and a run whose caller was one would have had to make it one.
    assertEquals("@service/parent", ask.callerHandle());
    assertNotEquals(childPrincipal.toString(), ask.callerHandle());
    // AUTHOR: the child's. The harness speaks for the child by name, which survives into the log
    // and is what lets a container tell its child's writing from its own now that the account is
    // shared -- child output being untrusted input to its container.
    assertEquals(Speaker.Kind.HARNESS, ask.speaker().kind());
    assertEquals("contained " + childId, ask.speaker().name());
    assertFalse(ask.speaker().name().contains("parent"));
    // WORK AUTHORITY: asked about the nearest enclosing uncontained project, never about the
    // child's own project name, which carries no role for anybody.
    assertEquals("parent", ask.contained().workProject());
    assertNotEquals(childProject, ask.contained().workProject());
    verify(authority).mediatingHome(any());
    // THE POOL: the child's own allowance, under the ceiling its container set -- not this
    // server's default conversation budget and not a second pool minted for the run.
    assertEquals(childAllowance.value(), ask.contained().budget().identity());
    assertEquals(5, ask.contained().budget().limit());
    // THE ACCOUNTING ACCOUNT: the container's, carried by the admission the hop produced. ADR
    // 0025's rule that delegation cannot change the accounting account is untouched.
    assertEquals(account, ask.contained().admission().account());
    assertEquals("@service/parent", ask.contained().admission().executor());
    // The principal is still issued: Tasks 4 and 5 key cancellation and revocation off it.
    verify(principals).issue(any());
    // And the live authority of the container really was proved, not merely retained.
    verify(authority).require(parentAdmission);
  }

  /**
   * The direct-container rule, not {@code trajectory}'s ancestor walk. A grandparent reads through
   * the chain and may not start work in a grandchild: the spend is the intermediate container's to
   * lend.
   */
  @Test
  void aGrandparentIsRefused() {
    var intermediate = new ScopeId(UUID.randomUUID());
    when(records.find(childId)).thenReturn(Optional.of(child(intermediate, 5, true)));
    when(records.ofScope(intermediate)).thenReturn(Optional.of(child(parentScope.id(), 5, true)));
    when(authority.conversationsIn(childScope)).thenReturn(List.of());

    assertEquals(
        "This Application does not contain that Application",
        assertThrows(CallerFault.class, () -> service.run("@service/parent", request()))
            .getMessage());
    verify(runs, never()).start(any(), any());
    verifyNoInteractions(principals);
    // Control, and the one that makes this test about the rule rather than about a broken chain:
    // the very same caller and child are admitted by trajectory, which is ancestor-gated. So the
    // chain really does reach this grandparent, and run applies the stricter rule on purpose.
    assertEquals(
        List.of(),
        service.trajectory("@service/parent", "c-1", childId),
        "the ancestor gate admits");
  }

  @Test
  void aSiblingIsRefused() {
    var siblingContainer = new ScopeId(UUID.randomUUID());
    when(records.find(childId)).thenReturn(Optional.of(child(siblingContainer, 5, true)));
    // A sibling's container is a scope this caller is not, and is on no chain of its own.
    when(records.ofScope(siblingContainer)).thenReturn(Optional.empty());

    assertThrows(CallerFault.class, () -> service.run("@service/parent", request()));
    verify(runs, never()).start(any(), any());
    // Control: the same caller, the same agent and the same package, differing only in whose
    // container the row names, is started.
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id(), 5, true)));
    assertEquals("job-1", service.run("@service/parent", request()).job());
  }

  /** Nothing travels the other way: a child naming its own container finds no row to compare. */
  @Test
  void aChildRunningItsContainerIsRefused() {
    var childAdmission = admission("@service/parent", childScope);
    when(authority.conversation("c-2"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-2", "worker", childAdmission, null, childAllowance, "0".repeat(64))));
    var container = UUID.randomUUID();
    when(records.find(container))
        .thenReturn(
            Optional.of(
                new ContainedApplicationRepository.Retained(
                    container,
                    parentScope.id(),
                    7L,
                    "contained:" + UUID.randomUUID(),
                    new ScopeId(UUID.randomUUID()),
                    UUID.randomUUID(),
                    "b".repeat(64),
                    UUID.randomUUID(),
                    parentBudget,
                    5,
                    true)));

    assertThrows(
        CallerFault.class,
        () ->
            service.run(
                "@service/parent",
                new ContainedApplications.Run("c-2", container, "worker", "Read upwards", null)));
    verify(runs, never()).start(any(), any());
    // Control: the container running the child over the same fixture is started, so the refusal is
    // the direction and not a missing stub.
    assertEquals("job-1", service.run("@service/parent", request()).job());
  }

  /** As {@code resources} refuses one: there is nothing left to run. */
  @Test
  void aDestroyedChildIsRefused() {
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id(), 5, false)));

    assertEquals(
        "That contained Application has been destroyed",
        assertThrows(CallerFault.class, () -> service.run("@service/parent", request()))
            .getMessage());
    verify(runs, never()).start(any(), any());
    verifyNoInteractions(principals);
  }

  /**
   * A child whose container reduced its ceiling to zero cannot make a call, so it is not started.
   */
  @Test
  void aChildWithAZeroCeilingIsRefused() {
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id(), 0, true)));

    var refusal = assertThrows(CallerFault.class, () -> service.run("@service/parent", request()));
    assertTrue(refusal.getMessage().contains("ceiling"), refusal.getMessage());
    verify(runs, never()).start(any(), any());
    // Control: the same call with a ceiling of one is started, so the refusal is the ceiling and
    // not a broken fixture.
    when(records.find(childId)).thenReturn(Optional.of(child(parentScope.id(), 1, true)));
    assertEquals("job-1", service.run("@service/parent", request()).job());
  }

  @Test
  void anUnconfiguredContainmentRefuses() {
    var unconfigured = service(ContainmentConfig.absent());

    assertThrows(CallerFault.class, () -> unconfigured.run("@service/parent", request()));
    // Before any lookup: no child row is read and no conversation is resolved.
    verify(records, never()).find(any());
    verify(authority, never()).conversation(any());
    verify(runs, never()).start(any(), any());
  }

  /**
   * The agent is resolved against the child's <em>own</em> package. A container cannot name an
   * agent of its own and have it run inside the child.
   */
  @Test
  void anAgentTheChildDoesNotDeclareIsRefused() {
    var refusal =
        assertThrows(
            CallerFault.class,
            () ->
                service.run(
                    "@service/parent",
                    new ContainedApplications.Run("c-1", childId, "auditor", "Audit", null)));
    assertTrue(refusal.getMessage().contains("auditor"), refusal.getMessage());
    verify(runs, never()).start(any(), any());
    // The container declares it, which changes nothing: the name is looked up in the child's
    // package and in nothing else. Without this control the refusal above could be read as "no
    // agent anywhere is nameable".
    assertDoesNotThrow(
        () -> {
          Files.createDirectories(parentWorkspace.resolve("agents"));
          Files.writeString(
              parentWorkspace.resolve("agents").resolve("auditor.md"),
              "---\nname: auditor\n---\nAudit.\n");
        });
    assertThrows(
        CallerFault.class,
        () ->
            service.run(
                "@service/parent",
                new ContainedApplications.Run("c-1", childId, "auditor", "Audit", null)));
    verify(runs, never()).start(any(), any());
    // Control: the agent the child does declare starts.
    assertEquals("job-1", service.run("@service/parent", request()).job());
  }

  /**
   * The breadth bound limits live children, not concurrent runs, and this pins that reading: a run
   * of an already-live child succeeds with the bound reached. A reader will assume the other
   * meaning, so the choice is asserted rather than left to the absence of a check.
   */
  @Test
  void theBreadthBoundCountsRunningChildren() {
    when(records.liveDescendants(parentScope.id())).thenReturn(4);

    assertEquals("job-1", service.run("@service/parent", request()).job());
    // And the bound is still the server's: creating another child at the same count refuses.
    assertThrows(
        CallerFault.class,
        () ->
            service.create(
                "@service/parent",
                new ContainedApplications.Create(
                    "c-1", UUID.randomUUID(), 1, null, List.of(), files())));
  }

  /**
   * A child this operation started calls {@code create} itself, and the server's own bounds still
   * refuse it. Execution is the first path on which a child could reach {@code create} at all.
   *
   * <p>Breadth is this service's own check and is asserted directly. Depth is the hop's — {@code
   * OperationAuthority.contain} refuses a chain longer than {@link ContainmentBounds#maxDepth}, and
   * {@code ContainmentHopTest} pins that refusal where it is made — so what is asserted here is
   * that the refusal reaches the caller and rolls the creation back, and that no argument of the
   * request could have supplied either bound.
   */
  @Test
  void aRunningChildCreatingAChildIsStillBounded() throws Exception {
    var childAdmission = admission("@service/child", childScope);
    Path childSource =
        root.resolve(childProject.substring("contained:".length()))
            .resolve("revisions")
            .resolve(childRevision.toString());
    when(projects.find(childProject))
        .thenReturn(
            Optional.of(
                new ProjectRecord(
                    childProject, childSource, List.of(), List.of(), "MANAGED", List.of())));
    // The running child's own admission names its contained home, which is where its create reads
    // its source from.
    var asChild =
        new OperationAuthority.Admission(
            childAdmission.id(),
            "@service/child",
            account,
            childScope,
            Home.of(childProject),
            recipient,
            childAdmission.support());
    when(authority.conversation("c-child"))
        .thenReturn(
            Optional.of(
                new OperationAuthority.Conversation(
                    "c-child", "worker", asChild, null, childAllowance, "0".repeat(64))));

    // Breadth: the server's configured bound, reached by this child's own descendants.
    when(records.liveDescendants(childScope)).thenReturn(4);
    assertEquals(
        "This Application already holds its permitted live contained Applications",
        assertThrows(
                CallerFault.class,
                () ->
                    service.create(
                        "@service/child",
                        new ContainedApplications.Create(
                            "c-child", UUID.randomUUID(), 1, null, List.of(), files())))
            .getMessage());
    verify(records, never()).create(any());

    // Depth: refused by the hop, inside the durable unit, which rolls the creation back.
    when(records.liveDescendants(childScope)).thenReturn(0);
    when(records.receipt(any(), any())).thenReturn(Optional.empty());
    when(records.create(any()))
        .thenAnswer(
            call -> {
              ContainedApplicationRepository.Creation creation = call.getArgument(0);
              return new ContainedApplicationRepository.Retained(
                  UUID.randomUUID(),
                  new ScopeId(UUID.randomUUID()),
                  99,
                  creation.project(),
                  creation.container(),
                  creation.requestId(),
                  creation.fingerprint(),
                  creation.revision(),
                  creation.allowance(),
                  creation.ceiling(),
                  true);
            });
    when(principals.issue(any()))
        .thenAnswer(
            call ->
                new ContainedPrincipals.ContainedPrincipal(
                    UUID.randomUUID(), ((AuthorityScope.Contained) call.getArgument(0)).id()));
    // doThrow and not when(...).thenThrow: when() would invoke the stubbed method with nulls to
    // record the new behaviour, which runs setup's answer and dies reading a null scope.
    doThrow(new CallerFault("This containment chain exceeds the configured depth"))
        .when(authority)
        .contain(any(), any(), any());
    assertEquals(
        "This containment chain exceeds the configured depth",
        assertThrows(
                CallerFault.class,
                () ->
                    service.create(
                        "@service/child",
                        new ContainedApplications.Create(
                            "c-child", UUID.randomUUID(), 1, null, List.of(), files())))
            .getMessage());
    assertTrue(work.rolledBack, "a refused hop rolls the child's own creation back");
  }

  /**
   * The safety claim {@code Runs.Ask.workProject} rests on, asserted rather than described: no wire
   * shape can set it.
   *
   * <p>The field asks the work question about a project other than the one the run answers from,
   * which is exactly what a caller would want to forge — prove authority against a project it
   * holds, then run somewhere else. It is safe because the only populated caller is this service,
   * which has already proved the admission it derives the value from. Two things keep that true and
   * both are pinned here: {@code api.RunAgentRequest} (the HTTP body and the frame payload Jackson
   * binds) has no such component, and the pre-existing {@code Ask} constructor leaves it null, so
   * every caller that is not this one asks the question it always asked.
   *
   * <p>It lives in this class because this task introduced the field; if the run surface grows its
   * own test for it, move it there.
   */
  @Test
  void noWireShapeCanSupplyTheWorkAuthorityProject() {
    var wireFields =
        java.util.Arrays.stream(
                io.aeyer.plowshare.server.api.RunAgentRequest.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();
    assertFalse(wireFields.contains("contained"), wireFields.toString());
    assertFalse(wireFields.contains("workProject"), wireFields.toString());
    // And the body really does carry the fields a run names, so the absence above is a decision
    // about this one field and not an empty reflection result.
    assertTrue(wireFields.contains("task"), wireFields.toString());
    assertTrue(wireFields.contains("project"), wireFields.toString());

    var ordinary =
        new Runs.Ask(
            "worker",
            "do it",
            "parent",
            null,
            null,
            null,
            null,
            List.of(),
            null,
            "@service/parent",
            null);
    assertNull(ordinary.contained(), "an ordinary caller carries no contained authority");
  }

  /**
   * {@code Runs} UN-MOCKED: the real run service deciding a contained start.
   *
   * <p>Every other test in this class mocks {@code Runs} and measures what this operation
   * <em>asks</em> for. This one measures what the real {@code Runs.start} then <em>does</em> with
   * it, which is the half that was unverifiable until the lineage gate opened: which conversation
   * door it chooses, which project it proves work authority against, and which pool and admission
   * it hands to that door.
   *
   * <p>Only the two collaborators below {@code Runs} are stubbed — the conversation store, so the
   * call can be captured, and {@code Turn}, so no model is called. {@code Runs}, {@code Callers},
   * {@code CallerAccess} and {@code DefinitionResolver} are all real.
   *
   * <p><strong>The work question is load-bearing here and the fixture makes it so.</strong> The
   * membership fake answers {@code mayWork} for the container's project and for nothing else, so if
   * the real {@code Runs} asked about the child's contained project — as it did before this slice —
   * this test would fail with the CONTRIBUTOR refusal rather than pass. The persisted counterpart
   * of these assertions is {@code ContainedConversationDoorDatabaseTest}, which drives the real
   * door against real PostgreSQL.
   */
  @Test
  void theRealRunServiceOpensTheContainedDoorWithTheChildsPool() {
    var members = mock(io.aeyer.plowshare.server.archive.ProjectMembers.class);
    // The container's project and nothing else. The child's contained project answers false,
    // which is what it answers in production: a contained project carries no PROJECT scope.
    when(members.mayWork("parent", "@service/parent")).thenReturn(true);
    when(members.role(eq("parent"), eq("@service/parent")))
        .thenReturn(
            java.util.Optional.of(io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR));
    var store = mock(io.aeyer.plowshare.server.archive.ProjectStore.class);
    when(store.id(childProject)).thenReturn(42L);
    // A default answer rather than a stubbed method, because the method this now reaches --
    // Turn.speakContained -- is package-private in `agents` and cannot be named from here. That is
    // the door's narrowing working as intended; WHICH door the real Runs chooses is asserted in
    // io.aeyer.plowshare.server.agents.ContainedStartDoorTest, which can name both.
    var turn =
        mock(
            io.aeyer.plowshare.server.agents.Turn.class,
            call -> call.getMethod().getReturnType() == String.class ? "job-real" : null);
    var conversations = mock(io.aeyer.plowshare.server.archive.ConversationStore.class);
    var opened = mock(io.aeyer.plowshare.server.archive.ConversationRecord.class);
    when(opened.id()).thenReturn("child-conv");
    when(opened.home()).thenReturn(Home.of(childProject));
    when(conversations.openContained(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(opened);

    var definition =
        new io.aeyer.plowshare.server.agents.AgentDefinition(
            "worker",
            "a fixture",
            "fast",
            List.of(),
            List.of(),
            List.of(),
            2,
            4,
            "Do it.",
            true,
            true);
    var resolver =
        new io.aeyer.plowshare.server.agents.DefinitionResolver(
            new io.aeyer.plowshare.server.agents.AgentRegistry(Map.of("worker", definition)),
            io.aeyer.plowshare.server.data.DataLayout.NONE,
            id -> true,
            Set.of(),
            Set.of(),
            mock(io.aeyer.plowshare.server.files.SessionChannel.class),
            session -> true,
            io.aeyer.plowshare.server.agents.DefinitionChecks.NONE);
    var real =
        new Runs(
            new io.aeyer.plowshare.server.agents.Callers(
                resolver,
                store,
                turn,
                new io.aeyer.plowshare.server.agents.CallerAccess(
                    new io.aeyer.plowshare.server.session.SessionRegistry(), members)),
            mock(io.aeyer.plowshare.server.agents.JobStore.class),
            turn);
    @SuppressWarnings("unchecked")
    var stages =
        (org.springframework.beans.factory.ObjectProvider<
                io.aeyer.plowshare.server.agents.LogStages>)
            mock(org.springframework.beans.factory.ObjectProvider.class);
    when(stages.getObject()).thenReturn(mock(io.aeyer.plowshare.server.agents.LogStages.class));
    real.useConversations(
        conversations, new io.aeyer.plowshare.server.api.ConversationsProperties(), stages);

    var running =
        service(new ContainmentConfig(root.toString(), 2, 4, null), real)
            .run("@service/parent", request());

    assertEquals("job-real", running.job());
    assertEquals("child-conv", running.conversation());
    assertEquals(childId, running.child());

    // THE DOOR: the contained one, never the ordinary one.
    var home = ArgumentCaptor.forClass(Home.class);
    var pool = ArgumentCaptor.forClass(io.aeyer.plowshare.server.agents.Budget.class);
    var admitted = ArgumentCaptor.forClass(OperationAuthority.Admission.class);
    var agent = ArgumentCaptor.forClass(String.class);
    verify(conversations)
        .openContained(
            home.capture(),
            pool.capture(),
            any(),
            eq("@service/parent"),
            admitted.capture(),
            agent.capture(),
            any());
    verify(conversations, never()).open(any(), any(), any(), any());
    assertEquals(Home.of(childProject), home.getValue(), "opened in the child's own home");
    // THE POOL: the child's own allowance, with its container's ceiling.
    assertEquals(childAllowance.value(), pool.getValue().identity());
    assertEquals(5, pool.getValue().limit());
    // THE ACCOUNT: the container's, carried by the admission the hop produced.
    assertEquals(account, admitted.getValue().account());
    assertEquals("@service/parent", admitted.getValue().executor());
    assertEquals(childScope, admitted.getValue().scope());
    assertEquals("worker", agent.getValue());
    // Which Turn door the first utterance goes through, and that it carries the child's speaker
    // and task, is asserted in ContainedStartDoorTest: speakContained is package-private, so this
    // package cannot name it to verify it, and verifying the ordinary speak() was NOT called would
    // be an absence assertion with nothing positive beside it.
  }

  List<File> files() {
    return List.of(
        new File("plowshare.json", "{\"version\":1,\"name\":\"grandchild\"}"),
        new File("agents/README.md", "notes"));
  }
}
