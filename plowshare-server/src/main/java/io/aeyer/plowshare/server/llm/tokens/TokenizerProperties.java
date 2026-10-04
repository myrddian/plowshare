package io.aeyer.plowshare.server.llm.tokens;

/**
 * Which tokenizer this deployment has, and what to fall back to when it has none.
 *
 * <p>Nested under {@code plowshare.llm}, whose prefix is closed ({@code ignoreUnknownFields =
 * false}), so a key here exists in code before it can appear in an environment — never the other
 * way round.
 *
 * <p><b>The default is the fallback</b>, because the reference node has no tokenizer endpoint and
 * pretending otherwise would make every count on this server silently wrong rather than visibly
 * estimated.
 */
public class TokenizerProperties {

  /**
   * Which implementation to build.
   *
   * <p>{@code ratio} is the only one this server ships. The key exists so that a deployment which
   * acquires a real tokenizer names it here and every caller starts getting {@link
   * TokenCount.Basis#MEASURED} counts without a change anywhere else — which is the entire purpose
   * of the interface.
   *
   * <p>An unrecognised name fails the startup rather than falling back: a deployment that asked for
   * a real tokenizer and silently got a heuristic would report estimates as though they had been
   * measured, and the misconfiguration is invisible from the outside.
   */
  private String implementation = "ratio";

  /**
   * The fallback's characters-per-token, used until something is measured.
   *
   * <p>See {@link RatioTokenizer#DEFAULT_CHARACTERS_PER_TOKEN} for why four, and {@link
   * RatioTokenizer#anchoredOn} for what replaces it.
   */
  private double charactersPerToken = RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN;

  public String getImplementation() {
    return implementation;
  }

  public void setImplementation(String implementation) {
    this.implementation = implementation;
  }

  public double getCharactersPerToken() {
    return charactersPerToken;
  }

  public void setCharactersPerToken(double charactersPerToken) {
    this.charactersPerToken = charactersPerToken;
  }

  /** The tokenizer this configuration asks for, or a refusal naming what it got. */
  public Tokenizer build() {
    if ("ratio".equalsIgnoreCase(implementation)) {
      return new RatioTokenizer(charactersPerToken);
    }
    throw new IllegalStateException(
        "plowshare.llm.tokenizer.implementation is '"
            + implementation
            + "', and this server builds only 'ratio'. Refusing to start rather than"
            + " falling back: a deployment that asked for a real tokenizer and"
            + " quietly got a heuristic would report estimates as measurements.");
  }
}
