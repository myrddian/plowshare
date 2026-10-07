package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.api.AgentRows;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.data.DataLayout;
import io.aeyer.plowshare.server.files.SessionChannel;
import io.aeyer.plowshare.server.requests.RequestedAgent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Alias selection is deterministic, retains concrete identity, and cannot widen a grant. */
class AgentAliasTest {
  private static final Set<String> TOOLS = Set.of("run", "agent_run");

  private static void write(Path directory, String name, String extra) throws Exception {
    Files.writeString(
        directory.resolve(name + ".md"),
        """
        ---
        name: %s
        description: Changes code
        model: reasoning
        exported: true
        max-turns: 20
        max-model-calls: 20
        %s
        ---
        Prompt for %s.
        """
            .formatted(name, extra, name));
  }

  private static AgentRegistry registry(Path directory, String profile) {
    return new AgentRegistry(AgentRegistry.read(directory, TOOLS, Set.of()), model -> profile);
  }

  @Test
  void exact_profile_wins_over_default_and_concrete_name_stays_direct(@TempDir Path directory)
      throws Exception {
    write(directory, "coder", "alias: coder");
    write(directory, "coder_minimal", "alias: coder\nguidance: minimal");
    write(directory, "coder_guided", "alias: coder\nguidance: guided");
    var registry = registry(directory, "minimal");
    assertEquals("coder_minimal", registry.get("coder").name());
    assertEquals("Prompt for coder_minimal.", registry.get("coder").prompt());
    assertEquals("coder_guided", registry.get("coder_guided").name());
    assertEquals("coder", registry(directory, "standard").get("coder").name());
    assertEquals("coder", registry(directory, null).get("coder").name());
  }

