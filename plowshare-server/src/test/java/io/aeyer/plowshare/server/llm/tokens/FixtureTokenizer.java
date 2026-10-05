package io.aeyer.plowshare.server.llm.tokens;

/** Deterministic measured fixture vocabulary; not an embedding-model tokenizer. */
public final class FixtureTokenizer implements Tokenizer {
  private final double charactersPerToken;

  public FixtureTokenizer(double charactersPerToken) {
    this.charactersPerToken = charactersPerToken;
  }

  public TokenCount count(String text) {
    return TokenCount.measured(
        text == null ? 0 : (int) Math.ceil(text.length() / charactersPerToken),
        "fixture vocabulary");
  }

  public String describe() {
    return "fixture vocabulary";
  }
}
