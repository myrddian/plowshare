package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedBudgetTest {

  @Test
  void takes_the_operators_default_when_the_request_names_no_limit() {
    assertEquals(Budget.of(7).limit(), RequestedBudget.in(null, null, 7).limit());
  }

  @Test
  void takes_the_number_the_request_named() {
    assertEquals(Budget.of(3).limit(), RequestedBudget.in(3, null, 7).limit());
  }

  @Test
  void lifts_the_ceiling_when_the_request_asks_for_no_budget() {
    assertFalse(Budget.none().capped());
    assertFalse(RequestedBudget.in(null, true, 7).capped());
  }

  @Test
  void refuses_a_request_that_asks_for_both_a_cap_and_no_ceiling() {
    // The two mean opposite things and only one can decide. The message says
    // what to send instead, and ends "Nothing was opened."
    CallerFault refused = assertThrows(CallerFault.class, () -> RequestedBudget.in(5, true, 7));
    assertTrue(refused.getMessage().contains("Nothing was opened"), refused.getMessage());
  }

  @Test
  void refuses_a_number_Budget_itself_rejects() {
    // Budget.of throws CallerFault itself for a limit with nothing to
    // spend; this pins that RequestedBudget.in lets it straight through
    // rather than catching and restating it.
    assertThrows(CallerFault.class, () -> RequestedBudget.in(0, null, 7));
  }
}
