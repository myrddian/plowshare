package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.server.access.AuthorityIds.*;
import java.util.Objects;

/**
 * Explicit resolved scope identity, separate from request omission, resource IDs and permission.
 */
public sealed interface AuthorityScope {
  ScopeId id();

  record Project(ScopeId id, long projectId) implements AuthorityScope {
    public Project {
      Objects.requireNonNull(id);
      projectId = positive(projectId);
    }
  }

  record Personal(ScopeId id, AccountId account, long projectId) implements AuthorityScope {
    public Personal {
      Objects.requireNonNull(id);
      Objects.requireNonNull(account);
      projectId = positive(projectId);
    }
  }

  record AccountKnowledge(ScopeId id, AccountId account) implements AuthorityScope {
    public AccountKnowledge {
      Objects.requireNonNull(id);
      Objects.requireNonNull(account);
    }
  }

  record Administration(ScopeId id, AccountId account) implements AuthorityScope {
    public Administration {
      Objects.requireNonNull(id);
      Objects.requireNonNull(account);
    }
  }

  private static long positive(long id) {
    if (id <= 0) throw new IllegalArgumentException("A project mapping requires an existing key");
    return id;
  }
}
