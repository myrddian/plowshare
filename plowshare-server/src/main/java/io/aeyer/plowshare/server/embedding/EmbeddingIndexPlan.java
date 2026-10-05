package io.aeyer.plowshare.server.embedding;

import java.util.Objects;

/**
 * Repository-only pgvector expressions from the fixed retrieval menu. Identifiers must originate in
 * owning repositories, never transport values. Model IDs are validated fingerprints; numeric widths
 * come from validated descriptors. User text and vectors remain JDBC parameters.
 */
public final class EmbeddingIndexPlan {
  private final EmbeddingProfile profile;

  public EmbeddingIndexPlan(EmbeddingProfile profile) {
    this.profile = Objects.requireNonNull(profile);
  }

  public String column() {
    return profile.slot().stored() + "_embedding";
  }

  public String present(String alias) {
    if (alias == null || !alias.matches("[a-z][a-z0-9_]*|"))
      throw new IllegalArgumentException("invalid embedding repository alias");
    String prefix = alias.isEmpty() ? "" : alias + ".";
    String slot = profile.slot().stored();
    return prefix
        + slot
        + "_space_id='"
        + profile.space().id()
        + "' AND "
        + prefix
        + column()
        + " IS NOT NULL AND "
        + prefix
        + slot
        + "_source_revision="
        + prefix
        + "embedding_source_revision";
  }

  private String representation(String value) {
    int width = profile.space().definition().dimensions();
    return switch (profile.search().mode()) {
      case EXACT -> value;
      case HNSW_VECTOR, IVFFLAT_VECTOR -> "(" + value + "::vector(" + width + "))";
      case HNSW_HALF, IVFFLAT_HALF -> "(" + value + "::halfvec(" + width + "))";
      case HNSW_BINARY, IVFFLAT_BINARY -> "(binary_quantize(" + value + ")::bit(" + width + "))";
    };
  }

  private void columnIdentifier(String value) {
    if (value == null || !value.matches("(?:[a-z][a-z0-9_]*\\.)?" + column()))
      throw new IllegalArgumentException("invalid embedding repository column");
  }

  public String denseDistance(String value) {
    columnIdentifier(value);
    return value + " " + denseOperator() + " CAST(? AS vector)";
  }

  public String candidateDistance(String value) {
    columnIdentifier(value);
    return representation(value)
        + " "
        + (binary() ? "<~>" : denseOperator())
        + " "
        + representation("CAST(? AS vector)");
  }

  private String denseOperator() {
    return switch (profile.search().distance()) {
      case COSINE -> "<=>";
      case L2 -> "<->";
      case INNER_PRODUCT -> "<#>";
      case L1 -> "<+>";
    };
  }

  private boolean binary() {
    return profile.search().mode() == EmbeddingSearchPolicy.Mode.HNSW_BINARY
        || profile.search().mode() == EmbeddingSearchPolicy.Mode.IVFFLAT_BINARY;
  }

  public boolean exact() {
    return profile.search().mode() == EmbeddingSearchPolicy.Mode.EXACT;
  }

  /** Bounded oversampling followed by full-precision ranking, even for dense ANN indexes. */
  public int candidates(int limit) {
    if (limit < 1 || limit > 2000)
      throw new IllegalArgumentException("embedding result limit must be 1..2000");
    return exact() ? limit : Math.min(2000, Math.max(100, limit * 10));
  }

  public String indexName(EmbeddingWorkRepository.Store store) {
    return "emb_"
        + store.ordinal()
        + "_"
        + profile.slot().ordinal()
        + "_"
        + profile.search().mode().ordinal()
        + "_"
        + profile.search().distance().ordinal()
        + "_"
        + profile.space().id().substring(0, 32);
  }

  String createIndex(EmbeddingWorkRepository.Store store, String table, int lists) {
    if (exact()) throw new IllegalStateException("exact retrieval has no ANN index");
    String access =
        profile.search().mode().configurationName().startsWith("hnsw_") ? "hnsw" : "ivfflat";
    String type =
        binary() ? "bit" : profile.search().requiresFullPrecisionReranking() ? "halfvec" : "vector";
    String metric =
        binary()
            ? "hamming"
            : switch (profile.search().distance()) {
              case COSINE -> "cosine";
              case L2 -> "l2";
              case INNER_PRODUCT -> "ip";
              case L1 -> "l1";
            };
    // This transaction builds after copying the complete target corpus, so IVF training sees data.
    return "CREATE INDEX "
        + indexName(store)
        + " ON "
        + table
        + " USING "
        + access
        + " ("
        + representation(column())
        + " "
        + type
        + "_"
        + metric
        + "_ops)"
        + (access.equals("ivfflat") ? " WITH (lists=" + lists + ")" : "")
        + " WHERE "
        + profile.slot().stored()
        + "_space_id='"
        + profile.space().id()
        + "'";
  }
}
