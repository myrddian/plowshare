package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.List;

/** Pure text packing shared by derivation and the decision to spend a paragraph model call. */
public final class ParagraphPacking {
  /** Minimum whitespace-delimited words before a paragraph boundary is eligible. */
  public static final int MIN_WORDS = 80;

  private ParagraphPacking() {}

  /**
   * Whether text is too short to benefit from a separate paragraph summary. Numbers and labels
   * count as words too; this is a work-sizing heuristic, never a tokenizer or a claim detector.
   */
  public static boolean isShort(String text) {
    return words(text) < MIN_WORDS;
  }

  /**
   * Pack adjacent source blocks within one section without splitting any source block. Boundaries
   * are admitted only after 80 words; a short final remainder joins the previous paragraph. A whole
   * section shorter than 80 words stays one paragraph. Callers must not combine different sections.
   * Embedding chunks retain their independent, tokenizer-enforced input limits.
   */
  static List<String> pack(List<String> blocks) {
    List<String> paragraphs = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int count = 0;
    for (String block : blocks) {
      if (count >= MIN_WORDS) {
        paragraphs.add(current.toString());
        current.setLength(0);
        count = 0;
      }
      if (!current.isEmpty()) current.append(' ');
      current.append(block);
      count += words(block);
    }
    if (!current.isEmpty()) {
      if (count < MIN_WORDS && !paragraphs.isEmpty()) {
        int last = paragraphs.size() - 1;
        paragraphs.set(last, paragraphs.get(last) + " " + current);
      } else {
        paragraphs.add(current.toString());
      }
    }
    return List.copyOf(paragraphs);
  }

  private static int words(String text) {
    int count = 0;
    boolean inWord = false;
    for (int offset = 0; offset < text.length(); ) {
      int point = text.codePointAt(offset);
      boolean space = Character.isWhitespace(point) || Character.isSpaceChar(point);
      if (!space && !inWord) count++;
      inWord = !space;
      offset += Character.charCount(point);
    }
    return count;
  }
}
