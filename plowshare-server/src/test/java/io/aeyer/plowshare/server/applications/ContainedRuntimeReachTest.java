package io.aeyer.plowshare.server.applications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.access.*;
import io.aeyer.plowshare.server.access.AuthorityIds.*;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.allowances.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.events.ScheduleRecord;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.information.AccountKnowledgePermissions;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.relay.*;
import io.aeyer.plowshare.server.relay.tools.*;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.Hub;
import io.aeyer.plowshare.server.ws.Asking;
import io.aeyer.plowshare.server.ws.FrameTypes;
import io.aeyer.plowshare.server.ws.ScheduleDefineHandler;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * What a <em>running</em> contained Application can reach — the runtime half of ADR 0042's
 * confinement proof, which slice one could not test because nothing executed a child.
 *
 * <p>Slice one reduced or refused every surface a child's <em>source</em> declares, at creation. A
 * running child reaches surfaces no declaration mentions: the definitions {@code agent_run} can
 * name, the tool bindings its calls resolve against, whatever ordinary project creation provisions,
 * and the operational authority a schedule carries. This class asks, for each, whether the child's
 * reach falls out of <em>resolution</em> — per the spec's anti-goal, "confinement that is a check
 * rather than a structure" is a failure even if it ships.
 *
 * <h2>What already passed before this class existed, and what did not</h2>
 *
 * <p>Recorded because the brief asks for it and because the distinction is half this task's value:
 * a surface slice one's structural confinement already closes needs a <em>test</em>, not a change,
 * and writing a second fence for an already-fenced surface adds a second place to get it wrong.
 *
 * <p><strong>No production code was changed for this class, and no assertion here required one.
 * Every surface it asks about was already fenced.</strong> The first run of the six tests reported
 * two failures, and both were this class's own mistake about <em>where</em> the fence sits, not an
 * open surface: the delegation tests expected {@code agent_run}'s call-time lookup to be what
 * refuses, and production refuses earlier and harder — the definition loader drops an unresolvable
 * {@code calls:} edge outright, so a child whose only declared edges point outward is not offered
 * {@code agent_run} at all. The tests were corrected to assert the fence that exists rather than
 * the one they assumed. That is the honest account: the task converted five structural accidents
 * into asserted contracts, and closed nothing, because there was nothing open.
 *
 * <p>Each surface is fenced at resolution by a structure that predates containment and that
 * containment reuses:
 *
 * <ul>
 *   <li><b>Ancestor and sibling definitions</b> ({@link
 *       #aRunningChildCannotDelegateToAnAncestorsDefinition}, {@link
 *       #aRunningChildCannotDelegateToASiblingsDefinition}) — {@code agent_run} has no {@code
 *       project} field, so a delegate is looked up in the {@code Home} the run was started in and
 *       nowhere else, and {@code Callers.find} builds that home's registry from that project's own
 *       resource root. An ancestor's definition is not in it, and the loader says so in the words
 *       the spec asks for: the name is one "no file in this directory defines", which is a lookup
 *       failing and not a permission denying.
 *   <li><b>Tool bindings</b> ({@link #aRunningChildsToolCallResolvesOnlyItsOwnBindings}) — {@code
 *       ApplicationToolRegistry} snapshots a project's bindings from that project's own {@code
 *       server/tools.json}. A binding of the container's is in the container's snapshot and in no
 *       other, so it is not merely ungranted to the child: it is absent from the set the child's
 *       home resolves.
 *   <li><b>The union and git provisioning</b> ({@link #aRunningChildAcquiresNoWorkingTreeOrGitHub})
 *       — a hub is created only by {@code union.enable}, which needs an authenticated session, and
 *       a child's {@code MANAGED} project row is not a union candidate at all. Starting a run
 *       touches neither. The at-rest half of this is {@code
 *       ContainedAddressabilityDatabaseTest.creatingAChildProvisionsNoWorkingTreeOrGitHub}, which
 *       pins the SQL predicate; this is the runtime half.
 *   <li><b>Schedules</b> ({@link #aRunningChildCannotDefineASchedule}) — every schedule surface is
 *       a frame behind {@code Asking.requireHandle}, and no tool in the set a contained run
 *       resolves defines one. A contained principal is a bare UUID with no credential material
 *       (V218), so it holds no handle and reaches no frame.
 *   <li><b>The containment hop</b> ({@link #aRunningChildDelegatesWithinItsOwnSubtree}) — {@code
 *       contain} runs again per started run, so the grandchild's scope, principal, author and
 *       allowance are its own, and the actions confined are the child's own narrower set rather
 *       than the container's.
 * </ul>
 *
 * <h2>Every refusal here carries a positive control</h2>
 *
 * <p>An absence assertion that could hold because a fixture was never installed proves nothing, and
 * that has been the recurring review finding on this slice. So each refusal below is paired with
 * the same call succeeding for a caller that should be allowed: the ancestor's definition really
 * does resolve and delegate <em>in the ancestor's own home</em>; the container's binding really is
 * callable <em>on the container's home</em>; {@code Hub.create} really does produce a tree and a
 * bare repository <em>when something asks it to</em>; and {@code schedule.define} really does
 * define a schedule <em>for an authenticated handle</em>. Membership is stubbed permissive
 * throughout, deliberately, so that nothing here can pass because a permission said no.
 *
 * <h2>These assertions were checked against a broken fence</h2>
 *
 * <p>A test that passes because nothing was installed is this slice's recurring review finding, so
 * the controls above were not the only check. Four fences were deliberately broken and the suite
 * re-run, to confirm each refusal is sensitive to the thing it claims to pin: resolving the child's
 * definitions from the container's resource root failed both delegation tests; resolving its tool
 * bindings from the container's root failed the binding test; making the hop keep the caller's
 * scope and home — ADR 0011's downward inheritance, which ADR 0042 departs from — failed the
 * subtree test; and changing {@code requireReadTargets} to admit a row when <em>any</em> of its
 * projects is the admission's home — the audience mistake — failed the resource test. The last was
 * a mutation of production rather than of a fixture. All were reverted; they are recorded in the
 * task report with their output.
 *
 * <h2>A measured surface that is NOT fenced, and is not this task's to close</h2>
 *
 * <p>{@code DefinitionResolver} merges the inherited global tier into every project's registry, so
 * the delegation tests above prove confinement against <em>project-local</em> definitions only. A
 * contained child's agent declaring {@code calls: [some_global_agent]} keeps that edge: measured,
 * and the measurement is in the task report with its output. The whole global tier appears in the
 * child's registry, {@code Callers.find} resolves a global agent from inside the contained home,
 * and a global agent carrying its own {@code tools:} list resolves with that list — which no subset
 * test compares against the child's package or its container's, because {@code
 * ContainedDeclarations} compares only what the two <em>packages</em> declare.
 *
 * <p>Nothing here fences it, deliberately. A global agent is the server operator's own definition
 * rather than a child's or a container's, so whether a contained subtree should inherit the tier at
 * all is a question about the platform and not about this test, and closing it would be a core
 * change needing its own authorization. It is reported rather than asserted, because a test
 * asserting the current behaviour would bake in an answer nobody has given. The one existing
 * partial fence is {@code AgentRegistry.escalatingGrant}, which refuses a callee whose
 * <em>workspace</em> grants exceed the caller's; it says nothing about a callee's {@code tools:}.
 *
 * <h2>A door that does not exist, recorded rather than asserted</h2>
 *
 * <p>{@code application.child.run} is a frame behind {@code Asking.requireHandle}, and a contained
 * principal holds no credential, so <strong>a running child cannot start a grandchild on its own
 * initiative at all.</strong> The subtree hop below is therefore driven by an authenticated
 * ancestor naming the child's contained conversation, which is the only caller shape that exists.
 * That is a confinement fact and also a capability limitation; it is stated here and in the task
 * report rather than asserted, because it is the absence of a door rather than a refusal at one.
 *
 * <h2>No database</h2>
 *
 * <p>Per the database test policy: every repository is a mock or a small in-memory fake, and
 * nothing asserted here is PostgreSQL behaviour. The definition resolver, the tool registry, the
 * job runtime, {@code Hub} and {@code ContainedDeclarations} are all real.
 */
class ContainedRuntimeReachTest {
  @TempDir Path directory;

  // ---------------------------------------------------------------------------------------------
  // Shared names. A contained project's name is opaque and server-generated; it is spelled through
  // ContainedProjects.PREFIX rather than as a literal so the fixture cannot drift from the runtime.
  // ---------------------------------------------------------------------------------------------

  static final String PREFIX = io.aeyer.plowshare.protocol.ContainedProjects.PREFIX;
  static final String CONTAINER = "parent";

  final String childProject = PREFIX + UUID.randomUUID();
  final String siblingProject = PREFIX + UUID.randomUUID();
  final String grandchildProject = PREFIX + UUID.randomUUID();

  static final long CONTAINER_ID = 7L;
  static final long CHILD_ID = 8L;
  static final long SIBLING_ID = 9L;

  /** The container's own executor: the one account every hop below keeps, per ADR 0025. */
  static final String EXECUTOR = "@service/parent";

  /**
   * The built-in tools this fixture's {@link JobRuntime} genuinely serves, and therefore the whole
   * of what the child's model is offered. {@code agent_run}, {@code result_read} and {@code
   * result_list} are the three {@code JobRuntime.knownTools()} offers from its own wiring with no
   * tool list at all; {@code get_date} is a real {@link GetDateTool} instance handed to it, so the
   * set is derived from real tool objects through production's own derivation rather than named by
   * this fixture.
   *
   * <p><strong>The bound on what that can prove is stated at {@link
   * #aRunningChildCannotDefineASchedule}</strong>, because it is a real one.
   */
  static final List<String> SERVED_TOOLS =
      List.of("agent_run", "get_date", "result_list", "result_read");

  /** The same names as a definition's {@code tools:} list. */
  static final String SERVED_TOOLS_DECLARATION = "[" + String.join(", ", SERVED_TOOLS) + "]";

  // =============================================================================================
  // Fixture A: real definition resolution and a real job runtime, for agent_run.
  // =============================================================================================

  /**
   * Resolution as production performs it: one {@link DefinitionResolver} over per-project resource
   * roots, behind {@link Callers}, behind a {@link JobRuntime} whose model is scripted.
   *
   * <p>No stub stands between the delegating run and the registry its {@code Home} resolves, which
   * is the whole point — the fence under test <em>is</em> that resolution.
   */
  final class Delegation {
    final ProjectMembers members = mock(ProjectMembers.class);
    final SessionRegistry sessions = mock(SessionRegistry.class);
    final ProjectStore projects = mock(ProjectStore.class);
    final AgentRegistry boot = new AgentRegistry(Map.of());
    final DefinitionResolver resolver;
    final Callers callers;

    Delegation() {
      try {
        when(projects.id(CONTAINER)).thenReturn(CONTAINER_ID);
        when(projects.id(childProject)).thenReturn(CHILD_ID);
        when(projects.id(siblingProject)).thenReturn(SIBLING_ID);
        // Permissive on purpose. A refusal below must be the name failing to resolve; if
        // membership refused instead, the assertions would pass for the wrong reason.
        when(members.mayUse(anyString(), anyString())).thenReturn(true);
        when(members.mayWork(anyString(), anyString())).thenReturn(true);

        // The container and the sibling each hold a delegable helper of their own, under distinct
        // names so that a child's refusal to reach one cannot be mistaken for its refusal to
        // reach the other.
        definition(
            CONTAINER,
            "boss",
            "tools: [agent_run]\ncalls: [container_helper]\n",
            "caller container");
        definition(CONTAINER, "container_helper", "exported: false\n", "helper container");
        definition(
            siblingProject,
            "boss",
            "tools: [agent_run]\ncalls: [sibling_helper]\n",
            "sibling boss");
        definition(siblingProject, "sibling_helper", "exported: false\n", "helper sibling");
        // The child declares an edge to its own helper AND to each of the two it cannot see. The
        // own edge is what keeps agent_run offered at all, so the model really does get to name
        // the outward ones and the refusal is the lookup rather than a tool that was never there.
        definition(
            childProject,
            "worker",
            "tools: "
                + SERVED_TOOLS_DECLARATION
                + "\ncalls: [own_helper, container_helper, sibling_helper]\n",
            "child worker");
        definition(childProject, "own_helper", "exported: false\n", "helper child");

        resolver =
            new DefinitionResolver(
                boot,
                new DataLayout(null),
                id -> id == CONTAINER_ID || id == CHILD_ID || id == SIBLING_ID,
                Set.copyOf(SERVED_TOOLS),
                Set.of(),
                mock(SessionChannel.class),
                session -> false,
                (id, session) -> false,
                DefinitionChecks.NONE);
        resolver.useApplicationResources(
            id ->
                id == null
                    ? Optional.empty()
                    : id == CONTAINER_ID
                        ? Optional.of(workspace(CONTAINER))
                        : id == CHILD_ID
                            ? Optional.of(workspace(childProject))
                            : id == SIBLING_ID
                                ? Optional.of(workspace(siblingProject))
                                : Optional.empty());
        callers =
            new Callers(resolver, projects, mock(Turn.class), new CallerAccess(sessions, members));
      } catch (java.io.IOException unavailable) {
        throw new java.io.UncheckedIOException(unavailable);
      }
    }

    Path workspace(String project) {
      return directory.resolve("definitions").resolve(project.replace(':', '-'));
    }

    void definition(String project, String name, String extra, String prompt)
        throws java.io.IOException {
      Path agents = Files.createDirectories(workspace(project).resolve("agents"));
      Files.writeString(
          agents.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: description "
              + prompt
              + "\nmodel: fast\nmax-turns: 4\nmax-model-calls: 8\n"
              + extra
              + "---\n"
              + prompt);
    }

    /**
     * What the loader dropped for this project, keyed {@code "caller -> callee"} with its reason.
     */
    Map<String, String> refusals(long project) {
      return resolver.refusalsFor(new DefinitionResolver.Caller(project, null, EXECUTOR));
    }

    /** Runs {@code agent} in {@code project}'s home and returns what the scripted model saw. */
    Scripted delegate(String project, String agent, String wanted) {
      var transport = new Scripted();
      transport.wanted = wanted;
      var dispatcher =
          new LlmDispatcher(
              List.of(
                  new LlmPool(
                      "scripted",
                      List.of("model"),
                      Map.of("fast", "model"),
                      4,
                      1,
                      Duration.ofSeconds(5),
                      transport)),
              new NoOpTokenLedger());
      var runtime =
          new JobRuntime(
              dispatcher,
              List.of(new GetDateTool(Instant::now, java.time.ZoneId.of("UTC"))),
              () -> boot,
              null,
              Instant::now,
              Reminding.NONE,
              ImageStore.NONE,
              RefusalDetector.PHRASES,
              System::nanoTime,
              callers);
      var home = Home.of(project);
      var caller = callers.visible(home, null, EXECUTOR).get(agent);
      runtime.run(
          caller,
          "investigate",
          home,
          Budget.of(8),
          () -> false,
          null,
          JobWatch.UNWATCHED,
          Transcript.NONE,
          TurnCap.none(),
          List.of(),
          EXECUTOR);
      return transport;
    }
  }

  // =============================================================================================
  // Fixture B: a real ApplicationToolRegistry over two project roots.
  // =============================================================================================

  /**
   * The container binds a tool; the child's package binds nothing. Both roots are real files read
   * by the real registry, so the question asked is the production one: what does the child's home
   * resolve?
   */
  final class Bindings {
    final ProjectWorkspaces workspaces = mock(ProjectWorkspaces.class);
    final ProjectMembers members = mock(ProjectMembers.class);
    final ProjectNames names = mock(ProjectNames.class);
    final RelayLogRepository logs = mock(RelayLogRepository.class);
    final ApplicationToolRegistry registry;
    final Path containerRoot;
    final Path childRoot;

    Bindings() {
      try {
        containerRoot = Files.createDirectories(directory.resolve("bindings/container/server"));
        childRoot = Files.createDirectories(directory.resolve("bindings/child/server"));
        Files.writeString(
            containerRoot.resolve("tools.json"),
            """
            {"version":1,"bindings":[{"provider":"scanner","account":"provider","name":"network_scope","description":"Scope","parameters":[],"timeoutSeconds":30}],
             "providers":[]}
            """);
        Files.writeString(
            containerRoot.getParent().resolve("plowshare.json"),
            """
            {"executionAccount":"provider","toolScopes":[{"scope":"scans","provider":"scanner","grants":["*"]}],
             "toolGrants":[{"toolScope":"scans","agent":"worker"}]}
            """);
        // The child's manifest carries the SAME tool scope policy as its container, and no
        // bindings. So the child is not refused for want of a policy: the binding is simply not
        // in the set its own project resolves. That is the distinction this test exists to make.
        Files.writeString(
            childRoot.getParent().resolve("plowshare.json"),
            """
            {"executionAccount":"provider","toolScopes":[{"scope":"scans","provider":"scanner","grants":["*"]}],
             "toolGrants":[{"toolScope":"scans","agent":"worker"}]}
            """);
        when(workspaces.id(CONTAINER)).thenReturn(CONTAINER_ID);
        when(workspaces.id(childProject)).thenReturn(CHILD_ID);
        when(names.nameForId(CONTAINER_ID)).thenReturn(Optional.of(CONTAINER));
        when(names.nameForId(CHILD_ID)).thenReturn(Optional.of(childProject));
        when(workspaces.personalOwner(anyString())).thenReturn(Optional.empty());
        // Permissive again: a child that resolved nothing because membership refused would prove
        // nothing about resolution.
        when(workspaces.effectiveExclusions(any())).thenReturn(List.of());
        when(members.mayWork(anyString(), anyString())).thenReturn(true);
        when(logs.latest(any()))
            .thenReturn(new RelayLogRepository.Head(0, Optional.<Relay.Publication>empty()));
        registry =
            new ApplicationToolRegistry(
                id ->
                    id == CONTAINER_ID
                        ? Optional.of(containerRoot.getParent())
                        : id == CHILD_ID ? Optional.of(childRoot.getParent()) : Optional.empty(),
                workspaces,
                members,
                names,
                logs,
                new RelayToolProperties(),
                mock(RelayToolInvocations.class),
                new ToolGrants() {
                  @Override
                  public boolean permits(Long p, String a, String n, String s, String t) {
                    return true;
                  }

                  @Override
                  public boolean acceptsDynamic(Long p, String a, String n, String s) {
                    return true;
                  }
                },
                () -> Set.of("memory_read"),
                Instant::now);
      } catch (java.io.IOException unavailable) {
        throw new java.io.UncheckedIOException(unavailable);
      }
    }

    List<String> callable(String project) {
      return registry.tools(Home.of(project), "worker", null, () -> false, "provider").stream()
          .map(tool -> tool.schema().name())
          .sorted()
          .toList();
    }
  }

  // =============================================================================================
  // Fixture C: the containment hop, as SourceContainedApplications performs it.
  // =============================================================================================

  /**
   * Container -> child -> grandchild, with {@code Runs} mocked so the captured {@link Runs.Ask} is
   * the subject: what the service decides to ask of the run service is exactly the identity
   * question this task is about.
   */
  final class Containment {
    final ContainedApplicationRepository records = mock(ContainedApplicationRepository.class);
    final ProjectWorkspaces workspaces = mock(ProjectWorkspaces.class);
    final OperationAuthority authority = mock(OperationAuthority.class);
    final IdentityRepository identities = mock(IdentityRepository.class);
    final ContainedPrincipals principals = mock(ContainedPrincipals.class);
    final Runs runs = mock(Runs.class);
    final Pools allowances = new Pools();

    final AccountId account = new AccountId(UUID.randomUUID());
    final GrantRecipient recipient = GrantRecipient.account(account);
    final ScopeId containerScope = new ScopeId(UUID.randomUUID());
    final AllowanceId containerBudget = new AllowanceId(UUID.randomUUID());

    final UUID childId = UUID.randomUUID();
    final ScopeId childScope = new ScopeId(UUID.randomUUID());
    final AllowanceId childAllowance = new AllowanceId(UUID.randomUUID());
    final UUID childRevision = UUID.randomUUID();

    final UUID grandchildId = UUID.randomUUID();
    final ScopeId grandchildScope = new ScopeId(UUID.randomUUID());
    final AllowanceId grandchildAllowance = new AllowanceId(UUID.randomUUID());
    final UUID grandchildRevision = UUID.randomUUID();

    final Path root;
    final Path containerWorkspace;
    final SourceContainedApplications service;

    /**
     * The container's admission: four derivable actions. The child's, below, deliberately holds one
     * fewer, so that what {@code contain} is handed for the grandchild can be told apart from what
     * it would have been handed had the hop read the container's authority instead.
     */
    final OperationAuthority.Admission containerAdmission =
        admission(
            containerScope,
            Home.of(CONTAINER),
            AuthorityAction.READ,
            AuthorityAction.WRITE,
            AuthorityAction.RUN_MODEL,
            AuthorityAction.CONVERSATION_CREATE);

    final OperationAuthority.Admission childAdmission =
        admission(
            childScope,
            Home.of(childProject),
            AuthorityAction.READ,
            AuthorityAction.RUN_MODEL,
            AuthorityAction.CONVERSATION_CREATE);

    OperationAuthority.Admission admission(ScopeId scope, Home home, AuthorityAction... actions) {
      List<OperationAuthority.Support> support = new ArrayList<>();
      for (AuthorityAction action : actions)
        support.add(new OperationAuthority.Support(action, UUID.randomUUID()));
      return new OperationAuthority.Admission(
          UUID.randomUUID(), EXECUTOR, account, scope, home, recipient, List.copyOf(support));
    }

    Containment() {
      try {
        root = Files.createDirectories(directory.resolve("containment"));
        containerWorkspace = Files.createDirectories(directory.resolve("container"));
        Files.writeString(
            containerWorkspace.resolve("plowshare.json"),
            "{\"version\":1,\"name\":\"parent\",\"nesting\":true}");
        allowances.retain(new AllowanceSnapshot(containerBudget, 100, 10));
        allowances.retain(new AllowanceSnapshot(childAllowance, 20, 0));
        allowances.retain(new AllowanceSnapshot(grandchildAllowance, 5, 0));
        when(workspaces.find(CONTAINER))
            .thenReturn(
                Optional.of(
                    new ProjectRecord(
                        CONTAINER,
                        containerWorkspace,
                        List.of(),
                        List.of(),
                        "MANAGED",
                        List.of())));
        when(workspaces.effectiveExclusions(any())).thenReturn(List.of());
        // Two live conversations: the container's own, and the child's contained one. The second
        // is the caller for the subtree hop -- a contained conversation whose admission executor
        // is still the container's, because a hop keeps it (ADR 0025).
        when(authority.conversation("c-container"))
            .thenReturn(
                Optional.of(
                    new OperationAuthority.Conversation(
                        "c-container",
                        "worker",
                        containerAdmission,
                        null,
                        containerBudget,
                        "0".repeat(64))));
        when(authority.conversation("c-child"))
            .thenReturn(
                Optional.of(
                    new OperationAuthority.Conversation(
                        "c-child",
                        "worker",
                        childAdmission,
                        null,
                        childAllowance,
                        "0".repeat(64))));
        when(records.find(childId)).thenReturn(Optional.of(child()));
        when(records.find(grandchildId)).thenReturn(Optional.of(grandchild()));
        when(identities.containedHome(any(), any()))
            .thenAnswer(
                call -> {
                  AuthorityScope.Contained scope = call.getArgument(0);
                  return Home.of(scope.id().equals(childScope) ? childProject : grandchildProject);
                });
        when(principals.issue(any()))
            .thenAnswer(
                call -> {
                  AuthorityScope.Contained scope = call.getArgument(0);
                  return new ContainedPrincipals.ContainedPrincipal(UUID.randomUUID(), scope.id());
                });
        // The hop as PinnedOperationAuthority performs it: the asked scope and its home, with the
        // caller's executor and account carried through unchanged.
        when(authority.contain(any(), any(), any()))
            .thenAnswer(
                call -> {
                  OperationAuthority.Admission caller = call.getArgument(0);
                  AuthorityScope.Contained scope = call.getArgument(1);
                  @SuppressWarnings("unchecked")
                  Set<AuthorityAction> actions = call.getArgument(2);
                  List<OperationAuthority.Support> support = new ArrayList<>();
                  for (AuthorityAction action : actions)
                    support.add(new OperationAuthority.Support(action, UUID.randomUUID()));
                  return new OperationAuthority.Admission(
                      UUID.randomUUID(),
                      caller.executor(),
                      caller.account(),
                      scope.id(),
                      Home.of(scope.id().equals(childScope) ? childProject : grandchildProject),
                      caller.source(),
                      List.copyOf(support));
                });
        // The nearest enclosing uncontained home is the container's project, at either depth.
        when(authority.mediatingHome(any())).thenReturn(Home.of(CONTAINER));
        when(runs.start(any(), any()))
            .thenReturn(new Runs.Started("job-1", "worker", "run-conversation"));
        stage(childProject, childRevision);
        stage(grandchildProject, grandchildRevision);
        service =
            new SourceContainedApplications(
                records,
                workspaces,
                mock(ApplicationPackageValidator.class),
                authority,
                identities,
                principals,
                allowances,
                new Immediate(),
                new ContainmentConfig(root.toString(), 3, 4, null),
                new InProcessContainedStaging(),
                runs);
      } catch (java.io.IOException unavailable) {
        throw new java.io.UncheckedIOException(unavailable);
      }
    }

    Path area(String project) {
      return root.resolve(project.substring(PREFIX.length()));
    }

    void stage(String project, UUID revision) throws java.io.IOException {
      Path staged = area(project).resolve("revisions").resolve(revision.toString());
      Files.createDirectories(staged.resolve("agents"));
      Files.writeString(
          staged.resolve("plowshare.json"),
          "{\"version\":1,\"name\":\"" + project + "\",\"nesting\":true}");
      Files.writeString(
          staged.resolve("agents").resolve("worker.md"), "---\nname: worker\n---\nWork.\n");
    }

    ContainedApplicationRepository.Retained child() {
      return new ContainedApplicationRepository.Retained(
          childId,
          childScope,
          123L,
          childProject,
          containerScope,
          UUID.randomUUID(),
          "a".repeat(64),
          childRevision,
          childAllowance,
          20,
          true);
    }

    ContainedApplicationRepository.Retained grandchild() {
      return new ContainedApplicationRepository.Retained(
          grandchildId,
          grandchildScope,
          124L,
          grandchildProject,
          childScope,
          UUID.randomUUID(),
          "b".repeat(64),
          grandchildRevision,
          grandchildAllowance,
          5,
          true);
    }

    Runs.Ask asked() {
      var captured = ArgumentCaptor.forClass(Runs.Ask.class);
      verify(runs).start(captured.capture(), any());
      return captured.getValue();
    }
  }

  /** Real spends and ceilings, as the other contained tests use. */
  static final class Pools implements AllowanceRepository {
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

  static final class Immediate implements UnitOfWork {
    @Override
    public <T> T inTransaction(java.util.function.Supplier<T> body) {
      return body.get();
    }
  }

  // =============================================================================================
  // The definitions agent_run can name.
  // =============================================================================================

  /**
   * An ancestor's definition is not nameable from inside the subtree, and the refusal is the name
   * failing to resolve rather than a permission denying it.
   *
   * <p>{@code agent_run} has no {@code project} field: a delegate is looked up in the {@code Home}
   * the run was started in, and {@code Callers.find} builds that home's registry from that
   * project's own resource root. So the fence is two-layered, and both layers are resolution:
   *
   * <ol>
   *   <li><b>At definition load.</b> The child's {@code worker} declares {@code calls:
   *       [container_helper]}, and the loader <em>withholds</em> that edge because nothing in the
   *       child's project defines the name. The withheld edge names its own reason, which this
   *       asserts through {@code DefinitionResolver.refusalsFor} — a declaration honoured and found
   *       unresolvable, not a declaration denied.
   *   <li><b>At call time.</b> The child keeps one resolvable edge, so {@code agent_run} really is
   *       offered and the model really does get to name the outward one. It is refused, and the
   *       refusal offers the set that did resolve.
   * </ol>
   *
   * <p>Asserted negatively too: the result is <em>not</em> an {@code E_NO_ACCESS} denial. The two
   * are different facts and this run got the first.
   */
  @Test
  void aRunningChildCannotDelegateToAnAncestorsDefinition() {
    var fixture = new Delegation();

    // LAYER ONE, at resolution. The declaration is read and the edge is dropped for want of a
    // definition, and the loader says so in those words -- "no file in this directory defines
    // it". That sentence is the whole claim of this test: the child's reach ends where its own
    // project's definitions end, and what ends it is a lookup rather than a grant.
    var childRegistry = fixture.callers.visible(Home.of(childProject), null, EXECUTOR);
    assertEquals(Set.of("own_helper", "worker"), childRegistry.names());
    var refusals = fixture.refusals(CHILD_ID);
    assertTrue(
        refusals.getOrDefault("worker -> container_helper", "").contains("no file in this"),
        "the outward edge must be dropped at resolution, got: " + refusals);
    assertFalse(
        refusals.containsKey("worker -> own_helper"),
        "the child's own edge survives, so this is not a blanket drop: " + refusals);

    // LAYER TWO, at call time.
    var child = fixture.delegate(childProject, "worker", "container_helper");
    String refusal = child.toolResult();
    assertFalse(
        refusal.startsWith("E_NO_ACCESS:"),
        "an outward reach must fail to resolve, not be denied a permission: " + refusal);
    assertTrue(
        refusal.contains("container_helper"), "the refusal names what was asked for: " + refusal);
    assertTrue(refusal.contains("own_helper"), "and offers what did resolve: " + refusal);
    // Nothing of the container's travelled inward: not the definition and not its prompt.
    assertTrue(
        child.requests.stream()
            .flatMap(List::stream)
            .noneMatch(message -> message.content().contains("helper container")),
        "an ancestor's definition body must not reach a contained run");

    // CONTROL. The very same name and the same account, differing only in whose home the run was
    // started in, resolves and runs to an answer. So the refusals above are the home, and not a
    // definition that was never installed or that could not have been delegated to.
    var container = new Delegation();
    assertTrue(
        container
            .callers
            .visible(Home.of(CONTAINER), null, EXECUTOR)
            .find("container_helper")
            .isPresent(),
        "the same name resolves in the ancestor's own home");
    assertEquals(
        Map.of(),
        container.refusals(CONTAINER_ID),
        "and the identical declaration is dropped for nobody there");
    var ran = container.delegate(CONTAINER, "boss", "container_helper");
    assertEquals(3, ran.requests.size(), "the container's own delegation ran a child");
    assertTrue(
        ran.requests.get(1).getFirst().content().contains("helper container"),
        "the ancestor's helper really is delegable in the ancestor's own home");
  }

  /**
   * The same, sideways. A sibling contained Application's definition is not resolvable either, and
   * the sibling's own run proves the definition is there and delegable.
   */
  @Test
  void aRunningChildCannotDelegateToASiblingsDefinition() {
    var fixture = new Delegation();

    fixture.callers.visible(Home.of(childProject), null, EXECUTOR);
    var refusals = fixture.refusals(CHILD_ID);
    assertTrue(
        refusals.getOrDefault("worker -> sibling_helper", "").contains("no file in this"),
        "the sideways edge must be dropped at resolution, got: " + refusals);

    var child = fixture.delegate(childProject, "worker", "sibling_helper");
    String refusal = child.toolResult();
    assertFalse(
        refusal.startsWith("E_NO_ACCESS:"),
        "a sideways reach must fail to resolve, not be denied a permission: " + refusal);
    assertTrue(
        refusal.contains("sibling_helper"), "the refusal names what was asked for: " + refusal);
    assertTrue(
        refusal.contains("own_helper"),
        "and offers the set that did resolve, which excludes it: " + refusal);
    assertTrue(
        child.requests.stream()
            .flatMap(List::stream)
            .noneMatch(message -> message.content().contains("helper sibling")),
        "a sibling's definition body must not reach a contained run");

    // CONTROL. The sibling's own run resolves its own helper and delegates into it. Both
    // contained projects exist in this fixture, both are readable, and the one entitled to see
    // that definition does.
    var sibling = new Delegation();
    assertTrue(
        sibling
            .callers
            .visible(Home.of(siblingProject), null, EXECUTOR)
            .find("sibling_helper")
            .isPresent());
    assertEquals(Map.of(), sibling.refusals(SIBLING_ID));
    var ran = sibling.delegate(siblingProject, "boss", "sibling_helper");
    assertEquals(3, ran.requests.size());
    assertTrue(ran.requests.get(1).getFirst().content().contains("helper sibling"));
  }

  // =============================================================================================
  // The containment hop.
  // =============================================================================================

  /**
   * A child's own grandchild resolves, and the started run's execution identity is the
   * <em>grandchild's</em> — not the child's and not the container's.
   *
   * <p>ADR 0011 inherits execution identity downward across a delegation hop. ADR 0042 departs from
   * that deliberately: a contained child's actions are "neither attributable to nor exercisable as
   * the parent". So this asserts the three identity axes separately, because they are deliberately
   * three different answers and a test that checked one would read as checking all of them:
   *
   * <ol>
   *   <li><b>Scope and principal: the grandchild's.</b> {@code contain} runs again for this run, so
   *       the admission it produces is the grandchild's scope and home, and the principal issued is
   *       issued for that scope. Neither is the child's and neither is the container's.
   *   <li><b>Author: the grandchild's.</b> {@code Speaker.contained(grandchild)} travels into the
   *       log, so a container reading the work back can tell whose it is — child output being
   *       untrusted input to its container, the author field is the only thing that says so.
   *   <li><b>Accounting account and executor: the container's, and that is correct.</b> A contained
   *       principal is a bare UUID that is never an {@code authority_accounts} subject (V218), so
   *       anything needing an account takes the container's, exactly as Task 3 established. This is
   *       not inherited execution identity granting authority a parent lacks: the account is the
   *       one the whole subtree projects from, and the <em>actions</em> handed to {@code contain}
   *       are narrowed from the child's own admission, which this test pins by giving the child one
   *       fewer derivable action than its container and asserting the grandchild got the child's
   *       set.
   * </ol>
   *
   * <p>The caller is an authenticated ancestor naming the child's contained conversation, because
   * nothing else can be: {@code application.child.run} is a frame behind {@code requireHandle} and
   * a contained principal holds no credential. That is itself a confinement fact — a running child
   * cannot start a grandchild on its own initiative — and it is recorded in the task report as a
   * limitation rather than asserted here, since it is an absence of a door rather than a refusal.
   */
  @Test
  void aRunningChildDelegatesWithinItsOwnSubtree() {
    var fixture = new Containment();

    var running =
        fixture.service.run(
            EXECUTOR,
            new ContainedApplications.Run(
                "c-child", fixture.grandchildId, "worker", "Summarise the notes", 2));
    assertEquals(fixture.grandchildId, running.child());

    var ask = fixture.asked();
    assertEquals(grandchildProject, ask.project(), "the run lands in the grandchild's own home");

    // 1. SCOPE AND PRINCIPAL: the grandchild's.
    assertEquals(fixture.grandchildScope, ask.contained().admission().scope());
    assertNotEquals(fixture.childScope, ask.contained().admission().scope());
    assertNotEquals(fixture.containerScope, ask.contained().admission().scope());
    var issued = ArgumentCaptor.forClass(AuthorityScope.Contained.class);
    verify(fixture.principals).issue(issued.capture());
    assertEquals(
        fixture.grandchildScope,
        issued.getValue().id(),
        "the principal this run carries is the grandchild's own, issued for its scope");
    assertEquals(124L, issued.getValue().projectId(), "and its own project row");
    assertEquals(
        fixture.childScope, issued.getValue().container(), "contained by the child, one hop up");

    // 2. AUTHOR: the grandchild's, and demonstrably not either ancestor's.
    assertEquals(Speaker.Kind.HARNESS, ask.speaker().kind());
    assertEquals("contained " + fixture.grandchildId, ask.speaker().name());
    assertFalse(ask.speaker().name().contains(fixture.childId.toString()));
    assertFalse(ask.speaker().name().contains(CONTAINER));

    // 3. ACCOUNT: the container's, by design; and the ACTIONS narrowed from the child's.
    assertEquals(EXECUTOR, ask.callerHandle());
    assertEquals(fixture.account, ask.contained().admission().account());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Set<AuthorityAction>> narrowed = ArgumentCaptor.forClass(Set.class);
    verify(fixture.authority).contain(any(), any(), narrowed.capture());
    assertEquals(
        Set.of(
            AuthorityAction.READ, AuthorityAction.RUN_MODEL, AuthorityAction.CONVERSATION_CREATE),
        narrowed.getValue(),
        "the hop confines the child's own actions, never the container's wider set");
    assertFalse(
        narrowed.getValue().contains(AuthorityAction.WRITE),
        "an action the child does not hold cannot appear below it");

    // THE POOL: the grandchild's own allowance, under the ceiling its container set.
    assertEquals(fixture.grandchildAllowance.value(), ask.contained().budget().identity());
    assertEquals(5, ask.contained().budget().limit());
    assertNotEquals(fixture.childAllowance.value(), ask.contained().budget().identity());

    // WORK AUTHORITY: still the nearest enclosing uncontained project, two hops up.
    assertEquals(CONTAINER, ask.contained().workProject());

    // CONTROL, and the one that makes the author assertion about the hop rather than a constant:
    // the same service, the same executor, one level up names the CHILD, and the author changes.
    var other = new Containment();
    other.service.run(
        EXECUTOR, new ContainedApplications.Run("c-container", other.childId, "worker", "Work", 2));
    assertEquals("contained " + other.childId, other.asked().speaker().name());
    assertEquals(childProject, other.asked().project());
  }

  // =============================================================================================
  // The tool bindings a call resolves against.
  // =============================================================================================

  /**
   * A tool binding the container holds and the child never declared is not callable by the child,
   * because it is not in the set the child's home resolves at all.
   *
   * <p>The fence is {@code ApplicationToolRegistry}'s snapshot, which is built from the project's
   * own {@code server/tools.json} under its own resource root. Nothing here is a check on the
   * caller: the child's manifest carries the <em>same</em> execution account, tool scope and tool
   * grant as its container, so a policy check would pass. The binding is absent.
   */
  @Test
  void aRunningChildsToolCallResolvesOnlyItsOwnBindings() {
    var fixture = new Bindings();

    assertEquals(List.of(), fixture.callable(childProject), "the child resolves no binding");
    assertEquals(Set.of(), fixture.registry.names(CHILD_ID));

    // CONTROL. The identical call on the container's home, with the same account and the same
    // agent name, resolves the binding -- so the absence above is the project and not a fixture
    // that never installed a binding, nor a grant or membership that refused.
    assertEquals(List.of("network_scope"), fixture.callable(CONTAINER));
    assertEquals(Set.of("network_scope"), fixture.registry.names(CONTAINER_ID));
    verify(fixture.members, atLeastOnce()).mayWork(childProject, "provider");
  }

  // =============================================================================================
  // What ordinary project creation provisions, and a run does not.
  // =============================================================================================

  /**
   * Starting work inside a child provisions neither a working tree nor a bare Git hub.
   *
   * <p>Ordinary project creation provisions both per {@code docs/personal-space.md}. A child's
   * project row is {@code MANAGED} with no machine and no personal owner, which is not a union
   * candidate — {@code ContainedAddressabilityDatabaseTest
   * .creatingAChildProvisionsNoWorkingTreeOrGitHub} pins that SQL predicate at rest. This is the
   * runtime half: a started run is not the second door onto the same provisioning.
   */
  @Test
  void aRunningChildAcquiresNoWorkingTreeOrGitHub() throws Exception {
    var fixture = new Containment();

    fixture.service.run(
        EXECUTOR,
        new ContainedApplications.Run("c-container", fixture.childId, "worker", "Work", 2));
    verify(fixture.runs).start(any(), any());

    // The child's source area: the one directory this capability allocates and the service's own
    // path, derived exactly as the service derives it from the opaque project name.
    Path childArea = fixture.area(childProject);
    assertTrue(Files.isDirectory(childArea), "the service really does own this directory");
    assertFalse(new Hub(childArea).exists(), "and holds no bare hub in it");
    assertFalse(Files.exists(childArea.resolve("sync.git")));
    assertFalse(Files.exists(childArea.resolve("tree")));
    assertEquals(
        List.of("revisions"),
        listing(childArea),
        "the staged revision is the whole of what a started child's area holds");

    // CONTROL, over a real project workspace in the same fixture and through the same class the
    // server provisions with. So the absence above is that nothing asked -- not that Hub cannot
    // provision here, and not that the asserted path is the wrong one.
    Path containerArea = fixture.containerWorkspace;
    Hub containerHub = new Hub(containerArea);
    assertFalse(containerHub.exists());
    containerHub.create();
    assertTrue(containerHub.exists(), "provisioning is reachable in this fixture");
    assertTrue(Files.isDirectory(containerHub.tree()));
    assertTrue(Files.exists(containerArea.resolve("sync.git")));
    // And the child's area is unchanged by it, so one did not provision the other.
    assertEquals(List.of("revisions"), listing(childArea));
  }

  /** The immediate entries of a directory, sorted. */
  static List<String> listing(Path directory) throws java.io.IOException {
    try (var entries = Files.list(directory)) {
      return entries.map(entry -> entry.getFileName().toString()).sorted().toList();
    }
  }

  // =============================================================================================
  // The resources a built-in read tool can name: the audience-driven reach.
  // =============================================================================================

  /**
   * A real {@link PinnedOperationAuthority}, so the resource pin under test is production's and not
   * a stub. Shaped on {@code ContainmentHopTest}'s fixture, which is where the hop itself is
   * pinned.
   */
  final class Reads {
    final IdentityRepository identities = mock(IdentityRepository.class);
    final ScopedGrantRepository grants = mock(ScopedGrantRepository.class);
    final ScopedAuthority scoped = mock(ScopedAuthority.class);
    final ProjectMembers members = mock(ProjectMembers.class);
    final OperationRecords records = mock(OperationRecords.class);
    final ResourceScopeRepository resources = mock(ResourceScopeRepository.class);

    final AccountId account = new AccountId(UUID.randomUUID());
    final GrantRecipient recipient = GrantRecipient.account(account);
    final UUID grant = UUID.randomUUID();
    final AuthorityScope.Project container =
        new AuthorityScope.Project(new ScopeId(UUID.randomUUID()), 1);
    final AuthorityScope.Contained child =
        new AuthorityScope.Contained(new ScopeId(UUID.randomUUID()), 2, container.id());
    final Home childHome = Home.of(childProject);
    final PinnedOperationAuthority policy;

    Reads() {
      policy =
          new PinnedOperationAuthority(
              identities,
              grants,
              scoped,
              members,
              records,
              resources,
              AccountKnowledgePermissions.DENY,
              new ContainmentBounds(3, 4));
      when(identities.account("alice"))
          .thenReturn(
              new IdentityRepository.Account(
                  account, "alice", IdentityRepository.AccountKind.USER, false));
      when(identities.project(CONTAINER)).thenReturn(container);
      when(identities.home(container)).thenReturn(Home.of(CONTAINER));
      when(identities.scope(container.id())).thenReturn(container);
      when(identities.containedHome(child, container.id())).thenReturn(childHome);
      when(identities.contained(child.id())).thenReturn(Optional.of(child));
      when(members.authorityAccount("alice")).thenReturn(Optional.of("alice"));
      when(members.role(CONTAINER, "alice")).thenReturn(Optional.of(ProjectRole.CONTRIBUTOR));
      var binding = mock(ScopedGrantRepository.Binding.class);
      when(binding.id()).thenReturn(grant);
      when(grants.live(recipient)).thenReturn(List.of(binding));
      for (var action :
          List.of(
              AuthorityAction.READ,
              AuthorityAction.WRITE,
              AuthorityAction.RUN_MODEL,
              AuthorityAction.CONVERSATION_CREATE))
        when(scoped.allowsUsing(recipient, container, action, grant)).thenReturn(true);
    }

    /**
     * The child's own admission, produced by the hop and nothing else.
     *
     * <p>The ancestry edge is then wired back into {@code records}, because {@code require} walks
     * it on every call and a contained admission with no retained parent is refused by design —
     * there would be no evidence any container narrowed to it. {@code contain} records the edge
     * through {@code retainChild}; this makes the fake answer what a real store would.
     */
    OperationAuthority.Admission contained() {
      var parent =
          policy.admit(
              "alice",
              container,
              Set.of(AuthorityAction.READ, AuthorityAction.WRITE, AuthorityAction.RUN_MODEL));
      when(records.find(parent.id())).thenReturn(Optional.of(parent));
      var contained =
          policy.contain(parent, child, Set.of(AuthorityAction.READ, AuthorityAction.WRITE));
      verify(records).retainChild(contained, parent.id());
      when(records.parent(contained.id())).thenReturn(Optional.of(parent.id()));
      return contained;
    }

    /** Says which projects a referenced row belongs to, which is the audience question. */
    void belongsTo(AccessRequest.Resource reference, String... projects) {
      when(resources.projects(reference)).thenReturn(Arrays.asList(projects));
    }
  }

  /**
   * A built-in read tool's resource reference cannot name a row outside the child's own home, and a
   * row that several projects can see is refused rather than admitted on the strength of one of
   * them.
   *
   * <p>This is the anti-goal the spec names in as many words — "any path by which a child obtains
   * authority its parent lacks, including through audience-driven reads the child never names". The
   * contained run's built-in set includes {@code conversation_chat}, {@code conversation_list},
   * {@code memory_read} and {@code result_read}; {@code RetainedToolAccess} turns their arguments
   * into typed {@link AccessRequest.Resource} references, and {@code
   * PinnedOperationAuthority.requireReadTargets} is what pins each one to the admission's own home.
   * A contained admission's <em>action</em> authority is proved against the nearest enclosing
   * uncontained scope, so that home-equality clause is the only thing standing between a child and
   * its container's conversations and memories. It had no test; it has one now.
   *
   * <p>Both directions, because the write path is a separate method with a separate clause, and a
   * child writing into its container's conversation would be worse than reading one.
   */
  @Test
  void aRunningChildReachesNoResourceOutsideItsOwnHome() {
    var fixture = new Reads();
    var admission = fixture.contained();
    assertEquals(fixture.childHome, admission.home(), "the hop produced the child's own home");

    var ownConversation =
        new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "c-own");
    var containersConversation =
        new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "c-container");
    var containersMemory = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_1");
    var shared = new AccessRequest.Resource(ResourceScopeRepository.Kind.CONVERSATION, "c-shared");
    fixture.belongsTo(ownConversation, childProject);
    fixture.belongsTo(containersConversation, CONTAINER);
    fixture.belongsTo(containersMemory, CONTAINER);
    // An audience-driven row: one the child never named, which more than one project can see --
    // and one of those projects IS the child's own home, so admitting it on "my home is in the
    // list" is the exact mistake this clause avoids.
    fixture.belongsTo(shared, childProject, CONTAINER);

    // CONTROL FIRST, so that nothing below can pass for want of a live admission or a stub: the
    // child's own conversation is readable and writable through the very same calls.
    assertDoesNotThrow(
        () ->
            fixture.policy.requireReadTargets(
                admission, fixture.childHome, List.of(ownConversation)));
    assertDoesNotThrow(
        () ->
            fixture.policy.requireTargets(admission, fixture.childHome, List.of(ownConversation)));

    // Outward, on the read path.
    for (var outward : List.of(containersConversation, containersMemory, shared))
      assertEquals(
          "Operation is outside its retained scoped authority",
          assertThrows(
                  CallerFault.class,
                  () ->
                      fixture.policy.requireReadTargets(
                          admission, fixture.childHome, List.of(outward)))
              .getMessage(),
          "a contained read reached " + outward.id());

    // Outward, on the write path.
    for (var outward : List.of(containersConversation, containersMemory, shared))
      assertThrows(
          CallerFault.class,
          () -> fixture.policy.requireTargets(admission, fixture.childHome, List.of(outward)),
          "a contained write reached " + outward.id());

    // A batch is not a loophole: one outward reference refuses the whole list, so a child cannot
    // smuggle one in beside a legal one. memory_read takes up to 256 ids at once.
    assertThrows(
        CallerFault.class,
        () ->
            fixture.policy.requireReadTargets(
                admission, fixture.childHome, List.of(ownConversation, containersMemory)));

    // Nor is naming the container's home instead of its own: the home argument is compared to the
    // admission's, so a child cannot ask the question about somewhere else.
    assertThrows(
        CallerFault.class,
        () ->
            fixture.policy.requireReadTargets(
                admission, Home.of(CONTAINER), List.of(containersConversation)));
    // Including for a row that really is its own -- the home is not an argument it may choose.
    assertThrows(
        CallerFault.class,
        () ->
            fixture.policy.requireReadTargets(
                admission, Home.of(CONTAINER), List.of(ownConversation)));

    // Global memory sits outside every containment subtree, and the hop drops global read
    // unconditionally, so the one null-project escape in requireReadTargets is closed too.
    assertFalse(admission.globalRead());
    var globalMemory = new AccessRequest.Resource(ResourceScopeRepository.Kind.MEMORY, "mem_g");
    when(fixture.resources.projects(globalMemory))
        .thenReturn(java.util.Collections.singletonList(null));
    assertThrows(
        CallerFault.class,
        () ->
            fixture.policy.requireReadTargets(admission, fixture.childHome, List.of(globalMemory)));
  }

  // =============================================================================================
  // Schedules: refused as a declaration, and with no runtime door to become the way in.
  // =============================================================================================

  /**
   * A child cannot define a schedule, by declaration or at runtime.
   *
   * <p>ADR 0042 refuses a declared schedule outright, because ADR 0020 gives one operational
   * authority over the server's own scheduler and nothing in this slice mediates that for a child.
   * This asserts the declaration refusal still holds and that no runtime path stands beside it:
   *
   * <ul>
   *   <li>Every schedule surface is a frame behind {@code Asking.requireHandle}. A contained
   *       principal is a bare UUID with no row in {@code admins}, {@code authority_accounts} or
   *       {@code service_tokens} (V218), so it cannot sign in, holds no handle and reaches no
   *       frame.
   *   <li>No tool the child's model is <em>offered</em> defines a schedule, asserted over the
   *       schemas the runtime actually built for it and not over the relay tool registry. That
   *       distinction matters: {@code ApplicationToolRegistry} holds only an Application's own
   *       relay bindings, so a built-in could never appear in it and an assertion over it would be
   *       unfalsifiable.
   * </ul>
   *
   * <p><strong>The bound on the third leg, stated rather than overclaimed.</strong> The offered set
   * is non-empty and is derived by {@code JobRuntime.knownTools()} from real tool objects, so this
   * is a test and not a tautology. But it is the set <em>this fixture's runtime</em> serves, not
   * every built-in a production deployment wires, because building the real list needs the Spring
   * context {@code AgentsConfig} assembles and {@code RetainedToolAccess.SYNCHRONOUS} is private to
   * {@code agents}. So this leg says "nothing in a contained run's offered surface schedules
   * anything", and it would not by itself catch a scheduling tool added to production's wiring and
   * not to this fixture. The weight of the claim rests on the other two legs, which are a real
   * refusal and a real frame fence; this one closes the tool-shaped door it can see. Carrying it
   * further needs either a test in {@code agents} that can read that set or an accessor on it, and
   * I would rather that be asked for than added.
   * </ul>
   */
  @Test
  void aRunningChildCannotDefineASchedule() {
    // The declared door, still shut. One file under schedules/ is enough; the surface is refused
    // rather than compared, so there is no container declaration that could admit it.
    var child =
        new ContainedDeclarations.Hold(
            Set.of(), Map.of(), Set.of(), Set.of("nightly.json"), Set.of(), Set.of());
    var container =
        new ContainedDeclarations.Hold(
            Set.of(), Map.of(), Set.of(), Set.of("nightly.json"), Set.of(), Set.of());
    assertTrue(
        assertThrows(CallerFault.class, () -> ContainedDeclarations.requireWithin(child, container))
            .getMessage()
            .contains("cannot declare a schedule"),
        "a schedule is refused outright, even one its container declares too");

    // The runtime door: there is none. Asserted over the schemas actually offered to the child's
    // model -- the set a model could emit a call against -- and NOT over the relay tool registry,
    // which holds only Application bindings and in which a built-in could never appear at all.
    //
    // The non-emptiness assertion is what makes this a test rather than a tautology: the child's
    // agent declares every built-in a contained run can be served, the runtime really offers them,
    // and none of them defines a schedule.
    var offered = new Delegation().delegate(childProject, "worker", "own_helper").schemas;
    var names =
        offered.getFirst().stream()
            .map(ToolSchema::name)
            .collect(java.util.stream.Collectors.toSet());
    assertFalse(names.isEmpty(), "the child's model really was offered tools");
    assertEquals(
        Set.copyOf(SERVED_TOOLS),
        names,
        "and was offered exactly the built-ins this runtime serves, got: " + names);
    assertTrue(
        names.stream().noneMatch(name -> name.contains("schedule")),
        "none of which defines a schedule: " + names);

    // The frame door needs a handle, and refuses without one.
    var schedules = mock(ScheduleStore.class);
    var handler = new ScheduleDefineHandler(schedules);
    Map<String, Object> payload =
        Map.of("schedule", "nightly", "cron", "0 0 3 * * *", "emits", "nightly.due");
    var unsigned = new Asking("session", null);
    assertTrue(
        assertThrows(CallerFault.class, () -> handler.handle(payload, unsigned))
            .getMessage()
            .contains(FrameTypes.SCHEDULE_DEFINE),
        "a caller with no account handle cannot define a schedule");
    verifyNoInteractions(schedules);

    // CONTROL. The same payload through the same handler, for an authenticated handle, defines
    // one -- so the refusal above is the missing handle and not a broken fixture or an invalid
    // cron expression.
    when(schedules.define(anyString(), any(), anyString(), anyString(), any()))
        .thenReturn(
            new ScheduleRecord("nightly", "0 0 3 * * *", "UTC", "nightly.due", false, null, "sam"));
    assertNotNull(handler.handle(payload, new Asking("session", "sam")));
    verify(schedules).define(eq("nightly"), any(), eq("nightly.due"), eq("sam"), any());
  }

  // =============================================================================================
  // The scripted model, shared by the delegation tests.
  // =============================================================================================

  /** One delegating call, then an answer. The tool result it saw is what the assertions read. */
  static final class Scripted implements LlmTransport {
    final List<List<ChatMessage>> requests = new ArrayList<>();
    final List<List<ToolSchema>> schemas = new ArrayList<>();
    String wanted = "helper";

    String toolResult() {
      return requests.getLast().stream()
          .filter(message -> message.role() == ChatMessage.Role.TOOL)
          .map(ChatMessage::content)
          .findFirst()
          .orElseThrow();
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      requests.add(List.copyOf(messages));
      schemas.add(List.copyOf(tools));
      if (requests.size() == 1)
        return new Completion(
            "",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(
                new ToolCall(
                    "delegate",
                    "agent_run",
                    "{\"agent\":\"" + wanted + "\",\"task\":\"assess\"}")));
      return new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      var result = complete(model, messages, sampling, tools);
      if (!result.content().isEmpty()) sink.answered(result.content());
      return result;
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }
}
