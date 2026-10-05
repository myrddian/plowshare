package io.aeyer.plowshare.server.embedding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Owns durable slot targets, source-fenced rebuild progress, indexes and atomic activation. */
public interface EmbeddingWorkRepository {
  enum Store {
    MEMORIES,
    DIGESTS,
    DOCUMENTS,
    CHUNKS,
    PASSAGES
  }

  /**
   * Source keys are typed by owning store; passage keys include their actual composite identity.
   */
  record Key(Store store, String id, String sourceType, int position) {
    public Key {
      Objects.requireNonNull(store, "store");
      if (id == null || id.isBlank() || id.indexOf('\0') >= 0 || id.length() > 512)
        throw new IllegalArgumentException("invalid embedding source ID");
      if (store == Store.CHUNKS || store == Store.DOCUMENTS) UUID.fromString(id);
      if (store == Store.PASSAGES) {
        if (!("entry".equals(sourceType) || "digest".equals(sourceType)) || position < 0)
          throw new IllegalArgumentException("invalid passage identity");
      } else if (sourceType != null || position != 0)
        throw new IllegalArgumentException("unexpected composite source identity");
    }

    public static Key of(Store store, String id) {
      return new Key(store, id, null, 0);
    }
  }

  record Source(Key key, long revision, String text, String project) {
    public Source {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(text, "text");
      if (revision < 0) throw new IllegalArgumentException("negative source revision");
    }
  }

  /** Idempotent exact target configuration; changing only policy does not invalidate vectors. */
  EmbeddingProfile configure(
      EmbeddingSlot slot, EmbeddingSpace space, EmbeddingSearchPolicy policy);

  void inputLimit(EmbeddingSpace space, int tokens);

  int inputLimit(EmbeddingSpace space);

  Optional<EmbeddingProfile> active(EmbeddingSlot slot);

  Optional<EmbeddingProfile> target(EmbeddingSlot slot);

  List<Source> pending(EmbeddingProfile profile, Store store, int limit);

  Optional<Source> source(Key key);

  boolean ready(EmbeddingProfile target, Source source);

  boolean publish(EmbeddingProfile target, Source expected, float[] vector);

  /** Store only a bounded failure category, never provider bodies, credentials or source text. */
  void failed(EmbeddingProfile target, Source expected, String category);

  /**
   * Complete current-source coverage and all requested indexes are prerequisites for activation.
   */
  boolean activate(EmbeddingProfile target);

  <T> T read(EmbeddingProfile expected, Supplier<T> work);
}
