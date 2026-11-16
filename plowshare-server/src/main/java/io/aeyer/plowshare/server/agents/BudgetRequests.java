package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.approvals.BudgetExtension;
import io.aeyer.plowshare.server.approvals.RunApproval;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Durable requests for an execution's existing allowance. Implementations pin the original account,
 * allowance, stack and message owner; approval never creates another task or resets spending.
 */
public interface BudgetRequests extends ContinuationFrames {
  BudgetRequests NONE = new BudgetRequests() {};

  record Decision(String approval, boolean allowed, String text, boolean pending) {
    public Decision(String approval, boolean allowed, String text) {
      this(approval, allowed, text, false);
    }
  }

  record Answer(String state, boolean live, String note) {}

  default boolean available(Transcript transcript, String executor) {
    return false;
  }

  /** Waits without holding a model slot. Cancellation withdraws the pending question. */
  default Optional<Decision> await(
      BudgetCheckpoint checkpoint,
      Budget budget,
      TurnCap cap,
      BudgetExtension requested,
      String reason,
      BooleanSupplier cancelled) {
    return Optional.empty();
  }

  /** Acknowledges only after the decision/result was durably written to the original log. */
  default void acknowledged(String approval) {}

  /**
   * Called after existing approval authorization. Only one-time grants and denial are supported.
   */
  default Optional<Answer> answer(RunApproval approval, boolean allow, String by) {
    return answer(approval, allow, by, null);
  }

  default Optional<Answer> answer(RunApproval approval, boolean allow, String by, String reason) {
    return Optional.empty();
  }

  /** A committed approval with no live stack resumes from these original conversation frames. */
  default Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
    return Optional.empty();
  }

  default Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
    return Optional.empty();
  }

  default Optional<String> restorationDecision(String conversation) {
    return Optional.empty();
  }

  default boolean awaiting(String conversation) {
    return false;
  }

  default void restored(String conversation) {}

  default void recoveryStarted(String conversation) {}

  default void recover() {}
}
