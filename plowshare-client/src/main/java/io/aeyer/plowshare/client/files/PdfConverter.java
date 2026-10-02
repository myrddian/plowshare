package io.aeyer.plowshare.client.files;

import io.aeyer.plowshare.protocol.FileResult;
import java.io.IOException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * A PDF, as the text a person would read off it.
 *
 * <h2>The one dependency in this module that is not about talking to something</h2>
 *
 * <p>PDFBox, and the cost is written down rather than waved at: <b>three modules
 * — {@code pdfbox}, {@code fontbox}, {@code pdfbox-io} — and 3.58 MiB of
 * jars</b>, measured on this tree at 3.0.7. The Documents slice recorded the
 * same shape at 3.0.3 (3 modules, 3.5 MB) before reverting it on the instruction
 * that put this class here, and the comparison it was recorded against is the
 * reason a general answer was not taken instead: {@code
 * tika-parsers-standard-package} brings <b>eighty</b>.
 *
 * <p>{@code commons-logging}, which PDFBox declares as a fourth module, is
 * excluded and bridged — see {@code plowshare-client/build.gradle.kts}, where
 * the exclusion is a rule about stdout and not a size decision.
 *
 * <h2>What the extraction is, said plainly, because a caller cannot see it</h2>
 *
 * <p><b>The text drawn on the pages, in the order the content streams draw it,
 * and nothing else.</b> No page numbers, no headers marked as headers, no table
 * structure, no reading-order repair for a two-column layout, and <b>nothing at
 * all from a scanned page</b> — a PDF of photographs of text extracts to
 * nothing, and this class has no OCR and is not going to grow one.
 *
 * <p>{@link PDFTextStripper#setSortByPosition} is left at its default, which is
 * document order rather than geometric order. Both are wrong for some documents:
 * sorting by position repairs a two-column paper and scrambles a document whose
 * content streams were already in reading order. The default is kept because it
 * is what the extraction <em>is</em> — the file's own order — and a client that
 * silently rearranged a document would be making an editorial decision on
 * somebody's behalf.
 *
 * <p><b>An empty extraction is an empty extraction and not an error.</b> A
 * scanned PDF converts to no text, which is a true answer about it, and this
 * class returns it rather than inventing a refusal — {@link Conversions} decides
 * what an empty conversion means, in one place, for every format.
 *
 * <h2>Line endings are pinned, and the reason is not tidiness</h2>
 *
 * <p>{@code PDFTextStripper}'s separators default to {@code
 * System.lineSeparator()}, so the same PDF converts to a different string on
 * Windows than on this machine. What a caller gets back is lines, and the two
 * halves of this system disagreeing about what a line is — the thing {@code
 * ClientEnforcer.decode} already refuses to let happen for CRLF files — must not
 * arrive through the extractor instead.
 */
final class PdfConverter implements Converter {

    /**
     * {@code %PDF-} and not {@code %PDF}, which is the whole of the signature
     * the format guarantees.
     *
     * <p>Deliberately identical to the first entry of {@code
     * TextExtraction.KNOWN} on the server, which turns these same bytes away.
     * The two are separate literals in separate modules for that class's reason
     * — one refuses a format and one reads it — and both are five bytes because
     * that is what §7.5.2 says is there.
     */
    private static final byte[] MAGIC = {'%', 'P', 'D', 'F', '-'};

    @Override
    public String format() {
        return "PDF";
    }

    @Override
    public boolean recognises(byte[] bytes) {
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
     * {@inheritDoc}
     *
     * <p>Three failures are told apart, because the remedies are three different
     * things a person does. An encrypted document needs a password this client
     * has no way to ask for and will not take on a wire; a damaged one needs a
     * better copy; and everything else is named as far as it can be without
     * quoting a parser at somebody.
     */
    @Override
    public String toText(byte[] bytes) {
        // The password is the empty string and stays that way. A file channel
        // that could carry one would be a credential on a socket, in a request a
        // model composed, logged by whatever logs requests -- and there is no
        // frame for it, which is the right shape rather than a gap.
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            // AND pageEnd, which defaults to the same system separator. Both,
            // because both are captured from System.lineSeparator() when the
            // stripper is constructed, and pinning one leaves the other to make
            // the same document convert differently on Windows.
            //
            // MEASURED, because the obvious guess is wrong in both directions.
            // The stripper writes NO separator after the last line of a page --
            // pageEnd is that line's terminator, not an extra one. So "" glues
            // the last line of each page onto the first line of the next and a
            // four-page document extracts to one line, while "\n" reproduces
            // exactly one line break and adds no blank line. Nothing here is a
            // page marker: the boundary between two pages is spelled the same
            // way as the boundary between two lines, which is the only spelling
            // that cannot be read as a source coordinate.
            //
            // paragraphStart, paragraphEnd, pageStart and articleStart/End are
            // all left alone -- they default to empty, and setting paragraphEnd
            // to "\n" (an earlier version of this method did) puts a blank line
            // after every paragraph that is in no document anywhere.
            stripper.setPageEnd("\n");
            return stripper.getText(document);
        } catch (InvalidPasswordException locked) {
            throw new Failed(format(), FileResult.ENCRYPTED, locked);
        } catch (IOException | RuntimeException unreadable) {
            // RuntimeException as well as IOException, and it is not defensive.
            // PDFBox raises unchecked out of the parser for several shapes of
            // damaged file, and one of those reaching ClientEnforcer.answer's
            // residue clause would come back UNAVAILABLE and END THE RUN over
            // one unreadable document in a workspace of readable ones.
            throw new Failed(format(), FileResult.DAMAGED, unreadable);
        }
    }
}
