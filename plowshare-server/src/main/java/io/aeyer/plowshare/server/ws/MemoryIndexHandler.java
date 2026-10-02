package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.index} — one tier's index: its active memories as summary
 * lines, with no bodies. The frame equivalent of {@code GET
 * /v1/memories/index}.
 *
 * <h2>The read a socket-only client cannot do without</h2>
 *
 * <p>{@link FrameTypes#MEMORY_READ} takes an id and does not say where one
 * comes from, and {@link FrameTypes#MEMORY_RECALL} answers the memories a
 * question happens to reach — a different set from the ones that exist, and
 * silently a smaller one when some of them have no vector. This is the only
 * type that answers "what is in here", which is what a person surveying an
 * archive is asking and what the {@code unsearchable} flag on each entry is
 * for.
 *
 * <p>Unpaged, exactly as the endpoint is. A frame that invented a page would be
 * a second contract for one read.
 */
public final class MemoryIndexHandler implements FrameHandler {

    private final Archive archive;

    /**
     * @param archive the one archive both surfaces survey a tier through
     */
    public MemoryIndexHandler(Archive archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Tiered asked = Payloads.as(payload, Tiered.class, FrameTypes.MEMORY_INDEX);
        return Outcome.ok(archive.index(RequestedHome.in(asked.project())));
    }
}
