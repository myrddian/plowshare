package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.FetchController;
import io.aeyer.plowshare.server.fetch.FetchService;
import java.util.Map;
import java.util.Objects;

/**
 * {@code web.fetch} — read a page from outside this deployment, a window at a
 * time. The frame equivalent of {@code POST /v1/fetch}.
 *
 * <h2>A domain miss is {@link Code#OK}, and that is a ruling</h2>
 *
 * <p><b>Do not "fix" this.</b> {@link SearchQueryHandler}'s argument, which
 * applies here word for word because the two endpoints are the same shape: a
 * suppressed domain, a dead host, or a stored row that aged out from under a
 * stale paging offset comes back as {@code FetchWindow.refusal} inside a 200,
 * because that text is prose a caller reads and acts on rather than a mistake
 * this route is refusing. A blank or unparseable {@code url}, or a negative
 * {@code offset}, is the other kind and is a {@code CallerFault} from {@link
 * FetchService#read} before the store or the fetcher is touched — the same 400
 * the endpoint answers, in the same words, because it is the same throw.
 *
 * <h2>A window of text, and never the bytes</h2>
 *
 * <p>What comes back is a paginated window over extracted text and not a
 * content-type passthrough, which is the endpoint's own contract and is worth
 * saying on a transport where a client might expect otherwise: this is the one
 * frame type whose answer contains content this server did not generate, and it
 * arrives as the same {@code FetchWindow} record either surface renders.
 */
public final class WebFetchHandler implements FrameHandler {

    private final FetchService service;

    /**
     * @param service the same service the controller is injected with, which
     *     carries the domain rules, the buffer and both kinds of refusal
     */
    public WebFetchHandler(FetchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        FetchController.FetchRequest asked = Payloads.as(
                payload, FetchController.FetchRequest.class, FrameTypes.WEB_FETCH);
        return Outcome.ok(service.read(asked.url(), asked.offset()));
    }
}
