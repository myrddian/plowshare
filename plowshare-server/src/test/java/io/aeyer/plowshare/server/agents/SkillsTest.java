package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.FileRequest;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.data.DataLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillsTest {
  @TempDir Path temporary;

  private static String skill(String name, String fields) {
    return "---\nname: "
        + name
        + "\ndescription: Explicit review\n"
        + fields
        + "---\nReview the supplied work.";
  }

  private static SkillDefinition parse(String name, String fields) {
    return SkillDefinition.parse(
        new DefinitionSource.Definition(name, name + "/SKILL.md", skill(name, fields)),
        Tier.PROJECT);
  }

  private static AgentDefinition agent(String fields) {
    String source =
        "---\nname: worker\ndescription: Worker\nmodel: reasoning\ntools: []\ncalls: []\nscopes: []\nmax-turns: 4\nmax-model-calls: 8\n"
            + fields
            + "---\nWork.";
    return AgentRegistry.parseWith(
            new DefinitionSource.Definition("worker", "worker.md", source),
            Set.of(),
            new java.util.LinkedHashMap<>(),
            Set.of(),
            Set.of())
        .definition();
  }

  private SkillSource directory(String directory, String name, String source) throws Exception {
    Path root = temporary.resolve(directory);
    Path packageRoot = Files.createDirectories(root.resolve(name));
    Files.writeString(packageRoot.resolve("SKILL.md"), source);
    return SkillSource.disk(root);
  }

  @Test
  void portable_metadata_does_not_enable_a_trigger_or_choose_a_context_default() {
    SkillDefinition skill =
        parse("review", "metadata:\n  author: someone\nallowed-tools: file_read file_grep\n");
    assertEquals("interlocutor", skill.agent());
    assertNull(skill.mode());
    assertFalse(skill.agentVisible());
    assertTrue(parse("review", "agentVisible: true\n").agentVisible());
    assertThrows(IllegalArgumentException.class, () -> parse("review", "agentVisible: 'true'\n"));
    assertEquals(List.of("file_read", "file_grep"), skill.allowedTools());
    assertEquals(Map.of("author", "someone"), skill.metadata());
    assertTrue(skill.hash().startsWith("sha256:"));
    assertThrows(IllegalArgumentException.class, () -> parse("review", "auto-trigger: true\n"));
  }

  @Test
  void strict_yaml_and_execution_conflicts_are_visible_failures() {
    assertThrows(RuntimeException.class, () -> parse("review", "name: review\n"));
    assertThrows(RuntimeException.class, () -> parse("review", "metadata: {author: 7}\n"));
    assertThrows(RuntimeException.class, () -> parse("review", "agent: reviewer\nmode: DIRECT\n"));
    assertThrows(RuntimeException.class, () -> parse("bad--name", ""));
    assertThrows(
        RuntimeException.class,
        () ->
            SkillDefinition.parse(
                new DefinitionSource.Definition("other", "other/SKILL.md", skill("review", "")),
                Tier.GLOBAL));
    assertEquals(SkillDefinition.Mode.INHERITED, parse("review", "mode: INHERITED\n").mode());
    assertEquals("specialist", parse("review", "agent: specialist\nmode: NEW\n").agent());
    assertEquals("révision", parse("révision", "").name());
    assertThrows(RuntimeException.class, () -> parse("Révision", ""));
  }

  @Test
  void ordinary_agents_default_to_no_skills_and_copies_preserve_explicit_grants() {
    assertFalse(agent("").canUseSkill("review"));
    assertFalse(agent("bot: true\n").canUseSkill("review"));
    assertTrue(agent("bot: true\nskills: ['*']\n").canUseSkill("review"));
    assertFalse(agent("bot: true\nskills: [other]\n").canUseSkill("review"));
    AgentDefinition named = agent("skills: [review]\n");
    assertTrue(named.canUseSkill("review"));
    assertFalse(named.canUseSkill("unlisted"));
    assertEquals(
        List.of("review"),
        named
            .withPrompt("Changed")
            .withTools(List.of())
            .withScopes(List.of())
            .withCaps(2, 3)
            .fallback(named.fallback())
            .sampling(named.sampling())
            .skills());
  }

  @Test
  void wildcard_grants_intersect_visibility_and_do_not_resurrect_refused_overrides()
      throws Exception {
    SkillSource project = directory("project", "review", "not a skill");
    SkillSource global = directory("global", "review", skill("review", ""));
    Files.createDirectories(temporary.resolve("global/visible"));
    Files.writeString(temporary.resolve("global/visible/SKILL.md"), skill("visible", ""));
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(Tier.PROJECT, project),
                new SkillResolver.Layer(Tier.GLOBAL, global)));
    assertTrue(catalog.refused().containsKey("review"));
    assertEquals(Set.of("visible"), catalog.granted(agent("skills: ['*']\n")).keySet());
    assertTrue(catalog.granted(agent("")).isEmpty());
  }

  @Test
  void local_resources_cannot_escape_by_dotdot_or_symlink() throws Exception {
    SkillSource source = directory("local", "review", skill("review", ""));
    Path refs = Files.createDirectories(temporary.resolve("local/review/references"));
    Files.writeString(refs.resolve("guide.md"), "Guide");
    Files.writeString(temporary.resolve("secret"), "Secret");
    Files.createSymbolicLink(refs.resolve("outside.md"), temporary.resolve("secret"));
    assertEquals("Guide", source.readResource("review", "references/guide.md"));
    assertThrows(
        IllegalArgumentException.class, () -> source.readResource("review", "../../secret"));
    assertThrows(
        IllegalArgumentException.class,
        () -> source.readResource("review", "references/outside.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> source.readResource("review", "references/..\\secret"));
  }

  @Test
  void remote_packages_and_resources_keep_the_discovered_root_and_harness_purpose() {
    String root = "/client/work";
    List<FileRequest> requests = new ArrayList<>();
    FakeFiles files =
        new FakeFiles()
            .withRoots(List.of(root))
            .withRawListing(
                ".plowshare/skills",
                List.of(
                    root + "/.plowshare/skills/review/SKILL.md",
                    ".plowshare/skills/../escape/SKILL.md",
                    ".plowshare/skills/bad\\path/SKILL.md"))
            .withFile(root + "/.plowshare/skills/review/SKILL.md", skill("review", "mode: NEW\n"))
            .withFile(root + "/.plowshare/skills/review/references/guide.md", "Remote guide");
    SkillSource source =
        SkillSource.channel(
            (session, request) -> {
              assertEquals("own-session", session);
              requests.add(request);
              return files.ask(session, request);
            },
            "own-session");
    assertEquals(
        List.of("review"), source.list().stream().map(DefinitionSource.Definition::name).toList());
    assertEquals("Remote guide", source.readResource("review", "references/guide.md"));
    assertTrue(
        requests.stream().allMatch(request -> FileRequest.DEFINITIONS.equals(request.purpose())));
    assertFalse(requests.stream().anyMatch(request -> FileRequest.RUN.equals(request.op())));
    assertFalse(
        requests.stream()
            .anyMatch(request -> request.path() != null && request.path().contains("escape")));
  }

  @Test
  void an_unreadable_remote_override_does_not_fall_through_to_global() throws Exception {
    FakeFiles files = new FakeFiles().withListing(".plowshare/skills", List.of("review/SKILL.md"));
    var catalog =
        SkillResolver.load(
            List.of(
                new SkillResolver.Layer(Tier.SESSION, SkillSource.channel(files, "rooted")),
                new SkillResolver.Layer(
                    Tier.GLOBAL, directory("fallback", "review", skill("review", "")))));
    assertTrue(catalog.skills().isEmpty());
    assertTrue(catalog.refused().get("review").contains("file disappeared"));
  }

  @Test
  void unrelated_or_unrooted_sessions_never_supply_skill_definitions() {
    List<FileRequest> requests = new ArrayList<>();
    SkillResolver resolver =
        new SkillResolver(
            DataLayout.NONE,
            (session, request) -> {
              requests.add(request);
              throw new AssertionError("unrooted session was consulted");
            },
            id -> true,
            session -> true,
            (project, session) -> false);
    assertTrue(resolver.forCaller(new DefinitionResolver.Caller(7L, "other")).skills().isEmpty());
    assertTrue(requests.isEmpty());
  }

  @Test
  void scoped_agent_rules_apply_parent_first_and_alias_conflicts_refuse() throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("data"));
    Path global = Files.createDirectories(data.agentsFor(null).getParent());
    Path project = Files.createDirectories(data.agentsFor(7L).getParent());
    Files.writeString(global.resolve("AGENT.md"), "Global");
    Files.writeString(project.resolve("AGENTS.md"), "Project");
    Path nested = Files.createDirectories(project.resolve("src"));
    Files.writeString(nested.resolve("AGENTS.md"), "Nested");
    AgentRules rules = new AgentRules(data, new FakeFiles(), session -> false, (p, s) -> false);
    assertEquals(
        List.of("Global", "Project", "Nested"),
        rules.forPath(new DefinitionResolver.Caller(7L, null), "worker", "src/test.java").stream()
            .map(AgentRules.Rule::text)
            .toList());
    assertTrue(
        rules
            .apply(new DefinitionResolver.Caller(7L, null), agent(""))
            .prompt()
            .contains("Project"));
    Files.writeString(project.resolve("AGENT.md"), "Different");
    assertThrows(
        IllegalArgumentException.class,
        () -> rules.forPath(new DefinitionResolver.Caller(7L, null), "worker", null));
  }

  @Test
  void remote_rules_are_loaded_by_the_server_without_changing_agent_grants() {
    FakeFiles files =
        new FakeFiles()
            .withFile("AGENTS.md", "Root rules")
            .withFile(".plowshare/AGENT.md", "Project rules")
            .withFile(".plowshare/agents/worker/AGENTS.md", "Worker rules");
    AgentRules rules = new AgentRules(DataLayout.NONE, files, session -> true, (p, s) -> true);
    AgentDefinition resolved =
        rules.apply(new DefinitionResolver.Caller(7L, "rooted"), agent("skills: [review]\n"));
    assertTrue(resolved.prompt().contains("Worker rules"));
    assertEquals(List.of("review"), resolved.skills());
    assertFalse(resolved.canUseSkill("other"));
  }

  @Test
  void command_discovery_contains_only_granted_metadata_never_instructions() throws Exception {
    SkillSource source = directory("commands", "review", skill("review", "mode: NEW\n"));
    var catalog = SkillResolver.load(List.of(new SkillResolver.Layer(Tier.PROJECT, source)));
    assertTrue(CommandCatalog.of(agent(""), catalog, Map.of()).isEmpty());
    var commands = CommandCatalog.of(agent("skills: [review]\n"), catalog, Map.of());
    assertEquals("/skill:review", commands.getFirst().command());
    assertTrue(commands.getFirst().aliases().isEmpty());
    assertEquals("interlocutor", commands.getFirst().executor());
    assertFalse(commands.getFirst().agentVisible());
    assertFalse(commands.toString().contains("Review the supplied work"));
  }

  @Test
  void json_project_visibility_overrides_legacy_policy_without_granting_skills() throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("manifest-visibility"));
    Path shared = Files.createDirectories(data.skillsFor(null).resolve("review"));
    Files.writeString(shared.resolve("SKILL.md"), skill("review", "mode: DIRECT\n"));
    FakeFiles files =
        new FakeFiles()
            .withFile(
                "plowshare",
                "{\"version\":1,\"name\":\"house\",\"skills\":{\"review\":{\"agentVisible\":true}}}");
    SkillResolver resolver =
        new SkillResolver(data, files, id -> true, s -> true, (p, s) -> p == 7L);
    var caller = new DefinitionResolver.Caller(7L, "rooted");
    var local = resolver.forCaller(caller);
    assertTrue(local.skills().get("review").definition().agentVisible());
    assertTrue(local.granted(agent("")).isEmpty());
    resolver.useProjectConfiguration(
        id ->
            ProjectConfiguration.parse(
                "{\"version\":1,\"name\":\"house\",\"skills\":{\"review\":{\"agentVisible\":false}}}",
                "house",
                "server manifest"));
    assertFalse(resolver.forCaller(caller).skills().get("review").definition().agentVisible());
  }

  @Test
  void project_visibility_overrides_shared_defaults_without_changing_the_source_or_grants()
      throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("visibility"));
    Path shared = Files.createDirectories(data.skillsFor(null).resolve("review"));
    Files.writeString(shared.resolve("SKILL.md"), skill("review", "mode: DIRECT\n"));
    Path project = Files.createDirectories(data.skillsFor(7L).getParent());
    Path other = Files.createDirectories(data.skillsFor(8L).getParent());
    Files.writeString(
        project.resolve("skills.yml"), "skills:\n  review:\n    agentVisible: true\n");
    SkillResolver resolver =
        new SkillResolver(data, new FakeFiles(), id -> true, session -> false, (p, s) -> false);
    var enabled = resolver.forCaller(new DefinitionResolver.Caller(7L, null));
    var unchanged = resolver.forCaller(new DefinitionResolver.Caller(8L, null));
    assertTrue(enabled.skills().get("review").definition().agentVisible());
    assertFalse(unchanged.skills().get("review").definition().agentVisible());
    assertEquals(
        unchanged.skills().get("review").definition().hash(),
        enabled.skills().get("review").definition().hash());
    assertEquals(Tier.GLOBAL, enabled.skills().get("review").definition().tier());
    assertTrue(enabled.granted(agent("")).isEmpty());
    assertTrue(
        CommandCatalog.of(agent("skills: ['*']\n"), enabled, Map.of()).getFirst().agentVisible());
    Files.writeString(
        shared.resolve("SKILL.md"), skill("review", "mode: DIRECT\nagentVisible: true\n"));
    Files.writeString(other.resolve("skills.yml"), "skills:\n  review:\n    agentVisible: false\n");
    assertFalse(
        resolver
            .forCaller(new DefinitionResolver.Caller(8L, null))
            .skills()
            .get("review")
            .definition()
            .agentVisible());
    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(9L, null))
            .skills()
            .get("review")
            .definition()
            .agentVisible());
  }

  @Test
  void personal_policy_is_an_account_default_and_server_project_wins_over_rooted_session()
      throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("precedence"));
    data.usePersonalProjects(id -> id == 10L);
    Path personal = Files.createDirectories(data.skillsFor(10L).resolve("review"));
    Files.writeString(personal.resolve("SKILL.md"), skill("review", "mode: DIRECT\n"));
    Files.writeString(
        personal.getParent().getParent().resolve("skills.yml"),
        "skills:\n  review:\n    agentVisible: true\n");
    Path project = Files.createDirectories(data.skillsFor(7L).getParent());
    FakeFiles files =
        new FakeFiles()
            .withFile(".plowshare/skills.yml", "skills:\n  review:\n    agentVisible: true\n");
    SkillResolver resolver =
        new SkillResolver(data, files, id -> true, session -> true, (p, s) -> s.equals("rooted"));
    resolver.usePersonalResources(caller -> 10L);
    var caller = new DefinitionResolver.Caller(7L, "rooted");
    assertTrue(resolver.forCaller(caller).skills().get("review").definition().agentVisible());
    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(7L, "unrooted"))
            .skills()
            .get("review")
            .definition()
            .agentVisible());
    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(null, null))
            .skills()
            .get("review")
            .definition()
            .agentVisible());
    files.withFile(".plowshare/skills.yml", "skills:\n  review:\n    agentVisible: false\n");
    var hidden = resolver.forCaller(caller);
    assertFalse(hidden.skills().get("review").definition().agentVisible());
    assertEquals(
        "/skill:review",
        CommandCatalog.of(agent("skills: [review]\n"), hidden, Map.of()).getFirst().command());
    assertTrue(CommandCatalog.of(agent(""), hidden, Map.of()).isEmpty());
    files.withFile(".plowshare/skills.yml", "skills:\n  review:\n    agentVisible: true\n");
    Files.writeString(
        project.resolve("skills.yml"), "skills:\n  review:\n    agentVisible: false\n");
    assertFalse(resolver.forCaller(caller).skills().get("review").definition().agentVisible());
    assertTrue(
        resolver
            .forCaller(new DefinitionResolver.Caller(8L, "unrooted"))
            .skills()
            .get("review")
            .definition()
            .agentVisible());
    resolver.usePersonalResources(account -> null);
    assertFalse(
        resolver
            .forCaller(new DefinitionResolver.Caller(8L, "unrooted"))
            .skills()
            .containsKey("review"),
        "Another account cannot inherit Personal skills or policy");
  }

  @Test
  void
      unreadable_or_invalid_visibility_is_refused_instead_of_falling_back_to_visible_shared_skills()
          throws Exception {
    DataLayout data = new DataLayout(temporary.resolve("invalid-policy"));
    Path shared = Files.createDirectories(data.skillsFor(null).resolve("review"));
    Files.writeString(shared.resolve("SKILL.md"), skill("review", "agentVisible: true\n"));
    Path project = Files.createDirectories(data.skillsFor(7L).getParent());
    Path policy = project.resolve("skills.yml");
    SkillResolver resolver =
        new SkillResolver(data, new FakeFiles(), id -> true, session -> false, (p, s) -> false);
    for (String invalid :
        List.of(
            "skills: {review: {agentVisible: 'true'}}",
            "skills: {review: {enabled: true}}",
            "skills: {review: {agentVisible: true, agentVisible: false}}",
            "skills: []")) {
      Files.writeString(policy, invalid);
      var refused = resolver.forCaller(new DefinitionResolver.Caller(7L, null));
      assertTrue(refused.skills().isEmpty());
      assertTrue(refused.refused().containsKey("(skill visibility)"));
    }
    Files.delete(policy);
    Files.writeString(
        temporary.resolve("outside-policy"), "skills: {review: {agentVisible: true}}");
    Files.createSymbolicLink(policy, temporary.resolve("outside-policy"));
    assertTrue(resolver.forCaller(new DefinitionResolver.Caller(7L, null)).skills().isEmpty());
  }
}
