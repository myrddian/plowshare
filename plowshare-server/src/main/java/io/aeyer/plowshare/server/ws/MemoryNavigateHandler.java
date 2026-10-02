package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.digests.Digests;
import io.aeyer.plowshare.server.api.DigestController;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.navigate} — ask one tier's digests a question and walk them for
 * an answer. The frame equivalent of {@code POST /v1/memories/navigate}.
 *
 * <h2>A walk that found nothing is {@link Code#OK}, and that is a ruling</h2>
 *
 * <p><b>Do not "fix" this.</b> The endpoint answers 200 with the refusal inside
 * the body — {@code Navigator.Result} carries {@code complete}, the ids it
 * reached, and prose saying how far it got — and clients read it there. That is
 * this endpoint's contract rather than an accident of implementation: a walk
 * that ran out of allowance, or a tier with no digest covering the question, is
 * a finding a caller acts on (ask differently, build digests first), not a
 * request this server is refusing. A frame that promoted the embedded refusal
 * to a failure {@link Code} would be a second contract for one read, and every
 * client that had learned the first would break the day somebody changed it
 * back.
 *
 * <p>What <em>is</em> a refusal here: no question, and a blank project. Both
 * are {@link Digests}', in the endpoint's own words and the endpoint's own
 * order.
 *
 * <h2>{@code DigestController}'s endpoint, despite the path</h2>
 *
 * <p>It is served under {@code /v1/memories/} and is not {@code
 * MemoryController}'s — a path prefix is not a controller. {@link MemoryFrames}
 * says so from the other side, and {@link DigestFrames} is what claims this.
 */
public final class MemoryNavigateHandler implements FrameHandler {

    private final Digests digests;

    /**
     * @param digests the same service the controller is injected with, which
     *     owns the allowance a navigation spends and the order its two
     *     refusals happen in
     */
    public MemoryNavigateHandler(Digests digests) {
        this.digests = Objects.requireNonNull(digests, "digests");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        DigestController.Request asked =
                Payloads.as(payload, DigestController.Request.class, FrameTypes.MEMORY_NAVIGATE);
        return Outcome.ok(digests.navigate(asked.project(), asked.question()));
    }
}
