package io.aeyer.plowshare.server.approvals;

import java.util.Optional;

/** Routes an already recorded consent decision back to its retained execution owner. */
public interface RunApprovalContinuations {
  /** A live stack or acknowledged result needs no new job. Busy never revokes the decision. */
  record Resumed(String job, boolean busy, String note) {}

  /** A recovery that cannot restore its execution owner must remain visible to that account. */
  record RecoveryProblem(String approval, String conversation, String owner, String note) {}

  /**
   * Empty only for approvals without a retained tool call, such as orchestration acceptance sets.
   */
  Optional<Resumed> resume(String approval);

  /** Reconcile answered calls after a process restart or a temporarily busy conversation. */
  void recover();
}
