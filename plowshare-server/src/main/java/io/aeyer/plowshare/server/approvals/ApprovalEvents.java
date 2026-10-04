package io.aeyer.plowshare.server.approvals;

/**
 * Told when a command is put to a person and when that question is answered — the orchestration
 * record's source for approvals (spec 2026-09-28 §2). Must not throw; {@link RunApprovalStore}
 * guards it anyway, since the question has already been written.
 */
public interface ApprovalEvents {

  ApprovalEvents NONE = new ApprovalEvents() {};

  default void asked(RunApproval approval) {}

  /**
   * @param approval the approval as the answer left it: {@code allowed} or {@code denied}
   */
  default void answered(RunApproval approval) {}
}
