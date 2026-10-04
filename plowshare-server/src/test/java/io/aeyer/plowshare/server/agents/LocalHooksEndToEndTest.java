package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Found;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Needle;
import io.aeyer.plowshare.protocol.Span;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.protocol.Window;
import io.aeyer.plowshare.server.archive.ConversationRecord;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.archive.LocalHookSetStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.files.Changed;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.hooks.script.HooksProperties;
import io.aeyer.plowshare.server.hooks.script.ScriptHooks;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spec 2026-09-30-local-hooks-are-served §7, end to end: a TUI session roots a project with a hook
 * file, opens a conversation and runs a turn. The session is simulated at the file channel ({@link
 * FakeFiles} answers the glob and reads the TUI's enforcer would) and at its two sockets (a real
 * {@link SessionRegistry}, each role attached as the account it signed in as); everything after
 * that is real: swc4j and GraalJS run the hook, Postgres holds the snapshot, the pin and the {@code
 * log.open} entries, and the hook's records carry {@code tier: 'local'}.
 */
@Testcontainers
class LocalHooksEndToEndTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home LEDGER = Home.of("ledger");
  private static final String SESSION = "tui-1";
  private static final String OWNER = "enzo";
  private static final String STRANGER = "mallory";

  private static final String PROJECT =
      """
            export default { name: 'project-yes', stages: {
                'tool.pre': { tools: ['probe_write'], handle() { return { allow: true } } },
            } }
            """;

  private static final String MINE =
      """
            import type { Hook } from '@plowshare/hooks'

            export default {
                name: 'no-todo-on-my-watch',
                stages: {
                    'log.open': { handle() { return { add: 'The person keeps local hooks.' } } },
                    'tool.pre': {
                        tools: ['probe_write'],
                        handle(call) {
                            return String(call.args.content ?? '').includes('TODO')
                                ? { deny: 'no TODOs leave my laptop' } : undefined
                        },
                    },
                },
            } satisfies Hook
            """;

  private static final String MY_RUN_OK =
      """
            export default { name: 'my-run-ok', stages: {
                'tool.pre': { tools: ['run'], handle() { return { allow: true } } },
            } }
            """;

  private static final String PROJECT_RUN_OK =
      """
            export default { name: 'project-run-ok', stages: {
                'tool.pre': { tools: ['run'], handle() { return { allow: true } } },
            } }
            """;

  private static JdbcTemplate jdbc;

  @TempDir Path data;

  private ConversationStore conversations;
  private TurnStore turns;
  private EntryStore entries;
  private LocalHookSetStore sets;
  private HookEngine engine;
  private HooksProperties properties;
  private ScriptHooks project;
  private SessionRegistry registry;
  private final List<ScriptHooks> locals = new ArrayList<>();
  private final AtomicReference<SessionChannel> serving = new AtomicReference<>();

  /** A socket, as the registry holds it: signed in as {@code handle}. */
  private record Socket(String handle) {}

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() throws Exception {
    jdbc.execute(
        "TRUNCATE TABLE entries, turns, conversations, local_hook_sets, admins" + " CASCADE");
    jdbc.update(
        "INSERT INTO admins (handle, password_hash) VALUES (?, 'h'), (?, 'h')", OWNER, STRANGER);
    conversations = new ConversationStore(jdbc);
    turns = new TurnStore(jdbc);
    entries = new EntryStore(jdbc);
    sets = new LocalHookSetStore(jdbc);
    engine = new HookEngine();
    properties = new HooksProperties();
    properties.setTimeout(Duration.ofSeconds(2));
    Files.createDirectories(projectHooks());
    Files.writeString(projectHooks().resolve("10-yes.js"), PROJECT);
    project =
        new ScriptHooks(
            name -> "ledger".equals(name) ? 7L : null,
            id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
            engine,
            properties,
            Instant::now);
    serving.set(
        new FakeFiles()
            .withListing(".plowshare/hooks", List.of("10-mine.ts"))
            .withFile(".plowshare/hooks/10-mine.ts", MINE));
    registry = new SessionRegistry();
  }

  @AfterEach
  void close() {
    locals.forEach(ScriptHooks::close);
    project.close();
    engine.close();
  }

  private Path projectHooks() {
    return data.resolve("projects").resolve("7").resolve("hooks");
  }

  /** The TUI signs in as {@code account} on both sockets: the listener, then the file channel. */
  private void signedInAs(String account) {
    assertFalse(registry.attach(SESSION, Role.LISTENER, new Socket(account), account).refused());
    assertFalse(
        registry.attach(SESSION, Role.FILE_PROVIDER, new Socket(account), account).refused());
  }

  /** The account a role's socket signed in as: what each channel's {@code handleOf} answers. */
  private Function<String, Optional<String>> handleOn(Role role) {
    return session ->
        registry
            .find(session)
            .flatMap(held -> held.attached(role, Socket.class))
            .map(Socket::handle);
  }

  /** As {@code HooksConfig.localHooks} wires it: a local allow counts on the owner's session. */
  private ScriptHooks local() {
    ScriptHooks one =
        ScriptHooks.local(
            conversations::localHooksOf,
            conversations::ownerOf,
            sets::find,
            (log, session) -> {
              Optional<String> owner = conversations.ownerOf(log);
              return owner.isPresent() && owner.equals(registry.accountOf(session));
            },
            engine,
            properties,
            Instant::now);
    locals.add(one);
    return one;
  }

  /**
   * The server's door, as {@code LogStagesConfig} wires it: pins over the session's channel, held
   * three ways, then log.open over project and local. The log is {@link #OWNER}'s.
   */
  private String opened(ScriptHooks local) {
    PinnedLocalHooks pins =
        new PinnedLocalHooks(
            session -> new ChannelHooks(serving.get(), session).read(),
            AgentsConfig.sessionLive(registry),
            (named, session) -> "ledger".equals(named) && SESSION.equals(session),
            new PinnedLocalHooks.Accounts(
                registry::accountOf, handleOn(Role.LISTENER), handleOn(Role.FILE_PROVIDER)),
            conversations,
            sets);
    HookedLogStages stages =
        new HookedLogStages(
            Hooks.chain(project, local),
            conversations,
            turns,
            conversation -> false,
            entries,
            (handle, kind, text) -> {},
            name -> false,
            Instant::now,
            properties::getTimeout,
            new WordsTokenizer(),
            pins);
    ConversationRecord row = conversations.open(LEDGER, Budget.of(20), null, OWNER);
    stages.opened(LogStages.LogOpened.ofConversation(row, SESSION));
    return row.id();
  }

  /** The laptop closes: the file channel goes, and with it anything a re-read could see. */
  private void laptopCloses() {
    serving.set(new FakeFiles().thatIsClosed());
    registry
        .find(SESSION)
        .flatMap(held -> held.attached(Role.FILE_PROVIDER, Socket.class))
        .ifPresent(socket -> registry.detach(SESSION, Role.FILE_PROVIDER, socket));
  }

  @Test
  void a_session_s_hook_is_snapshotted_at_open_and_refuses_a_call_the_project_allowed()
      throws Exception {
    signedInAs(OWNER);
    String log = opened(local());
    // The laptop closes after the open: the log keeps what it opened with.
    laptopCloses();
    Probe probe = new Probe();
    Scripted model = writesATodo();
    Log transcript = new Log(log);

    Outcome outcome = turn(model, probe, transcript, Hooks.chain(project, locals.get(0)));

    assertEquals(Outcome.Ending.ANSWERED, outcome.ending());
    assertEquals(List.of(), probe.seen, "the write never ran");
    String shown =
        model.sent.get(1).stream()
            .filter(m -> m.role() == ChatMessage.Role.TOOL)
            .map(ChatMessage::content)
            .findFirst()
            .orElseThrow();
    assertTrue(shown.contains("no TODOs leave my laptop"), shown);
    List<String> hooks =
        transcript.entries.stream()
            .filter(e -> e.kind() == EntryKind.HOOK)
            .map(LoggedEntry::content)
            .toList();
    int allowed = indexOf(hooks, "project-yes");
    int denied = indexOf(hooks, "no-todo-on-my-watch");
    assertTrue(allowed >= 0 && denied > allowed, "project first, then local: " + hooks);
    assertTrue(hooks.get(allowed).contains("\"tier\":\"project\""), hooks.get(allowed));
    String mine = hooks.get(denied);
    assertTrue(
        mine.contains("\"tier\":\"local\"")
            && mine.contains("\"file\":\"10-mine.ts\"")
            && mine.contains("\"decision\":\"deny\""),
        mine);
    assertEquals(
        Optional.of("The person keeps local hooks."),
        conversations.opening(log),
        "the local tier saw its own log.open");
    assertTrue(
        entries.forConversation(log).stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.HOOK
                        && entry.content().contains("\"stage\":\"log.open\"")
                        && entry.content().contains("\"tier\":\"local\"")),
        "the log.open decision is in the archived log");
    assertTrue(conversations.localHooksOf(log).isPresent(), "the log is pinned");
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class));
  }

  @Test
  void a_restarted_server_runs_the_snapshot_from_the_table() throws Exception {
    signedInAs(OWNER);
    ScriptHooks before = local();
    String log = opened(before);
    before.close();
    laptopCloses();
    Probe probe = new Probe();
    Log transcript = new Log(log);

    turn(writesATodo(), probe, transcript, Hooks.chain(project, local()));

    assertEquals(List.of(), probe.seen, "the snapshot, not the gone session, decided");
    assertTrue(
        indexOf(
                transcript.entries.stream()
                    .filter(e -> e.kind() == EntryKind.HOOK)
                    .map(LoggedEntry::content)
                    .toList(),
                "no-todo-on-my-watch")
            >= 0);
  }

  @Test
  void a_session_another_account_holds_lends_this_log_no_local_tier() throws Exception {
    // The session and both its sockets are mallory's, and a caller named it for enzo's log.
    signedInAs(STRANGER);
    String log = opened(local());
    Probe probe = new Probe();
    Log transcript = new Log(log);

    turn(writesATodo(), probe, transcript, Hooks.chain(project, locals.get(0)));

    assertNoLocalTier(log, transcript);
    assertEquals(1, probe.seen.size(), "only the project's allow judged the write, and it ran");
  }

  @Test
  void a_file_channel_opened_as_another_account_serves_no_hooks() throws Exception {
    // enzo's listener holds the session; mallory's file channel is refused and displaces
    // nothing, so the session has no file channel and nothing is read.
    assertFalse(registry.attach(SESSION, Role.LISTENER, new Socket(OWNER), OWNER).refused());
    assertTrue(
        registry.attach(SESSION, Role.FILE_PROVIDER, new Socket(STRANGER), STRANGER).refused(),
        "another account's file channel joins no one's session");
    String refused = opened(local());
    assertNoLocalTier(refused, new Log(refused));

    // Were a file channel ever to be signed in as another account than the one the registry
    // holds the session for, the pin asks the socket too, and still lends nothing.
    registry.attach(SESSION, Role.FILE_PROVIDER, new Socket(STRANGER), OWNER);
    String disagreed = opened(local());
    Probe probe = new Probe();
    Log transcript = new Log(disagreed);

    turn(writesATodo(), probe, transcript, Hooks.chain(project, locals.get(1)));

    assertNoLocalTier(disagreed, transcript);
    assertEquals(1, probe.seen.size(), "only the project's allow judged the write, and it ran");
  }

  @Test
  void a_local_allow_on_a_gated_server_side_run_does_not_run_the_command() throws Exception {
    serving.set(
        new FakeFiles()
            .withListing(".plowshare/hooks", List.of("10-my-run-ok.js"))
            .withFile(".plowshare/hooks/10-my-run-ok.js", MY_RUN_OK));
    signedInAs(OWNER);
    String log = opened(local());
    Path environment = data.resolve("environment.yml");
    Files.writeString(environment, "server:\n  mode: gated\nlocal:\n  mode: gated\n");
    Machine server = new Machine(Files.createDirectories(data.toRealPath().resolve("repo")));
    Log transcript = new Log(log);

    String shown = runTurn(server, environment, transcript, Hooks.chain(project, locals.get(0)));

    assertEquals(List.of(), server.ran, "a local allow approved a gated server command");
    assertTrue(
        shown.startsWith(
            "the call was refused by a hook: the environment on the server"
                + " side is gated, and no hook in this project allowed this command"),
        shown);
    List<String> hooks =
        transcript.entries.stream()
            .filter(e -> e.kind() == EntryKind.HOOK)
            .map(LoggedEntry::content)
            .toList();
    String mine = hooks.get(indexOf(hooks, "my-run-ok"));
    assertTrue(
        mine.contains("\"tier\":\"local\"")
            && mine.contains("\"decision\":\"allow\"")
            && mine.contains("counts only for a command on the local side"),
        mine);

    // The control: the same command, the same gate, allowed by the project, runs.
    Files.writeString(projectHooks().resolve("20-run.js"), PROJECT_RUN_OK);
    runTurn(server, environment, new Log(log), Hooks.chain(project, locals.get(0)));

    assertEquals(List.of(List.of("./gradlew", "test")), server.ran);
  }

  private void assertNoLocalTier(String log, Log transcript) {
    assertEquals(Optional.empty(), conversations.localHooksOf(log), "nothing is pinned");
    assertEquals(
        0,
        jdbc.queryForObject("SELECT count(*) FROM local_hook_sets", Integer.class),
        "nothing was read and stored");
    assertEquals(Optional.empty(), conversations.opening(log), "no local log.open ran");
    assertTrue(
        entries.forConversation(log).stream()
            .noneMatch(
                entry ->
                    entry.kind() == EntryKind.HOOK
                        && entry.content().contains("\"tier\":\"local\"")),
        "the archived log has no local record, and no word of why");
    assertTrue(
        transcript.entries.stream()
            .noneMatch(
                entry ->
                    entry.kind() == EntryKind.HOOK
                        && entry.content().contains("\"tier\":\"local\"")),
        transcript.entries.toString());
  }

  private static int indexOf(List<String> hooks, String name) {
    for (int i = 0; i < hooks.size(); i++) {
      if (hooks.get(i).contains("\"hook\":\"" + name + "\"")) {
        return i;
      }
    }
    return -1;
  }

  private static Scripted writesATodo() {
    return new Scripted()
        .then(
            () ->
                new Completion(
                    "",
                    "tool_calls",
                    TokenUsage.UNKNOWN,
                    List.of(new ToolCall("c1", "probe_write", "{\"content\":\"TODO: later\"}"))))
        .then(() -> new Completion("I could not write it.", "stop", TokenUsage.UNKNOWN, List.of()));
  }

  private Outcome turn(Scripted model, Probe probe, Transcript log, Hooks chain) throws Exception {
    JobRuntime runtime =
        new JobRuntime(
            new LlmDispatcher(
                List.of(
                    new LlmPool(
                        "scripted",
                        List.of("model-fast"),
                        Map.of("fast", "model-fast"),
                        4,
                        1,
                        Duration.ofSeconds(5),
                        model)),
                new NoOpTokenLedger()),
            List.of(probe));
    runtime.useHooks(chain);
    Path fixtures = Path.of(LocalHooksEndToEndTest.class.getResource("/agents").toURI());
    AgentDefinition contrarian =
        AgentRegistry.of(
                fixtures,
                Set.of("probe_read", "probe_write", "memory_write", AgentRegistry.AGENT_RUN))
            .get("contrarian");
    return runtime.run(
        contrarian,
        "save the note",
        LEDGER,
        Budget.of(20),
        () -> false,
        SESSION,
        JobWatch.UNWATCHED,
        log);
  }

  /** A turn whose model asks to run the build on {@code server}; what the model was shown. */
  private String runTurn(Machine server, Path environment, Log log, Hooks chain) throws Exception {
    Scripted model =
        new Scripted()
            .then(
                () ->
                    new Completion(
                        "",
                        "tool_calls",
                        TokenUsage.UNKNOWN,
                        List.of(
                            new ToolCall(
                                "c1", RunTool.NAME, "{\"command\": [\"./gradlew\", \"test\"]}"))))
            .then(() -> new Completion("done", "stop", TokenUsage.UNKNOWN, List.of()));
    JobRuntime runtime =
        new JobRuntime(
            new LlmDispatcher(
                List.of(
                    new LlmPool(
                        "scripted",
                        List.of("model-fast"),
                        Map.of("fast", "model-fast"),
                        4,
                        1,
                        Duration.ofSeconds(5),
                        model)),
                new NoOpTokenLedger()),
            List.of(),
            null,
            (home, grants, sessionId, owner) -> List.of(server));
    runtime.useEnvironments(new Environments(name -> 7L, id -> environment, null));
    runtime.useHooks(chain);
    Path agents = Files.createDirectories(data.resolve("agents-" + System.nanoTime()));
    Files.writeString(
        agents.resolve("coder.md"),
        """
                ---
                name: coder
                description: runs the build
                model: fast
                tools: [run]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                ---
                You run the build.
                """);
    AgentDefinition coder = AgentRegistry.of(agents, runtime.knownTools()).get("coder");
    runtime.run(
        coder,
        "run the tests",
        LEDGER,
        Budget.of(20),
        () -> false,
        SESSION,
        JobWatch.UNWATCHED,
        log);
    return model.sent.get(1).stream()
        .filter(m -> m.role() == ChatMessage.Role.TOOL)
        .map(ChatMessage::content)
        .findFirst()
        .orElseThrow();
  }

  // --- the fixtures ProjectHooksEndToEndTest uses, the log named by the archived row ----------

  private static final class Scripted implements LlmTransport {
    private final List<Supplier<Completion>> steps = new ArrayList<>();
    final List<List<ChatMessage>> sent = Collections.synchronizedList(new ArrayList<>());

    Scripted then(Supplier<Completion> step) {
      steps.add(step);
      return this;
    }

    @Override
    public String poolName() {
      return "scripted";
    }

    @Override
    public Completion complete(
        String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
      int at = sent.size();
      sent.add(List.copyOf(messages));
      return at < steps.size()
          ? steps.get(at).get()
          : new Completion("done", "stop", TokenUsage.UNKNOWN, List.of());
    }

    @Override
    public Completion stream(
        String model,
        List<ChatMessage> messages,
        Sampling sampling,
        List<ToolSchema> tools,
        Deltas sink,
        BooleanSupplier abandoned) {
      return complete(model, messages, sampling, tools);
    }

    @Override
    public Embeddings embed(String model, List<String> input) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}
  }

  private static final class Log implements Transcript {
    final List<LoggedEntry> entries = Collections.synchronizedList(new ArrayList<>());
    private final String id;

    Log(String id) {
      this.id = id;
    }

    @Override
    public List<ChatMessage> before() {
      return List.of();
    }

    @Override
    public String conversationId() {
      return id;
    }

    @Override
    public void promptMeasured(int promptTokens) {}

    @Override
    public void record(LoggedEntry entry) {
      entries.add(entry);
    }
  }

  private static final class Probe implements AgentTool {
    final List<String> seen = Collections.synchronizedList(new ArrayList<>());

    @Override
    public ToolSchema schema() {
      return new ToolSchema(
          "probe_write", "a probe", Map.of("type", "object", "properties", Map.of()));
    }

    @Override
    public String run(String argumentsJson, Home home) {
      seen.add(argumentsJson);
      return "written";
    }
  }

  /** The server's own disk, as RunGateWiringTest's: records what it was asked to run. */
  private static final class Machine implements FileProvider {
    final List<List<String>> ran = Collections.synchronizedList(new ArrayList<>());
    private final Path repo;

    Machine(Path repo) {
      this.repo = repo;
    }

    @Override
    public String name() {
      return "local";
    }

    @Override
    public List<Path> roots() {
      return List.of(repo);
    }

    @Override
    public CommandRunner.Outcome run(
        Path cwd,
        List<String> argv,
        EnvironmentFile.Side side,
        Duration timeout,
        BooleanSupplier cancelled) {
      ran.add(argv);
      return new CommandRunner.Outcome(0, false, false, "built", 0, "", 0, 5);
    }

    @Override
    public Span read(Path path, Window window) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Span stat(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Path> glob(String pattern) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Found grep(Needle needle, Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Changed write(Path path, String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Changed create(Path path, String content) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Changed edit(Path path, String old, String replacement) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Changed delete(Path path) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Changed move(Path from, Path to) {
      throw new UnsupportedOperationException();
    }
  }
}
