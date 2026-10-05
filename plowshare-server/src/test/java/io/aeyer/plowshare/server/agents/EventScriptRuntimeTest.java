package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.orchestrations.scripted.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Handler effects use the real script driver; the persistence journal and model are mocked. */
class EventScriptRuntimeTest {
  private static final String SOURCE =
      ScriptProgram.MARKER
          + "\nexport const manifest={};export function step(input){return {state:null,command:{finish:'done'}};}";
  private final ScriptStore scripts = mock(ScriptStore.class);
  private final LlmDispatcher models = mock(LlmDispatcher.class);
  private final AgentTool tool = mock(AgentTool.class);
  private final List<LoggedEntry> entries = new ArrayList<>();

  private Transcript transcript(Origin origin) {
    return new Transcript() {
      public List<ChatMessage> before() {
        return List.of();
      }

      public void promptMeasured(int tokens) {}

      public String conversationId() {
        return "cnv_event_script";
      }

      public Origin origin() {
        return origin;
      }

      public Speaker speaker() {
        return Speaker.event("relay fixture");
      }

      public void record(LoggedEntry entry) {
        entries.add(entry);
      }
    };
  }

  private AgentDefinition agent() {
    return new AgentDefinition(
        "worker",
        "fixture",
        "fast",
        Sampling.Intent.DEFAULT,
        Sampling.NONE,
        List.of("probe"),
        List.of(),
        List.of(),
        10,
        5,
        SOURCE,
        true,
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

  private JobRuntime runtime(ScriptStore.Command command) {
    when(tool.schema()).thenReturn(ToolSchema.from("probe", "fixture", Map.of("type", "object")));
    when(scripts.prepareNext(anyString(), any(), anyBoolean()))
        .thenAnswer(
            call -> {
              ScriptStore.Input input = call.getArgument(1);
              return new ScriptStore.Prepared(
                  new ScriptStore.Step(
                      input.sequence(), input.hash(), command, null, null, false, null));
            });
    var runtime = new JobRuntime(models, List.of(tool));
    runtime.useScripts(scripts);
    return runtime;
  }

  private Outcome run(JobRuntime runtime, Origin origin) {
    return runtime.run(
        agent(),
        "event input",
        Home.global(),
        Budget.of(5),
        () -> false,
        null,
        JobWatch.UNWATCHED,
        transcript(origin),
        TurnCap.of(10),
        List.of(),
        "operator");
  }

  @Test
  void event_finish_is_journaled_and_logged_without_inference_or_conductor_authority() {
    var runtime = runtime(new ScriptStore.Finish("done"));
    assertEquals(Outcome.Ending.ANSWERED, run(runtime, Origin.EVENT).ending());
    verify(scripts).executed("cnv_event_script", 0, "done");
    verify(scripts).completed("cnv_event_script", 0, "done");
    assertTrue(
        entries.stream()
            .anyMatch(
                entry ->
                    entry.kind() == EntryKind.UTTERANCE
                        && entry.speaker().equals(Speaker.event("relay fixture"))));
    assertTrue(
        entries.stream()
            .anyMatch(entry -> entry.kind() == EntryKind.ANSWER && entry.content().equals("done")));
    verifyNoInteractions(models);
    verify(tool, never()).run(anyString(), any());
  }

  @Test
  void a_conductor_cannot_finish_without_using_its_stage_gate() {
    var runtime = runtime(new ScriptStore.Finish("done"));
    assertThrows(IllegalStateException.class, () -> run(runtime, Origin.ORCHESTRATION));
    verify(scripts, never()).executed(anyString(), anyInt(), anyString());
  }

  @Test
  void the_execution_fence_is_checked_before_journal_start_or_tool_effect() {
    var runtime = runtime(new ScriptStore.Tool("probe", "{}", false));
    runtime.useRunExtras(
        context ->
            new RunExtras.Extras(
                List.of(), context.end(), false, false, (name, args) -> "protected workspace"));
    assertEquals(Outcome.Ending.UNAVAILABLE, run(runtime, Origin.EVENT).ending());
    verify(scripts, never()).started(anyString(), anyInt(), anyString());
    verify(tool, never()).run(anyString(), any());
    assertTrue(entries.stream().anyMatch(entry -> entry.content().contains("protected workspace")));
  }

  @Test
  void interrupted_effects_without_a_receipt_are_never_replayed() throws Exception {
    var runtime = runtime(new ScriptStore.Tool("probe", "{}", false));
    String hash =
        "sha256:"
            + HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    when(scripts.latest("cnv_event_script"))
        .thenReturn(
            Optional.of(
                new ScriptStore.Step(
                    0, hash, new ScriptStore.Tool("probe", "{}", false), null, null, true, "{}")));
    assertThrows(IllegalStateException.class, () -> run(runtime, Origin.EVENT));
    verify(scripts, never()).prepareNext(anyString(), any(), anyBoolean());
    verify(tool, never()).run(anyString(), any());
  }
}
