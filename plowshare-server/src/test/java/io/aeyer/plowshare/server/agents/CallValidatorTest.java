package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.CallValidator.Verdict;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a validator's answer is read as (spec 2026-09-28-call-failures §5). */
class CallValidatorTest {

  @Test
  void a_call_verdict_carries_its_arguments_as_json_text() {
    Verdict verdict =
        Verdict.parse(
            "{\"verdict\": \"call\", \"arguments\": {\"ops\":"
                + " [{\"op\": \"add\"}]}, \"reason\": \"bare arguments\"}");

    assertTrue(verdict.isCall());
    assertEquals("{\"ops\":[{\"op\":\"add\"}]}", verdict.arguments());
    assertEquals("bare arguments", verdict.reason());
  }

  @Test
  void a_fenced_answer_is_read_through_its_fence() {
    Verdict verdict =
        Verdict.parse(
            "```json\n{\"verdict\": \"not_a_call\", \"reason\":" + " \"an example\"}\n```");

    assertEquals(Verdict.NOT_A_CALL, verdict.verdict());
    assertFalse(verdict.isCall());
    assertNull(verdict.arguments());
  }

  @Test
  void anything_but_a_json_object_is_unsure() {
    Verdict verdict = Verdict.parse("I think it was a call.");

    assertEquals(Verdict.UNSURE, verdict.verdict());
    assertFalse(verdict.isCall());
    assertTrue(verdict.reason().contains("I think it was a call."), verdict.reason());
    assertEquals(Verdict.UNSURE, Verdict.parse(null).verdict());
  }

  @Test
  void arguments_that_are_not_an_object_are_no_arguments() {
    assertNull(Verdict.parse("{\"verdict\": \"call\", \"arguments\": \"ops\"}").arguments());
  }

  @Test
  void the_absent_validator_never_says_call() {
    ToolSchema todoWrite =
        new ToolSchema("todo_write", "todos", Map.of("type", "object", "properties", Map.of()));

    assertFalse(
        CallValidator.NONE
            .validate(new CallValidator.Question("{}", todoWrite, "keep the list"), () -> false)
            .isCall());
  }
}
