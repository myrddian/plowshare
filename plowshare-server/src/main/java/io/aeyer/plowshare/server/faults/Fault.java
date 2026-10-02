package io.aeyer.plowshare.server.faults;

import io.aeyer.plowshare.protocol.frames.Code;
import java.util.Objects;

/**
 * One outcome of {@link Faults#of(Throwable)}: a {@link Code} and the sentence
 * that goes with it.
 *
 * <h2>Why this wraps {@link Code} rather than an {@code int} and a
 * {@code String} of its own</h2>
 *
 * <p>{@code Code} is already the one status vocabulary the socket and HTTP
 * surfaces share — see its own class javadoc. A {@code Fault} that carried a
 * fresh {@code httpStatus}/{@code slug} pair instead of a {@link Code} would be
 * a second place those two facts could be written down, and the two could
 * drift apart the moment somebody edited one without the other. Holding the
 * {@link Code} itself makes that impossible by construction: {@link
 * #httpStatus()} and {@link #error()} are reads of {@link Code#httpStatus()}
 * and {@link Code#slug()}, never a second copy of either number or word.
 *
 * <h2>{@code detail} is the one thing {@link Code} does not carry</h2>
 *
 * <p>{@link Code} is deliberately generic across every request that ever hits
 * {@link Code#IMAGE_NOT_STORED_TOO_LARGE}, so it cannot also hold the one
 * sentence that names <em>this</em> request's own path, id or byte count. That
 * sentence
 * is what {@code detail} is — {@code ApiExceptionHandler}'s {@code "detail"}
 * body key, produced once by {@link Faults#of(Throwable)} and read by both
 * surfaces without either reconstructing it.
 */
public record Fault(Code code, String detail) {

    /** Rejects a null {@link Code} or {@code detail} rather than a caller finding out later. */
    public Fault {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
    }

    /** {@link Code#httpStatus()}, read through the one place it is stored. */
    public int httpStatus() {
        return code.httpStatus();
    }

    /** {@link Code#slug()}, {@code ApiExceptionHandler}'s {@code "error"} body key. */
    public String error() {
        return code.slug();
    }
}
