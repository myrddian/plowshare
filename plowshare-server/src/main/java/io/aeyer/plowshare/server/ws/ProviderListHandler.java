package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.search.ProviderStore;
import java.util.Map;
import java.util.Objects;

/**
 * {@code provider.list} — every registered search provider. The frame
 * equivalent of {@code GET /v1/search/providers}.
 *
 * <h2>The store and not the registrar, which is the endpoint's own choice</h2>
 *
 * <p>{@code SearchProviderController} reads this straight out of {@link
 * ProviderStore} and says why in its own javadoc: {@code SearchRegistrar}
 * carries only what it was asked to carry — register and deregister — and
 * giving it a third method that existed only to satisfy a listing would make it
 * a proxy for a store it already holds. This handler asks the same object the
 * same question, so the order the two surfaces answer in is one decision.
 *
 * <h2>A payload with nothing in it, deliberately</h2>
 *
 * <p>The endpoint takes no path value, no body and no query parameter, so there
 * is nothing for this type's payload to carry and no record to bind — {@code
 * job.list}'s shape. Unpaged and unfiltered, exactly as the endpoint is: a
 * provider ladder is a handful of rows an operator wrote.
 */
public final class ProviderListHandler implements FrameHandler {

    private final ProviderStore store;

    /**
     * @param store the same store the controller reads the listing from, which
     *     decides the order
     */
    public ProviderListHandler(ProviderStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        return Outcome.ok(store.all());
    }
}
