package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.images.ImageStore;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.LlmPool;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.NoOpTokenLedger;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Recovery narrows ordinary delegation; no privileged summariser or independent allowance. */
class OutputRecoveryDelegationTest {
  private static AgentDefinition definition(
      String name, List<String> tools, List<String> calls, int turns) {
    return new AgentDefinition(
        name,
        "fixture",
        "fast",
        Sampling.Intent.DEFAULT,
        Sampling.NONE,
        tools,
        calls,
        List.of(),
        turns,
        2,
        "Follow the supplied task.",
        false,
        true,
        false,
        false,
        false,
        AgentDefinition.Fallback.NONE,
        List.of(),
        null,
        false,
        List.of());
  }

  private static Transcript recording(List<LoggedEntry> entries) {
    return new Transcript() {
      public List<ChatMessage> before() {
        return List.of();
      }

      public void promptMeasured(int tokens) {}

      public void record(LoggedEntry entry) {
        entries.add(entry);
      }
    };
  }

  @Test
  void a_replacement_worker_with_capabilities_is_refused_before_inference() {
    var worker = definition("recovery_worker", List.of("information_read"), List.of(), 1);
    var caller = definition("caller", List.of("agent_run"), List.of(worker.name()), 3);
    var registry = new AgentRegistry(Map.of(worker.name(), worker, caller.name(), caller));
    var dispatcher = mock(LlmDispatcher.class);
    var runtime = new JobRuntime(dispatcher, List.of(), () -> registry);
    Budget budget = Budget.of(2);
    var entries = new ArrayList<LoggedEntry>();
    var tool =
        new AgentRunTool(
            registry,
            runtime,
            caller,
            budget,
            () -> false,
            null,
            recording(entries),
            List.of(),
            ImageStore.NONE);
    String result =
        tool.run(
            "{\"agent\":\"recovery_worker\",\"task\":\"shorten\",\"recovery\":true}",
            new Home("fixture"));
    assertTrue(result.startsWith("E_NO_ACCESS:"));
    assertEquals(0, budget.spent());
    assertEquals(2, entries.stream().filter(e -> e.kind() == EntryKind.DIAGNOSTIC).count());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void the_runtime_retains_recovery_decisions_even_when_script_preparation_fails() {
    String source =
        ScriptProgram.MARKER
            + """

            export const manifest={name:'fixture',description:'fixture',model:'fast',tools:[],calls:[],scopes:[],
              'max-turns':2,'max-model-calls':2,stages:[{id:'one'}]};
            export function step(input) {
              modelOutput.recover('{"notes":"Unconfirmed device"}', {
                name:'fixture',worker:'recovery_worker',fields:{notes:{words:10,required:true}}
              });
              throw new Error('later validation failed');
            }
            """;
    var store = mock(ScriptStore.class);
    when(store.head(anyString())).thenCallRealMethod();
    when(store.prepareNext(anyString(), any(), anyBoolean()))
        .thenAnswer(
            invocation -> {
              ScriptStore.Input input = invocation.getArgument(1);
              ScriptProgram.step(
                  source, new ObjectMapper().createObjectNode(), input.recoveryDiagnostics());
              throw new AssertionError("the script must fail after recording its decision");
            });
    var entries = new ArrayList<LoggedEntry>();
    Transcript transcript =
        new Transcript() {
          public List<ChatMessage> before() {
            return List.of();
          }

          public void promptMeasured(int tokens) {}

          public String conversationId() {
            return "cnv_recovery_fixture";
          }

          public Origin origin() {
            return Origin.ORCHESTRATION;
          }

          public void record(LoggedEntry entry) {
            entries.add(entry);
          }
        };
    var dispatcher = mock(LlmDispatcher.class);
    var runtime = new JobRuntime(dispatcher, List.of());
    runtime.useScripts(store);
    var definition =
        OrchestrationRegistry.parsePinned(
                "fixture", "fixture.js", source, Set.of(), OrchestrationDefinition.Tier.SHIPPED)
            .conductor();
    Outcome outcome =
        runtime.run(
            definition,
            "begin",
            Home.global(),
            Budget.of(2),
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(2),
            List.of(),
            "fixture_account");
    assertEquals(Outcome.Ending.UNAVAILABLE, outcome.ending());
    assertTrue(
        entries.stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.DIAGNOSTIC
                        && entry.content().contains("\"type\":\"model_output_recovery\"")
                        && entry.content().contains("\"conversation\":\"cnv_recovery_fixture\"")));
    verifyNoInteractions(dispatcher);
  }

  @Test
  void recovery_uses_the_parent_budget_and_cancellation_and_offers_no_tools() {
    AtomicInteger calls = new AtomicInteger();
    var offered = new ArrayList<ToolSchema>();
    LlmTransport transport =
        new LlmTransport() {
          public String poolName() {
            return "fixture";
          }

          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            calls.incrementAndGet();
            offered.addAll(tools);
            return new Completion(
                "{\"notes\":\"Unconfirmed device\"}", "stop", TokenUsage.UNKNOWN, List.of());
          }

          public Completion stream(
              String model,
              List<ChatMessage> messages,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              BooleanSupplier abandoned) {
            return complete(model, messages, sampling, tools);
          }

          public Embeddings embed(String model, List<String> input) {
            throw new AssertionError("Recovery must not embed or fetch anything");
          }

          public void close() {}
        };
    var worker = definition("recovery_worker", List.of(), List.of(), 1);
    var caller = definition("caller", List.of("agent_run"), List.of(worker.name()), 3);
    var registry = new AgentRegistry(Map.of(worker.name(), worker, caller.name(), caller));
    try (var dispatcher =
        new LlmDispatcher(
            List.of(
                new LlmPool(
                    "fixture",
                    List.of("fixture-model"),
                    Map.of("fast", "fixture-model"),
                    1,
                    1,
                    Duration.ofSeconds(2),
                    transport)),
            new NoOpTokenLedger())) {
      var runtime = new JobRuntime(dispatcher, List.of(), () -> registry);
      Budget budget = Budget.of(1);
      var entries = new ArrayList<LoggedEntry>();
      var tool =
          new AgentRunTool(
              registry,
              runtime,
              caller,
              budget,
              () -> false,
              null,
              recording(entries),
              List.of(),
              ImageStore.NONE);
      String args =
          "{\"agent\":\"recovery_worker\",\"task\":\"Shorten only the supplied text\",\"recovery\":true}";
      assertTrue(tool.run(args, new Home("fixture")).contains("Unconfirmed device"));
      assertEquals(1, calls.get());
      assertEquals(1, budget.spent());
      assertTrue(offered.isEmpty());
      assertFalse(tool.run(args, new Home("fixture")).contains("answered:"));
      assertEquals(1, calls.get(), "exhausted allowance must not start another inference");
      var cancelled =
          new AgentRunTool(
              registry,
              runtime,
              caller,
              Budget.of(1),
              () -> true,
              null,
              recording(entries),
              List.of(),
              ImageStore.NONE);
      assertFalse(cancelled.run(args, new Home("fixture")).contains("answered:"));
      assertEquals(1, calls.get(), "cancellation must not start inference");
      assertTrue(entries.stream().allMatch(e -> e.kind() == EntryKind.DIAGNOSTIC));
    }
  }
}
