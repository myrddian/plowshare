package io.aeyer.plowshare.server.events;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The cron adapter: turns due schedules into events. One call is one pass; the
 * thread that calls it lives in EventsConfig and never starts in tests.
 */
public class Ticker {

    private static final Logger log = LoggerFactory.getLogger(Ticker.class);

    private final ScheduleStore schedules;
    private final Intake intake;

    public Ticker(ScheduleStore schedules, Intake intake) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.intake = Objects.requireNonNull(intake, "intake");
    }

    public int tick(Instant now) {
        int fired = 0;
        for (ScheduleRecord due : schedules.due(now)) {
            try {
                Instant following = CronSchedule.parse(due.cron(), due.zone()).nextAfter(now);
                if (!schedules.claim(due.name(), due.nextFireAt(), following)) {
                    continue;
                }
                intake.emit(due.emits(),
                        Map.of("schedule", due.name(), "fire_at", due.nextFireAt().toString()),
                        due.name(), due.nextFireAt());
                fired++;
            } catch (RuntimeException broken) {
                log.warn("schedule {} could not fire at {}: {}", due.name(), due.nextFireAt(),
                        broken.getMessage());
            }
        }
        return fired;
    }

    /**
     * Missed ticks are not replayed. Only fire times older than {@code grace} move, so a
     * server booting beside a running one does not swallow a tick that one is about to fire.
     */
    public int rollForward(Instant now, Duration grace) {
        return schedules.rollForward(now.minus(grace), stale -> {
            try {
                return CronSchedule.parse(stale.cron(), stale.zone()).nextAfter(now);
            } catch (RuntimeException broken) {
                log.warn("schedule {} cannot be rolled forward: {}", stale.name(), broken.getMessage());
                return stale.nextFireAt();
            }
        });
    }
}
