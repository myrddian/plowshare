package io.aeyer.plowshare.server.documents;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A real PDF, assembled here rather than by the library that reads it.
 *
 * <h2>Why it is written by hand</h2>
 *
 * <p>The obvious fixture is {@code PDDocument} plus a {@code PDPageContentStream} — the extractor's
 * own library, used as a writer. That fixture passes when the library round-trips its own output
 * and says nothing about whether this server reads a PDF, which is the whole claim. These bytes are
 * assembled from the format: a header, a handful of indirect objects, a cross-reference table with
 * real byte offsets, and a trailer. Nothing on the class path produced them.
 *
 * <p><b>The offsets are computed and not approximated.</b> PDFBox will rebuild a broken
 * cross-reference table and carry on, so a fixture with wrong offsets would still pass while
 * testing a recovery path no real document takes.
 *
 * <p>The server owns PDF conversion. These fixtures cover source-window conversion and corpus
 * derivation, including paragraphs with vertical gaps, outlines and document titles.
 *
 * <h2>What it is not</h2>
 *
 * <p>Uncompressed content streams, one Type 1 base font, no encryption and no embedded font
 * programs. It is the smallest thing that is genuinely a PDF, and a test that needs a hard one
 * should say what is hard about it rather than reach for a bigger fixture.
 */
public final class Pdfs {

  /**
   * The four high bytes every real PDF writer puts on the second line.
   *
   * <p>§7.5.2 of the format recommends it so that a file transferred by something that guesses at
   * text is detected as binary. It matters twice here: it makes the fixture a document a real
   * writer would produce, and it makes these bytes <b>not valid UTF-8</b>, so the fixture cannot
   * pass through the text path by accident.
   */
  private static final String BINARY_COMMENT = "%âãÏÓ\n";

  /** 12pt type on 14pt leading, which is what the content streams below set. */
  private static final double LEADING = 14;

  /** Where every line that is not an indented first line begins. */
  private static final double MARGIN = 72;

  /**
   * One em at 12pt, which is LaTeX's {@code \parindent} to within a point.
   *
   * <p><b>It has to clear PDFBox's own threshold or the fixture proves nothing.</b> That threshold
   * is {@code indentThreshold} space widths, and a space in 12pt Helvetica is 0.278 em — so 12
   * points is 3.6 space widths against a threshold of 2.0, which is a real indent and not a hair's
   * breadth over the line.
   */
  private static final double INDENT = 12;

  private Pdfs() {}

  /**
   * One run of lines, laid out with no gap between them.
   *
   * <p><b>Keep the text plain ASCII letters, digits and spaces.</b> The base-14 Helvetica this
   * fixture names carries no {@code /Encoding}, so a reader applies StandardEncoding, in which byte
   * {@code 0x27} is {@code quoteright} — an apostrophe typed here extracts as U+2019.
   */
  public record Paragraph(List<String> lines) {

    public static Paragraph of(String... lines) {
      return new Paragraph(List.of(lines));
    }
  }

  /** One page, holding its paragraphs one under another. */
  public record Page(List<Paragraph> paragraphs) {

    public static Page of(Paragraph... paragraphs) {
      return new Page(List.of(paragraphs));
    }
  }

  /** A one-page, one-paragraph, one-line document. */
  public static byte[] of(String line) {
    return build(List.of(Page.of(Paragraph.of(line))), List.of(), null, true, Layout.GAPPED);
  }

  /** Pages exactly as given, with no outline and no declared title. */
  public static byte[] of(List<Page> pages) {
    return build(pages, List.of(), null, true, Layout.GAPPED);
  }

  /**
   * The same pages laid out the way a paper is: <b>the first line of each paragraph indented, and
   * no extra leading between them</b>.
   *
   * <p>{@link Layout#GAPPED}, which every other fixture here uses, is the layout a word processor
   * produces and it is <em>not</em> the one this corpus is made of. Every arXiv preprint is LaTeX's
   * {@code \parindent} with no {@code \parskip}: the boundary between two paragraphs is drawn as an
   * indent and nothing else, and the vertical rhythm never changes.
   *
   * <p>That distinction is not cosmetic. It is the layout under which PDFBox's paragraph detection
   * separates <em>twice</em> per paragraph — see {@code PdfExtraction.Stripper} — so a fixture that
   * only ever draws gaps cannot fail on the defect that put 134 rows in the corpus where the paper
   * has 42.
   */
  public static byte[] indented(List<Page> pages) {
    return build(pages, List.of(), null, true, Layout.INDENTED);
  }

