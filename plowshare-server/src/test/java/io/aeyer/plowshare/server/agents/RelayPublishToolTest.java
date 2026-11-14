package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.server.llm.accounting.UsageLineage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.relay.RelayPublishing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayPublishToolTest {
  @TempDir Path directory;
  private final RelayPublishing publishing = mock(RelayPublishing.class);
  private final UsageAttribution owner =
      UsageAttribution.project("alice", "7", UsageAttribution.Operation.AGENT_CHAT)
          .withExecution(
              UsageLineage.root("conversation"),
              UsageLineage.root("run"),
              UsageLineage.NONE,
              "writer",
              1L,
              1L);

  private RelayPublishTool tool(String call) {
    var tool =
        new RelayPublishTool(publishing)
            .forRun("writer", "session", RelayPublishing.DefinitionKind.AGENT, () -> false);
    tool.calledAs(call);
    return tool;
  }

  @Test
  void strict_conversion_precedes_all_effects_and_does_not_echo_untrusted_input() {
    var tool = tool("call");
    for (String invalid :
        List.of(
            "null",
            "[]",
            "{}",
            "{\"topic\":\"input\",\"text\":1}",
            "{\"topic\":\"input\",\"text\":\"body\",\"publisher\":\"evil\"}",
            "{\"topic\":\"input\",\"text\":null}",
            "{\"topic\":\"input\",\"text\":\"a\",\"text\":\"b\"}",
            "{\"topic\":\"input\",\"text\":\"body\"} {}")) {
      assertTrue(tool.run(invalid, Home.of("research"), owner).contains("requires only"), invalid);
    }
    verifyNoInteractions(publishing);
  }

  @Test
  void per_run_identity_and_receipts_are_bound_without_sharing_mutable_calls() {
    var receipt =
        new RelayPublishing.Receipt(
            UUID.randomUUID(), "input", "9007199254740993", Instant.parse("2026-11-13T00:00:00Z"));
    when(publishing.publish(any(), any(), any(), any())).thenReturn(receipt);
    var first = tool("first");
    var second = tool("second");
    String input = "{\"topic\":\"input\",\"text\":\"  text\\n  \"}";
    String result = first.run(input, Home.of("research"), owner);
    second.run(input, Home.of("research"), owner);
    assertTrue(result.contains("\"position\":\"9007199254740993\""));
    assertTrue(result.contains("\"publishedAt\":\"2026-11-13T00:00:00Z\""));
    verify(publishing)
        .publish(
            eq("research"),
            eq(
                new RelayPublishing.Execution(
                    owner, "session", "first", RelayPublishing.DefinitionKind.AGENT)),
            eq(new RelayPublishing.Request("input", "  text\n  ")),
            any());
    verify(publishing)
        .publish(
            eq("research"),
            eq(
                new RelayPublishing.Execution(
                    owner, "session", "second", RelayPublishing.DefinitionKind.AGENT)),
            any(),
            any());
  }

  @Test
  void the_run_fence_is_handed_to_the_publication_transaction() {
    OperationFence fence = () -> {};
    var receipt =
        new RelayPublishing.Receipt(
            UUID.randomUUID(), "input", "1", Instant.parse("2026-11-13T00:00:00Z"));
    when(publishing.publish(any(), any(), any(), eq(fence))).thenReturn(receipt);
    var tool =
        new RelayPublishTool(publishing)
            .forRun("writer", "session", RelayPublishing.DefinitionKind.AGENT, () -> false, fence);
    tool.calledAs("call");
    tool.run("{\"topic\":\"input\",\"text\":\"text\"}", Home.of("research"), owner);
    verify(publishing).publish(eq("research"), any(), any(), eq(fence));
  }

  @Test
  void cancellation_missing_owner_and_database_failures_do_not_replay() {
    var cancelled =
        new RelayPublishTool(publishing)
            .forRun("writer", null, RelayPublishing.DefinitionKind.AGENT, () -> true);
    cancelled.calledAs("call");
    String input = "{\"topic\":\"input\",\"text\":\"text\"}";
    assertTrue(cancelled.run(input, Home.of("research"), owner).contains("cancelled"));
    assertTrue(tool("call").run(input, Home.of("research")).contains("authenticated"));
    verifyNoInteractions(publishing);
    when(publishing.publish(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));
    assertThrows(
        IllegalStateException.class, () -> tool("call").run(input, Home.of("research"), owner));
    verify(publishing).publish(any(), any(), any(), any());
  }

  @Test
  void registration_does_not_offer_the_tool_without_a_definition_grant() throws Exception {
    var runtime =
        new JobRuntime(mock(LlmDispatcher.class), List.of(new RelayPublishTool(publishing)));
    for (String name : List.of("granted", "ungranted")) {
      Files.writeString(
          directory.resolve(name + ".md"),
          "---\nname: "
              + name
              + "\ndescription: fixture\nmodel: fast\nmax-turns: 4\nmax-model-calls: 8\ntools: "
              + (name.equals("granted") ? "[relay_publish]" : "[]")
              + "\n---\nPublish.");
    }
    var agents = AgentRegistry.load(directory, runtime.knownTools());
    assertTrue(
        runtime.schemasOfferedTo(agents.get("granted")).stream()
            .anyMatch(schema -> schema.name().equals(RelayPublishTool.NAME)));
    assertFalse(
        runtime.schemasOfferedTo(agents.get("ungranted")).stream()
            .anyMatch(schema -> schema.name().equals(RelayPublishTool.NAME)));
    verifyNoInteractions(publishing);
  }
}
