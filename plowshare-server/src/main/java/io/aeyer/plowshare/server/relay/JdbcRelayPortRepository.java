package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
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
  public void rememberPublisher(String account) {
    RelayPort.identity(account);
    jdbc.update(
        "INSERT INTO relay_port_publishers(publisher,account) VALUES(?,?) ON CONFLICT(publisher) DO NOTHING",
        RelayPort.publisher(account),
        account);
  }

  @Override
  public Optional<String> publisherAccount(String publisher) {
    RelayPort.identity(publisher);
    return jdbc
        .query(
            "SELECT account FROM relay_port_publishers WHERE publisher=?",
            (row, number) -> row.getString(1),
            publisher)
        .stream()
        .filter(account -> RelayPort.publisher(account).equals(publisher))
        .findFirst();
  }

  @Override
  public boolean executionPublication(Relay.Publication publication) {
    return RelayExecutionPublisher.reserved(publication.event().publisher())
        || executionIdentity(publication).isPresent();
  }

  @Override
  public Optional<Execution> executionIdentity(Relay.Publication publication) {
    Objects.requireNonNull(publication, "publication");
    if (!(publication.topic().scope() instanceof Relay.ProjectScope)) return Optional.empty();
    try {
      if (!UUID.fromString(publication.event().eventId())
          .toString()
          .equals(publication.event().eventId())) return Optional.empty();
    } catch (IllegalArgumentException ordinaryEvent) {
      return Optional.empty();
    }
    // All immutable receipt coordinates are required. A matching UUID on another SDK topic is
    // never sufficient to acquire an execution's account or definition authority.
    return jdbc
        .query(
            """
        SELECT account,agent FROM relay_execution_publications
        WHERE id=?::uuid AND project_id=? AND topic=? AND position=? AND published_at=?
        """,
            (row, number) -> new Execution(row.getString(1), row.getString(2)),
            publication.event().eventId(),
            publication.topic().projectId(),
            publication.topic().name(),
            publication.position(),
            Timestamp.from(publication.publishedAt()))
        .stream()
        .filter(
            identity ->
                RelayExecutionPublisher.identity(identity.account(), identity.definition())
                        .equals(publication.event().publisher())
                    || RelayPort.publisher(identity.account())
                        .equals(publication.event().publisher()))
        .findFirst();
  }

  @Override
  public RelayPort.Batch consume(
      Relay.TopicKey topic, String account, RelayPort.Consume request, RelayReadBudget budget) {
    return consumeObserved(topic, account, request, budget, null).batch();
  }

  @Override
  public Consumed consumeObserved(
      Relay.TopicKey topic,
      String account,
      RelayPort.Consume request,
      RelayReadBudget budget,
      UUID expectedGeneration) {
    Objects.requireNonNull(budget);
    Objects.requireNonNull(request);
    RelayPort.identity(account);
    return transactions.inTransaction(
        () -> {
          String scope = lock(topic);
          var key = new Relay.SubscriptionKey(topic, "sdk." + request.group());
          var subscription =
              relay.subscribe(
                  key,
                  request.start() == RelayPort.Start.LATEST
                      ? Relay.Start.LATEST
                      : Relay.Start.OLDEST_RETAINED);
          if (expectedGeneration != null && !expectedGeneration.equals(subscription.generation()))
            throw new CallerFault("Relay group changed while waiting");
          Function<RelayPort.Batch, Consumed> stamp =
              value -> new Consumed(value, subscription.generation());
          Instant now = now();
          var rows = issued(scope, topic.name(), key.subscriber());
          Issued old = rows.isEmpty() ? null : rows.getFirst();
          // A retry must recover its complete retained boundary even if the caller now asks for
          // fewer rows. Decide before reading so that retry does not discard and reload a page.
          boolean ownOutstanding =
              old != null
                  && !old.acknowledged()
                  && old.until().isAfter(now)
                  && old.account().equals(account)
                  && old.consumer().equals(request.consumerId());
          var read =
              relay.read(
                  key, ownOutstanding ? 100 : (request.limit() == null ? 100 : request.limit()));
          if (old != null && !old.acknowledged() && old.until().isAfter(now)) {
            if (!old.account().equals(account) || !old.consumer().equals(request.consumerId()))
              return stamp.apply(
                  batch(
                      request,
                      subscription.generation(),
                      RelayPort.Status.BUSY,
                      null,
                      0,
                      read.subscription().seenThrough(),
                      null,
                      null,
                      List.of()));
            // Repeat the same token and exact boundary. A smaller later limit must not hide records
            // covered by that token. A new retention gap invalidates the original acknowledgement.
            if (read.gap().isEmpty() && old.gap() == null) {
              var events =
                  read.publications().stream()
                      .filter(p -> p.position() <= old.through())
                      .map(RelayPortEvents::event)
                      .toList();
              if (events.size() > (request.limit() == null ? 100 : request.limit()))
                throw new CallerFault("Relay limit is smaller than the outstanding batch");
              if (RelayPortEvents.boundedEvents(events, budget).size() != events.size())
                throw new CallerFault(
                    "Transport allowance is smaller than the outstanding Relay batch; use segmented transport");
              if (!events.isEmpty())
                return stamp.apply(
                    batch(
                        request,
                        subscription.generation(),
                        RelayPort.Status.DATA,
                        old.batch(),
                        old.fence(),
                        old.through(),
                        old.until(),
                        null,
                        events));
            } else if (old.gap() != null
                && read.gap().isPresent()
                && old.gap() == read.gap().get().throughInclusive())
              return stamp.apply(
                  batch(
                      request,
                      subscription.generation(),
                      RelayPort.Status.GAP,
                      old.batch(),
                      old.fence(),
                      old.through(),
                      old.until(),
                      old.gap(),
                      List.of()));
          }
          long through = read.subscription().seenThrough();
          Long gap = read.gap().map(Relay.Gap::throughInclusive).orElse(null);
          var events =
              gap == null
                  ? RelayPortEvents.bounded(read.publications(), budget)
                  : List.<RelayLog.Event>of();
          if (gap == null && events.isEmpty())
            return stamp.apply(
                batch(
                    request,
                    subscription.generation(),
                    RelayPort.Status.EMPTY,
                    null,
                    0,
                    through,
                    null,
                    null,
                    events));
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
              Timestamp.from(until));
          return stamp.apply(
              batch(
                  request,
                  subscription.generation(),
                  gap == null ? RelayPort.Status.DATA : RelayPort.Status.GAP,
                  token,
                  fence,
                  through,
                  until,
                  gap,
                  events));
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

  @Override
  public RelayPort.GroupDeleted deleteGroup(
      Relay.TopicKey topic, String account, RelayPort.DeleteGroup request) {
    Objects.requireNonNull(request);
    RelayPort.identity(account);
    return transactions.inTransaction(
        () -> {
          String scope = lock(topic);
          String subscriber = "sdk." + request.group();
          record Group(UUID generation, long seen, long last) {}
          var current =
              jdbc
                  .query(
                      """
          SELECT s.generation,s.seen_through,t.last_position FROM relay_subscriptions s
          JOIN relay_topics t ON t.scope_key=s.scope_key AND t.name=s.topic
          WHERE s.scope_key=? AND s.topic=? AND s.subscriber=? FOR UPDATE OF s
          """,
                      (row, index) ->
                          new Group(
                              row.getObject("generation", UUID.class),
                              row.getLong("seen_through"),
                              row.getLong("last_position")),
                      scope,
                      topic.name(),
                      subscriber)
                  .stream()
                  .findFirst();
          if (current.isEmpty())
            return new RelayPort.GroupDeleted(
                request.project(), request.topic(), request.group(), request.generation(), false);
          var group = current.get();
          if (!group.generation().equals(UUID.fromString(request.generation())))
            throw new CallerFault("Relay group changed; inspect it again");
          if (group.seen() < group.last())
            throw new CallerFault(
                "Relay group still has unread publications or an unacknowledged gap");
          // Batch issuance and acknowledgement take this same topic lock. A live delivery remains
          // protected even when another authorized account requests retirement of the shared group.
          boolean protectedWork =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      """
              SELECT EXISTS(SELECT 1 FROM relay_sdk_batches WHERE scope_key=? AND topic=? AND subscriber=? AND NOT acknowledged AND lease_until>clock_timestamp())
                OR EXISTS(SELECT 1 FROM relay_admissions WHERE scope_key=? AND topic=? AND subscriber=?)
                OR EXISTS(SELECT 1 FROM relay_consumer_leases WHERE scope_key=? AND topic=? AND subscriber=? AND lease_until>clock_timestamp())
                OR EXISTS(SELECT 1 FROM relay_native_consumers WHERE scope_key=? AND topic=? AND subscriber=?)
                OR EXISTS(SELECT 1 FROM relay_approval_gap_recoveries WHERE scope_key=? AND topic=? AND subscriber=?)
              """,
                      Boolean.class,
                      scope,
                      topic.name(),
                      subscriber,
                      scope,
                      topic.name(),
                      subscriber,
                      scope,
                      topic.name(),
                      subscriber,
                      scope,
                      topic.name(),
                      subscriber,
                      scope,
                      topic.name(),
                      subscriber));
          if (protectedWork) throw new CallerFault("Relay group still has live or retained work");
          int removed =
              jdbc.update(
                  "DELETE FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=? AND generation=?::uuid",
                  scope,
                  topic.name(),
                  subscriber,
                  request.generation());
          if (removed != 1)
            throw new IllegalStateException("Relay group disappeared under its lock");
          return new RelayPort.GroupDeleted(
              request.project(), request.topic(), request.group(), request.generation(), true);
        });
  }

  @Override
  public RelayPort.GroupState inspectGroup(
      Relay.TopicKey topic, String account, RelayPort.GroupQuery query) {
    Objects.requireNonNull(topic);
    Objects.requireNonNull(query);
    RelayPort.identity(account);
    if (!(topic.scope() instanceof Relay.ProjectScope))
      throw new CallerFault("SDK groups require project scope");
    String generation =
        jdbc
            .query(
                "SELECT generation FROM relay_subscriptions WHERE scope_key=? AND topic=? AND subscriber=?",
                (row, index) -> row.getObject(1, UUID.class).toString(),
                RelayScopeCodec.write(topic),
                topic.name(),
                "sdk." + query.group())
            .stream()
            .findFirst()
            .orElse(null);
    return new RelayPort.GroupState(
        query.project(), query.topic(), query.group(), generation, generation != null);
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
      UUID generation,
      RelayPort.Status status,
      String token,
      long fence,
      long through,
      Instant until,
      Long gap,
      List<RelayLog.Event> events) {
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
        events,
        generation.toString());
  }
}
