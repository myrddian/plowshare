package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Anchor's {@code ChapterDetector}, ported.
 *
 * <p>Precedence, and it is a precedence rather than a union: an explicit {@code ^Chapter N} regex,
 * then the PDF outline's top-level entries, then {@code ^Part I/II} markers, then one synthetic
 * chapter over the whole document. The first branch that finds anything wins outright.
 *
 * <p><b>The second branch has never had an input on this server until now.</b> {@code
 * Extracted.outlineTopLevel} arrived with the PDF path; before it, a faithful port of this class
 * would have carried a dead branch.
 */
class ChapterDetectorTest {

  /**
   * A paper's shape: a title line, an author block and an unlabelled abstract above the first
   * numbered heading — the three things §3 of {@code implementation rationale} watched an outline
   * delete.
   *
   * <p>None of those three lines is a section boundary either: the title's first word is one
   * letter, which {@code SectionDetector}'s title-case rule refuses, and the author line ends in a
   * period.
   */
  private static final String PAPER =
      """
            A Paper About Approximation, Measured

            Ferrer and Osei, Institute of Things.

            We show that a thing holds under conditions, and we measure it.

            1. Introduction

            Alpha claims a thing.

            Beta denies it.

            2. Method

            Gamma describes a method.

            3. Results

            Delta reports a finding.
            """;

  private static List<ChapterDetector.Detected> detect(String text) {
    return ChapterDetector.detect(text, List.of());
  }

  // --- precedence ---------------------------------------------------------------

  @Test
  void chapter_headings_are_the_first_thing_looked_for() {
    List<ChapterDetector.Detected> found =
        detect(
            """
                Chapter 1 Beginnings
                It starts here.

                Chapter 2 Endings
                It stops here.
                """);

    assertEquals(List.of("Chapter 1 Beginnings", "Chapter 2 Endings"), titles(found));
    assertEquals(List.of(1, 2), found.stream().map(ChapterDetector.Detected::ordinal).toList());
  }

  @Test
  void roman_numerals_and_any_case_are_chapter_headings_too() {
    assertEquals(
        List.of("CHAPTER IV Reprise"),
        titles(
            detect(
                """
                CHAPTER IV Reprise
                Words.
                """)));
  }

  /**
   * The branch this port could not have tested before stage 1. The outline is the author's own
   * table of contents, read out of the PDF rather than guessed at from the text.
   */
  @Test
  void the_documents_own_outline_names_the_chapters_when_no_chapter_heading_does() {
    String text =
        """
                Antichains
                Some opening words.

                Diameters
                More words.
                """;

    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(text, List.of("Antichains", "Diameters"));

    assertEquals(List.of("Antichains", "Diameters"), titles(found));
  }

  /**
   * An outline entry that names nothing in the extracted text finds no boundary, so the branch
   * falls through rather than inventing one.
   */
  @Test
  void an_outline_that_matches_no_line_does_not_make_a_chapter() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                Some opening words that go on for a while.
                """,
            List.of("A Heading The Extraction Lost"));

    assertEquals(1, found.size());
    assertTrue(found.get(0).title().isSynthetic());
  }

  @Test
  void part_markers_are_the_last_thing_looked_for_before_giving_up() {
    assertEquals(
        List.of("Part I The Setup", "Part II The Payoff"),
        titles(
            detect(
                """
                Part I The Setup
                Words.

                Part II The Payoff
                More words.
                """)));
  }

  @Test
  void a_document_that_declares_no_structure_is_one_synthetic_chapter_over_all_of_it() {
    List<ChapterDetector.Detected> found =
        detect(
            """
                A paper with no headings at all.

