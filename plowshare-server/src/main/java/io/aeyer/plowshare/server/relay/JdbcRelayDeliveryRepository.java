package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Broker admission/claim persistence. Admission holds the same topic lock as log append/expiry;
 * delivery transitions only lock their own branch. Effects run after prepareDispatch commits.
 * Expiry before preparation is retryable; expiry afterward is uncertain and requires the owning
 * receiver's reconciliation. Input and pinned sources survive topic eviction.
 */
public final class JdbcRelayDeliveryRepository implements RelayDeliveryRepository {
  private static final String INPUT_COLUMNS =
      """
      a.scope_key,a.topic,a.subscriber,a.publication_position,a.event_id,a.publisher,
      a.occurred_at,a.published_at,a.correlation_id,a.causation_id,a.relay_causation::text,a.payload_kind,a.schema_version,
      a.payload::text,a.routing_path,a.routing_source,a.routing_hash,a.branch_count,a.admitted_at
      """;
  private static final String BRANCH_COLUMNS =
      """
      d.id,d.branch_index,d.branch_name,d.receiver,d.publish_to,d.handler_path,d.handler_source,d.handler_hash,
      d.work_agent,d.work_project,d.work_definition,d.state,d.fence,d.worker,d.lease_until,d.updated_at,d.receipt_namespace,d.receipt_id,d.failure_code
      """;
  private static final String JOIN =
      """
      FROM relay_deliveries d JOIN relay_admissions a ON a.scope_key=d.scope_key AND a.topic=d.topic
          AND a.subscriber=d.subscriber AND a.publication_position=d.publication_position
      """;
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;

