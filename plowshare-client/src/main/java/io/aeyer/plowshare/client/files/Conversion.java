package io.aeyer.plowshare.client.files;

import java.util.Objects;

/**
 * One file's bytes, as text, with the two facts that say where the text came from.
 *
 * <h2>Why the two facts travel with the text</h2>
 *
 * <p>They are the ones {@code TextExtraction}'s javadoc names, one module over, as what the ingest
 * path would need from this seam: <em>"whole text in one delivery rather than windows, the SHA-256
 * of the original bytes (this pipeline's dedup gate is the source file, not its conversion), and a
 * name for what converted it."</em> Nothing in this slice sends any of it anywhere — reading is
 * windowed and stays windowed, and {@code POST /v1/documents} is untouched — so this record is not
 * a wire type and must not become one on the strength of being shaped like the answer to a
 * different question.
 *
 * <p>It carries them anyway because <b>both are already computed</b>. The hash is the buffer's key
 * and would exist if nobody ever asked for it (see {@link Conversions}); the converter's name is
 * what a refusal has to say. A later slice that does deliver whole text will find them here rather
 * than adding a second hash of the same bytes.
 *
 * <h2>{@code text} is an extraction and not a rendering of the source</h2>
 *
 * <p><b>An offset into {@link #text} is not a coordinate in the file it came from.</b> Line 400 of
 * a PDF's extraction is not page 3 line 10 of the PDF, and this record holds nothing that could be
 * mistaken for the second: no page numbers, no byte offsets into the source, no markers. That is
 * the whole of how the promise is kept — not by a caveat somewhere downstream, but by the fact
 * never being available to state.
 *
 * @param format what {@link #text} was extracted from, in a person's words — {@code "PDF"}. Used in
 *     the sentence a failed conversion gives, and it is the converter's own word for the format
 *     rather than a MIME type: the reader is a person or a model, and neither of them typed {@code
 *     application/pdf}
 * @param sourceSha256 the SHA-256 of the <b>original</b> bytes, lowercase hex. Of the source and
 *     never of {@link #text}: two converters, or two versions of one converter, may make different
 *     text of one file, and a key that changed when the extractor changed would be a key that could
 *     never notice the file had changed
 * @param text the whole conversion, newline-separated, with {@code \r\n} and a lone {@code \r}
 *     already folded to {@code \n} so that "a line" means here what it means for a file this client
 *     did not have to convert
 */
public record Conversion(String format, String sourceSha256, String text) {

  public Conversion {
    Objects.requireNonNull(format, "format");
    Objects.requireNonNull(sourceSha256, "sourceSha256");
    Objects.requireNonNull(text, "text");
  }

  /**
   * How much of the buffer's allowance this conversion occupies. Characters and not bytes, because
   * what is held is a {@code String}.
   */
  int weight() {
    return text.length();
  }
}
