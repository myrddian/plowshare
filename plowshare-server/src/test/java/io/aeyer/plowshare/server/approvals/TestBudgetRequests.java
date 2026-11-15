package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.AllowanceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-memory decisions for behavior tests; SQL atomicity is exercised separately against PostgreSQL.
 */
public final class TestBudgetRequests implements BudgetRequestRepository {
  private final Budget budget;
  private BudgetCheckpoint checkpoint;
  private BudgetExtension extension;
  private Integer ceiling;
  private String id;
  private String state;
  private String answeredBy;
  private boolean acknowledged;
  private String recovery = "pending";
  private int questions;

  public TestBudgetRequests(Budget budget) {
    this.budget = budget;
    ceiling = budget.snapshot().ceiling();
  }

  public synchronized int questions() {
    return questions;
  }

  public synchronized RunApproval approval() {
    var leaf = checkpoint.frames().getLast();
    return new RunApproval(
        id,
        1,
        checkpoint.frames().getFirst().conversation(),
        leaf.conversation(),
        checkpoint.executor(),
        leaf.agent(),
        "server",
        List.of(),
        "",
        "Finish the original work",
        state,
        RunApproval.ASKED.equals(state) || RunApproval.DENIED.equals(state)
            ? null
            : RunApproval.ONCE,
        null,
        answeredBy,
        RunApproval.ASKED.equals(state) ? null : Instant.EPOCH,
        null,
        Instant.EPOCH,
        null,
        null,
        extension);
  }

  @Override
  public synchronized Request ask(
      long project,
      BudgetCheckpoint checkpoint,
      AllowanceId allowance,
      BudgetExtension extension,
      String reason) {
    if (id != null && !acknowledged) return current();
    if (!allowance.value().equals(budget.identity()))
      throw new IllegalArgumentException("Unexpected source");
    this.checkpoint = checkpoint;
    this.extension = extension;
    id = "apr_budget" + ++questions;
    state = RunApproval.ASKED;
    acknowledged = false;
    recovery = "pending";
    return current();
  }

  @Override
  public synchronized Optional<Request> find(String approval) {
    return approval.equals(id) ? Optional.of(current()) : Optional.empty();
  }

  @Override
  public void requireExecutable(Request request) {}

  private Request current() {
    return new Request(
        id,
        checkpoint.frames().getFirst().conversation(),
        checkpoint,
        extension,
        state,
        answeredBy,
        new AllowanceSnapshot(new AllowanceId(budget.identity()), ceiling, budget.spent()),
        acknowledged,
        recovery);
  }

  @Override
  public synchronized Request answer(String approval, boolean allow, String by, String reason) {
    if (RunApproval.ASKED.equals(state)) {
      if (allow && ceiling != null) ceiling = Math.addExact(ceiling, extension.calls());
      state = allow ? RunApproval.ALLOWED : RunApproval.DENIED;
      answeredBy = by;
    }
    return current();
  }

  @Override
  public synchronized void cancel(String approval) {
    if (RunApproval.ASKED.equals(state)) {
      state = RunApproval.DENIED;
      answeredBy = RunApproval.RUN_ENDED;
    }
  }

  @Override
  public synchronized void acknowledged(String approval) {
    acknowledged = true;
    if (RunApproval.ALLOWED.equals(state)) state = RunApproval.USED;
  }

  @Override
  public synchronized boolean awaiting(String root) {
    return id != null && !acknowledged && current().root().equals(root);
  }

  @Override
  public synchronized List<Request> recoverable() {
    return id == null || acknowledged || RunApproval.ASKED.equals(state)
        ? List.of()
        : List.of(current());
  }

  @Override
  public synchronized boolean queueRecovery(String approval) {
    if (!"pending".equals(recovery)) return false;
    recovery = "queued";
    return true;
  }

  @Override
  public synchronized void recoveryStarted(String approval) {
    recovery = "started";
  }

  @Override
  public synchronized void recoveryDeferred(String approval) {
    if ("queued".equals(recovery)) recovery = "pending";
  }
}
