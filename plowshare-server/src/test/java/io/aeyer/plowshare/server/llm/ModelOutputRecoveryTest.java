package io.aeyer.plowshare.server.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Action;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Contract;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Limit;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Plan;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Problem;
import io.aeyer.plowshare.server.llm.ModelOutputRecovery.Request;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelOutputRecoveryTest {
  private final ModelOutputRecovery policy = new StandardModelOutputRecovery();
  private final Contract contract =
      new Contract(
          "fixture",
          Map.of("name", new Limit(3, true), "notes", new Limit(4, false)),
          "recovery_worker");

  private Plan inspect(String raw) {
    return policy.examine(new Request(raw, contract, Optional.empty()));
  }

  private Plan repaired(Plan prior, String response) {
    return policy.examine(new Request(response, contract, prior.checkpoint()));
  }

  @Test
  void valid_outputs_and_local_format_recovery_require_no_model_call() {
    for (String raw :
        new String[] {
          "{\"name\":\"Fixture\",\"notes\":\"Unconfirmed device\"}",
          "```json\n{\"name\":\"Fixture\",\"notes\":\"Unconfirmed device\"}\n```",
          "{'name':'Fixture','notes':'Unconfirmed device',}"
        }) {
      Plan result = inspect(raw);
      assertEquals(Action.ACCEPT, result.action());
      assertTrue(result.task().isEmpty());
      assertEquals("Unconfirmed device", result.value().get("notes"));
      assertEquals(0, result.diagnostic().attempt());
      assertEquals(ModelOutputRecovery.VERSION, result.diagnostic().policy());
      assertFalse(result.diagnostic().reason().isBlank());
    }
  }

  @Test
  void excessive_words_select_targeted_summary_and_merge_unchanged_fields() {
    Plan first =
        inspect("{\"name\":\"Fixture\",\"notes\":\"Possibly an unknown device in this network\"}");
    assertEquals(Action.SUMMARISE, first.action());
    assertEquals(Problem.WORD_LIMIT, first.diagnostic().issues().getFirst().problem());
    assertEquals(7, first.diagnostic().issues().getFirst().actualWords());
    assertEquals(4, first.diagnostic().issues().getFirst().maximumWords());
    assertTrue(first.task().orElseThrow().instruction().contains("Shorten only"));
    Plan result = repaired(first, "{\"notes\":\"Possibly an unknown device\"}");
    assertEquals(Action.ACCEPT, result.action());
    assertEquals("Fixture", result.value().get("name"));
    assertEquals("Possibly an unknown device", result.value().get("notes"));
    assertEquals(first.diagnostic().originalHash(), result.diagnostic().originalHash());
    assertEquals(1, result.diagnostic().attempt());
  }

  @Test
  void summary_cannot_change_untargeted_fields_or_drop_qualifiers_or_references() {
    Plan first =
        inspect(
            "{\"name\":\"Fixture\",\"notes\":\"Possibly unverified issue CVE-2026-1234 in version 1.2.3\"}");
    for (String response :
        new String[] {
          "{\"name\":\"Changed\",\"notes\":\"Possibly unverified CVE-2026-1234 1.2.3\"}",
          "{\"notes\":\"Confirmed CVE-2026-1234 1.2.3\"}",
          "{\"notes\":\"Possibly unverified issue\"}",
          "{\"notes\":\"Unverified CVE-2026-9999 1.2.3\"}"
        }) {
      Plan result = repaired(first, response);
      assertEquals(Action.NEEDS_REVIEW, result.action());
      assertTrue(result.task().isEmpty());
      assertTrue(
          result.diagnostic().issues().stream().anyMatch(i -> i.problem() == Problem.PRESERVATION));
    }
    assertEquals(
        Action.ACCEPT,
        repaired(first, "{\"notes\":\"Possibly unverified CVE-2026-1234 1.2.3\"}").action());
  }

  @Test
  void structural_correction_is_bounded_and_preserves_existing_valid_fields() {
    Plan first = inspect("{\"name\":\"Fixture\",\"notes\":null}");
    assertEquals(Action.CORRECT, first.action());
    assertEquals(Action.ACCEPT, repaired(first, "{\"name\":\"Fixture\",\"notes\":\"\"}").action());
    assertEquals(
        Action.NEEDS_REVIEW, repaired(first, "{\"name\":\"Invented\",\"notes\":\"\"}").action());
    Plan stillInvalid = repaired(first, "not a field object");
    assertEquals(Action.NEEDS_REVIEW, stillInvalid.action());
    assertTrue(stillInvalid.task().isEmpty());
  }

  @Test
  void truncated_content_requires_review_without_dispatching_a_repair_worker() {
    Plan result = inspect("{\"name\":\"Fixture\",\"notes\":\"Unconfirmed");
    assertEquals(Action.NEEDS_REVIEW, result.action());
    assertTrue(result.value().isEmpty());
    assertTrue(result.task().isEmpty());
    assertEquals("truncation", result.diagnostic().issues().getFirst().field());
  }

  @Test
  void refusals_content_and_oversize_responses_never_trigger_model_repairs() {
    for (String raw :
        new String[] {
          "E_NO_ACCESS: refused",
          "UNAVAILABLE: timeout",
          "Tool agent_run failed: E_NO_CONNECTION",
          "Information request refused: private"
        }) {
      assertEquals(Action.STOP, inspect(raw).action());
      assertTrue(inspect(raw).task().isEmpty());
    }
    assertEquals(
        Action.NEEDS_REVIEW, inspect("<tool_call>{\"name\":\"Fixture\",\"notes\":\"\"}").action());
    assertEquals(
        Action.NEEDS_REVIEW, inspect("{\"name\":\"Fixture\",\"notes\":\"<|unsafe\"}").action());
    assertEquals(Action.NEEDS_REVIEW, inspect("é".repeat(40000)).action());
    assertEquals(Action.NEEDS_REVIEW, inspect("x".repeat(32769)).action());
  }

  @Test
  void checkpoint_refuses_changed_contract_and_does_not_reset_attempt_count() {
    Plan first =
        inspect("{\"name\":\"Fixture\",\"notes\":\"too many words for this small budget\"}");
    Contract changed = new Contract("fixture", contract.fields(), "different_worker");
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.examine(new Request("{}", changed, first.checkpoint())));
    assertEquals(
        Action.NEEDS_REVIEW,
        repaired(first, "{\"notes\":\"still far too many words for the limit\"}").action());
  }

  @Test
  void ambiguous_fields_and_content_are_not_corrected_or_silently_selected() {
    for (String raw :
        new String[] {
          "{\"name\":\"First\",\"name\":\"Second\",\"notes\":\"\"}",
          "{\"name\":\"Fixture\",\"notes\":\"<function=write>\"}",
          "{\"name\":\"Fixture\",\"notes\":\"bad\\u0000content\"}"
        }) {
      Plan result = inspect(raw);
      assertEquals(Action.NEEDS_REVIEW, result.action());
      assertTrue(result.task().isEmpty());
      assertTrue(
          result.diagnostic().issues().stream().anyMatch(i -> i.problem() == Problem.CONTENT));
    }
  }

  @Test
  void word_boundaries_include_unicode_whitespace_without_character_limits() {
    assertEquals(4, StandardModelOutputRecovery.words("one\t two\u0085three\ufefffour"));
    assertEquals(
        Action.ACCEPT,
        inspect("{\"name\":\"" + "longword".repeat(50) + "\",\"notes\":\"\"}").action());
  }
}
