package io.aeyer.plowshare.server.archive;

/**
 * The archive does not hold what the caller named. Ported from Excalibur's
 * {@code ArchiveError}.
 *
 * <p>Raised where the caller believed something specific about the archive's
 * contents and was wrong: a verdict naming a target that does not exist, or a
 * read of an id that was never written. Those are refused rather than quietly
 * downgraded to a write that succeeds — a supersession silently filed as a new
 * memory looks identical to a working archive right up until the stale record
 * it should have retired answers a question.
 *
 * <p><b>Absent is now all this means, and {@link ArchiveRefusedException}
 * narrows it for the other half</b> — the row is there and the operation is
 * refused for what the row currently <em>is</em>. That class carries the whole
 * argument, every site's classification, and the reason the subclass points
 * this way round rather than the other; there is deliberately no second copy of
 * it here. What matters at this end is that <b>this type stays the one {@code
 * ApiExceptionHandler} maps</b>, so an archive throw nobody has classified
 * still answers 404 exactly as it did before the split.
 *
 * <p>Unchecked, for the same reason as {@link ValidationException}: it sits on
 * every archive path, and forcing every layer up to HTTP to declare it would
 * buy nothing a caller can act on differently.
 */
public class ArchiveException extends RuntimeException {

    public ArchiveException(String message) {
        super(message);
    }
}
