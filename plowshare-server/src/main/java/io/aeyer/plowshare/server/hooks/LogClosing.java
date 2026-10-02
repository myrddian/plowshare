package io.aeyer.plowshare.server.hooks;

import java.util.Objects;

/**
 * What {@code log.close} is shown beyond its context (spec 2026-09-28-hooks-reach-the-log §3).
 *
 * @param ending lower case, as {@code LogEnding} in plowshare-hooks declares it: a run's {@code
 *     Outcome.Ending} name lower-cased, an orchestration's state, or a conversation's new lifecycle
 * @param turns how many turns the log holds
 */
public record LogClosing(String ending, int turns) {

    public LogClosing {
        Objects.requireNonNull(ending, "ending");
    }
}
