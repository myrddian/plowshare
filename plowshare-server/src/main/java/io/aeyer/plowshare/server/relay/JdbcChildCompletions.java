package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.orchestrations.ChildCompletions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Indexed owning-row recovery; never reads report text or changes orchestration state. */
public final class JdbcChildCompletions implements ChildCompletions {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final RelayConsumerRepository consumers;

  public JdbcChildCompletions(
      JdbcTemplate jdbc, UnitOfWork work, RelayConsumerRepository consumers) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
    this.consumers = Objects.requireNonNull(consumers);
  }

  @Override
  public List<Long> ready(long after, int limit) {
    bounded(limit);
    if (after < -1) throw new IllegalArgumentException("Invalid completion scope cursor");
    return jdbc.query(
        """
        SELECT project FROM (
          SELECT DISTINCT COALESCE(c.project_id,0) AS project
          FROM orchestrations o JOIN conversations c ON c.id=o.conductor_conversation
          WHERE o.parent IS NOT NULL AND o.ended_at IS NOT NULL AND o.result_delivered_at IS NULL
          UNION
          SELECT CASE t.scope_key WHEN 'system' THEN 0 ELSE substring(t.scope_key from 9)::bigint END
          FROM relay_topics t LEFT JOIN relay_subscriptions s ON s.scope_key=t.scope_key
            AND s.topic=t.name AND s.subscriber='builtin.orchestrations.children'
          WHERE t.name='orchestration.child.completed'
            AND (s.subscriber IS NULL OR s.seen_through<t.last_position)
        ) candidates WHERE project>? ORDER BY project LIMIT ?
        """,
        (row, index) -> row.getLong(1),
        after,
        limit);
  }

  @Override
  public List<Completion> pending(RelayConsumerRepository.Lease lease, int limit) {
    bounded(limit);
    var topic = lease.subscription().topic();
    if (!topic.name().equals(TOPIC) || !lease.subscription().subscriber().equals(GROUP))
      throw new IllegalArgumentException("Foreign completion lease");
    long project = topic.scope() instanceof Relay.ProjectScope scope ? scope.projectId() : 0;
    return work.inTransaction(
        () -> {
          consumers.requireOwned(lease);
          String key = RelayScopeCodec.write(topic);
          jdbc.update(
              "INSERT INTO orchestration_completion_recovery(scope_key) VALUES(?) ON CONFLICT DO NOTHING",
              key);
          record Cursor(String after, String through) {}
          var cursor =
              Objects.requireNonNull(
                  jdbc.queryForObject(
                      "SELECT after_child,through_child FROM orchestration_completion_recovery WHERE scope_key=? FOR UPDATE",
                      (row, index) -> new Cursor(row.getString(1), row.getString(2)),
                      key));
          String after = cursor.after();
          String through = cursor.through();
          if (through == null)
            through =
                jdbc
                    .query(
                        """
                SELECT o.id FROM orchestrations o JOIN conversations c ON c.id=o.conductor_conversation
                WHERE COALESCE(c.project_id,0)=? AND o.parent IS NOT NULL AND o.ended_at IS NOT NULL
                  AND o.result_delivered_at IS NULL ORDER BY o.id DESC LIMIT 1
                """,
                        (row, index) -> row.getString(1),
                        project)
                    .stream()
                    .findFirst()
                    .orElse(null);
          var result =
              through == null
                  ? List.<Completion>of()
                  : jdbc.query(
                      """
          SELECT o.id,o.parent,o.ended_at FROM orchestrations o
          JOIN conversations c ON c.id=o.conductor_conversation
          WHERE COALESCE(c.project_id,0)=? AND o.parent IS NOT NULL AND o.ended_at IS NOT NULL
            AND o.result_delivered_at IS NULL AND (?::text IS NULL OR o.id>?) AND o.id<=?
            AND NOT EXISTS(SELECT 1 FROM relay_source_events source
              WHERE source.topic='orchestration.child.completed' AND source.subject=o.id AND source.occurred_at=o.ended_at)
            ORDER BY o.id LIMIT ?
          """,
                      (row, index) ->
                          new Completion(
                              project,
                              row.getString("id"),
                              row.getString("parent"),
                              row.getTimestamp("ended_at").toInstant()),
                      project,
                      after,
                      after,
                      through,
                      limit);
          // The cycle ceiling is fixed before effects. Even continuous new arrivals cannot
          // extend this cycle forever and starve an older report whose parent was busy.
          if (jdbc.update(
                  "UPDATE orchestration_completion_recovery SET after_child=?,through_child=? WHERE scope_key=?",
                  result.size() < limit ? null : result.getLast().child(),
                  result.size() < limit ? null : through,
                  key)
              != 1) throw new IllegalStateException("Completion recovery cursor disappeared");
          consumers.requireOwned(lease);
          return result;
        });
  }

  @Override
  public List<Completion> current(RelayConsumerRepository.Lease lease, List<Completion> notices) {
    notices = List.copyOf(notices);
    if (notices.isEmpty()) return List.of();
    bounded(notices.size());
    var topic = lease.subscription().topic();
    if (!topic.name().equals(TOPIC) || !lease.subscription().subscriber().equals(GROUP))
      throw new IllegalArgumentException("Foreign completion lease");
    long project = topic.scope() instanceof Relay.ProjectScope scope ? scope.projectId() : 0;
    if (notices.stream().anyMatch(notice -> notice.project() != project))
      throw new IllegalArgumentException("Foreign completion scope");
    var requested = notices;
    return work.inTransaction(
        () -> {
          consumers.requireOwned(lease);
          var ids = requested.stream().map(Completion::child).distinct().toList();
          var parameters = new ArrayList<Object>();
          parameters.add(project);
          parameters.addAll(ids);
          // Only the placeholder count is dynamic. Every identity remains a bound value; no N+1
          // metadata/authority lookups precede this batch. Preserve the publication order below.
          var valid =
              new HashSet<>(
                  jdbc.query(
                      """
          SELECT o.id,o.parent,o.ended_at FROM orchestrations o
          JOIN conversations c ON c.id=o.conductor_conversation
          WHERE COALESCE(c.project_id,0)=? AND o.parent IS NOT NULL AND o.ended_at IS NOT NULL
            AND o.result_delivered_at IS NULL AND o.id IN (
          """
                          + String.join(",", Collections.nCopies(ids.size(), "?"))
                          + ")",
                      (row, index) ->
                          new Completion(
                              project,
                              row.getString("id"),
                              row.getString("parent"),
                              row.getTimestamp("ended_at").toInstant()),
                      parameters.toArray()));
          consumers.requireOwned(lease);
          return requested.stream().filter(valid::contains).distinct().toList();
        });
  }

  private static void bounded(int limit) {
    if (limit < 1 || limit > 256)
      throw new IllegalArgumentException("Completion limit must be 1 through 256");
  }
}
