package io.aeyer.plowshare.server.documents;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A language-neutral projection: contiguous source slices, with no prose reflow or summaries. */
public final class CodeDerivation {
  public static final String VERSION = "code-lines-v2";

  private CodeDerivation() {}

  public static DerivedDocument derive(Extracted source, Chunking bounds) {
    String text = source.text();
    if (text.isBlank()) return new DerivedDocument(Vocabulary.SECTION, List.of(), List.of());
    List<Chunker.Chunk> chunks = new ArrayList<>();
    int from = 0;
    while (from < text.length()) {
      int upper = Math.min(text.length(), from + 32768);
      if (upper < text.length() && Character.isHighSurrogate(text.charAt(upper - 1))) upper--;
      int low = 1, high = text.codePointCount(from, upper), fitting = 0;
      while (low <= high) {
        int middle = low + (high - low) / 2;
        int end = text.offsetByCodePoints(from, middle);
        if (bounds.tokens(text.substring(from, end)) <= bounds.targetTokens()) {
          fitting = middle;
          low = middle + 1;
        } else high = middle - 1;
      }
      if (fitting == 0)
        throw new IllegalArgumentException(
            "one source character exceeds the code chunk token budget");
      int to = text.offsetByCodePoints(from, fitting);
      if (to < text.length()) {
        int newline = text.lastIndexOf('\n', to - 1);
        if (newline >= from
            && bounds.tokens(text.substring(from, newline + 1)) <= bounds.targetTokens())
          to = newline + 1;
      }
      String slice = text.substring(from, to);
      if (bounds.tokens(slice) > bounds.maxTokens())
        throw new IllegalArgumentException(
            "code slice exceeds the embedding allowance after preprocessing");
      chunks.add(
          new Chunker.Chunk(
              slice, slice.getBytes(StandardCharsets.UTF_8).length, to < text.length()));
      from = to;
    }
    var paragraph = new DerivedParagraph(1, text, Derivation.sha256(text), 1, chunks);
    return new DerivedDocument(
        Vocabulary.SECTION,
        List.of(
            new DerivedDocument.Chapter(
                1,
                new StructuralRef.Synthetic(),
                List.of(
                    new DerivedDocument.Section(
                        1, new StructuralRef.Synthetic(), List.of(paragraph))))),
        List.of());
  }
}
