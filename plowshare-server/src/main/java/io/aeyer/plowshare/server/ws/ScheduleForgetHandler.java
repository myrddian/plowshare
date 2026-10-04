package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ScheduleStore;
import java.util.Map;
import java.util.Objects;

/** {@code schedule.forget} — delete a schedule this account defined. */
public final class ScheduleForgetHandler implements FrameHandler {

  private final ScheduleStore schedules;

  public ScheduleForgetHandler(ScheduleStore schedules) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.SCHEDULE_FORGET);
    schedules.forget(
        Payloads.required(
            payload,
            "schedule",
            FrameTypes.SCHEDULE_FORGET,
            "the name schedule.list answers with. Nothing was deleted."),
        handle);
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
