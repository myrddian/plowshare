package io.aeyer.plowshare.server.embedding;

import java.util.Objects;

/**
 * Fixed retrieval choices over the same full-precision storage. Index policy is deliberately
 * separate from immutable model identity: changing it must not require re-embedding or new columns.
 * Index administration and retrieval consume this validated policy through their owning
 * repositories.
 */
public record EmbeddingSearchPolicy(Mode mode, Distance distance) {
  public EmbeddingSearchPolicy {
    Objects.requireNonNull(mode, "embedding search mode");
    Objects.requireNonNull(distance, "embedding distance");
    if (distance == Distance.L1 && (mode == Mode.IVFFLAT_VECTOR || mode == Mode.IVFFLAT_HALF)) {
      throw new IllegalArgumentException("IVFFlat dense indexes do not support L1 distance");
    }
  }

  /** Reject unsupported widths explicitly; never truncate vectors or select a fallback silently. */
  public void validate(EmbeddingSpace.Definition space) {
    Objects.requireNonNull(space, "embedding space definition");
    if (space.dimensions() > mode.maximumDimensions()) {
      throw new IllegalArgumentException("embedding width exceeds the selected search mode limit");
    }
  }

  /** Half/binary candidate scores must be reranked using the retained full-precision vector. */
  public boolean requiresFullPrecisionReranking() {
    return mode == Mode.HNSW_HALF
        || mode == Mode.IVFFLAT_HALF
        || mode == Mode.HNSW_BINARY
        || mode == Mode.IVFFLAT_BINARY;
  }

  /** Limits describe the searchable dense source, not pgvector's larger binary-only index limit. */
  public enum Mode {
    EXACT("exact", 16000),
    HNSW_VECTOR("hnsw_vector", 2000),
    HNSW_HALF("hnsw_half", 4000),
    HNSW_BINARY("hnsw_binary", 16000),
    IVFFLAT_VECTOR("ivfflat_vector", 2000),
    IVFFLAT_HALF("ivfflat_half", 4000),
    IVFFLAT_BINARY("ivfflat_binary", 16000);

    private final String configurationName;
    private final int maximumDimensions;

    Mode(String configurationName, int maximumDimensions) {
      this.configurationName = configurationName;
      this.maximumDimensions = maximumDimensions;
    }

    public String configurationName() {
      return configurationName;
    }

    public int maximumDimensions() {
      return maximumDimensions;
    }

    public static Mode fromConfiguration(String value) {
      for (Mode mode : values()) {
        if (mode.configurationName.equals(value)) return mode;
      }
      throw new IllegalArgumentException("unknown embedding search mode");
    }
  }

  /** Final dense-vector ranking metric; binary candidates use Hamming distance before reranking. */
  public enum Distance {
    COSINE,
    L2,
    INNER_PRODUCT,
    L1
  }
}
