package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.agents.*;
import io.aeyer.plowshare.server.archive.*;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.relay.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No database: live catalogue, scoped authority and per-call failure behavior at their seams. */
class ApplicationToolRegistryTest {
  @TempDir Path root;
  final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  final ProjectMembers members = mock(ProjectMembers.class);
  final ProjectNames names = mock(ProjectNames.class);
  final RelayLogRepository logs = mock(RelayLogRepository.class);
  final RelayToolInvocations invocations = mock(RelayToolInvocations.class);
  final AtomicBoolean grant = new AtomicBoolean(true);
  final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-10-09T00:00:00Z"));
  final AtomicReference<RelayLogRepository.Head> head =
      new AtomicReference<>(new RelayLogRepository.Head(0, Optional.empty()));
  final UsageAttribution owner =
      UsageAttribution.project("provider", "1", UsageAttribution.Operation.AGENT_CHAT);
  ApplicationToolRegistry registry;
  static final String DECLARATION =
      """
      {"name":"network_scope","description":"Scope","parameters":[],"timeoutSeconds":30}
      """;

  @BeforeEach
  void setup() throws Exception {
    Files.createDirectory(root.resolve("server"));
    Files.writeString(
        root.resolve("server/tools.json"),
        """
        {"version":1,"bindings":[{"provider":"scanner","account":"provider","name":"network_scope","description":"Scope","parameters":[],"timeoutSeconds":30}],
         "providers":[{"provider":"scanner","account":"provider","prefix":"network_","leaseSeconds":30}]}
        """);
    Files.writeString(
        root.resolve("server/ports.json"),
        """
        {"version":1,"bindings":[{"topic":"schedule.due","account":"provider","direction":"EGRESS","groups":["collector"]}]}
        """);
    Files.writeString(
        root.resolve("plowshare.json"),
        """
        {"executionAccount":"provider","toolScopes":[{"scope":"scans","provider":"scanner","grants":["*"]}],
         "toolGrants":[{"toolScope":"scans","agent":"coordinator"}]}
        """);
    when(projects.id("fixture")).thenReturn(1L);
    when(projects.id("other")).thenReturn(2L);
    when(names.nameForId(1L)).thenReturn(Optional.of("fixture"));
    when(names.nameForId(2L)).thenReturn(Optional.of("other"));
    when(projects.personalOwner(anyString())).thenReturn(Optional.empty());
    when(members.mayWork(anyString(), anyString())).thenReturn(true);
    when(logs.latest(any())).thenAnswer(call -> head.get());
    registry =
        new ApplicationToolRegistry(
            id -> id == 1L ? Optional.of(root) : Optional.empty(),
            projects,
            members,
            names,
            logs,
            new RelayToolProperties(),
            invocations,
            new ToolGrants() {
              public boolean permits(Long p, String a, String n, String s, String t) {
                return true; // A named grant must never substitute for Application scope policy.
              }

              public boolean acceptsDynamic(Long p, String a, String n, String s) {
                return grant.get();
              }
            },
            () -> Set.of("memory_read"),
            clock::get);
  }

  void catalogue(String declarations) {
    long position = head.get().position() + 1;
    var publication =
        new Relay.Publication(
            new Relay.TopicKey(1L, "tool.scanner.catalog"),
            position,
            clock.get(),
            new Relay.Draft(
                UUID.randomUUID().toString(),
                RelayPort.publisher("provider"),
                clock.get(),
                null,
                null,
                new RelayPayload.Text(
                    "{\"version\":\"plowshare-tool-catalog/1\",\"tools\":["
                        + declarations
                        + "]}")));
    head.set(new RelayLogRepository.Head(position, Optional.of(publication)));
  }

  AgentTool tool() {
    var tool =
        registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").getFirst();
    tool.calledAs("call");
    return tool;
  }

