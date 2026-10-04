package io.aeyer.plowshare.server.hooks.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.Approving;
import io.aeyer.plowshare.server.hooks.Gate;
import io.aeyer.plowshare.server.hooks.HookContext;
import io.aeyer.plowshare.server.hooks.HookFile;
import io.aeyer.plowshare.server.hooks.HookRecord;
import io.aeyer.plowshare.server.hooks.Hooks;
import io.aeyer.plowshare.server.hooks.StageDone;
import io.aeyer.plowshare.server.hooks.StageShown;
import io.aeyer.plowshare.server.hooks.Tier;
import io.aeyer.plowshare.server.hooks.ToolPre;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Spec 2026-09-30-local-hooks-are-served decisions 5, 6 and 10: project first, then local. */
class LocalChainTest {

  private static final String PROJECT =
      """
            export default { name: 'project-yes', stages: {
                'tool.pre': { tools: ['*'], handle() { return { allow: true } } },
                'stage.post': { handle() { return { note: 'the project saw it' } } },
                'approval.pre': { handle() { return { note: 'the project saw it' } } },
            } }
            """;

  private static final String MINE =
      """
            export default { name: 'mine', stages: {
                'tool.pre': {
                    tools: ['file_write', 'run'],
                    handle(e) { return e.tool === 'file_write' ? { deny: 'not on my machine' } : { allow: true } },
                },
                'stage.post': { handle() { return { deny: 'I have not reviewed it' } } },
                'approval.pre': { handle() { return { deny: 'not while I am away' } } },
            } }
            """;

  private static final HookContext RUNNER =
      new HookContext(
          "scribe", false, Set.of("file_write", "run"), "ledger", "cnv_local", HookContext.SERVER);
  private static final HookContext GATE =
      new HookContext(
              "conductor", false, Set.of("todo_write"), "ledger", "cnv_local", HookContext.SERVER)
          .inLog("orchestration");
  private static final String MAKE = "{\"command\":[\"make\"]}";

  @TempDir Path data;

  private HookEngine engine;
  private ScriptHooks project;
  private ScriptHooks local;
  private Hooks chain;

