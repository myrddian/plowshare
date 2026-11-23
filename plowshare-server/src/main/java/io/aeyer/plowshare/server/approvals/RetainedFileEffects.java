package io.aeyer.plowshare.server.approvals;

import io.aeyer.plowshare.server.files.FileMutationObservation;
import io.aeyer.plowshare.server.files.FileReplyTimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Collects evidence for one claimed file call on its execution thread. */
public final class RetainedFileEffects implements Consumer<FileMutationObservation> {
  private final BiConsumer<String, ToolEffectReceipt> record;
  private final String approval;
  private ToolEffectReceipt receipt;
  private boolean attempted;

  public RetainedFileEffects(SuspendedRunCalls calls, String approval) {
    this(calls::recordEffect, approval);
  }

  public RetainedFileEffects(BiConsumer<String, ToolEffectReceipt> record, String approval) {
    this.record = record;
    this.approval = approval;
  }

  @Override
  public void accept(FileMutationObservation observation) {
    attempted = true;
    if (observation.kind() == FileMutationObservation.Kind.ATTEMPTED) return;
    record(
        new ToolEffectReceipt(
            switch (observation.kind()) {
              case ATTEMPTED -> throw new IllegalStateException("Attempt is already fenced");
              case SENT -> ToolEffectReceipt.Kind.FILE_SENT;
              case CONFIRMED -> ToolEffectReceipt.Kind.FILE_EFFECT_CONFIRMED;
              case VERIFIED -> ToolEffectReceipt.Kind.FILE_EFFECT_VERIFIED;
              case UNCHANGED -> ToolEffectReceipt.Kind.FILE_EFFECT_UNCHANGED;
              case REPLY_UNVERIFIED -> ToolEffectReceipt.Kind.FILE_REPLY_UNVERIFIED;
              case LOCAL_CHANGED -> ToolEffectReceipt.Kind.FILE_LOCAL_CHANGED;
              case LOCAL_UNCHANGED -> ToolEffectReceipt.Kind.FILE_LOCAL_UNCHANGED;
              case LOCAL_UNVERIFIED -> ToolEffectReceipt.Kind.FILE_LOCAL_UNVERIFIED;
            },
            observation.requestId()));
  }

  public void refusedBeforeDispatch() {
    record(new ToolEffectReceipt(ToolEffectReceipt.Kind.PREFLIGHT_REFUSED, null));
  }

  public void settleBeforeDispatch() {
    if (!attempted) refusedBeforeDispatch();
  }

  /** Timeout of a workspace read or a different request does not attest mutation dispatch. */
  public boolean replyTimedOut(FileReplyTimeoutException timeout) {
    if (receipt == null
        || receipt.kind() != ToolEffectReceipt.Kind.FILE_SENT
        || !timeout.requestId().equals(receipt.providerRequestId())) return false;
    record(new ToolEffectReceipt(ToolEffectReceipt.Kind.FILE_REPLY_TIMEOUT, timeout.requestId()));
    return true;
  }

  public RunCallResumption.Result result(String text) {
    return new RunCallResumption.Result(text, receipt);
  }

  private void record(ToolEffectReceipt observation) {
    record.accept(approval, observation);
    receipt = observation;
  }
}
