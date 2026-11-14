package io.aeyer.plowshare.server.information;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * PostgreSQL facts for {@link LogReadability}.
 *
 * <p>{@link #tree} walks down from the root with {@code snapshot_cache_log_children} (V258), the
 * exact inverse of {@code snapshot_cache_log_parents} (V257), over the indexes on {@code
 * conversations(parent_id)}, {@code orchestrations(caller_conversation)} (V258), {@code
 * orchestrations(conductor_conversation)} and {@code orchestrations(parent)}; it then reads each
 * log's parents, quarantine row and retained inputs by primary key. The walk reads one log past
 * {@link LogTreeFact#MAX_LOGS} and each log one input past {@link LogTreeFact#MAX_INPUTS}, so an
 * oversized tree is detected without being hydrated further. Both arrays of one log's inputs share
 * one order, the table's key, so they pair by index.
 */
public final class JdbcLogTreeFactRepository implements LogTreeFactRepository {
  private static final String ROOTS = "SELECT snapshot_cache_log_roots(ARRAY[?::text])";

  private static final String TREE =
      """
      WITH RECURSIVE tree(log) AS (
       SELECT ?::text
       UNION SELECT child.log FROM tree t CROSS JOIN LATERAL snapshot_cache_log_children(t.log) child(log)
      ),
      bounded AS MATERIALIZED (SELECT log FROM tree LIMIT ?)
      SELECT b.log,
       ARRAY(SELECT DISTINCT parent.log FROM snapshot_cache_log_parents(b.log) parent(log)
        ORDER BY 1) AS parents,
       EXISTS(SELECT 1 FROM information_quarantined_payloads q WHERE q.payload_id=b.log) AS quarantined,
       ARRAY(SELECT i.revision_id FROM information_job_inputs i WHERE i.job_id=b.log
        ORDER BY i.revision_id LIMIT ?) AS revisions,
       ARRAY(SELECT i.scope FROM information_job_inputs i WHERE i.job_id=b.log
        ORDER BY i.revision_id LIMIT ?) AS scopes,
       ARRAY(SELECT i.project_name FROM information_job_inputs i WHERE i.job_id=b.log
        ORDER BY i.revision_id LIMIT ?) AS projects,
       ARRAY(SELECT i.include_shared FROM information_job_inputs i WHERE i.job_id=b.log
        ORDER BY i.revision_id LIMIT ?) AS shared
      FROM bounded b
      """;

  private final JdbcTemplate jdbc;

  public JdbcLogTreeFactRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public Optional<List<String>> roots(String log) {
    Objects.requireNonNull(log, "log");
    var roots =
        jdbc.query(
            ROOTS,
            row -> {
              if (!row.next()) throw new IllegalStateException("Root walk returned no row");
              Array array = row.getArray(1);
              return array == null ? null : texts(array);
            },
            log);
    if (roots == null || roots.isEmpty()) return Optional.empty();
    return Optional.of(List.copyOf(roots));
  }

  @Override
  public LogTreeFact tree(String root) {
    Objects.requireNonNull(root, "root");
    if (root.length() > LogTreeFact.MAX_LOG_LENGTH) return new LogTreeFact.Oversized(root);
    var logs = new HashMap<String, LogTreeFact.Log>();
    boolean[] oversized = {false};
    int[] inputs = {0};
    int probe = LogTreeFact.MAX_INPUTS + 1;
    jdbc.query(
        TREE,
        (RowCallbackHandler)
            row -> {
              if (oversized[0]) return;
              String log = Objects.requireNonNull(row.getString("log"), "Tree log");
              var parents = texts(row.getArray("parents"));
              var revisions = row.getArray("revisions");
              var selected = selections(revisions, row);
              inputs[0] += selected.size();
              if (logs.size() >= LogTreeFact.MAX_LOGS
                  || inputs[0] > LogTreeFact.MAX_INPUTS
                  || log.length() > LogTreeFact.MAX_LOG_LENGTH
                  || parents.stream().anyMatch(id -> id.length() > LogTreeFact.MAX_LOG_LENGTH)) {
                oversized[0] = true;
                return;
              }
              logs.put(log, new LogTreeFact.Log(parents, row.getBoolean("quarantined"), selected));
            },
        root,
        LogTreeFact.MAX_LOGS + 1,
        probe,
        probe,
        probe,
        probe);
    if (oversized[0]) return new LogTreeFact.Oversized(root);
    return new LogTreeFact.Tree(root, logs);
  }

  /** The four parallel input arrays of one row, paired by index and validated. */
  private static List<LogTreeFact.JobInput> selections(Array revisions, java.sql.ResultSet row)
      throws SQLException {
    var ids = (Object[]) revisions.getArray();
    var scopes = (Object[]) row.getArray("scopes").getArray();
    var projects = (Object[]) row.getArray("projects").getArray();
    var shared = (Object[]) row.getArray("shared").getArray();
    if (ids.length != scopes.length || ids.length != projects.length || ids.length != shared.length)
      throw new IllegalStateException("Retained input arrays disagree");
    var selected = new ArrayList<LogTreeFact.JobInput>(ids.length);
    for (int index = 0; index < ids.length; index++)
      selected.add(
          new LogTreeFact.JobInput(
              (UUID) Objects.requireNonNull(ids[index], "Retained input revision"),
              (String) Objects.requireNonNull(scopes[index], "Retained input scope"),
              Optional.ofNullable((String) projects[index]),
              (Boolean) Objects.requireNonNull(shared[index], "Retained input include_shared")));
    return selected;
  }

  private static List<String> texts(Array array) throws SQLException {
    var values = new ArrayList<String>();
    for (Object value : (Object[]) array.getArray())
      values.add((String) Objects.requireNonNull(value, "Unexpected NULL log"));
    return values;
  }
}