  /** How {@link #draw} spells the boundary between two paragraphs. */
  private enum Layout {

    /** A wider vertical gap, first lines all at the same margin. */
    GAPPED,

    /** A first-line indent, on unbroken leading. */
    INDENTED
  }

  /**
   * A document carrying a top-level outline.
   *
   * <p>Every top-level entry is given one child, because the branch under test takes the top level
   * <em>only</em> — a fixture with a flat outline would pass whether or not the collector descends.
   */
  public static byte[] outlined(List<String> topLevel, List<Page> pages) {
    return build(pages, topLevel, null, true, Layout.GAPPED);
  }

  /** A document whose {@code /Info} dictionary names it. */
  public static byte[] titled(String declaredTitle, List<Page> pages) {
    return build(pages, List.of(), declaredTitle, true, Layout.GAPPED);
  }

  /**
   * A well-formed PDF with a page that draws nothing.
   *
   * <p>A scan is this from the extractor's side: a real document, parsed without complaint, whose
   * pages carry no text objects at all.
   */
  public static byte[] noText() {
    return build(List.of(new Page(List.of())), List.of(), null, true, Layout.GAPPED);
  }

  /**
   * A readable PDF that makes the extractor complain while reading it.
   *
   * <p>Real documents do this constantly — a font that cannot be mapped, a stream whose length is a
   * byte out — and PDFBox says so at WARN, once per occurrence. That is the whole hazard of putting
   * a document library in a server, so there has to be a fixture that provokes it.
   *
   * <p>What provokes it here: the content stream's {@code endstream} is not followed by a newline,
   * so the parser reports {@code stream ends with 'endstreamendobj'} and recovers. Measured against
   * 3.0.7 — one warning per page, and the document still extracts correctly, which is exactly the
   * shape that matters: <b>a warning on a read that succeeded</b> is the one nobody would notice
   * going somewhere this server's logging does not decide.
   */
  public static byte[] chatty(String line) {
    return build(List.of(Page.of(Paragraph.of(line))), List.of(), null, false, Layout.GAPPED);
  }

