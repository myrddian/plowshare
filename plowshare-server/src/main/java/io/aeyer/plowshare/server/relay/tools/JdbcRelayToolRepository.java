package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable intent survives broker cleanup; row locks serialize completion and conflicting reuse. */
public final class JdbcRelayToolRepository implements RelayToolRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;
  private final HostedInvocationAuthority hosting;

  public JdbcRelayToolRepository(JdbcTemplate jdbc, UnitOfWork transactions, Relay relay) {
    this(jdbc, transactions, relay, HostedInvocationAuthority.NONE);
  }

  public JdbcRelayToolRepository(
      JdbcTemplate jdbc, UnitOfWork transactions, Relay relay, HostedInvocationAuthority hosting) {
    this.hosting = Objects.requireNonNull(hosting);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
  }

  @Override
  public java.util.Optional<RelayCausation> ancestry(
      long projectId, io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
    // Accounting run IDs are not job IDs. Match the retained turn ownership before inheriting
    // the conversation's immutable Relay ancestry (also inherited by delegated conversations).
    return jdbc
        .query(
            """
        SELECT c.relay_causation::text
        FROM conversations c JOIN inference_run_ownership o ON o.conversation_id=c.id
        WHERE c.project_id=? AND c.id=? AND o.turn_ordinal=?
          AND o.attribution->>'accountHandle'=? AND o.attribution->>'projectId'=?
          AND o.attribution->'runs'->>'id'=? AND o.attribution->>'status'='ATTRIBUTED'
        """,
            (row, ordinal) -> RelayCausationCodec.read(row.getString(1)),
            projectId,
            owner.conversations().id(),
            owner.turnOrdinal(),
            owner.accountHandle(),
            Long.toString(projectId),
            owner.runs().id())
        .stream()
        .filter(Objects::nonNull)
        .findFirst();
  }

  @Override
  public Stored submit(Intent intent) {
    return transactions.inTransaction(
        () -> {
          var request = intent.request();
          var supplied = intent;
          if (io.aeyer.plowshare.protocol.HostedToolScopes.hosted(intent.binding().provider())) {
            var existing = find(intent.projectId(), request.account(), intent.id(), false);
            // An existing immutable intent never acquires a new credential selection or publishes.
            if (existing.isPresent()) return locked(intent);
            supplied =
                new Intent(
                    intent.id(),
                    intent.projectId(),
                    intent.fingerprint(),
                    intent.binding(),
                    request,
                    intent.occurredAt(),
                    intent.causation(),
                    intent.agent(),
                    hosting.pin(intent));
          }
          boolean inserted =
              jdbc.update(
                      """
          INSERT INTO relay_tool_invocations(id,project_id,account,run_id,call_id,fingerprint,binding,request,occurred_at,causation,agent,hosted)
          VALUES(?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) ON CONFLICT(id) DO NOTHING
          """,
                      intent.id(),
                      intent.projectId(),
                      request.account(),
                      request.run(),
                      request.call(),
                      intent.fingerprint(),
                      RelayToolCodec.write(intent.binding()),
                      RelayToolCodec.write(request),
                      Timestamp.from(intent.occurredAt()),
                      RelayCausationCodec.write(intent.causation()),
                      intent.agent(),
                      supplied.hosted() == null ? null : RelayToolCodec.write(supplied.hosted()))
                  == 1;
          var retained = locked(intent);
          if (inserted) {
            var topic = new Relay.TopicKey(intent.projectId(), intent.binding().requests());
            relay.registerTopic(topic, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault());
            relay.registerTopic(
                new Relay.TopicKey(intent.projectId(), intent.binding().results()),
                RelayPayload.Kind.TEXT,
                Relay.Policy.systemDefault());
            relay.publish(
                topic,
                new Relay.Draft(
                    intent.id().toString(),
                    "tool-runtime",
                    intent.occurredAt(),
                    intent.id().toString(),
                    intent.causation().parentId(),
                    new RelayPayload.Text(RelayToolCodec.write(request)),
                    intent.causation()));
          }
          return retained;
        });
  }

  @Override
  public Stored finish(Intent intent, RelayToolCodec.Result result) {
    Objects.requireNonNull(result);
    if (!result.invocationId().equals(intent.id().toString())
        || !result.project().equals(intent.binding().project())
        || !result.provider().equals(intent.binding().provider())
        || !result.tool().equals(intent.binding().name())
        || RelayToolCodec.write(result).length() > 32768)
      throw new IllegalArgumentException("Provider completion belongs to another invocation");
    return transactions.inTransaction(
        () -> {
          var retained = locked(intent);
          if (retained.result() == null) {
            jdbc.update(
                "UPDATE relay_tool_invocations SET result=?,completed_at=now() WHERE id=?",
                RelayToolCodec.write(result),
                intent.id());
            return new Stored(retained.intent(), result);
          }
          if (!retained.result().equals(result))
            throw new IllegalStateException("Conflicting provider completion");
          return retained;
        });
  }

  private Stored locked(Intent supplied) {
    return jdbc
        .query(
            """
        SELECT project_id,account,fingerprint,binding,request,occurred_at,causation::text,agent,hosted::text,result
        FROM relay_tool_invocations WHERE id=? FOR UPDATE
        """,
            (row, ordinal) -> {
              if (row.getLong("project_id") != supplied.projectId()
                  || !row.getString("account").equals(supplied.request().account())
                  || !row.getString("fingerprint").equals(supplied.fingerprint()))
                throw new IllegalArgumentException(
                    "Tool invocation identity was reused for different work");
              var original =
                  new Intent(
                      supplied.id(),
                      supplied.projectId(),
                      supplied.fingerprint(),
                      RelayToolCodec.definition(row.getString("binding")),
                      RelayToolCodec.request(row.getString("request")),
                      row.getTimestamp("occurred_at").toInstant(),
                      RelayCausationCodec.read(row.getString("causation")),
                      row.getString("agent"),
                      RelayToolCodec.hosted(row.getString("hosted")));
              var result = row.getString("result");
              return new Stored(original, result == null ? null : RelayToolCodec.result(result));
            },
            supplied.id())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Missing tool intent"));
  }

  @Override
  public java.util.Optional<Stored> find(long projectId, String account, java.util.UUID id) {
    return find(projectId, account, id, false);
  }

  @Override
  public java.util.Optional<Stored> find(
      long projectId, String account, java.util.UUID id, boolean lock) {
    return jdbc
        .query(
            """
        SELECT fingerprint,binding,request,occurred_at,causation::text,agent,hosted::text,result FROM relay_tool_invocations
        WHERE id=? AND project_id=? AND account=?
        """
                + (lock ? " FOR UPDATE" : ""),
            (row, ordinal) -> {
              var intent =
                  new Intent(
                      id,
                      projectId,
                      row.getString("fingerprint"),
                      RelayToolCodec.definition(row.getString("binding")),
                      RelayToolCodec.request(row.getString("request")),
                      row.getTimestamp("occurred_at").toInstant(),
                      RelayCausationCodec.read(row.getString("causation")),
                      row.getString("agent"),
                      RelayToolCodec.hosted(row.getString("hosted")));
              var result = row.getString("result");
              return new Stored(intent, result == null ? null : RelayToolCodec.result(result));
            },
            id,
            projectId,
            account)
        .stream()
        .findFirst();
  }
}
