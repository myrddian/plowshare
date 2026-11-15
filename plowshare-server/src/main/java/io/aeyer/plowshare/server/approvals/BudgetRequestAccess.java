package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.allowances.AllowanceId;

/** Current execution authorization, independent of the account that approves spending. */
public interface BudgetRequestAccess {
  /**
   * Validates the original account, project and frame ancestry; returns the retained project ID.
   */
  long require(BudgetCheckpoint checkpoint, AllowanceId allowance);

  /** Revalidates work authority before a committed grant is used after interruption. */
  void requireRecovery(BudgetRequestRepository.Request request);
}
