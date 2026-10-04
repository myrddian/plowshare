package io.aeyer.plowshare.server.llm.tokens;

/**
 * The fallback: characters divided by a ratio.
 *
 * <p>This is DSH's meter — "a 4-chars-per-token heuristic anchored on the last provider {@code
 * usage}" — with the anchoring made explicit and the ratio made configuration rather than a
 * constant. It is a model of the text and not a count of it, so every answer it gives is {@link
 * TokenCount.Basis#ESTIMATED} and says so.
 *
 * <h2>It can be wrong in both directions, which is why it is not a bound</h2>
 *
 * <p>English prose runs near four characters a token; dense identifiers, JSON, base64 and any
 * script without spaces run far denser, and a CJK line can be more tokens than it has characters.
 * {@code Chunker}'s UTF-8 ceiling exists precisely because a ratio has no safe direction, and it
 * stays a {@link TokenCount.Basis#BOUND} for that reason: the two are different claims and this
 * class does not replace it.
 *
 * <h2>Anchoring</h2>
 *
 * <p>{@link #anchoredOn} is the part worth having. A conversation's projected prompt is exactly
 * reconstructible on this server with no model involved, and every measured turn carries {@code
 * usage.prompt_tokens} for that same prompt. Their quotient is this model's real
 * characters-per-token, on this corpus, measured — so the fallback stops being a guess about text
 * in general and becomes a calibration against the text actually being sent. The estimate is still
 * an estimate; it is just no longer a number somebody picked.
 */
public final class RatioTokenizer implements Tokenizer {

  /**
   * The starting ratio where nothing has been observed yet.
   *
   * <p>Four is DSH's number and it is a reasonable prior for prose. It is a default and not a
   * truth: {@link #anchoredOn} replaces it with a measurement as soon as one exists.
   */
  public static final double DEFAULT_CHARACTERS_PER_TOKEN = 4.0;

  private final double charactersPerToken;
  private final String how;

  public RatioTokenizer(double charactersPerToken) {
    this(
        charactersPerToken,
        String.format(
            "estimated at %.2f characters per token, the configured default — nothing on"
                + " this deployment has been measured against it",
            charactersPerToken));
  }

  private RatioTokenizer(double charactersPerToken, String how) {
    if (!Double.isFinite(charactersPerToken) || charactersPerToken <= 0) {
      throw new IllegalArgumentException(
          "characters per token is a positive ratio: " + charactersPerToken);
    }
    this.charactersPerToken = charactersPerToken;
    this.how = how;
  }

  /**
   * The same estimator, calibrated against one prompt somebody actually counted.
   *
   * @param characters how many characters that prompt was, computed here
   * @param measuredTokens what the model's own tokenizer made of it
   * @throws IllegalArgumentException if either is not positive — a zero measurement is not a
   *     measurement, and dividing by one is how a calibration turns into an infinity nobody notices
   */
  public static RatioTokenizer anchoredOn(int characters, int measuredTokens) {
    if (characters <= 0 || measuredTokens <= 0) {
      throw new IllegalArgumentException(
          "an anchor needs both a length and a count: "
              + characters
              + " characters, "
              + measuredTokens
              + " tokens");
    }
    double ratio = (double) characters / measuredTokens;
    return new RatioTokenizer(
        ratio,
        String.format(
            "estimated at %.2f characters per token, measured from this model's own count of"
                + " a %d-character prompt as %d tokens",
            ratio, characters, measuredTokens));
  }

  public double charactersPerToken() {
    return charactersPerToken;
  }

  @Override
  public TokenCount count(String text) {
    if (text == null || text.isEmpty()) {
      // Zero characters really is zero tokens, and this is the one place
      // on this surface where a zero is a measurement rather than an
      // absence: there is nothing to be wrong about.
      return TokenCount.measured(0, "empty, which is nought tokens on any tokenizer");
    }
    return TokenCount.estimated((int) Math.ceil(text.length() / charactersPerToken), how);
  }

  @Override
  public String describe() {
    return how;
  }
}
