package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.Objects;
import java.util.UUID;

/** Operation metadata only. It neither supplies authority nor owns the accounting lifecycle. */
final class RepairInferenceProgress implements InferenceProgress {
  private final EmbeddingWorkRepository repository;
  private final EmbeddingRepair.Attempt attempt;

  private record Evidence(UUID call, UUID wire, Integer status, CallLifecycle outcome) {}

  // A single immutable snapshot keeps a response paired with its wire attempt when cancellation
  // lets terminal evidence race the caller. This is metadata, never cached authorization.
  private final java.util.concurrent.atomic.AtomicReference<Evidence> evidence =
      new java.util.concurrent.atomic.AtomicReference<>(new Evidence(null, null, null, null));

  RepairInferenceProgress(EmbeddingWorkRepository repository, EmbeddingRepair.Attempt attempt) {
    this.repository = Objects.requireNonNull(repository);
    this.attempt = Objects.requireNonNull(attempt);
  }

  EmbeddingRepair.Progress snapshot(EmbeddingRepair.Phase phase) {
    var value = evidence.get();
    return new EmbeddingRepair.Progress(
        phase, value.call(), value.wire(), value.status(), value.outcome());
  }

  void phase(EmbeddingRepair.Phase phase) {
    repository.repairProgress(attempt, snapshot(phase));
  }

  public void accounted(InferenceCapture value) {
    if (value != null)
      evidence.updateAndGet(
          old -> new Evidence(value.callId(), old.wire(), old.status(), old.outcome()));
    phase(EmbeddingRepair.Phase.ACCOUNTED);
  }

  // Queue and worker-start events are already durable in canonical accounting. Reuse the call
  // link instead of writing the same transitions to PostgreSQL for every repair.

  public void attempting(UUID id) {
    evidence.updateAndGet(old -> new Evidence(old.call(), id, null, old.outcome()));
    phase(EmbeddingRepair.Phase.ATTEMPT_STARTED);
  }

  // Terminal evidence already belongs to the durable accounting journal. Do not throw a
  // second database failure into usable inference output. The owner persists this snapshot
  // with publication or the failure reason in its next short transaction.
  public void response(Integer value) {
    if (value != null && value >= 100 && value <= 599)
      evidence.updateAndGet(old -> new Evidence(old.call(), old.wire(), value, old.outcome()));
  }

  public void finished(CallLifecycle value) {
    evidence.updateAndGet(old -> new Evidence(old.call(), old.wire(), old.status(), value));
  }
}
