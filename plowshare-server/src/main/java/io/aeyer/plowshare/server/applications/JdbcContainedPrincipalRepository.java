package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityScope;
import io.aeyer.plowshare.server.access.IdentityRepository;
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
        .orElseThrow(() -> new CallerFault(IdentityRepository.UNRESOLVED));
  }

  /**
   * One statement, keyed on the project's name through the authority record.
   *
   * <p>The {@code kind='CONTAINED'} conjunct is not redundant with the {@code contained_principals}
   * join. The join alone would answer for any scope that happens to carry a principal row, and the
   * prefix test above it is a Java fence over a name; this conjunct is the authority record's own
   * statement of what the scope is, which is the thing the gates are keyed on. All three agreeing
   * is the point: a project that merely looks contained, or a principal row left beside a scope
   * that is no longer contained, answers empty.
   *
   * <p>{@link io.aeyer.plowshare.protocol.ContainedProjects#name} is case-insensitive while the
   * {@code projects.name} comparison is not, and that asymmetry is the safety belt ADR 0042
   * records: a differently-cased name cannot be created at all, so refusing it before the query is
   * correct and admitting it afterwards is impossible.
   *
   * <p>At most one row can match — {@code contained_principals.scope_id} is UNIQUE and a project
   * hosts one scope — so {@code findFirst} is the shape of the answer rather than a choice among
   * candidates. An absent row is empty and not a fault: see the contract.
   */
  @Override
  public java.util.Optional<UUID> principalOfProject(String project) {
    if (!io.aeyer.plowshare.protocol.ContainedProjects.name(project))
      return java.util.Optional.empty();
    return jdbc
        .query(
            "SELECT p.id FROM contained_principals p"
                + " JOIN authority_scopes s ON s.id=p.scope_id"
                + " JOIN projects pr ON pr.id=s.project_id"
                + " WHERE s.kind='CONTAINED' AND pr.name=?",
            (row, n) -> row.getObject(1, UUID.class),
            project)
        .stream()
        .findFirst();
  }

  /**
   * Deletes only against a stored {@code CONTAINED} row matching this scope's project <em>and</em>
   * container, which is {@link #issue}'s predicate verbatim. The asymmetry this closes mattered
   * because removing nothing is not an error: a caller holding a mis-described scope would have
   * removed another Application's principal and seen the same silent success.
   */
  @Override
  public void revoke(AuthorityScope.Contained scope) {
    jdbc.update(
        "DELETE FROM contained_principals p USING authority_scopes s"
            + " WHERE s.id=p.scope_id AND p.scope_id=? AND s.kind='CONTAINED'"
            + " AND s.project_id=? AND s.container=?",
        scope.id().value(),
        scope.projectId(),
        scope.container().value());
  }
}
