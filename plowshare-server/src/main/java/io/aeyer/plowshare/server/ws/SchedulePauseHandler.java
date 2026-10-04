package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Map;
import java.util.Objects;

/** {@code schedule.pause} — pause or resume a schedule this account defined. */
public final class SchedulePauseHandler implements FrameHandler {

  record Body(String schedule, Boolean paused) {}

  private final ScheduleStore schedules;

  public SchedulePauseHandler(ScheduleStore schedules) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String handle = asking.requireHandle(FrameTypes.SCHEDULE_PAUSE);
    String name =
        Payloads.required(
            payload, "schedule", FrameTypes.SCHEDULE_PAUSE, "the name schedule.list answers with");
    Body body = Payloads.as(payload, Body.class, FrameTypes.SCHEDULE_PAUSE);
    if (body.paused() == null) {
      throw new CallerFault(
          FrameTypes.SCHEDULE_PAUSE + " needs 'paused' as true or false. Nothing was changed.");
    }
    schedules.pause(name, body.paused(), handle);
    return new Outcome(Code.NO_CONTENT, null, null);
  }
}
