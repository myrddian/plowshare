package io.aeyer.plowshare.server.documents;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A PDF, as the text a person would read off it — and the outline its author
 * wrote.
 *
 * <p>Anchor's {@code PdfTextExtractor}, ported. The shape differs in one place
 * and it is the transport: Anchor takes a {@code Path} and reads it, while here
 * the bytes have already arrived as a multipart part on the request thread. So
 * there is no file, the content hash is computed by the caller over what
 * arrived, and a refusal raised here is a {@code 415} the uploader reads at
 * once rather than a job that fails later.
 *
 * <h2>Two outputs, not one</h2>
 *
 * <p>The text, and <b>the top-level entries of the document outline</b>. The
 * second is not a convenience: {@code ChapterDetector}'s second precedence
 * branch — {@code ^Chapter N} regex, then the PDF outline, then {@code ^Part
 * I/II}, then synthetic — reads it, and it is the branch that works on
 * well-made PDFs. Without it the port's chapter detection is permanently one
 * branch short. Only the top level is taken, because that is what a chapter is;
 * an outline's children are sections and below, and a detector handed a flat
 * list of every entry could not tell the two apart.
 *
 * <h2>What the extraction is, said plainly, because a caller cannot see it</h2>
 *
 * <p><b>The text drawn on the pages and nothing else.</b> No page numbers, no
 * headers marked as headers, no table structure, and <b>nothing at all from a
 * scanned page</b> — a PDF of photographs of text extracts to nothing, and this
 * class has no OCR and is not going to grow one. An offset into what comes back
 * is <b>not a coordinate in the file</b>: line 400 of an extraction is not page
 * 3 line 10 of the PDF, and nothing here should be read as if it were.
 *
 * <h2>Three separator defaults, all wrong, in different directions</h2>
 *
 * <p>All three are captured from {@link System#lineSeparator()} when the
 * stripper is constructed, so leaving any of them makes the same PDF convert
 * differently on Windows. That much this repository already knew. What matters
 * more here is what they do to <em>paragraphs</em>, because {@link Derivation}
 * splits on blank lines — so the blank lines in this text <em>are</em> the
 * paragraph structure of the document, and a converter that emits the wrong
 * number of them destroys the hierarchy this port exists to build.
 *
 * <p>Measured on PDFBox 3.0.7 against a four-paragraph fixture, and every one of
 * the three is a real answer rather than a tidy-up:
 *
 * <ul>
 *   <li>{@code paragraphEnd} defaults to <b>empty</b>, which puts a blank line
 *       <em>nowhere</em>: the four paragraphs come back as four consecutive
 *       lines and derive as <b>one</b> paragraph. Set to {@code "\n"} they
 *       derive as four. A PDF has no character meaning "new paragraph" — the
 *       boundary exists only as white space on a page, PDFBox detects it, and
 *       this is the only setting under which it survives into the text.
 *   <li>{@code pageEnd} defaults to the line separator, and <b>empty glues the
 *       pages together</b> — {@code pageEnd} is the page's last line's
 *       terminator rather than an extra one, so {@code ""} runs the last line of
 *       each page onto the first line of the next.
 *   <li>{@code lineSeparator} is the ordinary one and is pinned for the Windows
 *       reason alone.
 * </ul>
 *
 * <p>{@code paragraphStart} and {@code pageStart} are left alone: they default
 * to empty, and nothing here is a page marker. The boundary between two pages is
 * spelled the same way as the boundary between two paragraphs, which is the only
 * spelling that cannot be read as a source coordinate. <b>The cost of that is
 * named rather than hidden</b>: a paragraph that runs across a page break is two
 * paragraphs here, because a page break and a paragraph break arrive at this
 * class as the same blank line and no signal PDFBox offers tells them apart.
 *
 * <h2>Anchor sets none of the three, and its own corpus is the casualty</h2>
 *
 * <p>{@code PdfTextExtractor} constructs a bare {@code PDFTextStripper} and sets
 * only {@code setSortByPosition(true)}. Its extracted text therefore carries no
 * blank line anywhere, and {@code StructuralParser.splitParagraphs} — which is
 * {@link Derivation}'s own split, "one or more blank lines is a break" — folds
 * each of Anchor's sections into exactly <b>one</b> paragraph. Every PDF in
 * Anchor's corpus has a degenerate paragraph tier and nothing in Anchor says so.
 * This is the one place the port diverges from the reference implementation on
 * purpose, and the forcing reason is that porting it faithfully would port a
 * hierarchy with a level missing.
 *
 * <p><b>And Anchor's paragraph tier being degenerate does not make this one
 * right, which is what the divergence used to claim.</b> Hand counted on
 * arXiv:2212.12473, the paper has 36–42 paragraphs; Anchor derives 6 and this
 * derives 134. Two artefacts of the same proxy, failing in opposite directions,
 * and only one of them was written down as a defect.
 *
 * <p><b>The remainder is not fixed here, and the attempt to fix it here was
 * measured and reverted.</b> Suppressing PDFBox's third separation branch —
 * <i>"the previous line start was further right"</i>, which no setter reaches —
 * takes the count from 134 to 86 and looks like progress. Scored on
 * <em>seams</em> rather than units it is a regression: 16 of the 48 boundaries
 * it removes are real ones, because a line beginning left of the line above is
 * both the second line of an indented paragraph <em>and</em> prose resuming at
 * the margin after a display equation, and geometry cannot tell those apart.
 * Seam F1 went 0.47 to 0.40. The excess is display maths, matrices and
 * furniture, which want a content test in {@link Derivation} — where seams are
 * — and that is where one now is.
 *
 * <p>{@code setSortByPosition(true)} <em>is</em> carried, because that one is
 * right for the corpus: an academic paper is often two columns, and geometric
 * order is what makes a two-column page read as two columns rather than as
 * interleaved half-sentences. It is the opposite of what {@code
 * plowshare-client}'s {@code PdfConverter} does, and deliberately — a {@code
 * file_read} window is supposed to be the file's own order.
 *
 * <p>Static, and not a {@code @Service}, for {@link TextExtraction}'s reason:
 * there is no state and nothing to inject.
 */
