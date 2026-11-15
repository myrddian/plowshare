package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.agents.BudgetRequests;
import io.aeyer.plowshare.server.agents.Transcript;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.archive.Origin;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live virtual-thread waits preserve the entire delegation stack, including unexecuted siblings.
 * Approval commits the canonical grant before waking it. After interruption, retained frames are
 * handed to the same execution owner. No recovery starts work while a live root still exists.
 */
public final class RetainedBudgetRequests implements BudgetRequests {
  private static final Logger log = LoggerFactory.getLogger(RetainedBudgetRequests.class);
  private final BudgetRequestRepository repository;
  private final BudgetRequestAccess access;
  private final ActiveRunApprovals active;
  private final Consumer<String> notify;
  private final BudgetRequestContinuations continuations;
  private final ConcurrentMap<String, Restoring> restoring = new ConcurrentHashMap<>();

  private record Restoring(BudgetRequestRepository.Request request, int index) {}

  public RetainedBudgetRequests(
      BudgetRequestRepository repository,
      BudgetRequestAccess access,
      ActiveRunApprovals active,
      Consumer<String> notify,
      BudgetRequestContinuations continuations) {
    this.repository = Objects.requireNonNull(repository);
    this.access = Objects.requireNonNull(access);
    this.active = Objects.requireNonNull(active);
    this.notify = Objects.requireNonNull(notify);
    this.continuations = Objects.requireNonNull(continuations);
  }

  @Override
  public boolean available(Transcript transcript, String executor) {
    return executor != null
        && transcript.conversationId() != null
        && (transcript.origin() == Origin.DELEGATION
            || transcript.origin() == Origin.MESSAGE
            || transcript.origin() == Origin.TURN
            || transcript.origin() == Origin.EVENT
            || transcript.origin() == Origin.SUBMISSION);
  }

