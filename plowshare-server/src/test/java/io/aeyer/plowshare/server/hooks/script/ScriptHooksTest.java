package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.ApprovalAnswer;
import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Deadline;
import io.aeyer.plowshare.server.hooks.DeliveryPre;
import io.aeyer.plowshare.server.hooks.FoldPost;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.Handover;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.LogClosing;
import io.aeyer.plowshare.server.hooks.LogOpen;
import io.aeyer.plowshare.server.hooks.LogOpening;
import io.aeyer.plowshare.server.hooks.Mode;
import io.aeyer.plowshare.server.hooks.Notified;
import io.aeyer.plowshare.server.hooks.PromptPre;
import io.aeyer.plowshare.server.hooks.Stage;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.StageStart;
import io.aeyer.plowshare.server.hooks.Step;
import io.aeyer.plowshare.server.hooks.StepPost;
import io.aeyer.plowshare.server.hooks.Summarised;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPost;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptHooksTest {

  @TempDir Path data;

  private HookEngine engine;
  private ScriptHooks hooks;
  private Path dir;

  private static final HookContext LEDGER =
      new HookContext(
          "scribe",
          false,
          Set.of("file_write", "file_read"),
          "ledger",
          "cnv_1",
          HookContext.SERVER);

  @BeforeEach
  void setUp() throws Exception {
    engine = new HookEngine();
    dir = data.resolve("projects").resolve("7").resolve("hooks");
    Files.createDirectories(dir);
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(Duration.ofMillis(500));
    hooks =
        new ScriptHooks(
            name -> "ledger".equals(name) ? 7L : null,
            id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
            engine,
            properties,
            Instant::now);
  }

  @AfterEach
  void tearDown() {
    hooks.close();
    extra.forEach(ScriptHooks::close);
    engine.close();
  }

  private void write(String file, String source) throws Exception {
    Path path = dir.resolve(file);
    Files.writeString(path, source);
    // A different mtime every write, so a reload is not missed within one clock tick.
    Files.setLastModifiedTime(
        path, FileTime.from(Instant.now().plusMillis(System.nanoTime() % 100_000)));
  }

  @Test
  void personal_hooks_run_for_the_owner_inside_another_project_and_keep_their_tier()
      throws Exception {
    write("10-secrets.ts", SECRETS);
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(Duration.ofMillis(500));
    var personal =
        ScriptHooks.personal(
            context -> context.project().equals("ledger") ? 7L : null,
            id -> dir,
            engine,
            properties,
            Instant::now);
    extra.add(personal);
    ToolPre denied = personal.toolPre(LEDGER, "file_write", "{\"content\":\"PRIVATE KEY\"}");
    assertTrue(denied.isDenied());
    assertEquals(Tier.PERSONAL, denied.records().getFirst().tier());
    var other =
        new HookContext(
            "scribe", false, Set.of("file_write"), "another", "cnv_2", HookContext.SERVER);
    assertEquals(
        List.of(),
        personal.toolPre(other, "file_write", "{\"content\":\"PRIVATE KEY\"}").records());
  }

  private static final String SECRETS =
      """
            import type { Hook } from '@plowshare/hooks'
            export default {
                name: 'no-secrets-in-writes',
                stages: {
                    'tool.pre': {
                        tools: ['file_write'],
                        handle(event) {
                            return /PRIVATE KEY/.test(String(event.args.content ?? ''))
                                ? { deny: 'a private key' } : { allow: true }
                        },
                    },
                },
            } satisfies Hook
            """;

  @Test
  void the_global_tier_and_a_project_without_hooks_have_none() {
    HookContext global = new HookContext("scribe", false, Set.of(), null, null, HookContext.SERVER);
    HookContext unknown =
        new HookContext("scribe", false, Set.of(), "nowhere", null, HookContext.SERVER);

    assertEquals(PromptPre.NOTHING, hooks.promptPre(global, "hi"));
    assertEquals(ToolPre.allowed("{}"), hooks.toolPre(unknown, "file_write", "{}"));
  }

  @Test
  void a_projects_hook_denies_and_every_call_is_recorded() throws Exception {
    write("10-secrets.ts", SECRETS);

    ToolPre denied =
        hooks.toolPre(LEDGER, "file_write", "{\"content\":\"-----BEGIN PRIVATE KEY-----\"}");
    ToolPre allowed = hooks.toolPre(LEDGER, "file_write", "{\"content\":\"hello\"}");
    ToolPre unmatched = hooks.toolPre(LEDGER, "file_read", "{}");

    assertTrue(denied.isDenied());
    assertTrue(denied.denied().contains("a private key"));
    assertEquals(HookRecord.DENY, denied.records().get(0).decision());
    assertEquals("10-secrets.ts", denied.records().get(0).file());
    assertEquals(Tier.PROJECT, denied.records().get(0).tier());
    assertFalse(allowed.isDenied());
    assertEquals(HookRecord.ALLOW, allowed.records().get(0).decision());
    assertEquals(
        List.of(), unmatched.records(), "a hook that does not name the tool is not called");
  }

  @Test
  void hooks_run_in_filename_order_and_a_rewrite_reaches_the_next_one() throws Exception {
    write(
        "20-second.js",
        """
                export default { name: 'second', stages: { 'tool.pre': { tools: ['file_*'],
                    handle(e) { return e.args.path === 'b' ? { deny: 'saw b' } : { allow: true } } } } }
                """);
    write(
        "10-first.js",
        """
                export default { name: 'first', stages: { 'tool.pre': { tools: ['file_write'],
                    handle() { return { rewrite: { path: 'b' } } } } } }
                """);

    ToolPre result = hooks.toolPre(LEDGER, "file_write", "{\"path\":\"a\"}");

    assertEquals(
        List.of("first", "second"), result.records().stream().map(HookRecord::hook).toList());
    assertEquals("'second': saw b", result.denied());
  }

  @Test
  void additions_carry_their_mode() throws Exception {
    write(
        "house.js",
        """
                export default { name: 'house', stages: { 'prompt.pre': {
                    handle(e) { return { add: 'rule for ' + e.context.agent, mode: 'durable' } } } } }
                """);

    PromptPre pre = hooks.promptPre(LEDGER, "hi");

    assertEquals("rule for scribe", pre.additions().get(0).text());
    assertEquals(Mode.DURABLE, pre.additions().get(0).mode());
  }

  @Test
  void a_script_hook_at_step_post_sees_the_step_and_its_note_is_returned() throws Exception {
    write(
        "steps.js",
        """
                export default { name: 'steps', stages: { 'step.post': {
                    handle(e) { return { note: e.step.number + ':' + e.step.calls[0].tool + ':' + e.step.results[0] } } } } }
                """);

    StepPost after =
        hooks.stepPost(
            LEDGER,
            new Step(
                2,
                "model-fast",
                List.of(
                    new io.aeyer.plowshare.protocol.ToolCall(
                        "c1", "file_read", "{\"path\":\"a\"}")),
                List.of("contents"),
                null));

    assertEquals(List.of("2:file_read:contents"), after.notes());
    assertEquals(HookRecord.NOTE, after.records().get(0).decision());
    assertEquals(Stage.STEP_POST, after.records().get(0).stage());
  }

  @Test
  void a_changed_file_is_what_the_next_call_runs() throws Exception {
    write(
        "10-secrets.js",
        """
                export default { name: 'gate', stages: { 'tool.pre': { tools: ['file_write'], handle() { return { deny: 'old' } } } } }
                """);
    assertEquals("'gate': old", hooks.toolPre(LEDGER, "file_write", "{}").denied());

    write(
        "10-secrets.js",
        """
                export default { name: 'gate', stages: { 'tool.pre': { tools: ['file_write'], handle() { return { deny: 'new' } } } } }
                """);

    assertEquals("'gate': new", hooks.toolPre(LEDGER, "file_write", "{}").denied());
  }

  @Test
  void a_file_that_will_not_load_blocks_tools_and_adds_nothing_until_it_is_fixed()
      throws Exception {
    write("30-broken.ts", "export default { name: 'broken', ");

    ToolPre tool = hooks.toolPre(LEDGER, "file_read", "{}");
    PromptPre prompt = hooks.promptPre(LEDGER, "hi");

    assertTrue(tool.isDenied());
    assertTrue(
        tool.denied()
            .startsWith(
                "a hook file did not load, so tool calls in this project"
                    + " are refused until it is fixed: "),
        tool.denied());
    assertTrue(tool.denied().contains("30-broken.ts"), tool.denied());
    assertEquals(List.of(), prompt.additions());
    assertEquals(HookRecord.FAILED, prompt.records().get(0).decision());

    write(
        "30-broken.ts",
        "export default { name: 'fixed', stages: { 'prompt.pre': { handle() { return undefined } } } }");
    assertFalse(hooks.toolPre(LEDGER, "file_read", "{}").isDenied());
  }

  @Test
  void two_hooks_with_one_name_is_a_broken_directory_not_an_override() throws Exception {
    write("10-a.js", "export default { name: 'same', stages: { 'prompt.pre': { handle() {} } } }");
    write("20-b.js", "export default { name: 'same', stages: { 'prompt.pre': { handle() {} } } }");

    ToolPre tool = hooks.toolPre(LEDGER, "file_write", "{}");

    assertTrue(tool.isDenied());
    assertTrue(tool.denied().contains("20-b.js") && tool.denied().contains("same"), tool.denied());
  }

  @Test
  void a_tool_stage_that_fails_closes_and_a_prompt_stage_that_fails_opens() throws Exception {
    write(
        "spin.js",
        """
                export default { name: 'spin', stages: {
                    'tool.pre': { tools: ['file_write'], handle() { while (true) {} } },
                    'tool.post': { tools: ['file_read'], handle() { throw new Error('no') } },
                    'prompt.pre': { handle() { while (true) {} } } } }
                """);

    ToolPre pre = hooks.toolPre(LEDGER, "file_write", "{}");
    ToolPost post = hooks.toolPost(LEDGER, "file_read", "{}", "the file says hi");
    PromptPre prompt = hooks.promptPre(LEDGER, "hi");

    assertTrue(pre.isDenied());
    assertEquals(HookRecord.FAILED, pre.records().get(0).decision());
    assertTrue(post.result().startsWith("the result was withheld"), post.result());
    assertEquals(List.of(), prompt.additions());
    assertEquals(HookRecord.FAILED, prompt.records().get(0).decision());
  }

  /**
   * A module's top level runs when the file loads, outside every {@code handle}, so no call's time
   * limit covers it. Unbounded, {@code while (true) {}} there would hang the first fire in this
   * project — and every later one, queued behind the same load. Bounded by {@code
   * plowshare.hooks.timeout}, it is a file that did not load: tools refused, naming it.
   */
  @Test
  void a_file_whose_top_level_never_finishes_is_a_file_that_did_not_load() throws Exception {
    write(
        "40-spins-at-load.js",
        """
                while (true) {}
                export default { name: 'never', stages: { 'prompt.pre': { handle() {} } } }
                """);

    ToolPre tool =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> hooks.toolPre(LEDGER, "file_read", "{}"),
            "a spinning top level has to be stopped at the load limit, not waited on");

    assertTrue(tool.isDenied());
    assertTrue(tool.denied().contains("40-spins-at-load.js"), tool.denied());
    assertTrue(tool.denied().contains("did not finish loading within 500 ms"), tool.denied());
  }

  /**
   * Tool stages close for a broken directory on both sides of the call: a result that comes back
   * while a file is not loaded is withheld, not passed through unexamined.
   */
  @Test
  void a_broken_directory_withholds_tool_results_too() throws Exception {
    write("30-broken.ts", "export default { name: 'broken', ");

    ToolPost post = hooks.toolPost(LEDGER, "file_read", "{}", "the file says hi");

    assertTrue(
        post.result().startsWith("the result was withheld because a hook file did not" + " load: "),
        post.result());
    assertTrue(post.result().contains("30-broken.ts"), post.result());
    assertFalse(post.result().contains("the file says hi"), post.result());
    assertEquals(HookRecord.FAILED, post.records().get(0).decision());
    assertEquals("30-broken.ts", post.records().get(0).file());
  }

  /**
   * A lookup that throws — the archive unavailable — is not "this project has no hooks". Skipping
   * them would open every tool stage the project closed, so it is a broken set: tools refused,
   * prompts add nothing and record the failure. A lookup that answers {@code null} still means no
   * such project, and no hooks.
   */
  @Test
  void a_project_whose_hooks_cannot_be_looked_up_refuses_tools_and_adds_nothing() throws Exception {
    write(
        "house.js",
        """
                export default { name: 'house', stages: { 'prompt.pre': { handle() { return { add: 'x', mode: 'volatile' } } } } }
                """);
    ScriptHooks unreachable =
        hooksWith(
            name -> {
              throw new IllegalStateException("the archive is not answering");
            },
            Duration.ofMillis(500));

    ToolPre tool = unreachable.toolPre(LEDGER, "file_read", "{}");
    PromptPre prompt = unreachable.promptPre(LEDGER, "hi");
    ToolPost post = unreachable.toolPost(LEDGER, "file_read", "{}", "secret");

    assertTrue(tool.isDenied());
    assertEquals(
        "this project's hooks could not be looked up, so tool calls in this project are"
            + " refused until the archive is reachable",
        tool.denied());
    assertEquals(HookRecord.FAILED, tool.records().get(0).decision());
    assertEquals(List.of(), prompt.additions());
    assertEquals(HookRecord.FAILED, prompt.records().get(0).decision());
    assertFalse(post.result().contains("secret"), post.result());
    assertEquals(
        ToolPre.allowed("{}"),
        hooksWith(name -> null, Duration.ofMillis(500)).toolPre(LEDGER, "file_read", "{}"),
        "a null id is still no project, so no hooks");
  }

  /**
   * What a lookup threw is for the person reading the record, not the model: a driver's message
   * names hosts and ports. The model is given one fixed sentence.
   */
  @Test
  void a_failed_lookup_tells_the_model_a_fixed_sentence_and_keeps_the_detail_in_the_record() {
    ScriptHooks unreachable =
        hooksWith(
            name -> {
              throw new IllegalStateException("Connection to archive-db.internal:5432 refused");
            },
            Duration.ofMillis(500));

    ToolPre tool = unreachable.toolPre(LEDGER, "file_read", "{}");
    ToolPost post = unreachable.toolPost(LEDGER, "file_read", "{}", "secret");

    assertFalse(tool.denied().contains("archive-db.internal"), tool.denied());
    assertFalse(post.result().contains("archive-db.internal"), post.result());
    assertTrue(
        tool.records().get(0).reason().contains("archive-db.internal:5432"),
        tool.records().get(0).reason());
    assertTrue(
        post.records().get(0).reason().contains("archive-db.internal:5432"),
        post.records().get(0).reason());
  }

  /**
   * "Never throws" is the contract, and it covers what is not a {@link HookFailure} too — an {@link
   * java.io.UncheckedIOException} out of a directory listing, say. Each stage still applies its own
   * policy: tools close, prompts open.
   */
  @Test
  void a_runtime_exception_while_loading_is_each_stages_policy_not_a_thrown_turn() {
    ScriptHooks throwing =
        new ScriptHooks(
            name -> 7L,
            id -> {
              throw new java.io.UncheckedIOException(new java.io.IOException("the disk went away"));
            },
            engine,
            new HooksProperties(),
            Instant::now);
    extra.add(throwing);

    ToolPre tool = throwing.toolPre(LEDGER, "file_write", "{}");
    ToolPost post = throwing.toolPost(LEDGER, "file_read", "{}", "the file says hi");
    PromptPre prompt = throwing.promptPre(LEDGER, "hi");
    io.aeyer.plowshare.server.hooks.PromptPost reply =
        throwing.promptPost(LEDGER, "hello", List.of());

    assertTrue(tool.isDenied());
    assertEquals(HookRecord.FAILED, tool.records().get(0).decision());
    assertTrue(post.result().startsWith("the result was withheld"), post.result());
    assertFalse(post.result().contains("the file says hi"), post.result());
    assertEquals(HookRecord.FAILED, post.records().get(0).decision());
    assertEquals(List.of(), prompt.additions());
    assertEquals(HookRecord.FAILED, prompt.records().get(0).decision());
    assertEquals("hello", reply.reply());
    assertEquals(HookRecord.FAILED, reply.records().get(0).decision());
  }

  /** The sentence a model is shown must not carry where this server keeps its data. */
  @Test
  void an_unlistable_directory_refuses_tools_without_naming_the_servers_path() throws Exception {
    write(
        "house.js", "export default { name: 'house', stages: { 'prompt.pre': { handle() {} } } }");
    Files.setPosixFilePermissions(
        dir, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
    try {
      ToolPre tool = hooks.toolPre(LEDGER, "file_read", "{}");

      assertTrue(tool.isDenied());
      assertTrue(
          tool.denied().contains("this project's hooks directory could not be listed"),
          tool.denied());
      assertFalse(tool.denied().contains(data.toString()), tool.denied());
      assertFalse(tool.denied().contains(data.toRealPath().toString()), tool.denied());
    } finally {
      Files.setPosixFilePermissions(
          dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
  }

  /**
   * A reload is one project's business. Project ids 7 and 23 land in the same bin of a
   * default-sized {@code ConcurrentHashMap}, so a reload done inside {@code compute} would hold
   * 23's calls behind 7's load — up to every file's time limit, and recorded nowhere.
   */
  @Test
  void a_project_that_is_reloading_does_not_stall_a_project_beside_it() throws Exception {
    Path other = data.resolve("projects").resolve("23").resolve("hooks");
    Files.createDirectories(other);
    Files.writeString(
        other.resolve("gate.js"),
        """
                export default { name: 'gate', stages: { 'tool.pre': { tools: ['file_read'], handle() { return { allow: true } } } } }
                """);
    ScriptHooks both =
        hooksWith(
            name ->
                switch (name) {
                  case "ledger" -> 7L;
                  case "other" -> 23L;
                  default -> null;
                },
            Duration.ofSeconds(3));
    HookContext beside =
        new HookContext("scribe", false, Set.of("file_read"), "other", null, HookContext.SERVER);
    // Warm both sets, the engine and the stripper, so what is timed below is the
    // wait for a lock and nothing else.
    assertFalse(both.toolPre(beside, "file_read", "{}").isDenied());
    assertFalse(both.toolPre(LEDGER, "file_read", "{}").isDenied());
    write(
        "40-spins-at-load.js",
        "while (true) {}\nexport default { name: 'never', stages: { 'prompt.pre': { handle() {} } } }");

    java.util.concurrent.CompletableFuture<ToolPre> reloading =
        java.util.concurrent.CompletableFuture.supplyAsync(
            () -> both.toolPre(LEDGER, "file_read", "{}"));
    Thread.sleep(400);
    assertFalse(reloading.isDone(), "project 7 is expected to be mid-load here");
    long started = System.nanoTime();
    ToolPre meanwhile = both.toolPre(beside, "file_read", "{}");
    long tookMs = (System.nanoTime() - started) / 1_000_000;

    assertFalse(meanwhile.isDenied());
    assertTrue(tookMs < 1_000, "project 23 waited " + tookMs + " ms behind project 7's reload");
    assertTrue(reloading.get(10, java.util.concurrent.TimeUnit.SECONDS).isDenied());
  }

  private final List<ScriptHooks> extra = new java.util.ArrayList<>();

  private ScriptHooks hooksWith(java.util.function.Function<String, Long> ids, Duration timeout) {
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(timeout);
    ScriptHooks made =
        new ScriptHooks(
            ids,
            id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
            engine,
            properties,
            Instant::now);
    extra.add(made);
    return made;
  }

  @Test
  void a_run_call_s_context_carries_the_side_s_isolation() throws Exception {
    write(
        "iso.js",
        """
                export default { name: 'iso', stages: { 'tool.pre': { tools: ['run'],
                    handle(e) { return { deny: 'isolation=' + e.context.environment.isolation } } } } }
                """);

    ToolPre judged =
        hooks.toolPre(
            LEDGER.with(new HookContext.RunEnvironment("server", "gated", false, "none")),
            "run",
            "{}");

    assertEquals("'iso': isolation=none", judged.denied());
  }

  private static final HookContext SUBMISSION_LOG =
      HookContext.forLog("submission", "scribe", false, "ledger", "cnv_9");
  private static final HookContext DELEGATION_LOG =
      HookContext.forLog("delegation", "scribe", false, "ledger", "cnv_8");
  private static final Summarised SUMMARISED =
      new Summarised(3, 8, 900, "they agreed to roll back");
  private static final HookContext TURN_LOG =
      HookContext.forLog("turn", null, false, "ledger", "cnv_7");

  @Test
  void log_open_adds_for_the_origins_a_hook_names_and_for_no_other() throws Exception {
    write(
        "10-open.js",
        """
                export default { name: 'opening', stages: { 'log.open': { origins: ['submission'],
                    handle(e) { return { add: 'rules for ' + e.context.agent + ' in ' + e.context.log } } } } }
                """);

    LogOpen submission = hooks.logOpen(SUBMISSION_LOG, new LogOpening(null, "enzo"));
    LogOpen delegation = hooks.logOpen(DELEGATION_LOG, new LogOpening("cnv_9", "enzo"));

    assertEquals(List.of("rules for scribe in cnv_9"), submission.additions());
    assertEquals(HookRecord.ADD, submission.records().get(0).decision());
    assertEquals(Stage.LOG_OPEN, submission.records().get(0).stage());
    assertEquals(
        LogOpen.NOTHING,
        delegation,
        "a hook naming only submission is not called, and records nothing, for a delegation");
  }

  @Test
  void a_turn_log_names_no_agent_and_its_conversation_is_the_log() throws Exception {
    write(
        "ctx.js",
        """
                export default { name: 'ctx', stages: { 'log.open': { handle(e) { return { add: [
                    e.context.origin, String(e.context.agent), e.context.log,
                    String(e.context.conversation), String(e.owner)].join('/') } } } } }
                """);

    assertEquals(
        List.of("turn/undefined/cnv_7/cnv_7/enzo"),
        hooks.logOpen(TURN_LOG, new LogOpening(null, "enzo")).additions());
    assertEquals(
        List.of("submission/scribe/cnv_9/undefined/undefined"),
        hooks.logOpen(SUBMISSION_LOG, new LogOpening(null, null)).additions());
  }

  @Test
  void log_open_hands_a_delegated_log_its_parent_and_a_root_none() throws Exception {
    write(
        "parent.js",
        """
                export default { name: 'parent', stages: { 'log.open': {
                    handle(e) { return { add: 'parent=' + String(e.parent) } } } } }
                """);

    assertEquals(
        List.of("parent=cnv_9"),
        hooks.logOpen(DELEGATION_LOG, new LogOpening("cnv_9", "enzo")).additions());
    assertEquals(
        List.of("parent=undefined"),
        hooks.logOpen(SUBMISSION_LOG, new LogOpening(null, "enzo")).additions());
  }

  @Test
  void log_close_and_delivery_post_notify_and_delivery_pre_notes() throws Exception {
    write(
        "20-tell.js",
        """
                export default { name: 'tell', stages: {
                    'log.close': { handle(e) { return { notify: e.context.log + ' ended ' + e.ending
                        + ' after ' + e.turns } } },
                    'delivery.pre': { handle(e) { return { note: 'checked (' + e.destination + ', from '
                        + e.source.origin + ')' } } },
                    'delivery.post': { handle(e) { return e.delivered ? { notify: 'sent: ' + e.text } : undefined } },
                } }
                """);
    Handover inbox = new Handover(Handover.INBOX, "the answer");

    Notified closed = hooks.logClose(SUBMISSION_LOG, new LogClosing("ANSWERED", 2));
    DeliveryPre pre = hooks.deliveryPre(SUBMISSION_LOG, inbox);
    Notified post = hooks.deliveryPost(SUBMISSION_LOG, inbox);

    assertEquals(
        List.of(new Notified.Notice("tell", "cnv_9 ended ANSWERED after 2")), closed.notices());
    assertEquals(HookRecord.NOTIFY, closed.records().get(0).decision());
    assertEquals("the answer\n\nchecked (inbox, from submission)", pre.applyTo("the answer"));
    assertEquals(List.of(new Notified.Notice("tell", "sent: the answer")), post.notices());
  }

  /** Spec §2.3: every log stage in this slice fails open. */
  @Test
  void a_log_stage_that_fails_opens_and_records_the_failure() throws Exception {
    write(
        "bad.js",
        """
                export default { name: 'bad', stages: {
                    'log.open': { handle() { throw new Error('boom') } },
                    'delivery.pre': { handle() { return { redact: 'x' } } } } }
                """);

    LogOpen opened = hooks.logOpen(SUBMISSION_LOG, new LogOpening(null, null));
    DeliveryPre pre = hooks.deliveryPre(SUBMISSION_LOG, new Handover(Handover.INBOX, "t"));

    assertEquals(List.of(), opened.additions());
    assertEquals(HookRecord.FAILED, opened.records().get(0).decision());
    assertEquals("t", pre.applyTo("t"));
    assertEquals(HookRecord.FAILED, pre.records().get(0).decision());
  }

  // --- slices 2–4: stage.*, approval.*, fold.* ---------------------------------------------

  private static final HookContext CONDUCTING =
      new HookContext(
              "conductor", true, Set.of("todo_write"), "ledger", "cnv_c", HookContext.SERVER)
          .inLog("orchestration")
          .about(new HookContext.Orchestration("orc_1", "code_implementation", "code"));
  private static final StageShown CODE = new StageShown("code", "write the code", 1, 3);
  private static final Approving ASKING =
      new Approving(
          List.of("pytest", "-q"),
          "/repo",
          "this run's check",
          false,
          List.of("once", "conversation", "project"));

  @Test
  void stage_pre_notes_and_stage_post_denies_naming_the_hook_and_both_see_the_orchestration()
      throws Exception {
    write(
        "10-stages.js",
        """
                export default { name: 'stages', stages: {
                    'stage.pre': { origins: ['orchestration'], handle(e) { return { note: [
                        e.context.orchestration.id, e.context.orchestration.definition,
                        e.context.orchestration.stage, e.stage.id, e.stage.title, e.stage.index,
                        e.stage.count, e.returning, e.returnsLeft].join('/') } } },
                    'stage.post': { handle(e) { return e.check && e.check.passed
                        ? { deny: 'not on ' + e.check.command.join(' ') + ' after ' + e.summary }
                        : undefined } } } }
                """);

    Gate started = hooks.stagePre(CONDUCTING, new StageStart(CODE, true, 0));
    Gate done =
        hooks.stagePost(CONDUCTING, new StageDone(CODE, "built it", List.of("pytest", "-q")));

    assertEquals(
        List.of("orc_1/code_implementation/code/code/write the code/1/3/true/0"), started.notes());
    assertFalse(started.isDenied());
    assertEquals(HookRecord.NOTE, started.records().get(0).decision());
    assertEquals(Stage.STAGE_PRE, started.records().get(0).stage());
    assertEquals("'stages': not on pytest -q after built it", done.denied());
    assertEquals(HookRecord.DENY, done.records().get(0).decision());
  }

  @Test
  void information_stage_scripts_receive_exact_targets_and_can_refuse_publication()
      throws Exception {
    write(
        "10-information.js",
        """
                export default { name: 'information', stages: {
                    'stage.pre': { origins: ['submission'], handle(e) {
                        const d=e.context.document;
                        return { note: [d.operation,d.resource,d.revision,d.generation,d.stage,d.attempt,d.sourceUri].join('/') }
                    } },
                    'stage.post': { origins: ['submission'], handle(e) {
                        return { deny: 'review ' + e.context.document.revision }
                    } }
                } }
                """);
    var context =
        HookContext.forLog("submission", "document_pipeline", false, "ledger", "cnv_i")
            .about(
                new HookContext.Document(
                    "processing",
                    "resource-1",
                    "revision-1",
                    3,
                    "embed",
                    2,
                    "https://example.org/source"));
    var stage = new StageShown("embed", "Embed retained passages", 0, 1);
    assertEquals(
        List.of("processing/resource-1/revision-1/3/embed/2/https://example.org/source"),
        hooks.stagePre(context, new StageStart(stage, null, null)).notes());
    assertEquals(
        "'information': review revision-1",
        hooks.stagePost(context, new StageDone(stage, "prepared", null)).denied());
    assertEquals(Gate.NOTHING, hooks.stagePre(CONDUCTING, new StageStart(stage, null, null)));
  }

  @Test
  void a_stage_hook_that_names_other_origins_is_not_asked() throws Exception {
    write(
        "10-turns-only.js",
        """
                export default { name: 'turns-only', stages: {
                    'stage.pre': { origins: ['turn'], handle() { return { deny: 'no' } } } } }
                """);

    assertEquals(Gate.NOTHING, hooks.stagePre(CONDUCTING, new StageStart(CODE, false, 3)));
  }

  /** Spec §2.4: the gates fail closed, as tool.pre does. */
  @Test
  void a_gate_whose_hook_fails_or_spins_denies_and_the_stages_after_it_open() throws Exception {
    write(
        "spin.js",
        """
                export default { name: 'spin', stages: {
                    'stage.post': { handle() { while (true) {} } },
                    'approval.pre': { handle() { throw new Error('no') } },
                    'fold.post': { handle() { while (true) {} } },
                    'approval.post': { handle() { throw new Error('no') } } } }
                """);

    Gate done =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> hooks.stagePost(CONDUCTING, new StageDone(CODE, "built it", null)));
    Gate asked =
        hooks.approvalPre(
            CONDUCTING.with(new HookContext.RunEnvironment("server", "ask", false, "none")),
            ASKING);
    FoldPost kept =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5), () -> hooks.foldPost(SUBMISSION_LOG, SUMMARISED, fresh()));
    Notified answered =
        hooks.approvalPost(
            SUBMISSION_LOG, new ApprovalAnswer("apr_1", ApprovalAnswer.ALLOW, "once"));

    assertTrue(done.isDenied());
    assertTrue(
        done.denied().startsWith("the hook 'spin' failed, and a failed check refuses"),
        done.denied());
    assertEquals(HookRecord.FAILED, done.records().get(0).decision());
    assertTrue(asked.isDenied());
    assertEquals(List.of(), kept.kept(), "fold.post fails open: nothing kept");
    assertEquals(List.of(), kept.notices(), "and nobody told");
    assertEquals(HookRecord.FAILED, kept.records().get(0).decision());
    assertEquals(List.of(), answered.notices());
    assertEquals(HookRecord.FAILED, answered.records().get(0).decision());
  }

  /**
   * Spec decision 5: no stage answers an approval. An allow is a decision approval.pre does not
   * have.
   */
  @Test
  void approval_pre_cannot_allow_and_an_allow_refuses() throws Exception {
    write(
        "yes.js",
        """
                export default { name: 'yes', stages: { 'approval.pre': { handle() { return { allow: true } } } } }
                """);

    Gate asked = hooks.approvalPre(CONDUCTING, ASKING);

    assertTrue(asked.isDenied(), "an allow is not an approval.pre decision, so it fails closed");
    assertEquals(HookRecord.FAILED, asked.records().get(0).decision());
  }

  @Test
  void a_file_that_did_not_load_refuses_every_gate_and_opens_every_other_log_stage()
      throws Exception {
    write("broken.ts", "export default {");

    Gate move = hooks.stagePre(CONDUCTING, new StageStart(CODE, false, 3));
    Gate question = hooks.approvalPre(CONDUCTING, ASKING);
    FoldPost kept = hooks.foldPost(SUBMISSION_LOG, SUMMARISED, fresh());

    assertTrue(
        move.denied()
            .startsWith(
                "a hook file did not load, so stage moves in this"
                    + " project are refused until it is fixed"),
        move.denied());
    assertTrue(
        question
            .denied()
            .startsWith(
                "a hook file did not load, so questions to a"
                    + " person in this project are refused until it is fixed"),
        question.denied());
    assertEquals(List.of(), kept.kept());
    assertEquals(HookRecord.FAILED, kept.records().get(0).decision());
  }

  @Test
  void approval_pre_sees_the_command_where_it_would_run_and_its_note_is_the_question_s()
      throws Exception {
    write(
        "assess.js",
        """
                export default { name: 'assess', stages: { 'approval.pre': { handle(e) { return {
                    note: [e.argv.join(' '), e.cwd, e.reason, e.attended, e.scopes.join('|'),
                        e.context.environment.side, e.context.environment.mode,
                        e.context.environment.isolation].join('/') } } } } }
                """);

    Gate asked =
        hooks.approvalPre(
            CONDUCTING.with(new HookContext.RunEnvironment("local", "ask", false, "none")), ASKING);

    assertEquals(
        List.of(
            "pytest -q//repo/this run's check/false/once|conversation|project" + "/local/ask/none"),
        asked.notes());
    assertEquals(
        "this run's check\n\npytest -q//repo/this run's check/false/once|conversation"
            + "|project/local/ask/none",
        asked.applyTo(ASKING.reason()));
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log §3, amended 2026-09-29: fold.post reads the folder's
   * summary, so it keeps a marker only when the folder lost it, and otherwise may notify.
   */
  @Test
  void fold_post_sees_the_summary_and_keeps_verbatim_or_notifies() throws Exception {
    write(
        "20-fold.js",
        """
                export default { name: 'fold', stages: {
                    'fold.post': { handle(e) {
                        return e.summary.includes('skill X@1')
                            ? { notify: 'the folder kept skill X@1 through ' + e.through }
                            : { keep: 'skill X@1 was loaded (' + e.through + ', ' + e.entries + ', '
                                + e.estimatedTokens + ')' } } },
                    'approval.post': { handle(e) { return { notify: e.approval + ' ' + e.decision
                        + ' ' + String(e.scope) } } } } }
                """);

    FoldPost lost =
        hooks.foldPost(
            SUBMISSION_LOG, new Summarised(3, 8, 900, "they agreed to roll back"), fresh());
    FoldPost survived =
        hooks.foldPost(
            SUBMISSION_LOG,
            new Summarised(3, 8, 900, "skill X@1 is loaded; they agreed to roll back"),
            fresh());
    Notified denied =
        hooks.approvalPost(SUBMISSION_LOG, new ApprovalAnswer("apr_1", ApprovalAnswer.DENY, null));

    assertEquals(
        List.of(
            new FoldPost.Kept(
                "fold", "20-fold.js", Tier.PROJECT, "skill X@1 was loaded (3, 8, 900)")),
        lost.kept());
    assertEquals(List.of(), lost.notices());
    assertEquals(HookRecord.KEEP, lost.records().get(0).decision());
    assertEquals("skill X@1 was loaded (3, 8, 900)", lost.records().get(0).added());
    assertEquals(List.of(), survived.kept());
    assertEquals(
        List.of(new Notified.Notice("fold", "the folder kept skill X@1 through 3")),
        survived.notices());
    assertEquals(HookRecord.NOTIFY, survived.records().get(0).decision());
    assertEquals(List.of(new Notified.Notice("fold", "apr_1 deny undefined")), denied.notices());
  }

  /**
   * Spec 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29: all of a fold's fold.post
   * hooks share one time limit. Three that spin hold the fold up for about one limit, not three;
   * the hook that answered before them keeps what it kept; and the ones never reached are recorded
   * as timed out, as a hook stopped at its limit is.
   */
  @Test
  void fold_post_s_hooks_share_one_time_limit_and_one_that_answered_keeps_its_answer()
      throws Exception {
    write(
        "10-marker.js",
        """
                export default { name: 'marker', stages: {
                    'fold.post': { handle() { return { keep: 'skill X@1 was loaded' } } } } }
                """);
    for (String spinner : List.of("20-spin", "30-spin", "40-spin")) {
      write(
          spinner + ".js",
          "export default { name: '"
              + spinner
              + "', stages: {"
              + " 'fold.post': { handle() { while (true) {} } } } }");
    }
    // Loaded before the clock starts: loading has a limit of its own, and this is about calls.
    hooks.logClose(SUBMISSION_LOG, new LogClosing("answered", 1));
    Duration limit = Duration.ofMillis(500);

    long started = System.nanoTime();
    FoldPost folded =
        assertTimeoutPreemptively(
            Duration.ofSeconds(5),
            () -> hooks.foldPost(SUBMISSION_LOG, SUMMARISED, Deadline.after(limit)));
    Duration took = Duration.ofNanos(System.nanoTime() - started);

    // Under three limits, which three separate limits could not be: room for a loaded
    // machine, and still one limit against three.
    assertTrue(
        took.compareTo(limit.multipliedBy(3)) < 0,
        "three spinning hooks took "
            + took.toMillis()
            + " ms; one limit is "
            + limit.toMillis()
            + " ms");
    assertEquals(
        List.of("skill X@1 was loaded"), folded.kept().stream().map(FoldPost.Kept::text).toList());
    assertEquals(
        List.of("marker", "20-spin", "30-spin", "40-spin"),
        folded.records().stream().map(HookRecord::hook).toList());
    assertEquals(
        List.of(HookRecord.KEEP, HookRecord.FAILED, HookRecord.FAILED, HookRecord.FAILED),
        folded.records().stream().map(HookRecord::decision).toList());
    // Both name the one limit configured, never the leftover the stopped hook was handed.
    assertEquals(
        "the hook '20-spin' passed the time left of fold.post's shared 500 ms limit",
        folded.records().get(1).reason());
    for (HookRecord unreached : folded.records().subList(2, 4)) {
      assertEquals(
          "the hook '"
              + unreached.hook()
              + "' was not run: nothing was left of"
              + " fold.post's shared 500 ms limit when it was reached",
          unreached.reason());
    }
  }

  /** A deadline no shorter than any fold.post test here can need. */
  private static Deadline fresh() {
    return Deadline.after(Duration.ofSeconds(2));
  }
}
