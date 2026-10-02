package io.aeyer.plowshare.client.files;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A real PDF, assembled here rather than by the library that reads it.
 *
 * <h2>Why it is written by hand</h2>
 *
 * <p>The obvious fixture is {@code PDDocument} plus a {@code PDPageContentStream}
 * — the extractor's own library, used as a writer. That fixture passes when the
 * library round-trips its own output and says nothing about whether this client
 * reads a PDF, which is the whole claim. These bytes are assembled from the
 * format: a header, five to seven indirect objects, a cross-reference table with
 * real byte offsets, and a trailer. Nothing on the class path produced them.
 *
 * <p><b>The offsets are computed and not approximated.</b> PDFBox will rebuild a
 * broken cross-reference table and carry on — measured — so a fixture with wrong
 * offsets would still pass while testing a recovery path no real document takes,
 * and it would log a warning per object while doing it.
 *
 * <h2>What it is not</h2>
 *
 * <p>Uncompressed content streams, one Type 1 base font, no encryption and no
 * embedded font programs. It is the smallest thing that is genuinely a PDF, and
 * a test that needs a hard one should say what is hard about it rather than
 * reach for a bigger fixture.
 */
final class Pdfs {

    /**
     * The four high bytes every real PDF writer puts on the second line.
     *
     * <p>§7.5.2 of the format recommends it so that a file transferred by
     * something that guesses at text is detected as binary. It matters twice
     * here. It makes the fixture a document a real writer would produce; and it
     * makes these bytes <b>not valid UTF-8</b>, which is what a PDF is —
     * without it the fixture's uncompressed streams decode cleanly and the whole
     * file reads as a page of syntax, so every test below would be measuring the
     * one PDF in the world this client could already read.
     */
    private static final String BINARY_COMMENT = "%âãÏÓ\n";

    private Pdfs() {
    }

    /**
     * A one-page PDF whose page holds exactly {@code line}.
     *
     * <p><b>Keep the text plain ASCII letters, digits and spaces.</b> Measured:
     * the base-14 Helvetica this fixture names carries no {@code /Encoding}, so
     * a reader applies StandardEncoding, in which byte {@code 0x27} is {@code
     * quoteright} — an apostrophe typed here extracts as U+2019. That is the
     * format behaving correctly and a fixture behaving confusingly, and a test
     * that had to assert the curly one would be asserting something about this
     * file rather than about the client.
     */
    static byte[] of(String line) {
        return of(List.of(line));
    }

    /**
     * A PDF with one page per string, each page holding that string and nothing
     * else.
     *
     * <p>Every page is one line of text, so a document of {@code n} pages
     * extracts to {@code n} lines — which is what lets a windowing test count in
     * pages and read in lines, and is exactly the coordinate confusion the
     * extraction is not allowed to imply anywhere a model can see.
     */
    static byte[] of(List<String> pages) {
        return of(pages, true);
    }

    /**
     * A readable PDF that makes the extractor complain while reading it.
     *
     * <p>Real documents do this constantly — a font that cannot be mapped, a
     * stream whose length is a byte out — and PDFBox says so at WARN, once per
     * occurrence. That is the whole hazard of putting a document library inside
     * an MCP server, so there has to be a fixture that provokes it.
     *
     * <p>What provokes it here: the content stream's {@code endstream} is not
     * followed by a newline, so the parser reports {@code stream ends with
     * 'endstreamendobj'} and recovers. Measured against 3.0.7 — one warning per
     * page, and the document still extracts correctly, which is exactly the
     * shape that matters: <b>a warning on a read that succeeded</b> is the one
     * nobody would notice landing on stdout until the session had desynchronised.
     */
    static byte[] chatty(String line) {
        return of(List.of(line), false);
    }

    private static byte[] of(List<String> pages, boolean wellFormed) {
        List<String> objects = new ArrayList<>();
        // 1 catalog, 2 the page tree, then a (page, contents) pair per page, and
        // the one shared font last.
        int font = 3 + pages.size() * 2;
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(3 + i * 2).append(" 0 R ");
        }
        objects.add("<</Type/Catalog/Pages 2 0 R>>");
        objects.add("<</Type/Pages/Kids[" + kids.toString().trim() + "]/Count "
                + pages.size() + ">>");
        for (int i = 0; i < pages.size(); i++) {
            objects.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 "
                    + font + " 0 R>>>>/Contents " + (4 + i * 2) + " 0 R>>");
            String drawing = "BT /F1 12 Tf 72 720 Td (" + pages.get(i) + ") Tj ET\n";
            objects.add("<</Length " + drawing.length() + ">>stream\n" + drawing + "endstream"
                    + (wellFormed ? "\n" : ""));
        }
        objects.add("<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n" + BINARY_COMMENT);
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj" + objects.get(i) + "endobj\n");
        }
        int startxref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            write(out, String.format("%010d 00000 n \n", offset));
        }
        write(out, "trailer<</Size " + (objects.size() + 1) + "/Root 1 0 R>>\nstartxref\n"
                + startxref + "\n%%EOF\n");
        return out.toByteArray();
    }

    /**
     * Bytes that open {@code %PDF-} and are not a PDF.
     *
     * <p>The case that separates "this client does not convert PDFs" from "this
     * PDF could not be converted": both are refusals and only one of them is
     * about the file.
     */
    static byte[] broken() {
        return ("%PDF-1.4\n" + BINARY_COMMENT + "this is not a document\n%%EOF\n")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * ISO-8859-1 and not UTF-8, deliberately: a PDF's syntax layer is bytes, the
     * fixture's text is ASCII, and {@code /Length} above counts characters. A
     * multi-byte encoding here would make that count wrong for any non-ASCII
     * string somebody later added to a page.
     */
    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }
}
