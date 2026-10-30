package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The frame-boundary half of ADR 0042's schedule refusal. It lives here rather than beside the rest
 * of the addressability inventory because {@code ScheduleListHandler.project} is package-private
 * static decoding, and reaching it from another package would mean either widening it or using
 * reflection — both of which would weaken what the test asserts.
 *
 * <p>No collaborator at all: this is a pure decode of a transport value.
 */
class ContainedScheduleSelectionTest {
  @Test
  void scheduleSelectionRefusesAContainedProject() {
    String child = "contained:" + UUID.randomUUID();
    var refusal =
        assertThrows(
            CallerFault.class, () -> ScheduleListHandler.project(Map.of("project", child)));
    assertTrue(refusal.getMessage().contains("ordinary"), refusal.getMessage());
    // Case-insensitively, because the SQL exclusions are case-sensitive LIKE and a project spelled
    // this way would have been mapped to an ordinary scope had one ever been created.
    assertThrows(
        CallerFault.class,
        () -> ScheduleListHandler.project(Map.of("project", "Contained:anything")));
    // An ordinary selection still decodes, and an absent one is still absent.
    assertEquals("parent", ScheduleListHandler.project(Map.of("project", "parent")));
    assertNull(ScheduleListHandler.project(Map.of()));
  }
}
