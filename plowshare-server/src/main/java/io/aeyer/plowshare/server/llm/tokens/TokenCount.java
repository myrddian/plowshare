package io.aeyer.plowshare.server.llm.tokens;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * How many tokens something is, and <b>how the answer was arrived at</b>.
 *
 * <p>The count travels with its provenance because this server has three ways of knowing and they
 * are not interchangeable. A number alone would let an estimate be rendered, stored or compared as
 * though a tokenizer had produced it, which is the failure every absence on {@code ContextView} was
 * written to avoid — and avoided by refusing to answer at all, which turned out to be the wrong
 * half of the trade. The right half is to answer and say what the answer is worth.
 *
 * <p><b>{@link Basis#MEASURED} wins wherever it exists.</b> That is the whole ordering rule: a real
 * count is never overwritten by an estimate, and {@link #better} is where it is enforced rather
 * than at each call site.
 */
public record TokenCount(int tokens, Basis basis, String how) {

  /** What kind of knowledge a count is, worst to best. */
  public enum Basis {
    /**
     * A ratio, a heuristic, a model of the text. Useful and not a fact: it can be wrong in either
     * direction and by an unbounded amount.
     */
    ESTIMATED,
    /**
     * A ceiling the real count cannot exceed. Weaker than a measurement and stronger than an
     * estimate, because the direction of its error is known — {@code Chunker} relies on exactly
     * this property.
     */
    BOUND,
    /**
     * Counted by the tokenizer that will actually tokenize it. The only basis on which a number
     * here is a fact.
     */
    MEASURED,
  }

  public TokenCount {
    if (tokens < 0) {
      throw new IllegalArgumentException("a count of tokens is not negative: " + tokens);
    }
    if (basis == null) {
      throw new IllegalArgumentException("a count without a basis is a number nobody can weigh");
    }
    if (how == null || how.isBlank()) {
      throw new IllegalArgumentException("a count says how it knows, or it does not travel");
    }
  }

  public static TokenCount measured(int tokens, String how) {
    return new TokenCount(tokens, Basis.MEASURED, how);
  }

  public static TokenCount estimated(int tokens, String how) {
    return new TokenCount(tokens, Basis.ESTIMATED, how);
  }

  public static TokenCount bound(int tokens, String how) {
    return new TokenCount(tokens, Basis.BOUND, how);
  }

  /**
   * Whether this is a fact rather than a model of one.
   *
   * <p>{@code @JsonIgnore} because it is a question callers ask of the basis, not a field:
   * serialised, it would put a second, derived spelling of {@code basis} on the wire for a client
   * to read instead of the real one -- and one that collapses BOUND and ESTIMATED into the same
   * false.
   */
  @JsonIgnore
  public boolean isMeasured() {
    return basis == Basis.MEASURED;
  }

  /**
   * The better-founded of two counts, which is the rule "real values trump the heuristic when
   * obtained" with one place to enforce it.
   *
   * <p>Ties go to the incumbent: two counts on the same basis are two equally good answers, and
   * preferring the newcomer would make the result depend on the order they happened to arrive in.
   */
  public TokenCount better(TokenCount other) {
    if (other == null) {
      return this;
    }
    return other.basis.compareTo(basis) > 0 ? other : this;
  }
}
