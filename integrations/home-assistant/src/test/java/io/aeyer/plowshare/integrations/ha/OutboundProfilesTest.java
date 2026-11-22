package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Provider;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.sdk.RelayTools;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real host, manifest and definition parsers; no server, model fleet or device is started. */
class OutboundProfilesTest {
  private static final Set<String> READ_TOOLS =
      Set.of("ha_read_search", "ha_read_describe", "ha_read_read", "ha_read_list_actions");
  private static final Set<String> CONTROL_TOOLS =
      Set.of("ha_control_describe_action", "ha_control_execute_action");

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void completeProfilesMatchTheTypedHostAndCurrentApplicationLoaders(
      String profile, @TempDir Path temporary) throws Exception {
    Path root = temporary;
    Path application = copyApplication(profile, root);
    Files.copy(profile(profile).resolve("provider.json"), root.resolve("provider.json"));
    var host = HomeAssistantProviderConfiguration.read(root.resolve("provider.json"));
    String project = "home-assistant-" + profile;
    String agent = agent(profile);
    var boundary =
        WorkspaceApplicationPolicy.parse(
            Files.readString(application.resolve("plowshare.json")), project);
    assertEquals(ApplicationPolicy.Kind.APPLICATION, boundary.kind());
    assertTrue(boundary.accounts().isEmpty());
    assertTrue(boundary.limit("ungranted", Optional.of(ProjectRole.CONTRIBUTOR)).isEmpty());
    validator().validate(project, application);

    var agents = AgentRegistry.of(application.resolve("agents"), Set.of(), Set.of());
    assertTrue(agents.disabled().isEmpty(), agents.disabled().toString());
    assertEquals(Set.of(agent), agents.names());
    assertTrue(agents.get(agent).dynamic());
    assertTrue(agents.get(agent).exported());
    assertFalse(agents.get(agent).delegable());
    assertTrue(agents.get(agent).tools().isEmpty());
    assertTrue(agents.get(agent).scopes().isEmpty());
    assertTrue(agents.get(agent).orchestrations().isEmpty());

    var policy = ApplicationToolScopes.read(application);
    var settings = ApplicationServerSettings.read(project, application);
    var providers = new ArrayList<Provider>();
    providers.add(host.read());
    host.control().ifPresent(control -> providers.add(control.provider()));
    assertEquals(profile.equals("control") ? 2 : 1, providers.size());
    assertEquals(providers.size(), settings.providers().size());
    assertTrue(settings.tools().isEmpty());
    assertTrue(settings.ports().isEmpty());
    assertTrue(settings.worker().isEmpty());
    assertTrue(host.homeAssistant().subscriptions().isEmpty());
    assertEquals(profile.equals("control"), host.control().isPresent());
    assertEquals(profile.equals("control"), !host.homeAssistant().actions().isEmpty());
    Map<String, ApplicationServerSettings.Provider> authorities =
        settings.providers().stream()
            .collect(
                Collectors.toMap(
                    ApplicationServerSettings.Provider::provider, Function.identity()));
    for (Provider provider : providers) {
      assertEquals(project, provider.project());
      assertEquals(policy.executionAccount(), provider.account());
      var authority = authorities.get(provider.provider());
      assertEquals(provider.account(), authority.account());
      assertEquals(provider.prefix(), authority.prefix());
      assertEquals(provider.leaseSeconds(), authority.leaseSeconds());
    }
    assertTrue(policy.executionAccount().startsWith("REPLACE_WITH_"));
    assertFalse(Files.exists(root.resolve("private")), "offline parsing must not create journals");

    Binding binding = new Binding(host.binding(), "profile-test-credential-generation");
    var read =
        new HomeAssistantReadTools(
            binding,
            host.read().prefix(),
            mock(ResourceDiscovery.class),
            mock(ResourceReader.class),
            host.timing().requestTimeout());
    assertEquals(READ_TOOLS, names(read));
    if (host.control().isPresent()) {
      var control = host.control().orElseThrow();
      assertEquals(host.read().account(), control.provider().account());
      assertEquals(host.read().tokenEnv(), control.provider().tokenEnv());
      assertFalse(host.read().journal().equals(control.provider().journal()));
      assertFalse(host.read().journal().startsWith(control.provider().journal()));
      assertFalse(control.provider().journal().startsWith(host.read().journal()));
      assertEquals(List.of("lamp_on"), control.allowedActions());
      var actions =
          new HomeAssistantActionTools(
              binding,
              provider(host, control.provider()),
              provider(host, host.read()),
              mock(ResourceDiscovery.class),
              mock(ActionOfferValidator.class),
              new HomeAssistantActionPolicy(
                  binding, Map.of("lamp_on", host.homeAssistant().actions().get("lamp_on"))),
              mock(ActionExecutor.class));
      assertEquals(CONTROL_TOOLS, names(actions));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void exactScopeAssignmentsDoNotGrantOtherPrincipalsAgentsProvidersOrFutureTools(String profile) {
    var policy = ApplicationToolScopes.read(profile(profile).resolve("application"));
    String account = policy.executionAccount();
    String agent = agent(profile);
    assertEquals(READ_TOOLS, Set.copyOf(policy.toolScopes().getFirst().grants()));
    for (String tool : READ_TOOLS) {
      assertTrue(policy.permits(account, agent, "ha-read", tool));
      assertFalse(policy.permits("another-principal", agent, "ha-read", tool));
      assertFalse(policy.permits(account, "unassigned_agent", "ha-read", tool));
      assertFalse(policy.permits(account, agent, "another-provider", tool));
      assertFalse(policy.permits(account, agent, "ha-control", tool));
    }
    for (String tool : CONTROL_TOOLS) {
      assertEquals(profile.equals("control"), policy.permits(account, agent, "ha-control", tool));
      assertFalse(policy.permits(account, agent, "ha-read", tool));
      assertFalse(policy.permits("another-principal", agent, "ha-control", tool));
      assertFalse(policy.permits(account, "unassigned_agent", "ha-control", tool));
    }
    assertFalse(policy.permits(account, agent, "ha-read", "ha_read_future_tool"));
    assertFalse(policy.permits(account, agent, "ha-control", "ha_control_future_tool"));
    assertFalse(policy.permits(account, agent, "ha-read", "relay_publish"));
    assertTrue(policy.toolScopes().stream().noneMatch(scope -> scope.grants().contains("*")));
    if (profile.equals("control"))
      assertEquals(CONTROL_TOOLS, Set.copyOf(policy.toolScopes().get(1).grants()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void missingAssignmentsConferNoDynamicToolAuthority(String profile) throws Exception {
    Path manifest = profile(profile).resolve("application/plowshare.json");
    // Mutate fixture bytes only at the manifest boundary, then use its actual policy parser.
    ObjectNode body = (ObjectNode) Json.MAPPER.readTree(Files.readString(manifest));
    body.remove("toolGrants");
    var policy = ApplicationToolScopes.parse(Json.MAPPER.writeValueAsString(body));
    for (String tool : READ_TOOLS)
      assertFalse(policy.permits(policy.executionAccount(), agent(profile), "ha-read", tool));
    for (String tool : CONTROL_TOOLS)
      assertFalse(policy.permits(policy.executionAccount(), agent(profile), "ha-control", tool));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void scopeAssignmentRequiresTheNamedLocalAgentToRemainDynamic(
      String profile, @TempDir Path temporary) throws Exception {
    Path application = copyApplication(profile, temporary);
    Path definition = application.resolve("agents").resolve(agent(profile) + ".md");
    Files.writeString(
        definition, Files.readString(definition).replace("dynamic: true", "dynamic: false"));
    assertThrows(
        CallerFault.class, () -> validator().validate("home-assistant-" + profile, application));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void assigningAMissingAgentIsRejected(String profile, @TempDir Path temporary) throws Exception {
    Path application = copyApplication(profile, temporary);
    Files.delete(application.resolve("agents").resolve(agent(profile) + ".md"));
    assertThrows(
        CallerFault.class, () -> validator().validate("home-assistant-" + profile, application));
  }

  @ParameterizedTest
  @ValueSource(strings = {"readonly", "control"})
  void aProfileCannotBeLoadedAsAnotherProject(String profile) throws Exception {
    Path manifest = profile(profile).resolve("application/plowshare.json");
    String text = Files.readString(manifest);
    assertThrows(
        IllegalArgumentException.class,
        () -> WorkspaceApplicationPolicy.parse(text, "another-project"));
  }

  private static Path profile(String name) {
    return Path.of(System.getProperty("integration.examples")).resolve("outbound").resolve(name);
  }

  private static String agent(String profile) {
    return profile.equals("readonly") ? "house_reader" : "house_controller";
  }

  private static Set<String> names(HomeAssistantToolHandlers handlers) {
    return handlers.declarations().stream()
        .map(RelayTools.Declaration::name)
        .collect(Collectors.toSet());
  }

  private static HomeAssistantToolProvider.Configuration provider(
      HomeAssistantProviderConfiguration host, Provider provider) {
    return new HomeAssistantToolProvider.Configuration(
        new RelayTools.Binding(provider.project(), provider.provider(), provider.account()),
        provider.prefix(),
        provider.leaseSeconds(),
        host.timing().requestTimeout(),
        host.timing().cleanupTimeout());
  }

  private static RuntimeApplicationPackageValidator validator() {
    // Fleet/model availability is deliberately outside this offline template validation.
    return new RuntimeApplicationPackageValidator(
        new AgentRegistry(Map.of()),
        Set.of(),
        DefinitionChecks.NONE,
        mock(SwarmScheduler.Pools.class),
        mock(HookEngine.class),
        mock(RelayRouteProgram.class));
  }

  private static Path copyApplication(String profile, Path temporary) throws IOException {
    Path source = profile(profile).resolve("application");
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
