package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Text into the paragraphs and chunks a document is stored as. */
class DerivationTest {

  private static List<DerivedParagraph> derive(String text) {
    // The flat run of paragraphs the corpus stores, which is a view over the
    // hierarchy the derivation now also produces. Every assertion in this
    // class is about the run and none is about the tree; DerivationTree is
    // where the tree is pinned.
    return Derivation.derive(text, List.of(), ShippedChunking.SHIPPED).paragraphs();
  }

  // --- splitting -----------------------------------------------------------

  @Test
  void blank_lines_do_not_split_short_blocks_into_processing_paragraphs() {
    List<DerivedParagraph> paragraphs = derive("First one.\n\nSecond one.\n\n\nThird one.");

    assertEquals(
        List.of("First one. Second one. Third one."),
        paragraphs.stream().map(DerivedParagraph::text).toList());
  }

  /**
   * Contiguous lines are re-flowed into one string.
   *
   * <p>Anchor's reason ports unchanged: PDF and hard-wrapped text break lines mid-sentence, and a
   * chunker that saw the breaks would split a claim in half at a place the author did not choose.
   */
  @Test
  void wrapped_lines_are_reflowed_into_one_paragraph() {
    List<DerivedParagraph> paragraphs =
        derive("The retry budget is four\nattempts, and has been\nsince the timeout change.");

    assertEquals(1, paragraphs.size());
    assertEquals(
        "The retry budget is four attempts, and has been since the timeout change.",
        paragraphs.get(0).text());
  }

  @Test
  void leading_and_trailing_blank_lines_are_not_paragraphs() {
    assertEquals(1, derive("\n\n\nOnly one.\n\n\n").size());
  }

  @Test
  void text_with_nothing_in_it_derives_nothing() {
    assertEquals(List.of(), derive(""));
    assertEquals(List.of(), derive("   \n \n  "));
    assertEquals(List.of(), derive(null));
  }

  /**
   * Ordinals are 1-based and dense, which is what the unique index on {@code (document_id,
   * ordinal)} stores.
   */
  @Test
  void ordinals_start_at_one_and_are_dense() {
    List<DerivedParagraph> paragraphs =
        derive(longText("A.") + "\n\n" + longText("B.") + "\n\n" + longText("C."));

    assertEquals(List.of(1, 2, 3), paragraphs.stream().map(DerivedParagraph::ordinal).toList());
  }

  // --- identity ------------------------------------------------------------

  @Test
  void the_same_text_hashes_the_same_and_different_text_does_not() {
    List<DerivedParagraph> first = derive("The retry budget is four.");
    List<DerivedParagraph> same = derive("The retry budget is four.");
    List<DerivedParagraph> other = derive("The retry budget is five.");

    assertEquals(first.get(0).contentHash(), same.get(0).contentHash());
    assertNotEquals(first.get(0).contentHash(), other.get(0).contentHash());
  }

  /**
   * The occurrence term, and the reason it exists.
   *
   * <p>A repeated heading, a refrain, a boilerplate footer: identical text appearing more than once
   * in one document. Without the occurrence there is one identity for two rows and the second one
   * can never keep an id of its own.
   */
  @Test
  void identical_paragraphs_in_one_document_are_told_apart_by_occurrence() {
    List<DerivedParagraph> paragraphs =
        derive(
            longText("Notes")
                + "\n\n"
                + longText("Something.")
                + "\n\n"
                + longText("Notes")
                + "\n\n"
                + longText("Notes"));

    assertEquals(
        List.of(1, 1, 2, 3), paragraphs.stream().map(DerivedParagraph::occurrence).toList());
    assertEquals(paragraphs.get(0).contentHash(), paragraphs.get(2).contentHash());
  }

  /**
   * Occurrence counts within a hash, not across the document.
   *
   * <p>The first "Notes" and the first "Other" are both occurrence 1. A single counter would make
   * the pair unmatchable the moment anything reordered.
   */
  @Test
  void occurrence_is_counted_within_a_hash() {
    List<DerivedParagraph> paragraphs =
        derive(
            longText("Notes")
                + "\n\n"
                + longText("Other")
                + "\n\n"
                + longText("Notes")
                + "\n\n"
                + longText("Other"));

    assertEquals(
        List.of(1, 1, 2, 2), paragraphs.stream().map(DerivedParagraph::occurrence).toList());
  }

  // --- chunking ------------------------------------------------------------

  @Test
  void every_paragraph_carries_its_chunks_in_order() {
    List<DerivedParagraph> paragraphs =
        derive(longText("Short one.") + "\n\n" + "A sentence. ".repeat(400));

    assertEquals(1, paragraphs.get(0).chunks().size());
    assertTrue(paragraphs.get(1).chunks().size() > 1);
    for (DerivedParagraph paragraph : paragraphs) {
      for (Chunker.Chunk chunk : paragraph.chunks()) {
        assertTrue(ShippedChunking.SHIPPED.tokens(chunk.text()) <= ShippedChunking.MAX);
      }
    }
  }

  /**
   * No paragraph is stored with no chunks: a paragraph that produced none would be unsearchable and
   * nothing would say so.
   */
  @Test
  void no_derived_paragraph_is_chunkless() {
    for (DerivedParagraph paragraph : derive("A.\n\nB.\n\n" + "x".repeat(9000))) {
      assertTrue(paragraph.chunks().size() >= 1, "a paragraph derived no chunks");
    }
  }

  @Test
  void inferred_equations_keep_their_preceding_prose_when_packed() {
    String first = longText("The bound is tight.");
    String second = longText("And the argument continues.");
    List<String> paragraphs = inferred(first + "\n\n(1-X)A(X)k = (1-X)\n\n" + second);
    assertEquals(List.of(first + " (1-X)A(X)k = (1-X)", second), paragraphs);
  }

  @Test
  void short_authored_text_is_packed_without_changing_the_retained_extraction() {
    String text = "Yes.\n\nNo.\n\nMaybe.";
    var extracted = new Extracted("Dialogue", "hash", text, List.of(), Extracted.NOT_CONVERTED);
    assertEquals(
        List.of("Yes. No. Maybe."),
        Derivation.derive(extracted, ShippedChunking.SHIPPED).paragraphs().stream()
            .map(DerivedParagraph::text)
            .toList());
    assertEquals(text, extracted.text());
  }

  @Test
  void unspaced_text_is_preserved_and_still_obeys_embedding_token_bounds() {
    String text = "文".repeat(4000);
    List<DerivedParagraph> paragraphs = derive(text);
    assertEquals(1, paragraphs.size());
    assertEquals(text, paragraphs.getFirst().text());
    for (var chunk : paragraphs.getFirst().chunks()) {
      assertTrue(ShippedChunking.SHIPPED.tokens(chunk.text()) <= ShippedChunking.MAX);
    }
  }

  private static String longText(String prefix) {
    return prefix + " " + ParagraphPackingTest.words(80);
  }

  private static List<String> inferred(String text) {
    return Derivation.derive(text, List.of(), true, ShippedChunking.SHIPPED).paragraphs().stream()
        .map(DerivedParagraph::text)
        .toList();
  }
}
