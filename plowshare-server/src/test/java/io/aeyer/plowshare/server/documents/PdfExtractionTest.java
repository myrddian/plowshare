package io.aeyer.plowshare.server.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.documents.Pdfs.Page;
import io.aeyer.plowshare.server.documents.Pdfs.Paragraph;
import io.aeyer.plowshare.server.llm.tokens.RatioTokenizer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

/**
 * The one format this server converts, and what it refuses about it.
 *
 * <p>Anchor's corpus is PDFs of academic papers, so a port that cannot read one
 * is a port that cannot be evaluated. The fixtures are assembled from the format
 * by {@link Pdfs} rather than written by the library that reads them.
 */
class PdfExtractionTest {

    /** Small bounds, a quarter of the shipped ones, so these short fixtures
     *  still exercise packing. */
    private static final Chunking SMALL = new Chunking(
            new RatioTokenizer(RatioTokenizer.DEFAULT_CHARACTERS_PER_TOKEN), 100, 200);

    private static final Page ONE_PAGE_OF_PROSE = Page.of(
            Paragraph.of("The first paragraph begins here and runs",
                    "across two lines of type."),
            Paragraph.of("A second paragraph follows it, set off by",
                    "a wider gap above."),
            Paragraph.of("A third paragraph closes the page."));

    @Test
    void a_pdf_comes_back_as_the_text_drawn_on_its_pages() {
        Extracted extracted = TextExtraction.extract("paper.pdf", Pdfs.of("A single line."));

        assertEquals("A single line.", extracted.text().strip());
    }

    /**
     * The format is read off the bytes and not off the name, in both directions.
     *
     * <p>The refusal this replaces made the same point — a PDF called {@code
     * notes.txt} was still a PDF — and it has to keep being true now that the
     * answer is to read it rather than to turn it away.
     */
    @Test
    void a_pdf_under_a_text_name_is_read_as_a_pdf() {
        Extracted extracted = TextExtraction.extract("notes.txt", Pdfs.of("A single line."));

        assertEquals("A single line.", extracted.text().strip());
    }

    /**
     * <b>The load-bearing assertion of this whole slice.</b>
     *
     * <p>{@link Derivation} splits on blank lines and re-flows what is between
     * them, so the extraction's blank lines <em>are</em> the paragraph
     * structure. PDFBox's shipped defaults put a blank line nowhere, which makes
     * a whole document one paragraph; a naive fix puts one after every line,
     * which makes every line a paragraph. Both destroy the hierarchy this port
     * exists to build, so what is asserted here is the count a reader would
     * count.
     */
    @Test
    void a_multi_paragraph_pdf_derives_the_paragraphs_a_reader_would_count() {
        byte[] pdf = Pdfs.of(List.of(ONE_PAGE_OF_PROSE,
                Page.of(Paragraph.of("The fourth paragraph opens the second page."))));

        Extracted extracted = TextExtraction.extract("paper.pdf", pdf);
        List<DerivedParagraph> derived = Derivation.derive(extracted, SMALL).paragraphs();

        assertEquals(4, derived.size(), extracted.text());
        assertEquals("The first paragraph begins here and runs across two lines of type.",
                derived.get(0).text());
        assertEquals("The fourth paragraph opens the second page.", derived.get(3).text());
    }

    /**
     * <b>The same count, on the other way a page spells a paragraph break.</b>
     *
     * <p>The test above draws its paragraphs the way a word processor does — a
     * wider gap, every first line at the same margin. This one draws them the
     * way LaTeX does, which is every paper in Anchor's corpus: <b>a first-line
     * indent and no gap at all</b>. Nothing about the prose changes; only how
     * the boundary is spelled on the page, and both spellings have to survive
     * into the blank lines {@link Derivation} splits on.
     *
     * <p><b>What this fixture does not show, recorded because an earlier version
     * of this comment claimed it did.</b> PDFBox separates a second time at a
     * line that begins to the left of the one above it — its own comment calls
     * that the end of a column — and the guess was that this fires on the line
     * under every indent, cutting each paragraph in two. It does not fire here,
     * and hand counting arXiv:2212.12473 says it does not fire there either: the
     * paper's body paragraphs come out whole at 301 and 464 characters, and only
     * 12 of its 134 rows are severed prose. What that branch mostly catches is
     * prose <em>resuming</em> after a display equation, which is a real
     * boundary. Suppressing it was measured and reverted; see {@code
     * PdfExtraction} and the port spec's §6a.1.
     */
    @Test
    void an_indented_paragraph_is_one_paragraph_and_not_its_first_line_and_the_rest() {
        byte[] pdf = Pdfs.indented(List.of(ONE_PAGE_OF_PROSE,
                Page.of(Paragraph.of("The fourth paragraph opens the second page."))));

        Extracted extracted = TextExtraction.extract("paper.pdf", pdf);
        List<DerivedParagraph> derived = Derivation.derive(extracted, SMALL).paragraphs();

        assertEquals(4, derived.size(), extracted.text());
        assertEquals("The first paragraph begins here and runs across two lines of type.",
                derived.get(0).text());
        assertEquals("A second paragraph follows it, set off by a wider gap above.",
                derived.get(1).text());
    }

