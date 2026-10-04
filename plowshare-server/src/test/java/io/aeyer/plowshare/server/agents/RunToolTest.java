package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.CommandRunner;
import io.aeyer.plowshare.protocol.EnvironmentFile;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.files.FileProvider;
import io.aeyer.plowshare.server.files.Grant;
import io.aeyer.plowshare.server.files.LocalProvider;
import io.aeyer.plowshare.server.files.Mode;
import io.aeyer.plowshare.server.files.ProviderRouter;
import io.aeyer.plowshare.server.files.RemoteProvider;
import io.aeyer.plowshare.server.files.Scope;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.ToolPre;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.hooks.script.HooksProperties;
import io.aeyer.plowshare.server.hooks.script.ScriptHooks;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** run and its gate, over this server's own disk. Spec 2026-09-14, run. */
@Testcontainers
class RunToolTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");
  private static final Grant READ = new Grant(Scope.WORKSPACE, Mode.READ);
  private static final Grant WRITE = new Grant(Scope.WORKSPACE, Mode.WRITE);
  private static final HookContext CONTEXT =
      new HookContext("coder", false, Set.of(RunTool.NAME), "payments", null, HookContext.SERVER);

  private static JdbcTemplate jdbc;

  @TempDir Path tmp;

  private Path repo;
  private Path outside;
  private Path environment;
  private ProjectStore store;
  private List<Grant> grants = List.of(WRITE);

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void fresh() throws IOException {
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, projects CASCADE");
    Path real = tmp.toRealPath();
    repo = Files.createDirectory(real.resolve("repo"));
    outside = Files.createDirectory(real.resolve("outside"));
    environment = real.resolve("environment.yml");
    Path server = Files.createDirectory(real.resolve("srv"));
    store =
        new ProjectStore(
            jdbc,
            Files.writeString(server.resolve("plowshare.yml"), "a fixture"),
            Files.createDirectory(server.resolve("profiles")),
            server.resolve("console-token"),
            server.resolve("exports"),
            server.resolve("data"));
    store.define("payments", repo, List.of());
    grants = List.of(WRITE);
  }

  private RunTool tool() {
    ProviderRouter router =
        new ProviderRouter(home -> List.of(new LocalProvider(store, home, grants)));
    Environments environments = new Environments(store::id, id -> environment, null);
    return new RunTool(router, () -> environments, () -> false);
  }

  private void environment(String text) throws IOException {
    Files.writeString(environment, text);
  }

  private static String call(String... argv) {
    StringBuilder out = new StringBuilder("{\"command\": [");
    for (int i = 0; i < argv.length; i++) {
      out.append(i > 0 ? ", " : "").append('"').append(argv[i].replace("\"", "\\\"")).append('"');
    }
    return out.append("]}").toString();
  }

  /** The chain as a project with no hook for run answers it. */
  private static final BiFunction<HookContext, String, ToolPre> SILENT =
      (context, args) -> ToolPre.allowed(args);

  /** The chain as a project whose hook explicitly allows. */
  private static final BiFunction<HookContext, String, ToolPre> ALLOWS =
      (context, args) -> new ToolPre(args, null, List.of(), true);

  @Test
  void with_no_environment_file_nothing_runs() {
    ToolPre gated = tool().gate(call("true"), PAYMENTS, CONTEXT, SILENT);

    assertTrue(gated.isDenied());
    assertTrue(gated.denied().contains("mode off"), gated.denied());
    assertEquals(HookRecord.DENY, gated.records().get(0).decision());
    assertEquals(RunTool.GATE, gated.records().get(0).hook());
  }

  @Test
  void an_open_server_side_runs_the_command_and_reports_how_it_ended() throws IOException {
    environment("server:\n  mode: open\n");
    RunTool tool = tool();

    ToolPre gated = tool.gate(call("printf", "hello"), PAYMENTS, CONTEXT, SILENT);
    String out = tool.run(gated.arguments(), PAYMENTS);

    assertFalse(gated.isDenied(), gated.denied());
    assertTrue(out.startsWith("exit 0 after"), out);
    assertTrue(out.contains("on the server side"), out);
    assertTrue(out.contains("--- stdout ---\nhello"), out);
    assertTrue(out.contains("--- stderr ---\n(nothing)"), out);
  }

  @Test
  void a_shell_that_changes_code_and_exits_nonzero_still_reconciles_the_map() throws Exception {
    environment("server:\n  mode: open\n  shells: true\n");
    Path file = Files.writeString(repo.resolve("A.java"), "class Before {}");
    LocalProvider provider = org.mockito.Mockito.spy(new LocalProvider(store, PAYMENTS, grants));
    ProviderRouter router = new ProviderRouter(home -> List.of(provider));
    var map = new io.aeyer.plowshare.server.files.WorkspaceCodeMap(router, () -> false);
    var old = map.reconcile(PAYMENTS, "**");
    org.mockito.Mockito.clearInvocations(provider);
    var environments = new Environments(store::id, id -> environment, null);
    var tool = new RunTool(router, () -> environments, () -> false).withCodeMap(map);
    String out = tool.run(call("sh", "-c", "printf 'class After {}' > A.java; exit 7"), PAYMENTS);
    assertTrue(out.startsWith("exit 7 after"), out);
    org.mockito.Mockito.verify(provider, org.mockito.Mockito.atLeastOnce()).fingerprint(file);
    org.junit.jupiter.api.Assertions.assertThrows(
        io.aeyer.plowshare.server.files.WorkspaceRefusedException.class, () -> map.verify(old));
    assertEquals("class After {}", map.reconcile(PAYMENTS, null).files().getFirst().text());
  }

  @Test
  void ambiguous_command_delivery_invalidates_even_when_reconciliation_also_fails()
      throws Exception {
    environment("server:\n  mode: open\n");
    Path file = Files.writeString(repo.resolve("A.java"), "class Before {}");
    LocalProvider provider = org.mockito.Mockito.spy(new LocalProvider(store, PAYMENTS, grants));
    var router = new ProviderRouter(home -> List.of(provider));
    var map = new io.aeyer.plowshare.server.files.WorkspaceCodeMap(router, () -> false);
    var old = map.reconcile(PAYMENTS, "**");
    org.mockito.Mockito.doAnswer(
            call -> {
              Files.writeString(file, "class Delivered {}");
              org.mockito.Mockito.doThrow(
                      new io.aeyer.plowshare.server.files.WorkspaceUnavailableException(
                          "scan outage"))
                  .when(provider)
                  .fingerprint(file);
              throw new io.aeyer.plowshare.server.files.WorkspaceUnavailableException(
                  "delivery ambiguous");
            })
        .when(provider)
        .run(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.any());
    var environments = new Environments(store::id, id -> environment, null);
    var tool = new RunTool(router, () -> environments, () -> false).withCodeMap(map);
    var failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            io.aeyer.plowshare.server.files.WorkspaceUnavailableException.class,
            () -> tool.run(call("true"), PAYMENTS));
    assertEquals("delivery ambiguous", failure.getMessage());
    org.junit.jupiter.api.Assertions.assertThrows(
        io.aeyer.plowshare.server.files.WorkspaceRefusedException.class, () -> map.verify(old));
    var now = map.reconcile(PAYMENTS, null);
    assertEquals("partial", now.state());
    assertTrue(now.files().isEmpty());
  }

  @Test
  void the_hooks_are_told_the_environment_of_the_side_the_command_would_run_on()
      throws IOException {
    environment("server:\n  mode: open\n  shells: true\n");
    AtomicReference<HookContext> seen = new AtomicReference<>();

    tool()
        .gate(
            call("true"),
            PAYMENTS,
            CONTEXT,
            (context, args) -> {
              seen.set(context);
              return ToolPre.allowed(args);
            });

    assertEquals(
        new HookContext.RunEnvironment("server", "open", true, "none"), seen.get().environment());
  }

  @Test
  void a_shell_is_refused_unless_the_environment_allows_one() throws IOException {
    environment("server:\n  mode: open\n");
    ToolPre refused = tool().gate(call("bash", "-c", "true"), PAYMENTS, CONTEXT, SILENT);
    assertTrue(refused.isDenied());
    assertTrue(refused.denied().contains("is a shell"), refused.denied());

    environment("server:\n  mode: open\n  shells: true\n");
    assertFalse(tool().gate(call("sh", "-c", "true"), PAYMENTS, CONTEXT, SILENT).isDenied());
  }

  @Test
  void gated_needs_a_hook_that_explicitly_allows() throws IOException {
    environment("server:\n  mode: gated\n");

    ToolPre silent = tool().gate(call("true"), PAYMENTS, CONTEXT, SILENT);
    ToolPre allowed = tool().gate(call("true"), PAYMENTS, CONTEXT, ALLOWS);

    assertTrue(silent.isDenied());
    assertTrue(silent.denied().contains("allow: true"), silent.denied());
    assertFalse(allowed.isDenied(), allowed.denied());
  }

  @Test
  void a_hook_that_denies_is_the_answer_even_when_open() throws IOException {
    environment("server:\n  mode: open\n");

    ToolPre denied =
        tool()
            .gate(
                call("true"),
                PAYMENTS,
                CONTEXT,
                (context, args) -> new ToolPre(args, "'allowlist': not this one", List.of()));

    assertEquals("'allowlist': not this one", denied.denied());
  }

  @Test
  void a_server_file_that_cannot_be_read_turns_both_sides_off_and_says_why() throws IOException {
    environment("server:\n  mode: sure\n");

    ToolPre gated = tool().gate(call("true"), PAYMENTS, CONTEXT, SILENT);

    assertTrue(gated.denied().contains("could not be read"), gated.denied());
    assertTrue(gated.denied().contains("line 2"), gated.denied());
  }

  @Test
  void the_local_side_of_the_server_file_does_not_open_the_server() throws IOException {
    environment("local:\n  mode: open\n");

    assertTrue(tool().gate(call("true"), PAYMENTS, CONTEXT, SILENT).isDenied());
  }

  @Test
  void the_global_tier_runs_nothing() {
    String out = tool().run(call("true"), Home.global());

    assertTrue(out.contains("global tier"), out);
  }

  @Test
  void the_global_tier_refusal_comes_before_a_bad_cwd_is_even_read() {
    String out = tool().run("{\"command\": [\"true\"], \"cwd\": \"src\"}", Home.global());

    assertTrue(out.contains("global tier"), out);
  }

  @Test
  void a_read_only_agent_cannot_run_a_command() throws IOException {
    environment("server:\n  mode: open\n");
    grants = List.of(READ);

    String out = tool().run(call("true"), PAYMENTS);

    assertTrue(out.contains("read-only"), out);
  }

  @Test
  void a_working_directory_outside_every_root_is_refused() throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run("{\"command\": [\"true\"], \"cwd\": \"" + outside + "\"}", PAYMENTS);

    assertTrue(out.contains("outside"), out);
  }

  @Test
  void a_timeout_is_capped_at_the_environments_and_kills_the_command() throws IOException {
    environment("server:\n  mode: open\n  timeout: 1s\n");

    String out =
        tool().run("{\"command\": [\"sleep\", \"30\"], \"timeout_seconds\": 600}", PAYMENTS);

    assertTrue(out.startsWith("timed out after 1s and was killed"), out);
  }

  @Test
  void a_command_the_path_does_not_have_is_a_tool_result() throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run(call("plowshare-no-such-program"), PAYMENTS);

    assertTrue(out.contains("no program called"), out);
  }

  @Test
  void a_command_that_is_not_a_list_is_refused_naming_the_shape() {
    String out = tool().run("{\"command\": \"./gradlew test\"}", PAYMENTS);

    assertTrue(out.contains("list of strings"), out);
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A call as a model writes it, with {@code stdin} when it is not null. */
  private static String call(String stdin, List<String> argv) {
    ObjectNode args = JSON.createObjectNode();
    argv.forEach(args.putArray("command")::add);
    if (stdin != null) {
      args.put("stdin", stdin);
    }
    return args.toString();
  }

  @Test
  void stdin_reaches_the_program_as_its_input() throws IOException {
    environment("server:\n  mode: open\n");
    RunTool tool = tool();

    String args = call("first line\nsecond line\n", List.of("cat"));
    ToolPre gated = tool.gate(args, PAYMENTS, CONTEXT, SILENT);
    String out = tool.run(gated.arguments(), PAYMENTS);

    assertFalse(gated.isDenied(), gated.denied());
    assertTrue(out.startsWith("exit 0 after"), out);
    assertTrue(out.contains("--- stdout ---\nfirst line\nsecond line\n"), out);
  }

  @Test
  void with_no_stdin_the_program_reads_end_of_input_at_once() throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run(call(null, List.of("cat")), PAYMENTS);

    assertTrue(out.startsWith("exit 0 after"), out);
    assertTrue(out.contains("--- stdout ---\n(nothing)"), out);
  }

  @Test
  void stdin_past_the_cap_is_refused_naming_the_cap_and_nothing_runs() throws IOException {
    environment("server:\n  mode: open\n");
    Path made = repo.resolve("made");

    String out =
        tool()
            .run(
                call(
                    "x".repeat(CommandRunner.MAX_STDIN_BYTES + 1),
                    List.of("touch", made.toString())),
                PAYMENTS);

    assertTrue(out.contains(String.valueOf(CommandRunner.MAX_STDIN_BYTES)), out);
    assertFalse(Files.exists(made), "nothing runs when the input is refused");
  }

  @Test
  void stdin_that_is_not_text_is_refused_naming_the_shape() throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run("{\"command\": [\"cat\"], \"stdin\": [\"a\"]}", PAYMENTS);

    assertTrue(out.contains("'stdin'"), out);
    assertFalse(out.startsWith("exit"), out);
  }

  @Test
  void each_shell_operator_as_an_item_is_refused_with_nothing_run() throws IOException {
    environment("server:\n  mode: open\n  shells: true\n");
    Path made = repo.resolve("made");
    for (String operator : List.of("|", "||", "&&", ";", ">", ">>", "<", "2>", "2>&1", "&")) {
      String out =
          tool().run(call(null, List.of("touch", made.toString(), operator, "other")), PAYMENTS);

      assertEquals(
          "run has no shell: `"
              + operator
              + "` would reach the program as an"
              + " argument, not as its input. Give a program its input with `stdin`, as in "
              + SHAPE
              + ", and run one program per call.",
          out,
          operator);
      assertFalse(Files.exists(made), operator + ": nothing runs");
    }
  }

  /**
   * Measured 2026-09-30, orc_318DFD3782228160: a heredoc as one item, exit 2 and "Unknown option:
   * -", and nothing said why.
   */
  @Test
  void a_heredoc_item_is_refused_naming_its_marker_and_no_program() throws IOException {
    environment("server:\n  mode: open\n");
    Path made = repo.resolve("made");

    String measured =
        tool()
            .run(
                call(
                    null,
                    List.of("touch", made.toString(), "- <<PY\nimport sys\nprint(sys.argv)\nPY")),
                PAYMENTS);
    String alone = tool().run(call(null, List.of("touch", made.toString(), "<<EOF")), PAYMENTS);
    String glued =
        tool().run(call(null, List.of("touch", made.toString(), "<<EOF\nbody\nEOF")), PAYMENTS);
    String inside =
        tool()
            .run(call(null, List.of("touch", made.toString(), "cat <<'END'\nbody\nEND")), PAYMENTS);

    assertEquals(
        "run has no shell: `<<PY` would reach the program as an argument, not as its"
            + " input. Give a program its input with `stdin`, as in "
            + SHAPE
            + ", and run one"
            + " program per call.",
        measured);
    assertTrue(alone.startsWith("run has no shell: `<<EOF`"), alone);
    assertTrue(glued.startsWith("run has no shell: `<<EOF`"), glued);
    assertTrue(inside.startsWith("run has no shell: `<<'END'`"), inside);
    assertFalse(measured.contains("touch"), "the refusal names no program: " + measured);
    assertFalse(Files.exists(made), "nothing runs");
  }

  /**
   * The call shape the shell refusal shows: measured 2026-09-30, a coder sent six heredocs, each
   * refused, and never found {@code stdin}.
   */
  private static final String SHAPE =
      "{\"command\": [\"<the program>\", \"-\"], \"stdin\": \"<the text>\"}";

  @Test
  void the_shell_refusal_shows_a_call_that_fits_the_schema_and_names_no_program()
      throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run(call(null, List.of("touch", "- <<PY\nimport sys\nPY")), PAYMENTS);

    assertTrue(out.contains(SHAPE), out);
    com.fasterxml.jackson.databind.JsonNode shown =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(SHAPE);
    assertTrue(shown.path("command").isArray(), SHAPE);
    shown.path("command").forEach(item -> assertTrue(item.isTextual(), SHAPE));
    assertTrue(shown.path("stdin").isTextual(), SHAPE);
    Map<?, ?> properties = (Map<?, ?>) tool().schema().parameters().get("properties");
    shown.fieldNames().forEachRemaining(key -> assertTrue(properties.containsKey(key), key));
    assertFalse(out.contains("touch"), "the refusal names no program: " + out);
  }

  @Test
  void a_long_offending_item_is_cut_in_the_refusal() throws IOException {
    environment("server:\n  mode: open\n");

    String out = tool().run(call(null, List.of("true", "<<" + "A".repeat(100))), PAYMENTS);

    assertTrue(out.startsWith("run has no shell: `<<AAAA"), out);
    assertTrue(out.length() < 200 + SHAPE.length(), out);
    assertTrue(out.contains("…`"), out);
  }

  @Test
  void shell_characters_inside_an_ordinary_argument_are_the_programs() throws IOException {
    environment("server:\n  mode: open\n");
    RunTool tool = tool();

    String semicolons =
        tool.run(call(null, List.of("printf", "%s", "a; b && c | d > e")), PAYMENTS);
    String arrows = tool.run(call(null, List.of("printf", "%s", "x << 2")), PAYMENTS);
    String unterminated = tool.run(call(null, List.of("printf", "%s", "a <<b\nc")), PAYMENTS);

    assertTrue(semicolons.contains("--- stdout ---\na; b && c | d > e"), semicolons);
    assertTrue(arrows.contains("--- stdout ---\nx << 2"), arrows);
    assertTrue(unterminated.contains("--- stdout ---\na <<b\nc"), unterminated);
  }

  @Test
  void the_gate_lets_a_refused_shell_item_through_to_the_tool_which_runs_nothing()
      throws IOException {
    environment("server:\n  mode: open\n");
    RunTool tool = tool();
    Path made = repo.resolve("made");
    String args = call(null, List.of("touch", made.toString(), "&&", "true"));

    ToolPre gated = tool.gate(args, PAYMENTS, CONTEXT, SILENT);
    String out = tool.run(gated.arguments(), PAYMENTS);

    assertTrue(out.startsWith("run has no shell"), out);
    assertFalse(Files.exists(made));
  }

  @Test
  void the_schema_offers_stdin_and_names_its_cap() {
    String schema = tool().schema().toString();

    assertTrue(schema.contains("stdin"), schema);
    assertTrue(schema.contains(String.valueOf(CommandRunner.MAX_STDIN_BYTES)), schema);
    assertFalse(RunTool.DESCRIPTION.contains("empty stdin"), RunTool.DESCRIPTION);
    assertTrue(RunTool.DESCRIPTION.contains("`stdin`"), RunTool.DESCRIPTION);
  }

  @Test
  void output_too_long_for_one_answer_keeps_its_end() {
    String stdout = "x".repeat(FileTools.MAX_DISPLAY_CHARS) + "THE FAILURE";
    RunTool.Plan plan =
        new RunTool.Plan(
            List.of("true"),
            repo,
            null,
            "server",
            EnvironmentFile.Side.DEFAULT,
            null,
            Duration.ofSeconds(5));

    String out =
        RunTool.render(plan, new CommandRunner.Outcome(1, false, false, stdout, 0, "", 0, 10));

    // The status line stays first, whatever is cut: it is how the command
    // ended, and a cut answer whose first line was "[Cut: …" was recorded as
    // "ran" — the exit code gone from the record, and from every footer read
    // off it (measured 2026-09-30: long failing test output is the usual cut).
    assertTrue(out.startsWith("exit 1 after 0.0s in "), out.substring(0, 80));
    assertTrue(out.lines().skip(1).findFirst().orElse("").startsWith("[Cut: the last"), out);
    assertTrue(out.contains("THE FAILURE"));
    assertTrue(out.length() < FileTools.MAX_DISPLAY_CHARS + 400);
    assertEquals("exit 1", ToolLines.outcome(RunTool.NAME, out));
  }

  @Test
  void a_cut_stream_says_how_much_was_dropped() {
    RunTool.Plan plan =
        new RunTool.Plan(
            List.of("true"),
            repo,
            null,
            "local",
            EnvironmentFile.Side.DEFAULT,
            null,
            Duration.ofSeconds(5));

    String out =
        RunTool.render(plan, new CommandRunner.Outcome(null, false, true, "tail", 99, "", 0, 10));

    assertTrue(out.startsWith("cancelled after"), out);
    assertTrue(out.contains("the first 99 bytes were dropped"), out);
  }

  // --- a local allow, and an allow a rewrite moved (spec 2026-09-30-local-hooks-are-served) ---

  /** A run in a log, so the local tier finds the log's pinned set. */
  private static final HookContext IN_LOG =
      new HookContext(
          "coder", false, Set.of(RunTool.NAME), "payments", "cnv_1", HookContext.SERVER);

  private static final String ALLOWS_RUN =
      """
            export default { name: 'mine', stages: {
                'tool.pre': { tools: ['run'], handle() { return { allow: true } } },
            } }
            """;

  /** Both sides gated: nothing runs unless an allow that counts lets it. */
  private static final String BOTH_GATED = "server:\n  mode: gated\nlocal:\n  mode: gated\n";

  /** The server's disk, and a laptop held by {@code session} rooting {@code laptop}. */
  private RunTool withLaptop(Path laptop, String session) {
    RemoteProvider remote =
        new RemoteProvider(new FakeFiles().withRoots(List.of(laptop.toString())), session, grants);
    ProviderRouter router =
        new ProviderRouter(home -> List.of(new LocalProvider(store, home, grants), remote));
    Environments environments = new Environments(store::id, id -> environment, null);
    return new RunTool(router, () -> environments, () -> false);
  }

  /** cnv_1's local hooks; its owner holds s_mine, and s_theirs is another account's. */
  private ScriptHooks localHooks(HookEngine engine, HookFile... files) {
    List<HookFile> set = HookFile.ordered(List.of(files));
    String hash = HookFile.hashOf(set);
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(Duration.ofSeconds(2));
    return ScriptHooks.local(
        log -> "cnv_1".equals(log) ? Optional.of(hash) : Optional.empty(),
        log -> Optional.of("enzo"),
        named -> hash.equals(named) ? Optional.of(set) : Optional.empty(),
        (log, session) -> "cnv_1".equals(log) && "s_mine".equals(session),
        engine,
        properties,
        Instant::now);
  }

  private static BiFunction<HookContext, String, ToolPre> chainOf(ScriptHooks hooks) {
    return (context, args) -> hooks.toolPre(context, RunTool.NAME, args);
  }

  private static String callIn(Path cwd, String... argv) {
    String command = call(argv);
    return command.substring(0, command.length() - 1) + ", \"cwd\": \"" + cwd + "\"}";
  }

  @Test
  void an_allow_a_rewrite_moved_from_the_local_side_to_the_server_does_not_carry()
      throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    String move =
        """
                export default { name: 'move', stages: {
                    'tool.pre': { tools: ['run'], handle(e) {
                        return { rewrite: { command: e.args.command, cwd: '%s' } } } },
                } }
                """
            .formatted(repo);
    String asked = callIn(laptop, "true");
    String moved = callIn(repo, "true");

    try (HookEngine engine = new HookEngine();
        ScriptHooks hooks =
            localHooks(
                engine,
                new HookFile("10-move.js", move),
                new HookFile("20-allow.js", ALLOWS_RUN))) {
      ToolPre local = withLaptop(laptop, "s_mine").gate(asked, PAYMENTS, IN_LOG, chainOf(hooks));
      // Whichever tier allowed: a chain that allows while moving the command, as a project
      // hook's would.
      ToolPre project =
          withLaptop(laptop, "s_mine")
              .gate(
                  asked,
                  PAYMENTS,
                  IN_LOG,
                  (context, args) -> new ToolPre(moved, null, List.of(), true));

      for (ToolPre gated : List.of(local, project)) {
        assertTrue(
            gated.isDenied(), "a gated server command ran on an allow judged for" + " the laptop");
        assertFalse(gated.explicitlyAllowed());
        assertTrue(
            gated
                .denied()
                .contains(
                    "an allow judged for the local side does not"
                        + " carry to a command a rewrite moved to the server side"),
            gated.denied());
        assertTrue(
            gated.records().stream()
                .anyMatch(
                    record ->
                        HookRecord.NOTE.equals(record.decision())
                            && record.reason().startsWith("an allow judged for the local side")),
            gated.records().toString());
      }
    }
  }

  @Test
  void a_local_allow_on_the_gated_server_side_is_no_allow_and_nothing_runs() throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));

    try (HookEngine engine = new HookEngine();
        ScriptHooks hooks = localHooks(engine, new HookFile("20-allow.js", ALLOWS_RUN))) {
      ToolPre gated =
          withLaptop(laptop, "s_mine").gate(callIn(repo, "true"), PAYMENTS, IN_LOG, chainOf(hooks));

      assertTrue(gated.isDenied());
      assertFalse(gated.explicitlyAllowed());
      assertTrue(
          gated.records().stream()
              .anyMatch(
                  record ->
                      HookRecord.ALLOW.equals(record.decision())
                          && record.reason() != null
                          && record
                              .reason()
                              .contains("counts only for a command on the local" + " side")),
          gated.records().toString());
    }
  }

  @Test
  void a_local_allow_on_the_owner_s_own_gated_machine_lets_the_command_run() throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));

    try (HookEngine engine = new HookEngine();
        ScriptHooks hooks = localHooks(engine, new HookFile("20-allow.js", ALLOWS_RUN))) {
      ToolPre gated =
          withLaptop(laptop, "s_mine")
              .gate(callIn(laptop, "true"), PAYMENTS, IN_LOG, chainOf(hooks));

      assertFalse(gated.isDenied(), gated.denied());
      assertTrue(gated.explicitlyAllowed());
    }
  }

  @Test
  void a_local_allow_for_a_machine_another_account_holds_is_no_allow() throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));

    try (HookEngine engine = new HookEngine();
        ScriptHooks hooks = localHooks(engine, new HookFile("20-allow.js", ALLOWS_RUN))) {
      ToolPre gated =
          withLaptop(laptop, "s_theirs")
              .gate(callIn(laptop, "true"), PAYMENTS, IN_LOG, chainOf(hooks));

      assertTrue(gated.isDenied());
      assertFalse(gated.explicitlyAllowed());
      assertTrue(
          gated.records().stream()
              .anyMatch(
                  record ->
                      HookRecord.ALLOW.equals(record.decision())
                          && record.reason() != null
                          && record.reason().contains("the log owner's own machine")),
          gated.records().toString());
    }
  }

  @Test
  void a_project_allow_on_the_gated_server_side_that_moved_nothing_still_runs() throws IOException {
    environment(BOTH_GATED);

    ToolPre gated = tool().gate(callIn(repo, "true"), PAYMENTS, IN_LOG, ALLOWS);

    assertFalse(gated.isDenied(), gated.denied());
    assertTrue(gated.explicitlyAllowed());
    assertTrue(
        gated.records().stream().noneMatch(record -> HookRecord.NOTE.equals(record.decision())));
  }

  // --- fix round 2: an allow is bound to its arguments; unplaced calls; the judged place ----

  @Test
  void a_project_allow_followed_by_a_local_rewrite_does_not_run_the_rewrite_on_the_gated_server()
      throws IOException {
    environment(BOTH_GATED);
    String status = callIn(repo, "git", "status");
    String swapped = callIn(repo, "rm", "-rf", ".");
    Hooks project =
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String arguments) {
            return new ToolPre(arguments, null, List.of(), status.equals(arguments));
          }
        };
    Hooks local =
        new Hooks() {
          @Override
          public ToolPre toolPre(HookContext context, String tool, String arguments) {
            return ToolPre.allowed(swapped);
          }
        };
    Hooks chain = Hooks.chain(project, local);

    ToolPre gated =
        tool()
            .gate(
                status,
                PAYMENTS,
                IN_LOG,
                (context, args) -> chain.toolPre(context, RunTool.NAME, args));
    // Belt and braces: a chain that claims an allow for arguments other than the final ones.
    ToolPre stale =
        tool()
            .gate(
                status,
                PAYMENTS,
                IN_LOG,
                (context, args) -> new ToolPre(swapped, null, List.of(), null, status));

    for (ToolPre refused : List.of(gated, stale)) {
      assertTrue(refused.isDenied(), "rm -rf ran on an allow given for git status");
      assertFalse(refused.explicitlyAllowed());
      assertEquals(swapped, refused.arguments());
    }
  }

  @Test
  void
      a_call_that_does_not_place_and_a_hook_rewrites_onto_the_gated_server_is_judged_with_no_allow()
          throws IOException {
    environment(BOTH_GATED);
    String relative = "{\"command\": [\"true\"], \"cwd\": \"src\"}";
    String fixed = callIn(repo, "true");

    ToolPre gated =
        tool()
            .gate(
                relative,
                PAYMENTS,
                IN_LOG,
                (context, args) -> new ToolPre(fixed, null, List.of(), true));

    assertTrue(gated.isDenied(), "a hook's fix ran a gated server command it never judged");
    assertFalse(gated.explicitlyAllowed());
    assertTrue(gated.denied().startsWith(RunTool.UNPLACED_ALLOW), gated.denied());
  }

  @Test
  void a_call_that_does_not_place_and_nothing_rewrites_is_the_tool_s_to_refuse_as_before()
      throws IOException {
    environment(BOTH_GATED);
    RunTool tool = tool();
    String relative = "{\"command\": [\"true\"], \"cwd\": \"src\"}";

    ToolPre gated = tool.gate(relative, PAYMENTS, IN_LOG, ALLOWS);

    assertFalse(gated.isDenied());
    assertTrue(tool.run(gated.arguments(), PAYMENTS).contains("relative"));
  }

  @Test
  void an_allow_a_rewrite_moved_to_another_account_s_laptop_does_not_carry() throws IOException {
    environment(BOTH_GATED);
    Path mine = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    Path theirs = Files.createDirectory(tmp.toRealPath().resolve("their-laptop"));
    RemoteProvider enzo =
        new RemoteProvider(new FakeFiles().withRoots(List.of(mine.toString())), "s_mine", grants);
    RemoteProvider mallory =
        new RemoteProvider(
            new FakeFiles().withRoots(List.of(theirs.toString())), "s_theirs", grants);
    Environments environments = new Environments(store::id, id -> environment, null);
    RunTool tool =
        new RunTool(
            new ProviderRouter(home -> List.of(enzo, mallory)), () -> environments, () -> false);
    String moved = callIn(theirs, "true");

    ToolPre gated =
        tool.gate(
            callIn(mine, "true"),
            PAYMENTS,
            IN_LOG,
            (context, args) -> new ToolPre(moved, null, List.of(), true));

    assertTrue(gated.isDenied(), "an allow judged for s_mine's laptop ran on s_theirs'");
    assertFalse(gated.explicitlyAllowed());
    assertTrue(
        gated
            .denied()
            .startsWith(
                "an allow judged for one machine on the local side"
                    + " does not carry to a command a rewrite moved to another"),
        gated.denied());
  }

  @Test
  void a_command_runs_only_where_the_gate_judged_it_would() throws IOException {
    environment("server:\n  mode: open\nlocal:\n  mode: open\n");
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    RemoteProvider remote =
        new RemoteProvider(new FakeFiles().withRoots(List.of(laptop.toString())), "s_mine", grants);
    AtomicReference<List<FileProvider>> serving =
        new AtomicReference<>(List.of(new LocalProvider(store, PAYMENTS, grants)));
    Environments environments = new Environments(store::id, id -> environment, null);
    RunTool tool =
        new RunTool(new ProviderRouter(home -> serving.get()), () -> environments, () -> false);
    String firstRoot = call("true");

    // Judged on the server's first root; by the time it runs, the first root is a laptop's.
    ToolPre gated = tool.gate(firstRoot, PAYMENTS, IN_LOG, SILENT);
    assertFalse(gated.isDenied(), gated.denied());
    serving.set(List.of(remote, new LocalProvider(store, PAYMENTS, grants)));

    assertEquals(RunTool.PLACE_CHANGED, tool.run(gated.arguments(), PAYMENTS));
    // Judged again where it would now run, it is that place's to run or refuse.
    assertFalse(tool.gate(firstRoot, PAYMENTS, IN_LOG, SILENT).isDenied());
  }

  @Test
  void a_call_the_gate_could_not_place_does_not_run_once_it_can_be_placed() throws IOException {
    environment("server:\n  mode: open\nlocal:\n  mode: open\n");
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    RemoteProvider remote =
        new RemoteProvider(new FakeFiles().withRoots(List.of(laptop.toString())), "s_mine", grants);
    AtomicReference<List<FileProvider>> serving =
        new AtomicReference<>(List.of(new LocalProvider(store, PAYMENTS, grants)));
    Environments environments = new Environments(store::id, id -> environment, null);
    RunTool tool =
        new RunTool(new ProviderRouter(home -> serving.get()), () -> environments, () -> false);

    // No root covers the laptop yet: the gate cannot place it, and the tool says why, as ever.
    ToolPre gated = tool.gate(callIn(laptop, "true"), PAYMENTS, IN_LOG, SILENT);
    assertFalse(gated.isDenied(), gated.denied());
    serving.set(List.of(new LocalProvider(store, PAYMENTS, grants), remote));

    assertEquals(RunTool.PLACE_CHANGED, tool.run(gated.arguments(), PAYMENTS));
  }

  /**
   * Final review I2: a placeable call a hook rewrites onto a root nobody serves yet cannot be
   * placed, so the gate lets it through for the tool to refuse. If a root then appears before
   * {@code run}, it must not run: no mode or allow ever judged it, and under {@code gated} it would
   * be a bypass.
   */
  @Test
  void a_rewrite_onto_a_root_not_yet_served_does_not_run_once_the_root_appears()
      throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    RemoteProvider remote =
        new RemoteProvider(new FakeFiles().withRoots(List.of(laptop.toString())), "s_mine", grants);
    AtomicReference<List<FileProvider>> serving =
        new AtomicReference<>(List.of(new LocalProvider(store, PAYMENTS, grants)));
    Environments environments = new Environments(store::id, id -> environment, null);
    RunTool tool =
        new RunTool(new ProviderRouter(home -> serving.get()), () -> environments, () -> false);
    String rewritten = callIn(laptop, "true");

    ToolPre gated =
        tool.gate(
            call("true"),
            PAYMENTS,
            IN_LOG,
            (context, args) -> new ToolPre(rewritten, null, List.of()));
    assertFalse(gated.isDenied(), "not placed, so the tool's to refuse: " + gated.denied());
    assertEquals(rewritten, gated.arguments());
    serving.set(List.of(new LocalProvider(store, PAYMENTS, grants), remote));

    assertEquals(RunTool.PLACE_CHANGED, tool.run(gated.arguments(), PAYMENTS));
  }

  /**
   * Final review I2: a call that could not be placed starts to place while the chain judges it (a
   * root appears mid-chain). It was judged for no place, so {@code run} refuses it.
   */
  @Test
  void a_call_that_starts_to_place_while_the_chain_judges_it_does_not_run() throws IOException {
    environment(BOTH_GATED);
    Path laptop = Files.createDirectory(tmp.toRealPath().resolve("laptop"));
    RemoteProvider remote =
        new RemoteProvider(new FakeFiles().withRoots(List.of(laptop.toString())), "s_mine", grants);
    AtomicReference<List<FileProvider>> serving =
        new AtomicReference<>(List.of(new LocalProvider(store, PAYMENTS, grants)));
    Environments environments = new Environments(store::id, id -> environment, null);
    RunTool tool =
        new RunTool(new ProviderRouter(home -> serving.get()), () -> environments, () -> false);

    ToolPre gated =
        tool.gate(
            callIn(laptop, "true"),
            PAYMENTS,
            IN_LOG,
            (context, args) -> {
              serving.set(List.of(new LocalProvider(store, PAYMENTS, grants), remote));
              return ToolPre.allowed(args);
            });
    assertFalse(gated.isDenied(), gated.denied());

    assertEquals(RunTool.PLACE_CHANGED, tool.run(gated.arguments(), PAYMENTS));
  }
}
