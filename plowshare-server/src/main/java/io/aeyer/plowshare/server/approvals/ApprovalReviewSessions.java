package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.access.OperationAuthority;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.events.AgentRunner.ScheduledTask;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Creates an account review session within an existing retained run, never a second runtime. */
public interface ApprovalReviewSessions {
  record Context(
      String owner,
      String conversation,
      String authenticatedTask,
      ScheduledTask scheduledTask,
      OperationAuthority.Admission operation,
      Budget budget,
      UsageAttribution usage,
      BooleanSupplier cancelled,
      Runnable validity,
      BiConsumer<ApprovalIntent, ApprovalReviews.Decision> audit) {}

  /**
   * Machine/system tasks without a personal user retain their existing owning approval contract.
   */
  Optional<OperationApprovalReview> operations(Context context);
}
