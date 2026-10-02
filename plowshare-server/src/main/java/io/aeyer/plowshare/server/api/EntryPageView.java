package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.archive.EntryPage;
import java.util.List;

/**
 * One page of a conversation's entries, and everything a caller needs to ask for
 * the next one.
 *
 * <h2>Why an envelope and not a bare list</h2>
 *
 * <p>Every other read on this controller answers with a list, and each of them
 * is bounded by something real — a conversation has as many seams as it was
 * folded, and as many turns as somebody spoke. <b>A log is bounded by nothing a
 * person did.</b> One turn writes an entry per model call and one per tool
 * result, so a conversation of a hundred turns holds thousands, and the client
 * this surface exists for is a foreign harness with a context window. A listing
 * that answered with all of them is a listing that empties one.
 *
 * <p>So the page is capped, and a caller cannot tell a page that ended from a
 * page that was cut without being told both numbers — which is {@code
 * StoredResults}' argument one layer down and {@code file_read}'s two layers
 * further out. All three fields are needed and none is derivable: {@code total}
 * is the conversation's, {@code offset} is where this page started, and {@code
 * limit} is what this server actually used, <b>which is not necessarily what was
 * asked for</b>.
 *
 * @param entries the page. In conversation order for a forward reading; <b>newest
 *     first by ordinal for a backwards one</b> ({@code before} or {@code tail}),
 *     which the caller turns round to draw. Never null; empty for a conversation
 *     nothing has been said in and for a page past the end, which are two facts
 *     {@code total} tells apart
 * @param total how many entries this reading holds. <b>About the conversation
 *     and not about the page</b>, and different between the two readings of the
 *     same conversation: a chat's total counts what a model is shown and a
 *     trajectory's counts everything, so the gap between them is what folding
 *     and the roleless kinds have taken out
 * @param offset how many entries this page skipped, as asked for
 * @param limit how many entries this page could hold — <b>the number this server
 *     used and not the one the caller sent</b>, which are different exactly when
 *     the caller asked for more than {@code ConversationController.MOST_ENTRIES_A_PAGE}.
 *     A caller pages by adding this to {@code offset}, so echoing the request
 *     rather than the cap would send it stepping over entries it never saw
 * @param through the conversation's highest entry ordinal now, whatever this
 *     page holds — what a caller that has shown this page counts as shown, and
 *     what it names as {@code after} to read only what comes next
 * @param oldest the smallest ordinal on this page, or null when it is empty —
 *     what a caller reading backwards names as {@code before} to go further back
 * @param more whether this reading holds an entry older than {@code oldest}, so
 *     whether a client has anything earlier to offer. Null on a forward reading,
 *     which was not asked what lies before it
 */
public record EntryPageView(List<EntryView> entries, int total, int offset, int limit,
        int through, Integer oldest, Boolean more) {

    public static EntryPageView of(EntryPage page, int offset, int limit) {
        return new EntryPageView(
                page.listed().stream().map(EntryView::of).toList(),
                page.total(),
                offset,
                limit,
                page.through(),
                page.oldest(),
                page.more());
    }
}
