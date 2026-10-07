package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Topic then batch locks serialize issuance/ack with retention. No lock spans an SDK wait. */
public final class JdbcRelayPortRepository implements RelayPortRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;

  public JdbcRelayPortRepository(JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
  }

  private record Issued(
      String account,
      String consumer,
      String batch,
      long fence,
      long through,
      Long gap,
      Instant until,
      boolean acknowledged) {}

  @Override
  public RelayPort.Batch consume(Relay.TopicKey topic, String account, RelayPort.Consume request) {
    Objects.requireNonNull(request);
    RelayPort.identity(account);
    return transactions.inTransaction(
        () -> {
          String scope = lock(topic);
          var key = new Relay.SubscriptionKey(topic, "sdk." + request.group());
          relay.subscribe(
              key,
              request.start() == RelayPort.Start.LATEST
                  ? Relay.Start.LATEST
                  : Relay.Start.OLDEST_RETAINED);
          Instant now = now();
          var rows = issued(scope, topic.name(), key.subscriber());
          Issued old = rows.isEmpty() ? null : rows.getFirst();
          var read = relay.read(key, request.limit() == null ? 100 : request.limit());
          if (old != null && !old.acknowledged() && old.until().isAfter(now)) {
            if (!old.account().equals(account) || !old.consumer().equals(request.consumerId()))
              return batch(
                  request,
                  RelayPort.Status.BUSY,
                  null,
                  0,
                  read.subscription().seenThrough(),
                  null,
                  null,
                  List.of());
            // Repeat the same token and exact boundary. A smaller later limit must not hide records
            // covered by that token. A new retention gap invalidates the original acknowledgement.
            read = relay.read(key, 100);
            if (read.gap().isEmpty() && old.gap() == null) {
              var events =
                  read.publications().stream()
                      .filter(p -> p.position() <= old.through())
                      .map(RelayPortEvents::event)
                      .toList();
              if (events.size() > (request.limit() == null ? 100 : request.limit()))
                throw new CallerFault("Relay limit is smaller than the outstanding batch");
              if (!events.isEmpty())
                return batch(
                    request,
                    RelayPort.Status.DATA,
                    old.batch(),
                    old.fence(),
                    old.through(),
                    old.until(),
                    null,
                    events);
            } else if (old.gap() != null
                && read.gap().isPresent()
                && old.gap() == read.gap().get().throughInclusive())
              return batch(
                  request,
                  RelayPort.Status.GAP,
                  old.batch(),
                  old.fence(),
                  old.through(),
                  old.until(),
                  old.gap(),
                  List.of());
          }
          long through = read.subscription().seenThrough();
          Long gap = read.gap().map(Relay.Gap::throughInclusive).orElse(null);
          var events =
              gap == null
                  ? RelayPortEvents.bounded(read.publications())
                  : List.<io.aeyer.plowshare.protocol.RelayLog.Event>of();
          if (gap == null && events.isEmpty())
            return batch(request, RelayPort.Status.EMPTY, null, 0, through, null, null, events);
          if (!events.isEmpty()) through = Long.parseLong(events.getLast().position());
          String token = UUID.randomUUID().toString();
          long fence = old == null ? 1 : Math.addExact(old.fence(), 1);
          Instant until = now.plusSeconds(30);
          jdbc.update(
              """
          INSERT INTO relay_sdk_batches(scope_key,topic,subscriber,account,consumer_id,batch_id,fence,through_position,gap_through,lease_until,acknowledged)
          VALUES (?,?,?,?,?::uuid,?::uuid,?,?,?,?,false)
          ON CONFLICT(scope_key,topic,subscriber) DO UPDATE SET account=EXCLUDED.account,
            consumer_id=EXCLUDED.consumer_id,batch_id=EXCLUDED.batch_id,fence=EXCLUDED.fence,
            through_position=EXCLUDED.through_position,gap_through=EXCLUDED.gap_through,
            lease_until=EXCLUDED.lease_until,acknowledged=false
          """,
              scope,
              topic.name(),
              key.subscriber(),
              account,
              request.consumerId(),
              token,
              fence,
              through,
              gap,
              java.sql.Timestamp.from(until));
          return batch(
              request,
              gap == null ? RelayPort.Status.DATA : RelayPort.Status.GAP,
              token,
              fence,
              through,
              until,
              gap,
              events);
        });
  }

  @Override
  public RelayPort.Acknowledged acknowledge(
      Relay.TopicKey topic, String account, RelayPort.Ack request) {
    Objects.requireNonNull(request);
    RelayPort.identity(account);
    return transactions.inTransaction(
        () -> {
          String scope = lock(topic);
          String subscriber = "sdk." + request.group();
          var rows = issued(scope, topic.name(), subscriber);
          if (rows.isEmpty()) throw refused();
          var row = rows.getFirst();
          if (!row.account().equals(account)
              || !row.consumer().equals(request.consumerId())
              || !row.batch().equals(request.batchId())
              || row.fence() != Long.parseLong(request.fence())
              || !Objects.equals(
                  row.gap() == null ? null : row.gap().toString(), request.expiredThrough()))
            throw refused();
          var key = new Relay.SubscriptionKey(topic, subscriber);
          long through = row.gap() == null ? row.through() : row.gap();
          if (!row.acknowledged()) {
            if (!row.until().isAfter(now())) throw refused();
            if (row.gap() != null) relay.acknowledgeGap(key, row.gap());
            else relay.advanceSeen(key, through);
            jdbc.update(
                "UPDATE relay_sdk_batches SET acknowledged=true WHERE scope_key=? AND topic=? AND subscriber=?",
                scope,
                topic.name(),
                subscriber);
          }
          return new RelayPort.Acknowledged(
              request.project(),
              request.topic(),
              request.group(),
              request.batchId(),
              Long.toString(through),
              row.gap() != null);
        });
  }

  private String lock(Relay.TopicKey topic) {
    Objects.requireNonNull(topic);
    if (!(topic.scope() instanceof Relay.ProjectScope)) throw refused();
    String scope = RelayScopeCodec.write(topic);
    if (jdbc.query(
            "SELECT name FROM relay_topics WHERE scope_key=? AND name=? FOR UPDATE",
            (row, i) -> row.getString(1),
            scope,
            topic.name())
        .isEmpty()) throw new CallerFault("Relay topic unavailable");
    return scope;
  }

  private List<Issued> issued(String scope, String topic, String subscriber) {
    return jdbc.query(
        "SELECT * FROM relay_sdk_batches WHERE scope_key=? AND topic=? AND subscriber=? FOR UPDATE",
        (r, i) ->
            new Issued(
                r.getString("account"),
                r.getString("consumer_id"),
                r.getString("batch_id"),
                r.getLong("fence"),
                r.getLong("through_position"),
                r.getObject("gap_through", Long.class),
                r.getObject("lease_until", OffsetDateTime.class).toInstant(),
                r.getBoolean("acknowledged")),
        scope,
        topic,
        subscriber);
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
  }

  private static CallerFault refused() {
    return new CallerFault("Relay batch acknowledgement is stale or unavailable");
  }

  private static RelayPort.Batch batch(
      RelayPort.Consume r,
      RelayPort.Status status,
      String token,
      long fence,
      long through,
      Instant until,
      Long gap,
      List<io.aeyer.plowshare.protocol.RelayLog.Event> events) {
    return new RelayPort.Batch(
        r.project(),
        r.topic(),
        r.group(),
        r.consumerId(),
        status,
        token,
        token == null ? null : Long.toString(fence),
        Long.toString(through),
        until,
        gap == null ? null : gap.toString(),
        events);
  }
}
