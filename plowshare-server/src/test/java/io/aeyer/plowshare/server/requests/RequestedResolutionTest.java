package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedResolutionTest {

  @Test
  void a_settlement_says_which_way_it_went() {
    assertEquals(true, RequestedResolution.accepted(true));
    assertEquals(false, RequestedResolution.accepted(false));
  }

  @Test
  void a_settlement_that_says_neither_is_refused_rather_than_defaulted() {
    // There is no default, because a settled proposal is never re-opened:
    // a guess here would be permanent.
    String said =
        assertThrows(CallerFault.class, () -> RequestedResolution.accepted(null)).getMessage();

    assertTrue(said.contains("never re-opened"), said);
  }

  @Test
  void a_settlement_names_who_decided() {
    assertEquals("enzo", RequestedResolution.by("enzo"));
  }

  @Test
  void a_settlement_nobody_signed_is_refused_blank_as_well_as_absent() {
    // Blank is what an unset form field arrives as, and a row read months
    // later cannot tell a person's decision from the curator's own without
    // it.
    assertTrue(
        assertThrows(CallerFault.class, () -> RequestedResolution.by("   "))
            .getMessage()
            .contains("who decided"));
    assertTrue(
        assertThrows(CallerFault.class, () -> RequestedResolution.by(null))
            .getMessage()
            .contains("who decided"));
  }
}
