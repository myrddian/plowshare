package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.digests.Digests;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code DigestController}'s area of the frame surface — both of that
 * controller's endpoints, under the {@code memory.} noun their capabilities
 * already carry everywhere else a caller meets them.
 *
 * <h2>Two {@code memory.*} types in an area that is not {@link MemoryFrames}</h2>
 *
 * <p>Deliberate, and the one thing about this file worth reading twice. {@code
 * POST /v1/memories/navigate} and {@code POST /v1/memories/digest} are served
 * under {@code MemoryController}'s path prefix and are answered by {@code
 * DigestController} — <b>a path prefix is not a controller</b>, and the breadth
 * plan filed these two under the wrong task once before being corrected. The
 * noun follows the capability a client already knows ({@code memory_navigate},
 * {@code plowshare memory navigate}) rather than the class; the <em>area</em>
 * follows the controller, because an area is what keeps a later change local to
 * the endpoints it affects. {@link MemoryFrames} carries the matching note and
 * a test refusing to claim these, and {@code DigestFramesTest} asserts this map
 * holds exactly these two.
 *
 * <h2>One service, and it is the controller's own</h2>
 *
 * <p>{@link Digests} is the same bean {@code DigestController} is injected
 * with, which matters more here than in most areas: that class holds two
 * budgets nobody sends, the classification of how a pass ended, and the
 * ordering that makes a blank project a refusal rather than a job that fails on
 * a worker thread. None of those appears in a request or an answer, so a
 * surface that rebuilt them would disagree in silence.
 *
 * <p><b>Nothing here is runtime state.</b> {@link Digests} holds a navigator, a
 * digester, a properties bean and the job store; the job store is a table of
 * running work and not a session registry, and both surfaces submit into the
 * same one — which is the whole point rather than a hazard.
 */
@Component
public class DigestFrames implements FrameArea {

    private final Digests digests;

    /**
     * @param digests the one service both handlers below go through — the same
     *     bean {@code DigestController} is handed
     */
    public DigestFrames(Digests digests) {
        this.digests = Objects.requireNonNull(digests, "digests");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.of(
                FrameTypes.MEMORY_NAVIGATE, new MemoryNavigateHandler(digests),
                FrameTypes.MEMORY_DIGEST, new MemoryDigestHandler(digests));
    }
}
