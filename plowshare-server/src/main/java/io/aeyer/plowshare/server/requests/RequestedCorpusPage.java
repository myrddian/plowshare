package io.aeyer.plowshare.server.requests;

import io.aeyer.plowshare.server.documents.CitationStore;
import io.aeyer.plowshare.server.documents.RetrievalService;
import io.aeyer.plowshare.server.faults.CallerFault;

/**
 * How much of the corpus a read asked for — the {@code limit} five of {@code
 * DocumentController}'s endpoints take, and the {@code offset} one of them
 * takes beside it.
 *
 * <h2>Refused, never defaulted, and that is the decision being moved</h2>
 *
 * <p>A limit of zero is not "give me the default": a caller that sent zero has
 * said something, and answering it with ten hits would be this server deciding
 * what they meant, while answering it with none would be a claim about the
 * corpus that nothing checked. Every one of the five refuses it, each naming
 * its own noun and its own default, and {@link #skipped} refuses a negative
 * offset rather than clamping it for the same reason one level along — a
 * negative offset is a caller's arithmetic having gone wrong somewhere above
 * the call, and the first page would hide the fault behind a plausible answer.
 *
 * <h2>Not {@link RequestedWindow}, although it reads two of the same numbers</h2>
 *
 * <p>{@code RequestedWindow} is the archive's page: it caps at {@link
 * RequestedWindow#MOST_ENTRIES_A_PAGE}, and its two sentences talk about
 * entries in a conversation. These five talk about chunks, documents,
 * citations and hits, name four different defaults, and <b>apply no cap at
 * all</b> — the caps live in the stores that answer ({@code
 * RetrievalService.MAX_HITS}, {@link CitationStore#MOST_LISTED}, {@code
 * DocumentStore.MOST_LISTED}) and each answer reports the figure it really
 * used. Routing these through the archive's type would have changed six
 * messages and added a cap to five endpoints that never had one.
 *
 * <h2>What both surfaces get out of it</h2>
 *
 * <p>Six inline {@code BadRequestException}s on one controller until the
 * breadth plan's Task 2. {@code document.retrieve}, {@code document.list},
 * {@code document.rank}, {@code document.citations} and {@code
 * document.search} read the same fields, and a handler restating a bound would
 * be the copy that goes on saying fifty after the other one moved.
 */
public final class RequestedCorpusPage {

    /**
     * How many documents a listing answers with when the caller names no
     * number.
     *
     * <p>Anchor's {@code DocumentController.DEFAULT_LIMIT}, unchanged, and the
     * number {@code DocumentController.DEFAULT_LISTED} now reads off this
     * field: the other four defaults on this class belong to the services that
     * answer, and this one belonged to the controller alone, so it had to land
     * somewhere both surfaces can see. The controller keeps its constant as
     * the public spelling — {@code DocumentTools}' javadoc and {@code
     * CorpusReadControllerTest} both name it — and there is one number behind
     * the two.
     */
    public static final int DEFAULT_LISTED = 50;

    private RequestedCorpusPage() {
    }

    /**
     * {@code POST /v1/documents/retrieve}'s {@code limit}.
     *
     * @param limit the request's own {@code limit} field, or null for {@link
     *     RetrievalService#DEFAULT_RETRIEVED}
     * @throws CallerFault if it is under 1
     */
    public static int retrieved(Integer limit) {
        return atLeastOne(limit, RetrievalService.DEFAULT_RETRIEVED,
                "`limit` is " + limit + "; it must be at least 1. Leave it out for"
                        + " " + RetrievalService.DEFAULT_RETRIEVED + ". Asking for no chunks"
                        + " is not the same as finding none, and this endpoint will not"
                        + " report one as the other");
    }

    /**
     * {@code GET /v1/documents}' {@code limit}.
     *
     * @param limit the query string's own {@code limit}, or null for {@link
     *     #DEFAULT_LISTED}
     * @throws CallerFault if it is under 1
     */
    public static int listed(Integer limit) {
        return atLeastOne(limit, DEFAULT_LISTED,
                "`limit` is " + limit + "; it must be at least 1. Leave it out for "
                        + DEFAULT_LISTED + ". Asking for no documents is not the same as the"
                        + " corpus holding none, and this endpoint will not report one as the"
                        + " other");
    }

    /**
     * {@code POST /v1/documents/rank}'s {@code limit}.
     *
     * @param limit the request's own {@code limit} field, or null for {@link
     *     RetrievalService#DEFAULT_RANKED}
     * @throws CallerFault if it is under 1
     */
    public static int ranked(Integer limit) {
        return atLeastOne(limit, RetrievalService.DEFAULT_RANKED,
                "`limit` is " + limit + "; it must be at least 1. Leave it out for"
                        + " " + RetrievalService.DEFAULT_RANKED + ". Asking for no documents"
                        + " is not the same as finding none, and this endpoint will not"
                        + " report one as the other");
    }

    /**
     * {@code GET /v1/documents/citations}' {@code limit}.
     *
     * @param limit the query string's own {@code limit}, or null for {@link
     *     CitationStore#MOST_LISTED}
     * @throws CallerFault if it is under 1
     */
    public static int cited(Integer limit) {
        return atLeastOne(limit, CitationStore.MOST_LISTED,
                "`limit` is " + limit + "; it must be at least 1. Leave it out for "
                        + CitationStore.MOST_LISTED + ". Asking for no citations is not the"
                        + " same as there being none, and this endpoint will not report one"
                        + " as the other");
    }

    /**
     * {@code POST /v1/documents/search}'s {@code limit}.
     *
     * @param limit the request's own {@code limit} field, or null for {@link
     *     RetrievalService#DEFAULT_HITS}
     * @throws CallerFault if it is under 1
     */
    public static int searched(Integer limit) {
        return atLeastOne(limit, RetrievalService.DEFAULT_HITS,
                "`limit` is " + limit + "; it must be at least 1. Leave it out for"
                        + " " + RetrievalService.DEFAULT_HITS + ". Asking for no hits is not"
                        + " the same as finding none, and this endpoint will not report one"
                        + " as the other");
    }

    /**
     * {@code GET /v1/documents}' {@code offset}.
     *
     * @param offset the query string's own {@code offset}, or null for the
     *     beginning
     * @throws CallerFault if it is negative
     */
    public static int skipped(Integer offset) {
        if (offset != null && offset < 0) {
            throw new CallerFault(
                    "`offset` is " + offset + "; it must be at least 0. A negative offset is not"
                            + " a page of this corpus, and answering it with the first one would"
                            + " make a paging error look like a listing");
        }
        return offset == null ? 0 : offset;
    }

    /**
     * The rule the five limits share: an absent one is the endpoint's own
     * default, a positive one is passed through as it was sent, and anything
     * else is refused in the endpoint's own words.
     *
     * <p><b>Nothing is capped here.</b> Each store applies its own bound and
     * each answer reports the figure it used; see this class's javadoc.
     */
    private static int atLeastOne(Integer limit, int whenAbsent, String said) {
        if (limit == null) {
            return whenAbsent;
        }
        if (limit < 1) {
            throw new CallerFault(said);
        }
        return limit;
    }
}
