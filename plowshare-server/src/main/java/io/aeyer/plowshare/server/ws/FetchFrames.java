package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.fetch.FetchService;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code FetchController}'s area of the frame surface — its one endpoint, under
 * the {@code web.} noun it shares with {@link SearchFrames}.
 *
 * <h2>Two areas for one noun, which is {@link FrameArea}'s rule and not a slip</h2>
 *
 * <p>{@link SearchFrames} carries the argument: the noun says what a client is
 * asking for — content from outside this deployment — and the area says who
 * answers. Two controllers over two services get two areas however their types
 * are spelt, because an area is the unit that keeps a later change local.
 *
 * <p><b>Nothing here is runtime state.</b> {@link FetchService} holds a page
 * fetcher, a buffer store, properties and a clock; no session table.
 */
@Component
public class FetchFrames implements FrameArea {

    private final FetchService service;

    /**
     * @param service the same service the controller is injected with
     */
    public FetchFrames(FetchService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.of(FrameTypes.WEB_FETCH, new WebFetchHandler(service));
    }
}
