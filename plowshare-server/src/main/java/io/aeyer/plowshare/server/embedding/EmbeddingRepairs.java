package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.agents.Budget;

/**
 * Existing explicit memory repair and committed digest-vector publication, in either encoder mode.
 */
public interface EmbeddingRepairs {
  /** False means missing/changed source; failures never undo the owning content transaction. */
  boolean repair(EmbeddingWorkRepository.Key key, Budget budget, Runnable validity);
}
