package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Anchor's {@code SectionDetector}, ported.
 *
 * <p>Four ORed boundary conditions with a hard veto: an explicit chemistry
 * section name, a numbered heading, a numbered subsection, or a short title-cased
 * line followed by prose — with the LP/proof keyword list vetoing all four.
 *
 * <p><b>Every rule below that looks arbitrary is a recorded failure.</b> Anchor's
 * comments name the paper for three of them, and those three are the ones this
 * class is mostly about: the two-character floor, the separate subsection
 * pattern, and the veto list.
 */
class SectionDetectorTest {

    private static List<SectionDetector.Detected> detect(String body) {
        String[] lines = body.split("\\R", -1);
        return SectionDetector.detect(lines, 0, lines.length);
    }

    private static List<String> titles(List<SectionDetector.Detected> found) {
        return found.stream()
                .map(section -> section.title().render(StructuralRef.WhenSynthetic.PLACEHOLDER))
                .toList();
    }

    // --- the four ways a line becomes a boundary ----------------------------------

    @Test
    void a_chemistry_section_name_is_a_boundary_on_its_own() {
        assertEquals(List.of("Introduction", "Results and Discussion"), titles(detect("""
                Introduction
                We begin.

                Results and Discussion
                We found things.
                """)));
    }

    @Test
    void a_numbered_heading_is_a_boundary() {
        assertEquals(List.of("1. Antichains", "2. Diameters"), titles(detect("""
                1. Antichains
                Words about them.

                2. Diameters
                Words about those.
                """)));
    }

    /**
     * <b>A separate pattern, and Anchor records what happened without it:</b>
     *
     * <blockquote>the parser previously missed these (NUMBERED_HEADING only
     * matches single-level {@code \\d+\\.}) and the synthesiser ended up lifting
     * subsection titles directly out of chunk text into the GROUNDING block — a
     * "helpful hallucination".</blockquote>
     */
    @Test
    void a_numbered_subsection_is_a_boundary_too() {
        assertEquals(List.of("3.1 Antichains", "3.10. Diameters"), titles(detect("""
                3.1 Antichains
                Words about them.

                3.10. Diameters
                Words about those.
                """)));
    }

    @Test
    void a_short_title_cased_line_followed_by_prose_is_a_boundary() {
        assertEquals(List.of("Antichains Of Fixed Diameter"), titles(detect("""
                Antichains Of Fixed Diameter
                We consider the family and what it does.
                """)));
    }

    /** The prose requirement is the second half of that rule: a title-cased
     *  line followed by another heading-shaped line is a list, not a section. */
    @Test
    void a_title_cased_line_with_no_prose_under_it_is_not_a_boundary() {
        List<SectionDetector.Detected> found = detect("""
                Antichains Of Fixed Diameter
                ANOTHER SHOUTED LINE
                """);

        assertEquals(1, found.size());
        assertTrue(found.get(0).title().isSynthetic());
    }

    // --- the three rules that look arbitrary and are not --------------------------

    /**
     * <blockquote>the loose form matched LaTeX-flattened math residue like "X Y"
     * (subscripts stripped by Tika), promoting it to a section title that then
     * leaked into the LLM prompt as {@code [X Y]}.</blockquote>
     *
     * <p>So: each word at least two characters, and the whole heading at least
     * six.
     */
    @Test
    void math_residue_left_by_a_flattened_formula_is_not_a_heading() {
        List<SectionDetector.Detected> found = detect("""
                X Y
                and so the bound follows for every n.
                """);

        assertEquals(1, found.size());
        assertTrue(found.get(0).title().isSynthetic());
    }

    @Test
    void a_heading_shorter_than_six_characters_is_not_a_heading() {
        assertTrue(detect("Ab Cd\nand so the bound follows.\n").get(0).title().isSynthetic());
    }

    @Test
    void a_heading_longer_than_sixty_characters_is_not_a_heading() {
        // Four words, so the word cap is not what refuses it: 63 characters is.
        String tooLong = "Antichainsofrank Diametersofrank Latticesofranks Booleanofranks";
        assertTrue(detect(tooLong + "\nand so the bound follows.\n").get(0).title().isSynthetic());
    }

    /**
     * <blockquote>{@code Minimize} matches TITLE_CASE_HEADING and is ≥6 chars;
     * without this guard it ends up as a section title in any LP paper.</blockquote>
     *
     * <p>The veto is absolute: it beats the chemistry list and the title-case
     * rule alike, which is why it is checked before either.
     */
    @Test
    void an_lp_or_proof_keyword_never_becomes_a_section_however_it_is_shaped() {
        for (String keyword : List.of("Minimize", "Theorem", "Proof", "Subject to", "Notation")) {
            List<SectionDetector.Detected> found = detect(
                    keyword + ":\nsubject to the constraints below.\n");
            assertTrue(found.get(0).title().isSynthetic(),
                    keyword + " was promoted to a section title");
        }
    }

    /** The veto is over the same normalisation the matcher computes, so
     *  punctuation and case do not smuggle a keyword past it. */
    @Test
    void the_veto_is_matched_against_the_normalised_line() {
        assertTrue(detect("PROOF.\nwe argue by induction on n.\n").get(0).title().isSynthetic());
    }

