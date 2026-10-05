package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.EmbeddingException;
import java.util.Arrays;
import java.util.Objects;

/** Validated, immutable query vector paired with the exact captured retrieval space. */
public record EmbeddingQuery(EmbeddingProfile profile, float[] values) {
  public EmbeddingQuery {
    Objects.requireNonNull(profile, "profile");
    values = validate(profile, values);
  }

  @Override
  public float[] values() {
    return values.clone();
  }

  public String vectorText() {
    return Arrays.toString(values);
  }

  static float[] validate(EmbeddingProfile profile, float[] values) {
    float[] checked = validate(profile.space(), values);
    if (profile.search().mode() == EmbeddingSearchPolicy.Mode.HNSW_HALF
        || profile.search().mode() == EmbeddingSearchPolicy.Mode.IVFFLAT_HALF) {
      boolean representable = false;
      for (float value : checked) {
        if (Math.abs(value) > 65504)
          throw new EmbeddingException(
              "embedding output overflows the selected half precision index");
        representable |= Math.abs(value) >= 0.00000003;
      }
      if (!representable)
        throw new EmbeddingException(
            "embedding output becomes zero in the selected half precision index");
    }
    return checked;
  }

  static float[] validate(EmbeddingSpace space, float[] values) {
    if (values == null || values.length != space.definition().dimensions())
      throw new EmbeddingException("embedding output width does not match its registered space");
    double norm = 0;
    for (float value : values) {
      if (!Float.isFinite(value)) throw new EmbeddingException("non-finite embedding output");
      norm += (double) value * value;
    }
    if (norm == 0) throw new EmbeddingException("zero embedding output");
    if (space.definition().normalization() == EmbeddingSpace.Normalization.L2
        && Math.abs(Math.sqrt(norm) - 1) > 0.001)
      throw new EmbeddingException("embedding output violates its normalization contract");
    return values.clone();
  }
}
