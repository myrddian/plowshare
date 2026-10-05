package io.aeyer.plowshare.server.events;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Existing firing inbox as a transactional scheduled-event subscriber, independent of jobs. */
public interface ScheduledArrival {
  /** Copies every matching firing and applies existing queue policy; performs no external work. */
  List<Arrival> record(String event, String schedule, Instant fireAt, Instant now);

  /** Post-commit hint to start recorded work; lost hints leave durable firings for recovery. */
  void dispatch(List<Arrival> arrivals);

  /** Retries only durable queued scheduled targets; started/uncertain work is never replayed. */
  void retryWaiting();

  record Arrival(FiringRecord firing, TriggerRecord trigger) {
    public Arrival {
      Objects.requireNonNull(firing, "firing");
      if (trigger == null ? firing.trigger() != null : !trigger.name().equals(firing.trigger()))
        throw new IllegalArgumentException("scheduled arrival differs from its trigger");
    }
  }
}
