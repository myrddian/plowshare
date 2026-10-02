package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.search.SearchRegistrar;
import java.util.Map;
import java.util.Objects;

/**
 * {@code provider.deregister} — take one provider off the ladder. The frame
 * equivalent of {@code DELETE /v1/search/providers/&#123;key&#125;}.
 *
 * <h2>{@link Code#NO_CONTENT} and not {@link Code#OK}</h2>
 *
 * <p>The row is gone, so there is nothing to describe; {@code Outcome.ok()} is
 * the easy mistake, because it is also an answer carrying no payload and would
 * differ from the endpoint in the one field a body comparison cannot see.
 *
 * <h2>The 404 is the registrar's, and that is why this file is short</h2>
 *
 * <p>{@code ProviderStore.remove} answering {@code false} is not an error down
 * there — a delete of something absent is a no-op to a store — and turning it
 * into a refusal used to be the controller's own line. It is {@link
 * SearchRegistrar#deregister}'s now, so both surfaces say the same sentence
 * because it is one sentence, rather than because two authors copied it.
 *
 * <p><b>The key is a payload field named {@code provider}</b>, per {@link
 * Payloads}' convention: the noun of the thing, never {@code id}, which at the
 * envelope level already means the client's correlation.
 */
public final class ProviderDeregisterHandler implements FrameHandler {

    private final SearchRegistrar registrar;

    /**
     * @param registrar the same registrar the controller is injected with,
     *     which is what decides that an unknown key is a refusal
     */
    public ProviderDeregisterHandler(SearchRegistrar registrar) {
        this.registrar = Objects.requireNonNull(registrar, "registrar");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String provider = Payloads.required(payload, "provider",
                FrameTypes.PROVIDER_DEREGISTER,
                "the providerKey provider.list answers with. Nothing was removed.");
        registrar.deregister(provider);
        return new Outcome(Code.NO_CONTENT, null, null);
    }
}
