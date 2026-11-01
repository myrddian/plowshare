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

  /**
   * A project confined inside an enclosing scope. Containment is a relation over existing projects
   * and existing authority records, so a contained scope carries an ordinary project key; what
   * distinguishes it is that its authority derives from {@code container} and can only narrow.
   *
   * <p>{@code container} may itself be {@code Contained}, which is how depth is expressed. It is
   * never this scope's own id: a scope that contained itself would make the containment walk
   * unbounded and would let a child answer as its own parent. Nothing here is a permission check —
   * constructing a contained scope grants nothing.
   */
  record Contained(ScopeId id, long projectId, ScopeId container) implements AuthorityScope {
    public Contained {
      Objects.requireNonNull(id);
      Objects.requireNonNull(container);
      projectId = positive(projectId);
      if (id.equals(container)) throw new IllegalArgumentException("A scope cannot contain itself");
    }
  }

  private static long positive(long id) {
    if (id <= 0) throw new IllegalArgumentException("A project mapping requires an existing key");
    return id;
  }
}
