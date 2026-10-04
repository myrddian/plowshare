package io.aeyer.plowshare.server.fetch;

/**
 * The one thing the fetch facade does to a URL: hand it over, and get a page back — never an
 * exception.
 *
 * <p>This is the seam spec §2 promises when it describes "a facade with a built-in fetcher behind
 * it." Without this interface that sentence would be fiction: a facade calling {@link
 * BuiltinFetcher} directly is one class calling another, with nothing standing between them for
 * anything else to implement. With it, {@code FetchService} (not built in this task) depends on
 * {@link PageFetcher} rather than on {@link BuiltinFetcher} by name, so a remote fetch provider
 * speaking the contract's reserved {@code FETCH} verb — a process on the far side of a socket, the
 * same shape {@code RemoteSearchProvider} already is for search — can be registered later without
 * the facade's source changing at all. Nothing in this slice implements this interface twice:
 * {@link BuiltinFetcher} is the only implementation today, and this interface exists anyway,
 * because the seam is the thing the spec asks for, not the second implementation. The same shape
 * already paid for itself once in this codebase — {@link
 * io.aeyer.plowshare.server.search.SearchProvider} is one implementation for most of this
 * repository's life, with {@code RemoteSearchProvider} standing behind it from day one specifically
 * so a ladder of many providers was never a redesign away.
 *
 * <p><b>{@link #fetch} must never throw.</b> Every implementation reduces every failure — a scheme
 * this fetcher will not dial, a refused connection, a timeout, a non-2xx status, a content type it
 * will not parse — to {@link FetchAnswer#failed}. That rule belongs on the interface rather than
 * merely on {@link BuiltinFetcher}'s one implementation, for the reason {@link
 * io.aeyer.plowshare.server.search.SearchProvider} states for {@code search}: the facade above this
 * port is the thing that decides what a failure <em>means</em> — reported back to the agent
 * verbatim, retried, or handed to the next fetcher in line — and it can only do that by inspecting
 * a returned {@link FetchAnswer}. An implementation that threw instead would make that decision by
 * accident, as an uncaught exception racing whatever the facade's own catch clause happens to be.
 */
public interface PageFetcher {

  FetchAnswer fetch(String url);
}
