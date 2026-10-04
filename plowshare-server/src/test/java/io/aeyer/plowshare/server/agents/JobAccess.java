package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.Origin;

/**
 * A door into {@link Job}'s package-private lifecycle, for tests outside this package.
 *
 * <p>{@code Job}'s constructor, {@code finish} and {@code requestCancel} are package-private
 * because only {@link JobStore} may drive them — a job whose state anything could set would be a
 * handle that lies about a run. That is worth keeping, and it is also why an API test cannot simply
 * build one.
 *
 * <p>A mock would be the other way out and is the worse one: a stubbed {@code state()} can report
 * {@code DONE} with no outcome, which is a pair a real {@code Job} cannot produce, and a view built
 * from it would be asserted against a state the system has no way to reach. Everything here goes
 * through the real transitions.
 */
public final class JobAccess {

  private JobAccess() {}

  /**
   * A job with no limits on its handle, which is what {@code JobStore} registers for work that is
   * not one agent's run.
   */
  public static Job newJob(String id, String agent) {
    return new Job(id, agent, null);
  }

  /**
   * A job under the two bounds a run goes under, for a test that reads or moves them. The objects
   * are the caller's, so a test can move a ceiling and see the handle report it.
   */
  public static Job newJob(String id, String agent, RunLimits limits) {
    return new Job(id, agent, limits);
  }

  /**
   * A job that speaks into a conversation, for a test asserting on {@link Job#conversation()}
   * without standing up a real {@code JobStore} and a database to open one in.
   *
   * <p>4-arg and not 3-, deliberately: {@code conversation} and {@code origin} are one fact
   * together or neither on {@link Job}'s own terms, and a 3-arg {@code (String, String, String)}
   * overload sitting beside {@link #newJob(String, String, RunLimits)} would also make {@code
   * newJob(id, agent, null)} ambiguous between a null conversation and a null {@code RunLimits} — a
   * resolution error the next author to write the obvious thing would hit for no reason connected
   * to what they were testing.
   */
  public static Job newJob(String id, String agent, String conversation, Origin origin) {
    return new Job(id, agent, null, 0, conversation, origin);
  }

  /**
   * The two bounds a run goes under, together with the conversation it speaks into — the shape an
   * ordinary agent run has once both are wired, for a test whose fixture predates one or the other
   * and needs both to keep meaning what it always meant.
   */
  public static Job newJob(
      String id, String agent, RunLimits limits, String conversation, Origin origin) {
    return new Job(id, agent, limits, 0, conversation, origin);
  }

  public static void finish(Job job, Outcome outcome) {
    job.finish(outcome);
  }

  public static boolean requestCancel(Job job) {
    return job.requestCancel();
  }
}
