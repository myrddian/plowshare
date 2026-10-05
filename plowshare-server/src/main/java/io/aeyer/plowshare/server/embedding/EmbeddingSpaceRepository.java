package io.aeyer.plowshare.server.embedding;

import java.util.Optional;

/**
 * Registers immutable spaces idempotently. Registration does not select a serving model, relabel
 * historical vectors, start model calls or activate a slot. Persistence failures propagate.
 */
public interface EmbeddingSpaceRepository {
  /**
   * The database owns canonical fingerprinting; concurrent equal definitions return the same ID.
   */
  EmbeddingSpace register(EmbeddingSpace.Definition definition);

  /** A valid but unregistered fingerprint is absent, never an implicit default model. */
  Optional<EmbeddingSpace> find(String id);
}
