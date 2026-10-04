package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestedTask}, standing in for the presence check {@code AgentController.run} used to
 * hold inline.
 */
class RequestedTaskTest {

  @Test
  void reads_an_ordinary_task() {
    assertEquals("water the plants", RequestedTask.in("water the plants", "gardener"));
  }

  @Test
  void refuses_a_null_task_naming_the_agent() {
    CallerFault refused = assertThrows(CallerFault.class, () -> RequestedTask.in(null, "gardener"));
    assertEquals(
        "the agent 'gardener' was given no task to do; 'task' says what it is"
            + " being asked for, in prose",
        refused.getMessage());
  }

  @Test
  void refuses_a_blank_task_naming_the_agent() {
    CallerFault refused =
        assertThrows(CallerFault.class, () -> RequestedTask.in("   ", "gardener"));
    assertEquals(
        "the agent 'gardener' was given no task to do; 'task' says what it is"
            + " being asked for, in prose",
        refused.getMessage());
  }
}