    /**
     * The dedup gate is the source file.
     *
     * <p>Hashing the conversion would make the same PDF a different document
     * every time the converter changed, which is the one thing a content hash
     * exists to stop.
     */
    @Test
    void the_content_hash_is_of_the_pdf_and_not_of_the_text_taken_out_of_it() {
        byte[] pdf = Pdfs.of("A single line.");

        Extracted extracted = TextExtraction.extract("paper.pdf", pdf);

        assertEquals(sha256(pdf), extracted.contentHash());
        assertNotEquals(sha256(extracted.text().getBytes(StandardCharsets.UTF_8)),
                extracted.contentHash(),
                "a gate keyed on the conversion makes the same PDF a different document"
                        + " whenever the converter changes");
    }

    /** The JDK's, not this server's: an assertion that reuses the code under
     *  test proves the code agrees with itself. */
    private static String sha256(byte[] bytes) throws AssertionError {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** A PDF that names itself is named that, as Anchor's extractor does. */
    @Test
    void a_declared_title_beats_the_filename() {
        byte[] pdf = Pdfs.titled("On the Origin of Retries", List.of(ONE_PAGE_OF_PROSE));

        assertEquals("On the Origin of Retries",
                TextExtraction.extract("2109_04431v2.pdf", pdf).title());
    }

    /** And a PDF that does not falls back to the name, exactly as text does. */
    @Test
    void a_pdf_with_no_declared_title_is_filed_under_its_name() {
        assertEquals("retry budget notes",
                TextExtraction.extract("retry_budget-notes.pdf", Pdfs.of("Body.")).title());
    }

    // --- the outline, which is the other half of the point ------------------

    /**
     * The branch this whole slice is a blocking dependency for.
     *
     * <p>{@code ChapterDetector}'s second precedence branch — {@code ^Chapter N}
     * regex, then <b>this</b>, then {@code ^Part I/II}, then synthetic — has no
     * other source. The fixture gives every top-level entry a child, so a
     * collector that descended would come back with six titles rather than
     * three and this assertion would say so.
     */
    @Test
    void the_outlines_top_level_comes_back_in_the_authors_order() {
        byte[] pdf = Pdfs.outlined(List.of("Introduction", "Method", "Results"),
                List.of(ONE_PAGE_OF_PROSE));

        Extracted extracted = TextExtraction.extract("paper.pdf", pdf);

        assertEquals(List.of("Introduction", "Method", "Results"),
                extracted.outlineTopLevel());
    }

    /** Most PDFs carry none, and an absent outline is empty rather than an
     *  error — it means the next branch of the detector gets its turn. */
    @Test
    void a_pdf_with_no_outline_offers_an_empty_one() {
        Extracted extracted = TextExtraction.extract("paper.pdf", Pdfs.of("Body."));

        assertEquals(List.of(), extracted.outlineTopLevel());
    }

    /** Provenance: this text is a conversion, so an offset into it is not a
     *  position in the file somebody uploaded. */
    @Test
    void a_converted_document_records_what_converted_it() {
        Extracted extracted = TextExtraction.extract("paper.pdf", Pdfs.of("Body."));

        assertNotEquals(Extracted.NOT_CONVERTED, extracted.converter());
        assertEquals("PDFBox", extracted.converter());
    }

    // --- the refusals, which are 415s a person reads ------------------------

    /**
     * A scan is a real PDF that carries no text, and saying "holds no text, only
     * whitespace" would send whoever reads it looking at the wrong thing.
     */
    @Test
    void a_pdf_with_no_text_on_its_pages_is_refused_as_the_scan_it_probably_is() {
        UnreadableDocumentException refused = assertThrows(UnreadableDocumentException.class,
                () -> TextExtraction.extract("scan.pdf", Pdfs.noText()));

        assertTrue(refused.getMessage().contains("scan.pdf"), refused.getMessage());
        // Not "scan", which the filename already satisfies: this has to be the
        // sentence about the pages, and it has to say that no OCR is coming.
        assertTrue(refused.getMessage().contains("no text"), refused.getMessage());
        assertTrue(refused.getMessage().contains("OCR"), refused.getMessage());
    }

    /**
     * "This server does not read PDFs" and "this PDF could not be read" are
     * different sentences and only one of them is about the file.
     */
    @Test
    void a_damaged_pdf_is_refused_as_damaged_rather_than_as_a_format() {
        UnreadableDocumentException refused = assertThrows(UnreadableDocumentException.class,
                () -> TextExtraction.extract("torn.pdf", Pdfs.broken()));

        assertTrue(refused.getMessage().contains("torn.pdf"), refused.getMessage());
        assertTrue(refused.getMessage().contains("PDF"), refused.getMessage());
        assertTrue(refused.getMessage().contains("damaged"), refused.getMessage());
    }

    /**
     * An encrypted document needs a password, and there is nowhere on this
     * endpoint to put one — which is the right shape rather than a gap: a
     * multipart field carrying a password would be logged by whatever logs
     * requests.
     *
     * <p>The fixture is written by PDFBox, and that is the one place in this
     * file where it has to be: encryption is a thing {@link Pdfs} would have to
     * implement RC4 to produce, and what is under test is the refusal rather
     * than the cipher.
     */
    @Test
    void an_encrypted_pdf_is_refused_and_the_message_says_why_no_password_is_asked_for()
            throws Exception {
        byte[] locked;
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy(
                    "not-a-real-owner-password", "not-a-real-user-password",
                    new AccessPermission()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            locked = out.toByteArray();
        }

        UnreadableDocumentException refused = assertThrows(UnreadableDocumentException.class,
                () -> TextExtraction.extract("locked.pdf", locked));

        assertTrue(refused.getMessage().contains("locked.pdf"), refused.getMessage());
        assertTrue(refused.getMessage().contains("encrypted"), refused.getMessage());
        assertTrue(refused.getMessage().contains("password"), refused.getMessage());
    }
}
