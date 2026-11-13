package io.aeyer.plowshare.server.information;

import java.util.Optional;

/**
 * Whether the SYSTEM account may process information, decided outside the polling statement.
 *
 * <p>Both answers are installation-wide facts about authority, not about a revision, so a worker
 * poll should not re-derive them from live grants on every pass. An empty answer means only SQL may
 * decide for this caller (for example a transaction holding its own uncommitted authority writes);
 * callers then evaluate the original SQL functions unchanged.
 */
public interface SystemProcessingAuthority {
  /** {@code EXISTS(intrinsic)} of {@code information_system_processing_revisions} (V236). */
  Optional<Boolean> processing();

  /** {@code information_system_scope_valid(project)} (V161); {@code project} may be null. */
  Optional<Boolean> scopeValid(String project);

  /** Every decision stays in SQL. */
  static SystemProcessingAuthority sql() {
    return new SystemProcessingAuthority() {
      public Optional<Boolean> processing() {
        return Optional.empty();
      }

      public Optional<Boolean> scopeValid(String project) {
        return Optional.empty();
      }
    };
  }
}
