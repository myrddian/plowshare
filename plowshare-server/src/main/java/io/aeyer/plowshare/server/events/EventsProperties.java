package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.config.Live;
import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether this server fires its own schedules, whether boot recovers what a restart
 * interrupted, and how often it looks — see
 * {@code application.yml}'s {@code plowshare.events} block for the operator-facing
 * argument. {@code tick-interval} is {@link Live} on {@code
 * FetchProperties#ttlNow}'s own reasoning: an operator watching a schedule run too
 * loosely, or wanting a tighter one, over a running server rather than a redeploy.
 */
@ConfigurationProperties(prefix = "plowshare.events")
public class EventsProperties {

    private static final Logger log = LoggerFactory.getLogger(EventsProperties.class);

    static final String TICK_INTERVAL = "plowshare.events.tick-interval";

    private boolean tickerEnabled = true;
    private boolean recoverAtBoot = true;
    private Duration tickInterval = Duration.ofSeconds(15);
    private RuntimeConfig live;

    public boolean isTickerEnabled() {
        return tickerEnabled;
    }

    public void setTickerEnabled(boolean tickerEnabled) {
        this.tickerEnabled = tickerEnabled;
    }

    public boolean isRecoverAtBoot() {
        return recoverAtBoot;
    }

    public void setRecoverAtBoot(boolean recoverAtBoot) {
        this.recoverAtBoot = recoverAtBoot;
    }

    public Duration getTickInterval() {
        return tickInterval;
    }

    public void setTickInterval(Duration tickInterval) {
        this.tickInterval = tickInterval;
    }

    @Autowired(required = false)
    public void setLive(RuntimeConfig live) {
        this.live = live;
    }

    /** FetchProperties.ttlNow()'s reasoning, in full: no row answers quietly, a bad row warns. */
    @Live(TICK_INTERVAL)
    public Duration tickIntervalNow() {
        Optional<String> found = live == null ? Optional.empty() : live.get(TICK_INTERVAL);
        if (found.isEmpty()) {
            return tickInterval;
        }
        try {
            Duration parsed = Duration.parse(found.get().trim());
            return parsed.isNegative() || parsed.isZero() ? tickInterval : parsed;
        } catch (DateTimeParseException notADuration) {
            log.warn("the runtime config map holds '{}' for {}, which is not an ISO-8601 duration,"
                    + " so this server is answering with {}", found.get(), TICK_INTERVAL, tickInterval);
            return tickInterval;
        }
    }
}
