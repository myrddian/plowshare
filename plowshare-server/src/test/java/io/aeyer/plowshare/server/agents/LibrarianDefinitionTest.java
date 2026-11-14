package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The standalone Librarian is a read-only worker that can also execute the fresh-context skill. */
class LibrarianDefinitionTest {
  private final AgentDefinition librarian =
      ShippedDefinitions.registry(BoundTools.boundByThisServer()).get("librarian");

  @Test
  void discovery_and_retained_reads_are_the_only_capabilities() {
    assertEquals(
        List.of(
            "information_scopes",
            "information_list",
            "information_search",
            "information_facets",
            "information_read",
            "document_retrieve",
            "document_outline",
            "document_citations",
            "result_read",
            "result_list"),
        librarian.tools());
    assertTrue(librarian.scopes().isEmpty());
    assertTrue(librarian.calls().isEmpty());
    // Granted only so SkillRuntime admits it as the skill's executor; see below.
    assertEquals(List.of("information-discovery"), librarian.skills());
    assertFalse(librarian.canDelegate());
    assertFalse(librarian.dynamic());
  }

  @Test
  void the_same_worker_can_be_invoked_independently_or_by_the_granted_skill() {
    assertTrue(librarian.exported());
    assertTrue(librarian.delegable());
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(
                    OrchestrationDefinition.Tier.SHIPPED, SkillSource.shipped())));
    var skill = catalog.skills().get("information-discovery").definition();
    assertEquals(librarian.name(), skill.agent());
    assertEquals(SkillDefinition.Mode.NEW, skill.mode());
    assertEquals(librarian.tools(), skill.allowedTools());
    for (String caller : List.of("interlocutor", "farnsworth", "aristoxenus")) {
      var definition = ShippedDefinitions.registry(BoundTools.boundByThisServer()).get(caller);
      assertTrue(definition.canUseSkill(skill.name()));
      assertTrue(AgentRegistry.escalatingGrant(definition, librarian).isEmpty());
    }
  }

  @Test
  void every_shipped_skill_executor_is_granted_its_skill() {
    // SkillRuntime refuses a NEW-mode executor that is not granted the skill ("The selected
    // executor is not granted that skill"), so a shipped skill naming an ungranted agent can
    // never run. information-discovery shipped that way.
    var registry = ShippedDefinitions.registry(BoundTools.boundByThisServer());
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(
                    OrchestrationDefinition.Tier.SHIPPED, SkillSource.shipped())));
    assertFalse(catalog.skills().isEmpty());
    catalog
        .skills()
        .forEach(
            (name, resolved) -> {
              var skill = resolved.definition();
              if (skill.agentSpecified()) {
                var executor = registry.get(skill.agent());
                assertTrue(executor.canUseSkill(name), skill.agent() + " is not granted " + name);
                assertTrue(executor.delegable(), skill.agent());
              }
            });
  }

  @Test
  void the_executor_is_told_not_to_reinvoke_its_own_skill() {
    assertTrue(librarian.prompt().contains("Never call skill_run"));
  }

  @Test
  void navigation_has_a_bounded_model_budget() {
    assertTrue(librarian.maxModelCalls() > 1);
    assertTrue(librarian.maxTurns() > librarian.maxModelCalls());
  }
}