  @Test
  void staged_names_and_runtime_schemas_are_project_local_and_grant_filtered() throws Exception {
    assertEquals(
        Set.of("network_scope"), registry.stagedNames("fixture", root, Set.of("memory_read")));
    assertEquals(Set.of(), registry.names(2L));
    var runtime = new JobRuntime(mock(LlmDispatcher.class), List.of());
    runtime.useScopedTools(registry);
    assertFalse(runtime.knownTools().contains("network_scope"));
    assertTrue(runtime.knownTools(1L).contains("network_scope"));
    Path agents = Files.createDirectory(root.resolve("agents"));
    Files.writeString(
        agents.resolve("coordinator.md"),
        "---\nname: coordinator\ndescription: d\nmodel: m\nmax-turns: 2\nmax-model-calls: 4\ndynamic: true\ntools: []\n---\nRead.");
    var definition = AgentRegistry.load(agents, runtime.knownTools(1L)).get("coordinator");
    assertEquals(
        1, runtime.schemasOfferedTo(definition, Home.of("fixture"), null, "provider").size());
    assertEquals(
        0, runtime.schemasOfferedTo(definition, Home.of("other"), null, "provider").size());
  }

  @Test
  void ownerless_discovery_never_exposes_external_schemas() {
    catalogue(DECLARATION);
    assertTrue(registry.tools(Home.of("fixture"), "coordinator", null, () -> false).isEmpty());
    assertTrue(
        registry.tools(Home.of("fixture"), "coordinator", null, () -> false, null).isEmpty());
  }

