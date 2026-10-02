package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

    @Test
    void the_next_fire_time_is_strictly_after_the_instant_given() {
        CronSchedule nine = CronSchedule.parse("0 0 9 * * *", "UTC");
        assertEquals(Instant.parse("2026-09-14T09:00:00Z"),
                nine.nextAfter(Instant.parse("2026-09-13T09:00:00Z")));
    }

    @Test
    void the_zone_decides_the_wall_clock() {
        CronSchedule nine = CronSchedule.parse("0 0 9 * * *", "Europe/London");
        assertEquals(Instant.parse("2026-09-13T08:00:00Z"),
                nine.nextAfter(Instant.parse("2026-09-13T00:00:00Z")));
    }

    @Test
    void a_wall_clock_time_that_does_not_exist_on_a_dst_change_is_skipped_forward() {
        // 2026-03-29 01:30 does not exist in Europe/London.
        CronSchedule half = CronSchedule.parse("0 30 1 * * *", "Europe/London");
        Instant next = half.nextAfter(Instant.parse("2026-03-28T23:00:00Z"));
        assertTrue(next.isAfter(Instant.parse("2026-03-28T23:00:00Z")));
    }

    @Test
    void an_unparseable_cron_is_refused_naming_why() {
        CallerFault refused = assertThrows(CallerFault.class,
                () -> CronSchedule.parse("every morning", "UTC"));
        assertTrue(refused.getMessage().contains("every morning"), refused.getMessage());
    }

    @Test
    void an_unknown_zone_is_refused() {
        assertThrows(CallerFault.class, () -> CronSchedule.parse("0 0 9 * * *", "Mars/Olympus"));
    }
}