final class PdfExtraction {

    /**
     * {@code %PDF-} and not {@code %PDF}, which is the whole of the signature
     * the format guarantees.
     *
     * <p>Deliberately identical to {@code PdfConverter.MAGIC} in the client, and
     * to the entry this replaced in {@code TextExtraction.KNOWN}. Separate
     * literals in separate modules, because the two do different things with the
     * same five bytes.
     */
    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};

    /** What {@code Extracted.converter} says for anything this class produced. */
    static final String CONVERTER = "PDFBox";

    private PdfExtraction() {
    }

    /** Whether these bytes open with the PDF signature. */
    static boolean recognises(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The document, or a refusal naming it.
     *
     * @param name what the sender called it; used in every refusal and as the
     *     title of last resort, never for deciding the format
     * @param bytes the upload, exactly as it arrived
     * @param contentHash the SHA-256 of those bytes, computed by the caller.
     *     <b>Of the original file and never of the extraction</b> — the dedup
     *     gate is the document somebody sent, and one keyed on the conversion
     *     would make the same PDF a different document every time the converter
     *     changed
     * @param nameTitle the title derived from {@code name}, used when the
     *     document declares none of its own
     * @throws UnreadableDocumentException for an encrypted PDF, one the parser
     *     cannot read, and one whose pages carry no text. All three are facts
     *     about the document and none of them is retryable
     */
    static Extracted extract(String name, byte[] bytes, String contentHash, String nameTitle) {
        String raw;
        List<String> outline;
        String declaredTitle;
        // The parse is the whole of the try. Every check below it raises
        // UnreadableDocumentException, and inside a block that catches
        // RuntimeException those would be caught and re-dressed as "damaged".
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setPageEnd("\n");
            stripper.setParagraphEnd("\n");
            raw = stripper.getText(document);
            outline = topLevelOf(document.getDocumentCatalog().getDocumentOutline());
            declaredTitle = declaredTitleOf(document);
        } catch (InvalidPasswordException locked) {
            // The password is the empty string and stays that way. There is no
            // field on this endpoint that could carry one, and adding it would
            // put a credential in a multipart body that whatever logs requests
            // would log.
            throw new UnreadableDocumentException(
                    "'" + name + "' is an encrypted PDF and this server has no password for it;"
                            + " remove the encryption and upload it again");
        } catch (IOException | RuntimeException unreadable) {
            // RuntimeException as well as IOException, and it is not defensive:
            // PDFBox raises unchecked out of its parser for several shapes of
            // damaged file, and one of those escaping here would reach the
            // caller as a 500 saying this server is broken.
            throw new UnreadableDocumentException(
                    "'" + name + "' is a PDF this server could not read; the file is damaged, or"
                            + " it is a variant of the format PDFBox does not parse");
        }

        // \r\n and a lone \r both become \n, so "a blank line is a paragraph
        // break" is one rule rather than three. The separators above are already
        // pinned; this is about a carriage return inside a text run in the file.
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');

        if (text.isBlank()) {
            throw new UnreadableDocumentException(
                    "'" + name + "' is a PDF whose pages carry no text — it is almost certainly a"
                            + " scan, or a set of images, and this server has no OCR. Run it"
                            + " through one and upload the result");
        }
        int nul = text.indexOf('\0');
        if (nul >= 0) {
            // Not paranoia and not a UTF-8 question: a broken ToUnicode map
            // extracts to U+0000, and Postgres cannot store a NUL in a text
            // column at all — so the alternative to this sentence is a 500 from
            // the insert, three layers away from the document that caused it.
            throw new UnreadableDocumentException(
                    "'" + name + "' extracts to text holding a NUL at offset " + nul + ", which"
                            + " means the document's character mapping is broken rather than that"
                            + " the file is; there is no prose here for a corpus to hold");
        }

        String title = declaredTitle == null || declaredTitle.isBlank()
                ? nameTitle
                : declaredTitle.trim();
        return new Extracted(title, contentHash, text, outline, CONVERTER);
    }

    /**
     * The outline's top-level entries, in the author's order.
     *
     * <p>Anchor's {@code collectOutlineTitles} unchanged, including the blank
     * filter: an outline entry with no title is a bookmark somebody left behind,
     * and a chapter called "" is worse than a chapter the detector never saw.
     */
    private static List<String> topLevelOf(PDDocumentOutline outline) {
        List<String> titles = new ArrayList<>();
        if (outline == null) {
            return titles;
        }
        PDOutlineItem item = outline.getFirstChild();
        while (item != null) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) {
                titles.add(title.trim());
            }
            item = item.getNextSibling();
        }
        return titles;
    }

    /** The {@code /Info} dictionary's title, or null when there is none. */
    private static String declaredTitleOf(PDDocument document) {
        return document.getDocumentInformation() == null
                ? null
                : document.getDocumentInformation().getTitle();
    }
}
