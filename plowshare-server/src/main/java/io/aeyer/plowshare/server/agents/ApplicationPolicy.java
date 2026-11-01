package io.aeyer.plowshare.server.agents;

import io.aeyer.plowshare.server.archive.ProjectRole;
import java.util.Map;
import java.util.Optional;

/** Reads the deployed application boundary, never a client checkout's replacement policy. */
public interface ApplicationPolicy {
  /** Re-read on every decision so source changes and revocation apply to existing connections. */
  Boundary read(String project);

  /**
   * Resolves policy and its already-read server workspace together. A resolution belongs to one
   * operation, never an authorization lease; subsequent decisions resolve again. Implementations
   * without workspace facts fail closed for managed resource access.
   */
  default Source source(String project) {
    return new Source(read(project), Optional.empty());
  }

  record Source(
      Boundary boundary, Optional<io.aeyer.plowshare.server.archive.ProjectRecord> workspace) {
    public Source {
      java.util.Objects.requireNonNull(boundary);
      java.util.Objects.requireNonNull(workspace);
    }
  }

  enum Kind {
    EXTERNAL,
    APPLICATION,
    INVALID
  }

  /** An invalid or ungranted application denies use; legacy external membership is unchanged. */
  record Boundary(Kind kind, Map<String, ProjectRole> accounts) {
    public Boundary {
      accounts = Map.copyOf(accounts);
    }

    public Optional<ProjectRole> limit(String account, Optional<ProjectRole> membership) {
      if (kind == Kind.EXTERNAL) return membership;
      if (kind == Kind.INVALID || account == null) return Optional.empty();
      ProjectRole grant = accounts.get(account);
      return grant == null
          ? Optional.empty()
          : membership.map(role -> role.allows(grant) ? grant : role);
    }
  }
}
