package io.aeyer.plowshare.integrations.privacy;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.events.ScheduleDefinitionCodec;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.relay.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies the deployable Python Application with actual parsers and sandboxes; no database. */
class NetworkPrivacyWatchTest {
  private static final Set<String> TOOLS =
      Set.of(
          "information_read",
          "information_write",
          "memory_recall",
          "memory_read",
          "agent_run",
          "outgoing_peers",
          "outgoing_send",
          "outgoing_read",
          "relay_tool_read");
  private final Path application = Path.of(System.getProperty("privacy.application"));

  private static io.aeyer.plowshare.server.swarm.SwarmScheduler.Pools emptyPools() {
    return new io.aeyer.plowshare.server.swarm.SwarmScheduler.Pools() {
      public java.util.List<String> serving(String specifier) {
        return java.util.List.of();
      }

      public int slots(String pool) {
        return 0;
      }

      public java.util.List<String> all() {
        return java.util.List.of();
      }
    };
  }

  @Test
  void application_scope_loads_without_external_names_or_provider_schemas() throws Exception {
    var settings =
        io.aeyer.plowshare.server.applications.ApplicationServerSettings.read(
            "network-privacy-watch", application);
    assertEquals(0, settings.tools().size());
    assertEquals(4, settings.ports().size());
    assertEquals(1, settings.providers().size());
    var loaded =
        AgentRegistry.read(
            new FilesystemDefinitions(application.resolve("agents")), TOOLS, Set.of());
    assertEquals(java.util.Map.of(), loaded.withheldTools());
    assertEquals(java.util.Map.of(), loaded.disabled());
    var coordinator = loaded.enabled().get("privacy_coordinator");
    assertTrue(coordinator.dynamic());
    assertFalse(coordinator.tools().stream().anyMatch(name -> name.startsWith("network_")));
    var policy = io.aeyer.plowshare.server.applications.ApplicationToolScopes.read(application);
    assertEquals(6, policy.toolScopes().getFirst().grants().size());
    assertTrue(
        policy.permits(
            policy.executionAccount(), "privacy_coordinator", "privacy-scanner", "network_scan"));
    assertFalse(
        policy.permits(
            policy.executionAccount(), "privacy_analyst", "privacy-scanner", "network_scan"));
  }

  @Test
  void complete_application_passes_the_deployment_resource_validator() throws Exception {
    try (var hooks = new io.aeyer.plowshare.server.hooks.script.HookEngine();
        var relay = new GraalRelayRouteProgram()) {
      new RuntimeApplicationPackageValidator(
              new AgentRegistry(java.util.Map.of()),
              TOOLS,
              DefinitionChecks.NONE,
              emptyPools(),
              hooks,
              relay)
          .validate("network-privacy-watch", application);
    }
  }

  @Test
  void owned_source_can_be_admitted_before_its_execution_account_exists(
      @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
    try (var files = Files.walk(application)) {
      for (Path file : files.toList()) {
        Path target = root.resolve(application.relativize(file));
        if (Files.isDirectory(file)) Files.createDirectories(target);
        else Files.copy(file, target);
      }
    }
    var json = new ObjectMapper();
    var manifest =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            json.readTree(Files.readString(root.resolve("plowshare.json")));
    manifest.remove("executionAccount");
    manifest.putObject("service").put("handle", "privacy-worker").put("role", "CONTRIBUTOR");
    String principal = "@service/" + java.util.UUID.randomUUID();
    Files.writeString(
        root.resolve("plowshare.json"),
        io.aeyer.plowshare.server.applications.ApplicationServiceManifest.resolve(
            json.writeValueAsString(manifest), principal));
    var settings =
        io.aeyer.plowshare.server.applications.ApplicationServerSettings.read(
            "network-privacy-watch", root);
    assertEquals(principal, settings.providers().getFirst().account());
    try (var hooks = new io.aeyer.plowshare.server.hooks.script.HookEngine();
        var relay = new GraalRelayRouteProgram()) {
      new RuntimeApplicationPackageValidator(
              new AgentRegistry(java.util.Map.of()),
              TOOLS,
              DefinitionChecks.NONE,
              emptyPools(),
              hooks,
              relay)
          .validate("network-privacy-watch", root);
    }
  }

