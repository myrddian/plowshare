package io.aeyer.plowshare.server.documents;

import java.util.List;
import java.util.Objects;

/**
 * A document's text, and the facts about it that are not derivable from the text.
 *
 * <p>The format seam, and Anchor's {@code ExtractedDocument} is where the shape comes from:
 * everything downstream — paragraph splitting, chunking, embedding, persistence — works on this
 * record and knows nothing about what produced it. That is what makes a second format a second
 * producer rather than an edit to the pipeline.
 *
 * <p><b>{@code outlineTopLevel} was declined once and the reason has expired.</b> It was left off
 * on the judgement that Anchor's detectors are a regression log fitted to English chemistry and
 * optimisation PDFs, so a field whose only consumer was declined would be a component nothing
 * reads. The faithful port takes the hierarchy back — it is the structure the per-document ask is
 * built on rather than an improvement on it — and {@code ChapterDetector}'s second precedence
 * branch reads exactly this list. It is here one stage ahead of that detector on purpose: it is the
 * input the detector cannot be written without, and it can only be collected where the PDF is still
 * a PDF.
 *
 * @param title what to call this document. Never blank
 * @param contentHash SHA-256, lower-case hex, of the bytes <b>as they arrived</b> — before
 *     line-ending normalisation, before a byte-order mark is dropped, and <b>before any
 *     conversion</b>. It is the dedup gate, so it has to be a fact about what was sent rather than
 *     about what extraction made of it: a gate keyed on the conversion would make the same PDF a
 *     different document every time the converter changed
 * @param text the whole document as text, with {@code \r\n} and {@code \r} already newlines. Blank
 *     is permitted only for explicit code extraction. <b>An offset into it is not a coordinate in
 *     the source file</b> when something converted it — line 400 of an extraction is not page 3
 *     line 10 of a PDF
 * @param outlineTopLevel the top-level entries of the document's own outline, in the author's
 *     order, or empty when the format has no such thing or the document carries none. Never null.
 *     Only the top level: an outline's children are sections and below, and a chapter detector
 *     handed all of them flattened could not tell the two apart
 * @param converter the name of what turned the bytes into {@code text}, or {@link #NOT_CONVERTED}
 *     when nothing did. It is provenance and it is the only thing that answers "may I treat an
 *     offset in this text as a position in the file somebody sent", which is {@link #NOT_CONVERTED}
 *     and nothing else
 */
public record Extracted(
    String title, String contentHash, String text, List<String> outlineTopLevel, String converter) {

  /**
   * The bytes already were the text, so nothing converted them.
   *
   * <p>A value rather than a null, because every caller of {@code converter()} has to handle this
   * case and a null makes forgetting to the default.
   */
  public static final String NOT_CONVERTED = "none";

  /** Strict UTF-8 code with BOM removal and newline normalization; empty files remain source. */
  public static final String CODE_UTF8 = "code-utf8-newlines-v1";

  /**
   * Whether the blank lines in {@link #text} are this server's guess at where the paragraphs are,
   * rather than the author's own paragraph marks.
   *
   * <p><b>The distinction decides whether {@link Derivation} is allowed to overrule them.</b> In a
   * text file a blank line is a mark the author typed; there is nothing to repair and a rule that
   * joined two of those blocks would be deleting a paragraph somebody wrote. In a PDF there is no
   * character meaning "new paragraph" at all — {@code PdfExtraction} asks PDFBox to infer the
   * boundaries from glyph geometry, and hand counted on arXiv:2212.12473 that inference is wrong
   * about <b>69%</b> of what it produces.
   *
   * <p>Measured on the two books that made this a rule rather than a preference: on Frankenstein
   * the join would take {@code “Yes.”} — a line of dialogue, a paragraph by every convention of the
   * form — into the paragraph above it, along with the dateline of every letter. Those are not
   * extraction artefacts. They are the text.
   *
   * <p><b>Keyed on the converter, and only PDF answers true.</b> A DOCX carries real paragraph
   * elements and would answer false when that format arrives; the question is not "was this
   * converted" but "did the converter have to guess", and those are different questions for
   * different formats.
   */
  public boolean paragraphsWereInferred() {
    return PdfExtraction.CONVERTER.equals(converter);
  }

  public Extracted {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(contentHash, "contentHash");
    Objects.requireNonNull(text, "text");
    Objects.requireNonNull(converter, "converter");
    // List.copyOf, which also refuses a null entry: a nameless chapter is
    // worse than a chapter the detector never saw.
    outlineTopLevel = List.copyOf(Objects.requireNonNull(outlineTopLevel, "outlineTopLevel"));
    if (title.isBlank()) {
      throw new IllegalArgumentException("a document with no title cannot be filed");
    }
    if (text.isBlank() && !CODE_UTF8.equals(converter)) {
      throw new IllegalArgumentException(
          "a document whose extracted text is blank has nothing to ingest");
    }
    if (converter.isBlank()) {
      throw new IllegalArgumentException(
          "converter is provenance and a blank one records nothing; it is '"
              + NOT_CONVERTED
              + "' when the bytes were already text");
    }
  }
}
