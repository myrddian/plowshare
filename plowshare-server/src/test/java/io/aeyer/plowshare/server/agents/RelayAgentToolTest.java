package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.relay.tools.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayAgentToolTest {
  @TempDir Path directory;
  private final RelayToolDefinition binding =
      new RelayToolDefinition(
          "fixture",
          "scanner",
          "provider",
          "network_scope",
          "Read the configured scope",
          List.of(),
          30);

  @Test
  void registered_names_are_offered_only_when_the_definition_grants_them() throws Exception {
    var invocations = mock(RelayToolInvocations.class);
    var runtime =
        new JobRuntime(
            mock(LlmDispatcher.class), List.of(new RelayAgentTool(List.of(binding), invocations)));
    Files.writeString(
        directory.resolve("granted.md"),
        "---\nname: granted\ndescription: granted tools\nmodel: reasoning\nmax-turns: 4\nmax-model-calls: 8\ntools: [network_scope]\n---\nRead scope.");
    Files.writeString(
        directory.resolve("ungranted.md"),
        "---\nname: ungranted\ndescription: no tools\nmodel: reasoning\nmax-turns: 4\nmax-model-calls: 8\ntools: []\n---\nAnswer.");
    var agents = AgentRegistry.load(directory, runtime.knownTools());
    assertTrue(runtime.knownTools().contains("network_scope"));
    assertTrue(runtime.schemasOfferedTo(agents.get("granted")).contains(binding.schema()));
    assertFalse(runtime.schemasOfferedTo(agents.get("ungranted")).contains(binding.schema()));
    verifyNoInteractions(invocations);
  }

  @Test
  void per_run_calls_do_not_share_identity_and_never_accept_model_routing() {
    var invocations = mock(RelayToolInvocations.class);
    when(invocations.invoke(any(), any(), any(), anyString(), any()))
        .thenReturn(
            new RelayToolInvocations.Outcome(
                UUID.randomUUID(), RelayToolCodec.State.COMPLETED, "scope"));
    var shared = new RelayAgentTool(List.of(binding), invocations);
    var first = shared.forRun(() -> false);
    var second = shared.forRun(() -> true);
    first.calledAs("first");
    second.calledAs("second");
    var owner =
        UsageAttribution.project("caller", "fixture", UsageAttribution.Operation.AGENT_CHAT);
    assertTrue(first.run("{}", Home.of("fixture"), owner).contains("COMPLETED"));
    assertTrue(second.run("{}", Home.of("fixture"), owner).contains("COMPLETED"));
    verify(invocations)
        .invoke(
            eq(binding),
            any(),
            eq(owner),
            eq("first"),
            argThat(cancelled -> !cancelled.getAsBoolean()));
    verify(invocations)
        .invoke(
            eq(binding),
            any(),
            eq(owner),
            eq("second"),
            argThat(cancelled -> cancelled.getAsBoolean()));
    assertTrue(first.run("{}", Home.of("other"), owner).contains("no provider binding"));
  }

  @Test
  void duplicate_project_bindings_schema_conflicts_and_bad_topics_fail_configuration() {
    var properties = new RelayToolProperties();
    assertThrows(
        IllegalArgumentException.class, () -> properties.setBindings(List.of(binding, binding)));
    var differentSchema =
        new RelayToolDefinition(
            "other", "scanner", "provider", binding.name(), "Changed schema", List.of(), 30);
    assertThrows(
        IllegalArgumentException.class,
        () -> properties.setBindings(List.of(binding, differentSchema)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayToolDefinition(
                "fixture", "bad--provider", "provider", "network__scope", "scope", List.of(), 30));
  }
}
