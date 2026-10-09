package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptProgram;
import io.aeyer.plowshare.server.orchestrations.scripted.ScriptStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Script failure propagation through a mocked journal; no database or model calls. */
class ScriptedDelegationFailureTest {
  @Test
  void a_child_failure_retains_its_cause_counts_and_failed_call_without_replay() {
    Budget budget = Budget.of(10);
    var entries = new ArrayList<LoggedEntry>();
    Transcript transcript =
        new Transcript() {
          public List<ChatMessage> before() {
            return List.of();
          }

          public void promptMeasured(int tokens) {}

          public String conversationId() {
            return "cnv_script_fixture";
          }

          public Origin origin() {
            return Origin.ORCHESTRATION;
          }

          public void record(LoggedEntry entry) {
            entries.add(entry);
          }
        };
    var store = mock(ScriptStore.class);
    var latest = new AtomicReference<ScriptStore.Step>();
    when(store.latest(anyString())).thenAnswer(call -> Optional.ofNullable(latest.get()));
    when(store.prepareNext(anyString(), any(), anyBoolean()))
        .thenAnswer(
            call -> {
              ScriptStore.Input input = call.getArgument(1);
              var step =
                  new ScriptStore.Step(
                      input.sequence(),
                      input.hash(),
                      new ScriptStore.Tool(
                          input.sequence() == 0 ? "probe" : "failed_child", "{}", false),
                      null,
                      null,
                      false,
                      null);
              latest.set(step);
              return new ScriptStore.Prepared(step);
            });
    doAnswer(
            call -> {
              var step = latest.get();
              latest.set(
                  new ScriptStore.Step(
                      step.sequence(),
                      step.hash(),
                      step.command(),
                      call.getArgument(2),
                      call.getArgument(2),
                      true,
                      "{}"));
              return null;
            })
        .when(store)
        .completed(anyString(), anyInt(), anyString());
    AgentTool probe =
        new AgentTool() {
          public ToolSchema schema() {
            return ToolSchema.from("probe", "fixture", Map.of("type", "object"));
          }

          public String run(String arguments, Home home) {
            assertTrue(budget.trySpend());
            return "{\"ok\":true}";
          }
        };
    AgentTool child =
        new AgentTool() {
          public ToolSchema schema() {
            return ToolSchema.from("failed_child", "fixture", Map.of("type", "object"));
          }

          public String run(String arguments, Home home) {
            assertTrue(budget.trySpend());
            throw new AgentRunTool.SubAgentFailed(
                "research_analyst",
                new Outcome(
                    Outcome.Ending.UNAVAILABLE,
                    "The model connection failed.",
                    0,
                    1,
                    "LlmTransportException: connection reset (HTTP phase: request_headers; attempt: 2; cause: SocketException)"));
          }
        };
    var runtime = new JobRuntime(mock(LlmDispatcher.class), List.of(probe, child));
    var scoped = mock(ScopedTools.class);
    runtime.useScopedTools(scoped);
    runtime.useScripts(store);
    String source =
        ScriptProgram.MARKER
            + "\n"
            + "export const manifest={name:'fixture',description:'fixture',model:'fast',tools:['probe','failed_child'],calls:[],scopes:[],'max-turns':10,'max-model-calls':10,stages:[{id:'one'}]};\n"
            + "export function step(input) { return {command:{tool:'probe',arguments:{}}}; }";
    var definition =
        OrchestrationRegistry.parsePinned(
                "fixture",
                "fixture.js",
                source,
                Set.of("probe", "failed_child"),
                OrchestrationDefinition.Tier.SHIPPED)
            .conductor();
    Outcome result =
        runtime.run(
            definition,
            "begin",
            Home.global(),
            budget,
            () -> false,
            null,
            JobWatch.UNWATCHED,
            transcript,
            TurnCap.of(10),
            List.of(),
            "alice");
    assertEquals(Outcome.Ending.SUB_AGENT_FAILED, result.ending());
    assertEquals(1, result.steps());
    assertEquals(2, result.modelCalls());
    assertTrue(
        result.detail().startsWith("research_analyst: LlmTransportException: connection reset"));
    assertTrue(entries.stream().anyMatch(entry -> entry.content().equals(result.failureText())));
    verify(store).started("cnv_script_fixture", 1, "{}");
    verify(store, never()).executed(eq("cnv_script_fixture"), eq(1), anyString());
    verify(store, never()).completed(eq("cnv_script_fixture"), eq(1), anyString());
    // The script's admitted internal grants do not consult external provider authority.
    verify(scoped).tools(any(), eq("fixture"), any(), any(), any());
    verifyNoMoreInteractions(scoped);
  }
}