  @Test
  void schema_visibility_rechecks_membership_and_personal_owner() {
    catalogue(DECLARATION);
    var previouslyOffered = tool();
    assertEquals(
        1, registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").size());
    when(members.mayWork("fixture", "provider")).thenReturn(false);
    assertTrue(
        registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").isEmpty());
    assertTrue(previouslyOffered.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    when(members.mayWork("fixture", "provider")).thenReturn(true);
    when(projects.personalOwner("fixture")).thenReturn(Optional.of("another-owner"));
    assertTrue(
        registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").isEmpty());
    assertTrue(previouslyOffered.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    when(projects.personalOwner("fixture")).thenReturn(Optional.of("provider"));
    assertEquals(
        1, registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").size());
    verifyNoInteractions(invocations);
  }

  @Test
  void an_application_without_explicit_scopes_cannot_fall_back_to_named_tool_grants()
      throws Exception {
    catalogue(DECLARATION);
    var previouslyOffered = tool();
    for (String manifest : List.of("{}", "{\"executionAccount\":\"provider\"}", "invalid")) {
      Files.writeString(root.resolve("plowshare.json"), manifest);
      assertTrue(
          registry
              .tools(Home.of("fixture"), "coordinator", null, () -> false, "provider")
              .isEmpty());
      assertTrue(previouslyOffered.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    }
    Files.delete(root.resolve("plowshare.json"));
    assertTrue(
        registry.tools(Home.of("fixture"), "coordinator", null, () -> false, "provider").isEmpty());
    assertTrue(previouslyOffered.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    verifyNoInteractions(invocations);
  }

  @Test
  void accounting_identity_must_match_the_durable_project_id() {
    catalogue(DECLARATION);
    var offered = tool();
    for (var rejected :
        List.of(
            UsageAttribution.project("provider", "fixture", UsageAttribution.Operation.AGENT_CHAT),
            UsageAttribution.project("provider", "2", UsageAttribution.Operation.AGENT_CHAT),
            UsageAttribution.global("provider", UsageAttribution.Operation.AGENT_CHAT),
            UsageAttribution.system("1", UsageAttribution.Operation.AGENT_CHAT),
            UsageAttribution.LEGACY)) {
      assertTrue(offered.run("{}", Home.of("fixture"), rejected).startsWith("E_NO_ACCESS"));
    }
    assertTrue(offered.run("{}", Home.of("fixture"), null).startsWith("E_NO_ACCESS"));
    assertTrue(offered.run("{}", null, owner).startsWith("E_NO_ACCESS"));
    when(projects.id("fixture")).thenReturn(null);
    assertTrue(offered.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    verifyNoInteractions(invocations);
  }

  @Test
  void bootstrap_schema_does_not_claim_a_connected_provider() {
    assertTrue(tool().run("{}", Home.of("fixture"), owner).startsWith("E_NO_CONNECTION"));
    verifyNoInteractions(invocations);
  }

  @Test
  void every_call_checks_account_and_agent_permissions() {
    catalogue(DECLARATION);
    var tool = tool();
    when(invocations.invoke(any(), any(), eq(owner), eq("call"), any()))
        .thenReturn(
            new RelayToolInvocations.Outcome(
                UUID.randomUUID(), RelayToolCodec.State.COMPLETED, "scope"));
    assertTrue(tool.run("{}", Home.of("fixture"), owner).contains("COMPLETED"));
    grant.set(false);
    assertTrue(tool.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    grant.set(true);
    when(members.mayWork("fixture", "provider")).thenReturn(false);
    assertTrue(tool.run("{}", Home.of("fixture"), owner).startsWith("E_NO_ACCESS"));
    assertTrue(tool.run("{}", Home.of("other"), owner).startsWith("E_NO_ACCESS"));
    verify(invocations, times(1)).invoke(any(), any(), any(), anyString(), any());
  }

  @Test
  void lease_expiry_schema_changes_and_withdrawal_are_distinct_from_revocation() {
    catalogue(DECLARATION);
    var tool = tool();
    clock.set(clock.get().plusSeconds(31));
    assertTrue(tool.run("{}", Home.of("fixture"), owner).startsWith("E_NO_CONNECTION"));
    catalogue(DECLARATION.replace("Scope", "Changed"));
    assertTrue(tool.run("{}", Home.of("fixture"), owner).startsWith("E_NO_EXEC"));
    catalogue("");
    assertEquals(Set.of(), registry.names(1L));
    assertTrue(tool.run("{}", Home.of("fixture"), owner).startsWith("E_NO_EXEC"));
    head.set(new RelayLogRepository.Head(head.get().position(), Optional.empty()));
    assertEquals(Set.of(), registry.names(1L));
    verifyNoInteractions(invocations);
  }

  @Test
  void unknown_invocations_expose_the_receipt_and_never_submit_a_replacement() {
    catalogue(DECLARATION);
    UUID id = UUID.randomUUID();
    when(invocations.invoke(any(), any(), any(), anyString(), any()))
        .thenReturn(
            new RelayToolInvocations.Outcome(
                id, RelayToolCodec.State.UNKNOWN, "Provider reply unavailable"));
    String result = tool().run("{}", Home.of("fixture"), owner);
    assertTrue(result.startsWith("E_NO_CONNECTION"));
    assertTrue(result.contains(id.toString()));
    assertTrue(result.contains("relay_tool_read"));
    verify(invocations).invoke(any(), any(), any(), eq("call"), any());
  }

  @Test
  void unexpected_failures_have_a_stable_code_without_exposing_private_exceptions() {
    catalogue(DECLARATION);
    when(invocations.invoke(any(), any(), any(), anyString(), any()))
        .thenThrow(new IllegalStateException("private credential fixture"));
    String result = tool().run("{}", Home.of("fixture"), owner);
    assertTrue(result.startsWith("E_GENERAL_TOOL_FAILURE"));
    assertFalse(result.contains("private credential"));
  }

  @Test
  void ports_and_catalogue_authority_cannot_cross_account_project_or_prefix() {
    assertTrue(
        registry.permits(
            "provider",
            "fixture",
            "schedule.due",
            RelayPortProperties.Direction.EGRESS,
            "collector"));
    assertFalse(
        registry.permits(
            "provider", "fixture", "schedule.due", RelayPortProperties.Direction.EGRESS, "other"));
    assertFalse(
        registry.permits(
            "intruder",
            "fixture",
            "tool.scanner.catalog",
            RelayPortProperties.Direction.INGRESS,
            null));
    assertFalse(
        registry.permits(
            "provider",
            "other",
            "tool.scanner.catalog",
            RelayPortProperties.Direction.INGRESS,
            null));
    var valid =
        new RelayPort.Publish(
            UUID.randomUUID().toString(),
            "fixture",
            "tool.scanner.catalog",
            "{\"version\":\"plowshare-tool-catalog/1\",\"tools\":[" + DECLARATION + "]}",
            clock.get(),
            null,
            null,
            null);
    registry.validateResult("provider", valid);
    var invalid =
        new RelayPort.Publish(
            UUID.randomUUID().toString(),
            "fixture",
            "tool.scanner.catalog",
            valid.text().replace("network_scope", "memory_read"),
            clock.get(),
            null,
            null,
            null);
    assertThrows(CallerFault.class, () -> registry.validateResult("provider", invalid));
    assertTrue(
        registry.permits(
            "provider",
            "fixture",
            "tool.scanner.catalog",
            RelayPortProperties.Direction.INGRESS,
            null));
    catalogue(DECLARATION);
    assertTrue(
        registry.permits(
            "provider",
            "fixture",
            "tool.scanner.network_scope.request",
            RelayPortProperties.Direction.EGRESS,
            "tool-provider"));
    assertFalse(
        registry.permits(
            "provider",
            "fixture",
            "tool.scanner.network_scope.request",
            RelayPortProperties.Direction.EGRESS,
            "wrong"));
  }

  @Test
  void application_scope_offers_discovered_tools_only_to_its_execution_account_and_assigned_agent()
      throws Exception {
    Files.writeString(
        root.resolve("server/tools.json"),
        """
        {"version":1,"bindings":[],"providers":[{"provider":"scanner","account":"provider","prefix":"network_","leaseSeconds":30}]}
        """);
    Files.writeString(
        root.resolve("plowshare.json"),
        """
        {"executionAccount":"provider","toolScopes":[{"scope":"scans","provider":"scanner","grants":["*"]}],
         "toolGrants":[{"toolScope":"scans","agent":"coordinator"}]}
        """);
    var dynamic = new AtomicBoolean(true);
    registry =
        new ApplicationToolRegistry(
            id -> id == 1L ? Optional.of(root) : Optional.empty(),
            projects,
            members,
            names,
            logs,
            new RelayToolProperties(),
            invocations,
            new ToolGrants() {
              public boolean permits(Long p, String a, String n, String s, String t) {
                return false;
              }

              public boolean acceptsDynamic(Long p, String a, String n, String s) {
                return dynamic.get();
              }
            },
            () -> Set.of("memory_read"),
            clock::get);
    assertEquals(Set.of(), registry.stagedNames("fixture", root, Set.of("memory_read")));
    Path agents = Files.createDirectory(root.resolve("agents"));
    Files.writeString(
        agents.resolve("coordinator.md"),
        """
        ---
        name: coordinator
        description: d
        model: m
        dynamic: true
        tools: []
        max-turns: 2
        max-model-calls: 4
        ---
        Inspect permitted provider tools.
        """);
    // No external names or schemas are known when the agent loads. The provider scope,
    // not its static tools list, grants visibility and execution after discovery.
    var definition = AgentRegistry.load(agents, Set.of()).get("coordinator");
    assertTrue(definition.tools().isEmpty());
    var runtime = new JobRuntime(mock(LlmDispatcher.class), List.of());
    runtime.useScopedTools(registry);
    assertTrue(runtime.schemasOfferedTo(definition, Home.of("fixture"), "s", "provider").isEmpty());
    catalogue(DECLARATION);
    assertEquals(
        List.of("network_scope"),
        runtime.schemasOfferedTo(definition, Home.of("fixture"), "s", "provider").stream()
            .map(io.aeyer.plowshare.server.llm.dispatch.ToolSchema::name)
            .toList());
    catalogue(DECLARATION + "," + DECLARATION.replace("network_scope", "network_future"));
    assertEquals(
        Set.of("network_scope", "network_future"),
        runtime.schemasOfferedTo(definition, Home.of("fixture"), "s", "provider").stream()
            .map(io.aeyer.plowshare.server.llm.dispatch.ToolSchema::name)
            .collect(java.util.stream.Collectors.toSet()));
    catalogue(DECLARATION);
    assertEquals(
        1, registry.tools(Home.of("fixture"), "coordinator", "s", () -> false, "provider").size());
    assertTrue(
        registry.tools(Home.of("fixture"), "reviewer", "s", () -> false, "provider").isEmpty());
    assertTrue(
        registry.tools(Home.of("fixture"), "coordinator", "s", () -> false, "caller").isEmpty());
    var offered =
        registry.tools(Home.of("fixture"), "coordinator", "s", () -> false, "provider").getFirst();
    offered.calledAs("call");
    var execution =
        UsageAttribution.project("provider", "1", UsageAttribution.Operation.AGENT_CHAT);
    dynamic.set(false);
    assertTrue(runtime.schemasOfferedTo(definition, Home.of("fixture"), "s", "provider").isEmpty());
    assertTrue(offered.run("{}", Home.of("fixture"), execution).startsWith("E_NO_ACCESS"));
    dynamic.set(true);
    Files.writeString(
        root.resolve("plowshare.json"),
        """
        {"executionAccount":"provider","toolScopes":[{"scope":"scans","provider":"scanner","grants":["*"]}],"toolGrants":[]}
        """);
    assertTrue(offered.run("{}", Home.of("fixture"), execution).startsWith("E_NO_ACCESS"));
    verifyNoInteractions(invocations);
  }

  @Test
  void runtime_scopes_discover_without_application_files_and_socket_loss_is_no_connection()
      throws Exception {
    var dynamicGrants =
        new ToolGrants() {
          public boolean permits(Long p, String a, String n, String s, String t) {
            return false;
          }

          public boolean acceptsDynamic(Long p, String a, String n, String s) {
            return true;
          }
        };
    var connected = new AtomicBoolean(true);
    ApplicationResources external = id -> Optional.empty();
    var scopes =
        new SessionToolScopes(
            external,
            projects,
            members,
            dynamicGrants,
            (a, s, c) -> connected.get(),
            () -> Set.of("memory_read"));
    var connection =
        scopes.connect(
            "caller",
            "session",
            "socket",
            new io.aeyer.plowshare.protocol.ToolScopes.Connect(
                "fixture",
                "scans",
                "scanner",
                "network_",
                List.of("*"),
                List.of("coordinator"),
                30));
    registry =
        new ApplicationToolRegistry(
            external,
            projects,
            members,
            names,
            logs,
            new RelayToolProperties(),
            invocations,
            dynamicGrants,
            () -> Set.of("memory_read"),
            clock::get,
            scopes);
    head.set(
        new RelayLogRepository.Head(
            1,
            Optional.of(
                new Relay.Publication(
                    new Relay.TopicKey(1L, "tool." + connection.provider() + ".catalog"),
                    1,
                    clock.get(),
                    new Relay.Draft(
                        UUID.randomUUID().toString(),
                        RelayPort.publisher("caller"),
                        clock.get(),
                        null,
                        null,
                        new RelayPayload.Text(
                            "{\"version\":\"plowshare-tool-catalog/1\",\"tools\":["
                                + DECLARATION
                                + "]}"))))));
    assertTrue(
        registry
            .tools(Home.of("fixture"), "coordinator", "another", () -> false, "caller")
            .isEmpty());
    assertTrue(
        registry
            .tools(Home.of("fixture"), "coordinator", "session", () -> false, "intruder")
            .isEmpty());
    var offered =
        registry
            .tools(Home.of("fixture"), "coordinator", "session", () -> false, "caller")
            .getFirst();
    offered.calledAs("call");
    connected.set(false);
    assertTrue(
        offered
            .run(
                "{}",
                Home.of("fixture"),
                UsageAttribution.project("caller", "1", UsageAttribution.Operation.AGENT_CHAT))
            .startsWith("E_NO_CONNECTION"));
    verifyNoInteractions(invocations);
  }

  @Test
  void source_validation_reserves_harness_names_even_when_they_are_not_boot_tools()
      throws Exception {
    Files.writeString(
        root.resolve("server/tools.json"),
        """
        {"version":1,"bindings":[{"provider":"scanner","account":"provider","name":"board_read","description":"Board","parameters":[],"timeoutSeconds":30}],
         "providers":[{"provider":"scanner","account":"provider","prefix":"board_","leaseSeconds":30}]}
        """);
    assertThrows(
        CallerFault.class, () -> registry.stagedNames("fixture", root, Set.of("memory_read")));
  }

  @Test
  void built_in_collisions_are_refused_before_deployment() {
    assertThrows(
        CallerFault.class, () -> registry.stagedNames("fixture", root, Set.of("network_scope")));
  }
}
