package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;

/**
 * Owned model submission through the platform dispatcher. Implementations retain its admission,
 * accounting, cancellation and uncertain-delivery rules; callers must not add transport retries.
 */
@FunctionalInterface
public interface EmbeddingDispatch {
  Embeddings embed(EmbeddingRequest request);
}
