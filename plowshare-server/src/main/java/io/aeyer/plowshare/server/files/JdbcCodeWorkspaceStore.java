package io.aeyer.plowshare.server.files;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.CodeTrackingStatus;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable path/hash manifest, bounded subscriptions and generation-fenced scan leases. */
public final class JdbcCodeWorkspaceStore implements CodeWorkspaceStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final Clock clock;
  private final Duration interval;
  private final int capacity;

  public JdbcCodeWorkspaceStore(
      JdbcTemplate jdbc, UnitOfWork work, Clock clock, Duration interval, int capacity) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
    this.clock = Objects.requireNonNull(clock);
    if (interval.compareTo(Duration.ofSeconds(5)) < 0
        || interval.compareTo(Duration.ofHours(1)) > 0
        || capacity < 1
        || capacity > 256)
      throw new IllegalArgumentException(
          "code tracking needs a 5s..1h interval and 1..256 registrations");
    this.interval = interval;
    this.capacity = capacity;
  }

  public Duration interval() {
    return interval;
  }

  /** A foreground scan enrolls or updates only its runtime-bound account/agent scope. */
  public CodeMapObservations.Ticket begin(Scope scope, String pattern) {
    WorkspaceValues.path(pattern, "pattern");
    Objects.requireNonNull(scope, "scope");
    if (pattern.length() > 512)
      throw new IllegalArgumentException("code tracking pattern exceeds its bound");
    return work.inTransaction(
        () -> {
          // Serialize admission so concurrent new scopes cannot exceed the global bound.
          jdbc.execute("SELECT pg_advisory_xact_lock(914031)");
          Long project =
              scope.home().isGlobal()
                  ? null
                  : jdbc.queryForObject(
                      "SELECT id FROM projects WHERE name = ?", Long.class, scope.home().project());
          if (jdbc.queryForObject(
                      "SELECT count(*) FROM code_workspaces WHERE id = ?",
                      Integer.class,
                      scope.id())
                  == 0
              && (jdbc.queryForObject("SELECT count(*) FROM code_workspaces", Integer.class)
                      >= capacity
                  || jdbc.queryForObject(
                          "SELECT count(*) FROM code_workspaces WHERE owner_handle = ?",
                          Integer.class,
                          scope.owner())
                      >= Math.min(8, capacity))) throw new CapacityReached();
          var now = clock.instant();
          jdbc.update(
              """
                    INSERT INTO code_workspaces(id, project_id, owner_handle, agent_name, session_id, pattern, state, next_poll)
                    VALUES (?, ?, ?, ?, ?, ?, 'dirty', ?)
                    ON CONFLICT (id) DO UPDATE SET session_id = EXCLUDED.session_id
                    """,
              scope.id(),
              project,
              scope.owner(),
              scope.agent(),
              scope.session(),
              pattern,
              at(now));
          Long generation =
              jdbc.queryForObject(
                  """
                    UPDATE code_workspaces SET pattern = ?, generation = generation + 1, state = 'checking',
                        issues = '[]'::jsonb, lease_until = ?, next_poll = ? WHERE id = ? RETURNING generation
                    """,
                  Long.class,
                  pattern,
                  at(now.plusSeconds(120)),
                  at(now.plus(interval)),
                  scope.id());
          return new CodeMapObservations.Ticket(scope.id(), generation);
        });
  }

  /** Lease one due scope; two servers/workers cannot claim the same generation. */
  public Optional<Scan> claim() {
    return work.inTransaction(
        () -> {
          var now = clock.instant();
          jdbc.update("DELETE FROM code_workspace_mutations WHERE expires_at <= ?", at(now));
          var rows =
              jdbc.query(
                  """
                    SELECT w.id, p.name, w.owner_handle, w.agent_name, w.session_id, w.pattern
                    FROM code_workspaces w LEFT JOIN projects p ON p.id = w.project_id
                    WHERE w.next_poll <= ? AND (w.lease_until IS NULL OR w.lease_until <= ?)
                    AND NOT EXISTS (SELECT 1 FROM code_workspace_mutations m WHERE m.expires_at > ?
                        AND ((w.project_id IS NOT NULL AND m.project_id = w.project_id)
                        OR (w.project_id IS NULL AND m.project_id IS NULL AND m.owner_handle = w.owner_handle
                            AND m.session_id IS NOT DISTINCT FROM w.session_id)))
                    ORDER BY w.next_poll, w.id LIMIT 1 FOR UPDATE OF w SKIP LOCKED
                    """,
                  (rs, ordinal) ->
                      new Scan(
                          new CodeMapObservations.Ticket(rs.getString(1), 0),
                          new Scope(
                              rs.getString(2) == null ? Home.global() : Home.of(rs.getString(2)),
                              rs.getString(3),
                              rs.getString(4),
                              rs.getString(5)),
                          rs.getString(6)),
                  at(now),
                  at(now),
                  at(now));
          if (rows.isEmpty()) return Optional.empty();
          var selected = rows.getFirst();
          Long generation =
              jdbc.queryForObject(
                  """
                    UPDATE code_workspaces SET generation = generation + 1, state = 'checking', lease_until = ?, next_poll = ?
                    WHERE id = ? RETURNING generation
                    """,
                  Long.class,
                  at(now.plusSeconds(120)),
                  at(now.plus(interval)),
                  selected.ticket().workspace());
          return Optional.of(
              new Scan(
                  new CodeMapObservations.Ticket(selected.ticket().workspace(), generation),
                  selected.scope(),
                  selected.pattern()));
        });
  }

  /** Atomically replace the manifest only if this is still the most recent scan. */
  public boolean publish(CodeMapObservations.Ticket ticket, WorkspaceCodeMap.View view) {
    return work.inTransaction(
        () -> {
          int won =
              jdbc.update(
                  """
                    UPDATE code_workspaces w SET state = ?, issues = ?::jsonb, checked_at = ?, lease_until = NULL,
                        next_poll = ? WHERE id = ? AND generation = ? AND state = 'checking'
                        AND NOT EXISTS (SELECT 1 FROM code_workspace_mutations m WHERE m.expires_at > ?
                            AND ((w.project_id IS NOT NULL AND m.project_id = w.project_id)
                            OR (w.project_id IS NULL AND m.project_id IS NULL AND m.owner_handle = w.owner_handle
                                AND m.session_id IS NOT DISTINCT FROM w.session_id)))
                    """,
                  view.state(),
                  json(view.issues()),
                  at(view.checkedAt() == null ? clock.instant() : view.checkedAt()),
                  at(clock.instant().plus(interval)),
                  ticket.workspace(),
                  ticket.generation(),
                  at(clock.instant()));
          if (won == 0) return false;
          jdbc.update(
              "DELETE FROM code_workspace_sources WHERE workspace_id = ?", ticket.workspace());
          jdbc.batchUpdate(
              """
                    INSERT INTO code_workspace_sources(workspace_id, source_key, provider, root, path, relative_path,
                        source_hash, source_bytes, language, outline_status, observed_at, revision_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
              view.files(),
              100,
              (statement, file) -> {
                statement.setString(1, ticket.workspace());
                statement.setString(
                    2, FileContents.sha256(file.key().getBytes(StandardCharsets.UTF_8)));
                statement.setString(3, file.provider());
                statement.setString(4, file.root().toString());
                statement.setString(5, file.path().toString());
                statement.setString(6, file.root().relativize(file.path()).toString());
                statement.setString(7, file.fingerprint().sha256());
                statement.setLong(8, file.fingerprint().size());
                statement.setString(9, file.language());
                statement.setString(10, file.outline().status());
                statement.setTimestamp(11, at(file.observedAt()));
                statement.setObject(12, file.revision());
              });
          return true;
        });
  }

  public void unavailable(CodeMapObservations.Ticket ticket, String reason) {
    publish(
        ticket,
        new WorkspaceCodeMap.View(
            "unavailable", ticket.generation(), "", clock.instant(), List.of(reason), List.of()));
  }

  /** Writers hold a bounded crash-recovery lease. The token is never executable work. */
  public String mutationStarted(Scope scope) {
    return work.inTransaction(
        () -> {
          Long project =
              scope.home().isGlobal()
                  ? null
                  : jdbc.queryForObject(
                      "SELECT id FROM projects WHERE name = ?", Long.class, scope.home().project());
          String token = UUID.randomUUID().toString();
          jdbc.update(
              "INSERT INTO code_workspace_mutations(token, project_id, owner_handle, session_id, expires_at) VALUES (?, ?, ?, ?, ?)",
              token,
              project,
              scope.owner(),
              scope.session(),
              at(clock.instant().plus(Duration.ofHours(2))));
          invalidate(scope);
          return token;
        });
  }

  public void mutationFinished(Scope scope, String token) {
    work.inTransaction(
        () -> {
          jdbc.update("DELETE FROM code_workspace_mutations WHERE token = ?", token);
          invalidate(scope);
          return null;
        });
  }

  /**
   * Any writer in a project dirties all its projections; global files stay account/session scoped.
   */
  public void invalidate(Scope scope) {
    if (scope.home().isGlobal())
      jdbc.update(
          """
                UPDATE code_workspaces SET generation = generation + 1, state = 'dirty', issues = '["workspace_changed"]'::jsonb, lease_until = NULL, next_poll = ?
                WHERE project_id IS NULL AND owner_handle = ? AND session_id IS NOT DISTINCT FROM ?
                """,
          at(clock.instant()),
          scope.owner(),
          scope.session());
    else
      jdbc.update(
          """
                UPDATE code_workspaces SET generation = generation + 1, state = 'dirty', issues = '["workspace_changed"]'::jsonb, lease_until = NULL, next_poll = ?
                WHERE project_id = (SELECT id FROM projects WHERE name = ?)
                """,
          at(clock.instant()),
          scope.home().project());
  }

  public CodeTrackingStatus status(Scope scope) {
    var rows =
        jdbc.query(
            "SELECT state, generation, checked_at, next_poll, issues FROM code_workspaces WHERE id = ?",
            (rs, n) -> {
              try {
                var node = JSON.readTree(rs.getString(5));
                if (node == null || !node.isArray() || node.size() > 10000)
                  throw new IllegalArgumentException("tracking issues must be a bounded list");
                var issues = new ArrayList<String>();
                for (var issue : node) {
                  if (!issue.isTextual())
                    throw new IllegalArgumentException("tracking issue must be text");
                  issues.add(issue.textValue());
                }
                return new CodeTrackingStatus(
                    rs.getString(1),
                    null,
                    scope.id(),
                    rs.getLong(2),
                    rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4).toInstant(),
                    interval.toSeconds(),
                    issues);
              } catch (java.io.IOException | IllegalArgumentException malformed) {
                throw new java.sql.SQLException("invalid persisted tracking status", malformed);
              }
            },
            scope.id());
    return rows.isEmpty() ? CodeTrackingStatus.state("inactive") : rows.getFirst();
  }

  public void forget(Scope scope) {
    jdbc.update("DELETE FROM code_workspaces WHERE id = ?", scope.id());
  }

  public UUID indexed(Scope scope, String key, String hash, String parser) {
    var rows =
        jdbc.queryForList(
            "SELECT revision_id FROM code_workspace_indexes WHERE workspace_id=? AND source_key=? AND source_hash=? AND parser_version=?",
            UUID.class,
            scope.id(),
            sourceKey(key),
            hash,
            parser);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void index(Scope scope, String key, String hash, String parser, UUID revision) {
    jdbc.update(
        "INSERT INTO code_workspace_indexes(workspace_id,source_key,source_hash,parser_version,revision_id)"
            + " SELECT id,?,?,?,? FROM code_workspaces WHERE id=? ON CONFLICT (workspace_id,source_key,source_hash,parser_version) DO NOTHING",
        sourceKey(key),
        hash,
        parser,
        revision,
        scope.id());
  }

  public static String sourceKey(String key) {
    return WorkspaceValues.sourceKey(key);
  }

  private static Timestamp at(Instant instant) {
    return Timestamp.from(instant);
  }

  private static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("invalid code tracking metadata", invalid);
    }
  }
}