                Just prose, twice.
                """);

    assertEquals(1, found.size());
    assertTrue(found.get(0).title().isSynthetic());
    assertEquals(1, found.get(0).ordinal());
  }

  /**
   * <b>The synthetic chapter has no heading line, so its body starts at line zero.</b> Anchor's
   * section detector always skips {@code startLine + 1}, which on this branch skips the document's
   * first line — every document with no detected chapter loses its opening line. Named here because
   * the fix is a divergence.
   */
  @Test
  void the_synthetic_chapters_body_is_the_whole_document_including_its_first_line() {
    assertEquals(0, detect("First line.\n\nSecond line.\n").get(0).bodyStartLine());
  }

  /**
   * A real chapter's body starts after its heading, which is Anchor's rule and the reason the
   * heading never lands inside a paragraph.
   */
  @Test
  void a_real_chapters_body_starts_after_its_heading_line() {
    assertEquals(1, detect("Chapter 1 Beginnings\nWords.\n").get(0).bodyStartLine());
  }

  // --- the exclusions, which are two-step and whose order matters ----------------

  /**
   * The recorded reason, from Anchor:
   *
   * <blockquote>
   *
   * When these get promoted to chapters, the per-chapter summarizer dutifully writes a "References"
   * or "Appendix" summary, which the deliberation prompt then surfaces as authoritative content —
   * and the model writes things like "the References chapter concludes with a counterexample,"
   * fragment-stitching that reads as fabrication.
   *
   * </blockquote>
   *
   * <p>Through the outline branch, which is where the exclusion actually bites — see {@link
   * #a_heading_that_says_chapter_keeps_the_word_and_is_not_excluded}.
   */
  @Test
  void back_matter_that_looks_chapter_shaped_is_dropped() {
    assertEquals(
        List.of("Beginnings"),
        titles(
            ChapterDetector.detect(
                """
                Beginnings
                Words.

