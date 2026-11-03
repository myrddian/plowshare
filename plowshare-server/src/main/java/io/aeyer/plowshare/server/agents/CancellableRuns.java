package io.aeyer.plowshare.server.agents;

/**
 * Asking the live runs of one conversation to stop, for a caller that has already proved its
 * authority over them.
 *
 * <p>The sibling of {@link RunningConversations}, and narrow for the same reason: that interface
 * answers whether a conversation still holds a live run, this one asks the live runs of a
 * conversation to stop, and neither hands a caller anything else {@code JobStore} can do.
 *
 * <p><strong>Keyed on the conversation and not on a job id</strong>, because the callers that have
 * to stop work they did not start — a container cancelling its child, and a destruction collecting
 * a subtree — hold the child's retained conversation envelopes and have never seen a job id. A job
 * id is this process's handle on a run; a conversation is the durable thing a container can name.
 *
 * <p><strong>A request, not an interrupt, and process-local.</strong> Both properties are inherited
 * from {@link JobStore#cancel} rather than improved on here. A run honours the flag at its next
 * turn boundary, because an in-flight model call cannot be interrupted out of a synchronous
 * execute; and the runs that can be reached are the ones this process holds. A durable
 * cross-process stop would be a separate capability, so a caller must not read a zero as "nothing
 * is running anywhere".
 */
public interface CancellableRuns {
  /**
   * Asks every run still going in this conversation to stop at its next turn boundary.
   *
   * <p>Idempotent, and asking about a conversation with nothing running is <em>not</em> an error:
   * zero is the ordinary answer for a child that holds no live run, which is the idiom {@code
   * ContainedPrincipals.revoke} sets for removing nothing. A caller that had to tell "nothing was
   * running" apart from a refusal could not cancel idempotently.
   *
   * @param conversation the conversation whose runs are to stop; never null
   * @return how many runs this call was the one to ask. It never counts a run another caller has
   *     already asked to stop, nor one that has already finished, for {@link JobStore#cancel}'s
   *     reason: a count that included a run which had already answered would claim a stop that
   *     never happened
   */
  int cancelIn(String conversation);
}
