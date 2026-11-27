package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.server.events.FiringRecord.GoalSchedule;
import io.aeyer.plowshare.server.messaging.MessageService.Instance;
import io.aeyer.plowshare.server.messaging.MessageService.Route;

/** Routes ordinary, already-authorized sends into their dedicated Goal Agent inbox. */
@FunctionalInterface
public interface GoalMessageInbox {
  GoalMessageInbox NONE = (instance, message) -> false;

  /** Joins the send transaction. Returns false for a regular Messaging instance. */
  boolean stage(Instance instance, String message);

  /**
   * Accept the exact final reply to a Goal question under Messaging's mailbox and request locks.
   * The Goal owner checks its live wait and consumes the receipt in this same transaction.
   */
  default boolean stageGoalAnswer(Instance instance, Route answer) {
    return false;
  }

  /** Joins the send transaction. Refuses invalid scheduled input without a fallback send. */
  default void stageScheduled(Instance instance, String message, GoalSchedule firing) {
    throw new MessageRefused("The Goal schedule is unavailable");
  }

  /** True for a dedicated destination, with ended goals refused during schedule authoring. */
  default boolean scheduleTarget(Instance instance) {
    return false;
  }

  default boolean owns(String instance) {
    return false;
  }
}
