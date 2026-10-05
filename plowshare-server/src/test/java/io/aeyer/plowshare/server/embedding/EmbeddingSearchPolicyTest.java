package io.aeyer.plowshare.server.embedding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Policy validation is independent of PostgreSQL and model calls. */
class EmbeddingSearchPolicyTest {
  @ParameterizedTest
  @EnumSource(EmbeddingSearchPolicy.Mode.class)
  void validatesSelectedModeBeforeIndexAdministration(EmbeddingSearchPolicy.Mode mode) {
    var policy = new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.COSINE);
    assertDoesNotThrow(() -> policy.validate(space(1)));
    assertDoesNotThrow(() -> policy.validate(space(mode.maximumDimensions())));
    if (mode.maximumDimensions() < 16000) {
      assertThrows(
          IllegalArgumentException.class,
          () -> policy.validate(space(mode.maximumDimensions() + 1)));
    }
    assertEquals(mode, EmbeddingSearchPolicy.Mode.fromConfiguration(mode.configurationName()));
  }

  @Test
  void largeWidthsHaveExplicitExactAndBinaryOptions() {
    for (int width : new int[] {768, 1024, 1536, 2048, 3072, 4096, 8192, 16000}) {
      for (var mode :
          new EmbeddingSearchPolicy.Mode[] {
            EmbeddingSearchPolicy.Mode.EXACT,
            EmbeddingSearchPolicy.Mode.HNSW_BINARY,
            EmbeddingSearchPolicy.Mode.IVFFLAT_BINARY
          }) {
        for (var distance : EmbeddingSearchPolicy.Distance.values()) {
          assertDoesNotThrow(
              () -> new EmbeddingSearchPolicy(mode, distance).validate(space(width)));
        }
      }
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EmbeddingSearchPolicy(
                    EmbeddingSearchPolicy.Mode.HNSW_HALF, EmbeddingSearchPolicy.Distance.COSINE)
                .validate(space(4096)));
  }

  @Test
  void unsupportedNamesAndDistanceCombinationsFailClearly() {
    for (String unknown :
        new String[] {"", "auto", "HNSW_HALF", "hnsw_half ", "custom_sql", null}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> EmbeddingSearchPolicy.Mode.fromConfiguration(unknown));
    }
    for (var mode :
        new EmbeddingSearchPolicy.Mode[] {
          EmbeddingSearchPolicy.Mode.IVFFLAT_VECTOR, EmbeddingSearchPolicy.Mode.IVFFLAT_HALF
        }) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.L1));
    }
    assertThrows(
        NullPointerException.class,
        () -> new EmbeddingSearchPolicy(null, EmbeddingSearchPolicy.Distance.COSINE));
    assertThrows(
        NullPointerException.class,
        () -> new EmbeddingSearchPolicy(EmbeddingSearchPolicy.Mode.EXACT, null));
  }

  @Test
  void quantizedCandidateModesRequireFullPrecisionReranking() {
    for (var mode :
        new EmbeddingSearchPolicy.Mode[] {
          EmbeddingSearchPolicy.Mode.HNSW_HALF,
          EmbeddingSearchPolicy.Mode.IVFFLAT_HALF,
          EmbeddingSearchPolicy.Mode.HNSW_BINARY,
          EmbeddingSearchPolicy.Mode.IVFFLAT_BINARY
        }) {
      assertTrue(
          new EmbeddingSearchPolicy(mode, EmbeddingSearchPolicy.Distance.COSINE)
              .requiresFullPrecisionReranking());
    }
    assertFalse(
        new EmbeddingSearchPolicy(
                EmbeddingSearchPolicy.Mode.EXACT, EmbeddingSearchPolicy.Distance.COSINE)
            .requiresFullPrecisionReranking());
    assertFalse(
        new EmbeddingSearchPolicy(
                EmbeddingSearchPolicy.Mode.HNSW_VECTOR, EmbeddingSearchPolicy.Distance.COSINE)
            .requiresFullPrecisionReranking());
  }

  private static EmbeddingSpace.Definition space(int width) {
    return new EmbeddingSpace.Definition(
        "fixture", "revision", width, "", "", "mean", EmbeddingSpace.Normalization.L2, "none");
  }
}
