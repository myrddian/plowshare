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
  void a_blank_line_is_a_paragraph_break() {
    List<DerivedParagraph> paragraphs = derive("First one.\n\nSecond one.\n\n\nThird one.");

    assertEquals(
        List.of("First one.", "Second one.", "Third one."),
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
    List<DerivedParagraph> paragraphs = derive("A.\n\nB.\n\nC.");

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
    List<DerivedParagraph> paragraphs = derive("Notes\n\nSomething.\n\nNotes\n\nNotes");

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
    List<DerivedParagraph> paragraphs = derive("Notes\n\nOther\n\nNotes\n\nOther");

    assertEquals(
        List.of(1, 1, 2, 2), paragraphs.stream().map(DerivedParagraph::occurrence).toList());
  }

  // --- chunking ------------------------------------------------------------

  @Test
  void every_paragraph_carries_its_chunks_in_order() {
    List<DerivedParagraph> paragraphs = derive("Short one.\n\n" + "A sentence. ".repeat(400));

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

  // --- blocks that are not paragraphs ----------------------------------------

  /**
   * <b>A display equation belongs to the sentence that introduces it.</b>
   *
   * <p>{@code split} cuts wherever the extraction left a blank line, and in a PDF that is a
   * geometric event rather than a paragraph boundary — an equation, a matrix row, a page number and
   * a line of table data each arrive as their own block. Hand counted, arXiv:2212.12473 has 36–42
   * paragraphs and this produced 134, of which 69% were not paragraphs at all.
   */
  @Test
  void a_block_with_no_words_joins_the_paragraph_above_it() {
    String text =
        """
                The bound is tight for every antichain of this particular shape.

                (1-X)A(X)k = (1-X)

                And the argument continues from there to its conclusion.
                """;

    List<String> derived = inferred(text);

    assertEquals(2, derived.size(), text);
    assertTrue(derived.get(0).endsWith("(1-X)A(X)k = (1-X)"), derived.get(0));
  }

  /**
   * <b>Backwards, and the direction is nearly the whole of the fix.</b>
   *
   * <p>Measured on that paper against the 41 boundaries between its real units, changing nothing
   * but which neighbour the block attaches to: joining backwards scores seam F1 <b>0.81</b> and
   * forwards <b>0.60</b>, at the same unit count. The counts are identical and the segmentations
   * are not, which is why a count is the wrong thing to tune against.
   */
  @Test
  void a_block_that_is_not_a_paragraph_joins_backwards_and_not_forwards() {
    String text =
        """
                The bound is tight for every antichain of this particular shape.

                (1-X)A(X)k = (1-X)

                And the argument continues from there to its conclusion.
                """;

    List<String> derived = inferred(text);

    assertTrue(
        derived.get(0).contains("(1-X)A(X)k"),
        "the equation belongs to the sentence that introduced it: " + derived.get(0));
    assertEquals("And the argument continues from there to its conclusion.", derived.get(1));
  }

  /**
   * <b>And it never happens to a document whose author typed the blank lines.</b>
   *
   * <p>The join repairs an inference. In a text file a blank line is a mark somebody typed, and
   * joining two of those blocks deletes a paragraph they wrote — measured on Frankenstein, where it
   * would take {@code "Yes."}, a line of dialogue, into the paragraph above it. See {@link
   * Extracted#paragraphsWereInferred()}.
   */
  @Test
  void the_same_text_is_left_alone_when_the_author_typed_the_blank_lines() {
    String text =
        """
                The bound is tight for every antichain of this particular shape.

                (1-X)A(X)k = (1-X)

                And the argument continues from there to its conclusion.
                """;

    assertEquals(3, authored(text).size(), text);
  }

  /**
   * <b>The word count cannot read every script and must not act as though it can.</b>
   *
   * <p>Chinese, Japanese and Thai are written without spaces, so a whole paragraph arrives as one
   * token — one "word" — and the rule above would fold a document into its first paragraph with
   * nothing here noticing, because nothing here checks a language. Above the length guard the rule
   * keeps its hands off whatever it can or cannot read.
   */
  @Test
  void a_long_block_is_left_alone_however_few_words_this_can_find_in_it() {
    String unspaced = "\u6587".repeat(200);
    String text = "The bound is tight for every antichain of this shape.\n\n" + unspaced + "\n";

    List<String> derived = inferred(text);

    assertEquals(2, derived.size(), "a script with no spaces is not a fragment");
    assertEquals(unspaced, derived.get(1));
  }

  /** Derived as a PDF is: the blank lines are PDFBox's inference. */
  private static List<String> inferred(String text) {
    return Derivation.derive(text, List.of(), true, ShippedChunking.SHIPPED).paragraphs().stream()
        .map(DerivedParagraph::text)
        .toList();
  }

  /** Derived as a text file is: the blank lines are the author's. */
  private static List<String> authored(String text) {
    return Derivation.derive(text, List.of(), false, ShippedChunking.SHIPPED).paragraphs().stream()
        .map(DerivedParagraph::text)
        .toList();
  }
}
