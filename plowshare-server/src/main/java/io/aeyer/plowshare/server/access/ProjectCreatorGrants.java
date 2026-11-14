package io.aeyer.plowshare.server.access;

import java.util.Collection;
import java.util.Set;

/**
 * Issues and revokes the {@code PROJECT_CREATOR} role (V262) on the ADMIN role's authority.
 *
 * <p>The acting account must currently exercise the ADMIN role: an active, non-bootstrap USER
 * server administrator holding a live {@code ADMIN_CONTROL} grant at the ADMINISTRATION scope.
 * Otherwise both mutations throw {@code CallerFault("Only a server administrator may manage server
 * resources")} before writing anything. An issued grant hangs off the SYSTEM root and records the
 * administrator only as issuer and exercised grant, so it outlives that administrator's later
 * demotion or disablement; any current administrator may revoke it.
 *
 * <p>Both mutations are idempotent and run in one transaction, joining the caller's when one is
 * active. Creator mutations are serialized on the PROJECT_CREATION scope row.
 */
public interface ProjectCreatorGrants {
  /** {@code authority_may_create_projects(handle)}; a null or blank handle is false. */
  boolean holds(String handle);

  /**
   * The subset of {@code handles} that {@link #holds} would answer true for, in one read. Used by
   * account listings so the role is never resolved once per row.
   */
  Set<String> holdersAmong(Collection<String> handles);

  /**
   * Grants the role to {@code handle}, which must be an existing, active, non-bootstrap USER
   * account other than {@code actor}. A live holder is left unchanged. An expired but unrevoked
   * grant still occupies the one-creator-grant slot, so it is revoked (attributed to {@code actor})
   * in the same transaction before the new grant is written.
   */
  void grant(String actor, String handle);

  /**
   * Revokes the subject's unrevoked creator grant, live or expired, attributed to {@code actor} and
   * their {@code ADMIN_CONTROL} grant. A non-holder is left unchanged. No other grant is touched.
   */
  void revoke(String actor, String handle);
}
