package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.digests.Digests;
import io.aeyer.plowshare.server.api.DigestController;
import io.aeyer.plowshare.server.api.StartedJob;
import java.util.Map;
import java.util.Objects;

/**
 * {@code memory.digest} — fold one tier's memories into digests. The frame
 * equivalent of {@code POST /v1/memories/digest}.
 *
 * <h2>{@link Code#ACCEPTED} and never {@link Code#OK}</h2>
 *
 * <p>A pass is many model calls in series, so what comes back is a handle to
 * poll at {@code job.status} exactly as {@code agent.curate}'s is, and it is
 * cancelled through {@code job.cancel}.
 *
 * <h2>Everything else is {@link Digests}', which is the point of this file
 * being four lines</h2>
 *
 * <p>The allowance a pass spends, how its ending is classified, and — the one
 * that would have been invisible — <b>the fact that the tier is resolved before
 * the closure is submitted</b>. A handler that resolved inside the submitted
 * work would answer {@code ACCEPTED} for a blank project and lose the refusal
 * on a worker thread, which no comparison of two answers could see because
 * there would be no second answer to compare. Calling the same service method
 * is what makes that impossible rather than unlikely.
 *
 * <p>The payload's {@code question} is bound and not passed on, exactly as the
 * endpoint binds and ignores it: a pass folds a whole tier rather than
 * answering anything.
 */
public final class MemoryDigestHandler implements FrameHandler {

    private final Digests digests;

    /**
     * @param digests the same service the controller is injected with
     */
    public MemoryDigestHandler(Digests digests) {
        this.digests = Objects.requireNonNull(digests, "digests");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        DigestController.Request asked =
                Payloads.as(payload, DigestController.Request.class, FrameTypes.MEMORY_DIGEST);
        Digests.Started started = digests.start(asked.project());
        return new Outcome(Code.ACCEPTED, null, new StartedJob(started.id(), started.agent()));
    }
}
