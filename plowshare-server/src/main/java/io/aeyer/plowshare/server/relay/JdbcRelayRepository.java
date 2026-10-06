package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL broker log. Topic locks serialize position allocation, policy changes, cursor writes
 * and prefix deletion. Shared topic locks give reads coherent cursor/log snapshots at READ
 * COMMITTED. Transactions join the publisher's domain transaction where supplied, so rollback
 * removes both the append and its allocated position. No effects or notification callbacks run
 * under these locks.
 */
public final class JdbcRelayRepository implements RelayRepository {
  private static final String TOPIC_COLUMNS =
      "scope_key, name, payload_kind, retention_seconds, max_records, last_position, expired_through, generation";
  private static final String PUBLICATION_COLUMNS =
      "position, event_id, publisher, occurred_at, published_at, correlation_id, causation_id, relay_causation::text, schema_version, payload::text";

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;

  public JdbcRelayRepository(JdbcTemplate jdbc, UnitOfWork transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public Relay.Topic configureTopic(
      Relay.TopicKey key, RelayPayload.Kind kind, Relay.Policy policy) {
    return register(key, kind, policy, true);
  }

  @Override
  public Relay.Topic registerTopic(
      Relay.TopicKey key, RelayPayload.Kind kind, Relay.Policy initialPolicy) {
    return register(key, kind, initialPolicy, false);
  }

  private Relay.Topic register(
      Relay.TopicKey key, RelayPayload.Kind kind, Relay.Policy policy, boolean replacePolicy) {
    var requested = new Relay.Topic(key, kind, policy, 0, 0);
    return transactions.inTransaction(
        () -> {
          jdbc.update(
              """
          INSERT INTO relay_topics(scope_key,project_id,name,payload_kind,retention_seconds,max_records)
          VALUES(?,?,?,?,?,?) ON CONFLICT(scope_key,name) DO NOTHING
          """,
              RelayScopeCodec.write(key),
              RelayScopeCodec.project(key),
              key.name(),
              requested.kind().name(),
              policy.retention().getSeconds(),
              policy.maxRecords());
          Relay.Topic existing = topic(key, true);
          if (existing.kind() != kind)
            throw new IllegalArgumentException(
                "an existing Relay topic cannot change payload family");
          if (!replacePolicy) return existing;
          changed(
              jdbc.update(
                  """
          UPDATE relay_topics SET retention_seconds=?,max_records=? WHERE scope_key=? AND name=?
          """,
                  policy.retention().getSeconds(),
                  policy.maxRecords(),
                  RelayScopeCodec.write(key),
                  key.name()));
          return new Relay.Topic(
              key,
              kind,
              policy,
              existing.lastPosition(),
              existing.expiredThrough(),
              existing.generation());
        });
  }

  @Override
  public Relay.Publication append(Relay.TopicKey key, Relay.Draft draft, Instant publishedAt) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(draft, "draft");
    Instant admittedAt = RelayValues.time(publishedAt);
    String payload = RelayPayloadCodec.write(draft.payload());
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key, true);
          if (topic.kind() != draft.payload().kind())
            throw new IllegalArgumentException("Relay payload differs from the topic family");
          var retained =
              jdbc.query(
                  "SELECT "
                      + PUBLICATION_COLUMNS
                      + " FROM relay_publications WHERE scope_key=? AND topic=? AND event_id=?",
                  (row, index) -> publication(row, topic),
                  RelayScopeCodec.write(key),
                  key.name(),
                  draft.eventId());
          if (!retained.isEmpty()) {
            Relay.Publication original = retained.getFirst();
            if (!original.event().equals(draft))
              throw new IllegalArgumentException("Relay event ID conflicts with retained content");
            return original;
          }
          long position = Math.addExact(topic.lastPosition(), 1);
          changed(
              jdbc.update(
                  "UPDATE relay_topics SET last_position=? WHERE scope_key=? AND name=?",
                  position,
                  RelayScopeCodec.write(key),
                  key.name()));
          changed(
              jdbc.update(
                  """
          INSERT INTO relay_publications(scope_key,topic,position,event_id,publisher,occurred_at,
              published_at,correlation_id,causation_id,relay_causation,schema_version,payload)
          VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,1,?::jsonb)
          """,
                  RelayScopeCodec.write(key),
                  key.name(),
                  position,
                  draft.eventId(),
                  draft.publisher(),
                  timestamp(draft.occurredAt()),
                  timestamp(admittedAt),
                  draft.correlationId(),
                  draft.causationId(),
                  RelayCausationCodec.write(draft.causation()),
                  payload));
          return new Relay.Publication(key, position, admittedAt, draft);
        });
  }

  @Override
  public Relay.Subscription subscribe(Relay.SubscriptionKey key, Relay.Start start, Instant now) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(start, "start");
    Instant establishedAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), true);
          long baseline =
              start == Relay.Start.LATEST ? topic.lastPosition() : topic.expiredThrough();
          jdbc.update(
              """
          INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)
          VALUES(?,?,?,?,?) ON CONFLICT(scope_key,topic,subscriber) DO NOTHING
          """,
              RelayScopeCodec.write(key.topic()),
              key.topic().name(),
              key.subscriber(),
              baseline,
              timestamp(establishedAt));
          return subscription(key);
        });
  }

  @Override
  public Optional<Relay.Publication> retained(Relay.TopicKey key, String eventId) {
    Objects.requireNonNull(key, "key");
    RelayValues.identity(eventId, "event ID");
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key, false);
          return jdbc
              .query(
                  "SELECT "
                      + PUBLICATION_COLUMNS
                      + " FROM relay_publications WHERE scope_key=? AND topic=? AND event_id=?",
                  (row, index) -> publication(row, topic),
                  RelayScopeCodec.write(key),
                  key.name(),
                  eventId)
              .stream()
              .findFirst();
        });
  }

  @Override
  public Relay.Topic topic(Relay.TopicKey key) {
    Objects.requireNonNull(key, "key");
    return transactions.inTransaction(() -> topic(key, false));
  }

  @Override
  public Relay.Read read(Relay.SubscriptionKey key, int limit) {
    Objects.requireNonNull(key, "key");
    RelayValues.limit(limit, 1000);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), false);
          Relay.Subscription subscription = subscription(key);
          var publications =
              jdbc.query(
                  "SELECT "
                      + PUBLICATION_COLUMNS
                      + " FROM relay_publications WHERE scope_key=? AND topic=? AND position>? ORDER BY position LIMIT ?",
                  (row, index) -> publication(row, topic),
                  RelayScopeCodec.write(key.topic()),
                  key.topic().name(),
                  subscription.seenThrough(),
                  limit);
          return new Relay.Read(subscription, gap(topic, subscription), publications);
        });
  }

  @Override
  public Relay.Subscription advanceSeen(Relay.SubscriptionKey key, long position, Instant now) {
    Objects.requireNonNull(key, "key");
    if (position < 1) throw new IllegalArgumentException("position must be positive");
    Instant seenAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), true);
          Relay.Subscription subscription = subscription(key);
          if (position <= subscription.seenThrough()) return subscription;
          if (gap(topic, subscription).isPresent())
            throw new IllegalStateException("acknowledge the expired Relay gap before advancing");
          if (position > topic.lastPosition() || position <= topic.expiredThrough())
            throw new IllegalArgumentException(
                "seen position must identify a retained publication");
          return move(subscription, position, seenAt);
        });
  }

  @Override
  public Relay.Subscription acknowledgeGap(
      Relay.SubscriptionKey key, long expiredThrough, Instant now) {
    Objects.requireNonNull(key, "key");
    if (expiredThrough < 1) throw new IllegalArgumentException("expired boundary must be positive");
    Instant seenAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), true);
          Relay.Subscription subscription = subscription(key);
          if (expiredThrough > topic.expiredThrough())
            throw new IllegalArgumentException("position has not expired");
          if (expiredThrough <= subscription.seenThrough()) return subscription;
          if (expiredThrough != topic.expiredThrough())
            throw new IllegalStateException("Relay gap changed; read its current boundary");
          return move(subscription, expiredThrough, seenAt);
        });
  }

  @Override
  public Relay.Unread unread(Relay.SubscriptionKey key) {
    Objects.requireNonNull(key, "key");
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), false);
          Relay.Subscription subscription = subscription(key);
          // Append and eviction maintain a contiguous retained suffix under the same topic lock.
          long count =
              topic.lastPosition() - Math.max(topic.expiredThrough(), subscription.seenThrough());
          return new Relay.Unread(subscription, count, gap(topic, subscription));
        });
  }

  @Override
  public List<Relay.TopicKey> topicsToPrune(Instant now, int limit) {
    Instant at = RelayValues.time(now);
    RelayValues.limit(limit, 100);
    return List.copyOf(
        jdbc.query(
            """
        SELECT t.scope_key,t.name FROM relay_topics t
        JOIN relay_publications p ON p.scope_key=t.scope_key AND p.topic=t.name
            AND p.position=t.expired_through+1
        WHERE p.published_at <= CAST(? AS timestamptz) - t.retention_seconds * interval '1 second'
            OR (t.max_records IS NOT NULL AND t.last_position-t.expired_through > t.max_records)
        ORDER BY p.published_at,t.scope_key,t.name LIMIT ?
        """,
            (row, index) ->
                new Relay.TopicKey(
                    RelayScopeCodec.read(row.getString("scope_key")), row.getString("name")),
            timestamp(at),
            limit));
  }

  @Override
  public int prune(Relay.TopicKey key, Instant now, int limit) {
    Objects.requireNonNull(key, "key");
    Instant at = RelayValues.time(now);
    RelayValues.limit(limit, 1000);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key, true);
          Instant cutoff = at.minus(topic.policy().retention());
          long excessThrough =
              topic.policy().maxRecords() == null
                  ? 0
                  : topic.lastPosition() - topic.policy().maxRecords();
          var candidates =
              jdbc.query(
                  """
          SELECT position,published_at FROM relay_publications
          WHERE scope_key=? AND topic=? ORDER BY position LIMIT ?
          """,
                  (row, index) -> new Expiry(row.getLong("position"), instant(row, "published_at")),
                  RelayScopeCodec.write(key),
                  key.name(),
                  limit);
          long through = topic.expiredThrough();
          int count = 0;
          for (var candidate : candidates) {
            // Remove only a prefix, even if wall-clock correction made a later timestamp older.
            if (candidate.position() > excessThrough && candidate.publishedAt().isAfter(cutoff))
              break;
            through = candidate.position();
            count++;
          }
          if (count == 0) return 0;
          int deleted =
              jdbc.update(
                  "DELETE FROM relay_publications WHERE scope_key=? AND topic=? AND position<=?",
                  RelayScopeCodec.write(key),
                  key.name(),
                  through);
          if (deleted != count)
            throw new IllegalStateException("Relay retained prefix changed under lock");
          changed(
              jdbc.update(
                  "UPDATE relay_topics SET expired_through=? WHERE scope_key=? AND name=?",
                  through,
                  RelayScopeCodec.write(key),
                  key.name()));
          return deleted;
        });
  }

  private Relay.Topic topic(Relay.TopicKey key, boolean exclusive) {
    // The lock keyword is selected only by this code-owned boolean, never by external input.
    return jdbc
        .query(
            "SELECT "
                + TOPIC_COLUMNS
                + " FROM relay_topics WHERE scope_key=? AND name=? "
                + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
            JdbcRelayRepository::topicRow,
            RelayScopeCodec.write(key),
            key.name())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Relay topic does not exist"));
  }

  private Relay.Subscription subscription(Relay.SubscriptionKey key) {
    return jdbc
        .query(
            """
        SELECT seen_through,seen_at,generation FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?
        """,
            (row, index) ->
                new Relay.Subscription(
                    key,
                    row.getLong("seen_through"),
                    instant(row, "seen_at"),
                    row.getObject("generation", java.util.UUID.class)),
            RelayScopeCodec.write(key.topic()),
            key.topic().name(),
            key.subscriber())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Relay subscription does not exist"));
  }

  private Relay.Subscription move(Relay.Subscription previous, long position, Instant seenAt) {
    var key = previous.key();
    changed(
        jdbc.update(
            """
        UPDATE relay_subscriptions SET seen_through=?,seen_at=? WHERE scope_key=? AND topic=? AND subscriber=?
        """,
            position,
            timestamp(seenAt),
            RelayScopeCodec.write(key.topic()),
            key.topic().name(),
            key.subscriber()));
    return new Relay.Subscription(key, position, seenAt, previous.generation());
  }

  private static Optional<Relay.Gap> gap(Relay.Topic topic, Relay.Subscription subscription) {
    return subscription.seenThrough() < topic.expiredThrough()
        ? Optional.of(new Relay.Gap(subscription.seenThrough(), topic.expiredThrough()))
        : Optional.empty();
  }

  private static Relay.Topic topicRow(ResultSet row, int index) throws SQLException {
    return new Relay.Topic(
        new Relay.TopicKey(RelayScopeCodec.read(row.getString("scope_key")), row.getString("name")),
        RelayPayload.Kind.valueOf(row.getString("payload_kind")),
        new Relay.Policy(
            Duration.ofSeconds(row.getLong("retention_seconds")),
            row.getObject("max_records", Long.class)),
        row.getLong("last_position"),
        row.getLong("expired_through"),
        row.getObject("generation", java.util.UUID.class));
  }

  private static Relay.Publication publication(ResultSet row, Relay.Topic topic)
      throws SQLException {
    return new Relay.Publication(
        topic.key(),
        row.getLong("position"),
        instant(row, "published_at"),
        new Relay.Draft(
            row.getString("event_id"),
            row.getString("publisher"),
            instant(row, "occurred_at"),
            row.getString("correlation_id"),
            row.getString("causation_id"),
            RelayPayloadCodec.read(
                topic.kind(), row.getInt("schema_version"), row.getString("payload")),
            RelayCausationCodec.read(row.getString("relay_causation"))));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static void changed(int rows) {
    if (rows != 1) throw new IllegalStateException("Relay state transition did not affect one row");
  }

  private record Expiry(long position, Instant publishedAt) {}
}
