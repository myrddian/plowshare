package io.aeyer.plowshare.server.agents;

/** Current runtime ownership; an unfinished historical job row is not proof of a live job. */
public interface RunningConversations {
  /** True until the active job's close callbacks and outcome recording have completed. */
  boolean hasRunningJob(String conversation);
}
