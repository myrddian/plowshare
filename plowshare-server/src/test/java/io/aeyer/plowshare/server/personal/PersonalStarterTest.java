package io.aeyer.plowshare.server.personal;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.agents.SkillDefinition;
import io.aeyer.plowshare.server.agents.SkillResolver;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersonalStarterTest {
  @TempDir Path temporary;

  @Test
  void editable_packages_follow_account_personal_defaults_and_all_referenced_resources_exist()
      throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("data"));
    data.usePersonalProjects(id -> id == 10L);
    Path root = Files.createDirectories(data.unionFor(10L).resolve("tree"));
    var installed = PersonalStarter.install(root);
    assertEquals(25, installed.created().size());
    SkillResolver resolver =
        new SkillResolver(
            data,
            (session, request) -> {
              throw new AssertionError("No session read needed");
            },
            id -> true,
            session -> false,
            (id, session) -> false);
    resolver.usePersonalResources(caller -> "alice".equals(caller.handle()) ? 10L : null);
    var personal = resolver.forCaller(new Caller(10L, null, "alice"));
    assertTrue(personal.refused().isEmpty(), personal.refused().toString());
    assertEquals(
        Set.of(
            "personal-capture",
            "personal-process-inbox",
            "personal-recall",
            "personal-plan",
            "personal-express",
            "personal-review",
            "personal-maintain-knowledge"),
        personal.skills().keySet());
    var worker =
        new AgentDefinition(
            "worker", "Worker", "reasoning", List.of(), List.of(), List.of(), 4, 8, "Role");
    assertTrue(personal.granted(worker).isEmpty());
    assertEquals(7, personal.granted(worker.withSkills(List.of("*"))).size());
    for (var skill : personal.skills().values()) {
      assertTrue(skill.definition().agentVisible());
      assertEquals(SkillDefinition.Mode.DIRECT, skill.definition().mode());
      var references =
          Pattern.compile("references/[a-z-]+\\.md").matcher(skill.definition().instructions());
      while (references.find())
        assertFalse(
            skill
                .resources()
                .readResource(skill.definition().name(), references.group())
                .isBlank());
    }
    var otherProject = resolver.forCaller(new Caller(11L, null, "alice"));
    assertEquals(personal.skills().keySet(), otherProject.skills().keySet());
    assertTrue(
        otherProject.skills().values().stream()
            .allMatch(skill -> skill.definition().agentVisible()));
    assertTrue(otherProject.granted(worker).isEmpty());
    assertTrue(
        resolver.forCaller(new Caller(11L, null, "bob")).skills().values().stream()
            .noneMatch(skill -> skill.definition().agentVisible()));
    String rules = Files.readString(root.resolve("Resources/AGENTS.md"));
    assertTrue(rules.contains("In another project"));
    assertTrue(rules.contains("preserve user"));
  }

  @Test
  void existing_instructions_preferences_and_visibility_are_preserved() throws Exception {
    Path root = temporary.resolve("existing");
    Files.createDirectories(root.resolve("Resources/personal"));
    Files.writeString(root.resolve("Resources/AGENTS.md"), "My instructions");
    Files.writeString(root.resolve("Resources/skills.yml"), "skills: {}\n");
    var result = PersonalStarter.install(root);
    assertEquals(List.of("Resources/AGENTS.md", "Resources/skills.yml"), result.preserved());
    assertEquals("My instructions", Files.readString(root.resolve("Resources/AGENTS.md")));
    assertEquals("skills: {}\n", Files.readString(root.resolve("Resources/skills.yml")));
    Files.writeString(root.resolve("Resources/personal/preferences.md"), "My preferences");
    assertTrue(PersonalStarter.install(root).created().isEmpty());
    assertEquals(
        "My preferences", Files.readString(root.resolve("Resources/personal/preferences.md")));
  }

  @Test
  void symlinked_targets_cannot_write_outside_personal() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("root"));
    Path outside = Files.createDirectories(temporary.resolve("outside"));
    Files.createSymbolicLink(root.resolve("Resources"), outside);
    assertThrows(java.io.IOException.class, () -> PersonalStarter.install(root));
    assertFalse(Files.exists(outside.resolve("AGENTS.md")));
  }
}
