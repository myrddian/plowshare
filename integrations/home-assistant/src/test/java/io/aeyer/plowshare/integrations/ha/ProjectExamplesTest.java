package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.Configuration;
import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ScriptHost;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.DefinitionSource;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.agents.SkillDefinition;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.archive.ProjectRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Real current definition parsers, without starting a server or inference model. */
class ProjectExamplesTest {
  @Test
  void application_resources_load_and_match_the_binding() throws Exception {
    Path examples = Path.of(System.getProperty("integration.examples"));
    Configuration config = Configuration.read(examples.resolve("config.json"));
    var binding = config.bindings().get("house");
    Path application = examples.resolve("application");
    var boundary =
        WorkspaceApplicationPolicy.parse(
            Files.readString(application.resolve("plowshare.json")), binding.project());
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertTrue(boundary.accounts().isEmpty());
    assertTrue(boundary.limit("ungranted", Optional.of(ProjectRole.MANAGER)).isEmpty());
    assertFalse(Files.exists(application.resolve("plowshare")));
    Path definitions = application;
    Set<String> tools = Set.of("relay_publish");
    AgentRegistry agents = AgentRegistry.of(definitions.resolve("agents"), tools, Set.of());
    assertTrue(agents.disabled().isEmpty(), agents.disabled().toString());
    assertEquals(Set.of("house_coordinator"), agents.names());
    assertTrue(
        agents.get("house_coordinator").orchestrations().contains("investigate_office_heat"));
    Path orchestration = definitions.resolve("orchestrations/investigate_office_heat.md");
    var loaded =
        OrchestrationRegistry.parsePinned(
            "investigate_office_heat",
            orchestration.toString(),
            Files.readString(orchestration),
            tools,
            OrchestrationDefinition.Tier.PROJECT);
    assertEquals(3, loaded.stages().size());
    assertEquals(loaded.name(), binding.routes().get("office_heat").definition());
    Path skill = definitions.resolve("skills/house-evidence/SKILL.md");
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
                new IntegrationContracts.EventEnvelope(new IntegrationContracts.Empty()),
                IntegrationContracts.ScriptContext.empty())
            .effects()
            .size());
  }
}
