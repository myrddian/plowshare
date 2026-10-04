package io.aeyer.plowshare.protocol;

import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryTest {

  /**
   * The four states are the whole lifecycle, and two of them are not failures: `cold` is still
   * true, merely unused, and `superseded` is a tombstone whose reason is what stops a future agent
   * relearning the stale fact. Neither is ever deleted.
   */
  @Test
  void there_are_exactly_four_states() {
    assertEquals(
        Set.of("active", "superseded", "invalidated", "cold"),
        Arrays.stream(MemoryState.values()).map(MemoryState::wireName).collect(toSet()));
  }

  /**
   * Global is the absence of a project, not a project called "global" — so no project can ever be
   * named in a way that silently becomes the global tier.
   */
  @Test
  void a_home_without_a_project_is_global() {
    assertTrue(Home.global().isGlobal());
    assertFalse(Home.of("excalibur").isGlobal());
  }

  @Test
  void a_memory_is_active_and_unused_when_formed() {
    Memory m =
        Memory.formed(
            "mem_01",
            "The retry budget is 4",
            "Calling the payments API",
            "Four attempts since the timeout change.",
            new Provenance(Instant.EPOCH, "probe", "test"),
            Home.global());
    assertEquals(MemoryState.ACTIVE, m.state());
    assertEquals(0, m.uses());
    assertNull(m.lastUsed());
    assertNull(m.supersededBy());
  }
}
