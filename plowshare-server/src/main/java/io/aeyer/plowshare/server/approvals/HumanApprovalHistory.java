package io.aeyer.plowshare.server.approvals;

/**
 * Exact, owner-scoped human evidence. Automated reviews and imported legacy grants are excluded.
 */
public interface HumanApprovalHistory {
  boolean eligible(ApprovalIntent intent);

  /**
   * Records only a verified owner answer to this retained call; retry is idempotent by approval.
   */
  void remember(ApprovalIntent intent, String approval);
}
