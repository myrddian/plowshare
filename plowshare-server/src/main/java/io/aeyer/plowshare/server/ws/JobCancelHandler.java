package io.aeyer.plowshare.server.ws;

import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.api.JobView;
import java.util.Map;
import java.util.Objects;

/**
 * {@code job.cancel} — ask a run to stop at its next turn boundary. The frame equivalent of {@code
 * POST /v1/jobs/&#123;id&#125;/cancel}.
 *
 * <h2>The answer still reads {@code RUNNING}, and that is the contract</h2>
 *
 * <p>A cancel is a request the loop honours between turns, not a kill, so the job this answers with
 * is the job as it stands and {@code cancelRequested} is what says the request landed. A client
 * shown {@code DONE} here would be shown a state that is not yet true — and on a socket, where the
 * same connection is already carrying the run's own events, a premature {@code DONE} is a client
 * that stops listening to a run still going.
 *
 * <p>Cancelling a finished job is not an error. It changes nothing, and refusing it would make a
 * caller race the run it is trying to stop. The job is looked up before the cancel, so an unknown
 * id is {@code JobStore.get}'s 404 rather than a silent no-op.
 */
public final class JobCancelHandler implements FrameHandler {

  private final JobStore jobs;

  /**
   * @param jobs the same store the controller is injected with, which holds both the lookup and the
   *     cancellation
   */
  public JobCancelHandler(JobStore jobs) {
    this.jobs = Objects.requireNonNull(jobs, "jobs");
  }

  @Override
  public Outcome handle(Map<String, Object> payload, Asking asking) {
    String id =
        Payloads.required(
            payload,
            "job",
            FrameTypes.JOB_CANCEL,
            "the id this server named when the run was submitted. Nothing was stopped.");
    Job job = jobs.getFor(id, asking.handle());
    jobs.cancel(id);
    return Outcome.ok(JobView.of(job));
  }
}
