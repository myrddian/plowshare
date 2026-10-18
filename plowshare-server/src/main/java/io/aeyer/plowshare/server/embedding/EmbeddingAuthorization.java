package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;

/**
 * A single document lease's exact output set, claim carriage and durable publication checkpoint.
 */
public interface EmbeddingAuthorization extends EmbeddingPermit {
  default Mode mode() {
    return Mode.DOCUMENT;
  }

  void sources(List<EmbeddingWorkRepository.Key> sources);

  CallAuthorization authorize(EmbeddingRequest request);

  /** Called only after all outputs for the preceding request have been committed. */
  void published();
}
