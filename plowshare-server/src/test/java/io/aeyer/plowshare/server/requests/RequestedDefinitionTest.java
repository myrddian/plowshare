package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestedDefinition}, standing in for the two presence checks {@code
 * AgentController.define} used to hold inline before the size ceiling and the writer's own ladder
 * of refusals.
 */
class RequestedDefinitionTest {

  @Test
  void reads_an_ordinary_name() {
    assertEquals("gardener", RequestedDefinition.name("gardener"));
  }

  @Test
  void refuses_a_null_name() {
    CallerFault refused = assertThrows(CallerFault.class, () -> RequestedDefinition.name(null));
    assertEquals(
        "'name' is required to define an agent, and this request named none", refused.getMessage());
  }

  @Test
  void reads_an_ordinary_text() {
    assertEquals("You water plants.", RequestedDefinition.text("You water plants."));
  }

  @Test
  void refuses_a_null_text() {
    CallerFault refused = assertThrows(CallerFault.class, () -> RequestedDefinition.text(null));
    assertEquals(
        "'text' is required to define an agent, and this request named none", refused.getMessage());
  }
}
