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
  // The payload bytes live once in relay_payloads; a publication names them by digest. Every read
  // of a publication joins the store in its own query, so a page of records costs one query and
  // the running byte sum below can use the length PostgreSQL already recorded.
  private static final String PUBLICATION_COLUMNS =
      "p.position, p.event_id, p.publisher, p.occurred_at, p.published_at, p.correlation_id,"
          + " p.causation_id, p.relay_causation::text, p.schema_version, q.payload::text";
  private static final String PUBLICATION_SOURCE =
      " FROM relay_publications p JOIN relay_payloads q ON q.digest=p.payload_digest";

  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final RelayPayloadStore payloads;
  private final RelayTextLimit textLimit;

  public JdbcRelayRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, RelayPayloadStore payloads) {
    this(jdbc, transactions, payloads, RelayTextLimit.DEFAULT);
  }

  public JdbcRelayRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      RelayPayloadStore payloads,
      RelayTextLimit textLimit) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.payloads = Objects.requireNonNull(payloads, "payloads");
    this.textLimit = Objects.requireNonNull(textLimit, "textLimit");
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
          var inserted =
              jdbc.query(
                  """
          INSERT INTO relay_topics(scope_key,project_id,name,payload_kind,retention_seconds,max_records)
          VALUES(?,?,?,?,?,?) ON CONFLICT(scope_key,name) DO NOTHING
          """
                      + " RETURNING "
                      + TOPIC_COLUMNS,
                  JdbcRelayRepository::topicRow,
                  RelayScopeCodec.write(key),
                  RelayScopeCodec.project(key),
                  key.name(),
                  requested.kind().name(),
                  policy.retention().getSeconds(),
                  policy.maxRecords());
          Relay.Topic existing = inserted.isEmpty() ? topic(key, true) : inserted.getFirst();
          if (existing.kind() != kind)
            throw new IllegalArgumentException(
                "an existing Relay topic cannot change payload family");
          if (!replacePolicy || existing.policy().equals(policy)) return existing;
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
    // The payload is committed before the topic transaction opens, on purpose. Inside it, the
    // store's ON CONFLICT (digest) would make a publisher wait on another's uncommitted insert of
    // the same digest, serialising publishers that share nothing but identical content. Committed
    // ahead, the insert for every later publisher is a conflict that writes a row version only
    // when it must push the staging date out, and the topic lock covers only position allocation.
    // The price is orphans: a payload is written before the topic is read, so an
    // append that then fails leaves an unreferenced row. That covers a failed or rolled-back
    // insert, and also an append to an unknown topic or one of a different payload family, which
    // used to fail with no side effect. That is correct, not an error: the sweep reclaims the row
    // once it matures and nothing references it.
    //
    // What protects the row until this append's own insert references it is the staging window the
    // store writes, and it is written on a repeat as well as on a fresh insert: the conflict clause
    // re-arms ejection_at and takes the row lock. That matters here specifically, because the
    // window between the committed store and the insert below spans the topic lock, the locked
    // retained read and position allocation. A repeat that left an already matured ejection_at
    // alone would leave the row a sweep candidate, with no lock held, for that whole window, and
    // the insert below would then fail its foreign key.
    //
    // The window is narrowed, not closed. The row lock only delays a sweep's reclamation: the
    // sweep's DELETE predicate does not mention ejection_at, so a delete that already selected the
    // digest and then waits on the store's lock re-evaluates its reference checks afterwards,
    // finds none yet, and deletes. The re-armed date protects the row from every later candidate
    // query, which shrinks the exposure from unbounded (topic-lock acquisition, the locked read,
    // position allocation) to the sub-statement span of a single sweep candidate. If a sweep lands
    // in that span the insert below fails its foreign key, this append is rejected, and a retry
    // stores the bytes again.
    //
    // Because the text allowance is checked before the family, a draft that is both oversize and
    // the wrong family now reports the text-limit error.
    //
    // An identical repeat of a retained event stores nothing. The probe below is advisory and
    // unlocked; the locked read under the topic lock stays the authority for idempotency and
    // conflicts. It can be stale in two directions, both benign. If the event was first appended
    // concurrently, the probe says absent: one redundant idempotent store, then the locked read
    // returns the original. If the event was pruned after the probe said present, nothing was
    // stored and the append is new: the fallback below validates and stores it inside the
    // transaction. No lock cycle arises on the common path because a competing publisher commits
    // its store before it takes any topic lock. The fallback stores under the topic lock and so
    // holds a row-exclusive lock on the payload row until commit; it is safe for a different
    // reason: locks are always acquired topic first, then payload, and the sweep takes no topic
    // lock at all, so nothing can wait on a topic lock while holding a payload lock.
    // The text allowance is checked here, before the bytes are written, so a refused payload
    // never reaches the store. A lowered allowance still recovers an identical retained event
    // because the probe returns before this check.
    String digest = null;
    if (!isRetained(key, draft.eventId())) {
      textLimit.validateNew(draft.payload());
      digest = payloads.store(draft.payload(), admittedAt);
    }
    String payloadDigest = digest;
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key, true);
          if (topic.kind() != draft.payload().kind())
            throw new IllegalArgumentException("Relay payload differs from the topic family");
          var retained =
              jdbc.query(
                  "SELECT "
                      + PUBLICATION_COLUMNS
                      + PUBLICATION_SOURCE
                      + " WHERE p.scope_key=? AND p.topic=? AND p.event_id=?",
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
          // The probe saw a retained event that pruning removed before the topic lock was taken, so
          // nothing was stored. This is a genuinely new append: validate and store it here. It is
          // the only path that stores under the lock, and only after a race with pruning.
          String digestToWrite = payloadDigest;
          if (digestToWrite == null) {
            textLimit.validateNew(draft.payload());
            digestToWrite = payloads.store(draft.payload(), admittedAt);
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
              published_at,correlation_id,causation_id,relay_causation,schema_version,
              payload_digest)
          VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,1,?)
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
                  digestToWrite));
          return new Relay.Publication(key, position, admittedAt, draft);
        });
  }

  /** Unlocked existence probe for an event ID; see {@link #append} for why it may be stale. */
  private boolean isRetained(Relay.TopicKey key, String eventId) {
    return !jdbc.query(
            "SELECT 1 FROM relay_publications WHERE scope_key=? AND topic=? AND event_id=?",
            (row, index) -> Boolean.TRUE,
            RelayScopeCodec.write(key),
            key.name(),
            eventId)
        .isEmpty();
  }

  @Override
  public Optional<Relay.Subscription> subscribeAsReader(
      Relay.SubscriptionKey key, Relay.Start start, String reader, Instant now) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(start, "start");
    RelayValues.identity(reader, "subscription reader");
    // The stored name must be the one this account's namespace produces. The schema requires the
    // same agreement between reader_account and the name, so neither can be written alone.
    if (!key.subscriber().startsWith("reader:" + reader + ":"))
      throw new IllegalArgumentException(
          "a read-tier subscription must use this account's consumer group namespace");
    Instant establishedAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), true);
          long baseline =
              start == Relay.Start.LATEST ? topic.lastPosition() : topic.expiredThrough();
          // The namespace already makes a cross-tier collision unnameable; this conflict clause is
          // the backstop, and it is in the one place a backstop belongs -- the statement that
          // writes the row, so it cannot race the writer it is checking. A row whose
          // reader_account is NULL or another account's does not satisfy the WHERE, so no row is
          // returned and nothing is written. The no-op SET exists only because ON CONFLICT DO
          // UPDATE requires an assignment: the stored cursor and generation must survive a
          // repeated registration untouched.
          return jdbc
              .query(
                  """
          INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at,reader_account)
          VALUES(?,?,?,?,?,?) ON CONFLICT(scope_key,topic,subscriber) DO UPDATE
            SET reader_account=relay_subscriptions.reader_account
            WHERE relay_subscriptions.reader_account=EXCLUDED.reader_account
          RETURNING seen_through,seen_at,generation
          """,
                  (row, index) -> subscriptionRow(row, key),
                  RelayScopeCodec.write(key.topic()),
                  key.topic().name(),
                  key.subscriber(),
                  baseline,
                  timestamp(establishedAt),
                  reader)
              .stream()
              .findFirst();
        });
  }

  @Override
  public Relay.Subscription subscribe(Relay.SubscriptionKey key, Relay.Start start, Instant now) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(start, "start");
    // This path registers a work-tier group and has no reader_account to write, so the schema's
    // work-tier branch would reject the INSERT. The conflict path would not reach that branch
    // though: ON CONFLICT DO NOTHING followed by a read of the existing row would quietly adopt a
    // reader's group and share its committed offset. Refusing the namespace here closes that.
    if (RelayValues.reader(key.subscriber()))
      throw new IllegalArgumentException(
          "a work-tier subscription cannot use a read-tier consumer group name");
    Instant establishedAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          Relay.Topic topic = topic(key.topic(), true);
          long baseline =
              start == Relay.Start.LATEST ? topic.lastPosition() : topic.expiredThrough();
          var inserted =
              jdbc.query(
                  """
          INSERT INTO relay_subscriptions(scope_key,topic,subscriber,seen_through,seen_at)
          VALUES(?,?,?,?,?) ON CONFLICT(scope_key,topic,subscriber) DO NOTHING
          RETURNING seen_through,seen_at,generation
          """,
                  (row, index) -> subscriptionRow(row, key),
                  RelayScopeCodec.write(key.topic()),
                  key.topic().name(),
                  key.subscriber(),
                  baseline,
                  timestamp(establishedAt));
          // A conflict may retain a concurrently established cursor and generation. Only that
          // path needs a fresh read; a newly inserted row is already the authoritative result.
          return inserted.isEmpty() ? subscription(key) : inserted.getFirst();
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
                      + PUBLICATION_SOURCE
                      + " WHERE p.scope_key=? AND p.topic=? AND p.event_id=?",
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
                  // Bound database materialization before decoding large payloads. Adding per-row
                  // metadata headroom preserves a contiguous prefix; a short page never skips work.
                  // byte_length is the stored payload's octet_length(payload::text), which is
                  // exactly what this window used to compute from the publication's own copy, so
                  // the page boundary is unchanged. The window stays outside the LIMIT: a window
                  // function is evaluated before LIMIT, so summing inside the candidate query
                  // would budget against every matching row rather than the page. page_bytes is
                  // now numeric rather than bigint, because byte_length is bigint where
                  // octet_length returned integer; only the comparison against the budget below
                  // depends on it, and an integral numeric compares the same.
                  //
                  // This page keeps SELECT *, so payload_bytes reaches the result set, which the
                  // row mapper ignores. That is safe only because this is a plain query. The
                  // inspection log's page names its outer columns instead, because
                  // JdbcRelayLogRepository.boundedProjection sizes the projected record with
                  // to_jsonb, where an extra column would shift the projection-cache bound.
                  // Anything that later wraps this page that way must do the same.
                  "SELECT * FROM (SELECT candidates.*, SUM(payload_bytes + 4096)"
                      + " OVER (ORDER BY position) AS page_bytes FROM (SELECT "
                      + PUBLICATION_COLUMNS
                      + ", q.byte_length AS payload_bytes"
                      + PUBLICATION_SOURCE
                      + " WHERE p.scope_key=? AND p.topic=? AND p.position>?"
                      + " ORDER BY p.position LIMIT ?) candidates) bounded"
                      + " WHERE page_bytes <= ? ORDER BY position",
                  (row, index) -> publication(row, topic),
                  RelayScopeCodec.write(key.topic()),
                  key.topic().name(),
                  subscription.seenThrough(),
                  limit,
                  io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES
                      - 1024 * 1024);
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
            (row, index) -> subscriptionRow(row, key),
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

  private static Relay.Subscription subscriptionRow(ResultSet row, Relay.SubscriptionKey key)
      throws SQLException {
    return new Relay.Subscription(
        key,
        row.getLong("seen_through"),
        instant(row, "seen_at"),
        row.getObject("generation", java.util.UUID.class));
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
