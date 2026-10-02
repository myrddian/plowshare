package io.aeyer.plowshare.server.archive;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One page of the tool results a conversation still holds behind a seam, and
 * how many there are in all.
 *
 * <h2>What it is for</h2>
 *
 * <p>A later turn is shown an earlier turn's tool result as a <em>reference</em>
 * — {@code Compaction.REFERENCE}, a line naming the tool, the size and a handle
 * — and {@code result_read} redeems the handle. A fold supersedes that line
 * along with the turn that carried it. The row stays redeemable, because {@link
 * EntryStore#redeem} deliberately does not filter {@code superseded_by}; what it
 * stops being is <b>addressable</b>, since after the fold nothing in the prompt
 * tells a model the handle. This is what {@code result_list} is answered from,
 * and it carries exactly what a reference line carries, for the results whose
 * lines are gone.
 *
 * <h2>Why the total is here and not counted from the page</h2>
 *
 * <p>The page is bounded and the sentence the model reads names both numbers —
 * {@code file_read}'s continuation note is the shape. {@code listed.size()}
 * cannot supply the second: a page that came back short is either the end of the
 * list or a caller that paged past it, and those are the two answers a model
 * corrects differently. {@code file_read} splits the same pair with {@code
 * Span.totalLines}, for the same reason.
 *
 * @param listed the page, most recent first. Never null; empty for a
 *     conversation nothing has folded and for a page past the end of the list
 * @param total how many stored results this conversation holds behind a seam,
 *     which is a number about the conversation and not about the page
 */
public record StoredResults(List<Result> listed, int total) {

    /** A conversation with nothing behind a seam, and the answer for a run in no
     *  conversation at all. {@code Transcript.NONE} returns it, and so does a
     *  read that could not reach the database — the model is told there is
     *  nothing stored rather than being given an exception. */
    public static final StoredResults NONE = new StoredResults(List.of(), 0);

    public StoredResults {
        listed = listed == null ? List.of() : List.copyOf(listed);
        if (total < listed.size()) {
            throw new IllegalArgumentException(
                    "a page of " + listed.size() + " stored results cannot come out of a"
                            + " conversation said to hold " + total);
        }
    }

    /**
     * One stored result, as much of it as a model needs to choose without
     * redeeming it.
     *
     * <p>The three facts {@code Compaction.REFERENCE} carries and no more, and
     * the sameness is the point: a model that has read a reference line already
     * knows how to read this. Which tool ran separates "the file I read" from
     * "the search I ran"; the size is what a turn is weighed against; the handle
     * is what {@code result_read} takes.
     *
     * <p><b>The arguments are not here</b>, for the reason the reference line
     * does not copy them either: {@code file_edit} sends a whole file as an
     * argument, so an argument-carrying listing would be unbounded in exactly
     * the dimension the listing exists to bound. Unlike a reference, this cannot
     * even point at them — the call that asked is behind the same seam — and
     * that is a real limit of this answer rather than an oversight.
     *
     * @param tool the tool that ran, as the model named it in the call this
     *     answers, or {@code null} when nothing in the log declares that call.
     *     <b>Model-supplied text</b>: a call to a tool that does not exist is
     *     still answered and still recorded, so this is not a name from any
     *     registry and is neither bounded in length nor free of line breaks. The
     *     tool that renders it flattens and shortens it
     * @param size how many characters the stored result is, which is what {@code
     *     Compaction.REFERENCE} reports for a result whose line is still in the
     *     prompt. <b>Kept across an ejection</b>: {@code entries.ejected_chars}
     *     holds it once the text is gone, so the number a model reads does not
     *     change when the payload does
     * @param ejectedAt when this payload was ejected, or {@code null} while it
     *     is still here.
     *
     *     <p><b>An ejected result is listed AS ejected rather than left out</b>,
     *     and that is a decision the retention design makes explicitly: a model
     *     that cannot tell <em>was never here</em> from <em>was here and went</em>
     *     will re-run the tool on the first reading and give up on the second,
     *     and only one of those is right. A listing that dropped the row would
     *     also make the total disagree with the trajectory, which still holds
     *     every one of these calls
     * @param handle the address {@code result_read} redeems. Never null: a row
     *     without one is not listed, because a line that cannot be redeemed is a
     *     line that costs context and returns nothing
     */
    public record Result(String tool, int size, Instant ejectedAt, UUID handle) {

        public Result {
            Objects.requireNonNull(handle, "handle");
        }
    }
}