  /**
   * Bytes that open {@code %PDF-} and are not a PDF.
   *
   * <p>The case that separates "this server does not read PDFs" from "this PDF could not be read":
   * both are refusals and only one of them is about the file.
   */
  public static byte[] broken() {
    return ("%PDF-1.4\n" + BINARY_COMMENT + "this is not a document\n%%EOF\n")
        .getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] build(
      List<Page> pages,
      List<String> outline,
      String declaredTitle,
      boolean wellFormed,
      Layout layout) {

    List<String> objects = new ArrayList<>();
    int pageCount = pages.size();
    // 1 catalog, 2 the page tree, then a (page, contents) pair per page, the
    // one shared font, then the outline root and two objects per entry.
    int font = 3 + pageCount * 2;
    int outlineRoot = font + 1;
    int firstItem = outlineRoot + 1;
    boolean hasOutline = !outline.isEmpty();

    StringBuilder kids = new StringBuilder();
    for (int i = 0; i < pageCount; i++) {
      kids.append(3 + i * 2).append(" 0 R ");
    }
    objects.add(
        "<</Type/Catalog/Pages 2 0 R"
            + (hasOutline ? "/Outlines " + outlineRoot + " 0 R" : "")
            + ">>");
    objects.add("<</Type/Pages/Kids[" + kids.toString().trim() + "]/Count " + pageCount + ">>");
    for (int i = 0; i < pageCount; i++) {
      objects.add(
          "<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Resources<</Font<</F1 "
              + font
              + " 0 R>>>>/Contents "
              + (4 + i * 2)
              + " 0 R>>");
      String drawing = draw(pages.get(i), layout);
      objects.add(
          "<</Length "
              + drawing.length()
              + ">>stream\n"
              + drawing
              + "endstream"
              + (wellFormed ? "\n" : ""));
    }
    objects.add("<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>");
    if (hasOutline) {
      objects.add(
          "<</Type/Outlines/First "
              + firstItem
              + " 0 R/Last "
              + (firstItem + (outline.size() - 1) * 2)
              + " 0 R/Count "
              + outline.size()
              + ">>");
      for (int i = 0; i < outline.size(); i++) {
        int self = firstItem + i * 2;
        int child = self + 1;
        StringBuilder item =
            new StringBuilder("<</Title(" + outline.get(i) + ")/Parent " + outlineRoot + " 0 R");
        if (i > 0) {
          item.append("/Prev ").append(self - 2).append(" 0 R");
        }
        if (i < outline.size() - 1) {
          item.append("/Next ").append(self + 2).append(" 0 R");
        }
        item.append("/First ")
            .append(child)
            .append(" 0 R/Last ")
            .append(child)
            .append(" 0 R/Count 1/Dest[3 0 R /Fit]>>");
        objects.add(item.toString());
        objects.add(
            "<</Title("
                + outline.get(i)
                + " in more detail)/Parent "
                + self
                + " 0 R/Dest[3 0 R /Fit]>>");
      }
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    List<Integer> offsets = new ArrayList<>();
    write(out, "%PDF-1.4\n" + BINARY_COMMENT);
    for (int i = 0; i < objects.size(); i++) {
      offsets.add(out.size());
      write(out, (i + 1) + " 0 obj" + objects.get(i) + "endobj\n");
    }
    int info = objects.size() + 1;
    if (declaredTitle != null) {
      offsets.add(out.size());
      write(out, info + " 0 obj<</Title(" + declaredTitle + ")>>endobj\n");
    }
    int startxref = out.size();
    int size = offsets.size() + 1;
    write(out, "xref\n0 " + size + "\n0000000000 65535 f \n");
    for (int offset : offsets) {
      write(out, String.format("%010d 00000 n \n", offset));
    }
    write(
        out,
        "trailer<</Size "
            + size
            + "/Root 1 0 R"
            + (declaredTitle == null ? "" : "/Info " + info + " 0 R")
            + ">>\nstartxref\n"
            + startxref
            + "\n%%EOF\n");
    return out.toByteArray();
  }

  /**
   * A page's content stream.
   *
   * <p>Each line is placed with its own text matrix at a falling {@code y}, and <b>a paragraph
   * break is a wider gap and nothing else</b> — which is what a paragraph is in a PDF. There is no
   * character in the file that says "new paragraph"; the boundary exists only as white space on a
   * page, and whether the extraction preserves it is the whole question this fixture is built to
   * ask.
   */
  private static String draw(Page page, Layout layout) {
    if (page.paragraphs().isEmpty()) {
      return "";
    }
    StringBuilder drawing = new StringBuilder("BT /F1 12 Tf\n");
    double y = 720;
    for (Paragraph paragraph : page.paragraphs()) {
      boolean first = true;
      for (String line : paragraph.lines()) {
        double x = layout == Layout.INDENTED && first ? MARGIN + INDENT : MARGIN;
        drawing
            .append("1 0 0 1 ")
            .append(x)
            .append(' ')
            .append(y)
            .append(" Tm (")
            .append(line)
            .append(") Tj\n");
        y -= LEADING;
        first = false;
      }
      if (layout == Layout.GAPPED) {
        y -= LEADING;
      }
    }
    return drawing.append("ET\n").toString();
  }

  /**
   * ISO-8859-1 and not UTF-8, deliberately: a PDF's syntax layer is bytes, the fixture's text is
   * ASCII, and {@code /Length} above counts characters. A multi-byte encoding here would make that
   * count wrong for any non-ASCII string somebody later added to a page.
   */
  private static void write(ByteArrayOutputStream out, String text) {
    byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
    out.write(bytes, 0, bytes.length);
  }
}
