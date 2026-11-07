package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import java.util.Objects;
import java.util.Optional;
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
   * The live principal of the contained scope hosted by this project, or empty.
   *
   * <p>This is the resolution a run binding needs and the only one it needs: a contained run knows
   * its home, which is the child's own {@code contained:} project, and it has to arrive at the
   * principal UUID every information gate is asked about. It is a read and it issues nothing — a
   * caller holding only this method cannot mint a principal, which is why it is separate from
   * {@link #issue} rather than a fallback inside it.
   *
   * <p><strong>It hands out no credential material.</strong> The returned UUID is an identity and
   * nothing else: it is not a handle, not an {@code admins} or {@code authority_accounts} row and
   * not a token, so a caller that obtains one gains no way to authenticate as the child. That is
   * the same absence {@link ContainedPrincipals}' own contract rests on, restated here because this
   * method is the first one a non-containment caller reaches for.
   *
   * <p>Empty means "not a contained project, or no live principal for it", and the two are
   * deliberately one answer: both must leave a caller resolving no contained actor rather than
   * guessing one. A project name that is not a contained Application's is answered empty without a
   * query.
   *
   * @param project a project name, or null; neither has to be a contained project
   */
  Optional<UUID> principalOfProject(String project);

  /**
   * Removes the scope's principal if present. Removal of the scope itself already collects it by
   * cascade; this is for retiring a principal while the scope remains. Revoking nothing is not an
   * error.
   *
   * <p>It takes the same fully described {@link AuthorityScope.Contained} {@link #issue} takes, and
   * for the same reason: the caller's claim about which project and container a scope belongs to is
   * checked against the authority record rather than trusted. The earlier shape took a bare scope
   * id and checked neither, which was harmless only while nothing called it. It has a caller now --
   * destruction -- and a bare id is the one argument an attacker-influenced value could be: a
   * caller that resolved the wrong record, or was handed an id from a request, would have retired
   * another Application's principal silently, since removing nothing is not an error and removing
   * the wrong thing would have looked identical.
   */
  void revoke(AuthorityScope.Contained scope);
}