  @Test
  void without_a_default_the_least_guided_variant_wins(@TempDir Path directory) throws Exception {
    write(directory, "z_minimal", "alias: coder\nguidance: minimal");
    write(directory, "a_guided", "alias: coder\nguidance: guided");
    write(directory, "b_standard", "alias: coder\nguidance: standard");
    assertEquals("z_minimal", registry(directory, "custom").get("coder").name());
    assertEquals("z_minimal", registry(directory, null).get("coder").name());
    assertEquals("b_standard", registry(directory, "standard").get("coder").name());
    assertEquals("a_guided", registry(directory, "guided").get("coder").name());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "guidance: minimal",
        "alias: ''",
        "alias: null",
        "alias: [coder]",
        "alias: '../coder'",
        "alias: ' coder'",
        "alias: coder\nguidance: true",
        "alias: coder\nguidance: null",
        "alias: coder\nguidance: custom",
        "alias: coder\nbot: true"
      })
  void invalid_selection_metadata_is_refused(String extra, @TempDir Path directory)
      throws Exception {
    write(directory, "variant", extra);
    assertThrows(IllegalStateException.class, () -> AgentRegistry.load(directory, TOOLS));
    assertTrue(AgentRegistry.read(directory, TOOLS, Set.of()).disabled().containsKey("variant"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "guidance: minimal\n"})
  void duplicate_guidance_disables_the_whole_family(String level, @TempDir Path directory)
      throws Exception {
    write(directory, "one", "alias: coder\n" + level);
    write(directory, "two", "alias: coder\n" + level);
    var loaded = AgentRegistry.read(directory, TOOLS, Set.of());
    assertEquals(Set.of("one", "two"), loaded.disabled().keySet());
    assertTrue(loaded.enabled().isEmpty());
    assertThrows(IllegalStateException.class, () -> AgentRegistry.load(directory, TOOLS));
  }

  @Test
  void aliases_cannot_collide_with_unrelated_concrete_names(@TempDir Path directory)
      throws Exception {
    write(directory, "coder", "");
    write(directory, "variant", "alias: coder");
    var loaded = AgentRegistry.read(directory, TOOLS, Set.of());
    assertEquals(Set.of("variant"), loaded.disabled().keySet());
    assertEquals(Set.of("coder"), loaded.enabled().keySet());
  }

  @Test
  void variants_must_share_the_model_binding(@TempDir Path directory) throws Exception {
    write(directory, "one", "alias: coder");
    write(directory, "two", "alias: coder\nguidance: minimal");
    Path second = directory.resolve("two.md");
    Files.writeString(second, Files.readString(second).replace("model: reasoning", "model: fast"));
    assertEquals(
        Set.of("one", "two"), AgentRegistry.read(directory, TOOLS, Set.of()).disabled().keySet());
  }

  @Test
  void every_variant_must_fit_the_callers_workspace_grants(@TempDir Path directory)
      throws Exception {
    write(directory, "caller", "tools: [agent_run]\ncalls: [coder]\nscopes: [workspace:read]");
    write(directory, "light", "alias: coder\nguidance: minimal\nscopes: [workspace:read]");
    write(directory, "heavy", "alias: coder\nguidance: guided\nscopes: [workspace:write]");
    var loaded = AgentRegistry.read(directory, TOOLS, Set.of());
    assertEquals(List.of(), loaded.enabled().get("caller").calls());
    assertTrue(loaded.withheldEdges().containsKey("caller -> coder"));
  }

  @Test
  void every_variant_must_consent_to_delegation(@TempDir Path directory) throws Exception {
    write(directory, "caller", "tools: [agent_run]\ncalls: [coder]");
    write(directory, "light", "alias: coder\nguidance: minimal");
    write(directory, "heavy", "alias: coder\nguidance: guided\ndelegable: false");
    assertTrue(
        AgentRegistry.read(directory, TOOLS, Set.of())
            .withheldEdges()
            .containsKey("caller -> coder"));
  }

  @Test
  void cycles_through_an_alias_are_refused(@TempDir Path directory) throws Exception {
    write(directory, "caller", "tools: [agent_run]\ncalls: [coder]");
    write(directory, "variant", "alias: coder\ntools: [agent_run]\ncalls: [caller]");
    assertThrows(IllegalStateException.class, () -> AgentRegistry.load(directory, TOOLS));
    assertEquals(
        Set.of("caller", "variant"),
        AgentRegistry.read(directory, TOOLS, Set.of()).disabled().keySet());
  }

  @Test
  void copies_and_merged_tiers_retain_selection(@TempDir Path directory) throws Exception {
    write(directory, "default", "alias: coder");
    write(directory, "light", "alias: coder\nguidance: minimal");
    var registry = registry(directory, "minimal");
    var original = registry.get("light");
    var copy =
        original
            .withPrompt("Updated.")
            .withTools(List.of("run"))
            .withCalls(List.of())
            .withSkills(List.of())
            .withScopes(List.of())
            .withCaps(10, 10)
            .sampling(original.sampling())
            .fallback(original.fallback());
    assertEquals("coder", copy.alias());
    assertEquals("minimal", copy.guidance());
    var merged = new java.util.HashMap<>(registry.byName());
    merged.put("light", copy);
    assertEquals("Updated.", registry.replacing(merged).get("coder").prompt());
    assertEquals("Prompt for light.", original.prompt());
  }

  @Test
  void roster_keeps_the_alias_address_and_shows_its_selection(@TempDir Path directory)
      throws Exception {
    write(directory, "light", "alias: coder\nguidance: minimal\ntools: [run]");
    var registry = registry(directory, "minimal");
    var callers = mock(Callers.class);
    when(callers.withheldFrom(registry, "light")).thenReturn(List.of());
    var rows = AgentRows.of(registry, callers, Optional.empty());
    var alias = rows.stream().filter(row -> row.name().equals("coder")).findFirst().orElseThrow();
    assertTrue(alias.description().contains("resolves to light"));
    assertEquals(List.of("run"), alias.tools());
    assertEquals(Set.of("coder", "light"), registry.exportedNames());
  }

  @Test
  void alias_does_not_bypass_exported_policy(@TempDir Path directory) throws Exception {
    write(directory, "light", "alias: coder\nguidance: minimal");
    Path source = directory.resolve("light.md");
    Files.writeString(
        source, Files.readString(source).replace("exported: true", "exported: false"));
    var registry = registry(directory, "minimal");
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () -> RequestedAgent.toRun(registry, registry::exportedNames, "coder"));
    assertEquals(Set.of(), registry.exportedNames());
  }

  @Test
  void submitted_runs_pin_and_return_the_concrete_definition(@TempDir Path directory)
      throws Exception {
    write(directory, "light", "alias: coder\nguidance: minimal");
    var registry = registry(directory, "minimal");
    var projects = mock(ProjectStore.class);
    var turns = mock(Turn.class);
    var resolver =
        new DefinitionResolver(
            registry,
            DataLayout.NONE,
            id -> false,
            TOOLS,
            Set.of(),
            mock(SessionChannel.class),
            session -> false,
            DefinitionChecks.NONE);
    var callers = new Callers(resolver, projects, turns, mock(CallerAccess.class));
    var jobs = mock(JobStore.class);
    when(jobs.submit(any(), anyString(), any(), isNull(), any(), anyList(), eq(true), isNull()))
        .thenReturn("job_test");
    var runs = new Runs(callers, jobs, turns);
    var started =
        runs.start(
            new Runs.Ask("coder", "Make the change", null, null, null, null, null, List.of()),
            (definition, home, images) -> List.of());
    assertEquals("light", started.agent());
    assertEquals("job_test", started.id());
    verify(jobs)
        .submit(
            eq(registry.get("light")),
            eq("Make the change"),
            any(),
            isNull(),
            any(),
            anyList(),
            eq(true),
            isNull());
  }

  @Test
  void delegation_uses_the_selected_identity_without_widening_the_allowlist(@TempDir Path directory)
      throws Exception {
    write(directory, "caller", "tools: [agent_run]\ncalls: [coder]");
    write(directory, "light", "alias: coder\nguidance: minimal");
    var registry = registry(directory, "minimal");
    var runtime = mock(JobRuntime.class);
    var activity = new ToldActivity();
    when(runtime.activity()).thenReturn(activity);
    var home = io.aeyer.plowshare.protocol.Home.of("project");
    var budget = Budget.of(10);
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "Done", 1, 1, "");
    when(runtime.run(
            eq(registry.get("light")),
            eq("change"),
            eq(home),
            same(budget),
            any(),
            isNull(),
            same(JobWatch.UNWATCHED),
            any(),
            any(TurnCap.class),
            eq(List.of())))
        .thenReturn(outcome);
    var tool =
        new AgentRunTool(
            registry,
            runtime,
            registry.get("caller"),
            budget,
            () -> false,
            null,
            Transcript.NONE,
            List.of(),
            io.aeyer.plowshare.server.images.ImageStore.NONE);
    assertTrue(tool.run("{\"agent\":\"coder\",\"task\":\"change\"}", home).contains("Done"));
    assertTrue(activity.told().contains("delegated caller light change"));
    clearInvocations(runtime);
    tool.run("{\"agent\":\"light\",\"task\":\"change\"}", home);
    verifyNoInteractions(runtime);
  }

  @Test
  void runtime_refuses_a_variant_that_exceeds_a_different_tiers_caller(@TempDir Path directory)
      throws Exception {
    write(directory, "light", "alias: coder\nguidance: minimal\nscopes: [workspace:write]");
    var registry = registry(directory, "minimal");
    var caller =
        new AgentDefinition(
            "caller",
            "Calls coder",
            "reasoning",
            List.of("agent_run"),
            List.of("coder"),
            List.of(io.aeyer.plowshare.server.files.Grant.parse("workspace:read")),
            10,
            10,
            "Do the task",
            false,
            true);
    var runtime = mock(JobRuntime.class);
    var tool =
        new AgentRunTool(
            registry,
            runtime,
            caller,
            Budget.of(10),
            () -> false,
            null,
            Transcript.NONE,
            List.of(),
            io.aeyer.plowshare.server.images.ImageStore.NONE);
    assertTrue(
        tool.run(
                "{\"agent\":\"coder\",\"task\":\"change\"}",
                io.aeyer.plowshare.protocol.Home.of("project"))
            .contains("workspace grants exceed"));
    verifyNoInteractions(runtime);
  }

  @Test
  void approval_continuations_do_not_reselect_a_default_whose_name_is_the_alias(
      @TempDir Path directory) throws Exception {
    write(directory, "coder", "alias: coder");
    write(directory, "light", "alias: coder\nguidance: minimal");
    var profile = new java.util.concurrent.atomic.AtomicReference<String>("standard");
    var registry =
        new AgentRegistry(AgentRegistry.read(directory, TOOLS, Set.of()), model -> profile.get());
    assertEquals("coder", RequestedAgent.toRun(registry, registry::exportedNames, "coder").name());
    profile.set("minimal");
    assertEquals("light", RequestedAgent.toRun(registry, registry::exportedNames, "coder").name());
    assertEquals(
        "coder", RequestedAgent.toContinue(registry, registry::exportedNames, "coder").name());

    var turns = mock(Turn.class);
    when(turns.homeOf("conversation")).thenReturn(io.aeyer.plowshare.protocol.Home.global());
    var resolver =
        new DefinitionResolver(
            registry,
            DataLayout.NONE,
            id -> false,
            TOOLS,
            Set.of(),
            mock(SessionChannel.class),
            session -> false,
            DefinitionChecks.NONE);
    var callers = new Callers(resolver, mock(ProjectStore.class), turns, mock(CallerAccess.class));
    var runs = new Runs(callers, mock(JobStore.class), turns);
    var selected = registry.findConcrete("coder").orElseThrow();
    when(turns.speakToApprovedRun(eq("conversation"), eq(selected), eq("Approved"), any(), any()))
        .thenReturn("continued");
    assertEquals(
        "coder", runs.continueApproved("conversation", "coder", "Approved", outcome -> {}).agent());
    verify(turns)
        .speakToApprovedRun(eq("conversation"), eq(selected), eq("Approved"), any(), any());
  }
}
