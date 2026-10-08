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
          "network_scope",
          "network_scan",
          "network_scan_status",
          "network_scan_list",
          "network_evidence",
          "network_destinations",
          "relay_tool_read");
  private final Path application = Path.of(System.getProperty("privacy.application"));

  @Test
  void complete_application_passes_the_deployment_resource_validator() throws Exception {
    try (var hooks = new io.aeyer.plowshare.server.hooks.script.HookEngine();
        var relay = new GraalRelayRouteProgram()) {
      new RuntimeApplicationPackageValidator(
              new AgentRegistry(java.util.Map.of()),
              TOOLS,
              DefinitionChecks.NONE,
              new io.aeyer.plowshare.server.swarm.SwarmScheduler.Pools() {
                public java.util.List<String> serving(String specifier) {
                  return java.util.List.of();
                }

                public int slots(String pool) {
                  return 0;
                }

                public java.util.List<String> all() {
                  return java.util.List.of();
                }
              },
              hooks,
              relay)
          .validate("network-privacy-watch", application);
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
    for (var binding : bindings) {
      assertEquals("network-privacy-watch", binding.project());
      assertTrue(coordinator.tools().contains(binding.name()));
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
      String suffix = name.equals("privacy_tick") ? ".js" : ".md";
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
}
