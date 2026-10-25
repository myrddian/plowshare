package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import java.util.Objects;
import java.util.UUID;

/**
 * The ephemeral principal of a contained scope.
 *
 * <p>A contained principal exists to attribute work and to be the subject of a narrowed admission.
 * It holds <b>no credential material</b>: no token id, no handle, no digest, and no row in the
 * account, admin or service-token tables that every authentication path resolves through. "No
 * external login as the child" is therefore structural -- there is nothing for an authentication
 * path to find -- and not a check that each call site must remember. This is deliberately the
 * opposite of ADR 0017's Application-owned service identity, whose reservations and token survive
 * deletion. A contained principal is deleted with its scope.
 *
 * <p>Its identifier is also not an {@code @service/} account name, so {@code
 * ServiceCredentials.principal} is false for it and service-token rules do not classify it.
 */
public interface ContainedPrincipals {
  /** Identity only. Adding a credential-bearing component here would defeat the design. */
  record ContainedPrincipal(UUID id, ScopeId scope) {
    public ContainedPrincipal {
      Objects.requireNonNull(id);
      Objects.requireNonNull(scope);
    }
  }

  /**
   * Returns the scope's principal, creating it on first call. Idempotent: issuing twice for one
   * scope yields the same principal. Refuses a scope that is not a persisted contained scope with
   * exactly this project and container, so a caller cannot mint a principal for an ordinary scope
   * or a mis-described one.
   */
  ContainedPrincipal issue(AuthorityScope.Contained scope);

  /**
   * Removes the scope's principal if present. Removal of the scope itself already collects it by
   * cascade; this is for retiring a principal while the scope remains. Revoking nothing is not an
   * error.
   */
  void revoke(ScopeId scope);
}
