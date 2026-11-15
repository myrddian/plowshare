package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import java.util.List;
import java.util.Optional;

/** Atomic approval, additive allowance grant and retained continuation ownership. */
public interface BudgetRequestRepository {
  record Request(
      String approval,
      String root,
      BudgetCheckpoint checkpoint,
      BudgetExtension extension,
      String state,
      String answeredBy,
      AllowanceSnapshot allowance,
      boolean acknowledged,
      String recovery) {}

  /** One open request per allowance; retries reuse the checkpoint rather than minting work. */
  Request ask(
      long project,
      BudgetCheckpoint checkpoint,
      AllowanceId allowance,
      BudgetExtension extension,
      String reason);

  Optional<Request> find(String approval);

  boolean awaiting(String root);

  /** Revalidates the original live source before delayed dispatch, after any message expiry. */
  void requireExecutable(Request request);

  /** Decision and additive grant commit together. Repeated decisions never grant again. */
  default Request answer(String approval, boolean allow, String by) {
    return answer(approval, allow, by, null);
  }

  Request answer(String approval, boolean allow, String by, String reason);

  /** Cancellation withdraws only a question still pending; it never starts a continuation. */
  void cancel(String approval);

  void acknowledged(String approval);

  List<Request> recoverable();

  /** A recovery may dispatch once; started execution is never replayed after uncertain effects. */
  boolean queueRecovery(String approval);

  void recoveryStarted(String approval);

  void recoveryDeferred(String approval);
}