  @Test
  void exported_native_declarations_load_and_match_the_application_grants() throws Exception {
    var json = new ObjectMapper();
    var source =
        json.readTree(
                Files.readString(
                    application.getParent().resolve("examples/server-tool-bindings.json")))
            .path("plowshare")
            .path("relay")
            .path("tools")
            .path("bindings");
    java.util.List<io.aeyer.plowshare.server.relay.tools.RelayToolDefinition> bindings =
        json.readValue(
            source.toString(),
            json.getTypeFactory()
                .constructCollectionType(
                    java.util.List.class,
                    io.aeyer.plowshare.server.relay.tools.RelayToolDefinition.class));
    var properties = new io.aeyer.plowshare.server.relay.tools.RelayToolProperties();
    properties.setBindings(bindings);
    assertEquals(6, properties.getBindings().size());
    var agents = AgentRegistry.of(application.resolve("agents"), TOOLS, Set.of());
    var coordinator = agents.get("privacy_coordinator");
    var scopes = io.aeyer.plowshare.server.applications.ApplicationToolScopes.read(application);
    for (var binding : bindings) {
      assertEquals("network-privacy-watch", binding.project());
      assertFalse(coordinator.tools().contains(binding.name()));
      assertTrue(
          scopes.permits(
              scopes.executionAccount(), coordinator.name(), binding.provider(), binding.name()));
      assertEquals(binding.name(), binding.schema().name());
    }
    assertTrue(coordinator.tools().contains("relay_tool_read"));
    assertTrue(
        bindings.stream()
            .filter(b -> b.name().equals("network_scan"))
            .findFirst()
            .orElseThrow()
            .parameters()
            .isEmpty());
  }

  @Test
  void manifest_agents_orchestrations_and_paused_schedule_are_valid() throws Exception {
    var policy =
        WorkspaceApplicationPolicy.parse(
            Files.readString(application.resolve("plowshare.json")), "network-privacy-watch");
    assertEquals(ApplicationPolicy.Kind.APPLICATION, policy.kind());
    assertTrue(policy.accounts().isEmpty());
    Path definitions = application;
    var agents = AgentRegistry.of(definitions.resolve("agents"), TOOLS, Set.of());
    assertTrue(agents.disabled().isEmpty(), agents.disabled().toString());
    assertEquals(
        Set.of("privacy_coordinator", "privacy_analyst", "privacy_reviewer"), agents.names());
    for (String name : Set.of("investigate_network", "privacy_tick")) {
      String suffix = ".js";
      Path source = definitions.resolve("orchestrations/" + name + suffix);
      var loaded =
          OrchestrationRegistry.parsePinned(
              name,
              source.toString(),
              Files.readString(source),
              TOOLS,
              OrchestrationDefinition.Tier.PROJECT);
      assertEquals(name, loaded.name());
      assertTrue(agents.get("privacy_coordinator").orchestrations().contains(name));
    }
    var schedule =
        ScheduleDefinitionCodec.read(
            Files.readString(definitions.resolve("schedules/network_scan.json")));
    assertTrue(schedule.paused());
    assertEquals("privacy_tick", schedule.action().name());
    assertEquals("privacy_coordinator", schedule.action().agent());
  }

  @Test
  void scheduled_native_action_uses_no_model_or_external_tool() throws Exception {
    String source = Files.readString(application.resolve("orchestrations/privacy_tick.js"));
    var json = new ObjectMapper();
    for (String status : Set.of("pending", "in_progress", "done")) {
      var input = json.createObjectNode();
      input
          .putArray("todos")
          .addObject()
          .put("stageId", "available")
          .put("id", "fixture-todo")
          .put("status", status);
      var command = ScriptProgram.step(source, input).path("command");
      assertEquals(
          status.equals("done") ? "orchestration_finish" : "todo_write",
          command.path("tool").asText());
    }
    assertTrue(ScriptProgram.manifest(source).path("tools").isEmpty());
    assertTrue(ScriptProgram.manifest(source).path("calls").isEmpty());
  }

  @Test
  void completed_python_publication_starts_the_granted_investigation() throws Exception {
    var pin =
        RelayDeliveries.SourcePin.of(
            "privacy/routes.js", Files.readString(application.resolve("Relay/privacy/routes.js")));
    try (var program = new GraalRelayRouteProgram()) {
      var relay = new RelayRouting.Package("privacy", pin, program.manifest(pin));
      var subscription = relay.manifest().subscriptions().getFirst();
      var input =
          new Relay.Publication(
              new Relay.TopicKey(1, subscription.topic()),
              1,
              Instant.EPOCH,
              new Relay.Draft(
                  "fixture",
                  "sdk:collector",
                  Instant.EPOCH,
                  null,
                  null,
                  new RelayPayload.Text(
                      """
              {"version":1,"scan_id":"00000000-0000-0000-0000-000000000001",
              "revision":"00000000-0000-0000-0000-000000000002","collector":"home-network",
              "mode":"fixture","changes":["Baseline collection"],"issues":[]}
              """)));
      var routes = program.route(relay, subscription, input);
      assertEquals(1, routes.size());
      assertEquals("orchestration.start", routes.getFirst().receiver());
      assertEquals("privacy_coordinator", routes.getFirst().work().agent());
      assertEquals("investigate_network", routes.getFirst().work().definition());
    }
  }

