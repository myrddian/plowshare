package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.api.JobView;
import java.util.Map;
import java.util.Objects;

/**
 * {@code job.list} — every job this process is holding. The frame equivalent of
 * {@code GET /v1/jobs}.
 *
 * <h2>The read a socket-only client cannot do without</h2>
 *
 * <p>This is what a live job view reconciles against, and it exists rather than
 * a client keeping its own list from the event stream because {@code
 * EventChannelHandler} publishes into a bounded queue and drops on overflow by
 * design. A client that treated pushes as a log would render a job whose events
 * were dropped as a job that did nothing; what a run came to is here. <b>The
 * same socket carries both</b>, which makes the distinction easier to lose
 * here than it was on HTTP, not harder.
 *
 * <h2>A payload with nothing in it, deliberately</h2>
 *
 * <p>The endpoint takes no path value, no body and no query parameter, so there
 * is nothing for this type's payload to carry and no record to bind. Scoped to
 * what {@link JobStore} holds, which is this process's jobs and not history:
 * nothing reaps them, so a finished run stays listed until the process ends and
 * is then gone entirely. Neither filtered nor paged — a limit here would be a
 * number invented rather than measured.
 */
public final class JobListHandler implements FrameHandler {

    private final JobStore jobs;

    /**
     * @param jobs the same store the controller is injected with, which decides
     *     the order
     */
    public JobListHandler(JobStore jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        return Outcome.ok(jobs.jobsFor(asking.handle()).stream().map(JobView::of).toList());
    }
}
