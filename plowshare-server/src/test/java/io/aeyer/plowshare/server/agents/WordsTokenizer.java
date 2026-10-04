package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.llm.tokens.TokenCount;
import io.aeyer.plowshare.server.llm.tokens.Tokenizer;

/**
 * A tokenizer a test can count by hand: one token per word, and one per blank line, so a count of
 * several texts joined a blank line apart is not the sum of their counts.
 */
final class WordsTokenizer implements Tokenizer {

  @Override
  public TokenCount count(String text) {
    String words = text.strip();
    int count = words.isEmpty() ? 0 : words.split("\\s+").length;
    int blankLines = text.split("\n\n", -1).length - 1;
    return TokenCount.estimated(count + blankLines, "a word or a blank line each, for a test");
  }

  @Override
  public String describe() {
    return "a word or a blank line each, for a test";
  }

  /** {@code count} words, each {@code w}, a space apart. */
  static String words(int count) {
    return "w ".repeat(count).strip();
  }
}
