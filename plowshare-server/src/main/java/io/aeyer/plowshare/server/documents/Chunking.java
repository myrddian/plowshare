package io.aeyer.plowshare.server.documents;

import io.aeyer.plowshare.server.llm.tokens.Tokenizer;
import java.util.Objects;

/**
 * What a chunk's size is measured with, and the two bounds on it.
 *
 * <p><b>One value and not three arguments</b>, because a count of tokens means something only
 * beside the tokenizer that produced it: a target of 400 is a different chunk under a tokenizer
 * that charges a token per character than under one that charges a token per four. Travelling
 * together, the bounds cannot be handed to a chunker counting with something else.
 *
 * <p>The tokenizer is the interface and never a named implementation. The one that ships is {@code
 * RatioTokenizer}, a heuristic; it is there to be replaced by a real tokenizer behind the same
 * interface, and nothing that chunks may depend on which one it was given.
 *
 * @param tokenizer what every bound here is counted with
 * @param targetTokens what packing aims at. Whole sentences are added while they fit under this, so
 *     an ordinary chunk lands at or under it
 * @param maxTokens the ceiling nothing may exceed: {@code
 *     plowshare.llm.embedding-max-input-tokens}, the number the embedding client refuses above, and
 *     the number that must never be crossed rather than the number packing aims at
 */
public record Chunking(Tokenizer tokenizer, int targetTokens, int maxTokens) {

  /**
   * @throws IllegalArgumentException if either bound is non-positive, or if the target is above the
   *     ceiling — a pair that could not be honoured, refused where the pair is made rather than at
   *     the embed call
   */
  public Chunking {
    Objects.requireNonNull(tokenizer, "a token bound needs the tokenizer that counts it");
    if (targetTokens <= 0 || maxTokens <= 0) {
      throw new IllegalArgumentException(
          "a chunk bound has to be a positive number of tokens; asked for a target of "
              + targetTokens
              + " under a ceiling of "
              + maxTokens);
    }
    if (targetTokens > maxTokens) {
      throw new IllegalArgumentException(
          "a chunk target of "
              + targetTokens
              + " tokens is above the ceiling of "
              + maxTokens
              + " tokens, so packing would aim past what the embedding"
              + " endpoint accepts; lower plowshare.documents.chunk-target-tokens"
              + " or raise plowshare.llm.embedding-max-input-tokens");
    }
  }

  /** What {@code text} costs, in the unit both bounds are in. */
  int tokens(String text) {
    return tokenizer.count(text).tokens();
  }
}
