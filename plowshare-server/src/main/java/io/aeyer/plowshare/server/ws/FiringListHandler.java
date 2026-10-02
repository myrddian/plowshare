package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.events.FiringStore;
import java.util.Map;
import java.util.Objects;

/** {@code firing.list} — what arrived, what it started, and why anything was refused. */
public final class FiringListHandler implements FrameHandler {

    record Body(String trigger, String status, Integer offset, Integer limit) {}

    private final FiringStore firings;

    public FiringListHandler(FiringStore firings) {
        this.firings = Objects.requireNonNull(firings, "firings");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        Body body = Payloads.as(payload, Body.class, FrameTypes.FIRING_LIST);
        int offset = body.offset() == null ? 0 : Math.max(0, body.offset());
        int limit = body.limit() == null ? 50 : Math.min(200, Math.max(1, body.limit()));
        return Outcome.ok(firings.list(body.trigger(), body.status(), offset, limit));
    }
}
