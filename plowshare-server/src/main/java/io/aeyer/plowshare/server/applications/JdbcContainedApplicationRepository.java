package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Specialist repository for the containment relation's own rows. Every statement here joins the
 * caller's transaction, so the project, the scope and the record either all exist or none do.
 */
@Repository
public class JdbcContainedApplicationRepository implements ContainedApplicationRepository {
  private static final String COLUMNS =
      "c.id,c.scope_id,c.project_id,p.name,c.container,c.request_id,c.fingerprint,c.revision,"
          + "c.allowance,c.ceiling,c.live";

  private final JdbcTemplate jdbc;

  public JdbcContainedApplicationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Retained> receipt(ScopeId container, UUID requestId) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM contained_applications c JOIN projects p ON p.id=c.project_id"
                + " WHERE c.container=? AND c.request_id=?",
            JdbcContainedApplicationRepository::read,
            container.value(),
            requestId)
        .stream()
        .findFirst();
  }

  /**
   * Live descendants anywhere beneath this container. The recursive term walks {@code
   * authority_scopes.container}, which is the relation itself, so the count is the subtree's and
   * not merely the immediate children's: depth and breadth each multiply a container's budget, so
   * the aggregate is what the breadth bound limits. The walk terminates because V196 freezes {@code
   * container} after insert and an insert can only name an existing container, which makes the
   * relation acyclic by construction.
   */
  @Override
  public int liveDescendants(ScopeId container) {
    Integer count =
        jdbc.queryForObject(
            "WITH RECURSIVE subtree AS ("
                + "  SELECT s.id FROM authority_scopes s WHERE s.container=?"
                + "  UNION ALL"
                + "  SELECT s.id FROM authority_scopes s JOIN subtree t ON s.container=t.id)"
                + " SELECT count(*) FROM contained_applications c"
                + " JOIN subtree t ON t.id=c.scope_id WHERE c.live",
            Integer.class,
            container.value());
    return count == null ? 0 : count;
  }

  @Override
  public Retained create(Creation creation) {
    try {
      // The project first: authority_scopes.project_id needs it, and V195 excludes the reserved
      // contained: prefix from authority_map_project, so this insert acquires no PROJECT scope
      // that no caller named. ProjectStore.ordinary refuses this prefix precisely so that the
      // explicit CONTAINED insert below has exactly one home, which is here.
      Long project =
          jdbc.queryForObject(
              "INSERT INTO projects(name,workspace,project_type,application_boundary)"
                  + " VALUES(?,?, 'MANAGED', TRUE) RETURNING id",
              Long.class,
              creation.project(),
              creation.workspace().toString());
      if (project == null) throw new CallerFault(UNAVAILABLE);
      UUID scope = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO authority_scopes(id,kind,project_id,container) VALUES(?,'CONTAINED',?,?)",
          scope,
          project,
          creation.container().value());
      // The agreement trigger in V198 rechecks that these three columns say what the scope row
      // says, so the denormalised container cannot drift from the authority record.
      jdbc.update(
          "INSERT INTO contained_applications"
              + "(scope_id,container,project_id,request_id,fingerprint,revision,workspace,"
              + "allowance,ceiling) VALUES(?,?,?,?,?,?,?,?,?)",
          scope,
          creation.container().value(),
          project,
          creation.requestId(),
          creation.fingerprint(),
          creation.revision(),
          creation.workspace().toString(),
          creation.allowance().value(),
          creation.ceiling());
      return receipt(creation.container(), creation.requestId())
          .orElseThrow(() -> new CallerFault(UNAVAILABLE));
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent identical request is the single-winner case the UNIQUE(container,request_id)
      // exists for. It is not retried here and not reported as success: the caller reads its
      // receipt, exactly as ADR 0005 requires of an uncertain submission.
      throw new CallerFault(
          "This contained Application was created concurrently; read its receipt rather than"
              + " repeating the request");
    }
  }

  @Override
  public Optional<Retained> find(UUID id) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM contained_applications c JOIN projects p ON p.id=c.project_id"
                + " WHERE c.id=?",
            JdbcContainedApplicationRepository::read,
            id)
        .stream()
        .findFirst();
  }

  @Override
  public void ceiling(UUID id, Integer calls) {
    if (calls == null || calls < 0)
      throw new CallerFault("A contained ceiling is explicit and non-negative");
    // Only a live child's ceiling changes. A destroyed child's record is history and its stored
    // ceiling is what it was destroyed with.
    if (jdbc.update("UPDATE contained_applications SET ceiling=? WHERE id=? AND live", calls, id)
        != 1) throw new CallerFault("No such live contained Application");
  }

  private static final String UNAVAILABLE = "The contained Application record is unavailable";

  private static Retained read(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Retained(
        row.getObject("id", UUID.class),
        new ScopeId(row.getObject("scope_id", UUID.class)),
        row.getLong("project_id"),
        row.getString("name"),
        new ScopeId(row.getObject("container", UUID.class)),
        row.getObject("request_id", UUID.class),
        row.getString("fingerprint"),
        row.getObject("revision", UUID.class),
        new AllowanceId(row.getObject("allowance", UUID.class)),
        row.getInt("ceiling"),
        row.getBoolean("live"));
  }
}
