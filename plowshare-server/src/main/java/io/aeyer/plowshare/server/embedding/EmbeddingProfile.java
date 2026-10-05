package io.aeyer.plowshare.server.embedding;

import java.util.Objects;

/** A captured model and index policy; its version fences a complete retrieval operation. */
public record EmbeddingProfile(
    EmbeddingSlot slot, EmbeddingSpace space, EmbeddingSearchPolicy search, long version) {
  public EmbeddingProfile {
    Objects.requireNonNull(slot, "slot");
    Objects.requireNonNull(space, "space");
    Objects.requireNonNull(search, "search policy").validate(space.definition());
    if (version < 0) throw new IllegalArgumentException("negative embedding configuration version");
  }
}
