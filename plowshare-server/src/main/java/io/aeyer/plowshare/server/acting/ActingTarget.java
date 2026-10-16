package io.aeyer.plowshare.server.acting;

/**
 * Resolved scope to authorize. Adapters must resolve resource IDs to their actual owning scope
 * before calling the policy. Global and client-private scopes are not admitted by this slice.
 */
public sealed interface ActingTarget permits ActingTarget.Project, ActingTarget.Personal {
  /** Ordinary server project; a Personal name cannot bypass the separate Personal gate. */
  record Project(String name) implements ActingTarget {
    public Project {
      name = ActingValues.application(name);
    }
  }

  /** The actual owner of the resolved Personal resource, not an account selected by the caller. */
  record Personal(String owner) implements ActingTarget {
    public Personal {
      owner = ActingValues.account(owner);
    }
  }
}
