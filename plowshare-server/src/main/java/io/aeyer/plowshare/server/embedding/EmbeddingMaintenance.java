package io.aeyer.plowshare.server.embedding;

/** Durable, bounded maintenance for all owning embedding stores. */
@FunctionalInterface
public interface EmbeddingMaintenance {
  /**
   * Repairs missing current sources and attempts fully covered slot activation. Model calls run
   * outside publication transactions; interruption stops between sources and preserves committed
   * checkpoints. Per-source embedding failures are retained for bounded retry; repository or
   * activation failures escape to the lifecycle worker without discarding earlier publications.
   *
   * @return number of source representations published during this pass
   */
  int tick();
}