    // --- what a subsection title is trimmed to ------------------------------------

    /**
     * Anchor's {@code trimSubsectionTitle}, and the shape it exists for is one
     * Tika and PDFBox both produce: the heading and the first sentence of body
     * prose on one physical line.
     */
    @Test
    void a_subsection_title_stops_at_the_period_that_ends_it() {
        assertEquals(
                List.of("3.1. Antichains of fixed diameter."),
                titles(detect("""
                        3.1. Antichains of fixed diameter. Define the diameter of a family F as
                        the largest distance between two of its members.
                        """)));
    }

    @Test
    void a_subsection_title_that_is_the_whole_line_is_kept_whole() {
        assertEquals(List.of("3.1 Antichains of fixed diameter"), titles(detect("""
                3.1 Antichains of fixed diameter
                Define the diameter of a family F.
                """)));
    }

    // --- the exclusions -----------------------------------------------------------

    /** References are dropped wholesale: they are not claim-bearing, and a
     *  summariser handed them writes a summary the deliberation then treats as
     *  an argument the document makes. */
    @Test
    void a_references_section_is_dropped() {
        assertEquals(List.of("Introduction"), titles(detect("""
                Introduction
                We begin.

                References
                [1] Wagner, A.
                """)));
    }

    /** And it was a boundary before it was dropped, so the section before it
     *  ends where it starts rather than absorbing the bibliography. */
    @Test
    void an_excluded_section_still_ends_the_section_before_it() {
        List<SectionDetector.Detected> found = detect("""
                Introduction
                We begin.

                References
                [1] Wagner, A.
                """);

        assertEquals(1, found.size());
        assertEquals(3, found.get(0).endLine());
    }

    @Test
    void a_dropped_section_does_not_consume_an_ordinal() {
        List<SectionDetector.Detected> found = detect("""
                Introduction
                We begin.

                Acknowledgements
                We thank the reviewers.

                Conclusion
                We conclude.
                """);

        assertEquals(List.of(1, 2), found.stream()
                .map(SectionDetector.Detected::ordinal).toList());
        assertEquals(List.of("Introduction", "Conclusion"), titles(found));
    }

    // --- the synthetic heap -------------------------------------------------------

    @Test
    void a_chapter_body_with_no_heading_in_it_is_one_synthetic_section() {
        List<SectionDetector.Detected> found = detect("""
                and so the bound follows for every n.

                which completes the argument.
                """);

        assertEquals(1, found.size());
        assertTrue(found.get(0).title().isSynthetic());
        assertEquals(1, found.get(0).ordinal());
        assertEquals(0, found.get(0).bodyStartLine());
    }

    /**
     * <b>A divergence, and the same shape as the zero-chapter fix.</b> Anchor
     * builds a section for every boundary and none for the text BEFORE the first
     * one, so a paper's title block, authors and unlabelled abstract — everything
     * above "1. Introduction" — belongs to no section and is dropped from the
     * corpus entirely.
     *
     * <p>Silently, and it is not the intended kind of drop: an excluded section
     * is dropped on purpose and paid back by {@code ReferencesExtractor}, while
     * this text is simply never reached. On this server the loss would also be a
     * regression rather than an omission — every one of those paragraphs is in
     * the corpus today, and a re-ingest that stopped deriving them would delete
     * their rows and stale every citation into them.
     *
     * <p>So the preamble is a synthetic section, which is the mechanism this
     * class already has for "text that belongs to no heading".
     */
    @Test
    void text_above_the_first_heading_is_kept_as_a_synthetic_section() {
        List<SectionDetector.Detected> found = detect("""
                A Paper About Antichains, by A. Wagner. We show that the conjecture fails.

                1. Introduction
                We begin.
                """);

        assertEquals(2, found.size());
        assertTrue(found.get(0).title().isSynthetic());
        assertEquals(0, found.get(0).bodyStartLine());
        assertEquals(2, found.get(0).endLine());
        assertEquals("1. Introduction", titles(found).get(1));
        assertEquals(List.of(1, 2), found.stream()
                .map(SectionDetector.Detected::ordinal).toList());
    }

    /** Blank lines above the first heading are not a preamble, so the common
     *  case gains no empty section. */
    @Test
    void nothing_but_blank_lines_above_the_first_heading_is_not_a_preamble() {
        List<SectionDetector.Detected> found = detect("""

                1. Introduction
                We begin.
                """);

        assertEquals(1, found.size());
        assertEquals("1. Introduction", titles(found).get(0));
    }

    @Test
    void an_empty_range_detects_nothing() {
        assertEquals(List.of(), SectionDetector.detect(new String[] {"x"}, 1, 1));
        assertEquals(List.of(), SectionDetector.detect(new String[] {"x"}, 2, 1));
    }

    // --- the ranges hold together --------------------------------------------------

    @Test
    void each_section_body_starts_after_its_heading_and_ends_where_the_next_begins() {
        List<SectionDetector.Detected> found = detect("""
                Introduction
                We begin.

                Conclusion
                We conclude.
                """);

        assertEquals(1, found.get(0).bodyStartLine());
        assertEquals(3, found.get(0).endLine());
        assertEquals(4, found.get(1).bodyStartLine());
        assertEquals(6, found.get(1).endLine());
    }
}
