package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
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

  public JdbcRelayPublishingRepository(JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
  }

  @Override
  public Optional<RelayCausation> ancestry(long project, UsageAttribution owner) {
    // Accounting run IDs differ from job IDs. Native scripts also retain run ownership before
    // dispatch; no model call is required to establish this provenance.
    return jdbc
        .query(
            """
        SELECT c.relay_causation::text
        FROM conversations c JOIN inference_run_ownership o ON o.conversation_id=c.id
        WHERE c.project_id=? AND c.id=? AND o.turn_ordinal=?
          AND o.attribution->>'accountHandle'=? AND o.attribution->>'projectId'=?
          AND o.attribution->'runs'->>'id'=? AND o.attribution->>'agentName'=?
          AND o.attribution->>'status'='ATTRIBUTED'
        """,
            (row, index) -> RelayCausationCodec.read(row.getString(1)),
            project,
            owner.conversations().id(),
            owner.turnOrdinal(),
            owner.accountHandle(),
            Long.toString(project),
            owner.runs().id(),
            owner.agentName())
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  @Override
  public RelayPublishing.Receipt publish(Intent intent) {
    Objects.requireNonNull(intent);
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
          if (inserted) {
            var topic = new Relay.TopicKey(intent.project(), intent.request().topic());
            // A grant permits creating its exact TEXT topic with the standard initial policy, never
            // changing an existing kind or retention policy.
            relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
            var publication =
                relay.publish(
                    topic,
                    new Relay.Draft(
                        intent.id().toString(),
                        RelayPort.publisher(owner.accountHandle()),
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
