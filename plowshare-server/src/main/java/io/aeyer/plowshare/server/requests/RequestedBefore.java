package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.archive.EntryStore;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * Whether a reading of a log goes backwards, and from where, as both surfaces
 * read it.
 *
 * <p><b>Three fields and one answer.</b> {@code before} names an ordinal to read
 * below, {@code tail} names the log's end, and {@code after} names a forward
 * reading; a request that names two of them has asked for two readings, and
 * which one it meant is not this server's to guess. So the combinations are
 * refused here, once, for {@code conversation.trajectory} and {@code GET
 * /v1/conversations/{id}/trajectory} alike — {@link RequestedAfter}'s reason for
 * being a type of its own.
 */
public final class RequestedBefore {

    /** The answer for a request that reads forward. No ordinal is below 1, so no
     *  backwards reading is ever asked for with it. */
    public static final int FORWARD = 0;

    private RequestedBefore() {
    }

    /**
     * The ordinal a backwards reading starts below, or {@link #FORWARD}.
     *
     * @param before the request's own {@code before}, or null
     * @param tail the request's own {@code tail}, or null
     * @param after the request's own {@code after}, or null
     * @return {@link #FORWARD} when neither {@code before} nor {@code tail} was
     *     asked for; {@link EntryStore#FROM_THE_END} for {@code tail}; otherwise
     *     {@code before}
     * @throws CallerFault if {@code before} is below 1, or if the request named
     *     more than one of {@code before}, {@code tail} and {@code after}
     */
    public static int in(Integer before, Boolean tail, Integer after) {
        boolean fromTheEnd = Boolean.TRUE.equals(tail);
        if (before == null && !fromTheEnd) {
            return FORWARD;
        }
        if (after != null) {
            throw new CallerFault(
                    "'after' reads forward from an ordinal and '" + (before != null ? "before"
                            : "tail") + "' reads backwards, and one reading cannot do both."
                            + " Name one of them.");
        }
        if (before != null && fromTheEnd) {
            throw new CallerFault(
                    "'tail' reads back from the log's end and 'before' from an ordinal, and one"
                            + " reading cannot start at both. Name one of them.");
        }
        if (before == null) {
            return EntryStore.FROM_THE_END;
        }
        if (before < 1) {
            throw new CallerFault(
                    "'before' is the ordinal a reading goes back from, and ordinals count from"
                            + " 1, so it is 1 or later; this asked for " + before
                            + ". Use 'tail' to read back from the end.");
        }
        return before;
    }
}
