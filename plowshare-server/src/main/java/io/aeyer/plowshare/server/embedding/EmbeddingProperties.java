package io.aeyer.plowshare.server.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit two-slot configuration. Legacy deployments remain opt-out until both are declared.
 * Search spending is independent: unlimited is the default, zero disables, positive caps calls.
 */
@ConfigurationProperties(prefix = "plowshare.embeddings", ignoreUnknownFields = false)
public record EmbeddingProperties(
    boolean enabled,
    Model code,
    Model prose,
    boolean paused,
    java.util.List<TokenizerBinding> retainedTokenizers,
    String searchBudget) {
  public EmbeddingProperties(
      boolean enabled,
      Model code,
      Model prose,
      boolean paused,
      java.util.List<TokenizerBinding> retainedTokenizers) {
    this(enabled, code, prose, paused, retainedTokenizers, "unlimited");
  }

  public EmbeddingProperties(
      boolean enabled,
      Model code,
      Model prose,
      boolean paused,
      java.util.List<TokenizerBinding> retainedTokenizers,
      int searchBudget) {
    this(enabled, code, prose, paused, retainedTokenizers, Integer.toString(searchBudget));
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public EmbeddingProperties {
    searchBudget =
        searchBudget == null || searchBudget.isBlank() ? "unlimited" : searchBudget.strip();
    searchCeiling(searchBudget);
  }

  /** Converts configuration to the existing nullable Budget.Claim ceiling; null is unlimited. */
  public Integer searchLimit() {
    return searchCeiling(searchBudget);
  }

  private static Integer searchCeiling(String policy) {
    return SpendingPolicy.limit(policy, "plowshare.embeddings.search-budget");
  }

  public void validate() {
    if (!enabled) return;
    if (retainedTokenizers != null && retainedTokenizers.size() > 16)
      throw new IllegalArgumentException(
          "at most 16 retained embedding tokenizers may be configured");
    if (code == null || prose == null)
      throw new IllegalArgumentException(
          "plowshare.embeddings requires both code and prose models");
    if (code.tokenizer() == null || prose.tokenizer() == null)
      throw new IllegalArgumentException(
          "both embedding slots require a pinned tokenizer file and sha256");
    code.definition();
    prose.definition();
    if (code.modelId().equals(prose.modelId())
        && !sameEncoder(code.definition(), prose.definition()))
      throw new IllegalArgumentException(
          "one served model name cannot identify different code/prose encoders");
  }

  static boolean sameEncoder(EmbeddingSpace.Definition left, EmbeddingSpace.Definition right) {
    return left.modelRevision().equals(right.modelRevision())
        && left.dimensions() == right.dimensions()
        && left.pooling().equals(right.pooling())
        && left.reduction().equals(right.reduction());
  }

  /** Tokenizers for serving generations retained while a replacement encoder rebuilds. */
  public record TokenizerBinding(
      String modelId,
      String modelRevision,
      io.aeyer.plowshare.server.llm.tokens.TokenizerFile tokenizer) {
    public TokenizerBinding {
      if (modelId == null
          || modelId.isBlank()
          || modelRevision == null
          || modelRevision.isBlank()
          || tokenizer == null)
        throw new IllegalArgumentException(
            "retained tokenizer requires an encoder identity and pinned artifact");
    }
  }

  public Model model(EmbeddingSlot slot) {
    return slot == EmbeddingSlot.CODE ? code : prose;
  }

  /** Prefixes, revision, pooling and reduction are required, including explicit empty prefixes. */
  public record Model(
      String modelId,
      String modelRevision,
      int dimensions,
      String queryPrefix,
      String documentPrefix,
      String pooling,
      EmbeddingSpace.Normalization normalization,
      String reduction,
      int maxInputTokens,
      String searchMode,
      EmbeddingSearchPolicy.Distance distance,
      io.aeyer.plowshare.server.llm.tokens.TokenizerFile tokenizer) {
    public EmbeddingSpace.Definition definition() {
      if (maxInputTokens < 1)
        throw new IllegalArgumentException("embedding max-input-tokens must be positive");
      var definition =
          new EmbeddingSpace.Definition(
              modelId,
              modelRevision,
              dimensions,
              queryPrefix,
              documentPrefix,
              pooling,
              normalization,
              reduction);
      policy().validate(definition);
      return definition;
    }

    public EmbeddingSearchPolicy policy() {
      return new EmbeddingSearchPolicy(
          EmbeddingSearchPolicy.Mode.fromConfiguration(searchMode), distance);
    }
  }
}
