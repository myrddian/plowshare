package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.VerdictKind;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ModelAnswersTest {
  static Stream<String> invalidLearning() {
    return Stream.of(
        "{\"memories\":1}",
        "{\"memories\":[{\"summary\":3,\"scope\":\"scope\",\"body\":\"body\"}]}",
        "{\"memories\":[{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"body\",\"formedBy\":\"forged\"}]}",
        "{\"memories\":[{\"summary\":\"claim\\nforged\",\"scope\":\"scope\",\"body\":\"body\"}]}",
        "{\"memories\":[{\"summary\":\"claim\",\"scope\":\"scope\",\"body\":\"\\u0000\"}]}",
        "{\"memories\":[],\"memories\":[{}]}",
        "{\"memories\":[],\"other\":true}");
  }

  @ParameterizedTest
  @MethodSource("invalidLearning")
  void invalid_learning_never_becomes_a_partial_proposal_list(String content) {
    assertThrows(ModelJson.Unreadable.class, () -> ModelAnswers.learning(content));
  }

  @Test
  void supported_model_syntax_recovery_precedes_strict_field_validation() {
    var answer =
        ModelAnswers.scribe(
            "```json\n{\"verdict\":\"new\",\"target\":null,\"reason\":\"distinct claim\"}\n```");
    assertEquals(VerdictKind.NEW, answer.verdict());
    assertNull(answer.target());
    assertThrows(
        ModelJson.Unreadable.class,
        () ->
            ModelAnswers.scribe(
                "{\"verdict\":\"new\",\"target\":{},\"reason\":\"distinct claim\"}"));
  }

  @Test
  void curation_and_schedule_fields_cannot_be_coerced_or_exceed_their_bounds() {
    assertThrows(
        ModelJson.Unreadable.class,
        () -> ModelAnswers.curation("{\"decision\":\"keep\",\"reason\":true}"));
    assertThrows(
        ModelJson.Unreadable.class,
        () -> new ModelAnswers.Curation(ModelAnswers.Decision.KEEP, "x".repeat(32769)));
    assertThrows(ModelJson.Unreadable.class, () -> ModelAnswers.schedule("{\"cron\":5}"));
    assertThrows(
        ModelJson.Unreadable.class, () -> ModelAnswers.schedule("{\"agent\":\"bot\\nother\"}"));
  }
}
