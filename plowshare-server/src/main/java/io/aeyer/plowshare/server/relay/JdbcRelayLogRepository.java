package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.cache.ProjectionCache;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** A topic lock makes the retained page, expiry boundary and offsets one coherent snapshot. */
public final class JdbcRelayLogRepository implements RelayLogRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork work;
  private final ProjectionCache<List<Relay.Topic>> topicsCache;
  private final ProjectionCache<Snapshot> pagesCache;
  private static final String TOPIC_COLUMNS =
      "scope_key,name,payload_kind,retention_seconds,max_records,last_position,expired_through,generation";
  private static final String EVENT_COLUMNS =
      "position,event_id,publisher,occurred_at,published_at,correlation_id,causation_id,relay_causation::text AS relay_causation,schema_version,payload::text AS payload";

  public JdbcRelayLogRepository(JdbcTemplate jdbc, UnitOfWork work) {
    this(jdbc, work, ProjectionCache.direct(), ProjectionCache.direct());
  }

  /** Inspection-only DTO caches; mutations and execution/catalogue decisions remain direct. */
  public JdbcRelayLogRepository(
      JdbcTemplate jdbc,
      UnitOfWork work,
      ProjectionCache<List<Relay.Topic>> topicsCache,
      ProjectionCache<Snapshot> pagesCache) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.work = Objects.requireNonNull(work);
    this.topicsCache = Objects.requireNonNull(topicsCache);
    this.pagesCache = Objects.requireNonNull(pagesCache);
  }

  public java.util.List<Relay.Topic> topics(Relay.Scope scope, int limit) {
    RelayValues.limit(limit, 100);
    String key = RelayScopeCodec.write(new Relay.TopicKey(scope, "inspection"));
    return topicsCache.read(
        new SnapshotCache.Key("topics", ProjectionCache.fingerprint(key, Integer.toString(limit))),
        topicsCache.maxWeight() > 4096,
        () -> loadTopics(key, limit, (topicsCache.maxWeight() - 4096) / 16),
        () -> loadTopics(key, limit, null),
        values -> 4096L + values.stream().mapToLong(JdbcRelayLogRepository::topicWeight).sum());
  }

  private List<Relay.Topic> loadTopics(String scope, int limit, Long bound) {
    return boundedProjection(
        "SELECT " + TOPIC_COLUMNS + " FROM relay_topics WHERE scope_key=? ORDER BY name LIMIT ?",
        "name",
        bound,
        (row, index) -> topic(row),
        scope,
        limit);
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
                      "SELECT "
                          + TOPIC_COLUMNS
                          + " FROM relay_topics WHERE scope_key=? AND name=? FOR SHARE",
                      (row, index) -> topic(row),
                      scope,
                      key.name())
                  .stream()
                  .findFirst();
          if (topic.isEmpty()) return new Head(0, java.util.Optional.empty());
          var publication =
              jdbc
                  .query(
                      "SELECT "
                          + EVENT_COLUMNS
                          + " FROM relay_publications WHERE scope_key=? AND topic=? ORDER BY position DESC LIMIT 1",
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
    // The complete selection includes the account: execution receipts are account filtered.
    // A warm diagnostic snapshot needs current durable versions, not a cached topic lock. Its
    // authenticated owner still gates every operation before reaching this repository.
    return pagesCache.read(
        new SnapshotCache.Key(
            "page",
            ProjectionCache.fingerprint(
                RelayScopeCodec.write(key),
                key.name(),
                Long.toString(after),
                Integer.toString(limit),
                account)),
        pagesCache.maxWeight() > 32768,
        () -> loadSnapshot(key, after, limit, account, (pagesCache.maxWeight() - 32768) / 64),
        () -> loadSnapshot(key, after, limit, account, null),
        JdbcRelayLogRepository::snapshotWeight);
  }

  private Snapshot loadSnapshot(
      Relay.TopicKey key, long after, int limit, String account, Long bound) {
    String scope = RelayScopeCodec.write(key);
    return work.inTransaction(
        () -> {
          var topic =
              jdbc
                  .query(
                      "SELECT "
                          + TOPIC_COLUMNS
                          + " FROM relay_topics WHERE scope_key=? AND name=? FOR SHARE",
                      (row, index) -> topic(row),
                      scope,
                      key.name())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new IllegalArgumentException("Relay topic is unavailable"));
          if (after > topic.lastPosition())
            throw new IllegalArgumentException("Relay position is beyond the topic log");
          var events =
              boundedProjection(
                  // Apply the byte prefix in SQL, before JDBC materializes/decodes large rows.
                  "SELECT * FROM (SELECT candidates.*, SUM(octet_length(payload::text) + 4096)"
                      + " OVER (ORDER BY position) AS page_bytes FROM (SELECT "
                      + EVENT_COLUMNS
                      + " FROM relay_publications WHERE scope_key=? AND topic=? AND position>?"
                      + " ORDER BY position LIMIT ?) candidates) bounded"
                      + " WHERE page_bytes <= ? ORDER BY position",
                  "position",
                  bound,
                  (row, index) -> publication(row, topic),
                  scope,
                  key.name(),
                  after,
                  // Row-count and byte budgets are independent. The public projection selects
                  // a frame-sized prefix and advances next only through that returned prefix.
                  Math.min(limit, 16),
                  io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES
                      - 1024 * 1024);
          var subscribers =
              boundedProjection(
                  "SELECT subscriber,seen_through,seen_at,generation FROM relay_subscriptions WHERE scope_key=? AND topic=? ORDER BY subscriber LIMIT 1000",
                  "subscriber",
                  bound,
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
              boundedProjection(
                  """
          SELECT d.id,d.publication_position,d.subscriber,d.branch_name,d.receiver,d.state,d.fence,
            d.updated_at,d.failure_code,d.receipt_namespace,d.receipt_id,d.handler_hash,d.branch_index,
            a.routing_hash,e.account AS execution_account,e.conversation_id,p.name AS conversation_project
          FROM relay_deliveries d JOIN relay_admissions a USING(scope_key,topic,subscriber,publication_position)
          LEFT JOIN relay_executions e ON e.request_id=d.id
          LEFT JOIN conversations c ON c.id=e.conversation_id
          LEFT JOIN projects p ON p.id=c.project_id
          WHERE d.scope_key=? AND d.topic=? ORDER BY d.publication_position DESC,d.branch_index,d.subscriber,d.id LIMIT ?
          """,
                  "publication_position DESC,branch_index,subscriber,id",
                  bound,
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
              boundedProjection(
                  """
              SELECT DISTINCT ON(subscriber) subscriber,expired_through,pending_inbox,recovered_at FROM relay_native_gap_recoveries
              WHERE scope_key=? AND topic=? ORDER BY subscriber,expired_through DESC LIMIT 1000
              """,
                  "subscriber",
                  bound,
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

  /**
   * Private persistence projection guard. SQL and ordering are code-owned at the four call sites.
   * The empty/oversize sentinel is checked before mapping any row or payload. A refusal repeats the
   * complete ordinary projection once; cache limits never truncate events or diagnostics.
   */
  private <T> List<T> boundedProjection(
      String sql, String order, Long bound, RowMapper<T> mapper, Object... arguments) {
    if (bound == null) return List.copyOf(jdbc.query(sql, mapper, arguments));
    var args = new ArrayList<Object>(java.util.Arrays.asList(arguments));
    args.add(bound);
    var rows =
        jdbc.query(
            "WITH projected AS MATERIALIZED ("
                + sql
                + "), totals AS (SELECT count(*) AS projection_rows,COALESCE(sum(octet_length(to_jsonb(projected)::text)),0) AS projection_bytes FROM projected) "
                + "SELECT totals.projection_rows,totals.projection_bytes,projected.* FROM totals LEFT JOIN projected ON totals.projection_bytes<=? ORDER BY "
                + order,
            (row, index) -> {
              if (row.getLong("projection_bytes") > bound) throw new ProjectionCache.Bypass();
              if (row.getLong("projection_rows") == 0) return Optional.<T>empty();
              return Optional.of(Objects.requireNonNull(mapper.mapRow(row, index)));
            },
            args.toArray());
    return rows.stream().flatMap(Optional::stream).toList();
  }

  private static long textWeight(String... values) {
    long weight = 0;
    for (var value : values) if (value != null) weight += 64L + 4L * value.length();
    return weight;
  }

  private static long topicWeight(Relay.Topic topic) {
    return 512 + textWeight(RelayScopeCodec.write(topic.key()), topic.key().name());
  }

  private static long snapshotWeight(Snapshot value) {
    long weight = 4096 + topicWeight(value.topic());
    for (var event : value.events()) {
      var draft = event.event();
      weight +=
          1024
              + textWeight(
                  draft.eventId(), draft.publisher(), draft.correlationId(), draft.causationId());
      if (draft.causation() != null)
        weight += textWeight(draft.causation().rootId(), draft.causation().parentId());
      weight +=
          switch (draft.payload()) {
            case RelayPayload.Text text -> textWeight(text.text());
            case RelayPayload.Lifecycle change ->
                textWeight(
                    change.source(),
                    change.subject(),
                    change.state(),
                    change.context(),
                    change.related());
            case RelayPayload.ScheduleDue due -> textWeight(due.schedule(), due.emits());
            case RelayPayload.WakeRequested wake -> textWeight(wake.firing(), wake.target());
            case RelayPayload.Empty ignored -> 0;
          };
    }
    for (var sub : value.subscribers()) weight += 256 + textWeight(sub.key().subscriber());
    for (var branch : value.branches())
      weight +=
          512
              + textWeight(
                  branch.subscriber(),
                  branch.name(),
                  branch.receiver(),
                  branch.failure(),
                  branch.conversation(),
                  branch.conversationProject(),
                  branch.routingHash(),
                  branch.handlerHash(),
                  branch.receipt() == null ? null : branch.receipt().namespace(),
                  branch.receipt() == null ? null : branch.receipt().id());
    for (var recovery : value.recoveries()) weight += 256 + textWeight(recovery.subscriber());
    return weight;
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
