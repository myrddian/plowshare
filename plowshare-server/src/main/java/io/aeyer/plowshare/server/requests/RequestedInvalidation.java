package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * The two things a caller has to say when it retires a memory: why, and who.
 *
 * <p>Moved out of {@code MemoryController.invalidate}, where both were presence
 * checks on the request's own fields with no archive call needed to decide
 * either — this package's shape. The {@code memory.invalidate} frame binds the
 * same record and has to refuse the same bodies in the same words.
 *
 * <h2>Why blank and not merely null</h2>
 *
 * <p>{@code protocol.Invalidation}'s own javadoc: "a tombstone without a reason
 * is just an absence, and an absence teaches nobody". The same is true of
 * {@code by} with no account of who retired the claim — and an empty string
 * passes a null check while still being exactly that absence, which is what an
 * unset form field and a stringified missing value both arrive as.
 *
 * <p>Two methods rather than one taking both fields, because the two refusals
 * are two sentences and a caller that got one of them wrong should be told
 * which. The order is the endpoint's own: the reason first.
 */
public final class RequestedInvalidation {

    private RequestedInvalidation() {
    }

    /**
     * {@code reason}, or a {@link CallerFault} for a tombstone that explains
     * nothing. The sentence is the endpoint's own, word for word.
     *
     * @param reason the request's own {@code reason} field
     * @throws CallerFault if {@code reason} is null or blank
     */
    public static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new CallerFault("reason must not be blank");
        }
        return reason;
    }

    /**
     * {@code by}, or a {@link CallerFault} for a tombstone nobody signed. The
     * sentence is the endpoint's own, word for word.
     *
     * @param by the request's own {@code by} field
     * @throws CallerFault if {@code by} is null or blank
     */
    public static String by(String by) {
        if (by == null || by.isBlank()) {
            throw new CallerFault("by must not be blank");
        }
        return by;
    }
}
