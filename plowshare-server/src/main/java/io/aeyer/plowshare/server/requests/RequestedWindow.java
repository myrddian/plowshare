package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * How much of a page a caller asked for, refused when it is not a page and
 * narrowed when it is more than this server answers with.
 *
 * <p><b>Refused and narrowed are two different responses to two different
 * mistakes</b>, and it is worth saying which is which. A limit of zero or a
 * negative offset is a request that cannot mean anything — there is no page of
 * no entries — and the caller corrects it from the message, so it is a 400. A
 * limit of a hundred thousand means something perfectly clear and is more than
 * this server will send; refusing it would make every client hunt for the cap
 * by binary search, so it is narrowed and the answer's own {@code limit} field
 * reports the number used. {@code file_read} takes the same pair of positions
 * on the same two arguments.
 *
 * <p>Absent is not zero for {@code limit} and is zero for {@code offset},
 * which is what a caller that said nothing means by each: start at the
 * beginning, and send as much as you send.
 *
 * <h2>Why this is in {@code requests} and not a private record on a
 * controller</h2>
 *
 * <p>It was that — {@code ConversationController.Window} — and it held two of
 * this server's remaining inline {@code BadRequestException} throws while three
 * endpoints on one controller shared it. Neither refusal is a fact about a
 * conversation, a store or a page: both are facts about two numbers a caller
 * sent, which is the whole of what this package is for, and the {@code
 * conversation.chat}, {@code conversation.trajectory} and {@code
 * conversation.search} frames need exactly the same two numbers read exactly
 * the same way. A frame handler that restated either bound would be a second
 * copy of a cap to keep in step with the first.
 *
 * @param skip how many entries to pass over, 0 or later
 * @param most how many this page may hold, 1 to {@link #MOST_ENTRIES_A_PAGE}
 */
public record RequestedWindow(int skip, int most) {

    /**
     * The most entries one page of a chat, a trajectory or a log search can
     * hold, whatever is asked for.
     *
     * <p><b>The cap is the server's because the risk is.</b> One turn writes an
     * entry per model call and one per tool result, so a hundred-turn
     * conversation holds thousands, and one of the callers this surface was
     * built for is a foreign harness with a context window — {@code
     * ResultTools.Listing} caps its own answer at 20 for the same reason and
     * says so. A caller asking for more is narrowed rather than refused, and
     * the answer's own {@code limit} is what says the narrowing happened.
     *
     * <p>100 rather than 20 because these readings are not tool answers: the
     * console renders a scrollback out of them, and the MCP tools that spend
     * them apply their own tighter bound on top. The text is capped separately
     * and in the database, at {@code EntryStore.MOST_CHARACTERS_PER_ENTRY}, so
     * this number bounds rows and not bytes.
     */
    public static final int MOST_ENTRIES_A_PAGE = 100;

    /**
     * The window {@code offset} and {@code limit} name, or a {@link
     * CallerFault} for a pair that names no page at all.
     *
     * @param offset the request's own {@code offset} field, or null
     * @param limit the request's own {@code limit} field, or null
     * @throws CallerFault if {@code offset} is negative or {@code limit} is
     *     under 1
     */
    public static RequestedWindow in(Integer offset, Integer limit) {
        int skip = offset == null ? 0 : offset;
        if (skip < 0) {
            throw new CallerFault(
                    "'offset' is how many entries to pass over and counts from 0, so it is 0"
                            + " or later; this asked for " + skip + ". Leave it out to start"
                            + " at the beginning.");
        }
        int asked = limit == null ? MOST_ENTRIES_A_PAGE : limit;
        if (asked < 1) {
            throw new CallerFault(
                    "'limit' is how many entries this page may hold, so it is 1 or more;"
                            + " this asked for " + asked + ". Leave it out for "
                            + MOST_ENTRIES_A_PAGE + ", which is also the most this server"
                            + " sends.");
        }
        return new RequestedWindow(skip, Math.min(asked, MOST_ENTRIES_A_PAGE));
    }
}
