package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayControl;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Receipt serialization and mutation share one transaction. Locks follow consumer lease -> topic ->
 * subscription/delivery, matching consumer admission order. Remote receipt inspection is outside
 * these locks. Empty metadata removal never cascades admitted input or uncertain effects.
 */
public final class JdbcRelayOperationRepository implements RelayOperationRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final RelayRepository broker;
  private final RelayDeliveryRepository deliveries;

  public JdbcRelayOperationRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      RelayRepository broker,
      RelayDeliveryRepository deliveries) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.broker = Objects.requireNonNull(broker);
    this.deliveries = Objects.requireNonNull(deliveries);
  }

  @Override
  public Optional<RelayControl.Result> receipt(
      RelayProjectFiles.Access access, RelayControl.Request request) {
    require(access, request);
    return stored(access, request, false).flatMap(value -> value.result());
  }

  @Override
  public RelayControl.Result apply(
      RelayProjectFiles.Access access,
      RelayControl.Request request,
      Optional<RelayDeliveries.Resolution> resolution) {
    require(access, request);
    Objects.requireNonNull(resolution);
    if (request.action() != RelayControl.Action.RECONCILE && resolution.isPresent())
      throw new IllegalArgumentException("Only reconciliation accepts an owning resolution");
    return transactions.inTransaction(
        () -> {
          jdbc.update(
              """
          INSERT INTO relay_operator_receipts(request_id,project_id,account,fingerprint,topic,action,subscriber,delivery_id,reason)
          VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (request_id) DO NOTHING
          """,
              UUID.fromString(request.requestId()),
              access.projectId(),
              access.account(),
              fingerprint(request),
              request.topic(),
              request.action().name(),
              request.subscriber(),
              request.deliveryId() == null ? null : UUID.fromString(request.deliveryId()),
              request.reason());
          var existing = stored(access, request, true).orElseThrow();
          if (existing.result().isPresent()) return existing.result().get();
          var topic = new Relay.TopicKey(access.projectId(), request.topic());
          String scope = RelayScopeCodec.write(topic);
          if (request.subscriber() != null) {
            // Hold the current lease row before taking a topic lock. This is also a deletion fence.
            jdbc.query(
                "SELECT epoch FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=? FOR UPDATE",
                (row, index) -> row.getLong("epoch"),
                scope,
                topic.name(),
                request.subscriber());
          }
          var generation =
              jdbc
                  .query(
                      "SELECT generation,expired_through FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE",
                      (row, index) ->
                          new TopicState(
                              row.getObject("generation", UUID.class),
                              row.getLong("expired_through")),
                      scope,
                      topic.name())
                  .stream()
                  .findFirst();
          if (generation.isEmpty()
              || !generation.get().generation().toString().equals(request.topicGeneration()))
            throw new CallerFault("Relay topic changed; inspect it again");
          var subscription =
              request.subscriber() == null
                  ? null
                  : new Relay.SubscriptionKey(topic, request.subscriber());
          if (subscription != null) {
            var subscriptionGeneration =
                jdbc
                    .query(
                        "SELECT generation FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=? FOR UPDATE",
                        (row, index) -> row.getObject("generation", UUID.class),
                        scope,
                        topic.name(),
                        request.subscriber())
                    .stream()
                    .findFirst();
            if (subscriptionGeneration.isEmpty()
                || !subscriptionGeneration
                    .get()
                    .toString()
                    .equals(request.subscriptionGeneration()))
              throw new CallerFault("Relay subscription changed; inspect it again");
          }
          String status;
          String seen = null;
          switch (request.action()) {
            case ACKNOWLEDGE_GAP -> {
              if (Long.parseLong(request.expiredThrough()) != generation.get().expiredThrough())
                throw new CallerFault("Relay gap changed; inspect its current boundary");
              var acknowledged =
                  broker.acknowledgeGap(
                      Objects.requireNonNull(subscription),
                      Long.parseLong(request.expiredThrough()),
                      now());
              status = "GAP_ACKNOWLEDGED";
              seen = Long.toString(acknowledged.seenThrough());
            }
            case RECONCILE, ABANDON -> {
              var key =
                  new RelayDeliveries.DeliveryKey(
                      Objects.requireNonNull(subscription), UUID.fromString(request.deliveryId()));
              var current =
                  jdbc
                      .query(
                          "SELECT state,fence FROM relay_deliveries WHERE id=? AND scope_key=? AND topic=? AND subscriber=? FOR UPDATE",
                          (row, index) ->
                              new BranchState(row.getString("state"), row.getLong("fence")),
                          key.id(),
                          scope,
                          topic.name(),
                          request.subscriber())
                      .stream()
                      .findFirst();
              if (current.isEmpty()
                  || !current.get().state().equals(request.expectedState())
                  || current.get().fence() != Long.parseLong(request.fence()))
                throw new CallerFault("Relay delivery changed; inspect it again");
              if (request.action() == RelayControl.Action.RECONCILE) {
                status =
                    resolution.isPresent()
                        ? deliveries.reconcile(key, resolution.get(), now()).state().name()
                        : current.get().state();
              } else {
                status =
                    current.get().state().equals("READY") ? "ABANDONED" : "ABANDONED_UNCERTAIN";
                jdbc.update(
                    "UPDATE relay_deliveries SET state=?,failure_code='operator.abandoned',lease_until=NULL,updated_at=clock_timestamp() WHERE id=?",
                    status,
                    key.id());
              }
            }
            case REMOVE_SUBSCRIPTION -> {
              boolean leased =
                  Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "SELECT EXISTS(SELECT 1 FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=? AND lease_until>clock_timestamp())",
                          Boolean.class,
                          scope,
                          topic.name(),
                          request.subscriber()));
              boolean history =
                  Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          "SELECT EXISTS(SELECT 1 FROM relay_admissions WHERE scope_key=? AND topic=? AND subscriber=?)",
                          Boolean.class,
                          scope,
                          topic.name(),
                          request.subscriber()));
              if (leased || history)
                throw new CallerFault(
                    "Relay subscription still has a live worker or retained input");
              jdbc.update(
                  "DELETE FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
                  scope,
                  topic.name(),
                  request.subscriber());
              status = "REMOVED";
            }
            case REMOVE_TOPIC -> {
              boolean occupied =
                  Boolean.TRUE.equals(
                      jdbc.queryForObject(
                          """
              SELECT EXISTS(SELECT 1 FROM relay_subscriptions WHERE scope_key=? AND topic=?)
                OR EXISTS(SELECT 1 FROM relay_publications WHERE scope_key=? AND topic=?)
              """,
                          Boolean.class,
                          scope,
                          topic.name(),
                          scope,
                          topic.name()));
              if (occupied)
                throw new CallerFault(
                    "Relay topic still has subscriptions or retained publications");
              jdbc.update(
                  "DELETE FROM relay_topics WHERE scope_key=? AND name=?", scope, topic.name());
              status = "REMOVED";
            }
            default -> throw new IllegalStateException("Unhandled Relay control");
          }
          jdbc.update(
              "UPDATE relay_operator_receipts SET status=?,seen_through=?,completed_at=clock_timestamp() WHERE request_id=?",
              status,
              seen == null ? null : Long.parseLong(seen),
              UUID.fromString(request.requestId()));
          return stored(access, request, false).orElseThrow().result().orElseThrow();
        });
  }

  @Override
  public int prune(Duration retention, int limit) {
    new Relay.Policy(retention, null);
    RelayValues.limit(limit, 1000);
    return jdbc.update(
        """
        WITH expired AS (SELECT request_id FROM relay_operator_receipts
          WHERE completed_at<=clock_timestamp()-(? * interval '1 second')
          ORDER BY completed_at,request_id LIMIT ? FOR UPDATE SKIP LOCKED)
        DELETE FROM relay_operator_receipts r USING expired e WHERE r.request_id=e.request_id
        """,
        retention.getSeconds(),
        limit);
  }

  private Optional<Stored> stored(
      RelayProjectFiles.Access access, RelayControl.Request request, boolean lock) {
    return jdbc
        .query(
            "SELECT * FROM relay_operator_receipts WHERE request_id=?"
                + (lock ? " FOR UPDATE" : ""),
            (row, index) -> {
              if (row.getLong("project_id") != access.projectId()
                  || !row.getString("account").equals(access.account())
                  || !row.getString("fingerprint").equals(fingerprint(request)))
                throw new CallerFault("Relay request ID was already used for another operation");
              return new Stored(
                  row.getString("status") == null
                      ? Optional.empty()
                      : Optional.of(result(row, request)));
            },
            UUID.fromString(request.requestId()))
        .stream()
        .findFirst();
  }

  private static RelayControl.Result result(ResultSet row, RelayControl.Request request)
      throws SQLException {
    Long seen = row.getObject("seen_through", Long.class);
    return new RelayControl.Result(
        request.requestId(),
        request.project(),
        request.topic(),
        request.action(),
        request.subscriber(),
        request.deliveryId(),
        row.getString("status"),
        seen == null ? null : seen.toString(),
        row.getObject("completed_at", OffsetDateTime.class).toInstant());
  }

  private static void require(RelayProjectFiles.Access access, RelayControl.Request request) {
    Objects.requireNonNull(access);
    Objects.requireNonNull(request);
    if (!access.project().equals(request.project()))
      throw new IllegalArgumentException("Foreign Relay control project");
  }

  private static String fingerprint(RelayControl.Request request) {
    // Validated identities/reasons cannot contain NUL; explicit separators preserve field identity.
    String source =
        String.join(
            "\0",
            request.project(),
            request.topic(),
            request.topicGeneration(),
            request.action().name(),
            Objects.toString(request.subscriber(), ""),
            Objects.toString(request.subscriptionGeneration(), ""),
            Objects.toString(request.deliveryId(), ""),
            Objects.toString(request.fence(), ""),
            Objects.toString(request.expiredThrough(), ""),
            Objects.toString(request.expectedState(), ""),
            request.reason());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  private Instant now() {
    return Objects.requireNonNull(
            jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class))
        .toInstant();
  }

  private record Stored(Optional<RelayControl.Result> result) {}

  private record TopicState(UUID generation, long expiredThrough) {}

  private record BranchState(String state, long fence) {}
}
