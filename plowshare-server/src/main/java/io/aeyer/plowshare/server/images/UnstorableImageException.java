package io.aeyer.plowshare.server.images;

import io.aeyer.plowshare.protocol.ImageFormat;

/**
 * An upload this server will not hold, and which of the three reasons it is.
 *
 * <p><b>The reason is an enum and not only a sentence</b>, because the three answer with three
 * different statuses and a handler that had to read the message to choose would be parsing prose.
 * {@code UnreadableDocumentException} gets away with one status because every one of its refusals
 * is the same fact — this server does not read that format — and these are not: a file that is not
 * an image is the caller's format problem, and a PNG over the cap is a perfectly good PNG this
 * deployment has decided not to hold.
 *
 * <p>The message is still the whole of what a person is told. The enum decides the number; it never
 * replaces the sentence.
 */
public class UnstorableImageException extends RuntimeException {

  /** Why the upload was refused, which is also which status it answers. */
  public enum Reason {
    /** Nothing arrived, or an empty file did. Answered as 400. */
    EMPTY,
    /** The bytes are not one of {@link ImageFormat}'s four. Answered as 415. */
    UNRECOGNISED,
    /** A recognised image over {@code plowshare.images.max-bytes}. Answered as 413. */
    TOO_LARGE
  }

  private final transient Reason reason;

  public UnstorableImageException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
