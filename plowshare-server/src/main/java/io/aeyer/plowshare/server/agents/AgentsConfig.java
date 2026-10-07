package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.protocol.Bubblewrap;
import io.aeyer.plowshare.protocol.CommandIsolation;
import io.aeyer.plowshare.server.agents.curator.Curator;
import io.aeyer.plowshare.server.agents.learner.Learner;
import io.aeyer.plowshare.server.agents.learner.Reminder;
import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.api.ConversationsProperties;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.archive.CompactionStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.JobLog;
import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectRecord;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.archive.PromotionQueue;
import io.aeyer.plowshare.server.archive.ProposalStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.archive.ValidationException;
import io.aeyer.plowshare.server.auth.AuthProperties;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.documents.Deliberation;
import io.aeyer.plowshare.server.documents.DocumentStore;
import io.aeyer.plowshare.server.documents.DocumentsProperties;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.fetch.FetchService;
import io.aeyer.plowshare.server.files.AbsentPresence;
import io.aeyer.plowshare.server.files.CommandIsolationProperties;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.RunProviders;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.files.WorkspaceProperties;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.LlmProperties;
import io.aeyer.plowshare.server.llm.SamplingProfiles;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.search.SearchService;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.session.PresenceRegistry;
import io.aeyer.plowshare.server.session.ProjectRoots;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.Session;
import io.aeyer.plowshare.server.session.SessionRegistry;
import io.aeyer.plowshare.server.union.UnionRouting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the agent layer's beans, and refuses to start on a bad agent set.
 *
 * <p>{@link Scribe}, {@link Curator} and {@link JobRuntime} take no stereotype annotation of their
 * own, on {@link Archive}'s reasoning: they stay framework-free, and whatever wires them supplies
 * the mechanisms they will not name.
 *
 * <h2>The registry and the runtime need each other, and a supplier breaks it</h2>
 *
 * <p>{@code AgentRegistry.load} validates every declared tool name against the exact set <em>this
 * boot</em> binds, so it needs the runtime; the runtime needs the registry so that {@code
 * agent_run} has a graph to delegate over. Neither can be built after the other. {@link JobRuntime}
 * therefore takes a {@code Supplier<AgentRegistry>} rather than a registry, backed here by an
 * {@link ObjectProvider} resolved on the first delegation and not at bean-creation time — which is
 * also what lets a context with no registry at all be a legal, running server.
 *
 * <p>The same provider reaches {@link Scribe} and {@link Curator}, and for the reason Task 8 wired
 * it that way: resolving it here would fix the answer for the life of the context.
 *
 * <h2>The asymmetry, which is the spec's and is deliberate</h2>
 *
 * <p><b>There is no longer a directory that is not there.</b> {@code agentRegistry} always has
 * {@link ClasspathDefinitions} to fall back to, which is inside this jar on every deployment, so
 * the registry bean is defined in every context that imports this configuration and {@code
 * ObjectProvider.getIfAvailable()} answers {@code null} only one step up, in a context that does
 * not import {@link AgentsConfig} at all. That step-up state is not benign in the way "a directory
 * that is not there" used to be, either — it used to mean a registry with nothing configured beyond
 * the shipped set, and {@code scribe} being {@link #REQUIRED} meant that set always included a real
 * scribe. What is one step up now has no registry at all, so {@code Scribe.judge}'s "no agent
 * registry" degradation is what a context that never wired this configuration falls back to —
 * {@code ScribeTest} drives that path directly, and no context that imports {@code AgentsConfig}
 * can reach it any more, because such a context either has a {@code scribe} or fails to boot.
 *
 * <p><b>A definition that is there and wrong stops the boot for {@link #REQUIRED} and reports for
 * everything else</b>, naming the file and the key — a cycle, an unknown tool, an unknown callee, a
 * missing budget, a model no pool serves. Those are mistakes somebody made while watching, and the
 * only other report they would get arrives inside a job, in production, once. {@code
 * AgentRegistry.read} owns the ladder that decides which of the three a given fault costs; the
 * paragraph above was written when it was one rule for everybody, and the loudness it is defending
 * is what survived the trade.
 *
 * <p><b>{@code global/agents/} or {@code global/bots/} existing and being wrong also stops the
 * boot</b>, which is not a third rule but the first applied honestly: {@link FilesystemDefinitions}
 * refuses a path that exists and is not a directory for the same reason {@code AgentsConfig} used
 * to refuse one itself, and {@code agentRegistry}'s own clash check refuses the same name defined
 * in both.
 *
 * <h2>Where the shipped set is read from, and why it is layered rather than substituted</h2>
 *
 * <p>Measured by Task 8, and it is the trap {@link ClasspathDefinitions} exists not to fall into:
 * both source sets publish an {@code agents} directory, so {@code getResource("/agents")} in a test
 * resolves to {@code build/resources/test/agents} — the runtime fixtures — unless read by an exact
 * path rather than the classpath, which is what the tests that validate the shipped set itself do.
 * {@code agentRegistry} layers {@code global/agents/} and {@code global/bots/}, from {@link
 * DataLayout}, over that seed — see the method's own javadoc for the full reasoning, including the
 * deployment that keeps no data directory at all.
 */
@Configuration
@EnableConfigurationProperties({
  AgentsProperties.class, WorkspaceProperties.class,
  // Enabled here as well as in AuthConfig, and that is not a duplicate
  // registration: @EnableConfigurationProperties is idempotent by bean
  // definition name, and this configuration reads plowshare.auth
  // .token-file for the fence below. Declaring the need here is what lets
  // a context that imports this configuration and not AuthConfig -- which
  // is what AgentsConfigTest builds -- start at all. The same holds for
  // plowshare.conversations, which PlowshareServerApplication enables for
  // the whole application and which this configuration reads for the
  // export-directory fence below.
  AuthProperties.class, ConversationsProperties.class,
  // And plowshare.jobs, for the heartbeat interval jobStore is built with
  // below. Enabled for the whole application by
  // PlowshareServerApplication and again here on the same terms as the two
  // above: a context that imports this configuration and not that one --
  // AgentsConfigTest -- has to be able to start.
  JobsProperties.class, CommandIsolationProperties.class
})
public class AgentsConfig {

  /**
   * Composition owns installation paths; an unconfigured installation refuses isolated commands.
   */
  @Bean
  public CommandIsolation commandIsolation(CommandIsolationProperties properties) {
    if (properties.getBubblewrap().isBlank()
        && properties.getLauncher().isBlank()
        && properties.getRuntimeRoots().isEmpty()
        && properties.getScratchRoot().isBlank()) {
      return CommandIsolation.UNAVAILABLE;
    }
    return new Bubblewrap(
        new Bubblewrap.Configuration(
            Path.of(properties.getBubblewrap()),
            Path.of(properties.getLauncher()),
            properties.getRuntimeRoots().stream().map(Path::of).toList(),
            Path.of(properties.getScratchRoot())));
  }

  private static final Logger log = LoggerFactory.getLogger(AgentsConfig.class);

  /**
   * Where a project's workspace and the paths no project may reach live.
   *
   * <p>Wired here rather than annotated, matching {@code ProposalStore} in {@code ArchiveConfig}:
   * it takes constructor arguments a component scan has nothing to supply. <b>It was wired nowhere
   * at all until this task</b>, so the {@code projects} table had a schema, a store and a test
   * suite and no production caller — which is exactly as much file access as a server with no
   * store: none.
   *
   * <p><b>The sampling directory comes from the same property the profiles are read from.</b> One
   * key, so the directory an operator drops profiles into and the directory no workspace may cover
   * cannot disagree; two keys would let a deployment fence off a directory it does not use while
   * leaving the live one writable, which is the escalation with a delay fuse the exclusion exists
   * to stop.
   *
   * <p><b>There is no equivalent entry for agent definitions any more, and that is the fence
   * catching up with where they actually live.</b> Until task 10 this fenced {@code
   * AgentsProperties}' own retired {@code directory} key on the same one-key argument — the
   * directory the registry read from and the directory no workspace could cover had to be the same
   * key, or a deployment could fence off a path it did not use while leaving the live one open.
   * That argument does not generalise to agents once they are loaded from the classpath seed and
   * this server's own {@code data} tree, not from a property naming a standalone directory: {@link
   * DataLayout#root} is what {@code data} is taken from below, and naming the root already fences
   * {@code global/agents/}, {@code global/bots/} and every project's own tier along with it, on
   * {@code ProjectStore}'s own "naming the root is what makes the rule survive a subdirectory being
   * added." {@code ProjectStoreTest.no_workspace_may_reach_the_definitions_directories} is the test
   * that measures exactly that survival, and it needed no production change to pass — the root was
   * already covering it.
   *
   * <p><b>Which is also why the sampling key is not {@code @Live}.</b> This read could move to call
   * time — {@code ProjectStore.effectiveExclusions} recomputes per call — and moving it would break
   * the one-key sentence above, because the running profiles and the next boot would both still be
   * on the bound path while the fence had followed the map somewhere else.
   *
   * <p><b>And the operator token file comes from {@code plowshare.auth}, which is a layer this
   * configuration otherwise knows nothing about.</b> That is the same one-key rule one more time:
   * {@code AuthConfig.announce} writes the file named by {@code plowshare.auth.token-file}, so the
   * fence has to read that key and not a second one of its own. A {@code
   * plowshare.workspace.token-file} would let a deployment fence off a path nothing writes while
   * leaving the live credential readable — the exact failure the sampling-directory paragraph above
   * describes, on the file that is a complete credential for every gated route.
   *
   * <p><b>And the export directory comes from {@code plowshare.conversations.retention}, which is
   * the one-key rule a third time.</b> {@code ArchiveConfig.payloadExport} builds {@code
   * PayloadExport} from that key and from nothing else, so the fence reads the same one: a {@code
   * plowshare.workspace.export-directory} would let a deployment fence off a directory nothing
   * writes while leaving the one holding people's ejected file bodies inside a workspace. The blank
   * value that makes {@code PayloadExport.NONE} makes this {@code null} here, by the same test in
   * both places, because a deployment that keeps no export has nothing to fence.
   *
   * <p><b>And the data directory arrives as the object rather than as the key.</b> {@link
   * DataLayout} is injected and its {@code root()} taken, not {@code plowshare.data.dir} re-read
   * and re-resolved here — because the layout absolutises its root once, at construction, and a
   * second resolution would be a second answer to "where is the tree" that could differ from the
   * one an export is actually written into. Every other entry in this list is a key read once; this
   * one is a value computed once, and taking the value is the same rule.
   */
  @Bean
  public ProjectStore projectStore(
      JdbcTemplate jdbc,
      WorkspaceProperties workspace,
      LlmProperties llm,
      AuthProperties auth,
      ConversationsProperties conversations,
      DataLayout data) {
    String token = auth.getTokenFile();
    String exports = conversations.getRetention().getExportDirectory();
    return new ProjectStore(
        jdbc,
        Path.of(workspace.getConfigFile()),
        // FROM THE SAME PROPERTY SamplingProfiles IS READ FROM: two keys
        // would let a deployment fence off a directory it does not use
        // while leaving the live one writable.
        Path.of(llm.getSamplingDirectory()),
        // THE CONFIGURED VALUE, never AuthConfig.defaultTokenFile(). The
        // property is what AuthConfig.announce actually writes, so an
        // operator who moves it moves the fence with it; the default
        // would fence off a path this deployment may never touch and
        // would leave the one it does touch open. Blank is the state of
        // every context that did not come through
        // PlowshareServerApplication.main -- which is every test context
        // in this repository -- and it means no token is minted or
        // written at all, so there is nothing to fence and the answer is
        // null. AuthProperties.getTokenFile() argues that default at
        // length.
        token == null || token.isBlank() ? null : Path.of(token),
        // THE SAME TEST ArchiveConfig.payloadExport APPLIES, so that
        // "this deployment keeps no export" and "there is no export
        // directory to fence" are one decision read twice rather than
        // two decisions that can disagree.
        exports == null || exports.isBlank() ? null : Path.of(exports),
        // THE LAYOUT'S OWN ROOT, already absolute and normalised, and
        // null for the deployment that keeps no data directory -- which
        // is every context that did not come through
        // PlowshareServerApplication.main.
        data.root());
  }

  /**
   * Where a client's declaration of what it roots is written down.
   *
   * <p><b>Bound here rather than injected as a {@code ProjectStore}</b>, so that {@code ws/}
   * depends on one method and not on the archive. {@code ProjectRoots} makes the argument; what
   * this line is, is the one binding of it, beside the store it comes from.
   *
   * <p>A method reference and not a lambda with anything in it. There is nothing for this seam to
   * do beyond naming the store's own operation: validation, the refusal when some other place
   * already holds the project, and the decision not to check the path all belong to {@link
   * ProjectStore#rootOn}, where the row is.
   */
  /**
   * What a run is told about where its project's files can be reached — wired onto {@link
   * JobRuntime} by a setter, on {@code EventsConfig.inboxNoticing}'s pattern, over the same three
   * things {@link #runProviders} routes by, so the note and the tools cannot disagree.
   */
  @Bean
  public ProjectWhereabouts projectWhereabouts(
      ProjectStore projects,
      SessionRegistry sessions,
      PresenceRegistry presences,
      UnionRouting unions,
      JobRuntime runtime) {
    ProjectWhereabouts whereabouts = new ProjectWhereabouts(projects, sessions, presences, unions);
    runtime.useWhereabouts(whereabouts);
    return whereabouts;
  }

  /**
   * What {@code run} may do in each project, read from the data directory and the rooting session's
   * {@code .plowshare} — wired onto {@link JobRuntime} by a setter, on {@link #projectWhereabouts}'
   * pattern. A server with no data directory has no project files, so every side is the defaults,
   * which run nothing.
   */
  @Bean
  public Environments environments(
      ProjectStore projects,
      io.aeyer.plowshare.server.data.DataLayout data,
      SessionChannel channel,
      JobRuntime runtime,
      ObjectProvider<ProjectConfigurations> configurations) {
    Environments environments =
        data.keepsAnything()
            ? new Environments(projects::id, data::environmentFor, channel)
            : new Environments(name -> null, id -> null, channel);
    environments.useProjectConfiguration(
        project ->
            configurations.getIfAvailable() == null
                ? ProjectConfiguration.NONE
                : configurations.getIfAvailable().read(project));
    runtime.useEnvironments(environments);
    return environments;
  }

  @Bean
  public ProjectRoots projectRoots(ProjectStore projects) {
    return projects::rootOn;
  }

  /**
   * The stages every run passes through: whatever the project holds, then the person's local hooks
   * — wired onto {@link JobRuntime} by a setter, on {@link #projectWhereabouts}' pattern.
   *
   * <p>Each layer is found by name and is {@link Hooks#NONE} when no bean provides it, so a context
   * standing this configuration up alone runs no hooks at all.
   */
  @Bean
  @Primary
  public Hooks runHooks(
      JobRuntime runtime,
      @Qualifier("personalHooks") ObjectProvider<Hooks> personalHooks,
      @Qualifier("projectHooks") ObjectProvider<Hooks> projectHooks,
      @Qualifier("localHooks") ObjectProvider<Hooks> localHooks,
      ObjectProvider<io.aeyer.plowshare.server.harness.Harness> harness,
      ObjectProvider<CallValidator> validator) {
    // NO HARNESS STAGE HERE ANY MORE. MemoryPolicy stood first in this chain and
    // added a volatile line to every bot turn -- "record it with memory_write on
    // your own judgement" -- as the last user message of the request.
    // gpt-oss-120b answered it as the person: asked which tools it had, its
    // thinking read "The user wants the assistant to ... record memories
    // automatically", and it wrote instead of answering (cnv_313AE867D7AEA6EE).
    // The guidance stands in memory_write's own description, said once. See
    // implementation rationale
    // Spec 2026-09-30-local-hooks-are-served decision 5: the person's own hooks after the
    // project's, so they narrow what the project allows. activeHooks puts the harness first.
    Hooks hooks =
        Hooks.chain(
            personalHooks.getIfAvailable(() -> Hooks.NONE),
            projectHooks.getIfAvailable(() -> Hooks.NONE),
            localHooks.getIfAvailable(() -> Hooks.NONE));
    runtime.useHooks(hooks);
    runtime.useHarness(
        harness.getIfAvailable(() -> io.aeyer.plowshare.server.harness.Harness.NONE));
    // The call validator (spec 2026-09-28-call-failures §5); none in a context without one.
    runtime.useValidator(validator.getIfAvailable(() -> CallValidator.NONE));
    return hooks;
  }

  /** Manual composition without an installed command isolation backend. */
  public RunProviders runProviders(
      ProjectStore projects,
      SessionChannel channel,
      SessionRegistry sessions,
      PresenceRegistry presences,
      ImageStore images,
      UnionRouting unions,
      ProjectMembers members) {
    return runProviders(
        projects,
        channel,
        sessions,
        presences,
        images,
        unions,
        members,
        CommandIsolation.UNAVAILABLE);
  }

  /**
   * The filesystems a run may reach: this server's own disk, leashed by the running job's project
   * and the running agent's grants, <b>plus the disk of the machine that roots the project, when
   * that machine is attached.</b>
   *
   * <p><b>A seam and not a provider</b>, because a provider cannot be a bean: {@link LocalProvider}
   * carries one job's tier and one agent's grants, and a singleton could know neither. {@code
   * JobRuntime} asks this once per routing call and builds the file tools over the answer.
   *
   * <h2>The project decides which machine, and never the caller</h2>
   *
   * <p><b>This sentence used to read "the machine that submitted it", and that is the whole of what
   * presence changed.</b> A {@link io.aeyer.plowshare.server.session.Presence} is a live session's
   * claim to root one project, at most one per project, and {@link PresenceRegistry#serving} is
   * what this asks instead of assuming the caller. A run in {@code payments} reaches the machine
   * that holds {@code payments}, whichever terminal it was typed at — which is the point of the
   * design, and is also the new capability its authorisation rule is about. That rule is written
   * down in {@link PresenceRegistry}: {@code plowshare.auth} is single-user, so the operator may
   * address any presence they have running.
   *
   * <p>Four answers, and only the last two are new:
   *
   * <ul>
   *   <li><b>a named project some session roots</b> — that session's disk, and nobody else's;
   *   <li><b>the global tier</b> — <em>the caller's</em> session, exactly as before. Global is the
   *       absence of a project, so there is no place to root and no presence to ask, and the only
   *       machine a run that is not place-bound can mean is the one it came from. This is the one
   *       remaining path on which who asked decides what is reached, and it is deliberate rather
   *       than left over;
   *   <li><b>a named project nothing roots, that this server holds the workspace for</b> — the
   *       server's own disk, with no client-presence warning. A server-owned root does not need a
   *       connected client;
   *   <li><b>a named project nothing roots, that this server does not hold the workspace for</b> —
   *       the {@link AbsentPresence} <em>alone</em>, carrying the server's own account of why it
   *       cannot serve it. {@link #serverWorkspace} is the whole of that decision.
   * </ul>
   *
   * <h2>Which trust model applies is asked here, not discovered downstream</h2>
   *
   * <p>The design spec's §5: {@code LocalProvider} reads the <b>server's</b> disk and {@code
   * RemoteProvider} reads a <b>client's</b> through the leash that client enforces, both models are
   * correct, and <b>the code must always know which one applies to a given path</b>. This method is
   * where it knows, and until now it knew only half: a remote provider was appended on evidence — a
   * presence, a role — while a local provider was appended unconditionally, on no evidence at all,
   * for every project in every tier.
   *
   * <p>That asymmetry had a measured cost and it is what {@link #serverWorkspace} closes. Presence
   * makes "define a project at a path only my laptop has" the ordinary thing to type, so a {@code
   * projects} row routinely names a directory this server does not have; {@code
   * LocalProvider.roots()} raises for it, {@code AbsentPresence} covers no path, and {@code
   * ProviderRouter}'s rule — a provider that could not be asked is fatal only when no provider that
   * could be asked covers the path — correctly makes it fatal. The run ends {@code UNAVAILABLE} and
   * <b>the model is never told that no presence serves the project</b>. The rule is right; what was
   * wrong is that the set contained a provider with no claim to be in it.
   *
   * <p>So a local provider is now appended on evidence too, and the evidence is the same fact that
   * makes the server-rooted case legitimate: this server holds the project's workspace. <b>When it
   * does, nothing changes</b> — the set is what it was, and a workspace that vanishes mid-run is
   * still an outage that ends the run, which is the right answer for a project that really is the
   * server's.
   *
   * <h2>Attached, and not merely named</h2>
   *
   * <p>The append is keyed on {@link Role#FILE_PROVIDER} being attached to the session and never on
   * the session id being non-null, or on a session existing under that id. <b>A browser holds a
   * listener and never a disk</b> — it would need a second implementation of {@code FileAccess} in
   * JavaScript to hold one, which is the thing this project's doubled enforcement forbids — so a
   * wiring that appended for any live id would hand a page on the internet a machine. {@code
   * SessionSubmissionTest.a_run_whose_session_holds_only_a_listener_is_given_exactly_the_same_providers}
   * held that distinction as a fixture from before this line existed, and it was measured there
   * against the mistake: with the append keyed on the id being non-null, that test fails.
   *
   * <p>The three "no" answers are all ordinary and none of them refuses: no session at all (the
   * curator's pass, a scheduled tick, plain HTTP), a session holding only a listener, and an id
   * nothing ever attached to. {@link RunProviders#forRun} owns that contract; {@link
   * SessionRegistry#find} is what makes the last of them a lookup that answers rather than one that
   * creates.
   *
   * <h2>Asked afresh, so a client that comes or goes changes the run</h2>
   *
   * <p>{@code JobRuntime} builds a {@code ProviderRouter} over this seam and the router asks on
   * every routing call, so this lambda runs per file operation rather than once per job. That is
   * what {@link io.aeyer.plowshare.server.files.FileProvider#roots()}'s "may change between two
   * calls" rule looks like one layer up, and it is why a session whose client disconnects mid-run
   * simply has a smaller set for the rest of it — <b>a socket closing is not an ending.</b> {@code
   * SESSION_GONE} is produced by a file request that cannot be served, and by nothing else.
   *
   * @param channel the one {@code FileChannelHandler}, injected under the seam's name. {@code
   *     files/} knows nothing about WebSockets and {@code ws/} already depends on {@code files/},
   *     so this is the type the dependency runs through
   * @param sessions the one registry, the same bean {@code FileChannelHandler} attaches to.
   *     Required rather than optional: both channel configurations are unconditional in this
   *     module, so a Plowshare with no registry is not a deployment anybody has — and a null one
   *     here would silently give every run the smaller set
   * @param presences which live session roots which project, the same bean {@code
   *     FileChannelHandler} declares into. Required for the reason {@code sessions} is, and one
   *     more: a null one would send every run in every project to the caller's own machine, which
   *     is the behaviour this replaced and which produces no error anywhere
   */
  @Bean
  public RunProviders runProviders(
      ProjectStore projects,
      SessionChannel channel,
      SessionRegistry sessions,
      PresenceRegistry presences,
      ImageStore images,
      UnionRouting unions,
      ProjectMembers members,
      CommandIsolation isolation) {
    return (home, grants, sessionId, owner) -> {
      if (!home.isGlobal() && owner != null)
        members.requireRole(
            home.project(), owner, io.aeyer.plowshare.server.archive.ProjectRole.CONTRIBUTOR);
      // Conversion stays on the server. Source-capable remote clients
      // stream fenced bytes over the file WS; image storage uses the
      // run's Home. Older clients retain their existing text protocol.
      FileProvider local = new LocalProvider(projects, home, grants, images, isolation);
      // The global tier has no place, so it can only mean the caller's own
      // machine; a named project means the machine that roots it, and
      // `sessionId` is not consulted at all on that path.
      boolean permitted;
      try {
        permitted =
            home.isGlobal()
                ? owner != null && sessions.accountOf(sessionId).filter(owner::equals).isPresent()
                : owner == null || members.mayWork(home.project(), owner);
      } catch (io.aeyer.plowshare.server.archive.ArchiveUnavailableException down) {
        permitted = false;
      }
      Optional<String> personalOwner =
          home.isGlobal() ? Optional.empty() : projects.personalOwner(home.project());
      if (personalOwner.isPresent() && !personalOwner.get().equals(owner)) return List.of(local);
      Optional<String> rooting =
          !permitted
              ? Optional.empty()
              : home.isGlobal()
                  ? Optional.ofNullable(sessionId)
                  : presences.serving(home.project()).map(Presence::session);
      boolean separateCheckout =
          !home.isGlobal()
              && projects
                  .find(home.project())
                  .filter(io.aeyer.plowshare.server.archive.ProjectRecord::serverProject)
                  .isPresent();
      if (separateCheckout && permitted) {
        // A server project's local checkout is tied to the asking session, including a standby
        // serving the same folder. Primary routing must not hide that session's own file claim.
        rooting =
            presences
                .rootedBy(sessionId)
                .filter(claim -> claim.project().equals(home.project()))
                .map(Presence::session);
      }
      Optional<Session> machine =
          rooting.flatMap(sessions::find).filter(session -> session.has(Role.FILE_PROVIDER));
      if (!home.isGlobal()) {
        Optional<List<FileProvider>> union =
            unions.providers(
                home.project(),
                grants,
                machine.map(Session::id),
                session -> new RemoteProvider(channel, session, grants, home, images));
        if (union.isPresent()) {
          return union.get();
        }
      }
      if (machine.isPresent()) {
        FileProvider remote = new RemoteProvider(channel, machine.get().id(), grants, home, images);
        if (separateCheckout)
          return List.of(remote); // Only this checkout's run uses its local files.
        // `local` IS LEFT OUT FOR A PROJECT WHOSE ROW NAMES ANOTHER MACHINE,
        // on the unserved branch's reasoning below: it cannot hold such a
        // project, and all it can say about one is "no workspace is
        // defined", which file_roots printed above the laptop's root and
        // a model took as the whole answer (cnv_313AE867D7AEA6EE).
        if (!home.isGlobal() && projects.rootedElsewhere(home.project()).isPresent()) {
          return List.of(remote);
        }
        return List.of(local, remote);
      }
      if (home.isGlobal()) {
        // No project to say is rooted nowhere, so nothing is added to
        // say it. The three ordinary "no" answers this method has always
        // had — no session, a listener-only session, an id nothing
        // attached to — all land here and none of them refuses.
        return List.of(local);
      }
      // Reached both when nothing has declared the project and when the
      // presence that did has lost its file channel between the
      // declaration and this call. The second is the narrower race and the
      // sentence is right for both: a claim is not a disk, and what a
      // person has to do about either is the same.
      //
      // AND THIS IS WHERE THE SPEC'S §5 QUESTION IS ASKED. Nothing roots
      // the project, so the server is the only machine that could be
      // serving it -- and whether it IS decides which of the two trust
      // models applies. The javadoc argues it; the answer is one sentence
      // or none.
      ServerWorkspace workspace = serverWorkspace(projects, home.project());
      if (workspace.rootedHere()) {
        return List.of(local);
      }
      if (workspace.refusal() == null) {
        return List.of(local, new AbsentPresence(home.project()));
      }
      // `local` is LEFT OUT, and that is the fix rather than a tidy-up:
      // the only thing it can do from here is raise, and under
      // ProviderRouter's rule a provider that raises while nothing else
      // covers the path ends the run -- before the absence beside it is
      // ever read. A provider that cannot be asked about a project this
      // server does not hold has no claim to be in the set at all.
      return List.of(new AbsentPresence(home.project(), workspace.refusal()));
    };
  }

  private record ServerWorkspace(boolean rootedHere, String refusal) {}

  /**
   * Distinguish a valid server-owned root, an undefined workspace, and an unavailable or
   * client-owned workspace. A server-owned directory needs no live client presence. The stored
   * machine identity takes precedence over coincidentally matching paths. Invalid names retain
   * LocalProvider's existing validation; store outages propagate.
   */
  private static ServerWorkspace serverWorkspace(ProjectStore projects, String project) {
    Optional<ProjectRecord> row;
    try {
      Optional<String> elsewhere = projects.rootedElsewhere(project);
      if (elsewhere.isPresent()) {
        return new ServerWorkspace(
            false,
            "nor is this server the machine that holds it: the project's row says its"
                + " files are on '"
                + elsewhere.get()
                + "', so no directory on this"
                + " server is the right answer for it and nothing here is broken");
      }
      row = projects.find(project);
    } catch (ValidationException neverDefinable) {
      return new ServerWorkspace(false, null);
    }
    if (row.isEmpty()) return new ServerWorkspace(false, null);
    if (Files.isDirectory(row.get().workspace())) return new ServerWorkspace(true, null);
    return new ServerWorkspace(
        false,
        "nor is this server the machine that holds it: the workspace "
            + row.get().workspace()
            + " that '"
            + project
            + "' is defined against is not a"
            + " directory on the machine this server runs on. Either that directory comes"
            + " back, or the project is pointed at one this server has");
  }

  /**
   * The turn loop, and the tools an agent may hold.
   *
   * <p>Two memory tools, delegation, and the file tools. The spec's containment claim is a claim
   * about <em>this list</em>, enforced by what is registered rather than by a rule the loop
   * remembers, and pinned by {@code
   * the_runtime_binds_the_memory_tools_delegation_and_the_file_tools}. <b>What it no longer says is
   * that an agent here cannot touch a filesystem</b>: it can, exactly as far as its {@code scopes:}
   * and its project's workspace allow, which is what this slice was for.
   *
   * <p>The file tools arrive as a mechanism rather than as instances for the reason {@link
   * #runProviders} gives. It is taken through an {@link ObjectProvider} on {@code
   * AgentController}'s reasoning: a context without one is a legal, running server whose agents
   * simply have no files, and {@code knownTools} then does not name them, so no definition can
   * declare one either.
   *
   * <p>{@code memory_recall} is bound although no shipped definition declares it: {@link
   * #agentRegistry} validates an operator's own agents against this set, and a runtime that bound
   * only what today's two files happen to use would refuse a perfectly reasonable agent for no
   * reason a message could explain.
   *
   * <p><b>{@code document_search} is a shared tool and not a gated mechanism</b>, which is worth
   * saying because two of the four things here are gated. Delegation is gated because a deployment
   * can decline to define agents, and the file tools are gated because a run's providers carry the
   * running agent's grants and cannot be built before a definition is known. The corpus is neither:
   * {@code DocumentsConfig} is unconditional, the corpus has no tier for a definition to change,
   * and a corpus with nothing in it is an ordinary state the tool answers in words. So it is one
   * instance beside the memory tools, and {@code knownTools} names it on every boot — which is the
   * property that stops it being the superset trap {@code knownTools}' own javadoc warns about, a
   * name waved through that will never be offered.
   *
   * <p><b>{@code document_list} is registered the same way and takes the other corpus bean.</b> It
   * reads {@link DocumentStore} where the search reads {@link RetrievalService}, and that second
   * dependency is not a second gate: {@code DocumentsConfig} binds both unconditionally, so a
   * context with one has the other. The two tools answer different questions over the same corpus —
   * which passage answers this, and what is here at all — and the second was reachable by {@code
   * curl} and by no agent until it was registered here, which is the hazard {@code Capabilities}'
   * javadoc names and neither assembly check can see.
   *
   * <p><b>{@code document_ask} is the fifth thing here and is registered like the corpus rather
   * than like delegation</b>, although {@link JobRuntime} rebinds it per run. It is one shared
   * instance because everything about it <em>is</em> shared — one deliberation, one corpus, one
   * operator's allowance — and the single per-run thing it needs, the cancellation flag, is a copy
   * the runtime makes. Registering it here rather than special-casing it there is what keeps {@code
   * knownTools()} derived from what is really bound: {@code AskTool}'s own javadoc has the rest.
   *
   * <p>The deliberation arrives through an {@link ObjectProvider} <b>because the cycle is real and
   * not defensive</b>. {@code DeliberationConfig} builds it out of this very runtime — a pass is
   * three agents run through {@link JobRuntime#run} — so it cannot exist when this bean is being
   * built, and a hard parameter here would be a context that refuses to start. {@link Asking#NONE}
   * is what a context genuinely without one gets, and it says so in the ending rather than throwing
   * out of a tool call.
   *
   * <p>{@code plowshare.documents.ask-budget} likewise arrives as a supplier read per call, so an
   * operator who moves it moves the next ask; and it comes through an {@link ObjectProvider} for
   * the same reason the corpus itself would if it could — a context standing this configuration up
   * alone binds no {@code DocumentsProperties}, and the fallback is unreachable in a server that
   * has a deliberation to spend it on.
   *
   * <p><b>{@code fetch} is registered like the corpus, as a hard parameter rather than an {@link
   * ObjectProvider}.</b> {@code FetchConfig} binds {@link FetchService} unconditionally — it has no
   * companion property gating it out, the same reason {@code corpus} and {@code documents} are hard
   * parameters here — so a context that boots this bean at all has one. {@link FetchTool} is the
   * harness capability the previous slice's export left unbuilt; the MCP surface and the CLI verb
   * both come later and both reach the same {@link FetchService}, on {@code document_search}'s own
   * precedent of one shared instance behind every front end.
   *
   * <p><b>{@code search} is registered the same way, and it is the other half of the same hole.</b>
   * The previous slice shipped {@code search} as an MCP tool and a CLI verb and never built the
   * agent tool — {@link SearchTool}'s own class comment names it as {@link FetchTool}'s twin — so
   * it is a hard parameter here too: {@code SearchConfig} binds {@link SearchService}
   * unconditionally, the same reason {@code fetch} is a hard parameter and not an {@link
   * ObjectProvider}.
   */
  @Bean
  public JobRuntime jobRuntime(
      LlmDispatcher dispatcher,
      Archive archive,
      ConversationStore conversations,
      EntryStore entries,
      RetrievalService corpus,
      ObjectProvider<io.aeyer.plowshare.server.information.InformationCatalogue> information,
      DocumentStore documents,
      io.aeyer.plowshare.server.documents.CitationStore citations,
      io.aeyer.plowshare.server.archive.TurnStore turns,
      ObjectProvider<io.aeyer.plowshare.server.archive.Conversations> conversationRules,
      ObjectProvider<Callers> contextCallers,
      ObjectProvider<JobRuntime> contextRuntime,
      ObjectProvider<io.aeyer.plowshare.server.llm.tokens.Tokenizer> contextTokenizer,
      ObjectProvider<Compaction> contextCompaction,
      FetchService fetch,
      SearchService search,
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<RunProviders> files,
      ObjectProvider<Reminding> reminding,
      ObjectProvider<Deliberation> deliberation,
      ObjectProvider<DocumentsProperties> props,
      ObjectProvider<io.aeyer.plowshare.server.agents.digests.Navigator> navigator,
      ObjectProvider<io.aeyer.plowshare.server.agents.digests.MemoryProperties> memoryProperties,
      ObjectProvider<ImageStore> images,
      ObjectProvider<Scribe> scribe,
      io.aeyer.plowshare.server.todos.TodoBoard todos,
      ObjectProvider<io.aeyer.plowshare.server.outgoing.OutgoingWork> outgoing,
      ObjectProvider<io.aeyer.plowshare.server.board.BoardMessaging> messaging) {
    JobRuntime runtime =
        new JobRuntime(
            dispatcher,
            List.of(
                new MemoryTools.Recall(archive),
                new MemoryTools.Read(archive),
                new MemoryTools.Write(archive, scribe::getObject),
                new ConversationTrajectoryTool(conversations, entries),
                new ConversationSearchTool(entries),
                new ArchiveReadTools.Index(archive),
                new ArchiveReadTools.Conversations(conversations),
                new ArchiveReadTools.Chat(conversations, entries),
                new ConversationContextTool(
                    conversations,
                    turns,
                    conversationRules::getObject,
                    (conversation, agent, session) -> {
                      Callers callers = contextCallers.getObject();
                      AgentDefinition definition =
                          callers.readAgent(
                              agent, callers.callerForConversation(conversation, session));
                      definition =
                          contextRuntime
                              .getObject()
                              .withAgentRules(
                                  definition,
                                  callers.homeOfConversation(conversation),
                                  session,
                                  conversation);
                      return io.aeyer.plowshare.server.api.ContextView.Prefix.of(
                          definition,
                          contextRuntime.getObject().schemasOfferedTo(definition),
                          contextTokenizer.getObject(),
                          contextCompaction.getObject().contextLengthOf(definition));
                    }),
                new RetrievalTools.Retrieve(corpus),
                new RetrievalTools.Rank(corpus),
                new RetrievalTools.Outline(documents),
                new RetrievalTools.Citations(citations, conversations),
                new DocumentTools.Search(corpus),
                new InformationTool(false, information::getObject).withRetrieval(corpus),
                new InformationTool(true, information::getObject),
                new MemoryNavigateTool(
                    navigator::getObject, () -> memoryProperties.getObject().getNavigationBudget()),
                new DocumentTools.AgentList(documents),
                new AskTool(asking(deliberation), corpus, () -> askBudget(props)),
                new GetDateTool(Instant::now, ZoneId.systemDefault()),
                new FetchTool(fetch),
                new SearchTool(search),
                new OutgoingTool(outgoing::getIfAvailable, "send"),
                new OutgoingTool(outgoing::getIfAvailable, "read"),
                new OutgoingTool(outgoing::getIfAvailable, "cancel"),
                new OutgoingTool(outgoing::getIfAvailable, "peers")),
            agents::getIfAvailable,
            files.getIfAvailable(),
            Instant::now,
            reminding.getIfAvailable(() -> Reminding.NONE),
            // WHERE agent_run RESOLVES AN ID ITS CALLER WAS TOLD RATHER THAN
            // SHOWN, and the second of the two places in this server that
            // turns a UID into bytes — Pictures is the first. It is
            // scoped by the run's own home, which arrives as a parameter of
            // AgentTool.run, so no model can name another project's; that
            // argument, and the enumeration absence it rests on, is in
            // AgentRunTool's javadoc.
            //
            // Through a provider, and NONE when nothing bound one: ImagesConfig
            // is unconditional in production, and a context standing this
            // configuration up alone (AgentsConfigTest does) binds no image
            // store. A runtime given NONE resolves every id against what the
            // run was shown and finds nothing else, which is exactly this
            // tool's behaviour before the decision.
            images.getIfAvailable(() -> ImageStore.NONE));
    // Before the registry reads knownTools(): see JobRuntime.useTodos. REQUIRED, not optional:
    // a todo list is how any agent keeps a task of several steps across turns and compactions,
    // so every boot binds todo_read and todo_write and a shipped definition may declare them.
    // TodosConfig is unconditional in production; a context without a board is not a boot.
    runtime.useTodos(todos);
    runtime.useMessaging(
        new Messaging() {
          public SendMessageTool tool(RunExtras.Context context) {
            return new SendMessageTool(
                (request, home) -> {
                  var available = messaging.getIfAvailable();
                  if (available == null) return "Messaging is unavailable on this server.";
                  return available.send(context, request, home);
                });
          }

          public AgentTool protect(
              AgentTool tool, AgentDefinition definition, String conversation) {
            var available = messaging.getIfAvailable();
            return available == null ? tool : available.protect(tool, definition, conversation);
          }
        });
    return runtime;
  }

  /**
   * The deliberation behind the seam, resolved at every call.
   *
   * <p>Late for {@link #agentRegistry}'s reason exactly: a bean defined after this one has to be
   * picked up, and a resolution at bean-creation time would fix the answer for the life of the
   * context — which for this cycle means fixing it at {@code null}.
   */
  private static Asking asking(ObjectProvider<Deliberation> deliberation) {
    return new Asking() {
      @Override
      public Outcome ask(
          java.util.UUID documentId,
          String question,
          Budget budget,
          java.util.function.BooleanSupplier cancelled) {
        Deliberation pass = deliberation.getIfAvailable();
        return pass == null
            ? Asking.NONE.ask(documentId, question, budget, cancelled)
            : pass.ask(documentId, question, budget, cancelled);
      }

      @Override
      public Asking scoped(
          io.aeyer.plowshare.server.information.InformationAccess access,
          io.aeyer.plowshare.server.information.InformationContext context,
          String session) {
        return scoped(access, context, session, null);
      }

      @Override
      public Asking scoped(
          io.aeyer.plowshare.server.information.InformationAccess access,
          io.aeyer.plowshare.server.information.InformationContext context,
          String session,
          String parent) {
        return (document, question, budget, cancelled) -> {
          Deliberation pass = deliberation.getIfAvailable();
          return pass == null
              ? Asking.NONE.ask(document, question, budget, cancelled)
              : pass.scoped(access, context, session, parent)
                  .ask(document, question, budget, cancelled);
        };
      }
    };
  }

  /**
   * What a pass may spend, from the operator's key.
   *
   * <p>The fallback is {@link Deliberation#A_PASS} and is unreachable in a server that can ask
   * anything: a context with no {@code DocumentsProperties} has no {@code Deliberation} either, so
   * {@link Asking#NONE} answers before any budget is looked at. It is a number rather than a throw
   * because this is read inside a tool call, where a failure to find a property would end
   * somebody's run over a bean that was never going to be asked.
   */
  private static int askBudget(ObjectProvider<DocumentsProperties> documents) {
    DocumentsProperties props = documents.getIfAvailable();
    return props == null ? Deliberation.A_PASS : props.getAskBudget();
  }

  /**
   * Automatic recall: what the archive already holds about the question a run is about to be asked.
   *
   * <p>Wired here beside {@link #learner} and for its reason — framework-free, a collaborator from
   * another package, and what it does is spend a call rather than hold rows. It is the reverse
   * direction of the same feature and lives in the same package.
   *
   * <p><b>Through an {@link ObjectProvider} at the runtime, and defaulting to {@link
   * Reminding#NONE}</b>, on this file's standing rule about degraded states: a context with no
   * reminder is a legal running server whose turns open exactly the way they always did. That is
   * also the shape every fixture in the suite gets, which is what keeps this additive.
   */
  @Bean
  public Reminding reminding(Archive archive) {
    return new Reminder(archive);
  }

  /**
   * The agents this server's own Java looks up by name, and the whole of what a bad file may still
   * take the boot down for.
   *
   * <h2>Dependency, and not location</h2>
   *
   * <p>{@code Scribe} asks the registry for {@code scribe}, {@code Curator} for {@code
   * promotion_judge}, {@code Learner} for {@code learner}, and {@code Compaction} for {@code
   * conversation_folder}. Each of the four has a degraded path — a write filed flat, a pass that
   * judged nothing, a fold that mined nothing, a conversation that never folds — and every one of
   * those is discovered later, somewhere else, with nothing in it that names a file. That is the
   * failure abort-on-fail exists for, so these four keep it and nothing else does. An earlier draft
   * split on which directory a file sat in; a directory is only ever a proxy for this.
   *
   * <h2>{@code conversation_folder} is here because its degraded path is permanent and looks like
   * weather</h2>
   *
   * <p><b>A harness function this server cannot perform is not a degraded server, it is a
   * misconfigured one.</b> {@code Compaction} resolves the folder per fold and catches what the
   * lookup throws, because a fold must never fail a turn — so an absent, misspelled, disabled or
   * unserved folder is a server that answers every question and folds nothing, for its whole life,
   * at one {@code WARN} a turn whose sentence is the same one a model timeout writes. Nothing else
   * reports it: the conversation keeps working until its prompt reaches the endpoint's own refusal.
   *
   * <p><b>And it is newly reachable, which is why it was not here before.</b> A fold used to run on
   * the conversing agent's own specifier — already checked by {@link #requireModelServed} for the
   * agent being folded — so there was no second name to get wrong. It runs as its own agent on
   * {@code system.compaction} now ({@code implementation rationale} §6.1 for why), and that
   * specifier resolves through {@code plowshare.llm.system-overrides} or the plain {@code
   * plowshare.llm.system} binding — neither of which any conversing agent names. Membership here is
   * what turns "that binding names nothing" from a silent permanent degradation into a boot that
   * stops and says which key.
   *
   * <h2>No transitive closure</h2>
   *
   * <p><b>{@code calls:} is a grant and not a dependency</b>, so nothing is dragged in through the
   * call graph. A required agent's callee is an ordinary agent: if it is refused, the required
   * agent keeps running with an inert grant and the edge fails at call time. That is also why
   * {@code interlocutor} and {@code code_reviewer} are not here — nothing in code names either, and
   * a broken chat agent is discovered by the person trying to chat, who is much better served by a
   * running server they can fix it from.
   *
   * <p>Public so that {@code AgentsConfigTest} can assert the membership as literal names. Spelled
   * from the three classes' own constants so that a rename cannot leave this set pointing at an
   * agent nobody looks up.
   */
  public static final Set<String> REQUIRED =
      Set.of(Scribe.AGENT, Curator.AGENT, Learner.AGENT, Compaction.FOLDER);

  /**
   * The agent set this server serves, and there is no longer a server that serves none.
   *
   * <p><b>The shipped set is the floor.</b> {@link ClasspathDefinitions} reads from inside this
   * jar, which a deployment always has, so the state this method used to answer with — {@code
   * null}, on the grounds that "the directory is absent" — cannot happen any more. What varies is
   * only whether anything is layered <em>over</em> that floor.
   *
   * <p><b>A deployment that keeps no data directory has no {@code global/} to layer, and that is a
   * branch rather than an oversight.</b> {@link DataLayout#agentsFor} and {@link
   * DataLayout#botsFor} throw on a {@code null} root, because every other caller reaching them has
   * already decided to write and a path relative to nothing would silently land in whatever
   * directory the process happened to start in. A boot is not that caller: it is allowed to have
   * nothing to layer, on {@code ClasspathDefinitions}' promise that the seed is always there.
   * Calling {@code agentsFor}/{@code botsFor} unconditionally would abort startup on every context
   * that never asked for a data directory — {@code DataLayout}'s own javadoc names that as most of
   * this module's test suite, and it is equally any deployment run with {@code PLOWSHARE_DATA_DIR}
   * unset. So {@link DataLayout#keepsAnything()} decides which of the two this boot is, once, here.
   *
   * <p><b>A bad definition is fatal only for {@link #REQUIRED}</b>; everything else is disabled,
   * named on {@link AgentRegistry#disabled()} and logged at boot with its reason — unless the fault
   * is one <em>item</em> of a grant the agent could never have issued, which costs the item and is
   * named on {@link AgentRegistry#withheldTools()} or {@link AgentRegistry#withheldEdges()}
   * instead. {@code AgentRegistry.read} owns that ladder and says at length why availability is
   * worth the trade and what has to be paid for it.
   *
   * <p><b>The source is read here once, and that is structural rather than an optimisation.</b>
   * What this method returns is the loaded set, not a source kept for later, so there is no second
   * read for a live value to reach — asking the map on a second call would mean re-parsing and
   * re-validating every definition per delegation, which is the hot-reload {@code implementation
   * rationale} §8 excludes and which would take the boot-time refusals this class exists for with
   * it.
   *
   * @throws IllegalStateException if {@code global/agents/} and {@code global/bots/} both name a
   *     definition of the same name — there is no sensible winner between two operator-written
   *     directories, so this is caught before either is layered rather than left for {@link
   *     LayeredDefinitions} to resolve silently — or if an agent this code depends on is
   *     unreadable, invalid, or names a model no pool serves
   */
  @Bean
  public AgentRegistry agentRegistry(
      JobRuntime runtime, LlmDispatcher dispatcher, SamplingProfiles profiles, DataLayout data) {

    DefinitionSource boot;
    if (data.keepsAnything()) {
      FilesystemDefinitions globalAgents = new FilesystemDefinitions(data.agentsFor(null));
      FilesystemDefinitions globalBots = new FilesystemDefinitions(data.botsFor(null));
      requireNoClash(globalAgents, globalBots);
      boot = new LayeredDefinitions(List.of(globalAgents, globalBots, new ClasspathDefinitions()));
    } else {
      boot = new ClasspathDefinitions();
    }

    String source = boot.describe();
    log.info("Definitions: {}", source);

    AgentRegistry.Loaded loaded = AgentRegistry.read(boot, runtime.knownTools(), REQUIRED);
    return new AgentRegistry(
        sampled(modelChecked(loaded, dispatcher, source, REQUIRED), dispatcher, profiles));
  }

  /**
   * The two model checks over a whole reading, boot tier or project tier.
   *
   * <p><b>One method and not a loop in each caller</b>, because the two callers must not be able to
   * disagree about what a definition has to satisfy. They differ in exactly one argument: which
   * names are allowed to take the process down with them.
   *
   * @param fatal the names for which a failed check is thrown rather than absorbed — {@link
   *     #REQUIRED} at boot, where there is a startup log to report to and a degraded path that must
   *     not be entered, and empty for a hot-loaded tier, where nothing may abort a resolution and
   *     the whole ladder ends at "disabled, because X"
   */
  private static AgentRegistry.Loaded modelChecked(
      AgentRegistry.Loaded loaded, LlmDispatcher dispatcher, String source, Set<String> fatal) {
    for (AgentDefinition definition : List.copyOf(loaded.enabled().values())) {
      try {
        requireModelServed(dispatcher, definition, source);
        requireModelSees(dispatcher, definition, source);
      } catch (IllegalStateException unserved) {
        // Folded back into the reading rather than rethrown, so that the
        // one check the registry cannot make for itself lands in the same
        // two buckets as every other refusal. Rethrowing unconditionally
        // would have left an unserved model as the single fault that
        // still took the boot down for everybody -- the rule true of the
        // loader and false of the boot.
        if (fatal.contains(definition.name())) {
          throw unserved;
        }
        loaded = loaded.without(definition.name(), unserved.getMessage());
      }
    }
    return loaded;
  }

  /**
   * The questions a hot-loaded tier is judged by — a project's own {@code agents/}/{@code bots/},
   * or a definition {@link DefinitionWriter} is about to write — bound once so that {@link
   * #definitionResolver} and {@link #definitionWriter} take the identical instance rather than two
   * lambdas that happen to read the same way today.
   *
   * <p><b>That "identical instance" is the point and not a tidiness preference.</b> A writer that
   * validated a candidate against a different opinion of "is this model served, and what does it
   * actually send" than the loader would judge the same file by is the exact drift {@link
   * DefinitionWriter}'s own javadoc — "the same seam, extended" — exists to close: {@code write}
   * would accept a definition the very next resolution disables, discovered again, later, by
   * somebody else. Two separately written lambdas of this same shape could be edited out of step by
   * a later change to either call site with nobody noticing until a definition passed one and
   * failed the other; one bean handed to both callers cannot drift from itself.
   *
   * <p>{@link #modelChecked} and {@link #sampled} are the same two calls {@link #agentRegistry}
   * makes for the boot set, with {@code fatal} empty — nothing a hot-loaded tier or a candidate not
   * yet on disk may ever abort a resolution or a write, so the whole ladder for either ends at
   * "disabled" or "refused", never at a thrown {@link IllegalStateException} the boot set's own
   * {@code REQUIRED} set is what earns.
   */
  @Bean
  public DefinitionChecks definitionChecks(LlmDispatcher dispatcher, SamplingProfiles profiles) {
    return (loaded, source) ->
        sampled(modelChecked(loaded, dispatcher, source, Set.of()), dispatcher, profiles);
  }

  /**
   * The layer in front of {@link #agentRegistry}: what one caller, asking on behalf of one project,
   * sees on top of the boot set.
   *
   * <p>Built over the same registry {@link #agentRegistry} already validated and never a second
   * read of it — a project's own tier is what {@link DefinitionResolver} reads fresh, on demand,
   * per project. {@code runtime.knownTools()} and {@link #REQUIRED} are the same two sets {@link
   * #agentRegistry} was built with, so a project's definitions are judged by the same rules the
   * boot set was.
   *
   * <p><b>{@code projects::exists} and not {@code projects::find}.</b> {@link
   * DefinitionResolver#forCaller} asks this once per cache miss for exactly one bit — does this id
   * still have a row — and {@link ProjectStore#exists(long)} answers that without decoding a {@code
   * ProjectRecord} nobody reads. See its own javadoc, and {@link DefinitionResolver}'s, for why the
   * database and not the filesystem is the authority on which projects exist.
   *
   * <p><b>And judged by the same checks the boot set was, through {@link #definitionChecks} and not
   * a second lambda of the same shape.</b> See that bean's own javadoc for why sharing the instance
   * is load-bearing rather than cosmetic. A definition means the same thing whichever directory it
   * sits in: its sampling is resolved against the profiles for the model that will actually answer,
   * and a model no pool serves is caught rather than accepted. The one difference from the boot set
   * is the {@code fatal} set inside {@link #definitionChecks} itself, which is empty — nothing
   * hot-loaded may abort anything, so a project definition on an unserved model is disabled and
   * named where the same file under {@code global/} would have stopped the boot. That asymmetry is
   * spec §4's, not this method's: the tier with no boot to report to is also the tier with nothing
   * that must abort.
   *
   * <p><b>And {@code sessions} is asked the same one bit about a session that {@code projects} is
   * asked about a project.</b> {@link DefinitionResolver} caches per project <em>and</em> session,
   * and a session id is a name a client minted rather than a row anything issued — {@code POST
   * /v1/agents/run} accepts a body naming a session nothing has ever attached to, deliberately — so
   * an entry keyed by one is an entry nothing is ever obliged to remove. The predicate below is
   * what makes such a caller resolve as the sessionless caller it effectively is.
   *
   * <p><b>{@link Role#FILE_PROVIDER} attached, and not merely a session known to have existed.</b>
   * {@link SessionRegistry} never removes a {@link Session}: {@code detach} empties a role and
   * leaves the session, and that class's own javadoc says plainly that nothing reaps. So {@code
   * find(id).isPresent()} answers yes for every id that ever attached, including one that closed
   * hours ago, and a resolver built on that bit alone would still keep one registry per project for
   * every session that has ever existed. The role is the part that actually goes when a client
   * does.
   *
   * <p><b>And that role rather than any role, because it is the only one that can contribute
   * anything and the only one whose closing this resolver is told about.</b> What a session adds to
   * a resolution is a {@code ChannelDefinitions} layer read down its file channel; a session
   * holding only a listener answers every file request with "gone", so its resolved tier is the
   * sessionless tier and it is served exactly that, from the entry every sessionless caller on that
   * project already shares. The other half is eviction: {@code FileChannelHandler} is the only
   * caller of {@code DefinitionResolver.sessionClosed}, and it is also what detaches this role.
   * Keying on it makes the two sets one — every session-keyed entry belongs to a session whose
   * closing fires the sweep — where keying on "any role" would install entries for listener-only
   * sessions that nothing would ever tell this resolver about. It is the same line {@link
   * #runProviders} one method up already uses to decide whether a session is a machine, asked here
   * about the same session for the same reason.
   *
   * @param channel the same {@link SessionChannel} bean {@link #runProviders} is built against —
   *     what {@link DefinitionResolver} reaches a caller's own {@code .plowshare/} through, for the
   *     one caller in {@code forCaller} whose session is attached
   * @param sessions the one registry, the same bean {@link #runProviders} and {@code
   *     FileChannelHandler} already share. Asked one bit, per cache miss, and never written to
   *     <p><b>And the session must root the project asked about</b>, which is {@code presences}'
   *     answer and not {@code sessions}'. A client that roots A and then moves its tier to B with
   *     {@code /project} keeps A's channel open, so the bit above still says yes; but its {@code
   *     .plowshare/} is A's, and handing it to B would list A's definitions there and let A's
   *     {@code bots/default} pick who answers in B. Asked in memory first — the name that session
   *     roots, if any — and only then the database, to turn that name into the id the resolver
   *     holds.
   * @param checks {@link #definitionChecks}, shared with {@link #definitionWriter} rather than
   *     rebuilt here
   * @param presences which session roots which project — the same bean {@code FileChannelHandler}
   *     declares into
   */
  @Bean
  public DefinitionResolver definitionResolver(
      AgentRegistry agentRegistry,
      DataLayout data,
      ProjectStore projects,
      JobRuntime runtime,
      SessionChannel channel,
      SessionRegistry sessions,
      DefinitionChecks checks,
      PresenceRegistry presences,
      ObjectProvider<io.aeyer.plowshare.server.personal.PersonalSpaces> personal,
      ObjectProvider<ProjectConfigurations> configurations) {
    DefinitionResolver resolver =
        new DefinitionResolver(
            agentRegistry,
            data,
            projects::exists,
            runtime.knownTools(),
            REQUIRED,
            channel,
            sessionLive(sessions),
            sessionRoots(projects, presences),
            checks);
    resolver.usePersonalResources(personalIds(personal.getIfAvailable(), sessions));
    resolver.useProjectConfiguration(
        id ->
            configurations.getIfAvailable() == null
                ? ProjectConfiguration.NONE
                : configurations.getIfAvailable().read(id));
    return resolver;
  }

  @Bean
  public SkillResolver skillResolver(
      DataLayout data,
      ProjectStore projects,
      SessionChannel channel,
      SessionRegistry sessions,
      PresenceRegistry presences,
      ObjectProvider<io.aeyer.plowshare.server.personal.PersonalSpaces> personal,
      ObjectProvider<ProjectConfigurations> configurations) {
    SkillResolver resolver =
        new SkillResolver(
            data,
            channel,
            projects::exists,
            sessionLive(sessions),
            sessionRoots(projects, presences));
    resolver.usePersonalResources(personalIds(personal.getIfAvailable(), sessions));
    resolver.useProjectConfiguration(
        id ->
            configurations.getIfAvailable() == null
                ? ProjectConfiguration.NONE
                : configurations.getIfAvailable().read(id));
    return resolver;
  }

  @Bean
  public AgentRules agentRules(
      DataLayout data,
      ProjectStore projects,
      SessionChannel channel,
      SessionRegistry sessions,
      PresenceRegistry presences,
      JobRuntime runtime,
      ObjectProvider<io.aeyer.plowshare.server.personal.PersonalSpaces> personal) {
    AgentRules rules =
        new AgentRules(data, channel, sessionLive(sessions), sessionRoots(projects, presences));
    rules.usePersonalResources(personalIds(personal.getIfAvailable(), sessions));
    runtime.useAgentRules(
        (definition, home, session, account) ->
            rules.apply(
                new DefinitionResolver.Caller(
                    home.isGlobal() ? null : projects.id(home.project()), session, account),
                definition));
    return rules;
  }

  static java.util.function.Function<DefinitionResolver.Caller, Long> personalIds(
      io.aeyer.plowshare.server.personal.PersonalSpaces personal, SessionRegistry sessions) {
    if (personal == null) return caller -> null;
    return caller -> {
      String handle = caller.handle();
      if (handle == null && caller.sessionId() != null)
        handle = sessions.accountOf(caller.sessionId()).orElse(null);
      if (handle == null && caller.projectId() != null)
        handle = personal.owner(caller.projectId()).orElse(null);
      return personal.id(handle).orElse(null);
    };
  }

  static Predicate<String> sessionLive(SessionRegistry sessions) {
    return id -> sessions.find(id).filter(live -> live.has(Role.FILE_PROVIDER)).isPresent();
  }

  static BiPredicate<Long, String> sessionRoots(ProjectStore projects, PresenceRegistry presences) {
    return (projectId, session) ->
        projectId != null
            && presences
                .rootedBy(session)
                .map(rooted -> projectId.equals(projects.id(rooted.project())))
                .orElse(false);
  }

  /**
   * Which orchestrations a caller can reach. Its boot set is read here, over the same boot agents
   * and the same {@link DefinitionChecks} the agent registry got, so a shipped or global
   * orchestration that calls an unserved agent is disabled at boot and logged, never offered.
   * Required system orchestrations must survive these same checks or startup fails.
   */
  @Bean
  public OrchestrationResolver orchestrationResolver(
      AgentRegistry agentRegistry,
      DefinitionResolver definitions,
      DataLayout data,
      ProjectStore projects,
      JobRuntime runtime,
      SessionChannel channel,
      SessionRegistry sessions,
      DefinitionChecks checks,
      PresenceRegistry presences,
      ObjectProvider<io.aeyer.plowshare.server.personal.PersonalSpaces> personal) {
    DefinitionSource shipped =
        new ClasspathDefinitions(ClasspathDefinitions.SHIPPED_ORCHESTRATIONS);
    List<OrchestrationRegistry.Layer> boot =
        data.keepsAnything()
            ? List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL,
                    new FilesystemDefinitions(data.orchestrationsFor(null), true)),
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED, shipped))
            : List.of(
                new OrchestrationRegistry.Layer(OrchestrationDefinition.Tier.SHIPPED, shipped));
    OrchestrationRegistry.Loaded bootSet =
        OrchestrationRegistry.readRequired(boot, runtime.knownTools(), agentRegistry, checks);
    OrchestrationResolver resolver =
        new OrchestrationResolver(
            bootSet,
            data,
            projects::exists,
            runtime.knownTools(),
            channel,
            sessionLive(sessions),
            sessionRoots(projects, presences),
            definitions::forCaller,
            checks);
    resolver.usePersonalResources(personalIds(personal.getIfAvailable(), sessions));
    return resolver;
  }

  /**
   * The write path behind {@link #agentRegistry} and {@link #definitionResolver} — the one door
   * {@link DefinitionWriter}'s own class javadoc says nothing agent-facing may ever be handed,
   * reached by whatever this repository's later tasks put in front of it (this task's own {@code
   * POST /v1/agents} among them).
   *
   * <p><b>Every argument here is one already built for a reader, handed to the writer instead of
   * rebuilt for it.</b> {@code agentRegistry} is the same boot set {@link #definitionResolver}
   * layers a project's tier over, so a candidate's {@code calls:} naming an agent the boot set
   * already serves is not read as a typo — see {@link DefinitionWriter}'s own constructor javadoc,
   * "tier targeting". {@code runtime.knownTools()} and {@link #REQUIRED} are the identical two sets
   * {@link #agentRegistry} and {@link #definitionResolver} were built with. {@code data} is the
   * same {@link DataLayout} both already read from, so "which directory a write lands in" and
   * "which directory a read comes from" cannot name two different trees. And {@link
   * #definitionChecks} is the one this method does <b>not</b> build a second copy of — see that
   * bean's own javadoc for why a writer validating differently from the loader is the exact drift
   * this whole design fights, one call site over.
   */
  @Bean
  public DefinitionWriter definitionWriter(
      AgentRegistry agentRegistry, DataLayout data, JobRuntime runtime, DefinitionChecks checks) {
    return new DefinitionWriter(agentRegistry, data, runtime.knownTools(), REQUIRED, checks);
  }

  /**
   * The one case {@link LayeredDefinitions} would otherwise resolve silently: the same name defined
   * in both {@code global/agents/} and {@code global/bots/}.
   *
   * <p>Both are operator-written directories at the same tier, so neither has a claim to win over
   * the other the way a more specific tier wins over the shipped seed — {@code LayeredDefinitions}'
   * "first layer to name something wins it" is the right rule between tiers and the wrong one
   * between two directories an operator filled in by hand. Called beside the layering rather than
   * folded into it, so this is a boot-time refusal naming both paths rather than a quiet pick.
   */
  private static void requireNoClash(FilesystemDefinitions agents, FilesystemDefinitions bots) {
    Map<String, String> byName = new LinkedHashMap<>();
    for (DefinitionSource.Definition definition : agents.list()) {
      byName.put(definition.name(), definition.origin());
    }
    for (DefinitionSource.Definition definition : bots.list()) {
      String agentOrigin = byName.get(definition.name());
      if (agentOrigin != null) {
        throw new IllegalStateException(
            "'"
                + definition.name()
                + "' is defined in both "
                + agentOrigin
                + " and "
                + definition.origin()
                + ". global/agents/ and global/bots/ are"
                + " the same tier, so there is no sensible winner between them --"
                + " rename one of the two files");
      }
    }
  }

  /**
   * The layering, and this is where it lands.
   *
   * <h2>The one place that holds both halves</h2>
   *
   * <p>A profile is keyed on the <em>wire model</em>; an agent file names a <em>specifier</em>.
   * Only a dispatcher can turn one into the other, and only the registry knows what the files said
   * — so this method, which has both in its hands, is where the resolution has to happen. {@code
   * AgentDefinition}'s field javadoc calls this out from the other end, and stage 6 predicted it in
   * as many words: <i>"a change at the loader, where the layering would live."</i>
   *
   * <p>The order is the profile's numbers first and the file's explicit {@code temperature:} over
   * the top, field by field — see {@link Sampling#overriddenBy}, which is what stops an agent that
   * named only a temperature from silently erasing the truncation it was recommended beside. The
   * third layer, what the transport can carry, is applied at dispatch, because it depends on which
   * pool answers.
   *
   * <h2>Every outcome is logged, and the silent one loudest</h2>
   *
   * <p>Three things can happen and an operator has to be able to tell them apart from a log:
   *
   * <ul>
   *   <li><b>a profile answered</b> — INFO, naming the agent, the wire model, the profile, the mode
   *       and the numbers. This is the line that makes "what does this agent actually send" a
   *       question with an answer;
   *   <li><b>no profile matched and the agent asked for nothing in particular</b> — INFO once per
   *       model. The request carries no sampling parameters and the model's own defaults apply,
   *       which is the safe outcome;
   *   <li><b>no profile matched and the agent declared an intent</b> — WARN, naming the agent and
   *       the model. <b>This is the case that must never be quiet.</b> An agent asking for {@code
   *       precise} and silently getting whatever the model does by default is exactly the
   *       invisible-fact failure this design exists to end, and the honest thing is to say that the
   *       declaration was not honoured rather than to let a file go on claiming something nothing
   *       delivered.
   * </ul>
   *
   * <p>An agent with an explicit {@code temperature:} still gets it under DEFAULT — an override is
   * a number a person wrote, and nothing about the absence of a profile makes it less written.
   */
  static AgentRegistry.Loaded sampled(
      AgentRegistry.Loaded loaded, LlmDispatcher dispatcher, SamplingProfiles profiles) {
    Map<String, AgentDefinition> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, AgentDefinition> entry : loaded.enabled().entrySet()) {
      AgentDefinition definition = entry.getValue();
      String wireModel = dispatcher.wireModelFor(definition.model());
      SamplingProfiles.Resolved match = profiles.resolve(wireModel, definition.intent());
      AgentDefinition sampled =
          definition.sampling(match.sampling().overriddenBy(definition.sampling()));
      if (match.matched()) {
        log.info(
            "agent '{}' wants {} sampling; on '{}' that is profile '{}' mode '{}',"
                + " so it sends {}",
            definition.name(),
            definition.intent().declared(),
            wireModel,
            match.profile(),
            match.mode(),
            sampled.sampling().described());
      } else if (definition.intent() != AgentDefinition.DEFAULT_INTENT) {
        log.warn(
            "agent '{}' declares 'sampling: {}' and no profile matches the model"
                + " '{}', so THAT INTENT IS NOT HONOURED: the request will send {}."
                + " Add a line for this model to models.yaml in the sampling directory,"
                + " or accept that the file is asking for something this server cannot"
                + " give it.",
            definition.name(),
            definition.intent().declared(),
            wireModel,
            sampled.sampling().described());
      } else {
        log.info(
            "agent '{}' has no sampling profile for the model '{}', so it sends {}",
            definition.name(),
            wireModel,
            sampled.sampling().described());
      }
      AgentDefinition.Fallback fallback = sampled.fallback();
      if (!fallback.on().isEmpty() && !served(dispatcher, fallback.model())) {
        // NOT A REFUSAL OF THE AGENT, and that is the opposite of what
        // an unserved `model:` gets. An agent's own model is what it
        // runs on; its fallback is a remedy for one kind of answer. A
        // server with no low-refusal model is a server where that
        // remedy is unavailable, and the agent behaves exactly as it
        // would have with no `fallback:` at all -- its refusals are its
        // answers -- rather than disappearing, or taking a required
        // agent's boot down with it.
        log.warn(
            "agent '{}' falls back to '{}' on {}, and no pool serves it, so this"
                + " agent runs with NO FALLBACK: a refusal from its own model is"
                + " its answer. Serve '{}' from a pool, or bind it in"
                + " plowshare.llm.classes, to enable it.",
            definition.name(),
            fallback.model(),
            fallback.on(),
            fallback.model());
        sampled = sampled.fallback(AgentDefinition.Fallback.NONE);
        fallback = sampled.fallback();
      }
      if (!fallback.on().isEmpty()) {
        // The fallback's requests carry what the SAME intent resolves to
        // on the fallback's OWN model. The agent's resolution is a fact
        // about a different model and would be sent to one it was never
        // measured against -- the exact "a file asserting the model's
        // shape" fault profiles exist to stop. The file's own explicit
        // overrides still win, as they do for the agent's model.
        String fallbackWire = dispatcher.wireModelFor(fallback.model());
        SamplingProfiles.Resolved fallbackMatch =
            profiles.resolve(fallbackWire, definition.intent());
        sampled =
            sampled.fallback(
                fallback.sampling(fallbackMatch.sampling().overriddenBy(definition.sampling())));
        log.info(
            "agent '{}' falls back to '{}' ({}) on {}, sending {}",
            definition.name(),
            fallback.model(),
            fallbackWire,
            fallback.on(),
            sampled.fallback().sampling().described());
      }
      resolved.put(entry.getKey(), sampled);
    }
    return new AgentRegistry.Loaded(
        resolved, loaded.disabled(), loaded.withheldEdges(), loaded.withheldTools());
  }

  /**
   * The check the registry cannot make for itself: it reads files and knows nothing about pools.
   *
   * <p>{@code LlmDispatcher.requireServed} exists for exactly this. Without it an agent on a model
   * no pool declares fails at its first run — in production, once, inside a job, where the whole
   * report is an {@code UNAVAILABLE} outcome naming a specifier and no file at all. The refusal is
   * re-wrapped rather than left to propagate so that it names the agent and the directory an
   * operator has to edit; the dispatcher's own message, which lists what <em>is</em> configured, is
   * kept.
   */
  private static void requireModelServed(
      LlmDispatcher dispatcher, AgentDefinition definition, String source) {
    try {
      dispatcher.requireServed(definition.model());
    } catch (LlmException unserved) {
      throw new IllegalStateException(
          "the agent '"
              + definition.name()
              + "' in "
              + source
              + " names the model '"
              + definition.model()
              + "', which no pool serves: "
              + unserved.getMessage(),
          unserved);
    }
  }

  /**
   * The second boot check, and the first capability this server has.
   *
   * <p>{@link #requireModelServed}'s twin, deliberately beside it and in the same {@code try}: both
   * are "this definition names a model the fleet cannot answer for", and both cost the agent rather
   * than the boot unless it is {@link #REQUIRED}. Sharing the {@code catch} is what keeps that
   * ladder one ladder — a second one would be a second place for "does this fault disable the agent
   * or stop the server" to be decided.
   *
   * <p><b>One capability, one check.</b> Not a tag system, not a selection policy, not roles as
   * queries — {@code 2026-09-07-a-class-means-one-thing-design.md} §5 deferred a general capability
   * system on the grounds that one designed against imagined requirements is worse than none, and
   * named the vision work as what would make it concrete. If a second modality lands and this has
   * to generalise, that is a better moment to design it than this one was.
   *
   * <p>An agent that declares nothing is not asked. That is not an oversight to be tightened later:
   * an agent with no {@code vision:} key is every agent in this tree, and putting them all behind a
   * check about a capability none of them uses would make a fleet with no vision model unable to
   * boot.
   */
  private static boolean served(LlmDispatcher dispatcher, String specifier) {
    try {
      dispatcher.requireServed(specifier);
      return true;
    } catch (LlmException unserved) {
      return false;
    }
  }

  private static void requireModelSees(
      LlmDispatcher dispatcher, AgentDefinition definition, String source) {
    if (!definition.vision()) {
      return;
    }
    try {
      dispatcher.requireSees(definition.model());
    } catch (LlmException blind) {
      throw new IllegalStateException(
          "the agent '"
              + definition.name()
              + "' in "
              + source
              + " declares 'vision:"
              + " true' and names the model '"
              + definition.model()
              + "', which is"
              + " not declared as one that sees: "
              + blind.getMessage(),
          blind);
    }
  }

  /**
   * The scribe the write path judges with.
   *
   * <p>The provider is passed as the supplier rather than resolved here: a resolution at
   * bean-creation time would fix the answer for the life of the context, and the whole point is
   * that a registry defined later is picked up.
   */
  @Bean
  public Scribe scribe(
      LlmDispatcher dispatcher, Archive archive, ObjectProvider<AgentRegistry> agents) {
    return new Scribe(dispatcher, archive, agents::getIfAvailable);
  }

  /**
   * The curator, which is Java orchestration over the runtime rather than an agent of its own.
   *
   * <p>There is deliberately no {@code curator.md}, and Task 9 recorded why: the listing, the
   * subtraction and the near-neighbour query are in code, so what is left for a model is one ruling
   * per candidate — which is {@code promotion_judge}. A second definition nothing runs would be a
   * shipped file that lies about what this server does.
   */
  @Bean
  public Curator curator(
      Archive archive,
      ProposalStore proposals,
      PromotionQueue queue,
      JobRuntime runtime,
      ObjectProvider<AgentRegistry> agents,
      Compaction compaction) {
    return new Curator(archive, proposals, queue, runtime, agents::getIfAvailable, compaction);
  }

  /**
   * The jobs this process is running, and the virtual threads they run on.
   *
   * <p>{@code destroyMethod} rather than a shutdown hook: {@link JobStore#close} asks every running
   * job to stop at its next turn boundary and then shuts the executor down, and a context that went
   * away while jobs were still submitting model calls would leave a lane held by a run nobody can
   * poll for.
   *
   * @param events where a job's lifecycle goes: the {@code EventChannelHandler} bean, injected
   *     under the seam's name for the reason {@link #runProviders} takes {@link SessionChannel}
   *     rather than a handler. Through an {@link ObjectProvider} and defaulting to {@link
   *     JobEvents#NONE}, on this file's standing rule about degraded states: a context with no
   *     event channel is a legal, running server whose jobs are simply watched by nobody, and the
   *     stream is droppable by design — so the absent case is the one every run already has to
   *     survive rather than a wiring hole worth refusing over
   * @param jobs the durable record of every run this store starts. Required rather than an {@link
   *     ObjectProvider}, unlike {@code events}: a server with no event channel is a running server
   *     whose jobs nobody watches, and a server with no {@code jobs} table is a server whose job
   *     ids mean nothing tomorrow — which is the defect {@code V24__jobs.sql} exists to close, and
   *     not a degraded state worth wiring for
   * @param properties read for one key, {@code plowshare.jobs.heartbeat}: how often a run in flight
   *     publishes that it is still there. This is the one wiring in the repository that passes an
   *     interval — every other {@link JobStore} is a fixture on {@link JobStore#HEARTBEAT}, which
   *     is also this key's own default, so a server nobody has configured beats at exactly the
   *     interval the tests were written against
   * @param runEnds who hears that a run ended, so a union can commit what it wrote. Through an
   *     {@link ObjectProvider} and defaulting to {@link RunEnds#NONE} on the same standing rule as
   *     {@code events}: a server with no union configured is a legal server whose runs are simply
   *     not reported to anybody
   */
  @Bean(destroyMethod = "close")
  public JobStore jobStore(
      JobRuntime runtime,
      ObjectProvider<JobEvents> events,
      Compaction compaction,
      JobLog jobs,
      JobsProperties properties,
      ObjectProvider<RunEnds> runEnds,
      io.aeyer.plowshare.server.access.ProjectAuthorization projectAccess) {
    JobStore store =
        new JobStore(
            runtime,
            events.getIfAvailable(() -> JobEvents.NONE),
            compaction,
            jobs,
            properties.getHeartbeat());
    store.useProjectAccess(projectAccess);
    store.onRunEnded(runEnds.getIfAvailable(() -> RunEnds.NONE));
    return store;
  }

  /**
   * The door an utterance comes through.
   *
   * <p>Wired here rather than annotated for the same reason nothing else in this package is a
   * component: {@link Turn} is framework-free and holds four collaborators that are themselves
   * wired by configuration classes. It is unconditional, unlike {@link AgentRegistry} above — a
   * server with no agent directory is a legal server that runs nothing, but a server that can reach
   * Postgres can always hold a conversation, and the definition an utterance names is checked at
   * the controller before this is asked anything.
   */
  @Bean
  public Turn turn(
      JobStore jobs, ConversationStore conversations, TurnStore turns, Compaction compaction) {
    return new Turn(jobs, conversations, turns, compaction);
  }

  @Bean
  public AutomaticLimits automaticLimits(
      Environments environments, ConversationStore conversations, JobRuntime runtime, Turn turn) {
    var limits = new AutomaticLimits(environments, conversations);
    runtime.useAutomaticLimits(limits);
    turn.useAutomaticLimits(limits);
    return limits;
  }

  /**
   * What a conversation puts in front of its next turn.
   *
   * <p>Wired here rather than in {@code ArchiveConfig} even though three of its four collaborators
   * are archive stores, because what it does is a decision about a run: it reads the model's loaded
   * context length off the dispatcher and makes a summarising call. The archive holds rows; this
   * spends model calls.
   *
   * <p>{@code destroyMethod} for {@link JobStore}'s reason, one thread pool over: a fold runs on a
   * virtual thread of this bean's own, and a context that went away without shutting the executor
   * down would leave it accepting work nothing is going to poll for. {@link Compaction#close} does
   * not wait for a fold in flight and says why.
   *
   * <p>The fourth is {@link EntryStore}, and it arrives here for two jobs that are the same job
   * read from either end: a fold appends its summary to the log and marks what it covers, and every
   * message a turn sends is recorded through the {@code TurnTranscript} this class hands each run.
   * Nothing reads either yet.
   */
  /**
   * <b>{@link LlmProperties} arrives here for one number.</b> {@code
   * plowshare.llm.default-context-length} is the bottom tier of context-length resolution and the
   * reason compaction cannot be switched off by a node that will not say how big it is. It is bound
   * under {@code plowshare.llm} because it is a fact about models rather than about agents, and it
   * is carried by {@code Compaction} because deciding what to do when nobody can size a model is
   * compaction's decision and nobody else's.
   */
  @Bean(destroyMethod = "close")
  public Compaction compaction(
      LlmDispatcher models,
      TurnStore turns,
      CompactionStore compactions,
      EntryStore entries,
      LlmProperties llm,
      ConversationStore conversations,
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<Learning> learning,
      ObjectProvider<Citing> citing,
      ObjectProvider<io.aeyer.plowshare.server.agents.digests.Digester> digester,
      ObjectProvider<io.aeyer.plowshare.server.llm.tokens.Tokenizer> tokenizer) {
    Compaction compaction =
        new Compaction(
            models,
            // Through the provider and resolved per fold, for jobRuntime's
            // reason exactly: this bean is built before the registry, and a
            // definition read once at bean-creation time would be the
            // conversation_folder.md of whichever boot won the race rather
            // than the one an operator is editing. A miss is the registry's
            // to report -- get(...) throws, summarise catches, and the turn
            // runs with its whole history.
            () -> agents.getObject().get(Compaction.FOLDER),
            turns,
            compactions,
            entries,
            llm.getDefaultContextLength(),
            conversations,
            () -> {
              try {
                learning.getIfAvailable(() -> Learning.NONE).thereIsMaterial();
              } finally {
                var pass = digester.getIfAvailable();
                if (pass != null) pass.folded();
              }
            },
            // Through a provider for `learning`'s reason exactly: a
            // deployment with no corpus configuration is a running server,
            // and one whose turns close as they always did.
            citing.getIfAvailable(() -> Citing.NONE));
    // What counts a step's results when a fold inside a turn is decided: the server's one
    // Tokenizer (spec 2026-09-30-fold-at-60-and-80 §1). LlmConfig always defines one.
    tokenizer.ifAvailable(compaction::useTokenizer);
    return compaction;
  }

  /**
   * What reads a folded span and asks whether anything in it was worth keeping.
   *
   * <p>Wired here beside {@link Scribe} and {@link Curator}, and for their reason: it is
   * framework-free, it holds collaborators from two packages, and what it does is spend model calls
   * rather than hold rows. The registry arrives as a supplier for {@code Scribe}'s reason exactly —
   * a resolution at bean-creation time would fix the answer for the life of the context, and the
   * point is that a registry defined later is picked up.
   *
   * <p><b>It takes the {@link Scribe} bean rather than making its own.</b> The learner proposes and
   * does not write, and "the existing pipeline takes over" has to mean the same instance a person's
   * write goes through: one scribe, one set of candidates, one measurement.
   */
  @Bean
  public Learner learner(
      LlmDispatcher models,
      EntryStore entries,
      ConversationStore conversations,
      Archive archive,
      Scribe scribe,
      ObjectProvider<AgentRegistry> agents,
      ObjectProvider<io.aeyer.plowshare.server.archive.DigestStore> digests) {
    return new Learner(models, entries, conversations, archive, scribe, agents::getIfAvailable)
        .withProvenance(digests.getIfAvailable());
  }
}
