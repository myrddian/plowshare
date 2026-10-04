package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a bad file costs, which is one of three things and never a fourth.
 *
 * <h2>The ladder</h2>
 *
 * <p>An agent the <em>system code</em> depends on is abort-on-fail: {@code Scribe} looks up {@code
 * scribe} by name, {@code Curator} looks up {@code promotion_judge}, {@code Learner} looks up
 * {@code learner}, and a server that started without one of those would be a server that files
 * every write flat and discovers it at the first write.
 *
 * <p>An invalid <b>item inside a grant</b> costs the item: a tool name nothing binds, a callee no
 * file defines, a callee that refused delegation, an edge that would escalate. Every one of those
 * names something that was never issuable — the tool is never constructed, no job is ever started
 * down the route — so dropping it changes nothing a model can reach, and what it saves is the
 * agent. That matters because bots are user-authored, which is the same reason the rung below
 * exists: refusing a stranger's whole bot over a line it could never have used is the boot-failure
 * mistake made one level down.
 *
 * <p>Everything else is <b>disabled</b>: read, refused, named, and left out of the set, with the
 * server up. A cycle stays here because which of its edges to drop is arbitrary, and an unreadable
 * {@code scopes:} stays here because a grant that failed to parse is not a grant that was never
 * issuable — dropping it would quietly serve an agent with less access than its file describes.
 *
 * <p>That first cut is about dependency and not about which directory a file sits in. Bots are user
 * content, and a stranger's malformed definition must not be able to stop Plowshare starting — but
 * neither must a missing agent, or a missing tool, become a silence, which is why every rung is
 * named and logged.
 *
 * <h2>{@code calls:} is a grant, which is the whole of why this is simple</h2>
 *
 * <p>It says one agent is <em>permitted</em> to reach another. It does not say it needs it. Two
 * things follow and both are measured here: <b>disabling does not cascade</b> — a disabled callee
 * leaves its caller running with an inert grant, and the edge fails at call time — and <b>the abort
 * set has no transitive closure</b>, being exactly the names the code spells and nothing dragged in
 * through the graph.
 *
 * <p>What survives is a question about <em>whose file is wrong</em>, and the item rule answers it
 * one line at a time: a caller naming an agent no file defines loses the name and keeps running; a
 * caller whose callee is disabled for the callee's own reasons keeps the name, because that grant
 * is merely inert and the callee may be back next boot.
 */
class DisabledAgentsTest {

  private static final Set<String> TOOLS = Set.of("memory_read", "agent_run");

  /**
   * The abort set for these fixtures. Named here rather than taken from {@code AgentsConfig} so
   * that this file measures the mechanism and {@code AgentsConfigTest} measures the wiring's choice
   * of set.
   */
  private static final Set<String> REQUIRED = Set.of("scribe");

  private static void write(Path dir, String name, String frontmatter) throws Exception {
    Files.writeString(dir.resolve(name + ".md"), "---\n" + frontmatter + "---\nA body.\n");
  }

  private static String leaf(String name) {
    return """
               name: %s
               description: a leaf
               model: fast
               tools: [memory_read]
               max-turns: 4
               max-model-calls: 8
               """
        .formatted(name);
  }

  private static String broken(String name) {
    return """
               name: %s
               description: a leaf
               model: fast
               tools: [memory_grep]
               max-turns: 4
               max-model-calls: 8
               """
        .formatted(name);
  }

  /**
   * A fault with no item in it: a required key nobody wrote. There is nothing to drop, so the whole
   * definition goes.
   */
  private static String headless(String name) {
    return """
               name: %s
               description: a leaf
               tools: [memory_read]
               max-turns: 4
               max-model-calls: 8
               """
        .formatted(name);
  }

  private static String tooled(String name, String tools) {
    return """
               name: %s
               description: a leaf
               model: fast
               tools: %s
               max-turns: 4
               max-model-calls: 8
               """
        .formatted(name, tools);
  }

  private static String delegating(String name, String callee, String scopes) {
    return """
               name: %s
               description: a caller
               model: fast
               tools: [agent_run]
               calls: [%s]
               scopes: [%s]
               max-turns: 4
               max-model-calls: 8
               """
        .formatted(name, callee, scopes);
  }

  // -----------------------------------------------------------------------
  // Abort, for what the code depends on
  // -----------------------------------------------------------------------

