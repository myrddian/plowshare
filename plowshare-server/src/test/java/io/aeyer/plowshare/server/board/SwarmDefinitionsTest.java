package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SwarmDefinitionsTest {

  private static final Set<String> TOOLS =
      Set.of("file_read", "file_edit", "search", "fetch", "run", AgentRegistry.AGENT_RUN);

  /** Pools serving the model "fast" only. */
  private static final SwarmScheduler.Pools POOLS =
      new SwarmScheduler.Pools() {
        @Override
        public List<String> serving(String specifier) {
          return specifier.equals("fast") ? List.of("spark") : List.of();
        }

        @Override
        public int slots(String pool) {
          return 5;
        }

        @Override
        public List<String> all() {
          return List.of("spark");
        }
      };

  @TempDir Path root;

  private Path agents;

  private void agent(String name, String model, String tools, String extra) throws Exception {
    Files.writeString(
        agents.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: "
            + name
            + "\nmodel: "
            + model
            + "\ntools: "
            + tools
            + "\n"
            + extra
            + "max-turns: 4\nmax-model-calls: 8\n---\nYou help.\n");
  }

  private SwarmDefinitions definitions() {
    return new SwarmDefinitions(
        new DataLayout(root), projectId -> AgentRegistry.of(agents, TOOLS), POOLS);
  }

  private void swarm(Path file, String body) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, body);
  }

  private void setUp() throws Exception {
    agents = Files.createDirectories(root.resolve("fixture-agents"));
    agent("researcher", "fast", "[search, fetch, file_read]", "");
    agent("critic", "fast", "[file_read]", "");
    agent("spec_writer", "fast", "[file_read]", "");
    agent("editor", "fast", "[file_read, file_edit]", "scopes: [workspace:write]\n");
    agent("runner", "fast", "[run]", "");
    agent("slow", "elsewhere", "[file_read]", "");
    agent(
        "rerouted",
        "fast",
        "[file_read]",
        "fallback:\n  when: [refusal]\n  model: elsewhere\n  max-attempts: 1\n");
    agent(
        "steady",
        "fast",
        "[file_read]",
        "fallback:\n  when: [refusal]\n  model: fast\n  max-attempts: 1\n");
  }

  @Test
  void manual_example_resolves_its_members_and_bounds_the_shared_topic_budget() throws Exception {
    setUp();
    Path destination = new DataLayout(root).swarmFor(7L);
    Files.createDirectories(destination.getParent());
    Files.copy(Path.of("../docs/examples/swarm/swarm.md"), destination);
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("researcher", "spec_writer", "critic"), swarm.members());
    assertEquals(36, swarm.budget());
    assertTrue(swarm.refused().isEmpty(), swarm.why());
  }

  @Test
  void the_projects_file_names_the_members_and_the_budget() throws Exception {
    setUp();
    swarm(
        new DataLayout(root).swarmFor(7L),
        "---\nmembers: [researcher, critic]\nbudget: 60\n---\nOur swarm.\n");
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("researcher", "critic"), swarm.members());
    assertEquals(60, swarm.budget());
    assertTrue(swarm.refused().isEmpty());
  }

  @Test
  void a_project_with_no_file_falls_back_to_the_global_one() throws Exception {
    setUp();
    swarm(new DataLayout(root).swarmFor(null), "---\nmembers: [critic]\n---\n");
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("critic"), swarm.members());
    assertEquals(SwarmDefinitions.DEFAULT_BUDGET, swarm.budget());
  }

  @Test
  void no_local_file_uses_the_shipped_default_swarm() throws Exception {
    setUp();
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("researcher", "spec_writer", "critic"), swarm.members());
    assertTrue(swarm.refused().isEmpty(), swarm.why());
    assertEquals("classpath:global/swarm.md", swarm.origin());
  }

  @Test
  void a_rejected_override_does_not_fall_back_to_the_shipped_swarm() throws Exception {
    setUp();
    swarm(new DataLayout(root).swarmFor(null), "---\nmembers: []\n---\n");
    var resolved = definitions().forProject(7L);
    assertTrue(resolved.members().isEmpty());
    assertTrue(resolved.origin().endsWith("global/swarm.md"));
    assertTrue(resolved.why().contains("non-empty"), resolved.why());
  }

  @Test
  void normal_agents_can_act_while_unserved_models_and_unknown_members_are_refused()
      throws Exception {
    setUp();
    swarm(
        new DataLayout(root).swarmFor(7L),
        "---\nmembers: [researcher, editor, runner, slow, ghost]\n---\n");
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("researcher", "editor", "runner"), swarm.members());
    assertTrue(swarm.refused().get("slow").contains("elsewhere"), swarm.refused().toString());
    assertTrue(swarm.refused().get("ghost").contains("no agent"), swarm.refused().toString());
  }

  /**
   * The final review's I-3: a member rerouted on a refusal runs its next call on the fallback's
   * model, and if no swarm pool serves that model the scheduled run waits for a slot that never
   * comes. Refused at load, as a member whose own model no swarm pool serves is.
   */
  @Test
  void a_member_whose_fallback_no_swarm_pool_serves_is_refused_naming_the_model() throws Exception {
    setUp();
    swarm(new DataLayout(root).swarmFor(7L), "---\nmembers: [rerouted, steady]\n---\n");
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertEquals(List.of("steady"), swarm.members());
    String why = swarm.refused().get("rerouted");
    assertTrue(
        why != null && why.contains("fallback") && why.contains("elsewhere"),
        swarm.refused().toString());
  }

  @Test
  void an_unknown_key_or_a_bad_budget_refuses_the_whole_file() throws Exception {
    setUp();
    swarm(new DataLayout(root).swarmFor(7L), "---\nmembers: [critic]\nmay-act: true\n---\n");
    assertTrue(definitions().forProject(7L).members().isEmpty());
    assertTrue(definitions().forProject(7L).why().contains("may-act"));
    swarm(new DataLayout(root).swarmFor(7L), "---\nmembers: [critic]\nbudget: 1\n---\n");
    assertTrue(definitions().forProject(7L).why().contains("budget"));
  }

  @Test
  void a_repeated_key_refuses_the_whole_file_instead_of_throwing() throws Exception {
    setUp();
    swarm(
        new DataLayout(root).swarmFor(7L), "---\nmembers: [critic]\nmembers: [researcher]\n---\n");
    SwarmDefinitions.SwarmDefinition swarm = definitions().forProject(7L);
    assertTrue(swarm.members().isEmpty(), swarm.members().toString());
    assertTrue(swarm.why().contains("valid YAML"), swarm.why());
  }

  @Test
  void the_same_refusal_is_logged_once_across_repeated_reads() throws Exception {
    setUp();
    swarm(new DataLayout(root).swarmFor(7L), "---\nmembers: [ghost]\n---\n");
    Logger logger = (Logger) LoggerFactory.getLogger(SwarmDefinitions.class);
    ListAppender<ILoggingEvent> heard = new ListAppender<>();
    heard.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    heard.start();
    logger.addAppender(heard);
    SwarmDefinitions definitions = definitions();
    try {
      definitions.forProject(7L);
      definitions.forProject(7L);
      definitions.forProject(7L);
    } finally {
      logger.detachAppender(heard);
      heard.stop();
    }
    long warnings = heard.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    assertEquals(1, warnings, heard.list.toString());
  }

  @Test
  void a_refusal_that_clears_and_recurs_is_logged_again() throws Exception {
    setUp();
    Path file = new DataLayout(root).swarmFor(7L);
    swarm(file, "---\nmembers: [ghost]\n---\n");
    Logger logger = (Logger) LoggerFactory.getLogger(SwarmDefinitions.class);
    ListAppender<ILoggingEvent> heard = new ListAppender<>();
    heard.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    heard.start();
    logger.addAppender(heard);
    SwarmDefinitions definitions = definitions();
    try {
      definitions.forProject(7L);
      swarm(file, "---\nmembers: [critic]\n---\n");
      definitions.forProject(7L);
      swarm(file, "---\nmembers: [ghost]\n---\n");
      definitions.forProject(7L);
    } finally {
      logger.detachAppender(heard);
      heard.stop();
    }
    long warnings = heard.list.stream().filter(event -> event.getLevel() == Level.WARN).count();
    assertEquals(2, warnings, heard.list.toString());
  }
}
