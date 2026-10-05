package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.Callers;
import io.aeyer.plowshare.server.events.FiringStore;
import io.aeyer.plowshare.server.events.Intake;
import io.aeyer.plowshare.server.events.ScheduleReader;
import io.aeyer.plowshare.server.events.ScheduleStore;
import io.aeyer.plowshare.server.events.TriggerStore;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Schedules, triggers, firings, and raising an event by hand. Frames only: no REST twin. */
@Component
public class EventFrames implements FrameArea {

  private final ScheduleStore schedules;
  private final TriggerStore triggers;
  private final FiringStore firings;
  private final Intake intake;
  private final Callers callers;
  private final ScheduleReader reader;
  private final Supplier<Instant> clock;

  /**
   * The production constructor — {@code @Autowired} names it explicitly because a second,
   * package-private constructor below exists for tests, and Spring must not have to guess between
   * the two. No {@code Clock} bean exists in this repo, so {@code Instant::now} is threaded here
   * rather than resolved from the container, on {@code events.EventsConfig}'s own precedent for
   * {@code Inbox}/{@code Dispatcher}/{@code Intake}.
   */
  @Autowired
  public EventFrames(
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      Intake intake,
      Callers callers,
      ScheduleReader reader) {
    this(schedules, triggers, firings, intake, callers, reader, Instant::now);
  }

  /**
   * @param clock where {@code schedule.define}'s {@code now} comes from — a parameter so a test can
   *     assert on the exact instant it was computed from
   */
  EventFrames(
      ScheduleStore schedules,
      TriggerStore triggers,
      FiringStore firings,
      Intake intake,
      Callers callers,
      ScheduleReader reader,
      Supplier<Instant> clock) {
    this.schedules = Objects.requireNonNull(schedules, "schedules");
    this.triggers = Objects.requireNonNull(triggers, "triggers");
    this.firings = Objects.requireNonNull(firings, "firings");
    this.intake = Objects.requireNonNull(intake, "intake");
    this.callers = Objects.requireNonNull(callers, "callers");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private org.springframework.beans.factory.ObjectProvider<
          io.aeyer.plowshare.server.events.ScheduleDefinitions>
      definitions;

  @Autowired
  public void useScheduleDefinitions(
      org.springframework.beans.factory.ObjectProvider<
              io.aeyer.plowshare.server.events.ScheduleDefinitions>
          definitions) {
    this.definitions = definitions;
  }

  private FrameHandler managedControl(String kind, boolean pause, FrameHandler legacy) {
    return (payload, asking) -> {
      var service = definitions == null ? null : definitions.getIfAvailable();
      String name =
          Payloads.required(
              payload, kind, kind + "." + (pause ? "pause" : "forget"), "the displayed name");
      String account = asking.requireHandle(kind + "." + (pause ? "pause" : "forget"));
      if (service != null && service.managed(name, account).isPresent()) {
        if (pause) {
          Object value = payload.get("paused");
          if (!(value instanceof Boolean paused))
            throw new io.aeyer.plowshare.server.faults.CallerFault("paused must be true or false");
          service.pause(name, paused, account);
        } else service.forget(name, account);
        return new io.aeyer.plowshare.protocol.frames.Outcome(
            io.aeyer.plowshare.protocol.frames.Code.NO_CONTENT, null, null);
      }
      return legacy.handle(payload, asking);
    };
  }

  @Override
  public Map<String, FrameHandler> frames() {
    return Map.ofEntries(
        Map.entry(FrameTypes.SCHEDULE_DEFINE, new ScheduleDefineHandler(schedules, clock)),
        Map.entry(FrameTypes.SCHEDULE_LIST, new ScheduleListHandler(schedules)),
        Map.entry(
            FrameTypes.SCHEDULE_PAUSE,
            managedControl("schedule", true, new SchedulePauseHandler(schedules))),
        Map.entry(
            FrameTypes.SCHEDULE_FORGET,
            managedControl("schedule", false, new ScheduleForgetHandler(schedules))),
        Map.entry(FrameTypes.SCHEDULE_READ, new ScheduleReadHandler(reader)),
        Map.entry(FrameTypes.TRIGGER_DEFINE, new TriggerDefineHandler(triggers, callers)),
        Map.entry(FrameTypes.TRIGGER_LIST, new TriggerListHandler(triggers)),
        Map.entry(
            FrameTypes.TRIGGER_PAUSE,
            managedControl("trigger", true, new TriggerPauseHandler(triggers, firings))),
        Map.entry(
            FrameTypes.TRIGGER_FORGET,
            managedControl("trigger", false, new TriggerForgetHandler(triggers, firings))),
        Map.entry(FrameTypes.EVENT_FIRE, new EventFireHandler(intake, firings)),
        Map.entry(FrameTypes.FIRING_LIST, new FiringListHandler(firings)));
  }
}
