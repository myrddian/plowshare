package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real file/snapshot resolution with no model calls or database dependencies. */
class GlobalDefinitionRefreshTest {
  @TempDir Path root;
  private static final Set<String> TOOLS = Set.of("agent_run");

  private static void agent(Path dir, String name, String prompt) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: fixture\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n---\n"
            + prompt
            + "\n");
  }

  private ReloadingGlobalAgents globals(DataLayout data) {
    var shipped = new FilesystemDefinitions(root.resolve("shipped"));
    var seed = new AgentRegistry(AgentRegistry.read(shipped, TOOLS, Set.of()));
    return new ReloadingGlobalAgents(
        data, TOOLS, DefinitionChecks.NONE, AgentGuidance.NONE, seed, shipped);
  }

  private DefinitionResolver resolver(DataLayout data, GlobalAgentDefinitions globals) {
    var resolver =
        new DefinitionResolver(
            globals,
            data,
            id -> true,
            TOOLS,
            Set.of(),
            new FakeFiles(),
            session -> true,
            (id, session) -> true,
            DefinitionChecks.NONE);
    return resolver;
  }

  @Test
  void content_changes_refresh_cached_projects_without_mutating_admitted_snapshots()
      throws Exception {
    var data = new DataLayout(root.resolve("data")).initialise();
    agent(root.resolve("shipped"), "helper", "release");
    agent(data.botsFor(null), "helper", "before");
    agent(data.botsFor(8L), "helper", "project override");
    var globals = globals(data);
    var resolver = resolver(data, globals);
    var caller = new DefinitionResolver.Caller(7L, null);
    var first = resolver.forCaller(caller);
    var stamp = Files.getLastModifiedTime(data.botsFor(null).resolve("helper.md"));
    agent(data.botsFor(null), "helper", "after!");
    Files.setLastModifiedTime(data.botsFor(null).resolve("helper.md"), stamp);
    var second = resolver.forCaller(caller);
    assertNotSame(first, second);
    assertTrue(first.get("helper").prompt().contains("before"));
    assertTrue(second.get("helper").prompt().contains("after!"));
    assertSame(second, resolver.forCaller(caller));
    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(8L, null))
            .get("helper")
            .prompt()
            .contains("project override"));
    // Changing the installed seed after construction cannot replace release resources.
    agent(root.resolve("shipped"), "helper", "changed release");
    Files.delete(data.botsFor(null).resolve("helper.md"));
    assertTrue(resolver.forCaller(caller).get("helper").prompt().contains("release"));
    assertFalse(resolver.forCaller(caller).get("helper").prompt().contains("changed release"));
  }

  @Test
  void malformed_replacements_mask_the_release_and_recover_after_correction() throws Exception {
    var data = new DataLayout(root.resolve("data")).initialise();
    agent(root.resolve("shipped"), "helper", "release");
    var globals = globals(data);
    var original = globals.current();
    Files.createDirectories(data.botsFor(null));
    Files.writeString(data.botsFor(null).resolve("helper.md"), "bad frontmatter");
    var refused = globals.current();
    assertFalse(refused.names().contains("helper"));
    assertTrue(refused.disabled().containsKey("helper"));
    assertTrue(original.names().contains("helper"));
    agent(data.botsFor(null), "helper", "fixed");
    assertTrue(globals.current().get("helper").prompt().contains("fixed"));
  }

  @Test
  void conflicting_or_unreadable_global_tiers_never_serve_the_old_snapshot() throws Exception {
    var data = new DataLayout(root.resolve("data")).initialise();
    agent(data.agentsFor(null), "helper", "agent");
    var globals = globals(data);
    var first = globals.current();
    agent(data.botsFor(null), "helper", "bot");
    assertThrows(IllegalStateException.class, globals::current);
    Files.delete(data.botsFor(null).resolve("helper.md"));
    assertNotSame(first, globals.current());
    Files.delete(data.botsFor(null));
    Files.writeString(data.botsFor(null), "not a directory");
    assertThrows(IllegalStateException.class, globals::current);
  }

  @Test
  void refreshed_definitions_receive_model_checks_and_global_writes_invalidate() throws Exception {
    var data = new DataLayout(root.resolve("data")).initialise();
    agent(data.botsFor(null), "helper", "valid");
    var source = new FilesystemDefinitions(root.resolve("shipped"));
    var globals =
        new ReloadingGlobalAgents(
            data,
            TOOLS,
            (loaded, origin) -> loaded.without("helper", "model unavailable"),
            AgentGuidance.NONE,
            new AgentRegistry(Map.of()),
            source);
    assertEquals("model unavailable", globals.current().disabled().get("helper"));
    var resolver = resolver(data, globals);
    var first = globals.current();
    resolver.invalidate(null);
    assertNotSame(first, globals.current());
  }

  @Test
  void orchestration_refresh_tracks_global_files_and_agent_dependencies() throws Exception {
    var data = new DataLayout(root.resolve("data")).initialise();
    agent(data.botsFor(null), "helper", "delegate");
    var globals = globals(data);
    var source = new FilesystemDefinitions(root.resolve("shipped-orchestrations"));
    var seed = new OrchestrationRegistry.Loaded(Map.of(), Map.of());
    var reloading =
        new ReloadingGlobalOrchestrations(
            data, globals, TOOLS, DefinitionChecks.NONE, seed, source);
    var agents = resolver(data, globals);
    var resolver =
        new OrchestrationResolver(
            reloading,
            data,
            id -> true,
            TOOLS,
            new FakeFiles(),
            session -> true,
            (id, session) -> true,
            agents::forCaller,
            DefinitionChecks.NONE);
    Path file = data.orchestrationsFor(null).resolve("review.md");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        "---\nname: review\ndescription: fixture\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\ncalls: [helper]\nstages:\n  - {id: goal}\n---\nbefore\n");
    var caller = new DefinitionResolver.Caller(7L, null);
    var first = resolver.forCaller(caller).get("review");
    assertNotNull(first);
    var stamp = Files.getLastModifiedTime(file);
    Files.writeString(file, Files.readString(file).replace("before", "after!"));
    Files.setLastModifiedTime(file, stamp);
    var second = resolver.forCaller(caller).get("review");
    assertNotEquals(first.hash(), second.hash());
    assertTrue(first.conductor().prompt().contains("before"));
    Files.createDirectories(data.botsFor(null));
    Files.writeString(data.botsFor(null).resolve("helper.md"), "bad frontmatter");
    assertFalse(resolver.forCaller(caller).containsKey("review"));
    assertTrue(resolver.refusalsFor(caller).containsKey("review"));
    agent(data.botsFor(null), "helper", "restored");
    assertTrue(resolver.forCaller(caller).containsKey("review"));
    Files.delete(file);
    assertFalse(resolver.forCaller(caller).containsKey("review"));
  }
}
