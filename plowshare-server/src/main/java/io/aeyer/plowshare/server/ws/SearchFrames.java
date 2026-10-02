package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.search.SearchService;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code SearchController}'s area of the frame surface — its one endpoint,
 * under the {@code web.} noun it shares with {@link FetchFrames}.
 *
 * <h2>One area per controller, even at one endpoint each</h2>
 *
 * <p>This area and {@link FetchFrames} claim two types in one namespace and are
 * still two files, which is {@link FrameArea}'s own rule kept rather than an
 * oversight: an area is the unit that keeps a later change local, and the two
 * endpoints are answered by two controllers over two services with two stores
 * behind them. Sharing a noun is a fact about what a client is asking for —
 * content from outside this deployment — and not about who answers.
 *
 * <p><b>Nothing here is runtime state.</b> {@link SearchService} holds the
 * provider ladder, a result-set store and a clock; no session table.
 */
@Component
public class SearchFrames implements FrameArea {

    private final SearchService service;

    /**
     * @param service the same service the controller is injected with
     */
    public SearchFrames(SearchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.of(FrameTypes.WEB_SEARCH, new SearchQueryHandler(service));
    }
}
