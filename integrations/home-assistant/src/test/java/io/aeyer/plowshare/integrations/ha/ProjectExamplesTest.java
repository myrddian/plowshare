package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.integrations.*;
import io.aeyer.plowshare.server.agents.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Real current definition parsers, without starting a server or inference model. */
class ProjectExamplesTest {
  @Test
  void optional_project_resources_load_and_match_the_binding() throws Exception {
    Path examples = Path.of(System.getProperty("integration.examples"));
    Configuration config = Configuration.read(examples.resolve("config.json"));
    var binding = config.bindings().get("house");
    Set<String> tools = Set.of("outgoing_peers", "outgoing_send", "outgoing_read");
    AgentRegistry agents = AgentRegistry.of(examples.resolve("project/agents"), tools, Set.of());
    assertTrue(agents.disabled().isEmpty(), agents.disabled().toString());
    assertEquals(Set.of("house_coordinator"), agents.names());
    assertTrue(
        agents.get("house_coordinator").orchestrations().contains("investigate_office_heat"));
    Path orchestration = examples.resolve("project/orchestrations/investigate_office_heat.md");
    var loaded =
        OrchestrationRegistry.parsePinned(
            "investigate_office_heat",
            orchestration.toString(),
            Files.readString(orchestration),
            tools,
            OrchestrationDefinition.Tier.PROJECT);
    assertEquals(3, loaded.stages().size());
    assertEquals(loaded.name(), binding.routes().get("office_heat").definition());
    Path skill = examples.resolve("project/skills/house-evidence/SKILL.md");
    var definition =
        SkillDefinition.parse(
            new DefinitionSource.Definition(
                "house-evidence", skill.toString(), Files.readString(skill)),
            OrchestrationDefinition.Tier.PROJECT);
    assertEquals(SkillDefinition.Mode.NEW, definition.mode());
    assertEquals("house_coordinator", definition.agent());
    assertEquals(tools, Set.copyOf(definition.allowedTools()));
    assertEquals(
        0,
        new ScriptHost(Duration.ofSeconds(5))
            .evaluate(
                Files.readString(examples.resolve("office.mjs")),
                "",
                new io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope(
                    new io.aeyer.plowshare.integrations.IntegrationContracts.Empty()),
                io.aeyer.plowshare.integrations.IntegrationContracts.ScriptContext.empty())
            .effects()
            .size());
  }
}
