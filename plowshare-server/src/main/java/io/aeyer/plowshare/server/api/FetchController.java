package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.protocol.fetch.FetchWindow;
import io.aeyer.plowshare.server.agents.FetchTool;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.fetch.FetchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /v1/fetch} — the door {@link FetchTool} already opens for this
 * server's own agents, opened again here for a foreign harness over MCP and a
 * person at the terminal.
 *
 * <h2>{@code SearchController}'s shape, verbatim</h2>
 *
 * <p>The division of labour is identical: {@link FetchService} carries the
 * whole behaviour and this class is the HTTP shape wrapped around one call to
 * it. A domain-level miss — a suppressed domain, a dead host, a store row that
 * aged out from under a stale paging offset — is {@link FetchWindow#refusal()}
 * carried in a 200, on the same reasoning {@link SearchController}'s own
 * javadoc gives for {@code SearchPage#refusal}: that text is prose a caller
 * reads and acts on, not a mistake this route is refusing. A request that is
 * malformed on its face — a blank or unparseable {@code url}, a negative
 * {@code offset} — is a caller mistake, and {@link FetchService#read} throws
 * {@link CallerFault} for it before touching the store or the
 * fetcher; {@code ApiExceptionHandler} maps that to 400, the terms every
 * sibling controller in this package already uses. So this method returns
 * {@link FetchWindow} directly rather than wrapping it in a status this route
 * never itself varies.
 *
 * @see FetchTool the in-process door this class is not: an agent of this
 *     server reaches {@link FetchService} directly and never crosses HTTP to
 *     reach itself
 */
@RestController
public class FetchController {

    private final FetchService service;

    public FetchController(FetchService service) {
        this.service = service;
    }

    /** What {@code POST /v1/fetch} takes. */
    public record FetchRequest(String url, int offset) {}

    @PostMapping("/v1/fetch")
    public FetchWindow fetch(@RequestBody FetchRequest request) {
        return service.read(request.url(), request.offset());
    }
}
