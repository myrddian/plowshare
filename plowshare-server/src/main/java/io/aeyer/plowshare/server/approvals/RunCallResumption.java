package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.files.FileReadWindow;
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
  public record Result(String text, ToolEffectReceipt effectReceipt, FileReadWindow fileWindow) {
    public Result(String text, ToolEffectReceipt effectReceipt) {
      this(text, effectReceipt, null);
    }

    public boolean replyTimedOut() {
      return effectReceipt != null
          && effectReceipt.kind() == ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT;
    }
  }

  public static final String FILE_REPLY_TIMEOUT_RESULT =
      """
      {"error":{"source":"plowshare","code":"FILE_REPLY_TIMEOUT","message":"The request was sent and no reply arrived before the deadline. The effect is unknown. Reconcile the original call before retrying."}}
      """
          .strip();

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
    return awaitWithReceipt(
            approval,
            root,
            active,
            cancelled,
            ready,
            decisionObserved,
            call -> new Result(execute.apply(call), null),
            result -> append.accept(result.text()))
        .map(Result::text);
  }

  public Optional<Result> awaitWithReceipt(
      String approval,
      String root,
      ActiveRunApprovals active,
      BooleanSupplier cancelled,
      Runnable ready,
      Consumer<SuspendedRunCalls.Call> decisionObserved,
      Function<SuspendedRunCalls.Call, Result> execute,
      Consumer<Result> append) {
    active.entered(approval, root);
    try {
      ready.run();
      while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()) {
        var call = calls.find(approval).orElseThrow();
        if (call.released() && !RunApproval.ASKED.equals(call.decision()))
          decisionObserved.accept(call);
        var output = new AtomicReference<Result>();
        if (resumeWithReceipt(
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
    return resumeWithReceipt(
        call,
        retained -> new Result(execute.apply(retained), null),
        result -> append.accept(result.text()));
  }

  public boolean resumeWithReceipt(
      SuspendedRunCalls.Call call,
      Function<SuspendedRunCalls.Call, Result> execute,
      Consumer<Result> append) {
    if (RunApproval.ASKED.equals(call.decision()) || !call.released()) return false;
    String result = call.result();
    ToolEffectReceipt receipt = call.effectReceipt();
    FileReadWindow window = call.fileWindow();
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
        result = interruptedResult(receipt);
      } else if (!RunApproval.ALLOWED.equals(call.decision())) {
        result = "The approval no longer authorizes this retained command; it did not run.";
      } else {
        if (!calls.claim(call.approval()))
          throw new IllegalStateException(
              "The retained run call was claimed or its approval changed");
        // Exceptions deliberately leave the start fence in place. A restart must not guess whether
        // a remote command ran, even when failure occurred before a result reached this process.
        Result completed = execute.apply(call);
        result = completed.text();
        receipt = completed.effectReceipt();
        window = completed.fileWindow();
      }
      if (window == null) calls.complete(call.approval(), result);
      else calls.completeRead(call.approval(), result, window);
    }
    append.accept(new Result(result, receipt, window));
    calls.acknowledged(call.approval());
    return true;
  }

  /** Direct recovery has no executor. The journal fence already owns the original attempt. */
  public static void reconcileDirect(
      DirectFileCalls calls, DirectFileCalls.Pending pending, Consumer<Result> append) {
    String result = pending.result();
    if (result == null) {
      result = interruptedResult(pending.receipt());
      calls.complete(pending.invocation(), result);
    }
    append.accept(new Result(result, pending.receipt()));
    calls.acknowledged(pending.invocation());
  }

  private static String interruptedResult(ToolEffectReceipt receipt) {
    if (receipt == null) {
      return "The harness cannot establish whether this command executed before interruption."
          + " Its outcome is unknown. Do not repeat it; reconcile its effects before further action.";
    }
    return switch (receipt.kind()) {
      case PREFLIGHT_REFUSED -> "The approved tool was refused in preflight and did not run.";
      case FILE_REPLY_TIMEOUT -> FILE_REPLY_TIMEOUT_RESULT;
      case FILE_EFFECT_CONFIRMED, FILE_EFFECT_VERIFIED, FILE_LOCAL_CHANGED ->
          "The file provider confirmed the change. The original result is unavailable. Do not repeat the change.";
      case FILE_EFFECT_UNCHANGED, FILE_LOCAL_UNCHANGED ->
          "The file operation completed without a content change. The original result is unavailable.";
      case FILE_DISPATCH_UNCERTAIN, FILE_REPLY_UNVERIFIED, FILE_LOCAL_UNVERIFIED ->
          "The file operation's effect is unknown. Its original result is unavailable. Reconcile the original call before retrying.";
      case FILE_SENT ->
          "The file request was sent before interruption. Its effect is unknown. Reconcile the original call before retrying.";
    };
  }
}
