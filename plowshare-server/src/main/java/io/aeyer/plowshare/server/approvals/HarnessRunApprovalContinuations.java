package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.agents.Messaging;
import io.aeyer.plowshare.server.agents.Turn;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Uses existing conversation, orchestration and messaging lifecycle owners for retained calls. */
public final class HarnessRunApprovalContinuations implements RunApprovalContinuations {
  private static final Logger log = LoggerFactory.getLogger(HarnessRunApprovalContinuations.class);
  private final SuspendedRunCalls calls;
  private final ApprovalNotificationStore approvals;
  private final ActiveRunApprovals active;
  private final RetainedRunStarter starter;
  private final Supplier<ConductorContinuation> conductors;
  private final Supplier<Messaging> messaging;
  private final Consumer<RecoveryProblem> report;
  private Consumer<RunApproval> recoveredDecision = approval -> {};

  /** Recovery repeats notification hooks only, never the command effect. */
  public void whenDecisionRecovered(Consumer<RunApproval> callback) {
    recoveredDecision = Objects.requireNonNull(callback);
  }

  public HarnessRunApprovalContinuations(
      SuspendedRunCalls calls,
      ApprovalNotificationStore approvals,
      ActiveRunApprovals active,
      RetainedRunStarter starter,
      Supplier<ConductorContinuation> conductors,
      Supplier<Messaging> messaging,
      Consumer<RecoveryProblem> report) {
    this.calls = Objects.requireNonNull(calls);
    this.approvals = Objects.requireNonNull(approvals);
    this.active = Objects.requireNonNull(active);
    this.starter = Objects.requireNonNull(starter);
    this.conductors = Objects.requireNonNull(conductors);
    this.messaging = Objects.requireNonNull(messaging);
    this.report = Objects.requireNonNull(report);
  }

  @Override
  public Optional<Resumed> resume(String id) {
    var retained = calls.find(id);
    if (retained.isEmpty()) return Optional.empty();
    var call = retained.get();
    if (active.contains(id)
        || calls.pending(call.conversation()).stream()
            .noneMatch(pending -> pending.approval().equals(id)))
      return Optional.of(new Resumed(null, false, null));
    RunApproval approval = approvals.find(id).orElseThrow();
    if (RunApproval.ASKED.equals(approval.state()))
      return Optional.of(new Resumed(null, true, "The command is still awaiting a decision."));
    if (!call.released())
      return Optional.of(new Resumed(null, true, "The decision is awaiting its harness handoff."));
    String audit =
        ApprovalUtterance.forAnswer(
            approval,
            RunApproval.DENIED.equals(approval.state()) ? "deny" : approval.scope(),
            approval.prefix());
    try {
      Messaging transport = messaging.get();
      if (transport != null && transport.continueApproved(approval, audit))
        return Optional.of(new Resumed(null, false, null));
      if (conductors.get().continueApproved(approval, audit))
        return Optional.of(new Resumed(null, false, null));
      if (!call.conversation().equals(approval.conversation())) {
        // A missing delegation lease is not permission to start its root again. In particular,
        // never ask a model to recreate a child whose original budget/stack cannot be restored.
        String note =
            "The approved command could not resume because its delegated run is unavailable. Nothing was executed. Start a new task if this work is still needed.";
        report.accept(new RecoveryProblem(id, call.conversation(), call.owner(), note));
        return Optional.of(new Resumed(null, true, note));
      }
      String job = starter.start(approval, call, audit);
      return Optional.of(new Resumed(job, false, null));
    } catch (Turn.Refused busy) {
      return Optional.of(new Resumed(null, true, busy.getMessage()));
    }
  }

  @Override
  public void recover() {
    for (var call : calls.recoverable()) {
      try {
        if (!call.released()) {
          recoveredDecision.accept(approvals.find(call.approval()).orElseThrow());
          calls.release(call.approval());
        }
        var recovered = resume(call.approval());
        if (recovered.isPresent() && recovered.get().busy() && recovered.get().note() != null) {
          report.accept(
              new RecoveryProblem(
                  call.approval(),
                  call.conversation(),
                  call.owner(),
                  "Approval recovery was delayed. The harness will retry; check the trajectory for its latest status. Last reason: "
                      + recovered.get().note()));
        }
      } catch (RuntimeException failure) {
        // Keep the durable pending row. A failed recovery must not prevent other independent
        // approvals from resuming, nor turn an uncertain dispatch into another execution.
        log.warn(
            "Retained approval {} could not recover ({})",
            call.approval(),
            failure.getClass().getSimpleName());
        report.accept(
            new RecoveryProblem(
                call.approval(),
                call.conversation(),
                call.owner(),
                "The approval decision is retained, but its execution could not recover. Check the trajectory; the harness will retry recovery without replaying an uncertain command."));
      }
    }
  }
}
