package io.aeyer.plowshare.protocol.transport;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConcurrentDispatchTest {
  @Test
  void selection_confirms_exact_mode_and_portable_bounds() {
    var selected = new ConcurrentDispatch.Selected();
    assertEquals("concurrent-v1", selected.mode());
    assertEquals(64, selected.maxPending());
    assertEquals(128, selected.maxIdBytes());
    assertThrows(IllegalArgumentException.class, () -> new ConcurrentDispatch.Selection("future"));
  }

  @Test
  void correlation_bounds_are_utf8_bytes_and_do_not_normalize_identity() {
    ConcurrentDispatch.requireId("x".repeat(128));
    ConcurrentDispatch.requireId("é".repeat(64));
    for (String invalid :
        new String[] {"", "  ", " x", "x ", "x\n", "x\u0080", "x".repeat(129), "é".repeat(65)})
      assertThrows(IllegalArgumentException.class, () -> ConcurrentDispatch.requireId(invalid));
    assertThrows(IllegalArgumentException.class, () -> ConcurrentDispatch.requireId(null));
  }
}
