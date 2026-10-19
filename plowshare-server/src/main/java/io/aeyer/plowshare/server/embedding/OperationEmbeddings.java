package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import java.util.List;

/** Internal embedding bindings for existing curation, digest and maintenance operations. */
public interface OperationEmbeddings {
  interface Batch extends EmbeddingPermit, AutoCloseable {
    void close();
  }

  interface Query extends Batch {}

  interface Publication extends EmbeddingAuthorization, AutoCloseable {
    void close();
  }

  /** Exact materialized query; caller retains responsibility for source reads and disclosure. */
  Query query(Home home, String text, Budget budget, Runnable validity);

  /** Existing legacy passage queue has its own hash/position publication fence. */
  Batch maintenanceBatch(Home home, List<String> texts, Budget budget, Runnable validity);

  /**
   * Exact committed outputs only. Current versions are rechecked before dispatch and publication.
   */
  Publication publication(
      Home home, List<EmbeddingWorkRepository.Source> sources, Budget budget, Runnable validity);
}
