package io.aeyer.plowshare.server.hooks;

import java.time.Duration;
import java.util.Objects;

/**
 * One hook time limit shared by every hook of a stage, counted from when the stage started (spec
 * 2026-09-28-hooks-reach-the-log decision 4, amended 2026-09-29): all of a fold's {@code
 * fold.post} hooks, harness and project layers alike, get one limit together, not one each. Each
 * hook runs with what is left, and one reached with nothing left is not run.
 *
 * <p><b>It bounds how long the hooks run, not loading.</b> Reloading a changed hook file and
 * building a fresh context for one each keep their own full limit, outside this one: the loaded
 * set and its pools serve every stage, so a load cut short by one fold's leftover time would mark
 * a file broken for all of them.
 *
 * <p>{@link System#nanoTime} and not a clock, as {@code ContextPool} counts its own limit: a
 * deadline is an interval, and a wall clock can be moved under it.
 *
 * @param atNanos when it passes, on {@link System#nanoTime}'s scale
 * @param limit the whole limit it was made from, for a record to name
 */
public record Deadline(long atNanos, Duration limit) {

    public Deadline {
        Objects.requireNonNull(limit, "limit");
    }

    /** A deadline {@code limit} from now. */
    public static Deadline after(Duration limit) {
        return new Deadline(System.nanoTime() + limit.toNanos(), limit);
    }

    /** What is left of it, and {@link Duration#ZERO} once it has passed. */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, atNanos - System.nanoTime()));
    }
}
