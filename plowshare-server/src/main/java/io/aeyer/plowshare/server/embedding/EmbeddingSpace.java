package io.aeyer.plowshare.server.embedding;

import java.util.Objects;

/** Immutable identity of comparable vectors. Provider addresses are deliberately excluded. */
public record EmbeddingSpace(String id, Definition definition) {
  public EmbeddingSpace {
    requireId(id);
    Objects.requireNonNull(definition, "embedding space definition");
  }

  public static void requireId(String id) {
    if (id == null || !id.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "embedding space ID must be a lowercase SHA-256 fingerprint");
    }
  }

  /**
   * Describes the exact encoder and preprocessing; equal widths alone do not imply compatibility.
   */
  public record Definition(
      String modelId,
      String modelRevision,
      int dimensions,
      String queryPrefix,
      String documentPrefix,
      String pooling,
      Normalization normalization,
      String reduction) {
    public Definition {
      named(modelId, "model ID");
      named(modelRevision, "model revision");
      if (dimensions < 1 || dimensions > 16000) {
        throw new IllegalArgumentException("embedding dimensions must be between 1 and 16000");
      }
      prefix(queryPrefix);
      prefix(documentPrefix);
      named(pooling, "pooling");
      Objects.requireNonNull(normalization, "normalization");
      named(reduction, "reduction");
    }

    private static void named(String value, String field) {
      if (value == null
          || value.isBlank()
          || !value.equals(value.strip())
          || value.codePointCount(0, value.length()) > 512
          || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("invalid embedding " + field);
      }
    }

    private static void prefix(String value) {
      // Prefix whitespace is significant and must not be stripped during validation.
      if (value == null
          || value.codePointCount(0, value.length()) > 16384
          || value.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("invalid embedding task prefix");
      }
    }
  }

  public enum Normalization {
    NONE("none"),
    L2("l2");

    private final String stored;

    Normalization(String stored) {
      this.stored = stored;
    }

    public String stored() {
      return stored;
    }

    public static Normalization fromStored(String value) {
      return switch (value) {
        case "none" -> NONE;
        case "l2" -> L2;
        default -> throw new IllegalArgumentException("unknown embedding normalization");
      };
    }
  }
}
