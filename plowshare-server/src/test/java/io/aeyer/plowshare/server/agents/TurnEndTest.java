package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * First-wins, except for a soft request: rule 1's "your turn ends here" (spec 2026-09-27 §2) is the
 * weakest reason a turn can end, and any other request in the same batch — an approval, a question,
 * a finish — is one a person or a caller has to see, so it takes the ending instead.
 */
class TurnEndTest {

  @Test
  void a_hard_request_after_a_soft_one_replaces_it() {
    TurnEnd end = new TurnEnd();

    assertTrue(end.requestSoftly(Outcome.Ending.ANSWERED, "is working"));
    assertTrue(end.request(Outcome.Ending.AWAITING, "Approve running pytest?"));

    assertEquals(
        new TurnEnd.Requested(Outcome.Ending.AWAITING, "Approve running pytest?"),
        end.requested().orElseThrow());
  }

  @Test
  void a_soft_request_never_replaces_one_already_made() {
    TurnEnd end = new TurnEnd();

    assertTrue(end.request(Outcome.Ending.ANSWERED, "the result"));
    assertFalse(end.requestSoftly(Outcome.Ending.ANSWERED, "is working"));

    assertEquals(
        new TurnEnd.Requested(Outcome.Ending.ANSWERED, "the result"),
        end.requested().orElseThrow());
  }

  @Test
  void a_second_soft_request_does_not_replace_the_first() {
    TurnEnd end = new TurnEnd();

    assertTrue(end.requestSoftly(Outcome.Ending.ANSWERED, "the first is working"));
    assertFalse(end.requestSoftly(Outcome.Ending.ANSWERED, "the second is working"));

    assertEquals("the first is working", end.requested().orElseThrow().text());
  }

  @Test
  void a_soft_request_alone_is_the_ending() {
    TurnEnd end = new TurnEnd();

    assertTrue(end.requestSoftly(Outcome.Ending.ANSWERED, "is working"));

    assertEquals(
        new TurnEnd.Requested(Outcome.Ending.ANSWERED, "is working"),
        end.requested().orElseThrow());
  }

  @Test
  void two_hard_requests_are_still_first_wins() {
    TurnEnd end = new TurnEnd();

    assertTrue(end.request(Outcome.Ending.AWAITING, "Which database?"));
    assertFalse(end.request(Outcome.Ending.AWAITING, "Approve running pytest?"));

    assertEquals("Which database?", end.requested().orElseThrow().text());
  }

  @Test
  void a_soft_request_is_held_to_the_same_endings_and_text() {
    TurnEnd end = new TurnEnd();

    assertThrows(
        IllegalArgumentException.class, () -> end.requestSoftly(Outcome.Ending.CANCELLED, "no"));
    assertThrows(
        IllegalArgumentException.class, () -> end.requestSoftly(Outcome.Ending.ANSWERED, " "));
    assertTrue(end.requested().isEmpty());
  }
}
