package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedHomeTest {

  @Test
  void reads_a_named_project() {
    assertEquals(Home.of("pay"), RequestedHome.in("pay"));
  }

  @Test
  void treats_a_null_project_as_the_global_home() {
    // Absent means global, and that is a decision rather than a default:
    // an endpoint that took no project would otherwise have to refuse.
    assertEquals(Home.global(), RequestedHome.in(null));
  }

  @Test
  void refuses_a_blank_project_as_the_callers_fault() {
    // Home lives in plowshare-protocol and cannot depend on the server, so
    // it raises IllegalArgumentException and this is where that becomes a
    // fault with a status behind it.
    assertThrows(CallerFault.class, () -> RequestedHome.in("   "));
  }

  @Test
  void refuses_an_empty_project_as_the_callers_fault() {
    // The empty string is what an omitted 'project' field arrives as, so
    // this is the case a real caller actually sends, unlike "   " above.
    assertThrows(CallerFault.class, () -> RequestedHome.in(""));
  }
}
