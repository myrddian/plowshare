package io.aeyer.plowshare.server.agents;

import java.util.Optional;

/** Safe restoration of an existing execution stack, independently of why it was suspended. */
public interface ContinuationFrames {
  ContinuationFrames NONE = new ContinuationFrames() {};

  default Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
    return Optional.empty();
  }

  default Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
    return Optional.empty();
  }

  default Optional<String> restorationDecision(String conversation) {
    return Optional.empty();
  }

  default int modelCalls(String conversation) {
    return 0;
  }

  default boolean awaiting(String conversation) {
    return false;
  }

  default void restored(String conversation) {}

  default void recoveryStarted(String conversation) {}
}