  public JdbcRelayDeliveryRepository(JdbcTemplate jdbc, UnitOfWork transactions) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
  }

  @Override
  public RelayDeliveries.Admission admit(
      RelayDeliveries.AdmissionKey key, RelayDeliveries.Decision decision, Instant now) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(decision, "decision");
    Instant admittedAt = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          var subscription = key.subscription();
          var topic = subscription.topic();
          var retained =
              jdbc
                  .query(
                      """
          SELECT payload_kind,last_position,expired_through FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE
          """,
                      (row, index) ->
                          new Boundary(
                              RelayPayload.Kind.valueOf(row.getString("payload_kind")),
                              row.getLong("last_position"),
                              row.getLong("expired_through")),
                      RelayScopeCodec.write(topic),
                      topic.name())
                  .stream()
                  .findFirst()
                  .orElseThrow(() -> new IllegalStateException("Relay topic does not exist"));
          var existing = admitted(key);
          if (existing.isPresent()) {
            if (!existing.get().decision().equals(decision))
              throw new IllegalArgumentException(
                  "Relay admission conflicts with pinned routing decision");
            return existing.get();
          }
          Long pending =
              jdbc.queryForObject(
                  """
              SELECT COUNT(*) FROM relay_deliveries WHERE scope_key=? AND topic=? AND subscriber=?
                AND state IN ('READY','CLAIMED','DISPATCHING','UNCERTAIN')
              """,
                  Long.class,
                  RelayScopeCodec.write(topic),
                  topic.name(),
                  subscription.subscriber());
          // The topic lock serializes this admission bound. Backpressure preserves the unseen
          // publication and never abandons already admitted input or uncertain work.
          if (pending == null || pending + decision.branches().size() > 1000)
            throw new IllegalStateException("Relay subscription pending delivery limit reached");
          Long seen =
              jdbc
                  .query(
                      """
          SELECT seen_through FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?
          """,
                      (row, index) -> row.getLong("seen_through"),
                      RelayScopeCodec.write(topic),
                      topic.name(),
                      subscription.subscriber())
                  .stream()
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalStateException("Relay subscription does not exist"));
          if (seen < retained.expiredThrough())
            throw new IllegalStateException("acknowledge the expired Relay gap before admitting");
          if (key.position() <= seen)
            throw new IllegalStateException(
                "Relay publication was already seen; no retained admission can be replayed");
          if (key.position() != Math.addExact(seen, 1) || key.position() > retained.lastPosition())
            throw new IllegalArgumentException(
                "Relay admission must select the next retained publication");
          Relay.Publication publication =
              jdbc
                  .query(
                      """
          SELECT event_id,publisher,occurred_at,published_at,correlation_id,causation_id,relay_causation::text,schema_version,payload::text
          FROM relay_publications WHERE scope_key=? AND topic=? AND position=?
          """,
                      (row, index) -> publication(row, topic, key.position(), retained.kind()),
                      RelayScopeCodec.write(topic),
                      topic.name(),
                      key.position())
                  .stream()
                  .findFirst()
                  .orElseThrow(
                      () -> new IllegalStateException("Relay publication is not retained"));
          var source = decision.routing();
          changed(
              jdbc.update(
                  """
          INSERT INTO relay_admissions(scope_key,topic,subscriber,publication_position,event_id,publisher,
              occurred_at,published_at,correlation_id,causation_id,relay_causation,payload_kind,schema_version,payload,
              routing_path,routing_source,routing_hash,branch_count,admitted_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb,?,1,?::jsonb,?,?,?,?,?)
          """,
                  RelayScopeCodec.write(topic),
                  topic.name(),
                  subscription.subscriber(),
                  key.position(),
                  publication.event().eventId(),
                  publication.event().publisher(),
                  timestamp(publication.event().occurredAt()),
                  timestamp(publication.publishedAt()),
                  publication.event().correlationId(),
                  publication.event().causationId(),
                  RelayCausationCodec.write(publication.event().causation()),
                  retained.kind().name(),
                  RelayPayloadCodec.write(publication.event().payload()),
                  source.path(),
                  source.source(),
                  source.sha256(),
                  decision.branches().size(),
                  timestamp(admittedAt)));
          var identities = new ArrayList<RelayDeliveries.DeliveryKey>();
          for (int index = 0; index < decision.branches().size(); index++) {
            var branch = decision.branches().get(index);
            var handler = branch.handler();
            var id = UUID.randomUUID();
            changed(
                jdbc.update(
                    """
            INSERT INTO relay_deliveries(id,scope_key,topic,subscriber,publication_position,branch_index,
                branch_name,receiver,publish_to,handler_path,handler_source,handler_hash,updated_at,work_agent,work_project,work_definition)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
                    id,
                    RelayScopeCodec.write(topic),
                    topic.name(),
                    subscription.subscriber(),
                    key.position(),
                    index,
                    branch.name(),
                    branch.receiver(),
                    branch.publishTo(),
                    handler == null ? null : handler.path(),
                    handler == null ? null : handler.source(),
                    handler == null ? null : handler.sha256(),
                    timestamp(admittedAt),
                    branch.work() == null ? null : branch.work().agent(),
                    branch.work() == null ? null : branch.work().project(),
                    branch.work() == null ? null : branch.work().definition()));
            identities.add(new RelayDeliveries.DeliveryKey(subscription, id));
          }
          // The copied input and every fan-out branch commit before this cursor can hide the event.
          changed(
              jdbc.update(
                  """
          UPDATE relay_subscriptions SET seen_through=?,seen_at=? WHERE scope_key=? AND topic=? AND subscriber=?
          """,
                  key.position(),
                  timestamp(admittedAt),
                  RelayScopeCodec.write(topic),
                  topic.name(),
                  subscription.subscriber()));
          return new RelayDeliveries.Admission(key, publication, decision, admittedAt, identities);
        });
  }

  @Override
  public Optional<RelayDeliveries.Admission> admission(RelayDeliveries.AdmissionKey key) {
    Objects.requireNonNull(key, "key");
    return transactions.inTransaction(() -> admitted(key));
  }

  private Optional<RelayDeliveries.Admission> admitted(RelayDeliveries.AdmissionKey key) {
    var subscription = key.subscription();
    var input =
        jdbc
            .query(
                "SELECT "
                    + INPUT_COLUMNS
                    + " FROM relay_admissions a WHERE a.scope_key=? AND a.topic=? AND a.subscriber=? AND a.publication_position=? FOR SHARE OF a",
                (row, index) -> input(row),
                RelayScopeCodec.write(subscription.topic()),
                subscription.topic().name(),
                subscription.subscriber(),
                key.position())
            .stream()
            .findFirst();
    if (input.isEmpty()) return Optional.empty();
    var branches =
        jdbc.query(
            """
        SELECT id,branch_index,branch_name,receiver,publish_to,handler_path,handler_source,handler_hash,work_agent,work_project,work_definition FROM relay_deliveries
        WHERE scope_key=? AND topic=? AND subscriber=? AND publication_position=? ORDER BY branch_index
        """,
            (row, index) -> {
              if (row.getInt("branch_index") != index)
                throw new IllegalStateException("Relay branch order is incomplete");
              return new Definition(row.getObject("id", UUID.class), branch(row));
            },
            RelayScopeCodec.write(subscription.topic()),
            subscription.topic().name(),
            subscription.subscriber(),
            key.position());
    var recorded = input.get();
    if (branches.size() != recorded.branchCount())
      throw new IllegalStateException("Relay admission is missing branches");
    return Optional.of(
        new RelayDeliveries.Admission(
            key,
            recorded.publication(),
            new RelayDeliveries.Decision(
                recorded.routing(), branches.stream().map(Definition::branch).toList()),
            recorded.admittedAt(),
            branches.stream()
                .map(branch -> new RelayDeliveries.DeliveryKey(subscription, branch.id()))
                .toList()));
  }

  @Override
  public Optional<RelayDeliveries.Delivery> delivery(RelayDeliveries.DeliveryKey key) {
    Objects.requireNonNull(key, "key");
    return find(key, false);
  }

  @Override
  public Optional<RelayDeliveries.Delivery> claim(
      Relay.SubscriptionKey key, String worker, Duration lease, Instant now) {
    Objects.requireNonNull(key, "key");
    String owner = RelayValues.identity(worker, "worker");
    RelayDeliveries.requireLease(lease);
    Instant at = RelayValues.time(now);
    Instant until = RelayValues.time(at.plus(lease));
    return transactions.inTransaction(
        () -> {
          var ready =
              jdbc
                  .query(
                      "SELECT "
                          + INPUT_COLUMNS
                          + ","
                          + BRANCH_COLUMNS
                          + " "
                          + JOIN
                          + """
          WHERE d.scope_key=? AND d.topic=? AND d.subscriber=? AND d.state='READY'
          ORDER BY d.publication_position,d.branch_index LIMIT 1 FOR UPDATE OF d SKIP LOCKED
          """,
                      (row, index) -> deliveryRow(row),
                      RelayScopeCodec.write(key.topic()),
                      key.topic().name(),
                      key.subscriber())
                  .stream()
                  .findFirst();
          if (ready.isEmpty()) return Optional.empty();
          var delivery = ready.get();
          changed(
              jdbc.update(
                  """
          UPDATE relay_deliveries SET state='CLAIMED',fence=?,worker=?,lease_until=?,updated_at=? WHERE id=?
          """,
                  Math.addExact(delivery.fence(), 1),
                  owner,
                  timestamp(until),
                  timestamp(at),
                  delivery.key().id()));
          return Optional.of(locked(delivery.key()));
        });
  }

  @Override
  public RelayDeliveries.Delivery prepareDispatch(RelayDeliveries.Claim claim, Instant now) {
    Objects.requireNonNull(claim, "claim");
    Instant at = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          var delivery = locked(claim.key());
          live(delivery, claim, at);
          if (delivery.state() == RelayDeliveries.State.DISPATCHING) return delivery;
          changed(
              jdbc.update(
                  "UPDATE relay_deliveries SET state='DISPATCHING',updated_at=? WHERE id=?",
                  timestamp(at),
                  claim.key().id()));
          return locked(claim.key());
        });
  }

  @Override
  public RelayDeliveries.Delivery renew(RelayDeliveries.Claim claim, Duration lease, Instant now) {
    Objects.requireNonNull(claim, "claim");
    RelayDeliveries.requireLease(lease);
    Instant at = RelayValues.time(now);
    Instant requested = RelayValues.time(at.plus(lease));
    return transactions.inTransaction(
        () -> {
          var delivery = locked(claim.key());
          live(delivery, claim, at);
          Instant until =
              requested.isAfter(delivery.leaseUntil()) ? requested : delivery.leaseUntil();
          changed(
              jdbc.update(
                  "UPDATE relay_deliveries SET lease_until=?,updated_at=? WHERE id=?",
                  timestamp(until),
                  timestamp(at),
                  claim.key().id()));
          return locked(claim.key());
        });
  }

  @Override
  public RelayDeliveries.Delivery accepted(
      RelayDeliveries.Claim claim, RelayDeliveries.Receipt receipt, Instant now) {
    Objects.requireNonNull(claim, "claim");
    Objects.requireNonNull(receipt, "receipt");
    Instant at = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          var delivery = locked(claim.key());
          if (delivery.state() == RelayDeliveries.State.ACCEPTED
              && owns(delivery, claim)
              && receipt.equals(delivery.receipt())) return delivery;
          live(delivery, claim, at);
          if (delivery.state() != RelayDeliveries.State.DISPATCHING)
            throw new IllegalStateException("prepare Relay dispatch before recording acceptance");
          recordAccepted(claim.key(), receipt, at);
          return locked(claim.key());
        });
  }

  @Override
  public RelayDeliveries.Delivery failed(RelayDeliveries.Claim claim, String code, Instant now) {
    return outcome(claim, code, RelayDeliveries.State.FAILED, now);
  }

  @Override
  public RelayDeliveries.Delivery uncertain(RelayDeliveries.Claim claim, String code, Instant now) {
    return outcome(claim, code, RelayDeliveries.State.UNCERTAIN, now);
  }

  private RelayDeliveries.Delivery outcome(
      RelayDeliveries.Claim claim, String code, RelayDeliveries.State state, Instant now) {
    Objects.requireNonNull(claim, "claim");
    String checked = RelayValues.name(code, "failure code");
    Instant at = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          var delivery = locked(claim.key());
          if (delivery.state() == state
              && owns(delivery, claim)
              && checked.equals(delivery.failureCode())) return delivery;
          live(delivery, claim, at);
          if (state == RelayDeliveries.State.UNCERTAIN
              && delivery.state() != RelayDeliveries.State.DISPATCHING)
            throw new IllegalStateException("only prepared Relay dispatch can become uncertain");
          recordOutcome(claim.key(), state, checked, at);
          return locked(claim.key());
        });
  }

  @Override
  public RelayDeliveries.Delivery reconcile(
      RelayDeliveries.DeliveryKey key, RelayDeliveries.Resolution resolution, Instant now) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(resolution, "resolution");
    Instant at = RelayValues.time(now);
    return transactions.inTransaction(
        () -> {
          var delivery = locked(key);
          boolean same =
              switch (resolution) {
                case RelayDeliveries.Accepted accepted ->
                    delivery.state() == RelayDeliveries.State.ACCEPTED
                        && accepted.receipt().equals(delivery.receipt());
                case RelayDeliveries.Failed failed ->
                    delivery.state() == RelayDeliveries.State.FAILED
                        && failed.code().equals(delivery.failureCode());
              };
          if (same) return delivery;
          if (delivery.state() != RelayDeliveries.State.UNCERTAIN
              && delivery.state() != RelayDeliveries.State.ABANDONED_UNCERTAIN)
            throw new IllegalStateException("only uncertain Relay dispatch can be reconciled");
          switch (resolution) {
            case RelayDeliveries.Accepted accepted -> recordAccepted(key, accepted.receipt(), at);
            case RelayDeliveries.Failed failed ->
                recordOutcome(key, RelayDeliveries.State.FAILED, failed.code(), at);
          }
          return locked(key);
        });
  }

  @Override
  public int recoverExpired(Instant now, int limit) {
    Instant at = RelayValues.time(now);
    RelayValues.limit(limit, 1000);
    return jdbc.update(
        """
        WITH expired AS (
            SELECT id FROM relay_deliveries WHERE state IN ('CLAIMED','DISPATCHING') AND lease_until<=?
            ORDER BY lease_until,id LIMIT ? FOR UPDATE SKIP LOCKED
        )
        UPDATE relay_deliveries d SET
            state=CASE WHEN d.state='DISPATCHING' THEN 'UNCERTAIN' ELSE 'READY' END,
            failure_code=CASE WHEN d.state='DISPATCHING' THEN 'lease.expired' ELSE NULL END,
            worker=CASE WHEN d.state='DISPATCHING' THEN d.worker ELSE NULL END,
            lease_until=NULL,updated_at=?
        FROM expired e WHERE d.id=e.id
        """,
        timestamp(at),
        limit,
        timestamp(at));
  }

  @Override
  public int pruneSettled(Instant now, Duration retention, int limit) {
    Instant at = RelayValues.time(now);
    new Relay.Policy(retention, null);
    RelayValues.limit(limit, 1000);
    Instant cutoff = at.minus(retention);
    return jdbc.update(
        """
        WITH expired AS (
            SELECT a.scope_key,a.topic,a.subscriber,a.publication_position FROM relay_admissions a
            WHERE a.admitted_at<=? AND NOT EXISTS (
                SELECT 1 FROM relay_deliveries d WHERE d.scope_key=a.scope_key AND d.topic=a.topic
                    AND d.subscriber=a.subscriber AND d.publication_position=a.publication_position
                    AND (d.state NOT IN ('ACCEPTED','FAILED','ABANDONED') OR d.updated_at>?)
            )
            ORDER BY a.admitted_at,a.scope_key,a.topic,a.subscriber,a.publication_position
            LIMIT ? FOR UPDATE OF a SKIP LOCKED
        )
        DELETE FROM relay_admissions a USING expired e WHERE a.scope_key=e.scope_key AND a.topic=e.topic
            AND a.subscriber=e.subscriber AND a.publication_position=e.publication_position
        """,
        timestamp(cutoff),
        timestamp(cutoff),
        limit);
  }

  private void recordAccepted(
      RelayDeliveries.DeliveryKey key, RelayDeliveries.Receipt receipt, Instant now) {
    changed(
        jdbc.update(
            """
        UPDATE relay_deliveries SET state='ACCEPTED',receipt_namespace=?,receipt_id=?,failure_code=NULL,
            lease_until=NULL,updated_at=? WHERE id=?
        """,
            receipt.namespace(),
            receipt.id(),
            timestamp(now),
            key.id()));
  }

  private void recordOutcome(
      RelayDeliveries.DeliveryKey key, RelayDeliveries.State state, String code, Instant now) {
    changed(
        jdbc.update(
            """
        UPDATE relay_deliveries SET state=?,failure_code=?,lease_until=NULL,updated_at=? WHERE id=?
        """,
            state.name(),
            code,
            timestamp(now),
            key.id()));
  }

  private RelayDeliveries.Delivery locked(RelayDeliveries.DeliveryKey key) {
    return find(key, true)
        .orElseThrow(
            () -> new IllegalStateException("Relay delivery does not exist in this subscription"));
  }

  private Optional<RelayDeliveries.Delivery> find(
      RelayDeliveries.DeliveryKey key, boolean exclusive) {
    var subscription = key.subscription();
    return jdbc
        .query(
            "SELECT "
                + INPUT_COLUMNS
                + ","
                + BRANCH_COLUMNS
                + " "
                + JOIN
                + " WHERE d.scope_key=? AND d.topic=? AND d.subscriber=? AND d.id=? "
                + (exclusive ? "FOR UPDATE OF d" : ""),
            (row, index) -> deliveryRow(row),
            RelayScopeCodec.write(subscription.topic()),
            subscription.topic().name(),
            subscription.subscriber(),
            key.id())
        .stream()
        .findFirst();
  }

  private static boolean owns(RelayDeliveries.Delivery delivery, RelayDeliveries.Claim claim) {
    return delivery.fence() == claim.fence() && Objects.equals(delivery.worker(), claim.worker());
  }

  private static void live(
      RelayDeliveries.Delivery delivery, RelayDeliveries.Claim claim, Instant now) {
    if (!owns(delivery, claim)
        || (delivery.state() != RelayDeliveries.State.CLAIMED
            && delivery.state() != RelayDeliveries.State.DISPATCHING)
        || !delivery.leaseUntil().isAfter(now))
      throw new IllegalStateException("Relay delivery claim is stale or expired");
  }

  private static Input input(ResultSet row) throws SQLException {
    var topic =
        new Relay.TopicKey(
            RelayScopeCodec.read(row.getString("scope_key")), row.getString("topic"));
    var key =
        new RelayDeliveries.AdmissionKey(
            new Relay.SubscriptionKey(topic, row.getString("subscriber")),
            row.getLong("publication_position"));
    return new Input(
        key,
        publication(
            row, topic, key.position(), RelayPayload.Kind.valueOf(row.getString("payload_kind"))),
        new RelayDeliveries.SourcePin(
            row.getString("routing_path"),
            row.getString("routing_source"),
            row.getString("routing_hash")),
        row.getInt("branch_count"),
        instant(row, "admitted_at"));
  }

  private static Relay.Publication publication(
      ResultSet row, Relay.TopicKey topic, long position, RelayPayload.Kind kind)
      throws SQLException {
    return new Relay.Publication(
        topic,
        position,
        instant(row, "published_at"),
        new Relay.Draft(
            row.getString("event_id"),
            row.getString("publisher"),
            instant(row, "occurred_at"),
            row.getString("correlation_id"),
            row.getString("causation_id"),
            RelayPayloadCodec.read(kind, row.getInt("schema_version"), row.getString("payload")),
            RelayCausationCodec.read(row.getString("relay_causation"))));
  }

  private static RelayDeliveries.Branch branch(ResultSet row) throws SQLException {
    String path = row.getString("handler_path");
    var handler =
        path == null
            ? null
            : new RelayDeliveries.SourcePin(
                path, row.getString("handler_source"), row.getString("handler_hash"));
    return new RelayDeliveries.Branch(
        row.getString("branch_name"),
        row.getString("receiver"),
        handler,
        row.getString("publish_to"),
        row.getString("work_agent") == null
            ? null
            : new RelayWork(
                row.getString("work_agent"),
                row.getString("work_project"),
                row.getString("work_definition")));
  }

  private static RelayDeliveries.Delivery deliveryRow(ResultSet row) throws SQLException {
    var input = input(row);
    String receiptNamespace = row.getString("receipt_namespace");
    var receipt =
        receiptNamespace == null
            ? null
            : new RelayDeliveries.Receipt(receiptNamespace, row.getString("receipt_id"));
    var until = row.getObject("lease_until", OffsetDateTime.class);
    return new RelayDeliveries.Delivery(
        new RelayDeliveries.DeliveryKey(
            input.key().subscription(), row.getObject("id", UUID.class)),
        input.key(),
        input.publication(),
        input.routing(),
        branch(row),
        RelayDeliveries.State.valueOf(row.getString("state")),
        row.getLong("fence"),
        row.getString("worker"),
        until == null ? null : until.toInstant(),
        input.admittedAt(),
        instant(row, "updated_at"),
        receipt,
        row.getString("failure_code"));
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static void changed(int rows) {
    if (rows != 1)
      throw new IllegalStateException("Relay delivery transition did not affect one row");
  }

  private record Boundary(RelayPayload.Kind kind, long lastPosition, long expiredThrough) {}

  private record Input(
      RelayDeliveries.AdmissionKey key,
      Relay.Publication publication,
      RelayDeliveries.SourcePin routing,
      int branchCount,
      Instant admittedAt) {}

  private record Definition(UUID id, RelayDeliveries.Branch branch) {}
}
