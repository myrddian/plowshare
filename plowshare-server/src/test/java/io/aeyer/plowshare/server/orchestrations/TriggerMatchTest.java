package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Trigger;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TriggerMatchTest {

  private static Trigger phrase(String text) {
    return new Trigger(text, false);
  }

  private static Trigger command(String text) {
    return new Trigger(text, true);
  }

  @Test
  void a_phrase_matches_case_insensitively() {
    assertEquals(
        Optional.of(phrase("build a feature")),
        TriggerMatch.first(List.of(phrase("build a feature")), "Please BUILD A Feature for login"));
  }

  @Test
  void a_phrase_matches_only_on_word_boundaries() {
    assertEquals(
        Optional.empty(),
        TriggerMatch.first(List.of(phrase("implement")), "the implementation is slow"));
    assertEquals(
        Optional.empty(), TriggerMatch.first(List.of(phrase("implement")), "reimplement it"));
    assertTrue(
        TriggerMatch.first(List.of(phrase("implement")), "implement, then test").isPresent());
    assertTrue(TriggerMatch.first(List.of(phrase("implement")), "(implement)").isPresent());
  }

  @Test
  void a_phrase_with_regex_characters_is_matched_literally() {
    assertTrue(TriggerMatch.first(List.of(phrase("c++ port")), "do a c++ port now").isPresent());
    assertEquals(Optional.empty(), TriggerMatch.first(List.of(phrase("a.b")), "axb"));
  }

  @Test
  void a_command_matches_only_at_the_start() {
    assertTrue(
        TriggerMatch.first(List.of(command("/implement")), "  /implement the parser").isPresent());
    assertTrue(TriggerMatch.first(List.of(command("/implement")), "/implement").isPresent());
    assertEquals(
        Optional.empty(),
        TriggerMatch.first(List.of(command("/implement")), "please /implement it"));
    assertEquals(
        Optional.empty(), TriggerMatch.first(List.of(command("/implement")), "/implementation"));
  }

  @Test
  void the_first_declared_trigger_that_matches_is_named() {
    assertEquals(
        Optional.of(phrase("build")),
        TriggerMatch.first(
            List.of(phrase("ship"), phrase("build"), phrase("implement")), "implement and build"));
  }
}
