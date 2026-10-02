package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.requests.RequestedHome;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.reembed} — give a vector to every live memory in one tier that
 * has none. The frame equivalent of {@code POST /v1/memories/reembed}.
 *
 * <h2>Operator-facing, and a frame anyway</h2>
 *
 * <p>This is the repair path for what {@code unsearchable} makes visible on the
 * two reads beside it: a memory written while the embedding endpoint was down
 * is complete except for its vector, and nothing else is asking for one. The
 * endpoint's own javadoc argues that it is maintenance somebody does after
 * fixing an endpoint rather than a judgement an agent should make mid-run,
 * which is why it has no MCP tool — but that is an argument about who should be
 * offered it, not about which transport carries it. The three endpoints the
 * breadth plan rules <em>operational</em> are the config pair and the search
 * provider registration; this is not one of them, and a socket-only console
 * that could see {@code unsearchable} with no way to act on it would be able to
 * name the fault and not fix it.
 *
 * <p><b>Slow, and synchronous on both surfaces.</b> One model call per
 * unembedded memory, with no job to poll — the endpoint's shape, matched here
 * rather than improved on. A frame that submitted this as a job would answer
 * {@code ACCEPTED} where the endpoint answers {@code OK} with the repair, which
 * is a different promise.
 */
public final class MemoryReembedHandler implements FrameHandler {

    private final Archive archive;

    /**
     * @param archive the one archive both surfaces repair a tier through
     */
    public MemoryReembedHandler(Archive archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Tiered asked = Payloads.as(payload, Tiered.class, FrameTypes.MEMORY_REEMBED);
        return Outcome.ok(archive.accountingEnabled() ? archive.reembed(RequestedHome.in(asked.project()),archive.usage(RequestedHome.in(asked.project()),asking.handle(),UsageAttribution.Operation.EMBEDDING_REPAIR)) : archive.reembed(RequestedHome.in(asked.project())));
    }
}