  /**
   * The loudness that abort-on-fail buys, kept for exactly the set that needs it: nothing
   * downstream of a missing scribe reports anything an operator would connect back to a file.
   */
  @Test
  void a_broken_agent_the_code_depends_on_still_stops_the_boot(@TempDir Path dir) throws Exception {
    write(dir, "scribe", broken("scribe"));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> AgentRegistry.read(dir, TOOLS, REQUIRED));
    assertTrue(e.getMessage().contains("scribe.md"), e.getMessage());
    assertTrue(e.getMessage().contains("memory_grep"), e.getMessage());
  }

  /**
   * No transitive closure: the abort set is the names the code spells, and a required agent's
   * callee is not dragged in behind it.
   */
  @Test
  void a_required_agents_callee_is_not_itself_required(@TempDir Path dir) throws Exception {
    write(dir, "scribe", delegating("scribe", "helper", "workspace:read"));
    write(dir, "helper", headless("helper"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, REQUIRED);

    assertTrue(loaded.disabled().containsKey("helper"));
    assertTrue(
        loaded.enabled().containsKey("scribe"),
        "calls: is a grant, so the scribe keeps running with an inert one");
  }

  // -----------------------------------------------------------------------
  // Disable, for everything else
  // -----------------------------------------------------------------------

  /**
   * The reason is the refusal the strict loader would have thrown, so an operator reads the same
   * sentence either way and it names the file.
   */
  @Test
  void a_broken_agent_nothing_depends_on_is_disabled_and_says_why(@TempDir Path dir)
      throws Exception {
    write(dir, "scribe", leaf("scribe"));
    write(dir, "bot", headless("bot"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, REQUIRED);

    assertEquals(Set.of("scribe"), loaded.enabled().keySet());
    assertTrue(loaded.disabled().get("bot").contains("bot.md"), loaded.disabled().toString());
    assertTrue(loaded.disabled().get("bot").contains("model"), loaded.disabled().toString());
  }

  /** A disabled agent is not served, which is the point: it is named, and it is not runnable. */
  @Test
  void a_disabled_agent_is_named_and_not_served(@TempDir Path dir) throws Exception {
    write(dir, "bot", headless("bot"));

    AgentRegistry registry = AgentRegistry.of(dir, TOOLS, Set.of());

    assertEquals(Set.of(), registry.names());
    assertEquals(Set.of("bot"), registry.disabled().keySet());
    assertThrows(IllegalArgumentException.class, () -> registry.get("bot"));
  }

  /**
   * Disabling does not cascade.
   *
   * <p>An earlier draft of the design said a caller whose callee was disabled would be disabled
   * too. That is the dependency reading of {@code calls:}, and {@code calls:} is a grant: the
   * caller keeps running and the edge fails at call time, where {@code AgentRunTool} already
   * renders a refusal the model can read.
   */
  @Test
  void a_caller_whose_callee_is_disabled_keeps_running(@TempDir Path dir) throws Exception {
    write(dir, "boss", delegating("boss", "helper", "workspace:read"));
    write(dir, "helper", headless("helper"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("boss"), loaded.enabled().keySet());
    assertEquals(
        List.of("helper"),
        loaded.enabled().get("boss").calls(),
        "the grant is inert, not withdrawn: the file still says what it was allowed");
  }

  /**
   * The typo check, which the boot has always had and which now costs the <b>name</b> rather than
   * the file that wrote it.
   *
   * <p>A callee no file defines is one entry of a grant, and a grant naming something that does not
   * exist was never issuable: no job can be started down an edge whose far end nobody wrote. So the
   * entry goes, the caller stays, and the route is reported — which is the escalation case's answer
   * applied to the fault sitting next to it.
   */
  @Test
  void a_caller_that_names_an_agent_no_file_defines_loses_the_name_and_not_itself(@TempDir Path dir)
      throws Exception {
    write(
        dir,
        "boss",
        """
                name: boss
                description: a caller
                model: fast
                tools: [agent_run]
                calls: [ghost, other]
                scopes: [workspace:read]
                max-turns: 4
                max-model-calls: 8
                """);
    write(dir, "other", leaf("other"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("boss", "other"), loaded.enabled().keySet());
    assertEquals(Map.of(), loaded.disabled());
    assertEquals(List.of("other"), loaded.enabled().get("boss").calls());
    assertTrue(
        loaded.withheldEdges().get("boss -> ghost").contains("ghost"),
        loaded.withheldEdges().toString());
  }

  /**
   * The same typo on an agent the code depends on is still a boot failure: the system is entitled
   * to the agent behaving as its file reads.
   */
  @Test
  void a_required_agent_naming_an_agent_no_file_defines_stops_the_boot(@TempDir Path dir)
      throws Exception {
    write(dir, "scribe", delegating("scribe", "ghost", "workspace:read"));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> AgentRegistry.read(dir, TOOLS, REQUIRED));
    assertTrue(e.getMessage().contains("ghost"), e.getMessage());
  }

  /**
   * A callee that declared it will not be called costs the <b>name</b>, and neither file.
   *
   * <p>The refusal's own message named this remedy first — "drop the name from the caller, or drop
   * the refusal from the callee" — and dropping the name is the half the loader can do without
   * guessing which of the two authors was wrong. The callee's {@code delegable: false} is honoured
   * exactly as written: no agent may reach it.
   */
  @Test
  void a_callee_refusing_to_be_called_costs_the_name_and_neither_file(@TempDir Path dir)
      throws Exception {
    write(dir, "boss", delegating("boss", "hermit", "workspace:read"));
    write(dir, "hermit", leaf("hermit") + "delegable: false\n");

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("boss", "hermit"), loaded.enabled().keySet());
    assertEquals(Map.of(), loaded.disabled());
    assertEquals(List.of(), loaded.enabled().get("boss").calls());
    assertTrue(
        loaded.withheldEdges().get("boss -> hermit").contains("delegable: false"),
        loaded.withheldEdges().toString());
  }

  /**
   * The remedy the escalation case found, reached down the other road: a caller whose only callee
   * was a typo is not offered a tool that could reach nobody.
   */
  @Test
  void a_caller_left_with_no_callees_by_a_typo_loses_the_tool(@TempDir Path dir) throws Exception {
    write(dir, "boss", delegating("boss", "ghost", "workspace:read"));

    AgentDefinition boss = AgentRegistry.read(dir, TOOLS, Set.of()).enabled().get("boss");

    assertEquals(List.of(), boss.calls());
    assertFalse(
        boss.canDelegate(),
        "a delegating agent with nowhere to delegate is an agent that does not delegate");
  }

  // -----------------------------------------------------------------------
  // An item inside a grant, which costs the item
  // -----------------------------------------------------------------------

  /**
   * A tool name this runtime does not bind costs the <b>name</b>, and not the agent that wrote it.
   *
   * <p>The grant was never issuable. {@code knownTools()} is derived from what the tool layer
   * actually registered, so a name outside it is a schema that is never built and never offered —
   * the model cannot reach it whether the agent is served or not. What dropping it changes is only
   * whether one bad line costs somebody their whole bot, and bots are user-authored: the disable
   * rule exists precisely so a stranger's typo cannot stop the server, and disabling over an item
   * it could never have used is the same mistake one rung down.
   */
  @Test
  void an_unknown_tool_costs_the_tool_and_not_the_agent(@TempDir Path dir) throws Exception {
    write(dir, "bot", tooled("bot", "[memory_read, memory_grep]"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("bot"), loaded.enabled().keySet());
    assertEquals(Map.of(), loaded.disabled());
    assertEquals(
        List.of("memory_read"),
        loaded.enabled().get("bot").tools(),
        "the tool is gone and the rest of the declaration stands");
    String why = loaded.withheldTools().get("bot: memory_grep");
    assertTrue(why.contains("memory_grep"), why);
    assertTrue(why.contains("bot.md"), why);
    assertTrue(why.contains("no tool of that name"), why);
  }

  /**
   * A tool that exists and is never an agent's says so, because that is the likelier honest
   * mistake.
   *
   * <p>Somebody reading their own harness's MCP tool list sees {@code project_move} beside {@code
   * memory_read} and reasonably assumes an agent may hold it. "This runtime does not bind it" is
   * true and unhelpful there; what they need is that it is real, that the agent surface
   * deliberately excludes it, and why.
   */
  @Test
  void a_withheld_tool_is_named_as_withheld_and_not_as_unknown(@TempDir Path dir) throws Exception {
    write(dir, "bot", tooled("bot", "[memory_read, project_move]"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("bot"), loaded.enabled().keySet());
    assertEquals(List.of("memory_read"), loaded.enabled().get("bot").tools());
    String why = loaded.withheldTools().get("bot: project_move");
    assertTrue(why.contains("project_move"), why);
    assertTrue(why.contains("deliberately never on the agent-facing one"), why);
    assertFalse(
        why.contains("no tool of that name"),
        "a real tool must not be reported as a name nobody wrote: " + why);
  }

  /**
   * The reverse of the escalation case's remedy, and the second place a dropped item empties
   * something.
   *
   * <p>{@code agent_run} is in {@code knownTools()} exactly when the runtime was handed a graph, so
   * a runtime that cannot delegate drops the name from every definition that asked for it — and
   * leaves behind {@code calls:} with no way to reach any of it, which is a fault {@code
   * disagreeingHalves} would disable the agent for. An item drop that cascades into a disablement
   * is the thing this rule exists to prevent, so the calls go with the tool.
   */
  @Test
  void dropping_the_delegation_tool_takes_the_calls_with_it(@TempDir Path dir) throws Exception {
    write(dir, "boss", delegating("boss", "other", "workspace:read"));
    write(dir, "other", leaf("other"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, Set.of("memory_read"), Set.of());

    assertEquals(Set.of("boss", "other"), loaded.enabled().keySet());
    assertEquals(Map.of(), loaded.disabled());
    assertEquals(List.of(), loaded.enabled().get("boss").calls());
    assertEquals(List.of(), loaded.enabled().get("boss").tools());
    assertTrue(
        loaded.withheldEdges().get("boss -> other").contains("agent_run"),
        loaded.withheldEdges().toString());
  }

  /**
   * A {@code scopes:} entry is not on this rung, and the boundary is where it is for two reasons.
   *
   * <p><b>It is a typo and not a misplaced capability.</b> The vocabulary is one scope and two
   * modes, so there is no {@code project_move} story here — nobody writes an invalid scope having
   * seen it offered somewhere else.
   *
   * <p><b>And an unparseable grant is not a grant that was never issuable.</b> That is the whole
   * argument for dropping a tool: the schema is never built, so the model's reach is identical
   * either way. A {@code scopes:} line that failed to parse says nothing about what its author
   * meant to permit, and dropping it would quietly serve an agent with narrower access than its
   * file describes — which is the direction nothing downstream reports. {@code Grant.parseAll}'s
   * other refusal, two grants over one scope, is a fault about a <em>pair</em> with no
   * non-arbitrary half to drop, which is the cycle's argument.
   */
  @Test
  void an_unreadable_scope_still_disables_the_agent(@TempDir Path dir) throws Exception {
    write(
        dir,
        "bot",
        """
                name: bot
                description: a leaf
                model: fast
                tools: [memory_read]
                scopes: [wormhole:read]
                max-turns: 4
                max-model-calls: 8
                """);

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of(), loaded.enabled().keySet());
    assertTrue(loaded.disabled().get("bot").contains("scopes"), loaded.disabled().toString());
  }

  /**
   * Every agent on a cycle is disabled, and the reason names the whole path.
   *
   * <p>A cycle is the one fault where no single file is wrong: breaking it means an edit to one of
   * them and the loader cannot know which. Leaving any member enabled would leave the job tree that
   * never drains reachable, so both go and the reason each is given is the cycle itself.
   */
  @Test
  void every_agent_on_a_cycle_is_disabled(@TempDir Path dir) throws Exception {
    write(dir, "ping", delegating("ping", "pong", "workspace:read"));
    write(dir, "pong", delegating("pong", "ping", "workspace:read"));

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of(), loaded.enabled().keySet());
    assertEquals(Set.of("ping", "pong"), loaded.disabled().keySet());
    assertTrue(loaded.disabled().get("ping").contains("cycle"), loaded.disabled().toString());
  }

  /**
   * A cycle through an agent the code depends on is still a boot failure: the abort set is asked
   * about every member.
   */
  @Test
  void a_cycle_through_a_required_agent_stops_the_boot(@TempDir Path dir) throws Exception {
    write(dir, "scribe", delegating("scribe", "pong", "workspace:read"));
    write(dir, "pong", delegating("pong", "scribe", "workspace:read"));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> AgentRegistry.read(dir, TOOLS, REQUIRED));
    assertTrue(e.getMessage().contains("cycle"), e.getMessage());
  }

  // -----------------------------------------------------------------------
  // The escalation case, where "disable" means something other than
  // "disable an agent"
  // -----------------------------------------------------------------------

  /**
   * A callee holding a grant its caller lacks costs the <b>edge</b>, and neither agent.
   *
   * <p>This is the one check that spans two files where neither is wrong on its own: the callee
   * widened its own scopes, which it is entitled to do, and the caller named it, which it was
   * entitled to do. The <em>pair</em> is the fault, so the pair is what is dropped — and both
   * agents stay up, each doing what its own file says, with one route between them gone.
   *
   * <p>Disabling the callee would punish the file that is more likely right; disabling the caller
   * would take down an agent over a permission it never asked to inherit. Neither is a fix an
   * operator would have chosen.
   */
  @Test
  void an_escalating_edge_is_dropped_and_both_agents_stay(@TempDir Path dir) throws Exception {
    write(dir, "boss", delegating("boss", "helper", "workspace:read"));
    write(
        dir,
        "helper",
        """
                name: helper
                description: a leaf that reaches further than its caller
                model: fast
                tools: [memory_read]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                """);

    AgentRegistry.Loaded loaded = AgentRegistry.read(dir, TOOLS, Set.of());

    assertEquals(Set.of("boss", "helper"), loaded.enabled().keySet());
    assertEquals(Map.of(), loaded.disabled());
    assertTrue(
        loaded.withheldEdges().get("boss -> helper").contains("workspace:write"),
        loaded.withheldEdges().toString());
  }

  /**
   * The caller loses the route, and — when it was the last one — the tool as well.
   *
   * <p>{@code disagreeingHalves} refuses a file holding {@code agent_run} with an empty {@code
   * calls:}, and its refusal names the remedy: "either list the callees or drop the tool". With the
   * last callee withheld there is nothing left to list, so the loader applies its own remedy. The
   * model is then not offered a tool that could reach nobody, which is what the rule was protecting
   * against in the first place.
   */
  @Test
  void a_caller_left_with_no_callees_is_not_offered_the_tool(@TempDir Path dir) throws Exception {
    write(dir, "boss", delegating("boss", "helper", "workspace:read"));
    write(
        dir,
        "helper",
        """
                name: helper
                description: a leaf that reaches further than its caller
                model: fast
                tools: [memory_read]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                """);

    AgentDefinition boss = AgentRegistry.read(dir, TOOLS, Set.of()).enabled().get("boss");

    assertEquals(List.of(), boss.calls());
    assertFalse(
        boss.canDelegate(),
        "a delegating agent with nowhere to delegate is an agent that does not delegate");
  }

  /** With a second callee still legal, the tool stays: only the one edge went. */
  @Test
  void a_caller_with_another_callee_keeps_the_tool_and_the_other_edge(@TempDir Path dir)
      throws Exception {
    write(
        dir,
        "boss",
        """
                name: boss
                description: a caller
                model: fast
                tools: [agent_run]
                calls: [helper, other]
                scopes: [workspace:read]
                max-turns: 4
                max-model-calls: 8
                """);
    write(
        dir,
        "helper",
        """
                name: helper
                description: a leaf that reaches further than its caller
                model: fast
                tools: [memory_read]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                """);
    write(dir, "other", leaf("other"));

    AgentDefinition boss = AgentRegistry.read(dir, TOOLS, Set.of()).enabled().get("boss");

    assertEquals(List.of("other"), boss.calls());
    assertTrue(boss.canDelegate());
  }

  /**
   * An edge into or out of an agent the code depends on is not quietly removed: a silently narrowed
   * graph is exactly the surprise abort-on-fail exists to prevent, and the system is entitled to
   * the agent behaving as its file reads.
   */
  @Test
  void an_escalating_edge_touching_a_required_agent_stops_the_boot(@TempDir Path dir)
      throws Exception {
    write(dir, "scribe", delegating("scribe", "helper", "workspace:read"));
    write(
        dir,
        "helper",
        """
                name: helper
                description: a leaf that reaches further than its caller
                model: fast
                tools: [memory_read]
                scopes: [workspace:write]
                max-turns: 4
                max-model-calls: 8
                """);

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> AgentRegistry.read(dir, TOOLS, REQUIRED));
    assertTrue(e.getMessage().contains("'scribe' calls 'helper'"), e.getMessage());
  }

  // -----------------------------------------------------------------------
  // The strict door
  // -----------------------------------------------------------------------

  /**
   * {@code load} is the strict read — every agent treated as depended upon — and it is what the
   * shipped-definition tests want: five files that must all be perfect. It is stricter than the
   * boot and never looser, which is what makes a second door safe here.
   */
  @Test
  void the_strict_read_still_refuses_anything_wrong(@TempDir Path dir) throws Exception {
    write(dir, "bot", broken("bot"));

    assertThrows(IllegalStateException.class, () -> AgentRegistry.load(dir, TOOLS));
  }
}
