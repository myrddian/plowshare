package io.aeyer.plowshare.server.approvals;

/** Checks the lifecycle owner before any generic approval continuation can start a turn. */
@FunctionalInterface
public interface GoalApprovalContinuations {
  GoalApprovalContinuations NONE = ignored -> false;

  /** Includes terminal Goals, whose late decisions cannot start another turn. */
  boolean ownsConversation(String conversation);
}
