package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.server.archive.Retention;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code RetentionController}'s area of the frame surface — its one endpoint.
 *
 * <h2>An area of its own for one type</h2>
 *
 * <p>{@link FrameArea}'s rule, and this is where it costs the most and is still
 * right: four of this batch's controllers answer one endpoint each, and folding
 * them into a shared "the small ones" area would make every later change to any
 * of them a change to one file four capabilities share. An area is per
 * controller, so the cost is a short file and the benefit is that a sweep
 * growing a second verb touches nothing a purge can see.
 *
 * <p><b>Nothing here is runtime state.</b> {@link Retention} holds a policy, a
 * clock and the stores it sweeps; no session table.
 */
@Component
public class RetentionFrames implements FrameArea {

    private final Retention retention;

    /**
     * @param retention the same bean the controller is injected with
     */
    public RetentionFrames(Retention retention) {
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    @Override
    public Map<String, FrameHandler> frames() {
        return Map.of(FrameTypes.RETENTION_SWEEP, new RetentionSweepHandler(retention));
    }
}
