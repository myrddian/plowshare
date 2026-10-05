package io.aeyer.plowshare.server.events;

import java.time.Instant;

/**
 * Publishes one selected occurrence and durably admits its compatibility subscriber before
 * advancing the schedule. False means the guarded claim lost, with no publication or firing.
 * Implementations preserve source authority and must not start jobs inside a transaction.
 */
public interface ScheduledPublication {
  boolean publish(ScheduleRecord selected, Instant following, Instant now);

  /** Bounded drain of already admitted scheduled work after a lost post-commit notification. */
  void recover();

  /** Composition-supplied check that no caller transaction can delay commit beyond dispatch. */
  @FunctionalInterface
  interface Boundary {
    void requireOutsideTransaction();
  }
}
