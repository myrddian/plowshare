package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.*;
import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageContextBudgetTest {
  private static final Content.Image IMAGE =
      new Content.Image("img_" + "a".repeat(32), "data:image/png;base64,AAAA");

  private static final class Meter implements Tokenizer {
    final List<String> text = new ArrayList<>();

    public TokenCount count(String value) {
      text.add(value);
      return TokenCount.estimated(value.length(), "fixture");
    }

    public String describe() {
      return "fixture";
    }
  }

  @Test
  void repeated_images_reserve_each_occurrence_without_counting_encoded_bytes() {
    var budget = new ImageContextBudget(512, 256, 2, 1024);
    var meter = new Meter();
    var once = ChatRequest.of("model", List.of(ChatMessage.user("Inspect", List.of(IMAGE))));
    long input = budget.reserve(once, meter);
    var twice = once.withMessages(List.of(ChatMessage.user("Inspect", List.of(IMAGE, IMAGE))));
    assertEquals(input + 512, budget.reserve(twice, meter));
    assertFalse(
        meter.text.stream()
            .anyMatch(text -> text.contains("base64") || text.contains(IMAGE.uid())));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            budget.reserve(
                once.withMessages(
                    List.of(ChatMessage.user("Inspect", List.of(IMAGE, IMAGE, IMAGE)))),
                meter));
  }

  @Test
  void complete_non_image_prompt_includes_history_tool_arguments_and_declared_schemas() {
    var meter = new Meter();
    var request =
        ChatRequest.of(
                "model",
                List.of(
                    ChatMessage.system("System instructions"),
                    ChatMessage.user("First question"),
                    ChatMessage.assistant(
                        "", List.of(new ToolCall("call-id", "capture", "{\"area\":\"window\"}"))),
                    ChatMessage.tool("call-id", "Captured result"),
                    ChatMessage.user("Inspect", List.of(IMAGE))))
            .withTools(
                List.of(
                    ToolSchema.from(
                        "capture",
                        "Description",
                        Map.of("type", "object", "properties", Map.of()))))
            .withToolChoice(ToolChoice.REQUIRED)
            .withSampling(
                Sampling.NONE
                    .withReasoningEffort(Sampling.Effort.LOW)
                    .withResponseFormat(
                        JsonSchema.from(
                            "VisualAnswer", Map.of("type", "object", "properties", Map.of()))));
    long reserved = ImageContextBudget.DEFAULT.reserve(request, meter);
    assertTrue(
        meter.text.containsAll(
            List.of(
                "System instructions",
                "First question",
                "call-id",
                "capture",
                "{\"area\":\"window\"}",
                "Captured result",
                "required")));
    assertTrue(
        meter.text.stream()
            .anyMatch(text -> text.contains("Description") && text.contains("parameters")));
    assertTrue(meter.text.contains("low"));
    assertTrue(
        meter.text.stream()
            .anyMatch(text -> text.contains("VisualAnswer") && text.contains("json_schema")));
    assertTrue(reserved >= 4096 + 1024 + 32 * 6);
  }

  @Test
  void non_ascii_bytes_and_tool_schemas_obey_the_same_hard_prompt_limit() {
    var budget = new ImageContextBudget(512, 256, 2, 100);
    var request =
        ChatRequest.of("model", List.of(ChatMessage.user("é".repeat(60), List.of(IMAGE))));
    assertThrows(IllegalArgumentException.class, () -> budget.reserve(request, new Meter()));
    var tools =
        List.of(
            ToolSchema.from(
                "capture", "x".repeat(101), Map.of("type", "object", "properties", Map.of())));
    var small = request.withMessages(List.of(ChatMessage.user("Inspect", List.of(IMAGE))));
    assertDoesNotThrow(() -> budget.reserve(small, new Meter()));
    assertThrows(
        IllegalArgumentException.class, () -> budget.reserve(small.withTools(tools), new Meter()));
  }
}
