package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.access.OperationFence;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Authorises one prepared information mutation and reconciles its durable gate receipt. */
public interface InformationTransitions {
  record Prepared<T extends InformationGateResult>(
      Supplier<T> transition, io.aeyer.plowshare.protocol.InformationContent.Candidate candidate) {
    public Prepared {
      java.util.Objects.requireNonNull(transition);
    }
  }

  default <T extends InformationGateResult> T execute(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<T> transition) {
    return execute(
        context,
        request,
        operation,
        identity,
        sources,
        session,
        resultType,
        transition,
        OperationFence.none());
  }

  /** As the unfenced form, with {@code fence} placed as {@link #executePrepared} describes. */
  <T extends InformationGateResult> T execute(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<T> transition,
      OperationFence fence);

  /**
   * Resolves a completed receipt before preparing/validating new work, then gates its exact
   * candidate.
   */
  default <T extends InformationGateResult> T executePrepared(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<Prepared<T>> prepare) {
    return executePrepared(
        context,
        request,
        operation,
        identity,
        sources,
        session,
        resultType,
        prepare,
        OperationFence.none());
  }

  /**
   * As the unfenced form, and a run's {@code fence} runs inside every transaction the gate opens
   * (the receipt claim, before a completed receipt is replayed; the opened log; each gate record;
   * the approval; the final transition), immediately before the pipeline log is opened, and
   * immediately before the hook chain fires. A refusal after the claim records the receipt as
   * blocked, as any other transition failure does; a refusal in the claim leaves no receipt.
   */
  <T extends InformationGateResult> T executePrepared(
      InformationContext context,
      UUID request,
      String operation,
      InformationGateIdentity identity,
      List<UUID> sources,
      String session,
      Class<T> resultType,
      Supplier<Prepared<T>> prepare,
      OperationFence fence);
}
