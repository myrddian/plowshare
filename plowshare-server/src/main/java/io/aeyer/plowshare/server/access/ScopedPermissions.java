package io.aeyer.plowshare.server.access;

import io.aeyer.plowshare.protocol.Home;

/** Live project action admission; resource policy and lifecycle gates remain the caller's duty. */
public interface ScopedPermissions {
  /**
   * Checks the current effective role without retaining a grant or changing membership.
   *
   * <p>The principal must come from authenticated server context, never a request's claimed role or
   * identity. Missing authority returns false; unavailable authority propagates its failure and
   * must not be treated as permission. All arguments are required.
   */
  boolean allows(Principal principal, ProjectTarget target, Action action);

  /** Only the implemented project actions; no ordering or implicit permission inheritance. */
  enum Action {
    READ,
    WRITE
  }

  /** Validates identity presence, not authentication; preserves the server's exact principal. */
  record Principal(String value) {
    public Principal {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("project permission needs an authenticated principal");
      }
    }
  }

  /**
   * An explicit existing-format project locator, not a durable scope UUID or a fallback home.
   * Project existence and access are resolved by the effective role source on every check.
   */
  record ProjectTarget(String value) {
    public ProjectTarget {
      // Reuse the current project locator validation without admitting Home.global().
      Home.of(value);
    }
  }
}
