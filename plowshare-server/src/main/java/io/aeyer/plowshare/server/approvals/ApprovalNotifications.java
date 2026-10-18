package io.aeyer.plowshare.server.approvals;

/** Human notification only: never grants permission, continues a model or executes a command. */
public interface ApprovalNotifications {
  /**
   * Ensures an asked approval reaches its owning account's Inbox. Repeated delivery is safe.
   * Returns false while the requesting turn is closing or no account can be resolved; the approval
   * remains pending for recovery. Missing or already settled requests need no new notice. A project
   * mismatch is refused.
   */
  boolean requested(String approval, long projectId);

  /** Retire the Inbox question only after its authoritative approval row has settled. */
  void resolved(String approval, long projectId);
}
