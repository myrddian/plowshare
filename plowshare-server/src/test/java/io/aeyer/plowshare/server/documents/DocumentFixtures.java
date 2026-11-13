package io.aeyer.plowshare.server.documents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicit small paragraph fixtures for persistence, citation and retrieval tests. Those tests
 * exercise the store's handling of supplied units, including historical short units; production
 * derivation's minimum-size packing is tested separately and must not change their row cardinality.
 */
final class DocumentFixtures {
  private DocumentFixtures() {}

  /** Model-call fixtures need full prose units, rather than the literal short-text fast path. */
  static String modelParagraphs(String sourceName, String text) {
    var extracted =
        TextExtraction.extract(sourceName, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var fixture = separateBlocks(extracted, ShippedChunking.SHIPPED);
    String result = text;
    for (String paragraph :
        fixture.paragraphs().stream().map(DerivedParagraph::text).distinct().toList()) {
      result =
          result.replace(
              paragraph,
              paragraph
                  + " "
                  + ("This experimental observation describes the measured behaviour under controlled conditions "
                          + "and records the evidence needed to reproduce the result. ")
                      .repeat(4)
                      .strip());
    }
    return result;
  }

  static DerivedDocument separateBlocks(Extracted extracted, Chunking chunking) {
    String[] lines = extracted.text().split("\n", -1);
    List<DerivedDocument.Chapter> chapters = new ArrayList<>();
    Map<String, Integer> occurrences = new HashMap<>();
    int ordinal = 0;
    for (var chapter : ChapterDetector.detect(extracted.text(), extracted.outlineTopLevel())) {
      List<DerivedDocument.Section> sections = new ArrayList<>();
      for (var section :
          SectionDetector.detect(lines, chapter.bodyStartLine(), chapter.endLine())) {
        List<DerivedParagraph> paragraphs = new ArrayList<>();
        String body =
            String.join(
                "\n",
                java.util.Arrays.copyOfRange(lines, section.bodyStartLine(), section.endLine()));
        for (String block : body.split("\n\\s*\n")) {
          String text = block.strip().replaceAll("\\s+", " ");
          if (text.isEmpty()) continue;
          String hash = Derivation.sha256(text);
          paragraphs.add(
              new DerivedParagraph(
                  ++ordinal,
                  text,
                  hash,
                  occurrences.merge(hash, 1, Integer::sum),
                  Chunker.chunk(text, chunking)));
        }
        sections.add(new DerivedDocument.Section(section.ordinal(), section.title(), paragraphs));
      }
      chapters.add(new DerivedDocument.Chapter(chapter.ordinal(), chapter.title(), sections));
    }
    return new DerivedDocument(
        ChapterDetector.detectVocabulary(extracted.text()),
        chapters,
        ReferencesExtractor.extract(extracted.text()));
  }
}
