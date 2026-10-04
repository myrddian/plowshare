package io.aeyer.plowshare.server.api;

/**
 * What starting a run answers with, at once, before the run has done anything.
 *
 * @param id the job id, for polling and cancelling
 * @param agent what is running under it
 * @param revision retained report/source revision when relevant
 * @param conversation newly opened or reused conversation, when relevant
 */
public record StartedJob(
    String id,
    String agent,
    String revision,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String conversation) {
  public StartedJob(String id, String agent, String revision) {
    this(id, agent, revision, null);
  }

  public StartedJob(String id, String agent) {
    this(id, agent, null);
  }
}
