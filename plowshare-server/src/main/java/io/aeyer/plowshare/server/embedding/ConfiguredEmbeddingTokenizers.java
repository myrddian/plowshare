package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.EmbeddingException;
import io.aeyer.plowshare.server.llm.tokens.*;
import java.util.*;

/**
 * Owns local tokenizer lifetimes. Unknown encoder revisions never reuse another model's counter.
 */
public final class ConfiguredEmbeddingTokenizers implements EmbeddingTokenizers, AutoCloseable {
  private record Encoder(String modelId, String modelRevision) {}

  private final Map<Encoder, PinnedTokenizer> models = new LinkedHashMap<>();
  private final List<EmbeddingProperties.Model> documents;
  private final Tokenizer legacy;

  public ConfiguredEmbeddingTokenizers(EmbeddingProperties dual, TokenizerFile legacyArtifact) {
    if (dual != null) dual.validate();
    documents = dual != null && dual.enabled() ? List.of(dual.code(), dual.prose()) : List.of();
    try {
      for (var model : documents) {
        var pinned = new PinnedTokenizer(model.tokenizer());
        Encoder key = key(model.definition());
        var existing = models.putIfAbsent(key, pinned);
        if (existing != null) {
          pinned.close();
          if (!existing.describe().endsWith(model.tokenizer().sha256()))
            throw new IllegalArgumentException(
                "one encoder identity has different tokenizer files");
        }
      }
      if (dual != null && dual.retainedTokenizers() != null) {
        for (var retained : dual.retainedTokenizers()) {
          Encoder key = new Encoder(retained.modelId(), retained.modelRevision());
          var pinned = new PinnedTokenizer(retained.tokenizer());
          var existing = models.putIfAbsent(key, pinned);
          if (existing != null) {
            pinned.close();
            if (!existing.describe().endsWith(retained.tokenizer().sha256()))
              throw new IllegalArgumentException(
                  "one encoder identity has different tokenizer files");
          }
        }
      }
      legacy = legacyArtifact == null ? unavailable() : new PinnedTokenizer(legacyArtifact);
    } catch (RuntimeException | Error failure) {
      models.values().forEach(PinnedTokenizer::close);
      throw failure;
    }
  }

  private static Encoder key(EmbeddingSpace.Definition definition) {
    return new Encoder(definition.modelId(), definition.modelRevision());
  }

  @Override
  public Tokenizer model(EmbeddingSpace.Definition definition) {
    var tokenizer = models.get(key(definition));
    if (tokenizer == null)
      throw new EmbeddingException(
          "no pinned tokenizer is configured for this embedding encoder revision");
    return tokenizer;
  }

  @Override
  public Tokenizer legacy() {
    return legacy;
  }

  @Override
  public Tokenizer documents() {
    if (documents.isEmpty()) return legacy;
    return new Tokenizer() {
      public TokenCount count(String text) {
        int maximum = 0;
        for (var entry : documents)
          maximum =
              Math.max(
                  maximum, model(entry.definition()).count(entry.documentPrefix() + text).tokens());
        return TokenCount.bound(maximum, fingerprint());
      }

      public String describe() {
        return fingerprint();
      }
    };
  }

  @Override
  public int documentLimit(int legacyLimit) {
    return documents.stream()
        .mapToInt(EmbeddingProperties.Model::maxInputTokens)
        .min()
        .orElse(legacyLimit);
  }

  @Override
  public String fingerprint() {
    if (documents.isEmpty()) return legacy.describe();
    return "dual-chunk-tokenizers-v2:"
        + documents.stream()
            .map(
                entry ->
                    java.util.stream.Stream.of(
                            entry.modelId(),
                            entry.modelRevision(),
                            entry.tokenizer().sha256(),
                            entry.documentPrefix(),
                            Integer.toString(entry.maxInputTokens()))
                        .map(field -> field.length() + ":" + field)
                        .reduce("", String::concat))
            .reduce("", String::concat);
  }

  @Override
  public void close() {
    models.values().forEach(PinnedTokenizer::close);
    if (legacy instanceof PinnedTokenizer pinned) pinned.close();
  }

  private static Tokenizer unavailable() {
    return new Tokenizer() {
      public TokenCount count(String text) {
        throw new EmbeddingException(
            "configure plowshare.llm.embedding-tokenizer.file and sha256, or enable both embedding slots with pinned tokenizers; estimates cannot enforce embedding input limits");
      }

      public String describe() {
        return "embedding tokenizer unconfigured";
      }
    };
  }
}
