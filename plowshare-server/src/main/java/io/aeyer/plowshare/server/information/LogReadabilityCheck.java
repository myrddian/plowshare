package io.aeyer.plowshare.server.information;

import java.util.Optional;

/**
 * Answers {@link InformationLogVisibility#readable} without the per-call permission SQL when it
 * can. Empty means "ask SQL": the caller must then evaluate {@code information_log_readable}, which
 * is the only oracle. A present answer equals that predicate's answer for the same committed state.
 */
public interface LogReadabilityCheck {
  /**
   * @param account the reading account; never null or blank
   * @param log the log; never null
   */
  Optional<Boolean> readable(String account, String log);

  /** Every check goes to SQL. */
  static LogReadabilityCheck none() {
    return (account, log) -> Optional.empty();
  }
}
