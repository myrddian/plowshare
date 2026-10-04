package io.aeyer.plowshare.protocol.fetch;

/**
 * One window of a fetched page's text, as {@code FetchService.read} hands it back — a title, a
 * slice of the page's readable prose, where that slice sits, and where a caller re-issuing this
 * read should ask for next.
 *
 * <h2>Why this record lives in {@code plowshare-protocol} and not beside the service that builds it
 * </h2>
 *
 * <p>{@code plowshare-client} declares {@code api(project(":plowshare-protocol"))} and carries no
 * dependency on {@code plowshare-server} at all, so a record defined in {@code
 * io.aeyer.plowshare.server.fetch} would be a type the client-side {@code fetch} tool (built in a
 * later task of this slice) has no way to name. {@code SearchPage} made exactly this mistake in the
 * previous slice — defined server-side, then moved here once the client needed it — and this record
 * is placed correctly the first time on that precedent. {@code Navigation} and {@code StartedJob}
 * sit here for the identical reason: a shape both sides of the wire must agree on belongs where
 * both sides can reach it, not where it was first convenient to write.
 *
 * <h2>{@code refusal} is a field, not an exception</h2>
 *
 * <p>A dead host, a suppressed domain, a remote non-2xx status — none of these are a fault in this
 * server, and none of them are the caller's mistake either. They are the ordinary outcomes of
 * asking the open web for a page, and {@code SearchPage}'s own javadoc argues the identical point
 * for a search that comes back empty: a caller always gets something it can act on, rather than a
 * stack unwinding through a layer that has no opinion about whether the failure is worth retrying.
 * {@code refusal} is non-null exactly when a window did not come back, and every other field is
 * left at its empty default in that case — see {@code FetchService.read} for what a refusal's text
 * says for each of the three ways a read can fail to produce one.
 *
 * <h2>{@code nextOffset} is where the read actually reached, not where the caller asked for</h2>
 *
 * <p>{@code FetchService.read} prefers to end a window on a block boundary (a paragraph break) over
 * the operator-configured character count, so the offset a caller should re-issue is not always
 * {@code offset + window}. A caller that re-adds the window size itself, instead of reading {@link
 * #nextOffset}, would re-read a handful of characters twice or skip a handful at the seam every
 * time a boundary cut fired — silently, since nothing about a slightly-short window looks like an
 * error. Handing back the true endpoint removes that arithmetic from every caller rather than
 * asking each one to get it right.
 *
 * @param url the URL this window was read from, echoed back as the caller supplied it
 * @param title the page's title, or {@code null} when {@code refusal} is set
 * @param text this window's slice of the page's whole extracted prose, or {@code null} when {@code
 *     refusal} is set
 * @param offset where this window starts, counting characters into the page's stored text
 * @param nextOffset the offset a caller re-issues to read the next window — see the class comment
 *     for why this is not simply {@code offset} plus a configured window size
 * @param total the page's whole stored length in characters, regardless of how much of it this
 *     window carries — {@code 0} when {@code refusal} is set, since no page was ever sized
 * @param hasMore whether {@code nextOffset} is still short of {@code total}
 * @param refusal non-null exactly when this read did not produce a page: naming a suppressed
 *     domain, a dead host, or a remote failure, and never both a refusal and a page at once
 */
public record FetchWindow(
    String url,
    String title,
    String text,
    int offset,
    int nextOffset,
    int total,
    boolean hasMore,
    String refusal) {}