  @Override
  public Optional<Decision> await(
      BudgetCheckpoint checkpoint,
      Budget budget,
      TurnCap cap,
      BudgetExtension requested,
      String reason,
      BooleanSupplier cancelled) {
    AllowanceId allowance = new AllowanceId(budget.identity());
    long project = access.require(checkpoint, allowance);
    BudgetCheckpoint.Frame leaf = checkpoint.frames().getLast();
    if (leaf.stepLimit() != null)
      Math.addExact(Math.max(leaf.steps(), leaf.stepLimit()), requested.steps());
    BudgetRequestRepository.Request request =
        repository.ask(project, checkpoint, allowance, requested, reason);
    active.entered(request.approval(), request.root());
    try {
      notify.accept(request.root());
      while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        BudgetRequestRepository.Request current = repository.find(request.approval()).orElseThrow();
        if (!RunApproval.ASKED.equals(current.state())) {
          boolean allowed = RunApproval.ALLOWED.equals(current.state());
          if (allowed) {
            budget.refreshFrom(current.allowance());
            if (cap.capped() && current.extension().steps() > 0)
              cap.changeTo(
                  Math.addExact(Math.max(cap.turns(), leaf.steps()), current.extension().steps()));
          }
          return Optional.of(new Decision(current.approval(), allowed, decisionText(current)));
        }
        LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
      }
      if (!cancelled.getAsBoolean())
        return Optional.of(
            new Decision(
                request.approval(),
                false,
                "The budget decision remains pending; this same execution will continue after approval.",
                true));
      repository.cancel(request.approval());
      repository.acknowledged(request.approval());
      return Optional.empty();
    } finally {
      active.left(request.approval());
    }
  }

  public static String decisionText(BudgetRequestRepository.Request request) {
    return RunApproval.ALLOWED.equals(request.state())
        ? "Budget extension approved by "
            + request.answeredBy()
            + ": "
            + request.extension().calls()
            + " additional model calls and "
            + request.extension().steps()
            + " additional steps. Continue the same task; prior spending and tool results are retained."
        : "Budget extension denied. Finish with the verified work already retained; do not repeat the request.";
  }

  @Override
  public void acknowledged(String approval) {
    repository.acknowledged(approval);
  }

  @Override
  public Optional<Answer> answer(RunApproval approval, boolean allow, String by, String reason) {
    if (approval.budget() == null) return Optional.empty();
    BudgetRequestRepository.Request current = repository.find(approval.id()).orElseThrow();
    if (allow) access.requireRecovery(current);
    BudgetRequestRepository.Request answered = repository.answer(approval.id(), allow, by, reason);
    return Optional.of(
        new Answer(
            answered.state(),
            active.contains(approval.id()),
            active.contains(approval.id())
                ? allow
                    ? "The original execution will continue after observing this decision."
                    : "The original execution will observe the denial and stop at its allowance."
                : answered.acknowledged()
                    ? "This budget decision has already been consumed."
                    : "The decision is retained; the original execution is queued for continuation."));
  }

  @Override
  public Optional<BudgetCheckpoint.Frame> restoring(String conversation) {
    Restoring found = restoring.get(conversation);
    if (found == null) return Optional.empty();
    var request = found.request();
    var frame = request.checkpoint().frames().get(found.index());
    Integer ceiling = frame.stepLimit();
    if (ceiling != null && found.index() == request.checkpoint().frames().size() - 1)
      ceiling = Math.addExact(Math.max(ceiling, frame.steps()), request.extension().steps());
    return Optional.of(
        new BudgetCheckpoint.Frame(
            frame.conversation(),
            frame.agent(),
            frame.turn(),
            frame.steps(),
            ceiling,
            frame.pending()));
  }

  @Override
  public Optional<BudgetCheckpoint.Frame> restoringChild(String conversation) {
    Restoring found = restoring.get(conversation);
    if (found == null || found.index() + 1 == found.request().checkpoint().frames().size())
      return Optional.empty();
    return restoring(found.request().checkpoint().frames().get(found.index() + 1).conversation());
  }

  @Override
  public Optional<String> restorationDecision(String conversation) {
    Restoring found = restoring.get(conversation);
    return found == null ? Optional.empty() : Optional.of(decisionText(found.request()));
  }

  @Override
  public boolean awaiting(String conversation) {
    return repository.awaiting(conversation);
  }

  @Override
  public void restored(String conversation) {
    Restoring found = restoring.remove(conversation);
    if (found == null) return;
    if (found.index() == found.request().checkpoint().frames().size() - 1)
      repository.acknowledged(found.request().approval());
  }

  @Override
  public void recover() {
    for (BudgetRequestRepository.Request request : repository.recoverable()) {
      if (active.contains(request.approval())
          || restoring.containsKey(request.root())
          || continuations.busy(request.root())) continue;
      try {
        if ("started".equals(request.recovery())) {
          continuations.interrupted(
              request,
              "Budget continuation was interrupted after recovery began. Its tool outcomes may be uncertain; no effects were replayed and no replacement task was started.");
          repository.acknowledged(request.approval());
          continue;
        }
        if (RunApproval.DENIED.equals(request.state())) {
          continuations.denied(request);
          repository.acknowledged(request.approval());
          continue;
        }
        access.requireRecovery(request);
        if (!repository.queueRecovery(request.approval()) && !"queued".equals(request.recovery()))
          continue;
        for (int index = 0; index < request.checkpoint().frames().size(); index++) {
          var frame = request.checkpoint().frames().get(index);
          restoring.put(frame.conversation(), new Restoring(request, index));
        }
        if (!continuations.resume(request)) {
          request.checkpoint().frames().forEach(frame -> restoring.remove(frame.conversation()));
          repository.recoveryDeferred(request.approval());
        }
      } catch (RuntimeException failed) {
        request.checkpoint().frames().forEach(frame -> restoring.remove(frame.conversation()));
        repository.recoveryDeferred(request.approval());
        log.warn(
            "Budget approval {} could not resume ({})",
            request.approval(),
            failed.getClass().getSimpleName());
        continuations.problem(
            request,
            "The budget decision is retained, but its original execution could not resume. No replacement task was started.");
      }
    }
  }

  /** Fence recovery before any resumed inference or tool effect. */
  @Override
  public void recoveryStarted(String conversation) {
    Restoring found = restoring.get(conversation);
    if (found != null && found.index() == 0) repository.recoveryStarted(found.request().approval());
  }
}
