package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Where every event arrives, whoever raised it: the ticker, event.fire, a future adapter. */
public class Intake implements ScheduledArrival {

  private static final Logger log = LoggerFactory.getLogger(Intake.class);

  private final TriggerStore triggers;
  private final FiringStore firings;
  private final Dispatcher dispatcher;
  private final Supplier<Instant> clock;

  public Intake(
      TriggerStore triggers, FiringStore firings, Dispatcher dispatcher, Supplier<Instant> clock) {
    this.triggers = Objects.requireNonNull(triggers, "triggers");
    this.firings = Objects.requireNonNull(firings, "firings");
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public List<FiringRecord> emit(String event, EventPayload data) {
    return emit(event, data, null, null);
  }

  public List<FiringRecord> emit(String event, EventPayload data, String schedule, Instant fireAt) {
    if (event == null || event.isBlank()) {
      throw new CallerFault("an event needs a name; triggers listen for it by that name");
    }
    try {
      event = EventPayload.identity(event, "event");
    } catch (IllegalArgumentException invalid) {
      throw new CallerFault("event needs a bounded identity without padding or control characters");
    }
    data = data == null ? new EventPayload.Empty() : data;
    EventPayloadCodec.require(event, null, schedule, fireAt, data);
    Instant at = clock.get();
    List<TriggerRecord> listening = triggers.listening(event);
    List<FiringRecord> created = new ArrayList<>();
    if (listening.isEmpty()) {
      firings.arrive(event, data, schedule, fireAt, null, at).ifPresent(created::add);
      return created;
    }
    // Every firing is recorded before any is dispatched, and each dispatch is its own try: a
    // tick is claimed once, so a trigger whose dispatch throws must not cost the triggers
    // after it their firing. What was recorded and not started is drained later like any
    // waiting firing.
    List<TriggerRecord> arrivedFor = new ArrayList<>();
    for (TriggerRecord trigger : listening) {
      firings
          .arrive(event, data, schedule, fireAt, trigger, at)
          .ifPresent(
              firing -> {
                created.add(firing);
                arrivedFor.add(trigger);
              });
    }
    for (int i = 0; i < created.size(); i++) {
      FiringRecord firing = created.get(i);
      TriggerRecord trigger = arrivedFor.get(i);
      try {
        dispatcher.dispatch(firing, trigger);
      } catch (RuntimeException undispatched) {
        log.warn(
            "firing {} of trigger {} was recorded but could not be dispatched",
            firing.id(),
            trigger.name(),
            undispatched);
      }
    }
    return created;
  }

  @Override
  public List<ScheduledArrival.Arrival> record(
      String event, String schedule, Instant fireAt, Instant now) {
    EventPayload.Scheduled payload = new EventPayload.Scheduled(schedule, fireAt);
    EventPayloadCodec.require(event, null, schedule, fireAt, payload);
    Objects.requireNonNull(now, "arrival time");
    List<TriggerRecord> listening = triggers.listening(event);
    var arrivals = new ArrayList<ScheduledArrival.Arrival>();
    if (listening.isEmpty()) {
      firings
          .arrive(event, payload, schedule, fireAt, null, now)
          .ifPresent(firing -> arrivals.add(new ScheduledArrival.Arrival(firing, null)));
    } else {
      firings.lockScheduledQueues(listening.stream().map(TriggerRecord::name).toList());
      for (var trigger : listening)
        firings
            .arrive(event, payload, schedule, fireAt, trigger, now)
            .ifPresent(firing -> arrivals.add(new ScheduledArrival.Arrival(firing, trigger)));
      // Apply the existing supersession policy before acknowledging the publication, even if the
      // post-commit notification is lost. No job can start from record().
      for (var arrival : arrivals)
        firings.supersedeBeyond(
            arrival.trigger().name(), arrival.trigger().queueCap(), arrival.firing().id());
    }
    return List.copyOf(arrivals);
  }

  @Override
  public void retryWaiting() {
    for (String target : firings.scheduledTargetsWaiting()) {
      try {
        dispatcher.drain(target);
      } catch (RuntimeException unavailable) {
        log.warn(
            "scheduled target {} remains queued after its recovery hint failed",
            target,
            unavailable);
      }
    }
  }

  @Override
  public void dispatch(List<ScheduledArrival.Arrival> arrivals) {
    for (var arrival : List.copyOf(arrivals)) {
      if (arrival.trigger() == null) continue;
      try {
        dispatcher.dispatch(arrival.firing(), arrival.trigger());
      } catch (RuntimeException unavailable) {
        // Publication and every firing already committed. A lost hint must not report the
        // occurrence as unaccepted or retry its effects; ordinary firing recovery drains it.
        log.warn(
            "scheduled firing {} was recorded but could not be dispatched",
            arrival.firing().id(),
            unavailable);
      }
    }
  }
}
