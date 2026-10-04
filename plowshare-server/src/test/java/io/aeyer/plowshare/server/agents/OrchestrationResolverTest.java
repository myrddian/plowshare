package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.DefinitionResolver.Caller;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrchestrationResolverTest {

  private static final Set<String> TOOLS = Set.of("file_read", "agent_run");

  private static void orchestration(Path dir, String name, String body) throws Exception {
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".md"),
        "---\nname: "
            + name
            + "\ndescription: d\n"
            + "model: m\nmax-turns: 2\nmax-model-calls: 4\nstages:\n  - {id: goal}\n---\n"
            + body
            + "\n");
  }

  private static OrchestrationDefinition bootDefinition(Path dir, String name, String body)
      throws Exception {
    orchestration(dir, name, body);
    return OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(dir))),
            TOOLS,
            new AgentRegistry(Map.of()),
            DefinitionChecks.NONE)
        .enabled()
        .get(name);
  }

  private static OrchestrationResolver resolver(
      DataLayout layout,
      OrchestrationRegistry.Loaded boot,
      SessionChannel channel,
      AtomicReference<AgentRegistry> agents) {
    return new OrchestrationResolver(
        boot,
        layout,
        id -> true,
        TOOLS,
        channel,
        session -> true,
        (projectId, session) -> true,
        caller -> agents.get(),
        DefinitionChecks.NONE);
  }

  @Test
  void personal_orchestrations_follow_the_account_and_are_present_in_authoring_trials(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    orchestration(layout.orchestrationsFor(8L), "review", "Personal review");
    orchestration(layout.orchestrationsFor(9L), "review", "Other review");
    orchestration(layout.orchestrationsFor(8L), "design_orchestration", "Protected shadow");
    var agents = new AtomicReference<>(new AgentRegistry(Map.of()));
    var resolver =
        resolver(
            layout, new OrchestrationRegistry.Loaded(Map.of(), Map.of()), new FakeFiles(), agents);
    resolver.usePersonalResources(caller -> "alice".equals(caller.handle()) ? 8L : 9L);
    var alice = new DefinitionResolver.Caller(7L, null, "alice");
    var bob = new DefinitionResolver.Caller(7L, null, "bob");
    assertEquals(
        OrchestrationDefinition.Tier.PERSONAL, resolver.forCaller(alice).get("review").tier());
    assertNotEquals(
        resolver.forCaller(alice).get("review").hash(),
        resolver.forCaller(bob).get("review").hash());
    assertFalse(resolver.forCaller(alice).containsKey("design_orchestration"));
    orchestration(layout.orchestrationsFor(7L), "draft", "Draft procedure");
    var trial =
        resolver.trial(
            alice, "draft", Files.readString(layout.orchestrationsFor(7L).resolve("draft.md")));
    assertEquals(OrchestrationDefinition.Tier.PERSONAL, trial.reachable().get("review").tier());
    orchestration(layout.orchestrationsFor(7L), "review", "Project review");
    assertEquals(
        OrchestrationDefinition.Tier.PROJECT, resolver.forCaller(alice).get("review").tier());
  }

  @Test
  void project_and_session_cannot_shadow_the_required_builder_or_poison_its_neighbours(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationDefinition system =
        bootDefinition(data.resolve("boot"), "design_orchestration", "system builder");
    var boot = new OrchestrationRegistry.Loaded(Map.of("design_orchestration", system), Map.of());
    // A shadow's cycle must be refused as a protected name before it can disable its neighbour.
    Path project = layout.orchestrationsFor(7L);
    orchestration(project, "design_orchestration", "project shadow");
    String shadow =
        Files.readString(project.resolve("design_orchestration.md"))
            .replace("stages:", "orchestrations: [triage]\nstages:");
    Files.writeString(project.resolve("design_orchestration.md"), shadow);
    orchestration(project, "triage", "ordinary project procedure");
    Files.writeString(
        project.resolve("triage.md"),
        Files.readString(project.resolve("triage.md"))
            .replace("stages:", "orchestrations: [design_orchestration]\nstages:"));
    FakeFiles files =
        new FakeFiles()
            .withListing(".plowshare/orchestrations", List.of("design_orchestration.md"))
            .withFile(".plowshare/orchestrations/design_orchestration.md", shadow);
    var resolver =
        resolver(layout, boot, files, new AtomicReference<>(new AgentRegistry(Map.of())));
    Caller caller = new Caller(7L, "s-laptop");
    assertSame(system, resolver.forCaller(caller).get("design_orchestration"));
    assertTrue(resolver.forCaller(caller).containsKey("triage"));
    assertTrue(
        resolver
            .refusalsFor(caller)
            .get("design_orchestration")
            .contains("required system capability"));

    Files.delete(project.resolve("design_orchestration.md"));
    assertSame(system, resolver.forCaller(caller).get("design_orchestration"));
    assertTrue(
        resolver
            .refusalsFor(caller)
            .get("design_orchestration")
            .contains("required system capability"),
        "session shadow is refused too");
  }

  @Test
  void studio_trial_cannot_replace_the_system_builder(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationDefinition system =
        bootDefinition(data.resolve("boot"), "design_orchestration", "system builder");
    var resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of("design_orchestration", system), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));
    Caller caller = new Caller(7L, null);
    var trial = resolver.trial(caller, "design_orchestration", system.source());
    assertNull(trial.draft());
    assertTrue(trial.refusal().contains("required system capability"));
    assertSame(system, resolver.forCaller(caller).get("design_orchestration"));
    assertFalse(Files.exists(layout.orchestrationsFor(7L).resolve("design_orchestration.md")));
  }

  @Test
  void a_caller_with_no_project_gets_the_boot_set(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationDefinition shipped = bootDefinition(data.resolve("boot"), "deep_research", "boot");
    OrchestrationRegistry.Loaded boot =
        new OrchestrationRegistry.Loaded(Map.of("deep_research", shipped), Map.of());

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));

    assertEquals(Map.of("deep_research", shipped), resolver.forCaller(new Caller(null, null)));
  }

  @Test
  void a_project_file_adds_to_and_shadows_the_boot_set(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationDefinition shipped = bootDefinition(data.resolve("boot"), "deep_research", "boot");
    OrchestrationDefinition shippedImpl =
        bootDefinition(data.resolve("boot"), "code_implementation", "boot impl");
    OrchestrationRegistry.Loaded boot =
        new OrchestrationRegistry.Loaded(
            Map.of("deep_research", shipped, "code_implementation", shippedImpl), Map.of());
    orchestration(layout.orchestrationsFor(7L), "code_implementation", "the project's own");
    orchestration(layout.orchestrationsFor(7L), "triage", "only sevens");

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));
    Map<String, OrchestrationDefinition> seven = resolver.forCaller(new Caller(7L, null));

    assertEquals(Set.of("deep_research", "code_implementation", "triage"), seven.keySet());
    assertTrue(seven.get("code_implementation").conductor().prompt().contains("the project's own"));
    assertEquals(OrchestrationDefinition.Tier.PROJECT, seven.get("code_implementation").tier());
    assertEquals(OrchestrationDefinition.Tier.GLOBAL, seven.get("deep_research").tier());
    assertFalse(resolver.forCaller(new Caller(8L, null)).containsKey("triage"));
  }

  @Test
  void a_session_rooting_the_project_adds_its_dot_plowshare_orchestrations(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    FakeFiles files =
        new FakeFiles()
            .withListing(".plowshare/orchestrations", List.of("local_flow.md"))
            .withFile(
                ".plowshare/orchestrations/local_flow.md",
                "---\nname: local_flow\n"
                    + "description: d\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n"
                    + "stages:\n  - {id: goal}\n---\nlocal\n");

    OrchestrationResolver resolver =
        resolver(
            layout,
            OrchestrationRegistry.Loaded.EMPTY,
            files,
            new AtomicReference<>(new AgentRegistry(Map.of())));

    assertTrue(resolver.forCaller(new Caller(7L, "s-laptop")).containsKey("local_flow"));
    assertEquals(
        OrchestrationDefinition.Tier.SESSION,
        resolver.forCaller(new Caller(7L, "s-laptop")).get("local_flow").tier());
    assertFalse(resolver.forCaller(new Caller(7L, null)).containsKey("local_flow"));
  }

  @Test
  void a_disabled_project_file_is_a_refusal_and_not_an_orchestration(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(layout.orchestrationsFor(7L).resolve("broken.md"), "no fence\n");

    OrchestrationResolver resolver =
        resolver(
            layout,
            OrchestrationRegistry.Loaded.EMPTY,
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    assertFalse(resolver.forCaller(new Caller(7L, null)).containsKey("broken"));
    assertTrue(
        resolver
            .refusalsFor(new Caller(7L, null))
            .get("broken")
            .startsWith("the orchestration definition 'broken'"));
  }

  @Test
  void the_tier_is_cached_until_its_directory_or_its_agents_change(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    orchestration(layout.orchestrationsFor(7L), "triage", "first");
    AtomicReference<AgentRegistry> agents = new AtomicReference<>(new AgentRegistry(Map.of()));
    OrchestrationResolver resolver =
        resolver(layout, OrchestrationRegistry.Loaded.EMPTY, new FakeFiles(), agents);

    OrchestrationDefinition first = resolver.find(new Caller(7L, null), "triage").orElseThrow();
    assertSame(first, resolver.find(new Caller(7L, null), "triage").orElseThrow());

    agents.set(new AgentRegistry(Map.of()));
    OrchestrationDefinition afterAgents =
        resolver.find(new Caller(7L, null), "triage").orElseThrow();
    assertNotSame(first, afterAgents);

    orchestration(layout.orchestrationsFor(7L), "triage", "second, and longer than the first");
    assertTrue(
        resolver
            .find(new Caller(7L, null), "triage")
            .orElseThrow()
            .conductor()
            .prompt()
            .contains("second"));
  }

  @Test
  void a_boot_orchestration_is_rechecked_against_the_callers_agents(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path agentsDir = data.resolve("boot-agents");
    Files.createDirectories(agentsDir);
    String coder =
        "---\nname: coder\ndescription: d\nmodel: m\nmax-turns: 2\n" + "max-model-calls: 4\n";
    Files.writeString(agentsDir.resolve("coder.md"), coder + "---\nYou code.\n");
    Path shadowDir = data.resolve("project-agents");
    Files.createDirectories(shadowDir);
    Files.writeString(
        shadowDir.resolve("coder.md"),
        coder + "delegable: false\nexported: true\n---\nYou code.\n");
    AgentRegistry bootAgents =
        new AgentRegistry(
            AgentRegistry.read(new FilesystemDefinitions(agentsDir), TOOLS, Set.of()));
    AgentRegistry shadowed =
        new AgentRegistry(
            AgentRegistry.read(new FilesystemDefinitions(shadowDir), TOOLS, Set.of()));
    Path bootDir = data.resolve("boot");
    Files.createDirectories(bootDir);
    Files.writeString(
        bootDir.resolve("implement.md"),
        "---\nname: implement\ndescription: d\n"
            + "model: m\nmax-turns: 2\nmax-model-calls: 4\ncalls: [coder]\n"
            + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
    OrchestrationRegistry.Loaded boot =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(bootDir))),
            TOOLS,
            bootAgents,
            DefinitionChecks.NONE);
    assertEquals(Set.of("implement"), boot.enabled().keySet());

    OrchestrationResolver resolver =
        new OrchestrationResolver(
            boot,
            layout,
            id -> true,
            TOOLS,
            new FakeFiles(),
            session -> true,
            (projectId, session) -> true,
            caller -> Long.valueOf(7L).equals(caller.projectId()) ? shadowed : bootAgents,
            DefinitionChecks.NONE);

    assertFalse(resolver.forCaller(new Caller(7L, null)).containsKey("implement"));
    assertTrue(
        resolver
            .refusalsFor(new Caller(7L, null))
            .get("implement")
            .endsWith("calls 'coder', which is not delegable: an agent can never run it"));
    assertTrue(resolver.forCaller(new Caller(8L, null)).containsKey("implement"));
    assertFalse(resolver.refusalsFor(new Caller(8L, null)).containsKey("implement"));
  }

  @Test
  void a_boot_orchestration_is_rechecked_against_the_callers_orchestrations(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path bootDir = data.resolve("boot");
    Files.createDirectories(bootDir);
    Files.writeString(
        bootDir.resolve("code_implementation.md"),
        "---\nname: code_implementation\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\norchestrations: [assistant]\n"
            + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
    OrchestrationRegistry.Loaded boot =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(bootDir))),
            TOOLS,
            new AgentRegistry(Map.of()),
            DefinitionChecks.NONE);
    // No tier serves 'assistant' yet, so the boot read itself has nothing to refuse.
    assertEquals(Set.of("code_implementation"), boot.enabled().keySet());

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));
    assertTrue(resolver.forCaller(new Caller(7L, null)).containsKey("code_implementation"));

    // Project 7 defines its own 'assistant', holding a scope the boot conductor does not.
    // The grant that was inert at boot time now resolves, and escalates.
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(
        layout.orchestrationsFor(7L).resolve("assistant.md"),
        "---\nname: assistant\ndescription: d\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\n"
            + "scopes: [workspace:write]\nstages:\n  - {id: goal}\n---\nYou conduct.\n");

    assertFalse(resolver.forCaller(new Caller(7L, null)).containsKey("code_implementation"));
    assertTrue(
        resolver
            .refusalsFor(new Caller(7L, null))
            .get("code_implementation")
            .contains("grants 'assistant', which is granted workspace:write"));
    assertTrue(resolver.forCaller(new Caller(7L, null)).containsKey("assistant"));
    // A different project, still with no 'assistant' of its own: the grant is inert again and
    // costs the boot conductor nothing.
    assertTrue(resolver.forCaller(new Caller(8L, null)).containsKey("code_implementation"));
  }

  @Test
  void a_project_orchestration_granting_a_wider_boot_one_is_refused(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path bootDir = data.resolve("boot");
    Files.createDirectories(bootDir);
    // The boot conductor holds a scope no project conductor here does.
    Files.writeString(
        bootDir.resolve("deep_research.md"),
        "---\nname: deep_research\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\nscopes: [workspace:write]\n"
            + "stages:\n  - {id: goal}\n---\nYou conduct.\n");
    OrchestrationRegistry.Loaded boot =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(bootDir))),
            TOOLS,
            new AgentRegistry(Map.of()),
            DefinitionChecks.NONE);
    assertEquals(Set.of("deep_research"), boot.enabled().keySet());
    // The project's own conductor grants it, holding nothing. Its own tier's read cannot see
    // the escalation: 'deep_research' is not in the map that read is over.
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(
        layout.orchestrationsFor(7L).resolve("triage.md"),
        "---\nname: triage\ndescription: d\nmodel: m\nmax-turns: 2\n"
            + "max-model-calls: 4\norchestrations: [deep_research]\n"
            + "stages:\n  - {id: goal}\n---\nYou conduct.\n");

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));

    assertFalse(
        resolver.forCaller(new Caller(7L, null)).containsKey("triage"),
        "a project conductor may not widen its reach by granting a boot orchestration");
    assertTrue(
        resolver
            .refusalsFor(new Caller(7L, null))
            .get("triage")
            .contains("grants 'deep_research', which is granted workspace:write"));
    assertTrue(
        resolver.forCaller(new Caller(7L, null)).containsKey("deep_research"),
        "the grantee itself is untouched");
  }

  /** A conductor holding {@code workspace:write} and granting nothing. */
  private static String wide(String name) {
    return "---\nname: "
        + name
        + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
        + "max-model-calls: 4\nscopes: [workspace:write]\nstages:\n  - {id: goal}\n---\n"
        + "You conduct.\n";
  }

  /** A conductor holding nothing and granting one orchestration. */
  private static String narrowGranting(String name, String granted) {
    return "---\nname: "
        + name
        + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
        + "max-model-calls: 4\norchestrations: ["
        + granted
        + "]\nstages:\n  - {id: goal}\n"
        + "---\nYou conduct.\n";
  }

  @Test
  void a_dropped_project_file_un_shadows_its_boot_definition_before_the_next_grant_is_judged(
      @TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path bootDir = data.resolve("boot");
    Files.createDirectories(bootDir);
    // Two wide boot conductors, each granting nothing, so the boot read has nothing to refuse.
    Files.writeString(bootDir.resolve("deep_research.md"), wide("deep_research"));
    Files.writeString(bootDir.resolve("assistant.md"), wide("assistant"));
    OrchestrationRegistry.Loaded boot =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(bootDir))),
            TOOLS,
            new AgentRegistry(Map.of()),
            DefinitionChecks.NONE);
    assertEquals(Set.of("deep_research", "assistant"), boot.enabled().keySet());
    Files.createDirectories(layout.orchestrationsFor(7L));
    // The project shadows 'deep_research' with a narrow one, which is itself refused for granting
    // the wide boot 'assistant' — so what this caller is offered under that name is the WIDE
    // boot file.
    Files.writeString(
        layout.orchestrationsFor(7L).resolve("deep_research.md"),
        narrowGranting("deep_research", "assistant"));
    // 'triage' grants 'deep_research'. Judged against the shadowing project file it looks clean;
    // judged against what is actually offered it escalates.
    Files.writeString(
        layout.orchestrationsFor(7L).resolve("triage.md"),
        narrowGranting("triage", "deep_research"));

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));
    Map<String, OrchestrationDefinition> seven = resolver.forCaller(new Caller(7L, null));
    Map<String, String> refusals = resolver.refusalsFor(new Caller(7L, null));

    assertFalse(
        seven.containsKey("triage"),
        "its grant resolves to the wide boot file once the project one is dropped");
    assertTrue(
        refusals.get("triage").contains("grants 'deep_research', which is granted workspace:write"),
        String.valueOf(refusals.get("triage")));
    assertTrue(
        refusals
            .get("deep_research")
            .contains("grants 'assistant', which is granted workspace:write"),
        String.valueOf(refusals.get("deep_research")));
    assertEquals(
        OrchestrationDefinition.Tier.GLOBAL,
        seven.get("deep_research").tier(),
        "the boot file of that name is un-shadowed and offered on its own terms");
    assertEquals(Set.of("deep_research", "assistant"), seven.keySet());
  }

  @Test
  void a_name_neither_tier_offers_explains_both_halves(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path bootDir = data.resolve("boot");
    Files.createDirectories(bootDir);
    // Boot 'deep_research' grants 'assistant', which no tier serves at boot, so the grant is
    // inert there and the boot read enables it.
    Files.writeString(
        bootDir.resolve("deep_research.md"), narrowGranting("deep_research", "assistant"));
    OrchestrationRegistry.Loaded boot =
        OrchestrationRegistry.read(
            List.of(
                new OrchestrationRegistry.Layer(
                    OrchestrationDefinition.Tier.GLOBAL, new FilesystemDefinitions(bootDir))),
            TOOLS,
            new AgentRegistry(Map.of()),
            DefinitionChecks.NONE);
    assertEquals(Set.of("deep_research"), boot.enabled().keySet());
    Files.createDirectories(layout.orchestrationsFor(7L));
    // The project's own file of that name does not parse, and its 'assistant' makes the boot
    // file of that name escalate: the name is offered by neither, for two different reasons.
    Files.writeString(layout.orchestrationsFor(7L).resolve("deep_research.md"), "no fence\n");
    Files.writeString(layout.orchestrationsFor(7L).resolve("assistant.md"), wide("assistant"));

    OrchestrationResolver resolver =
        resolver(layout, boot, new FakeFiles(), new AtomicReference<>(new AgentRegistry(Map.of())));

    assertEquals(Set.of("assistant"), resolver.forCaller(new Caller(7L, null)).keySet());
    String reason = resolver.refusalsFor(new Caller(7L, null)).get("deep_research");
    assertTrue(reason.startsWith("the orchestration definition 'deep_research'"), reason);
    assertTrue(
        reason.contains("The boot definition of that name is not offered here either:"), reason);
    assertTrue(reason.contains("grants 'assistant', which is granted workspace:write"), reason);
    assertFalse(reason.contains(".. The boot definition"), reason);
  }

  @Test
  void a_broken_tier_falls_back_once_per_change(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Path broken = layout.orchestrationsFor(7L);
    Files.createDirectories(broken.getParent());
    Files.writeString(broken, "a file where a directory belongs\n");
    OrchestrationResolver resolver =
        resolver(
            layout,
            OrchestrationRegistry.Loaded.EMPTY,
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));
    ch.qos.logback.classic.Logger log =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(OrchestrationResolver.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
        new ch.qos.logback.core.read.ListAppender<>();
    warnings.start();
    log.addAppender(warnings);
    try {
      assertEquals(Map.of(), resolver.forCaller(new Caller(7L, null)));
      assertEquals(Map.of(), resolver.forCaller(new Caller(7L, null)));
      assertEquals(1, warnings.list.size());
      assertTrue(
          resolver
              .refusalsFor(new Caller(7L, null))
              .get("(the project tier)")
              .contains("exists and is not a directory"));
      assertEquals(1, warnings.list.size());

      Files.delete(broken);
      orchestration(broken, "triage", "mended");
      assertTrue(resolver.forCaller(new Caller(7L, null)).containsKey("triage"));
      assertFalse(resolver.refusalsFor(new Caller(7L, null)).containsKey("(the project tier)"));
      assertEquals(1, warnings.list.size());
    } finally {
      log.detachAppender(warnings);
    }
  }

  @Test
  void invalidate_forgets_a_project(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    orchestration(layout.orchestrationsFor(7L), "triage", "first");
    OrchestrationResolver resolver =
        resolver(
            layout,
            OrchestrationRegistry.Loaded.EMPTY,
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));
    OrchestrationDefinition first = resolver.find(new Caller(7L, null), "triage").orElseThrow();

    resolver.invalidate(7L);

    assertNotSame(first, resolver.find(new Caller(7L, null), "triage").orElseThrow());
  }

  private static String draft(String name, String stages) {
    return "---\nname: "
        + name
        + "\ndescription: d\nmodel: m\nmax-turns: 2\n"
        + "max-model-calls: 4\nstages:\n"
        + stages
        + "---\nbody\n";
  }

  @Test
  void a_clean_draft_trials_as_it_would_load_and_names_what_it_replaces(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    orchestration(layout.orchestrationsFor(7L), "triage", "the old one");
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of(), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    OrchestrationResolver.Trial trial =
        resolver.trial(
            new Caller(7L, null),
            "triage",
            draft("triage", "  - {id: goal}\n  - {id: fix, may-return-to: [goal]}\n"));

    assertNull(trial.refusal());
    assertEquals(
        List.of("goal", "fix"),
        trial.draft().stages().stream().map(OrchestrationDefinition.Stage::id).toList());
    assertTrue(trial.replaces().conductor().prompt().contains("the old one"));
    assertFalse(trial.replacesBroken());
    assertNull(trial.shadows());
    assertEquals(Map.of(), trial.newlyRefused());
    assertTrue(trial.reachable().containsKey("triage"));
    assertTrue(
        Files.readString(layout.orchestrationsFor(7L).resolve("triage.md")).contains("the old one"),
        "a trial writes nothing");
  }

  @Test
  void a_draft_the_parser_refuses_is_refused_in_its_sentence(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of(), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    OrchestrationResolver.Trial trial =
        resolver.trial(
            new Caller(7L, null),
            "triage",
            draft("triage", "  - {id: goal, may-return-to: fix}\n  - {id: fix}\n"));

    assertNull(trial.draft());
    assertTrue(trial.refusal().contains("may-return-to"), trial.refusal());
  }

  @Test
  void a_draft_with_a_forward_return_is_refused(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of(), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    OrchestrationResolver.Trial trial =
        resolver.trial(
            new Caller(7L, null),
            "triage",
            draft("triage", "  - {id: goal, may-return-to: [fix]}\n  - {id: fix}\n"));

    assertNull(trial.draft());
    assertTrue(trial.refusal().contains("not an earlier stage"), trial.refusal());
  }

  @Test
  void a_draft_that_closes_a_grant_cycle_names_what_else_it_disables(@TempDir Path data)
      throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    Files.createDirectories(layout.orchestrationsFor(7L));
    Files.writeString(
        layout.orchestrationsFor(7L).resolve("outer.md"),
        draft("outer", "  - {id: goal}\n")
            .replace("max-turns: 2\n", "max-turns: 2\norchestrations: [inner]\n"));
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of(), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    OrchestrationResolver.Trial trial =
        resolver.trial(
            new Caller(7L, null),
            "inner",
            draft("inner", "  - {id: goal}\n")
                .replace("max-turns: 2\n", "max-turns: 2\norchestrations: [outer]\n"));

    assertNotNull(trial.refusal(), "the draft is in the cycle");
    assertTrue(trial.newlyRefused().containsKey("outer"), trial.newlyRefused().toString());
  }

  @Test
  void a_draft_named_like_a_boot_definition_shadows_it(@TempDir Path data) throws Exception {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationDefinition shipped = bootDefinition(data.resolve("boot"), "deep_research", "boot");
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of("deep_research", shipped), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    OrchestrationResolver.Trial trial =
        resolver.trial(
            new Caller(7L, null), "deep_research", draft("deep_research", "  - {id: goal}\n"));

    assertNull(trial.replaces());
    assertEquals(shipped, trial.shadows());
  }

  @Test
  void a_caller_with_no_project_cannot_trial(@TempDir Path data) {
    DataLayout layout = new DataLayout(data).initialise();
    OrchestrationResolver resolver =
        resolver(
            layout,
            new OrchestrationRegistry.Loaded(Map.of(), Map.of()),
            new FakeFiles(),
            new AtomicReference<>(new AgentRegistry(Map.of())));

    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                resolver.trial(
                    new Caller(null, null), "triage", draft("triage", "  - {id: goal}\n")));
    assertEquals(
        "This run is in no project, so there is no project tier to install into.",
        refused.getMessage());
  }
}
