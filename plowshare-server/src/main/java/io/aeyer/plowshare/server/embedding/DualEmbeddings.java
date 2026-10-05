package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.util.List;
import java.util.function.Supplier;

/** Model dispatch and short, version-fenced retrieval; no caller receives persistence internals. */
public interface DualEmbeddings {
  /** Fails explicitly while a slot has no complete active space. */
  /** Processing identity includes both model spaces, but excludes index policy. */
  String fingerprint();

  EmbeddingProfile active(EmbeddingSlot slot);

  /**
   * Applies the declared query prefix once, checks final input/output, and preserves attribution.
   */
  EmbeddingQuery query(EmbeddingProfile profile, String text, UsageAttribution owner);

  /** Both queries use one captured space, including stance comparisons. */
  List<EmbeddingQuery> queries(
      EmbeddingProfile profile, List<String> texts, UsageAttribution owner);

  /**
   * Rechecks and share-locks the captured slot for database reads only. Model calls must happen
   * before entering this callback. A raced activation fails rather than searching another space.
   */
  <T> T read(EmbeddingProfile profile, Supplier<T> work);

  /** Repair a specific committed source synchronously, keeping source edits safe on failure. */
  boolean repair(EmbeddingWorkRepository.Key source, UsageAttribution owner);

  record RepairResult(int completeSources, int modelCalls) {
    public RepairResult {
      if (completeSources < 0 || modelCalls < 0)
        throw new IllegalArgumentException("negative embedding repair result");
    }
  }

  /** Batches one owner's committed inputs independently per slot; skips ready representations. */
  default RepairResult repairAll(
      List<EmbeddingWorkRepository.Key> sources, UsageAttribution owner) {
    throw new UnsupportedOperationException("batched embedding repair is unavailable");
  }
}
