package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.access.OperationFence;
import io.aeyer.plowshare.server.archive.JdbcTurnRelaySources;
import io.aeyer.plowshare.server.archive.TurnRelaySources;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.llm.accounting.UsageAttribution;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Atomic local broker effects; receipt rows outlive publications and do not pin broker payloads.
 */
public final class JdbcRelayPublishingRepository implements RelayPublishingRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;
  private final ExecutionPublicationObserver observer;
  private final TurnRelaySources sources;

  public JdbcRelayPublishingRepository(JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    this(
        jdbc,
        transactions,
        relay,
        ExecutionPublicationObserver.NONE,
        new JdbcTurnRelaySources(jdbc));
  }

  public JdbcRelayPublishingRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      ExecutionPublicationObserver observer) {
    this(jdbc, transactions, relay, observer, new JdbcTurnRelaySources(jdbc));
  }

  public JdbcRelayPublishingRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      ExecutionPublicationObserver observer,
      TurnRelaySources sources) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
    this.observer = Objects.requireNonNull(observer);
    this.sources = Objects.requireNonNull(sources);
  }

  @Override
  public Optional<RelayCausation> ancestry(long project, UsageAttribution owner) {
    return sources.ancestry(project, owner);
  }

  @Override
  public RelayPublishing.Receipt publish(Intent intent, OperationFence fence) {
    Objects.requireNonNull(intent);
    Objects.requireNonNull(fence);
    return transactions.inTransaction(
        () -> {
          var owner = intent.owner();
          // INSERT serializes same-call contenders before topic locks. An uncommitted/incomplete
          // receipt cannot escape; failure in any broker/ledger write rolls the whole unit back.
          boolean inserted =
              jdbc.update(
                      """
          INSERT INTO relay_execution_publications
            (id,project_id,account,agent,conversation_id,run_id,turn_ordinal,step_ordinal,call_id,topic,fingerprint)
          VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING
          """,
                      intent.id(),
                      intent.project(),
                      owner.accountHandle(),
                      owner.agentName(),
                      owner.conversations().id(),
                      owner.runs().id(),
                      owner.turnOrdinal(),
                      owner.stepOrdinal(),
                      intent.call(),
                      intent.request().topic(),
                      intent.fingerprint())
                  == 1;
          // The run's live authority, inside this transaction and after the receipt-row insert
          // (the lock this unit takes) but before any append or receipt read. A refusal rolls the
          // inserted row back with the rest of the unit, and a retained receipt is not disclosed.
          fence.require();
          if (inserted) {
            // Native requester allocation precedes export, joining this same rollback boundary.
            // Retained repeats return their old receipt without minting another native request.
            observer.beforeAppend(intent);
            var topic = new Relay.TopicKey(intent.project(), intent.request().topic());
            // A grant permits creating its exact TEXT topic with the standard initial policy, never
            // changing an existing kind or retention policy.
            relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
            var publication =
                relay.publish(
                    topic,
                    new Relay.Draft(
                        intent.id().toString(),
                        RelayExecutionPublisher.identity(owner.accountHandle(), owner.agentName()),
                        intent.occurredAt(),
                        intent.id().toString(),
                        intent.causation().parentId(),
                        new RelayPayload.Text(intent.request().text()),
                        intent.causation()));
            if (jdbc.update(
                    """
            UPDATE relay_execution_publications SET position=?,published_at=? WHERE id=?
            """,
                    publication.position(),
                    Timestamp.from(publication.publishedAt()),
                    intent.id())
                != 1)
              throw new IllegalStateException("Publication receipt disappeared under its lock");
            return new RelayPublishing.Receipt(
                intent.id(),
                topic.name(),
                Long.toString(publication.position()),
                publication.publishedAt());
          }
          return jdbc.queryForObject(
              """
          SELECT project_id,account,fingerprint,topic,position,published_at
          FROM relay_execution_publications WHERE id=? FOR UPDATE
          """,
              (row, index) -> {
                if (row.getLong("project_id") != intent.project()
                    || !owner.accountHandle().equals(row.getString("account"))
                    || !intent.fingerprint().equals(row.getString("fingerprint")))
                  throw new IllegalArgumentException(
                      "Relay publication call identity was reused for different work");
                var published = row.getTimestamp("published_at");
                if (published == null || row.getLong("position") < 1)
                  throw new IllegalStateException("Publication has no committed receipt");
                return new RelayPublishing.Receipt(
                    intent.id(),
                    row.getString("topic"),
                    Long.toString(row.getLong("position")),
                    published.toInstant());
              },
              intent.id());
        });
  }
}
