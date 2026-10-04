package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tree {@link Derivation} now produces, and the two properties {@code DocumentStore} writes
 * against.
 *
 * <p>{@code DerivationTest} is about the run of paragraphs — the flat view V18's table stores. This
 * is about the shape over it, and about the one thing the two have to agree on: <b>a document's
 * paragraph ordinals run across the whole document, and a section's paragraphs are a contiguous
 * stretch of them.</b> That is what lets one statement point a whole section's paragraphs at their
 * row, and it is a property of the walk rather than of the schema, so it is asserted here.
 */
class DerivationTreeTest {

  private static DerivedDocument derive(String text) {
    return Derivation.derive(text, List.of(), ShippedChunking.SHIPPED);
  }

  private static final String PAPER =
      """
            1. Introduction
            The conjecture has stood for forty years.

            It is false, and we show why.

            2. Method
            We search the space with a learned policy.
            """;

  @Test
  void the_flat_run_is_the_tree_walked_in_order() {
    DerivedDocument derived = derive(PAPER);

    List<DerivedParagraph> walked = new ArrayList<>();
    for (DerivedDocument.Chapter chapter : derived.chapters()) {
      for (DerivedDocument.Section section : chapter.sections()) {
        walked.addAll(section.paragraphs());
      }
    }
    assertEquals(walked, derived.paragraphs());
  }

  @Test
  void ordinals_run_across_the_document_and_not_within_a_section() {
    DerivedDocument derived = derive(PAPER);

    assertEquals(
        List.of(1, 2, 3), derived.paragraphs().stream().map(DerivedParagraph::ordinal).toList());
    assertEquals(
        List.of(1, 2),
        derived.chapters().get(0).sections().get(0).paragraphs().stream()
            .map(DerivedParagraph::ordinal)
            .toList());
    assertEquals(
        List.of(3),
        derived.chapters().get(0).sections().get(1).paragraphs().stream()
            .map(DerivedParagraph::ordinal)
            .toList());
  }

  /**
   * The property {@code DocumentStore.insertHierarchy} writes against: a section is an ordinal
   * range, so it is one UPDATE and not one per row.
   */
  @Test
  void every_sections_paragraphs_are_a_contiguous_run_of_ordinals() {
    for (String text :
        List.of(
            PAPER,
            "No headings here at all.\n\nJust prose.\n",
            "Chapter 1 A\nWords here.\n\nChapter 2 B\nMore words here.\n")) {
      for (DerivedDocument.Chapter chapter : derive(text).chapters()) {
        for (DerivedDocument.Section section : chapter.sections()) {
          List<DerivedParagraph> held = section.paragraphs();
          for (int i = 1; i < held.size(); i++) {
            assertEquals(held.get(i - 1).ordinal() + 1, held.get(i).ordinal(), text);
          }
        }
      }
    }
  }

  /**
   * The occurrence counter is per document, so two identical paragraphs in two different sections
   * are still told apart — which is what keeps the identity rule working now that the split runs
   * per section.
   */
  @Test
  void the_occurrence_counter_does_not_restart_at_a_section_boundary() {
    DerivedDocument derived =
        derive(
            """
                1. Introduction
                Retries are budgeted per run.

                2. Method
                Retries are budgeted per run.
                """);

    assertEquals(
        List.of(1, 2), derived.paragraphs().stream().map(DerivedParagraph::occurrence).toList());
    assertEquals(
        derived.paragraphs().get(0).contentHash(), derived.paragraphs().get(1).contentHash());
  }

  @Test
  void a_document_with_no_text_derives_no_tree_and_no_bibliography() {
    DerivedDocument derived = derive("   \n\n  ");

    assertEquals(List.of(), derived.chapters());
    assertEquals(List.of(), derived.paragraphs());
    assertEquals(List.of(), derived.references());
    assertEquals(Vocabulary.SECTION, derived.vocabulary());
  }

  @Test
  void the_bibliography_travels_with_the_derivation_that_dropped_it() {
    DerivedDocument derived =
        derive(
            """
                1. Introduction
                The conjecture is false.

                References
                [1] Wagner, A. Constructions in combinatorics. 2021.
                """);

    assertEquals(1, derived.references().size());
    assertTrue(derived.paragraphs().stream().noneMatch(p -> p.text().contains("Wagner")));
  }

  @Test
  void the_outline_reaches_the_detector_through_the_extracted_record() {
    Extracted extracted =
        new Extracted(
            "paper",
            "hash",
            """
                Antichains
                A family of sets no one of which contains another.

                Diameters
                The largest distance between two members.
                """,
            List.of("Antichains", "Diameters"),
            Extracted.NOT_CONVERTED);

    DerivedDocument derived = Derivation.derive(extracted, ShippedChunking.SHIPPED);

    assertEquals(2, derived.chapters().size());
    assertEquals(
        "Antichains",
        derived.chapters().get(0).title().render(StructuralRef.WhenSynthetic.PLACEHOLDER));
  }
}
