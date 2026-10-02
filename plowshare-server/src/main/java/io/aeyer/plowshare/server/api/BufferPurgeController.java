package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.buffers.Buffers;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door a buffer purge is run through — {@code RetentionController}'s
 * sibling, for the buffers that are derived data rather than a person's
 * history.
 *
 * <h2>Why this is a verb an operator calls and not a timer this server owns</h2>
 *
 * <p>{@link Buffers}' own class comment carries the argument in full, on
 * {@code RetentionController}'s own reasoning for the sweep beside this one:
 * this server has no scheduler, a timer started from a bean would fire in
 * every Testcontainers test that stands up a fetched-page or result-set store
 * against a real database, and the operator already has cron, a systemd
 * timer, a Kubernetes CronJob, or a person. What this server owes them is an
 * operation safe to call twice, and {@link Buffers#purge()} is that
 * operation.
 *
 * <h2>A sibling of {@code POST /v1/retention/sweep}, not part of it</h2>
 *
 * <p>That sweep stages mark-then-export-then-null because its bytes leave the
 * database. A fetched page or a stored result set is derived data — it can be
 * fetched or searched again — so it needs no staging, and folding this
 * endpoint into that one would put two retention policies behind a single
 * verb. See {@link Buffers} for the argument in full, including why the two
 * stores it purges keep different arities rather than being normalised to
 * one.
 *
 * <h2>Only a door: the arithmetic lives in {@link Buffers}</h2>
 *
 * <p>This controller holds nothing but {@link Buffers} and delegates,
 * exactly as {@code RetentionController} holds nothing but {@code Retention}.
 * The clock, the two live-config reads, and both stores' calls belong to
 * {@link Buffers} so a web layer never does clock arithmetic on live
 * properties, and so this class can be tested the way {@code
 * BufferPurgeControllerTest} tests it — MVC against a mocked {@link Buffers},
 * with no database in the loop.
 *
 * <h2>Behind the same door as everything else</h2>
 *
 * <p>Under {@code /v1}, so {@code AuthFilter} refuses it to a request carrying
 * no access token exactly as it refuses every other path — {@code
 * RetentionController}'s own reasoning, unchanged: one authentication story,
 * not a second one invented for an endpoint that removes data.
 */
@RestController
public class BufferPurgeController {

    private final Buffers buffers;

    public BufferPurgeController(Buffers buffers) {
        this.buffers = buffers;
    }

    /**
     * {@code POST /v1/buffers/purge} — reclaim every expired row in both
     * buffers, and say how many each lost.
     *
     * <h2>200 and a report, not 202 and a job</h2>
     *
     * <p>{@code RetentionController#sweep()}'s own reasoning: this call does
     * a handful of deletes bounded by what is actually expired, not a call to
     * a model, so an operator who asked for space back is told what came back
     * rather than handed a job to poll.
     */
    @PostMapping("/v1/buffers/purge")
    public Buffers.BufferPurgeReport purge() {
        return buffers.purge();
    }
}
