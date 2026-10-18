package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.dispatch.*;
import java.util.List;

/**
 * An admitted publication operation's exact outputs, claim carriage and publication checkpoint.
 * Document leases and synchronous memory writes retain their own validity contracts.
 */
public interface EmbeddingAuthorization extends EmbeddingPermit {
  default Mode mode() {
    return Mode.DOCUMENT;
  }

  void sources(List<EmbeddingWorkRepository.Key> sources);

  CallAuthorization authorize(EmbeddingRequest request);

  /** Called only after all outputs for the preceding request have been committed. */
  void published();

  /** Called inside the repository publication transaction before source/version locks. */
  default void publicationFence() {
    require();
  }
}
