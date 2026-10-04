package io.aeyer.plowshare.server.orchestrations;

import io.aeyer.plowshare.server.agents.Job;
import io.aeyer.plowshare.server.agents.JobStore;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Cancelling a run and its conductor's live job together — Decision 6: the row is stopped first, so
 * the job's own {@code CANCELLED} ending finds the run already cancelled and tells nobody a second
 * time.
 *
 * <p>The job half is also {@link #jobsIn}, so the engine can do the same for every descendant a
 * cascade stops without learning what a job is: one rule, in the class whose javadoc argues for it.
 */
public final class OrchestrationCancel {

  private final Orchestrations orchestrations;
  private final OrchestrationStore store;
  private final JobStore jobs;
  private final ConversationStore conversations;

  public OrchestrationCancel(
      Orchestrations orchestrations,
      OrchestrationStore store,
      JobStore jobs,
      ConversationStore conversations) {
    this.orchestrations = Objects.requireNonNull(orchestrations, "orchestrations");
    this.store = Objects.requireNonNull(store, "store");
    this.jobs = Objects.requireNonNull(jobs, "jobs");
    this.conversations = Objects.requireNonNull(conversations, "conversations");
  }

  /**
   * Cancel the run, then any job still running in its conductor's conversation.
   *
   * <p>Only a cancel that won stops the job. A run that had already ended — finished, say, on the
   * very turn still winding down — keeps that turn, whose own ending delivers the result;
   * cancelling it would end it {@code CANCELLED} and leave the result for a later drain.
   *
   * @return whether this call is the one that cancelled the run
   */
  public boolean cancel(String orchestration, String by) {
    if (!orchestrations.cancel(orchestration, by)) {
      return false;
    }
    store
        .find(orchestration)
        .ifPresent(run -> jobsIn(jobs, conversations).accept(run.conductorConversation()));
    return true;
  }

  /**
   * {@link #cancel} for a parent's conductor cancelling its own child, which it may do only while
   * the child is asking or waiting: see {@link Orchestrations#cancelOwnChild}.
   */
  public boolean cancelOwnChild(String child, String by) {
    return stopJobsIfCancelled(child, orchestrations.cancelOwnChild(child, by));
  }

  /**
   * {@link #cancel} for a model caller: only a run still asking or waiting when the row is stopped
   * — rule 2 (spec 2026-09-27 §3), judged by the stop itself: see {@link
   * Orchestrations#cancelUnlessRunning}. A running run keeps its row and its job.
   */
  public boolean cancelUnlessRunning(String orchestration, String by) {
    return stopJobsIfCancelled(
        orchestration, orchestrations.cancelUnlessRunning(orchestration, by));
  }

  private boolean stopJobsIfCancelled(String orchestration, boolean cancelled) {
    if (!cancelled) {
      return false;
    }
    store
        .find(orchestration)
        .ifPresent(run -> jobsIn(jobs, conversations).accept(run.conductorConversation()));
    return true;
  }

  /**
   * Cancel every job still running for one conductor, and nothing else — what a stopped row owes
   * the conductor that was speaking for it. Handed to {@code Orchestrations} as its {@code
   * cancelJobsIn} seam, so a cascade's descendants are stopped the same way a caller's own cancel
   * stops a root.
   *
   * <p><b>"For one conductor" is its conversation and its direct delegations.</b> A sub-agent the
   * engine resumed after an approval — spec 2026-09-26 §4 — runs as a job of its own in its
   * delegation conversation, not the conductor's, and spends the conductor's budget. Matching the
   * conductor's conversation alone would leave it spending for a run nobody is waiting on. A
   * delegation made by any other conversation is not this conductor's and is left alone. The lookup
   * is only made for a running job whose conversation is a delegation, which is only ever such a
   * resume: an ordinary {@code agent_run} child runs inside its caller's job, not as one.
   */
  public static Consumer<String> jobsIn(JobStore jobs, ConversationStore conversations) {
    Objects.requireNonNull(jobs, "jobs");
    Objects.requireNonNull(conversations, "conversations");
    return conversation ->
        jobs.jobs().stream()
            .filter(job -> job.state() == Job.State.RUNNING)
            .filter(
                job ->
                    conversation.equals(job.conversation())
                        || delegatedBy(conversations, job, conversation))
            .forEach(job -> jobs.cancel(job.id()));
  }

  private static boolean delegatedBy(ConversationStore conversations, Job job, String conductor) {
    return job.conversationOrigin() == Origin.DELEGATION
        && job.conversation() != null
        && conversations
            .find(job.conversation())
            .filter(
                child -> child.origin() == Origin.DELEGATION && conductor.equals(child.parentId()))
            .isPresent();
  }
}
