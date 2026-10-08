package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** A topic lock makes the retained page, expiry boundary and offsets one coherent snapshot. */
public final class JdbcRelayLogRepository implements RelayLogRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  public JdbcRelayLogRepository(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
  }

  public java.util.List<Relay.Topic> topics(Relay.Scope scope, int limit) {
    RelayValues.limit(limit, 100);
    String key = RelayScopeCodec.write(new Relay.TopicKey(scope, "inspection"));
    return java.util.List.copyOf(
        jdbc.query(
            "SELECT * FROM relay_topics WHERE scope_key=? ORDER BY name LIMIT ?",
            (row, index) -> topic(row),
            key,
            limit));
  }

  @Override
  public Head latest(Relay.TopicKey key) {
    Objects.requireNonNull(key);
    String scope = RelayScopeCodec.write(key);
    return work.inTransaction(
        () -> {
          var topic =
              jdbc
                  .query(
                      "SELECT * FROM relay_topics WHERE scope_key=? AND name=? FOR SHARE",
                      (row, index) -> topic(row),
                      scope,
                      key.name())
                  .stream()
                  .findFirst();
          if (topic.isEmpty()) return new Head(0, java.util.Optional.empty());
          var publication =
              jdbc
                  .query(
                      "SELECT * FROM relay_publications WHERE scope_key=? AND topic=? ORDER BY position DESC LIMIT 1",
                      (row, index) -> publication(row, topic.get()),
                      scope,
                      key.name())
                  .stream()
                  .findFirst();
          return new Head(topic.get().lastPosition(), publication);
        });
  }

  public Snapshot read(Relay.TopicKey key, long after, int limit, String account) {
    Objects.requireNonNull(key);
    RelayValues.limit(limit, 100);
    RelayValues.identity(account, "account");
    if (after < 0) throw new IllegalArgumentException("negative Relay position");
    String scope = RelayScopeCodec.write(key);
    return work.inTransaction(
        () -> {
          var topic =
              jdbc
                  .query(
                      "SELECT * FROM relay_topics WHERE scope_key=? AND name=? FOR SHARE",
                      (row, index) -> topic(row),
                      scope,
                      key.name())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Relay topic is unavailable"));
          if (after > topic.lastPosition())
            throw new IllegalArgumentException("Relay position is beyond the topic log");
          var events =
              jdbc.query(
                  "SELECT * FROM relay_publications WHERE scope_key=? AND topic=? AND position>? ORDER BY position LIMIT ?",
                  (row, index) -> publication(row, topic),
                  scope,
                  key.name(),
                  after,
                  // Payloads may occupy 256 KiB each. A partial forward page keeps the entire
                  // response, including bounded subscriber/branch metadata, below frame limits.
                  Math.min(limit, 16));
          var subscribers =
              jdbc.query(
                  "SELECT * FROM relay_subscriptions WHERE scope_key=? AND topic=? ORDER BY subscriber LIMIT 1000",
                  (row, index) ->
                      new Relay.Subscription(
                          new Relay.SubscriptionKey(key, row.getString("subscriber")),
                          row.getLong("seen_through"),
                          time(row, "seen_at"),
                          row.getObject("generation", UUID.class)),
                  scope,
                  key.name());
          // Branches are a separate bounded newest-first window, including input already expired
          // from the topic. Never expose another account's private job/conversation receipt.
          var branches =
              jdbc.query(
                  """
          SELECT d.*,a.routing_hash,e.account AS execution_account,e.conversation_id,p.name AS conversation_project
          FROM relay_deliveries d JOIN relay_admissions a USING(scope_key,topic,subscriber,publication_position)
          LEFT JOIN relay_executions e ON e.request_id=d.id
          LEFT JOIN conversations c ON c.id=e.conversation_id
          LEFT JOIN projects p ON p.id=c.project_id
          WHERE d.scope_key=? AND d.topic=? ORDER BY d.publication_position DESC,d.branch_index LIMIT ?
          """,
                  (row, index) -> {
                    boolean visible =
                        account.equals(row.getString("execution_account"))
                            || row.getString("receiver").equals("relay.publish");
                    String namespace = visible ? row.getString("receipt_namespace") : null;
                    return new Branch(
                        row.getObject("id", UUID.class),
                        row.getLong("publication_position"),
                        row.getString("subscriber"),
                        row.getString("branch_name"),
                        row.getString("receiver"),
                        RelayDeliveries.State.valueOf(row.getString("state")),
                        row.getLong("fence"),
                        time(row, "updated_at"),
                        row.getString("failure_code"),
                        namespace == null
                            ? null
                            : new RelayDeliveries.Receipt(namespace, row.getString("receipt_id")),
                        visible && row.getString("conversation_project") != null
                            ? row.getString("conversation_id")
                            : null,
                        visible ? row.getString("conversation_project") : null,
                        row.getString("routing_hash"),
                        row.getString("handler_hash"));
                  },
                  scope,
                  key.name(),
                  limit);
          var recoveries =
              jdbc.query(
                  """
              SELECT DISTINCT ON(subscriber) * FROM relay_native_gap_recoveries
              WHERE scope_key=? AND topic=? ORDER BY subscriber,expired_through DESC LIMIT 1000
              """,
                  (row, index) ->
                      new Recovery(
                          row.getString("subscriber"),
                          row.getLong("expired_through"),
                          row.getLong("pending_inbox"),
                          time(row, "recovered_at")),
                  scope,
                  key.name());
          return new Snapshot(topic, after, events, subscribers, branches, recoveries);
        });
  }

  private static Relay.Topic topic(ResultSet row) throws SQLException {
    return new Relay.Topic(
        new Relay.TopicKey(RelayScopeCodec.read(row.getString("scope_key")), row.getString("name")),
        RelayPayload.Kind.valueOf(row.getString("payload_kind")),
        new Relay.Policy(
            Duration.ofSeconds(row.getLong("retention_seconds")),
            row.getObject("max_records", Long.class)),
        row.getLong("last_position"),
        row.getLong("expired_through"),
        row.getObject("generation", UUID.class));
  }

  private static Relay.Publication publication(ResultSet row, Relay.Topic topic)
      throws SQLException {
    return new Relay.Publication(
        topic.key(),
        row.getLong("position"),
        time(row, "published_at"),
        new Relay.Draft(
            row.getString("event_id"),
            row.getString("publisher"),
            time(row, "occurred_at"),
            row.getString("correlation_id"),
            row.getString("causation_id"),
            RelayPayloadCodec.read(
                topic.kind(), row.getInt("schema_version"), row.getString("payload")),
            RelayCausationCodec.read(row.getString("relay_causation"))));
  }

  private static Instant time(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
