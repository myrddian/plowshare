package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirms a resident authority projection or returns the current transaction's grant rows in one
 * statement. Implementations must preserve the caller's isolation, own writes and authority time.
 */
public interface CheckedScopedGrants {
  enum Reason {
    EXACT,
    DIRTY,
    ISOLATION,
    VERSION,
    WINDOW,
    INELIGIBLE
  }

  /** An exact answer carries no rows; every other answer carries the complete SQL fallback. */
  record Checked(Reason reason, long version, List<Binding> rows) {
    public Checked {
      Objects.requireNonNull(reason);
      rows = List.copyOf(rows);
      if (version < 0 || reason == Reason.EXACT && !rows.isEmpty())
        throw new IllegalArgumentException("Invalid checked grant answer");
    }

    public boolean exact() {
      return reason == Reason.EXACT;
    }
  }

  /** An absent token always requests SQL, including an explicit empty-result marker. */
  Checked liveChecked(GrantRecipient recipient, Optional<ProjectionVersions.Token> token);

  /** The SQL fallback retains child-to-parent order and the existing maximum ancestry depth. */
  Checked lineageChecked(UUID grant, Optional<ProjectionVersions.Token> token);
}
