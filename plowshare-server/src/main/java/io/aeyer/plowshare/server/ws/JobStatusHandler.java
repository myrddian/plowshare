package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.api.JobView;
import java.util.Map;
import java.util.Objects;

/**
 * {@code job.status} — how a run is going, and how it ended once it has. The
 * frame equivalent of {@code GET /v1/jobs/&#123;id&#125;}.
 *
 * <h2>One type for both questions</h2>
 *
 * <p>{@code agent_poll} and {@code agent_result} are two renderings of this,
 * not two states of the server: a job that has finished carries its outcome
 * from the moment it finishes, and there is nothing a second call could learn.
 * This is also the handle every {@link io.aeyer.plowshare.protocol.frames.Code#ACCEPTED}
 * answer on this surface hands back — {@code agent.run}'s and {@code
 * agent.curate}'s alike.
 *
 * <h2>The 404 is {@link JobStore#get}'s and not this handler's</h2>
 *
 * <p>A poll on an unknown id says so, which is honest: jobs live in memory, so
 * after a restart the run really is gone. That refusal moved down to the store
 * with the rest of them, and the caller that justified inventing {@code
 * NotFoundFault} for it is precisely this one — a frame handler calling {@code
 * JobStore.get} without passing through a controller, which would have been
 * answered 500 for a misspelled id.
 */
public final class JobStatusHandler implements FrameHandler {

    private final JobStore jobs;

    /**
     * @param jobs the same store the controller is injected with, which owns
     *     both the lookup and its refusal
     */
    public JobStatusHandler(JobStore jobs) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
    }

    @Override
    public Outcome handle(Map<String, Object> payload, Asking asking) {
        String id = Payloads.required(payload, "job", FrameTypes.JOB_STATUS,
                "the id this server named when the run was submitted. Nothing was read.");
        return Outcome.ok(JobView.of(jobs.getFor(id, asking.handle())));
    }
}
