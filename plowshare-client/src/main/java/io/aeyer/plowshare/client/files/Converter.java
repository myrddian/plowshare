package io.aeyer.plowshare.client.files;

/**
 * One format this client can turn into text.
 *
 * <h2>The format is read off the bytes, never off the name</h2>
 *
 * <p>{@link #recognises} is handed the file's bytes and not its path, and there is no method here
 * that takes a name. That is deliberate and it is the same rule {@code TextExtraction} states on
 * the server: <em>an extension is a claim the sender makes and a signature is what the file
 * is.</em> Dispatching on {@code .pdf} would convert a text file somebody named badly and refuse a
 * PDF called {@code notes.txt}, and this client has the bytes in its hand either way, so there is
 * nothing to trade.
 *
 * <h2>An implementation does no I/O and holds no state</h2>
 *
 * <p>It is given every byte of the file at once, because {@link ClientEnforcer} has already read
 * the file to decide whether it was text — reading it again through the library's own stream would
 * open a second window on a path that has been checked once. Implementations are called from
 * several threads at once and must be safe to be; nothing about turning bytes into a string needs
 * to be otherwise.
 *
 * <h2>Adding one</h2>
 *
 * <p>A class here and a line in {@link Conversions#STANDARD}. The buffer, the invalidation key, the
 * refusal wording and the windowing are all somebody else's, so a second format is genuinely those
 * two things — which is the point of the seam and the reason this slice ships one format rather
 * than a survey.
 */
interface Converter {

  /**
   * What this reads, in a person's words: {@code "PDF"}.
   *
   * <p>Reaches a model through a refusal, so it is a word a reader knows rather than a MIME type or
   * a class name. It is also what {@link Conversions#formats} declares this client can do.
   */
  String format();

  /**
   * Whether these bytes are this converter's format.
   *
   * <p>Cheap, and it has to be: it runs on every file this client reads that is not valid UTF-8
   * text, so it is a look at a magic number and never a parse. A converter that recognised a file
   * by trying to convert it would make every unreadable binary in a workspace cost a full parse.
   *
   * @param bytes the whole file. Possibly empty, never null
   */
  boolean recognises(byte[] bytes);

  /**
   * The text in these bytes.
   *
   * <p>Called only after {@link #recognises} said yes, and only when the buffer does not already
   * hold the answer.
   *
   * @return the whole extraction. Line endings are normalised by {@link Conversions}, so an
   *     implementation may return whatever its library produces
   * @throws Failed when these particular bytes cannot be converted — encrypted, truncated, a format
   *     version the library does not read. A fact about the document, and the sentence goes to
   *     whoever asked for it
   */
  String toText(byte[] bytes) throws Failed;

  /**
   * This document could not be converted, as opposed to this client not converting the format.
   *
   * <p><b>The distinction is the whole reason this type exists</b>, and it is the one a caller can
   * act on. "This client does not read images" is a fact about the build and the answer is to send
   * something else; "this PDF is encrypted" is a fact about the file and the answer is to unlock
   * it. A single refusal covering both would send a person to change the wrong thing.
   *
   * <p>Unchecked, for the reason every refusal in this package is: {@link ClientEnforcer#answer}
   * turns everything into a reply, and a checked exception here would put a {@code throws} clause
   * on the read path for a case that is already handled there.
   */
  final class Failed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String format;

    private final String reason;

    /**
     * @param format the {@link #format()} of whatever claimed the bytes. It is carried on the
     *     exception rather than looked up by the catcher because the refusal has to say
     *     <em>both</em> halves — that this client does read PDFs, and that this PDF is the problem
     *     — and a catcher holding only the reason can say the second
     * @param reason what is wrong with the document, as a {@code FileResult} reason — {@code
     *     ENCRYPTED} or {@code DAMAGED} — which the server words (spec 2026-09-30: the file side
     *     reports facts). Never a sentence: this client writes nothing a model reads
     * @param cause the library's own exception, kept for a log line and <b>never</b> put in a
     *     refusal: a stack trace from a PDF parser is not something a reader can act on
     */
    Failed(String format, String reason, Throwable cause) {
      super(format + " " + reason, cause);
      this.format = format;
      this.reason = reason;
    }

    Failed(String format, String reason) {
      this(format, reason, null);
    }

    /** Why, as a {@code FileResult} reason. */
    String reason() {
      return reason;
    }

    /**
     * What this was, so a refusal can say the client reads the format and the document is what
     * failed.
     */
    String format() {
      return format;
    }
  }
}