                Acknowledgements
                We thank the reviewers.
                """,
                List.of("Beginnings", "Acknowledgements"))));
  }

  /**
   * <b>The two steps, and the order is the whole of it.</b> An excluded heading is still a
   * boundary: it terminates the chapter before it, and is only then dropped. The first chapter must
   * therefore stop at the excluded heading rather than run to the end of the document and swallow
   * it.
   */
  @Test
  void an_excluded_heading_still_ends_the_chapter_before_it() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                Beginnings
                Words.

                References
                [1] Wagner, A.
                """,
            List.of("Beginnings", "References"));

    assertEquals(1, found.size());
    assertEquals(3, found.get(0).endLine());
  }

  /**
   * And it consumes no ordinal, so the surviving chapters are 1, 2, 3 with no gap for a reader to
   * read as a chapter that failed to store.
   */
  @Test
  void a_dropped_heading_does_not_consume_an_ordinal() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                Beginnings
                Words.

                Bibliography
                [1] Wagner, A.

                Endings
                Words.
                """,
            List.of("Beginnings", "Bibliography", "Endings"));

    assertEquals(List.of(1, 2), found.stream().map(ChapterDetector.Detected::ordinal).toList());
    assertEquals(List.of("Beginnings", "Endings"), titles(found));
  }

  /**
   * Anchor's own comment: the filter runs "after stripping leading numeric prefixes (e.g. "5.
   * References" → "references") so numbered bibliographies are caught alongside unnumbered ones".
   */
  @Test
  void numbering_is_stripped_before_the_exclusion_is_matched() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                5. References
                [1] Wagner, A.
                """,
            List.of("5. References"));

    assertEquals(1, found.size());
    assertTrue(found.get(0).title().isSynthetic());
  }

  /**
   * <b>A finding about Anchor, pinned rather than corrected.</b> The normalisation strips leading
   * numbering and punctuation but not the word {@code Chapter}, so {@code "Chapter 7 References"}
   * normalises to {@code "chapter references"} and matches nothing in the set. The exclusion list
   * therefore never fires on the first precedence branch at all — only on the outline and part
   * branches, where a heading line is the bare title.
   *
   * <p>Left as Anchor has it. The list is a regression log and each entry records a real paper;
   * widening the normalisation to strip a leading {@code Chapter N} would be new behaviour fitted
   * to no observed failure, which is the opposite of what makes the list worth carrying.
   */
  @Test
  void a_heading_that_says_chapter_keeps_the_word_and_is_not_excluded() {
    assertEquals(
        List.of("Chapter 1 Beginnings", "Chapter 2 References"),
        titles(
            detect(
                """
                Chapter 1 Beginnings
                Words.

                Chapter 2 References
                [1] Wagner, A.
                """)));
  }

  /**
   * <b>DIVERGENCE #10, fixed rather than ported, on the owner's instruction.</b> In Anchor, when
   * every detected heading is excluded {@code materialise} returns an empty list and the synthetic
   * fallback does not fire, because the fallback is guarded on no boundary having been
   * <em>detected</em> rather than on none having <em>survived</em>. The document then derives zero
   * chapters, zero sections and zero paragraphs — it is silently ingested as nothing.
   */
  @Test
  void a_document_whose_every_heading_was_excluded_is_still_one_synthetic_chapter() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                Table of Contents
                1. Beginnings ... 4

                References
                [1] Wagner, A.
                """,
            List.of("Table of Contents", "References"));

    assertEquals(1, found.size());
    assertTrue(found.get(0).title().isSynthetic());
    assertEquals(0, found.get(0).bodyStartLine());
    assertEquals(6, found.get(0).endLine());
  }

  // --- the text above the first chapter, which reached no row -------------------

  /**
   * <b>The hole §3.3(b) fixed at the section level, one level up.</b>
   *
   * <p>Measured by {@code implementation rationale} §3 on a generated paper: with no outline the
   * same bytes derive one synthetic chapter and every paragraph; handed the paper's own outline the
   * outline branch fires, {@code materialise} builds chapters from {@code boundaries[0]} onward,
   * and <b>the title block, the authors and the abstract are in no chapter — so in no section, so
   * in no paragraph and no row</b>: no summary, no search, no ask.
   *
   * <p>The count is the defect. On the evaluation's four-page paper it was 17 paragraphs against
   * 20; this is the same shape at a size a test can read. Asserted through {@link Derivation}
   * rather than over the detector's ranges because the loss is only visible where the ranges become
   * rows.
   */
  @Test
  void an_outline_does_not_cost_the_document_the_text_above_its_first_chapter() {
    List<String> outline = List.of("1. Introduction", "2. Method", "3. Results");

    DerivedDocument withoutOutline = Derivation.derive(PAPER, List.of(), ShippedChunking.SHIPPED);
    DerivedDocument withOutline = Derivation.derive(PAPER, outline, ShippedChunking.SHIPPED);

    assertEquals(
        withoutOutline.paragraphs().size(),
        withOutline.paragraphs().size(),
        "the same bytes lost paragraphs to the document's own table of contents");
    assertEquals(7, withOutline.paragraphs().size());
    assertEquals(
        1,
        withoutOutline.chapters().size(),
        "no outline: the fallback is one synthetic chapter over the whole document");
    assertEquals(
        4,
        withOutline.chapters().size(),
        "an outline: a chapter for the preamble and one per outline entry");
  }

  /**
   * And it is a chapter the parser invented, at ordinal 1, with the chapters the document did name
   * renumbered after it — so the chapters partition the document exactly as the sections partition
   * a chapter.
   */
  @Test
  void the_text_above_the_first_chapter_is_a_synthetic_chapter_at_ordinal_one() {
    List<ChapterDetector.Detected> found =
        detect(
            """
                A Paper About Approximation, Measured

                Ferrer and Osei, Institute of Things.

                Chapter 1 Beginnings
                It starts here.
                """);

    assertEquals(2, found.size());
    assertTrue(found.get(0).title().isSynthetic());
    assertEquals(1, found.get(0).ordinal());
    assertEquals(
        0,
        found.get(0).bodyStartLine(),
        "an invented chapter has no heading line to skip, so its body is line 0");
    assertEquals(4, found.get(0).endLine());
    assertEquals(List.of("Chapter 1 Beginnings"), titles(found));
    assertEquals(2, found.get(1).ordinal());
  }

  /**
   * Blank lines above the first heading are not a preamble. A document that opens on its first
   * chapter is given no invented one, and its chapters are numbered from 1 as the document numbers
   * them.
   */
  @Test
  void a_document_that_opens_on_its_first_chapter_is_given_no_preamble() {
    List<ChapterDetector.Detected> found =
        detect(
            """

                Chapter 1 Beginnings
                It starts here.
                """);

    assertEquals(1, found.size());
    assertEquals(List.of("Chapter 1 Beginnings"), titles(found));
    assertEquals(1, found.get(0).ordinal());
  }

  /**
   * <b>The preamble chapter does not re-open DIVERGENCE #10.</b> When every detected heading is
   * excluded there is no surviving chapter for a preamble to sit in front of, and the document must
   * still become the one synthetic chapter over the whole of it — the text above the excluded
   * heading and the excluded block both — which is what stops it being ingested as nothing.
   */
  @Test
  void a_preamble_above_an_excluded_heading_does_not_replace_the_whole_document() {
    List<ChapterDetector.Detected> found =
        ChapterDetector.detect(
            """
                A Paper About Approximation, Measured

                References
                [1] Wagner, A.
                """,
            List.of("References"));

    assertEquals(1, found.size());
    assertTrue(found.get(0).title().isSynthetic());
    assertEquals(0, found.get(0).bodyStartLine());
    assertEquals(5, found.get(0).endLine());
  }

  @Test
  void nothing_at_all_detects_nothing_at_all() {
    assertEquals(List.of(), detect(""));
    assertEquals(List.of(), detect("   \n \n"));
  }

  // --- vocabulary ---------------------------------------------------------------

  @Test
  void a_paper_that_numbers_its_sections_calls_them_sections() {
    assertEquals(
        Vocabulary.SECTION,
        ChapterDetector.detectVocabulary(
            """
                1. Introduction
                Words.

                2. Method
                Words.
                """));
  }

  @Test
  void a_book_that_says_chapter_calls_them_chapters() {
    assertEquals(
        Vocabulary.CHAPTER,
        ChapterDetector.detectVocabulary(
            """
                Chapter 1 Beginnings
                Words.

                Chapter 2 Endings
                Words.
                """));
  }

  @Test
  void a_document_in_parts_calls_them_parts() {
    assertEquals(
        Vocabulary.PART,
        ChapterDetector.detectVocabulary(
            """
                Part I The Setup
                Words.

                Part II The Payoff
                Words.
                """));
  }

  /**
   * One heading is not a format. Anchor requires two, and the default is "section" because untagged
   * academic papers dominate the corpus.
   */
  @Test
  void one_heading_is_not_enough_to_call_it_a_format() {
    assertEquals(
        Vocabulary.SECTION, ChapterDetector.detectVocabulary("Chapter 1 Beginnings\nWords.\n"));
    assertEquals(Vocabulary.SECTION, ChapterDetector.detectVocabulary(""));
  }

  /**
   * What the vocabulary is actually for: the level BELOW the top one, so a prompt can say
   * "subsection 2.3" to a paper and "section" to a book.
   */
  @Test
  void the_level_below_depends_on_what_the_top_level_is_called() {
    assertEquals("section", Vocabulary.CHAPTER.midLevel());
    assertEquals("subsection", Vocabulary.SECTION.midLevel());
    assertEquals("section", Vocabulary.PART.midLevel());
    assertEquals("Chapters", Vocabulary.CHAPTER.pluralCap());
    assertEquals("Subsections", Vocabulary.SECTION.midLevelPluralCap());
  }

  private static List<String> titles(List<ChapterDetector.Detected> found) {
    return found.stream()
        .map(chapter -> chapter.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER))
        .filter(title -> !StructuralRef.UNNAMED.equals(title))
        .toList();
  }

  /**
   * Nothing above depends on it, and it is the property the whole class is in service of: no
   * detected chapter carries the sentinel out.
   */
  @Test
  void no_detected_chapter_renders_a_sentinel() {
    for (ChapterDetector.Detected chapter : detect("Chapter 1 A\nx\n\nWords.\n")) {
      assertFalse(
          String.valueOf(chapter.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER))
              .contains("SYNTHETIC"));
    }
  }

  // --- a numbering, whatever the document calls it ---------------------------

  /**
   * <b>The branch that exists because the others are a list of words we have happened to meet.</b>
   *
   * <p>{@code ^Chapter N} and {@code ^Part I} are two English conventions and the outline branch
   * needs a PDF. Frankenstein opens in letters; the Odyssey is divided into books, which were
   * scrolls. Neither is reachable by adding a word to a regex, because the next document uses a
   * different one.
   */
  @Test
  void a_numbering_is_a_division_whatever_word_the_document_puts_in_front_of_it() {
    String book =
        """
                Letter 1

                He writes from the north with news of the voyage he intends.

                Letter 2

                The ship is icebound now and the crew have grown uneasy.

                Letter 3

                A stranger was taken aboard from the ice this morning.
                """;

    assertEquals(List.of("Letter 1", "Letter 2", "Letter 3"), titles(book));
  }

  /**
   * And the numbering may be roman, which is how the Odyssey numbers its twenty-four books and how
   * nothing else in this class can read.
   */
  @Test
  void a_numbering_written_in_roman_is_still_a_numbering() {
    String poem =
        """
                BOOK I

                The gods in council decide the wanderer shall return home.

                BOOK II

                He calls the men of the island together to hear him.

                BOOK III

                They come at last to the sandy shore of the old king.
                """;

    assertEquals(List.of("BOOK I", "BOOK II", "BOOK III"), titles(poem));
  }

  /**
   * <b>A document listing its own divisions is not a document with twice as many.</b>
   *
   * <p>Measured on Frankenstein, which detected <b>49</b> chapters for a book with 28: Project
   * Gutenberg's edition opens with a table of contents, and every rule in this class matches a
   * listing of headings exactly as well as the headings — they are the same lines. Half the
   * resulting chapters held no text, and the critic's macro view is built from chapter summaries.
   */
  @Test
  void a_table_of_contents_is_not_a_second_set_of_chapters() {
    String book =
        """
                Contents

                Letter 1
                Letter 2
                Letter 3

                Letter 1

                He writes from the north with news of the voyage he intends.

                Letter 2

                The ship is icebound now and the crew have grown uneasy.

                Letter 3

                A stranger was taken aboard from the ice this morning.
                """;

    // The contents block itself is text above the first division, so it
    // lands in the preamble — which is where an unattributed block belongs,
    // and is what `preamble` is for one level up.
    assertEquals(List.of(StructuralRef.UNNAMED, "Letter 1", "Letter 2", "Letter 3"), titles(book));
  }

  /**
   * And the comparison ignores punctuation, which is not fussiness.
   *
   * <p>Project Gutenberg's Odyssey punctuates its contents as {@code BOOK XXIV.} and its heading as
   * {@code BOOK XXIV}. An exact match read those as two divisions and kept the contents' last
   * entry, which then carried the whole of the translator's preface under the name of the poem's
   * final book.
   */
  @Test
  void a_contents_entry_that_ends_in_a_full_stop_still_names_the_same_division() {
    String poem =
        """
                BOOK I.
                BOOK II.
                BOOK III.

                BOOK I

                The gods in council decide the wanderer shall return home.

                BOOK II

                He calls the men of the island together to hear him.

                BOOK III

                They come at last to the sandy shore of the old king.
                """;

    assertEquals(List.of(StructuralRef.UNNAMED, "BOOK I", "BOOK II", "BOOK III"), titles(poem));
  }

  /**
   * A heading with nothing under it cannot be summarised, retrieved or cited; all it can do is put
   * an empty bullet in the critic's macro view.
   */
  @Test
  void a_division_with_no_text_under_it_is_not_a_division() {
    String document =
        """
                Chapter 1

                The first chapter says something a summariser could read.

                Chapter 2

                Chapter 3

                The third chapter says something a summariser could read.
                """;

    assertEquals(List.of("Chapter 1", "Chapter 3"), titles(document));
  }

  /**
   * <b>A running head is not a numbering, and telling them apart is the whole difficulty.</b>
   *
   * <p>Both recur through a document as a fixed phrase beside a changing number. Two things
   * separate them and both are checked here: a running head is the paper's whole title, so it
   * carries more words than a marker does, and it starts at whatever page it first appeared on
   * rather than at one.
   */
  @Test
  void a_running_head_is_not_a_numbering() {
    String paper =
        """
                THE BUNKBED CONJECTURE IS FALSE 3

                The first stretch of argument runs on for several lines here.

                THE BUNKBED CONJECTURE IS FALSE 5

                The second stretch of argument runs on for several lines here.

                THE BUNKBED CONJECTURE IS FALSE 7

                The third stretch of argument runs on for several lines here.
                """;

    assertEquals(
        List.of(StructuralRef.UNNAMED),
        titles(paper),
        "a running head detected as a division would make every page a chapter");
  }

  /** The names of the chapters this text derives, synthetic ones included. */
  private static List<String> titles(String text) {
    return ChapterDetector.detect(text, List.of()).stream()
        .map(chapter -> chapter.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER))
        .toList();
  }
}
