package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Anchor's {@code ReferencesExtractor}, ported — <b>the half that pays the
 * exclusions back</b>.
 *
 * <p>{@link SectionDetector} drops a references section wholesale because it is
 * not claim-bearing. That is right for the summariser and it makes the paper's
 * own bibliography invisible to everything downstream, which is a problem
 * because the deliberation needs it: {@code {document_citations}} is what lets a
 * model tell "an author of this document" from "a third party this document
 * cites". So the block is re-walked out of the raw text after the hierarchy has
 * thrown it away.
 */
class ReferencesExtractorTest {

    // --- finding the block --------------------------------------------------------

    @Test
    void the_block_is_everything_under_the_references_heading() {
        assertEquals("""
                [1] Wagner, A. Constructions in combinatorics. 2021.
                [2] Erdos, P. On a problem. 1965.""",
                ReferencesExtractor.findReferencesText("""
                        1. Introduction
                        We begin.

                        References
                        [1] Wagner, A. Constructions in combinatorics. 2021.
                        [2] Erdos, P. On a problem. 1965.
                        """));
    }

    @Test
    void a_numbered_or_shouted_or_bibliography_heading_is_the_same_heading() {
        for (String heading : List.of("References", "REFERENCES", "5. References",
                "Bibliography", "  bibliography  ")) {
            assertTrue(ReferencesExtractor.findReferencesText(
                            heading + "\n[1] Wagner, A. 2021.\n").contains("Wagner"),
                    heading + " was not recognised as a references heading");
        }
    }

    @Test
    void a_document_with_no_references_has_no_block() {
        assertEquals("", ReferencesExtractor.findReferencesText("1. Introduction\nWe begin.\n"));
        assertEquals("", ReferencesExtractor.findReferencesText(""));
    }

    /** The block ends where the next major heading begins, so an appendix
     *  after the bibliography is not scraped into it. */
    @Test
    void the_block_ends_at_the_next_major_heading() {
        String found = ReferencesExtractor.findReferencesText("""
                References
                [1] Wagner, A. 2021.
                [2] Erdos, P. 1965.
                [3] Bollobas, B. 1986.

                6. Appendix
                Proofs of the lemmas.
                """);

        assertTrue(found.contains("Bollobas"));
        assertTrue(!found.contains("Appendix"), "the appendix was scraped into the bibliography");
    }

    /**
     * And it does not end on a numbered entry, which is the thing the narrow
     * boundary regex would otherwise mistake for a numbered heading and truncate
     * the list at.
     */
    @Test
    void a_numbered_entry_does_not_look_like_the_next_heading() {
        String found = ReferencesExtractor.findReferencesText("""
                References
                1. Wagner, A. Constructions in combinatorics, volume 2. Springer, 2021.
                2. Erdos, P. On a problem of graph theory. Publ. Math. Debrecen, 1965.
                3. Bollobas, B. Combinatorics: set systems and hypergraphs. CUP, 1986.
                4. Frankl, P. The shifting technique in extremal set theory. CUP, 1987.
                """);

        assertTrue(found.contains("Frankl"), "the list was truncated at an entry");
    }

    // --- the block into entries ---------------------------------------------------

    @Test
    void bracketed_entries_are_numbered_by_what_the_paper_prints() {
        List<ReferencesExtractor.Reference> found = ReferencesExtractor.extract("""
                References
                [1] Wagner, A. Constructions in combinatorics. 2021.
                [2] Erdos, P. On a problem. 1965.
                """);

        assertEquals(List.of(1, 2),
                found.stream().map(ReferencesExtractor.Reference::refNum).toList());
        assertEquals("Wagner, A. Constructions in combinatorics. 2021.", found.get(0).raw());
    }

    @Test
    void plainly_numbered_entries_are_entries_too() {
        List<ReferencesExtractor.Reference> found = ReferencesExtractor.extract("""
                References
                1. Wagner, A. Constructions in combinatorics, volume 2. Springer, 2021.
                2. Erdos, P. On a problem of graph theory. Publ. Math. Debrecen, 1965.
                """);

        assertEquals(2, found.size());
        assertEquals(2, found.get(1).refNum());
        assertTrue(found.get(1).raw().startsWith("Erdos"));
    }

    /** An entry wrapped over several lines is one entry: the wrapping is the
     *  page's and not the bibliography's. */
    @Test
    void a_wrapped_entry_is_re_flowed_onto_one_line() {
        List<ReferencesExtractor.Reference> found = ReferencesExtractor.extract("""
                References
                [1] Wagner, A. Constructions in combinatorics
                    using machine learning. Springer, 2021.
                """);

        assertEquals(1, found.size());
        assertEquals("Wagner, A. Constructions in combinatorics using machine learning."
                + " Springer, 2021.", found.get(0).raw());
    }

    @Test
    void a_document_with_no_bibliography_lists_none() {
        assertEquals(List.of(), ReferencesExtractor.extract("1. Introduction\nWe begin.\n"));
    }

    /**
     * <b>An author-year bibliography yields nothing, and that is the honest
     * answer rather than a gap.</b> The stored row is {@code (ref_num, raw)} —
     * Anchor's {@code Citation}, whose number is how the paper's own prose points
     * at the entry. A bibliography that numbers nothing has no such handle, and
     * inventing positions for its entries would put a number in a column whose
     * whole meaning is "what the document printed".
     */
    @Test
    void an_unnumbered_bibliography_yields_no_entries() {
        assertEquals(List.of(), ReferencesExtractor.extract("""
                References
                Wagner, A. (2021). Constructions in combinatorics. Springer.
                Erdos, P. (1965). On a problem. Publ. Math. Debrecen.
                """));
    }
}
