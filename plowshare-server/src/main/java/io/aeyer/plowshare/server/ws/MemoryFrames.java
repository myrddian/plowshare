package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.agents.scribe.Scribe;
import io.aeyer.plowshare.server.archive.Archive;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code MemoryController}'s area of the frame surface — every {@code memory.*}
 * type this server answers, which is all six of that controller's endpoints.
 *
 * <h2>One constructor, and it is the controller's own</h2>
 *
 * <p>The two services below are the same beans {@code MemoryController} is
 * injected with, in the same order, which is what makes a frame's refusal the
 * endpoint's own refusal rather than a second one shaped like it. A surface
 * that built its own copy would give this server two instances of each — the
 * scanned bean a frame handler injects and a private one the controller called
 * — and the day either gains a cache or a proxy the two surfaces would answer
 * from different objects with nothing failing.
 *
 * <p><b>Nothing here is runtime state, and that was checked rather than
 * assumed.</b> The task before this one found that {@code ProjectController}
 * takes a {@code PresenceRegistry} — a live session table, not a service — so a
 * handler built over the store alone would have moved a project out from under
 * an open session with nothing failing, and no parity test over a mocked store
 * could have seen it. This controller's constructor takes an {@link Archive}
 * and a {@link Scribe}; both are services with a database and a model endpoint
 * behind them, neither holds a session, and the write path's one other
 * collaborator is {@code archive.Validation}, which is static and stateless. So
 * injecting these two is the whole of what parity needs here.
 *
 * <h2>Six verbs, and the two that share the URL prefix are not among them</h2>
 *
 * <p>{@code POST /v1/memories/navigate} and {@code POST /v1/memories/digest}
 * live under this controller's path and belong to {@code DigestController},
 * which the breadth plan batches with the small controllers. The plan's ruling
 * about an endpoint that embeds its refusal in a 200 body names {@code
 * navigate}: whoever builds its frame answers the same {@code OK} with the same
 * body rather than promoting the embedded refusal to a frame code. It is worth
 * saying here because the noun invites the mistake — none of the six types
 * below has that shape, and a later task adding {@code memory.navigate} to this
 * file would be putting another controller's endpoint in this area.
 *
 * <p>One of the {@link FrameArea} classes that interface's javadoc describes. A
 * later breadth task adds <b>its own</b> {@code *Frames.java} rather than
 * editing this one.
 */
@Component
public class MemoryFrames implements FrameArea {

    private final Archive archive;
    private final Scribe scribe;

    /**
     * @param archive the one archive every handler below reads and writes
     *     through — the same bean {@code MemoryController} is handed
     * @param scribe the judge a write's shape is decided by, which only {@link
     *     MemoryWriteHandler} needs and which is the controller's second
     *     constructor argument for the same reason
     */
    public MemoryFrames(Archive archive, Scribe scribe) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.scribe = Objects.requireNonNull(scribe, "scribe");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.ofEntries(
                Map.entry(FrameTypes.MEMORY_WRITE, new MemoryWriteHandler(archive, scribe)),
                Map.entry(FrameTypes.MEMORY_RECALL, new MemoryRecallHandler(archive)),
                Map.entry(FrameTypes.MEMORY_REEMBED, new MemoryReembedHandler(archive)),
                Map.entry(FrameTypes.MEMORY_READ, new MemoryReadHandler(archive)),
                Map.entry(FrameTypes.MEMORY_INDEX, new MemoryIndexHandler(archive)),
                Map.entry(FrameTypes.MEMORY_INVALIDATE, new MemoryInvalidateHandler(archive)));
    }
}
