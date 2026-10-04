package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefusalDetectorTest {

  private static Completion said(String content) {
    return new Completion(content, "stop", TokenUsage.UNKNOWN, List.of());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "I'm sorry, but I can't help with that.",
        "I’m sorry, but I can’t help with that.",
        "I can't assist with finding information about that person.",
        "Sorry, I can't comply with this request.",
        "  I cannot provide instructions for that.  "
      })
  void a_short_answer_that_opens_by_declining_is_a_refusal(String content) {
    assertTrue(RefusalDetector.PHRASES.refusal(said(content)).isPresent(), content);
  }

  @Test
  void a_long_answer_that_declines_one_part_is_an_answer() {
    String content =
        "I can't help with guessing a password, but here is what the public"
            + " record shows about the domain. ".repeat(20);
    assertFalse(
        RefusalDetector.PHRASES.refusal(said(content)).isPresent(),
        "rerouting a real answer is the expensive direction to be wrong in");
  }

  @Test
  void a_phrase_deep_inside_a_short_answer_is_not_its_opening() {
    String content =
        "The registrant for example.org is IANA, and its abuse contact is"
            + " listed. Note: I can't help with anything beyond public records.";
    assertFalse(RefusalDetector.PHRASES.refusal(said(content)).isPresent());
  }

  @Test
  void an_ordinary_answer_is_not_a_refusal() {
    assertFalse(RefusalDetector.PHRASES.refusal(said("The port is 5432.")).isPresent());
    assertFalse(
        RefusalDetector.PHRASES.refusal(said("")).isPresent(),
        "a model that said nothing did not decline anything");
  }

  @Test
  void a_completion_that_called_tools_is_doing_the_task() {
    Completion calling =
        new Completion(
            "I can't help with that without looking.",
            "tool_calls",
            TokenUsage.UNKNOWN,
            List.of(new ToolCall("c1", "search", "{}")));
    assertFalse(RefusalDetector.PHRASES.refusal(calling).isPresent());
  }

  @Test
  void a_completion_that_was_cut_off_stopped_rather_than_declined() {
    Completion cut = new Completion("I can't", "length", TokenUsage.UNKNOWN, List.of());
    assertFalse(RefusalDetector.PHRASES.refusal(cut).isPresent());
  }
}
