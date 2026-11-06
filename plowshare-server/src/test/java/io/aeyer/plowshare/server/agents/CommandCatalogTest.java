package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandCatalogTest {
  private OrchestrationDefinition workflow(String alias) {
    var definition = mock(OrchestrationDefinition.class);
    var conductor = mock(AgentDefinition.class);
    when(conductor.name()).thenReturn("conductor");
    when(definition.conductor()).thenReturn(conductor);
    when(definition.tier()).thenReturn(OrchestrationDefinition.Tier.GLOBAL);
    when(definition.triggers())
        .thenReturn(
            List.of(
                new OrchestrationDefinition.Trigger("a phrase", false),
                new OrchestrationDefinition.Trigger(alias, true)));
    return definition;
  }

  private List<CommandCatalog.Entry> catalog(
      List<String> grants, Map<String, OrchestrationDefinition> definitions) {
    var agent = mock(AgentDefinition.class);
    when(agent.orchestrations()).thenReturn(grants);
    return CommandCatalog.of(agent, new SkillResolver.Catalog(Map.of(), Map.of()), definitions);
  }

  @Test
  void command_triggers_are_granted_aliases_and_phrase_triggers_are_not() {
    var aliases =
        Map.of(
            "deep_research",
            "/research",
            "code_implementation",
            "/implement",
            "implement_specification",
            "/deliver",
            "design_orchestration",
            "/design-orchestration");
    var definitions = new java.util.HashMap<String, OrchestrationDefinition>();
    aliases.forEach((name, alias) -> definitions.put(name, workflow(alias)));
    var entries = catalog(List.copyOf(aliases.keySet()), definitions);
    assertEquals(4, entries.size());
    for (var entry : entries) {
      assertEquals("/orchestration:" + entry.name(), entry.command());
      assertEquals(List.of(aliases.get(entry.name())), entry.aliases());
    }
    assertTrue(catalog(List.of(), definitions).isEmpty());
  }

  @Test
  void ambiguous_aliases_are_withheld_without_hiding_the_qualified_commands() {
    var definitions = Map.of("first", workflow("/research"), "second", workflow("/research"));
    var entries = catalog(List.of("first", "second"), definitions);
    assertEquals(2, entries.size());
    assertTrue(entries.stream().allMatch(entry -> entry.aliases().isEmpty()));
    assertEquals(List.of("/research"), catalog(List.of("first"), definitions).getFirst().aliases());
  }
}
