package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeDerivationTest {
  @Test
  void slices_preserve_source_including_comments_indentation_and_repeated_text() {
    String text =
        "// Comment. Another sentence.\nclass A {\n    String face = \"🙂\";\n\n    same();\n    same();\n}\n";
    var bounds = new Chunking(new RatioTokenizer(1), 24, 32);
    var derived =
        CodeDerivation.derive(new Extracted("A.java", "hash", text, List.of(), "none"), bounds);
    var paragraph = derived.paragraphs().getFirst();
    assertEquals(text, paragraph.text());
    assertEquals(
        text,
        paragraph.chunks().stream()
            .map(Chunker.Chunk::text)
            .collect(java.util.stream.Collectors.joining()));
    for (var chunk : paragraph.chunks()) {
      assertTrue(bounds.tokens(chunk.text()) <= bounds.maxTokens());
      assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
      assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
    }
    assertTrue(derived.references().isEmpty());
    assertTrue(derived.chapters().getFirst().title().isSynthetic());
  }
}
