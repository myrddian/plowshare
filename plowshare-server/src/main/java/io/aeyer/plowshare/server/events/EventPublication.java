package io.aeyer.plowshare.server.events;

import java.util.List;

/**
 * Publishes one hand-raised event and durably records its subscriber's firings in the same
 * transaction.
 *
 * <p>The fired-event counterpart of {@link ScheduledPublication}. Implementations preserve the
 * caller's authority, validate the event before anything is appended, and must not start jobs
 * inside a transaction.
 */
public interface EventPublication {

  /**
   * Raises {@code event} on behalf of {@code actor} and answers the firings it produced.
   *
   * <p>The answer is each firing as it arrived, before dispatch moved it, which is the shape {@code
   * event.fire} has always returned.
   *
   * @param actor the authenticated account raising the event, used to attribute the publication
   *     without naming the account in a log the whole audience can read
   * @throws io.aeyer.plowshare.server.faults.CallerFault if the event is unnamed, or its name or
   *     data fails the family's validation — refused before any publication exists
   */
  List<FiringRecord> fire(String event, EventPayload data, String actor);

  /** Composition-supplied check that no caller transaction can delay commit beyond dispatch. */
  @FunctionalInterface
  interface Boundary {
    void requireOutsideTransaction();
  }
}
