package io.aeyer.plowshare.server.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The bound values the scheduler is built with, refused at boot when they cannot mean anything.
 * The refusal comes before the dispatcher is touched, so no dispatcher is needed to see it.
 */
class SwarmConfigTest {

    @Test
    void a_quantum_below_one_is_refused_at_boot() {
        SwarmProperties properties = new SwarmProperties();
        properties.setQuantum(0);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new SwarmConfig().swarmScheduler(null, properties));
        assertEquals("plowshare.swarm.quantum is 0; a quantum is a number of steps, at least 1",
                refused.getMessage());
    }

    @Test
    void a_wait_warning_that_is_absent_zero_or_negative_is_refused_at_boot() {
        for (Duration bad : Arrays.asList(null, Duration.ZERO, Duration.ofMinutes(-1))) {
            SwarmProperties properties = new SwarmProperties();
            properties.setWaitWarning(bad);
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> new SwarmConfig().swarmScheduler(null, properties), String.valueOf(bad));
            assertEquals("plowshare.swarm.wait-warning is " + bad
                    + "; a wait warning is a length of time, above zero", refused.getMessage());
        }
    }
}
