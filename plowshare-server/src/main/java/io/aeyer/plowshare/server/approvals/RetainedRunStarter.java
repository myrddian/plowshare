package io.aeyer.plowshare.server.approvals;

/** Starts the original root owner after a process interruption; runtime resumes its tool first. */
@FunctionalInterface
public interface RetainedRunStarter {
  /** Returns the continuation job ID; must retain the call's account, session and conversation. */
  String start(RunApproval approval, SuspendedRunCalls.Call call, String audit);
}
