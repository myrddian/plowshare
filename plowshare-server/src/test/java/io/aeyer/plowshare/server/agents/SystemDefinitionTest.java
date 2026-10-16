package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SYSTEM exposure is trusted release metadata and survives ordinary definition transformations. */
class SystemDefinitionTest {
  private static String text(String extra) {
    return "---\nname: worker\ndescription: Fixture\nmodel: fast\nexported: true\n"
        + "max-turns: 4\nmax-model-calls: 8\n"
        + extra
        + "---\nFixture prompt.";
  }

  private static DefinitionSource source(String text, DefinitionSource.Provenance provenance) {
    return new DefinitionSource() {
      public String describe() {
        return "fixture";
      }

      public List<Definition> list() {
        // Spoofing a descriptive origin must never confer bundle provenance.
        return List.of(
            new Definition("worker", "the shipped definition `worker`", text, provenance));
      }
    };
  }

  @Test
  void absent_flag_defaults_false_even_in_the_bundle() {
    for (var provenance : DefinitionSource.Provenance.values()) {
      var loaded = AgentRegistry.read(source(text(""), provenance), Set.of(), Set.of("worker"));
      assertFalse(loaded.enabled().get("worker").isSystem());
    }
    assertFalse(AgentExposureTest.definition("daedalus").isSystem());
  }

  @Test
  void authored_content_cannot_declare_the_reserved_key_even_as_false() {
    for (String value : List.of("true", "false")) {
      var loaded =
          AgentRegistry.read(
              source(text("is-system: " + value + "\n"), DefinitionSource.Provenance.AUTHORED),
              Set.of(),
              Set.of());
      assertTrue(loaded.enabled().isEmpty());
      assertTrue(loaded.disabled().get("worker").contains("reserved for bundled definitions"));
      assertTrue(loaded.systemNames().isEmpty());
    }
  }

  @Test
  void filesystem_copy_of_a_system_definition_does_not_become_trusted(@TempDir Path path)
      throws Exception {
    Files.writeString(path.resolve("worker.md"), text("is-system: true\n"));
    var loaded = AgentRegistry.read(path, Set.of(), Set.of());
    assertTrue(loaded.enabled().isEmpty());
    assertTrue(loaded.disabled().get("worker").contains("reserved for bundled definitions"));
  }

  @Test
  void bundled_flag_requires_a_boolean() {
    for (String value : List.of("null", "1", "\"true\"")) {
      var loaded =
          AgentRegistry.read(
              source(text("is-system: " + value + "\n"), DefinitionSource.Provenance.BUNDLED),
              Set.of(),
              Set.of());
      assertTrue(loaded.enabled().isEmpty(), value);
      assertTrue(loaded.disabled().get("worker").contains("is-system"));
    }
  }

  @Test
  void snapshot_and_layering_preserve_provenance_but_do_not_inherit_classification() {
    var bundled = source(text("is-system: true\n"), DefinitionSource.Provenance.BUNDLED);
    var frozen = DefinitionSource.snapshot(bundled);
    assertEquals(DefinitionSource.Provenance.BUNDLED, frozen.list().getFirst().provenance());
    assertTrue(AgentRegistry.read(frozen, Set.of(), Set.of()).enabled().get("worker").isSystem());
    var authored = source(text(""), DefinitionSource.Provenance.AUTHORED);
    var layered = new LayeredDefinitions(List.of(authored, frozen));
    assertFalse(AgentRegistry.read(layered, Set.of(), Set.of()).enabled().get("worker").isSystem());
  }

  @Test
  void transformations_preserve_the_flag() {
    var system = AgentExposureTest.systemDefinition("worker", null);
    for (var copied :
        List.of(
            system.sampling(Sampling.NONE),
            system.fallback(AgentDefinition.Fallback.NONE),
            system.withSkills(List.of("fixture")),
            system.withCalls(List.of()),
            system.withPrompt("Replaced prompt"),
            system.withDynamic(false),
            system.withTools(List.of()),
            system.withScopes(List.of()),
            system.withCaps(2, 3))) {
      assertTrue(copied.isSystem(), copied.toString());
      assertFalse(AgentExposure.availableTo(copied, "@service/fixture"));
    }
  }

  @Test
  void registry_tool_and_callee_repairs_preserve_classification() {
    var loaded =
        AgentRegistry.read(
            source(
                text("is-system: true\ntools: [agent_run]\ncalls: [missing]\n"),
                DefinitionSource.Provenance.BUNDLED),
            Set.of("agent_run"),
            Set.of());
    var definition = loaded.enabled().get("worker");
    assertTrue(definition.isSystem());
    assertTrue(definition.calls().isEmpty());
    assertTrue(definition.tools().isEmpty());
    var withoutTool =
        AgentRegistry.read(
            source(
                text("is-system: true\ntools: [agent_run]\ncalls: [missing]\n"),
                DefinitionSource.Provenance.BUNDLED),
            Set.of(),
            Set.of());
    assertTrue(withoutTool.enabled().get("worker").isSystem());
  }

  @Test
  void model_disable_keeps_classification_for_discovery() {
    var system = AgentExposureTest.systemDefinition("worker", null);
    var loaded =
        new AgentRegistry.Loaded(Map.of("worker", system), Map.of(), Map.of())
            .without("worker", "Model unavailable");
    assertTrue(new AgentRegistry(loaded).isSystem("worker"));
    assertTrue(loaded.enabled().isEmpty());
  }

  @Test
  void project_namesake_does_not_change_the_internal_registry(@TempDir Path root) throws Exception {
    var system = AgentExposureTest.systemDefinition("worker", null);
    var global = new AgentRegistry(Map.of("worker", system));
    var layout = new DataLayout(root).initialise();
    Files.createDirectories(layout.agentsFor(7L));
    Files.writeString(layout.agentsFor(7L).resolve("worker.md"), text(""));
    var resolver =
        new DefinitionResolver(
            global,
            layout,
            id -> true,
            Set.of(),
            Set.of(),
            new FakeFiles(),
            session -> false,
            DefinitionChecks.NONE);
    var selected = resolver.forCaller(new DefinitionResolver.Caller(7L, null)).get("worker");
    assertFalse(selected.isSystem());
    assertTrue(AgentExposure.availableTo(selected, "@service/fixture"));
    assertSame(system, global.get("worker"));
    assertTrue(global.get("worker").isSystem());
  }
}
