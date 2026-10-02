package io.aeyer.plowshare.server.search;

import io.aeyer.plowshare.protocol.search.SearchAnswer;
import io.aeyer.plowshare.protocol.search.SearchAsk;

/**
 * The one thing dispatch does to a provider: hand it a question against one
 * registered row, and get an answer back — never an exception.
 *
 * <p>{@code at} carries the row rather than a bare {@code providerKey} because
 * the row is what a call needs and a key is only how you would look it back
 * up: {@code baseUrl} to dial, {@code facts} to decide what may be sent (see
 * {@link RemoteSearchProvider} on {@code domainExclusion}), and the health
 * fields the ladder above this port reads to decide whether to call at all.
 * An implementation that took a key and re-fetched the row itself would make
 * every call a second trip through {@link ProviderStore} for data its caller
 * already had in hand.
 *
 * <p><b>{@link #search} must never throw.</b> Every implementation reduces
 * every failure — refused connection, timeout, a bad status, a body that will
 * not parse — to {@link SearchAnswer#failed}. That rule belongs on the
 * interface and not merely on {@link RemoteSearchProvider}'s one
 * implementation, because the ladder above this port is the thing that
 * decides what a failure *means* — retried, demoted, surfaced — and it can
 * only do that by inspecting a returned {@link SearchAnswer}. An
 * implementation that threw instead would make that decision by accident,
 * as an uncaught exception racing whatever the ladder's own catch clause
 * happens to be, and the ladder would never see whichever rungs come after
 * the one that threw.
 */
public interface SearchProvider {

    SearchAnswer search(Registration at, SearchAsk ask);
}
