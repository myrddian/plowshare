package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.archive.Retention;
import java.util.Map;
import java.util.Objects;

/**
 * {@code retention.sweep} — eject what was marked, then mark what policy
 * selects, and say what happened. The frame equivalent of {@code POST
 * /v1/retention/sweep}.
 *
 * <h2>{@link Code#OK} and a report, not {@link Code#ACCEPTED} and a job</h2>
 *
 * <p>The mistake this type most invites, because every other verb on this
 * surface that starts work answers {@code ACCEPTED}. A sweep calls no model: it
 * is a few queries, a file write per payload and an {@code UPDATE} per row, all
 * bounded by what is actually marked. And <b>the report is the point of the
 * synchronous answer</b> — an operation that deletes file bodies and answers
 * "accepted" gives an operator nothing to check, while {@code SweepReport} names
 * how many trees were marked, how many results were ejected and how many
 * characters those held, which is the number they were trying to bring down.
 *
 * <h2>Safe to call twice, which is what makes an explicit trigger workable</h2>
 *
 * <p>{@link Retention#sweep} guards every ejection with {@code ejected_at IS
 * NULL} and every move by the transition table, so a second call against the
 * same state finds nothing to do and reports nothing done. Nothing about that
 * changes on a socket, and nothing here adds a guard of its own: a frame and a
 * {@code curl} run the same sweep.
 *
 * <h2>A payload with nothing in it, deliberately</h2>
 *
 * <p>The endpoint takes no body, no path value and no query parameter — {@code
 * job.list}'s shape. There is no tier to name: a sweep is the whole deployment's
 * policy, and a project field here would be a scope the endpoint does not have.
 */
public final class RetentionSweepHandler implements FrameHandler {

    private final Retention retention;

    /**
     * @param retention the same bean the controller is injected with, which
     *     holds the policy, the clock and the staging
     */
    public RetentionSweepHandler(Retention retention) {
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        return Outcome.ok(retention.sweep());
    }
}
