package io.aeyer.plowshare.server.embedding;

import io.aeyer.plowshare.server.llm.tokens.Tokenizer;

/** Model-specific input measurement; independent of chat context estimates and database state. */
public interface EmbeddingTokenizers {
  /** Exact counter for this encoder identity, or an actionable configuration failure. */
  Tokenizer model(EmbeddingSpace.Definition definition);

  /** Legacy input counter; refuses use when no model tokenizer was explicitly configured. */
  Tokenizer legacy();

  /**
   * Counts a shared chunk with both document prefixes and both encoders, taking the larger count.
   */
  Tokenizer documents();

  /** Conservative common ceiling: the smaller of the two model allowances. */
  int documentLimit(int legacyLimit);

  /** Derivation identity includes tokenizer bytes, prefixes and input limits. */
  String fingerprint();
}
