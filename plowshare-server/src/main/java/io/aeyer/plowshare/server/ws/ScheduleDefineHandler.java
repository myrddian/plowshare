package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.CronSchedule;
import io.aeyer.plowshare.server.events.ScheduleStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code schedule.define} — name a schedule, replacing whatever it had. No HTTP twin: a schedule is
 * fired by this server itself, never by a caller polling it.
 */
public final class ScheduleDefineHandler implements FrameHandler {

  record Body(String schedule, String cron, String zone, String emits) {}

  private final ScheduleStore schedules;
  private final Supplier<Instant> clock;

  /**
   * The production one. No {@code Clock} bean exists in this repo; see the global constraint this
   * class is threaded to satisfy, and {@code session.SessionRegistry}'s own precedent.
   */
  public ScheduleDefineHandler(ScheduleStore schedules) {
    this(schedules, Instant::now);
  }

  /**
   * @param clock where {@code define}'s {@code now} comes from — a parameter so a test can assert
   *     on the exact instant a schedule's next fire time was computed from, rather than matching it
   *     with {@code any()}
   */
  ScheduleDefineHandler(ScheduleStore schedules, Supplier<Instant> clock) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.SCHEDULE_DEFINE);
    Body body = Payloads.as(payload, Body.class, FrameTypes.SCHEDULE_DEFINE);
    String name =
        Payloads.required(
            payload, "schedule", FrameTypes.SCHEDULE_DEFINE, "the name to define it under");
    String emits =
        Payloads.required(
            payload, "emits", FrameTypes.SCHEDULE_DEFINE, "the event name triggers listen for");
    CronSchedule cron = CronSchedule.parse(body.cron(), body.zone());
    return Outcome.ok(schedules.define(name, cron, emits, handle, clock.get()));
  }
}
