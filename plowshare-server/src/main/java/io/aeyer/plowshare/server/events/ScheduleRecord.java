package io.aeyer.plowshare.server.events;

import java.time.Instant;

public record ScheduleRecord(
    String name,
    String cron,
    String zone,
    String emits,
    boolean paused,
    Instant nextFireAt,
    String definedBy) {}
