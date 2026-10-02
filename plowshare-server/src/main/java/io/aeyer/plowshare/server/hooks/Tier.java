package io.aeyer.plowshare.server.hooks;

/** Who a hook belongs to, which is also the order hooks run in. */
public enum Tier {
    /** Always on, Java, runs first, cannot be switched off. */
    HARNESS("harness"),
    /** A project's own hooks, in {@code projects/<id>/hooks/}. */
    PROJECT("project"),
    /**
     * A person's own hooks, from their session's {@code .plowshare/hooks/}: served by their
     * session, run by the server, snapshotted per log (spec 2026-09-30-local-hooks-are-served).
     */
    LOCAL("local");

    private final String wireName;

    Tier(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
