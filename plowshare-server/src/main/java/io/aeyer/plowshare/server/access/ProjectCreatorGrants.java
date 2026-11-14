package io.aeyer.plowshare.server.access;

import java.util.Collection;
import java.util.Set;

/**
 * Issues and revokes the {@code PROJECT_CREATOR} role (V262) on the ADMIN role's authority.
 *
 * <p>The acting account must currently exercise the ADMIN role: an active, non-bootstrap USER
 * server administrator holding a live {@code ADMIN_CONTROL} grant at the ADMINISTRATION scope.
 * Otherwise both mutations throw {@code CallerFault("Only a server administrator may manage server
 * resources")} before writing anything. A grant to another account hangs off the SYSTEM root and
 * records the administrator only as issuer and exercised grant, so it outlives that administrator's
 * later demotion or disablement. A grant to the administrator themselves hangs off their own
 * ADMIN_CONTROL grant instead and ends when they stop being an administrator, as self-assignment of
 * memberships does. Any current administrator may revoke either.
 *
 * <p>Both mutations are idempotent, report whether they changed anything, and run in one
 * transaction, joining the caller's when one is active. Creator mutations are serialized on the
 * PROJECT_CREATION scope row.
 */
public interface ProjectCreatorGrants {
  /**
   * {@code authority_may_create_projects(handle)}: the project-creation decision, which also
   * requires the account to be active. A null or blank handle is false.
   */
  boolean mayCreate(String handle);

  /**
   * {@code authority_holds_project_creator(handle)}: whether the account holds the role, for the
   * administration view and {@link #revoke}'s result. Unlike {@link #mayCreate} it ignores account
   * activity, so a disabled account whose grant revives on re-enable is still a holder an
   * administrator can see and revoke. Never a creation decision. A null or blank handle is false.
   */
  boolean holds(String handle);

  /**
   * The subset of {@code handles} that {@link #holds} would answer true for, in one read. Used by
   * account listings so the role is never resolved once per row.
   */
  Set<String> holdersAmong(Collection<String> handles);

  /**
   * Grants the role to {@code handle}, which must be an existing, active, non-bootstrap USER
   * account; it may be {@code actor}. A live holder is left unchanged, except that a live bound
   * self-grant is replaced (revoked, attributed to {@code actor}) by the durable grant when another
   * administrator grants the role. A dead (expired, or a self-grant whose ADMIN_CONTROL lineage
   * fell) but unrevoked grant still occupies the one-creator-grant slot, so it is revoked
   * (attributed to {@code actor}) in the same transaction before the new grant is written.
   *
   * @return whether a grant was written: the account became a holder, or its bound self-grant was
   *     replaced by a durable one; false for a no-op
   */
  boolean grant(String actor, String handle);

  /**
   * Revokes the subject's unrevoked creator grant, live or dead, attributed to {@code actor} and
   * their {@code ADMIN_CONTROL} grant. A non-holder is left unchanged (a dead row is still cleaned
   * up). No other grant is touched. A disabled holder ({@link #holds}) is revoked like any other.
   *
   * @return whether the account was a holder and no longer is; false for a no-op
   */
  boolean revoke(String actor, String handle);
}
