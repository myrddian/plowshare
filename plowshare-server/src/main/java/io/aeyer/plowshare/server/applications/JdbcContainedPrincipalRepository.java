package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Specialist repository for {@code contained_principals}. Mutations join the caller's transaction,
 * so a principal is created or retired atomically with the scope work around it.
 */
@Repository
public class JdbcContainedPrincipalRepository implements ContainedPrincipals {
  /**
   * The containment refusal for a contained scope that does not resolve; identical to the one
   * {@code JdbcIdentityRepository.containedHome} gives, so a caller layer translates one fault for
   * both.
   */
  static final String UNRESOLVED = "Identity or scope mapping is unavailable or restricted";

  private final JdbcTemplate jdbc;

  public JdbcContainedPrincipalRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public ContainedPrincipal issue(AuthorityScope.Contained scope) {
    // Insert only against a stored CONTAINED row matching project and container, so the claim
    // the caller holds is checked against the authority record rather than trusted. The UNIQUE
    // scope_id makes concurrent issues converge on one row.
    jdbc.update(
        "INSERT INTO contained_principals(scope_id) SELECT s.id FROM authority_scopes s"
            + " WHERE s.id=? AND s.kind='CONTAINED' AND s.project_id=? AND s.container=?"
            + " ON CONFLICT (scope_id) DO NOTHING",
        scope.id().value(),
        scope.projectId(),
        scope.container().value());
    return jdbc
        .query(
            "SELECT p.id FROM contained_principals p JOIN authority_scopes s ON s.id=p.scope_id"
                + " WHERE p.scope_id=? AND s.project_id=? AND s.container=?",
            (row, n) -> new ContainedPrincipal(row.getObject(1, UUID.class), scope.id()),
            scope.id().value(),
            scope.projectId(),
            scope.container().value())
        .stream()
        .findFirst()
        .orElseThrow(() -> new CallerFault(UNRESOLVED));
  }

  @Override
  public void revoke(ScopeId scope) {
    jdbc.update("DELETE FROM contained_principals WHERE scope_id=?", scope.value());
  }
}
