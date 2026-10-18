package io.aeyer.plowshare.server.approvals;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Resumes consent in the harness, without consulting a model. A persisted start fence is written
 * before dispatch, and the result before transcript append. Recovery can repeat the append, but
 * never an external effect whose completion is uncertain.
 */
public final class RunCallResumption {
  private final SuspendedRunCalls calls;

  public RunCallResumption(SuspendedRunCalls calls) {
    this.calls = Objects.requireNonNull(calls);
  }

  /**
   * Park the original virtual-thread stack while waiting. The callback releases notification
   * delivery only after both durable retention and process-local presence exist. Polling the
   * durable decision also covers decisions made through another server process; no model slot is
   * held while waiting. Cancellation returns without dispatch so the owner can retire the call.
   */
  public Optional<String> await(
      String approval,
      String root,
      ActiveRunApprovals active,
      BooleanSupplier cancelled,
      Runnable ready,
      Consumer<SuspendedRunCalls.Call> decisionObserved,
      Function<SuspendedRunCalls.Call, String> execute,
      Consumer<String> append) {
    active.entered(approval, root);
    try {
      ready.run();
      while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        var call = calls.find(approval).orElseThrow();
        if (call.released() && !RunApproval.ASKED.equals(call.decision()))
          decisionObserved.accept(call);
        AtomicReference<String> output = new AtomicReference<>();
        if (resume(
            call,
            execute,
            result -> {
              append.accept(result);
              output.set(result);
            })) return Optional.of(output.get());
        LockSupport.parkNanos(Duration.ofSeconds(1).toNanos());
      }
      return Optional.empty();
    } finally {
      active.left(approval);
    }
  }

  /**
   * Returns false while a person still needs to decide; no executor or result callback runs then.
   */
  public boolean resume(
      SuspendedRunCalls.Call call,
      Function<SuspendedRunCalls.Call, String> execute,
      Consumer<String> append) {
    if (RunApproval.ASKED.equals(call.decision()) || !call.released()) return false;
    String result = call.result();
    if (result == null) {
      if (RunApproval.DENIED.equals(call.decision())
          || RunApproval.REVOKED.equals(call.decision())) {
        result =
            "The command was denied by "
                + Objects.requireNonNullElse(call.answeredBy(), "the harness")
                + ". Reason: "
                + Objects.requireNonNullElse(call.denialReason(), "No reason was supplied.")
                + " The command did not run. Do not retry this denied call.";
      } else if (!"waiting".equals(call.state())) {
        result =
            "The harness cannot establish whether this command executed before interruption."
                + " Its outcome is unknown. Do not repeat it; reconcile its effects before further action.";
      } else if (!RunApproval.ALLOWED.equals(call.decision())) {
        result = "The approval no longer authorizes this retained command; it did not run.";
      } else {
        if (!calls.claim(call.approval()))
          throw new IllegalStateException(
              "The retained run call was claimed or its approval changed");
        // Exceptions deliberately leave the start fence in place. A restart must not guess whether
        // a remote command ran, even when failure occurred before a result reached this process.
        result = execute.apply(call);
      }
      calls.complete(call.approval(), result);
    }
    append.accept(result);
    calls.acknowledged(call.approval());
    return true;
  }
}
