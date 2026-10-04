package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestedLimitsChange}, standing in for the "names no limit to move" check {@code
 * AgentController.limits} used to hold inline, ahead of {@link
 * io.aeyer.plowshare.server.agents.Job#limits()} ever being consulted.
 */
class RequestedLimitsChangeTest {

  @Test
  void a_body_naming_a_turn_cap_is_read_through() {
    TurnCap wanted = RequestedLimitsChange.wanted(3, null, null, "this run");
    assertEquals(3, wanted.turns());
    assertEquals(true, wanted.capped());
  }

  @Test
  void a_body_naming_only_a_budget_change_names_no_turn_cap() {
    assertNull(RequestedLimitsChange.wanted(null, null, 7, "this run"));
  }

  @Test
  void a_body_naming_neither_is_refused() {
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> RequestedLimitsChange.wanted(null, null, null, "this run"));
    assertEquals(
        "this body names no limit to move. Send 'maxTurns' or 'noTurnCap' to"
            + " change how many turns this run may take, or 'maxModelCalls' to change what"
            + " it and everything it delegates to may spend. Nothing was changed.",
        refused.getMessage());
  }

  @Test
  void the_turn_cap_conflict_is_still_refused_first() {
    // RequestedTurnCap.in runs before the no-limit-named check -- a body
    // naming both maxTurns and noTurnCap must see that refusal, not this
    // one, even though it also names no maxModelCalls.
    CallerFault refused =
        assertThrows(
            CallerFault.class, () -> RequestedLimitsChange.wanted(3, true, null, "this run"));
    assertEquals(
        "this body says both 'maxTurns' and 'noTurnCap', and only one of them can"
            + " decide how many turns this run may take. Send a number to cap it, send"
            + " 'noTurnCap' to let it run, and leave both out to take the cap from the level"
            + " above. Nothing was changed.",
        refused.getMessage());
  }
}
