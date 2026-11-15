package io.aeyer.plowshare.server.approvals;

/** Existing execution owners resume the original root; none may create another task instance. */
public interface BudgetRequestContinuations {
  boolean busy(String root);

  /** Idempotent for queued messaging wakes. False means busy and leaves the decision retained. */
  boolean resume(BudgetRequestRepository.Request request);

  /** Finalizes a denied original request without paying for another model call. */
  void denied(BudgetRequestRepository.Request request);

  /** Recovery had already begun before interruption; settle without replaying uncertain effects. */
  void interrupted(BudgetRequestRepository.Request request, String text);

  /** A visible recovery failure; never silently substitutes a fresh task. */
  void problem(BudgetRequestRepository.Request request, String reason);
}
