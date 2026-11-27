package io.aeyer.plowshare.server.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.UUID;

public record ScheduleRecord(
    String name,
    String cron,
    String zone,
    String emits,
    boolean paused,
    Instant nextFireAt,
    String definedBy,
    @JsonIgnore UUID fileGeneration) {
  public ScheduleRecord(
      String name,
      String cron,
      String zone,
      String emits,
      boolean paused,
      Instant nextFireAt,
      String definedBy) {
    this(name, cron, zone, emits, paused, nextFireAt, definedBy, null);
  }
}
