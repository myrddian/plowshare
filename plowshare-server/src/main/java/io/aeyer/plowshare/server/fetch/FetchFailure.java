package io.aeyer.plowshare.server.fetch;

/**
 * The four ways {@link PageFetcher#fetch} can fail to hand back a page.
 *
 * <p>Four cases and not one generic failure, because the layer above this
 * port treats them differently — {@link BuiltinFetcher}'s javadoc on {@link
 * FetchAnswer} is where that argument lives in full. In short: {@link
 * #UNSUPPORTED_CONTENT_TYPE} is a page this fetcher will never be able to
 * read no matter how many times it is asked, {@link #REMOTE_STATUS} is a
 * status this same URL might not carry tomorrow, {@link #REFUSED_ADDRESS} is
 * a place this server will not go on an agent's behalf, however it is spelled,
 * and {@link #FETCH_FAILED} is everything else that happened before any of
 * those questions could even be asked — a refused connection, an unparsable
 * URL, a timeout. Collapsing them into one enum constant would hand a caller
 * deciding whether to retry nothing to decide it with.
 */
public enum FetchFailure {

    /**
     * The dial itself did not produce a response to read a status or a
     * content type off — a malformed or non-HTTP URL, a refused connection,
     * a timeout, a DNS failure, or a redirect chain that never landed (more
     * than five hops, a redirect with no {@code Location}, a {@code Location}
     * that is not http(s), or a redirect from https down to http).
     */
    FETCH_FAILED,

    /**
     * The remote answered, but not with a successful status. The code rides
     * in {@link FetchAnswer#message()} rather than as a field of its own —
     * see that record's javadoc for why one message string is enough here.
     */
    REMOTE_STATUS,

    /**
     * The remote answered successfully with a {@code Content-Type} this
     * fetcher does not treat as HTML. The body is never read in this case —
     * see {@link BuiltinFetcher}'s javadoc for why that refusal happens
     * before the body is so much as requested.
     */
    UNSUPPORTED_CONTENT_TYPE,

    /**
     * A hop was about to connect to an address off the public web. That is a
     * private-tier address no {@code plowshare.fetch.allow-private} entry
     * exempts on that port, or a never-tier address nothing exempts. The
     * message names the host and the tier ({@code "refused: intranet.example is
     * a private address"}) and never the address it resolved to, which would
     * hand internal DNS back to the model.
     */
    REFUSED_ADDRESS
}
