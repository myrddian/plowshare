package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ScheduleStore;
import java.util.Map;
import java.util.Objects;

/** {@code schedule.list} — every schedule, with its next fire time. */
public final class ScheduleListHandler implements FrameHandler {

  private final ScheduleStore schedules;

  public ScheduleListHandler(ScheduleStore schedules) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    return Outcome.ok(schedules.list());
  }
}
