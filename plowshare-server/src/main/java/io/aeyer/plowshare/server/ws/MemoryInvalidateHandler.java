package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.api.InvalidateRequest;
import io.aeyer.plowshare.server.archive.Archive;
import io.aeyer.plowshare.server.requests.RequestedInvalidation;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.invalidate} — record that a memory stopped being true, keeping
 * the memory. The frame equivalent of {@code POST
 * /v1/memories/&#123;id&#125;/invalidate}.
 *
 * <h2>Not a deletion, which is why it answers with a body</h2>
 *
 * <p>The memory is kept and only its state changes: an invalidated record still
 * reads, and carries the account of why it stopped holding, precisely so the
 * next agent that rediscovers the stale fact from old code finds that account
 * instead of writing the fact back in. So this answers {@code OK} with the
 * tombstone and not {@link
 * io.aeyer.plowshare.protocol.frames.Code#NO_CONTENT} — the endpoint's own
 * answer, and the difference between "we kept this, here it is" and "it is
 * gone".
 *
 * <p><b>Both fields are required, in the endpoint's own order</b>, through
 * {@link RequestedInvalidation}: a reason, and who is retiring the claim. A
 * tombstone without either is an absence, and an absence teaches nobody.
 *
 * <p>The memory is named in the payload as {@code memory} rather than {@code
 * id}, per {@link Payloads}' convention, and the two request fields bind the
 * same {@link InvalidateRequest} the endpoint binds.
 */
public final class MemoryInvalidateHandler implements FrameHandler {

    private final Archive archive;

    /**
     * @param archive the one archive both surfaces retire a memory through
     */
    public MemoryInvalidateHandler(Archive archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String memory = Payloads.required(payload, "memory", FrameTypes.MEMORY_INVALIDATE,
                "the id memory.index answers with. Nothing was retired.");
        InvalidateRequest request =
                Payloads.as(payload, InvalidateRequest.class, FrameTypes.MEMORY_INVALIDATE);
        return Outcome.ok(archive.invalidate(memory,
                RequestedInvalidation.reason(request.reason()),
                RequestedInvalidation.by(request.by())));
    }
}
