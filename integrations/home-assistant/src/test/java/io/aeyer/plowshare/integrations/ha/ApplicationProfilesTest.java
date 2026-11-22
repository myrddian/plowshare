package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.ApplicationPolicy;
import io.aeyer.plowshare.server.agents.DefinitionChecks;
import io.aeyer.plowshare.server.agents.RuntimeApplicationPackageValidator;
import io.aeyer.plowshare.server.agents.WorkspaceApplicationPolicy;
import io.aeyer.plowshare.server.applications.ApplicationServerSettings;
import io.aeyer.plowshare.server.applications.ApplicationToolScopes;
import io.aeyer.plowshare.server.archive.ProjectRole;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.hooks.script.HookEngine;
import io.aeyer.plowshare.server.relay.RelayRouteProgram;
import io.aeyer.plowshare.server.swarm.SwarmScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual Application policy and definition loaders; no server, database, model or device calls. */
class ApplicationProfilesTest {
  private static final Set<String> READ_TOOLS =
      Set.of("ha_read_search", "ha_read_describe", "ha_read_read", "ha_read_list_actions");
  private static final Set<String> CONTROL_TOOLS =
      Set.of("ha_control_describe_action", "ha_control_execute_action");

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control", "task"})
  void standaloneRootsLoadWithExplicitFiniteCapabilities(String profile) throws Exception {
    Path root = profile(profile);
    String project = "home-assistant-" + profile;
    validator().validate(project, root);
    var boundary =
        WorkspaceApplicationPolicy.parse(Files.readString(root.resolve("plowshare.json")), project);
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertTrue(boundary.accounts().isEmpty());
    assertTrue(boundary.limit("ungranted", Optional.of(ProjectRole.MANAGER)).isEmpty());

    var agents = AgentRegistry.of(root.resolve("agents"), Set.of(), Set.of());
    assertTrue(agents.disabled().isEmpty(), agents.disabled().toString());
    assertEquals(
        switch (profile) {
          case "readonly" -> Set.of("house_reader", "house_task");
          case "control" -> Set.of("house_reader", "house_controller", "house_task");
          default -> Set.of("house_task");
        },
        agents.names());
    for (String name : agents.names()) {
      var agent = agents.get(name);
      assertTrue(agent.exported());
      assertFalse(agent.delegable());
      assertEquals(!name.equals("house_task"), agent.dynamic());
      assertEquals("reasoning", agent.model());
      assertEquals(20, agent.maxTurns());
      assertEquals(40, agent.maxModelCalls());
      assertTrue(agent.tools().isEmpty());
      assertTrue(agent.scopes().isEmpty());
      assertTrue(agent.calls().isEmpty());
      assertTrue(agent.orchestrations().isEmpty());
      assertTrue(agent.skills().isEmpty());
    }
    // The owning manifest parser above validates these fields. These assertions pin
    // intentional operator ceilings so a valid but broader template cannot pass.
    var manifest = Json.MAPPER.readTree(Files.readString(root.resolve("plowshare.json")));
    assertEquals(20, manifest.path("caps").path("steps").intValue());
    assertEquals(40, manifest.path("caps").path("budget").intValue());
    assertFalse(manifest.path("caps").path("autoIncrease").booleanValue());
    assertEquals("off", manifest.path("commands").path("local").path("mode").textValue());
    assertEquals("off", manifest.path("commands").path("server").path("mode").textValue());
    assertFalse(manifest.has("defaultBot"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control", "task"})
  void exactAgentAssignmentsSeparateReadControlAndGeneration(String profile) {
    var policy = ApplicationToolScopes.read(profile(profile));
    String account = policy.executionAccount();
    assertTrue(account.startsWith("REPLACE_WITH_"));
    for (String agent : List.of("house_reader", "house_controller", "house_task", "unassigned")) {
      for (String tool : READ_TOOLS) {
        boolean allowed =
            !profile.equals("task")
                && (agent.equals("house_reader")
                    || profile.equals("control") && agent.equals("house_controller"));
        assertEquals(allowed, policy.permits(account, agent, "ha-read", tool));
        assertFalse(policy.permits("another-principal", agent, "ha-read", tool));
        assertFalse(policy.permits(account, agent, "another-provider", tool));
        assertFalse(policy.permits(account, agent, "ha-control", tool));
      }
      for (String tool : CONTROL_TOOLS) {
        boolean allowed = profile.equals("control") && agent.equals("house_controller");
        assertEquals(allowed, policy.permits(account, agent, "ha-control", tool));
        assertFalse(policy.permits("another-principal", agent, "ha-control", tool));
        assertFalse(policy.permits(account, agent, "ha-read", tool));
      }
      for (String provider : List.of("ha-read", "ha-control")) {
        assertFalse(policy.permits(account, agent, provider, "ha_read_future_tool"));
        assertFalse(policy.permits(account, agent, provider, "ha_control_future_tool"));
        assertFalse(policy.permits(account, agent, provider, "relay_publish"));
        assertFalse(policy.permits(account, agent, provider, "run"));
      }
    }
    assertTrue(policy.toolScopes().stream().noneMatch(scope -> scope.grants().contains("*")));
    assertEquals(
        profile.equals("task") ? 0 : profile.equals("control") ? 2 : 1, policy.toolScopes().size());
    assertEquals(
        profile.equals("task") ? 0 : profile.equals("control") ? 3 : 1, policy.toolGrants().size());
    if (!profile.equals("task"))
      assertEquals(READ_TOOLS, Set.copyOf(policy.toolScopes().getFirst().grants()));
    if (profile.equals("control"))
      assertEquals(CONTROL_TOOLS, Set.copyOf(policy.toolScopes().get(1).grants()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void houseProviderAuthoritiesMatchTheExistingHostProfile(String profile) throws Exception {
    Path root = profile(profile);
    var policy = ApplicationToolScopes.read(root);
    var settings = ApplicationServerSettings.read("home-assistant-" + profile, root);
    var host =
        HomeAssistantProviderConfiguration.read(
            examples().resolve("outbound").resolve(profile).resolve("provider.json"));
    var providers = settings.providers();
    assertEquals(profile.equals("control") ? 2 : 1, providers.size());
    assertTrue(settings.tools().isEmpty());
    assertTrue(settings.ports().isEmpty());
    assertTrue(settings.worker().isEmpty());
    assertEquals(
        policy.toolScopes().stream()
            .map(ApplicationToolScopes.Scope::provider)
            .collect(Collectors.toSet()),
        providers.stream()
            .map(ApplicationServerSettings.Provider::provider)
            .collect(Collectors.toSet()));
    for (var provider : providers) {
      var configured =
          provider.provider().equals("ha-read")
              ? host.read()
              : host.control().orElseThrow().provider();
      assertEquals("home-assistant-" + profile, configured.project());
      assertEquals(policy.executionAccount(), provider.account());
      assertEquals(configured.account(), provider.account());
      assertEquals(configured.provider(), provider.provider());
      assertEquals(configured.prefix(), provider.prefix());
      assertEquals(configured.leaseSeconds(), provider.leaseSeconds());
    }
    if (host.control().isPresent()) {
      var control = host.control().orElseThrow().provider();
      assertEquals(host.read().account(), control.account());
      assertEquals(host.read().tokenEnv(), control.tokenEnv());
    }
  }

  @Test
  void generationNeedsNoProviderOrDynamicOptIn() {
    Path root = profile("task");
    validator().validate("home-assistant-task", root);
    assertFalse(Files.exists(root.resolve("server")));
    var settings = ApplicationServerSettings.read("home-assistant-task", root);
    assertEquals(ApplicationServerSettings.EMPTY, settings);
    var policy = ApplicationToolScopes.read(root);
    assertTrue(policy.toolScopes().isEmpty());
    assertTrue(policy.toolGrants().isEmpty());
    assertFalse(
        AgentRegistry.of(root.resolve("agents"), Set.of(), Set.of()).get("house_task").dynamic());
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void removingAssignmentsDeniesEveryHouseTool(String profile) throws Exception {
    ObjectNode manifest = manifest(profile);
    manifest.remove("toolGrants");
    var policy = ApplicationToolScopes.parse(Json.MAPPER.writeValueAsString(manifest));
    for (String agent : List.of("house_reader", "house_controller", "house_task")) {
      for (String tool : READ_TOOLS)
        assertFalse(policy.permits(policy.executionAccount(), agent, "ha-read", tool));
      for (String tool : CONTROL_TOOLS)
        assertFalse(policy.permits(policy.executionAccount(), agent, "ha-control", tool));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void loaderRejectsAnAssignmentToTheNonDynamicGenerator(String profile, @TempDir Path temporary)
      throws Exception {
    Path root = copyApplication(profile, temporary);
    ObjectNode manifest = manifest(profile);
    manifest
        .withArray("toolGrants")
        .addObject()
        .put("toolScope", "ha-read")
        .put("agent", "house_task");
    Files.writeString(root.resolve("plowshare.json"), Json.MAPPER.writeValueAsString(manifest));
    assertThrows(CallerFault.class, () -> validator().validate("home-assistant-" + profile, root));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void loaderRejectsMissingAndNonDynamicReaders(String profile, @TempDir Path temporary)
      throws Exception {
    Path root = copyApplication(profile, temporary);
    Path definition = root.resolve("agents/house_reader.md");
    String original = Files.readString(definition);
    Files.writeString(definition, original.replace("dynamic: true", "dynamic: false"));
    assertThrows(CallerFault.class, () -> validator().validate("home-assistant-" + profile, root));
    Files.delete(definition);
    assertThrows(CallerFault.class, () -> validator().validate("home-assistant-" + profile, root));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control", "task"})
  void aProfileCannotBeReusedUnderAnotherProjectIdentity(String profile) throws Exception {
    String manifest = Files.readString(profile(profile).resolve("plowshare.json"));
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceApplicationPolicy.parse(manifest, "another-project"));
  }

  private static Path examples() {
    return Path.of(System.getProperty("integration.examples"));
  }

  private static Path profile(String name) {
    return examples().resolve("application/profiles").resolve(name);
  }

  private static ObjectNode manifest(String profile) throws IOException {
    return (ObjectNode)
        Json.MAPPER.readTree(Files.readString(profile(profile).resolve("plowshare.json")));
  }

  private static RuntimeApplicationPackageValidator validator() {
    // Model availability and live grant issuance are intentionally separate from source validation.
    return new RuntimeApplicationPackageValidator(
        new AgentRegistry(Map.of()),
        Set.of(),
        DefinitionChecks.NONE,
        mock(SwarmScheduler.Pools.class),
        mock(HookEngine.class),
        mock(RelayRouteProgram.class));
  }

  private static Path copyApplication(String profile, Path temporary) throws IOException {
    Path source = profile(profile);
    Path target = temporary.resolve("application");
    try (var paths = Files.walk(source)) {
      for (Path path : paths.toList()) {
        Path destination = target.resolve(source.relativize(path));
        if (Files.isDirectory(path)) Files.createDirectories(destination);
        else Files.copy(path, destination);
      }
    }
    return target;
  }
}