  @Test
  void operator_withheld_investigation_and_malformed_decision_admit_no_effects() throws Exception {
    var pin =
        RelayDeliveries.SourcePin.of(
            "privacy/routes.js", Files.readString(application.resolve("Relay/privacy/routes.js")));
    try (var program = new GraalRelayRouteProgram()) {
      var relay = new RelayRouting.Package("privacy", pin, program.manifest(pin));
      var subscription = relay.manifest().subscriptions().getFirst();
      for (String requested : java.util.List.of("false", "\"false\"")) {
        String completion =
            """
          {"version":1,"scan_id":"00000000-0000-0000-0000-000000000001",
          "revision":"00000000-0000-0000-0000-000000000002","collector":"home-network",
          "mode":"fixture","changes":["Baseline collection"],"issues":[],
          "investigation":{"requested":%s,"reason":"operator policy","decided_at":"2026-01-01T00:00:00Z"}}
          """
                .formatted(requested);
        var input =
            new Relay.Publication(
                new Relay.TopicKey(1, subscription.topic()),
                1,
                Instant.EPOCH,
                new Relay.Draft(
                    "fixture",
                    "sdk:collector",
                    Instant.EPOCH,
                    null,
                    null,
                    new RelayPayload.Text(completion)));
        if (requested.equals("false")) {
          assertTrue(program.route(relay, subscription, input).isEmpty());
        } else {
          assertTrue(program.route(relay, subscription, input).isEmpty());
        }
      }
    }
  }

  @Test
  void explicit_decisions_and_typed_baselines_do_not_depend_on_changes() throws Exception {
    String base =
        """
        {"version":1,"scan_id":"00000000-0000-0000-0000-000000000001",
        "revision":"00000000-0000-0000-0000-000000000002","collector":"home-network",
        "mode":"fixture","changes":[],"issues":[]%s}
        """;
    var pin =
        RelayDeliveries.SourcePin.of(
            "privacy/routes.js", Files.readString(application.resolve("Relay/privacy/routes.js")));
    try (var program = new GraalRelayRouteProgram()) {
      var relay = new RelayRouting.Package("privacy", pin, program.manifest(pin));
      var subscription = relay.manifest().subscriptions().getFirst();
      for (String extension :
          java.util.List.of(
              ",\"baseline\":true",
              ",\"investigation\":{\"requested\":true,\"reason\":\"operator policy\",\"decided_at\":\"2026-01-01T00:00:00Z\"}")) {
        var input = completion(subscription.topic(), base.formatted(extension));
        var selected = program.route(relay, subscription, input);
        assertEquals(1, selected.size());
        assertEquals("orchestration.start", selected.getFirst().receiver());
      }
      assertTrue(
          program
              .route(relay, subscription, completion(subscription.topic(), base.formatted("")))
              .isEmpty());
      assertTrue(
          program
              .route(
                  relay,
                  subscription,
                  completion(
                      subscription.topic(),
                      base.formatted(
                          ",\"baseline\":true,\"investigation\":{\"requested\":false,\"reason\":\"disabled\",\"decided_at\":\"2026-01-01T00:00:00Z\"}")))
              .isEmpty());
    }
  }

  @Test
  void malformed_completion_does_not_throw_or_prevent_a_following_valid_record() throws Exception {
    var pin =
        RelayDeliveries.SourcePin.of(
            "privacy/routes.js", Files.readString(application.resolve("Relay/privacy/routes.js")));
    String valid =
        """
        {"version":1,"scan_id":"00000000-0000-0000-0000-000000000001",
        "revision":"00000000-0000-0000-0000-000000000002","collector":"home-network",
        "mode":"fixture","changes":[],"issues":[],"baseline":true}
        """;
    try (var program = new GraalRelayRouteProgram()) {
      var relay = new RelayRouting.Package("privacy", pin, program.manifest(pin));
      var subscription = relay.manifest().subscriptions().getFirst();
      for (String malformed :
          java.util.List.of(
              "{",
              "null",
              "[]",
              "{}",
              valid.replace("\"baseline\":true", "\"baseline\":1"),
              valid.replace("00000000-0000-0000-0000-000000000001", "invalid-scan-id"),
              valid.replace("\"issues\":[]", "\"issues\":[null]"))) {
        assertTrue(
            program
                .route(relay, subscription, completion(subscription.topic(), malformed))
                .isEmpty());
        assertEquals(
            1, program.route(relay, subscription, completion(subscription.topic(), valid)).size());
      }
    }
  }

  private static Relay.Publication completion(String topic, String text) {
    return new Relay.Publication(
        new Relay.TopicKey(1, topic),
        1,
        Instant.EPOCH,
        new Relay.Draft(
            "fixture", "sdk:collector", Instant.EPOCH, null, null, new RelayPayload.Text(text)));
  }
}
