package io.aeyer.plowshare.server.requests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.aeyer.plowshare.server.archive.ConversationLifecycle;
import io.aeyer.plowshare.server.faults.CallerFault;
import org.junit.jupiter.api.Test;

class RequestedLifecycleTest {

    @Test
    void reads_a_state_this_server_writes() {
        assertEquals(ConversationLifecycle.ACTIVE, RequestedLifecycle.in("active"));
    }

    @Test
    void treats_absence_as_the_state_a_listing_shows_by_default() {
        // The endpoint's filter is optional, and its default is not "no filter"
        // but "the state a person works in".
        assertEquals(ConversationLifecycle.ACTIVE, RequestedLifecycle.in(null));
        assertEquals(ConversationLifecycle.ACTIVE, RequestedLifecycle.in("  "));
    }

    @Test
    void refuses_a_state_nobody_writes_as_the_callers_fault() {
        // And as a CallerFault rather than the IllegalArgumentException the
        // domain type raises: an unknown spelling is something the caller can
        // correct by sending a different one.
        CallerFault refused =
                assertThrows(CallerFault.class, () -> RequestedLifecycle.in("nonsense"));
        assertEquals(
                assertThrows(IllegalArgumentException.class,
                        () -> ConversationLifecycle.of("nonsense")).getMessage(),
                refused.getMessage());
    }
}
