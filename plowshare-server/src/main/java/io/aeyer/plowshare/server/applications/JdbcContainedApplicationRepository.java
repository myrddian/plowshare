package io.aeyer.plowshare.server.applications;

import io.aeyer.plowshare.server.access.AuthorityIds.ScopeId;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.List;
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

  /**
   * Live direct children, oldest first. {@code live} is in the predicate rather than left to the
   * caller: a destroyed child is history, and a container listing what it holds is asking about
   * what it holds now.
   */
  @Override
  public List<Retained> children(ScopeId container) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM contained_applications c JOIN projects p ON p.id=c.project_id"
            + " WHERE c.container=? AND c.live ORDER BY c.id",
        JdbcContainedApplicationRepository::read,
        container.value());
  }

  /**
   * The record a contained scope belongs to, live or not. Unlike {@link #children}, this answers an
   * ancestry walk rather than a listing: a destroyed container's row still names <em>its</em>
   * container, and dropping it would break the chain above a child that is still live.
   */
  @Override
  public Optional<Retained> ofScope(ScopeId scope) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM contained_applications c JOIN projects p ON p.id=c.project_id"
                + " WHERE c.scope_id=?",
            JdbcContainedApplicationRepository::read,
            scope.value())
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

  /**
   * The subtree, deepest first. The recursive term walks {@code authority_scopes.container} exactly
   * as {@link #liveDescendants} does, and the depth it accumulates is what the ordering uses: a
   * container's rows must be deleted after every descendant's, because {@code
   * authority_scopes.container} is {@code ON DELETE RESTRICT}. Live and destroyed alike, because a
   * row that is still present is still a row that must go before its container's.
   */
  @Override
  public List<Retained> subtree(ScopeId scope) {
    return jdbc.query(
        "WITH RECURSIVE tree AS ("
            + "  SELECT s.id,0 AS depth FROM authority_scopes s WHERE s.id=?"
            + "  UNION ALL"
            + "  SELECT s.id,t.depth+1 FROM authority_scopes s JOIN tree t ON s.container=t.id)"
            + " SELECT "
            + COLUMNS
            + " FROM contained_applications c JOIN projects p ON p.id=c.project_id"
            + " JOIN tree t ON t.id=c.scope_id ORDER BY t.depth DESC,c.id",
        JdbcContainedApplicationRepository::read,
        scope.value());
  }

  /**
   * Deletes the child's project, and the two-hop cascade removes the scope, the principal and this
   * record with it. {@code p.name LIKE 'contained:%'} is in the statement itself, so this cannot
   * reach an ordinary Application's project — and therefore cannot reach its retained revisions —
   * whatever identifier it is given.
   */
  @Override
  public void destroy(UUID id) {
    if (jdbc.update(
            "DELETE FROM projects p USING contained_applications c"
                + " WHERE c.project_id=p.id AND c.id=? AND p.name LIKE 'contained:%'",
            id)
        != 1) throw new CallerFault("No such contained Application");
  }

  @Override
  public void collected(Collection record) {
    jdbc.update(
        "INSERT INTO contained_collections"
            + "(child,scope,container,project,revision,workspace,cause) VALUES(?,?,?,?,?,?,?)"
            + " ON CONFLICT (child) DO NOTHING",
        record.child(),
        record.scope() == null ? null : record.scope().value(),
        record.container() == null ? null : record.container().value(),
        record.project(),
        record.revision(),
        record.workspace().toString(),
        record.cause().name());
  }

  @Override
  public void sourceCollected(UUID child) {
    jdbc.update("UPDATE contained_collections SET source_removed=TRUE WHERE child=?", child);
  }

  private static final String COLLECTION_COLUMNS =
      "child,scope,container,project,revision,workspace,cause,source_removed";

  @Override
  public List<Collection> uncollectedSources() {
    return jdbc.query(
        "SELECT "
            + COLLECTION_COLUMNS
            + " FROM contained_collections WHERE NOT source_removed ORDER BY collected_at",
        JdbcContainedApplicationRepository::collection);
  }

  @Override
  public Optional<Collection> collection(UUID child) {
    return jdbc
        .query(
            "SELECT " + COLLECTION_COLUMNS + " FROM contained_collections WHERE child=?",
            JdbcContainedApplicationRepository::collection,
            child)
        .stream()
        .findFirst();
  }

  /**
   * One recorded containment edge downwards.
   *
   * <p>A swept stage is off this chain already and not by a predicate here: V200's shape constraint
   * makes {@code cause='SWEPT'} imply a null {@code container}, so {@code container=?} cannot match
   * one. {@code cause='DESTROYED'} is kept because it states the intent in the statement; a {@code
   * scope IS NOT NULL} test was removed, because the same constraint makes {@code
   * cause='DESTROYED'} imply it — it guarded nothing, and its comment credited it with work these
   * two columns already do.
   */
  @Override
  public List<Collection> collectedUnder(ScopeId container) {
    return jdbc.query(
        "SELECT "
            + COLLECTION_COLUMNS
            + " FROM contained_collections WHERE container=? AND cause='DESTROYED' ORDER BY child",
        JdbcContainedApplicationRepository::collection,
        container.value());
  }

  private static Collection collection(java.sql.ResultSet row, int index)
      throws java.sql.SQLException {
    return new Collection(
        row.getObject("child", UUID.class),
        scope(row, "scope"),
        scope(row, "container"),
        row.getString("project"),
        row.getObject("revision", UUID.class),
        java.nio.file.Path.of(row.getString("workspace")),
        Cause.valueOf(row.getString("cause")),
        row.getBoolean("source_removed"));
  }

  private static ScopeId scope(java.sql.ResultSet row, String column) throws java.sql.SQLException {
    UUID value = row.getObject(column, UUID.class);
    return value == null ? null : new ScopeId(value);
  }

  @Override
  public boolean committed(String project) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM contained_applications c JOIN projects p ON p.id=c.project_id"
                + " WHERE p.name=?",
            Integer.class,
            project);
    return count != null && count > 0;
  }

  /**
   * Live contained Applications anywhere beneath this project's authority scope. The project is
   * resolved by name rather than by scope id because its caller is activation, which knows the
   * project row it has just locked; an ordinary project with no children answers zero.
   */
  @Override
  public int liveChildrenOfProject(String project) {
    Integer count =
        jdbc.queryForObject(
            "WITH RECURSIVE subtree AS ("
                + "  SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id"
                + "    WHERE p.name=?"
                + "  UNION ALL"
                + "  SELECT s.id FROM authority_scopes s JOIN subtree t ON s.container=t.id)"
                + " SELECT count(*) FROM contained_applications c"
                + " JOIN subtree t ON t.id=c.scope_id WHERE c.live",
            Integer.class,
            project);
    return count == null ? 0 : count;
  }

  /**
   * The container's own project row, locked. {@code FOR UPDATE OF p} names the projects row
   * explicitly so the join to {@code authority_scopes} is a read and not a second lock, and this is
   * the same row {@code JdbcApplicationDeploymentStore.commit} locks before it asks the fence.
   */
  @Override
  public void lockContainer(ScopeId container) {
    jdbc.query(
        "SELECT p.id FROM projects p JOIN authority_scopes s ON s.project_id=p.id"
            + " WHERE s.id=? FOR UPDATE OF p",
        (row, index) -> row.getLong(1),
        container.value());
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