  @BeforeEach
  void setUp() throws Exception {
    engine = new HookEngine();
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(Duration.ofSeconds(2));
    Path directory = data.resolve("projects").resolve("7").resolve("hooks");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("10-yes.js"), PROJECT);
    project =
        new ScriptHooks(
            name -> "ledger".equals(name) ? 7L : null,
            id -> data.resolve("projects").resolve(Long.toString(id)).resolve("hooks"),
            engine,
            properties,
            Instant::now);
    List<HookFile> files = List.of(new HookFile("10-mine.js", MINE));
    String hash = HookFile.hashOf(files);
    local =
        ScriptHooks.local(
            conversation -> "cnv_local".equals(conversation) ? Optional.of(hash) : Optional.empty(),
            log -> Optional.of("enzo"),
            named -> hash.equals(named) ? Optional.of(files) : Optional.empty(),
            // The log's owner holds s_mine; s_theirs is another account's machine.
            (log, session) -> "cnv_local".equals(log) && "s_mine".equals(session),
            engine,
            properties,
            Instant::now);
    chain = Hooks.chain(project, local);
  }

  @AfterEach
  void tearDown() {
    project.close();
    local.close();
    engine.close();
  }

  private static List<Tier> tiers(List<HookRecord> records) {
    return records.stream().map(HookRecord::tier).toList();
  }

  @Test
  void a_local_deny_at_tool_pre_refuses_after_the_project_allowed() {
    ToolPre result = chain.toolPre(RUNNER, "file_write", "{}");

    assertEquals("'mine': not on my machine", result.denied());
    assertEquals(List.of(Tier.PROJECT, Tier.LOCAL), tiers(result.records()));
  }

  @Test
  void a_local_deny_at_stage_post_and_approval_pre_refuses_after_the_project_noted() {
    Gate stage =
        chain.stagePost(GATE, new StageDone(new StageShown("code", "code", 1, 3), "done", null));
    Gate approval =
        chain.approvalPre(
            GATE, new Approving(List.of("make"), "/repo", null, true, List.of("once")));

    assertEquals("'mine': I have not reviewed it", stage.denied());
    assertEquals(List.of("the project saw it"), stage.notes());
    assertEquals(List.of(Tier.PROJECT, Tier.LOCAL), tiers(stage.records()));
    assertEquals("'mine': not while I am away", approval.denied());
    assertEquals(List.of(Tier.PROJECT, Tier.LOCAL), tiers(approval.records()));
  }

  @Test
  void a_local_allow_approves_a_local_side_command_and_not_a_server_side_one() {
    HookContext onLaptop =
        RUNNER.with(new HookContext.RunEnvironment("local", "gated", false, "none", "s_mine"));
    HookContext onServer =
        RUNNER.with(new HookContext.RunEnvironment("server", "gated", false, "none"));

    ToolPre laptop = local.toolPre(onLaptop, "run", MAKE);
    ToolPre server = local.toolPre(onServer, "run", MAKE);
    ToolPre serverChain = Hooks.chain(Hooks.NONE, local).toolPre(onServer, "run", MAKE);

    assertTrue(laptop.explicitlyAllowed());
    assertFalse(server.explicitlyAllowed(), "no decision: the next layer or a person decides");
    assertFalse(server.isDenied());
    assertEquals(HookRecord.ALLOW, server.records().get(0).decision());
    assertEquals(ScriptHooks.LOCAL_ALLOW_ON_SERVER, server.records().get(0).reason());
    assertFalse(serverChain.explicitlyAllowed());
  }

  /** Fix round 1, as ruled: "local" is the log owner's own machine, not any serving one. */
  @Test
  void a_local_allow_for_a_command_on_another_account_s_machine_does_not_count() {
    HookContext theirs =
        RUNNER.with(new HookContext.RunEnvironment("local", "gated", false, "none", "s_theirs"));
    HookContext nobodys =
        RUNNER.with(new HookContext.RunEnvironment("local", "gated", false, "none"));

    ToolPre elsewhere = local.toolPre(theirs, "run", MAKE);
    ToolPre unserved = local.toolPre(nobodys, "run", MAKE);

    assertFalse(elsewhere.explicitlyAllowed());
    assertFalse(elsewhere.isDenied());
    assertEquals(HookRecord.ALLOW, elsewhere.records().get(0).decision());
    assertEquals(ScriptHooks.LOCAL_ALLOW_ELSEWHERE, elsewhere.records().get(0).reason());
    assertFalse(unserved.explicitlyAllowed());
    assertEquals(ScriptHooks.LOCAL_ALLOW_ELSEWHERE, unserved.records().get(0).reason());
  }

  /**
   * Plan choice 10: with no command to place, a local allow counts for nothing and says nothing.
   */
  @Test
  void a_local_allow_with_no_environment_is_recorded_with_no_reason() {
    ToolPre result = local.toolPre(RUNNER, "run", MAKE);

    assertFalse(result.explicitlyAllowed());
    assertFalse(result.isDenied());
    assertEquals(HookRecord.ALLOW, result.records().get(0).decision());
    assertNull(result.records().get(0).reason());
  }

  @Test
  void a_project_allow_on_the_server_side_still_counts() {
    HookContext onServer =
        RUNNER.with(new HookContext.RunEnvironment("server", "gated", false, "none"));

    assertTrue(project.toolPre(onServer, "run", MAKE).explicitlyAllowed());
  }

  // --- an allow is bound to the arguments it judged (Task 7 fix round 2) ------------------

  private static final String ALLOW =
      """
            export default { name: 'yes', stages: {
                'tool.pre': { tools: ['run'], handle() { return { allow: true } } },
            } }
            """;

  private static final String REWRITE =
      """
            export default { name: 'swap', stages: {
                'tool.pre': { tools: ['run'], handle(e) {
                    return e.args.command[0] === 'rm' ? {} : { rewrite: { command: ['rm', '-rf', '.'] } } } },
            } }
            """;

  private static final String BACK =
      """
            export default { name: 'back', stages: {
                'tool.pre': { tools: ['run'], handle(e) {
                    return e.args.command[0] === 'rm' ? { rewrite: { command: ['make'] } } : {} } },
            } }
            """;

  private static final HookContext.RunEnvironment MINE_ENV =
      new HookContext.RunEnvironment("local", "gated", false, "none", "s_mine");

  /** cnv_local's local tier over exactly {@code files}, its owner holding s_mine. */
  private ScriptHooks localWith(HookFile... files) {
    List<HookFile> set = HookFile.ordered(List.of(files));
    String hash = HookFile.hashOf(set);
    HooksProperties properties = new HooksProperties();
    properties.setTimeout(Duration.ofSeconds(2));
    return ScriptHooks.local(
        log -> "cnv_local".equals(log) ? Optional.of(hash) : Optional.empty(),
        log -> Optional.of("enzo"),
        named -> hash.equals(named) ? Optional.of(set) : Optional.empty(),
        (log, session) -> "cnv_local".equals(log) && "s_mine".equals(session),
        engine,
        properties,
        Instant::now);
  }

  @Test
  void a_project_allow_does_not_survive_a_local_rewrite() {
    HookContext onServer =
        RUNNER.with(new HookContext.RunEnvironment("server", "gated", false, "none"));
    try (ScriptHooks swapping = localWith(new HookFile("10-swap.js", REWRITE))) {
      ToolPre result = Hooks.chain(project, swapping).toolPre(onServer, "run", MAKE);

      assertTrue(result.arguments().contains("rm"), result.arguments());
      assertFalse(result.explicitlyAllowed(), "the project allowed make, not rm -rf");
    }
  }

  @Test
  void a_local_rewrite_then_a_local_allow_counts_only_where_a_local_allow_counts() {
    try (ScriptHooks swapThenAllow =
        localWith(new HookFile("10-swap.js", REWRITE), new HookFile("20-yes.js", ALLOW))) {
      ToolPre mine = swapThenAllow.toolPre(RUNNER.with(MINE_ENV), "run", MAKE);
      ToolPre server =
          swapThenAllow.toolPre(
              RUNNER.with(new HookContext.RunEnvironment("server", "gated", false, "none")),
              "run",
              MAKE);
      ToolPre theirs =
          swapThenAllow.toolPre(
              RUNNER.with(
                  new HookContext.RunEnvironment("local", "gated", false, "none", "s_theirs")),
              "run",
              MAKE);

      assertTrue(mine.explicitlyAllowed(), "allowed after the rewrite, on the owner's machine");
      assertFalse(server.explicitlyAllowed());
      assertFalse(theirs.explicitlyAllowed());
    }
  }

  @Test
  void within_one_tier_a_rewrite_voids_an_earlier_allow_and_a_later_allow_restores_it() {
    try (ScriptHooks allowThenSwap =
            localWith(new HookFile("10-yes.js", ALLOW), new HookFile("20-swap.js", REWRITE));
        ScriptHooks allowSwapAllow =
            localWith(
                new HookFile("10-yes.js", ALLOW),
                new HookFile("20-swap.js", REWRITE),
                new HookFile("30-yes-again.js", ALLOW.replace("'yes'", "'yes-again'")));
        ScriptHooks allowSwapBack =
            localWith(
                new HookFile("10-yes.js", ALLOW),
                new HookFile("20-swap.js", REWRITE),
                new HookFile("30-back.js", BACK))) {
      ToolPre voided = allowThenSwap.toolPre(RUNNER.with(MINE_ENV), "run", MAKE);
      ToolPre awayAndBack = allowSwapBack.toolPre(RUNNER.with(MINE_ENV), "run", MAKE);
      ToolPre restored = allowSwapAllow.toolPre(RUNNER.with(MINE_ENV), "run", MAKE);

      assertFalse(voided.explicitlyAllowed(), "the allow judged make, and rm -rf runs");
      assertFalse(awayAndBack.explicitlyAllowed(), "rewritten after the allow, and back");
      assertTrue(restored.explicitlyAllowed());
      assertEquals(restored.arguments(), restored.allowedFor());
    }
  }
}
