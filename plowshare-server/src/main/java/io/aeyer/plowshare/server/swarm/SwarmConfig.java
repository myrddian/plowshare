package io.aeyer.plowshare.server.swarm;

import io.aeyer.plowshare.server.llm.dispatch.LlmDispatcher;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The swarm scheduler — spec 2026-09-29 §5. Built here; the board's {@code BoardConfig} hands
 * {@code JobRuntime.useScheduling} a {@link SwarmScheduling} over it, so seat runs take turns.
 */
@Configuration
@EnableConfigurationProperties(SwarmProperties.class)
public class SwarmConfig {

    /** How often a waiting run asks whether it has been cancelled. */
    static final Duration POLL = Duration.ofMillis(250);

    @Bean
    public SwarmScheduler swarmScheduler(LlmDispatcher dispatcher, SwarmProperties properties) {
        if (properties.getQuantum() < 1) {
            throw new IllegalStateException("plowshare.swarm.quantum is " + properties.getQuantum()
                    + "; a quantum is a number of steps, at least 1");
        }
        Duration waitWarning = properties.getWaitWarning();
        if (waitWarning == null || waitWarning.isZero() || waitWarning.isNegative()) {
            // Refused here rather than let through: the live read falls back to this bound value
            // whenever the map holds nothing usable, so a bad one would never be corrected at
            // runtime — null would be an unreadable key on every wait, silently standing in for
            // the scheduler's own default, and zero or less would call every wait overdue the
            // moment it began.
            throw new IllegalStateException("plowshare.swarm.wait-warning is " + waitWarning
                    + "; a wait warning is a length of time, above zero");
        }
        return new SwarmScheduler(new DispatcherPools(dispatcher), properties::quantumNow,
                properties::waitWarningNow, Clock.systemUTC(), POLL);
    }
}
