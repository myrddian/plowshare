package io.aeyer.plowshare.server.files;

import com.fasterxml.jackson.annotation.JsonProperty;
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

  private record SourceObservation(
      @JsonProperty("source_key") String sourceKey,
      String provider,
      String root,
      String path,
      @JsonProperty("relative_path") String relativePath,
      @JsonProperty("source_hash") String sourceHash,
      @JsonProperty("source_bytes") long sourceBytes,
      String language,
      @JsonProperty("outline_status") String outlineStatus,
      @JsonProperty("observed_at") String observedAt,
      @JsonProperty("revision_id") UUID revisionId) {
    static SourceObservation from(WorkspaceCodeMap.Entry file) {
      return new SourceObservation(
          FileContents.sha256(file.key().getBytes(StandardCharsets.UTF_8)),
          file.provider(),
          file.root().toString(),
          file.path().toString(),
          file.root().relativize(file.path()).toString(),
          file.fingerprint().sha256(),
          file.fingerprint().size(),
          file.language(),
          file.outline().status(),
          file.observedAt().toString(),
          file.revision());
    }
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
          // The winning workspace update holds this generation's row lock until commit. Apply
          // the bounded manifest as one set: retain unchanged facts, update changed sources, and
          // remove only absent keys. checked_at above records every scan; observed_at below records
          // the accepted source observation when its facts change, not a reason to rewrite a row.
          jdbc.update(
              """
              WITH incoming AS MATERIALIZED (
                SELECT * FROM jsonb_to_recordset(?::jsonb) AS source(
                  source_key text,provider text,root text,path text,relative_path text,
                  source_hash text,source_bytes bigint,language text,outline_status text,
                  observed_at timestamptz,revision_id uuid)
              ), removed AS (
                DELETE FROM code_workspace_sources retained WHERE workspace_id=?
                  AND NOT EXISTS(SELECT 1 FROM incoming WHERE incoming.source_key=retained.source_key)
              )
              INSERT INTO code_workspace_sources AS retained(workspace_id,source_key,provider,root,path,
                relative_path,source_hash,source_bytes,language,outline_status,observed_at,revision_id)
              SELECT ?,source_key,provider,root,path,relative_path,source_hash,source_bytes,language,
                outline_status,observed_at,revision_id FROM incoming
              ON CONFLICT(workspace_id,source_key) DO UPDATE SET provider=EXCLUDED.provider,
                root=EXCLUDED.root,path=EXCLUDED.path,relative_path=EXCLUDED.relative_path,
                source_hash=EXCLUDED.source_hash,source_bytes=EXCLUDED.source_bytes,
                language=EXCLUDED.language,outline_status=EXCLUDED.outline_status,
                observed_at=EXCLUDED.observed_at,revision_id=EXCLUDED.revision_id
              WHERE ROW(retained.provider,retained.root,retained.path,retained.relative_path,
                retained.source_hash,retained.source_bytes,retained.language,retained.outline_status,
                retained.revision_id) IS DISTINCT FROM ROW(EXCLUDED.provider,EXCLUDED.root,
                EXCLUDED.path,EXCLUDED.relative_path,EXCLUDED.source_hash,EXCLUDED.source_bytes,
                EXCLUDED.language,EXCLUDED.outline_status,EXCLUDED.revision_id)
              """,
              json(view.files().stream().map(SourceObservation::from).toList()),
              ticket.workspace(),
              ticket.workspace());
          return true;
        });
  }

  public void unavailable(CodeMapObservations.Ticket ticket, String reason) {
    publish(
        ticket,
        new WorkspaceCodeMap.View(
            "unavailable", ticket.generation(), "", clock.instant(), List.of(reason), List.of()));
  }

  @Override
  public void defer(CodeMapObservations.Ticket ticket) {
    jdbc.update(
        "UPDATE code_workspaces SET state='dirty',lease_until=NULL,next_poll=? WHERE id=? AND generation=? AND state='checking'",
        at(clock.instant().plusSeconds(2)),
        ticket.workspace(),
        ticket.generation());
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

  @Override
  public Map<String, UUID> indexed(Scope scope, List<IndexSource> sources, String parser) {
    Objects.requireNonNull(scope);
    Objects.requireNonNull(parser);
    if (sources == null || sources.size() > 100)
      throw new IllegalArgumentException("Code index page must have at most 100 sources");
    if (sources.isEmpty()) return Map.of();
    var arguments = new ArrayList<Object>();
    var originals = new HashMap<String, String>();
    for (var source : sources) {
      String key = sourceKey(source.key());
      if (originals.put(key, source.key()) != null)
        throw new IllegalArgumentException("Duplicate code source");
      arguments.add(key);
      arguments.add(source.source().sha256());
    }
    arguments.add(scope.id());
    arguments.add(parser);
    var result = new HashMap<String, UUID>();
    jdbc.query(
        "WITH requested(source_key,source_hash) AS (VALUES "
            + String.join(",", Collections.nCopies(sources.size(), "(?::text,?::text)"))
            + ") SELECT i.source_key,i.revision_id FROM requested r JOIN code_workspace_indexes i"
            + " ON i.source_key=r.source_key AND i.source_hash=r.source_hash WHERE i.workspace_id=? AND i.parser_version=?",
        (org.springframework.jdbc.core.RowCallbackHandler)
            row -> result.put(originals.get(row.getString(1)), row.getObject(2, UUID.class)),
        arguments.toArray());
    return Map.copyOf(result);
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
