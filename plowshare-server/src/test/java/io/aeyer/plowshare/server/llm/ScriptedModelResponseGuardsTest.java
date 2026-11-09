package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import io.aeyer.plowshare.server.security.ChatFiltering;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScriptedModelResponseGuardsTest {
  private static final String HARMONY_MODEL = "openai/gpt-oss-120b";
  private static final String QWEN_MODEL = "Qwen/Qwen3-8B";
  private static final String QWEN =
      "<tool_call>\n{\"name\":\"lookup\",\"arguments\":{}}\n</tool_call>";

  private static String retainedHarmony() throws Exception {
    try (var resource =
        ScriptedModelResponseGuardsTest.class.getResourceAsStream(
            "/model-response-guards/harmony-retained.txt")) {
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  void shipped_profiles_select_their_format_and_allow_documentation() throws Exception {
    try (var guards = new ScriptedModelResponseGuards(SamplingProfiles.read(null), null)) {
      var harmony = guards.forModel(HARMONY_MODEL).orElseThrow();
      var qwen = guards.forModel(QWEN_MODEL).orElseThrow();
      assertEquals("harmony", harmony.name());
      assertEquals("qwen-hermes", qwen.name());
      assertTrue(harmony.rejects().test(retainedHarmony()));
      assertTrue(harmony.rejects().test(" \n<|channel|>commentary to=functions.lookup"));
      assertTrue(qwen.rejects().test(QWEN));
      assertTrue(qwen.rejects().test(" \n<tool_call"));
      assertTrue(qwen.rejects().test("<|im_start|>assistant\n" + QWEN));
      assertFalse(harmony.rejects().test(QWEN));
      assertFalse(qwen.rejects().test(retainedHarmony()));
      assertFalse(harmony.rejects().test("Example\n```text\n" + retainedHarmony() + "\n```"));
      assertFalse(qwen.rejects().test("```xml\n" + QWEN + "\n```"));
      assertTrue(guards.forModel("gemma-4-e4b-it").isEmpty());
    }
  }

  @Test
  void an_exact_deployment_profile_loads_a_javascript_extension(@TempDir Path directory)
      throws Exception {
    Files.writeString(
        directory.resolve("models.yaml"),
        """
        models:
          gpt-oss-120b:
            profile: custom
        """);
    Files.writeString(
        directory.resolve("custom.yaml"),
        """
        version: 2
        response-guard: custom
        modes:
          default:
            precise: {}
            balanced: {}
            exploratory: {}
        """);
    var profiles = SamplingProfiles.read(directory);
    assertThrows(
        IllegalStateException.class, () -> new ScriptedModelResponseGuards(profiles, directory));
    Path guardsDir = Files.createDirectory(directory.resolve("response-guards"));
    Path script = guardsDir.resolve("custom.mjs");
    Files.writeString(script, "export const reject = text => text.startsWith('broken:');");
    try (var guards = new ScriptedModelResponseGuards(profiles, directory)) {
      assertTrue(guards.forModel(HARMONY_MODEL).orElseThrow().rejects().test("broken: test"));
      assertEquals("harmony", guards.forModel("gpt-oss-20b").orElseThrow().name());
    }
    Files.writeString(script, "export const reject = text => ({reject:true});");
    assertThrows(
        IllegalStateException.class, () -> new ScriptedModelResponseGuards(profiles, directory));
    Files.writeString(
        directory.resolve("custom.yaml"),
        """
        version: 2
        response-guard: ../outside
        modes:
          default:
            precise: {}
            balanced: {}
            exploratory: {}
        """);
    assertThrows(IllegalStateException.class, () -> SamplingProfiles.read(directory));
  }

  @Test
  void actual_route_guards_block_complete_and_split_streams_before_releasing_any_text()
      throws Exception {
    var response =
        new AtomicReference<>(
            new Completion(retainedHarmony(), "stop", TokenUsage.UNKNOWN, List.of()));
    LlmTransport transport =
        new LlmTransport() {
          public String poolName() {
            return "fixture";
          }

          public Completion complete(
              String model, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
            return response.get();
          }

          public Completion stream(
              String model,
              List<ChatMessage> messages,
              Sampling sampling,
              List<ToolSchema> tools,
              Deltas sink,
              BooleanSupplier abandoned) {
            var value = response.get();
            sink.thought("Thinking");
            if (value.content().length() > 1) {
              sink.answered(value.content().substring(0, 1));
              sink.answered(value.content().substring(1));
            }
            return value;
          }

          public Embeddings embed(String model, List<String> input) {
            throw new AssertionError("unexpected embedding");
          }

          public void close() {}
        };
    var pool =
        new LlmPool(
            "fixture",
            List.of(HARMONY_MODEL, QWEN_MODEL),
            Map.of("fast", HARMONY_MODEL),
            1,
            1,
            Duration.ofSeconds(5),
            transport);
    try (var guards = new ScriptedModelResponseGuards(SamplingProfiles.read(null), null);
        var dispatcher =
            new LlmDispatcher(
                List.of(pool),
                new NoOpTokenLedger(),
                ignored -> "",
                InferenceAccounting.NONE,
                null,
                null,
                ChatFiltering.NONE,
                InferenceBoundary.FIXTURE,
                ModelMediaProfiles.NONE,
                PromptImages.NONE,
                new RatioTokenizer(4),
                guards)) {
      var request = ChatRequest.of("fast", null, "Inspect retained evidence");
      var complete = assertThrows(ModelResponseRejected.class, () -> dispatcher.complete(request));
      assertEquals("harmony", complete.guard());
      assertEquals(HARMONY_MODEL, complete.completion().servedBy().wireModel());
      List<String> received = new ArrayList<>();
      List<String> reasoning = new ArrayList<>();
      Deltas sink =
          new Deltas() {
            @Override
            public void answered(String text) {
              received.add(text);
            }

            @Override
            public void thought(String text) {
              reasoning.add(text);
            }
          };
      assertThrows(ModelResponseRejected.class, () -> dispatcher.stream(request, sink));
      assertThrows(
          ModelResponseRejected.class,
          () -> dispatcher.streamOn("fixture", request, sink, () -> false));
      assertEquals(List.of(), received);
      assertEquals(List.of(), reasoning);

      response.set(new Completion(QWEN, "stop", TokenUsage.UNKNOWN, List.of()));
      var qwen = ChatRequest.of(QWEN_MODEL, null, "Inspect retained evidence");
      assertEquals(
          "qwen-hermes",
          assertThrows(ModelResponseRejected.class, () -> dispatcher.stream(qwen, sink)).guard());
      assertEquals(List.of(), received);
      assertEquals(List.of(), reasoning);

      var call = new ToolCall("call-1", "lookup", "{}");
      response.set(new Completion("", "tool_calls", TokenUsage.UNKNOWN, List.of(call)));
      assertEquals(List.of(call), dispatcher.stream(qwen, sink).toolCalls());
      response.set(new Completion("", "tool_calls", TokenUsage.UNKNOWN, List.of()));
      assertEquals(
          ModelResponseRejected.Reason.MISSING_STRUCTURED_TOOL_CALLS,
          assertThrows(ModelResponseRejected.class, () -> dispatcher.complete(qwen)).reason());

      response.set(new Completion("Assessment", "stop", TokenUsage.UNKNOWN, List.of()));
      dispatcher.stream(qwen, sink);
      assertEquals(List.of("Assessment"), received);
      assertEquals(List.of("Thinking", "Thinking"), reasoning);
    }
  }
}
