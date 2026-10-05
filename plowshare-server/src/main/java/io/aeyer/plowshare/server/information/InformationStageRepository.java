package io.aeyer.plowshare.server.information;

import io.aeyer.plowshare.server.agents.Outcome;
import java.util.Optional;
import java.util.UUID;

/**
 * Processing logs and paid stage receipts; model dispatch and hook policy stay outside persistence.
 */
public interface InformationStageRepository {
  record LogState(
      String project,
      String log,
      int allowance,
      int spent,
      String session,
      boolean systemCompatible) {
    public LogState(String project, String log, int allowance, int spent, String session) {
      this(project, log, allowance, spent, session, true);
    }
  }

  LogState logState(UUID revision, Long project);

  /**
   * Pins a newly created log only if still absent. A false result requires rereading the winner.
   */
  boolean pinLog(UUID revision, String log);

  /** Switch future work to a fresh log only while the previous frozen user log is still pinned. */
  boolean replaceLog(UUID revision, String previous, String replacement);

  UUID resource(UUID revision);

  Optional<Outcome> paid(UUID revision, long generation, String key, String owner);

  /** Stores an answered response before post-gate evaluation, without overwriting a winner. */
  void remember(
      UUID revision,
      long generation,
      String key,
      String stage,
      String owner,
      Outcome response,
      String log);

  void ready(UUID revision, long generation, String key);
}
