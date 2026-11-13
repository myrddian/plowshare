package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class TurnEligibilityTest {
  @Test
  void durable_holds_do_not_probe_occupancy_or_fetch_resource_state() {
    var occupancy = mock(BooleanSupplier.class);
    var resource = mock(BooleanSupplier.class);
    for (var hold :
        new TurnEligibility.Reason[] {
          TurnEligibility.Reason.CHILD,
          TurnEligibility.Reason.ANSWER,
          TurnEligibility.Reason.TERMINAL
        }) {
      var gate = TurnEligibility.decide(hold, occupancy, resource);
      assertFalse(gate.eligible());
      assertEquals(hold, gate.reason());
    }
    verifyNoInteractions(occupancy, resource);
  }

  @Test
  void an_active_turn_does_not_query_resources_and_only_an_idle_unblocked_turn_is_eligible() {
    var resource = mock(BooleanSupplier.class);
    assertEquals(
        TurnEligibility.Reason.OCCUPIED,
        TurnEligibility.decide(TurnEligibility.Reason.READY, () -> true, resource).reason());
    verifyNoInteractions(resource);
    when(resource.getAsBoolean()).thenReturn(true);
    assertEquals(
        TurnEligibility.Reason.RESOURCE,
        TurnEligibility.decide(TurnEligibility.Reason.READY, () -> false, resource).reason());
    when(resource.getAsBoolean()).thenReturn(false);
    assertTrue(
        TurnEligibility.decide(TurnEligibility.Reason.READY, () -> false, resource).eligible());
  }
}
