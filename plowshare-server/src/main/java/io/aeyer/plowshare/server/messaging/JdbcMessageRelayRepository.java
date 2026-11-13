package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Specialist ingress/outbox store. Snapshot transactions never lock a native route after a task.
 */
public final class JdbcMessageRelayRepository implements MessageRelayRepository {
  private final JdbcTemplate jdbc;

  public JdbcMessageRelayRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void retainFamily(Family family) {
    jdbc.update(
        """
        INSERT INTO message_relay_families(namespace,account,project,project_id,publisher,source_topic,
          subscriber,source_generation,channel,execution_definition,agent,reply_topic,reply_generation)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(namespace) DO NOTHING
        """,
        family.namespace(),
        family.account(),
        family.project(),
        family.projectId(),
        family.publisher(),
        family.sourceTopic(),
        family.subscriber(),
        family.sourceGeneration(),
        family.channel(),
        family.executionDefinition(),
        family.agent(),
        family.replyTopic(),
        family.replyGeneration());
    var retained =
        jdbc
            .query(
                "SELECT * FROM message_relay_families WHERE namespace=?",
                (row, index) -> family(row),
                family.namespace())
            .stream()
            .findFirst()
            .orElseThrow();
    if (!retained.equals(family))
      throw new MessageRefused("The native channel target or return binding changed");
  }

  @Override
  public void migrate(Family family, String client) {
    if (family.executionDefinition() != null
        || !RelayPort.publisher(family.account()).equals(family.publisher()))
      throw new IllegalArgumentException("Legacy ingress belongs to an authenticated SDK account");
    boolean first =
        jdbc.update(
                "INSERT INTO message_relay_legacy_migrations(namespace,client) VALUES(?,?) ON CONFLICT DO NOTHING",
                family.namespace(),
                client)
            == 1;
    if (!first) return;
    // Same native coordinator as receive. Exact context/task IDs and protocol evidence never move
    // to another account, project, client or receiving definition.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "mailbox:" + family.account());
    if (Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM message_external_contexts WHERE account=? AND project=? AND client=?
          AND channel='legacy' AND agent<>?)
        """,
            Boolean.class,
            family.account(),
            family.project(),
            client,
            family.agent())))
      throw new MessageRefused(
          "The configured legacy mapping changes its retained receiving definition");
    jdbc.update(
        """
        UPDATE message_external_contexts SET channel=?
        WHERE account=? AND project=? AND client=? AND channel='legacy' AND agent=?
        """,
        family.namespace(),
        family.account(),
        family.project(),
        client,
        family.agent());
    jdbc.update(
        """
        UPDATE message_external_tasks t SET channel=? FROM message_external_contexts c
        WHERE c.id=t.context AND t.account=? AND t.project=? AND t.client=? AND t.channel='legacy'
          AND c.channel=? AND c.account=t.account AND c.project=t.project AND c.client=t.client AND c.agent=?
        """,
        family.namespace(),
        family.account(),
        family.project(),
        client,
        family.namespace(),
        family.agent());
  }

  @Override
  public Optional<MessageRelayPayload.Snapshot> prior(Intent intent) {
    // The operation key is acquired before native mailbox/request locks. Async updates use their
    // own identities and never acquire this key while holding a task revision row.
    jdbc.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        "message-relay:"
            + intent.family().namespace()
            + ":"
            + intent.client()
            + ":"
            + intent.requestId());
    var duplicate = receipt(intent.delivery());
    if (duplicate.isPresent()) return duplicate;
    record Prior(
        String identity, String fingerprint, String source, String receiver, String snapshot) {}
    var retained =
        jdbc
            .query(
                """
        SELECT identity,fingerprint,source_event,receiver,snapshot FROM message_relay_operations
        WHERE namespace=? AND client=? AND request_id=? AND delivery IS NOT NULL
          AND receiver<>'message.conflict'
        """,
                (row, index) ->
                    new Prior(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4),
                        row.getString(5)),
                intent.family().namespace(),
                intent.client(),
                intent.requestId())
            .stream()
            .findFirst();
    if (retained.isEmpty()) return Optional.empty();
    var previous = retained.orElseThrow();
    if (!previous.fingerprint().equals(intent.fingerprint())
        || !previous.source().equals(intent.sourceEvent())
        || !previous.receiver().equals(intent.receiver())) throw new Conflict();
    jdbc.update(
        "INSERT INTO message_relay_delivery_receipts(delivery,operation) VALUES(?,?) ON CONFLICT DO NOTHING",
        intent.delivery(),
        previous.identity());
    return Optional.of(MessageRelayCodec.stored(previous.snapshot()));
  }

  @Override
  public Optional<MessageRelayPayload.Snapshot> receipt(UUID delivery) {
    return jdbc
        .query(
            "SELECT o.snapshot FROM message_relay_delivery_receipts d JOIN message_relay_operations o ON o.identity=d.operation WHERE d.delivery=?",
            (row, index) -> MessageRelayCodec.stored(row.getString(1)),
            delivery)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<MessageRelayPayload.Snapshot> receipt(
      UUID delivery, long project, String publisher, String topic, String event) {
    return jdbc
        .query(
            """
        SELECT o.snapshot FROM message_relay_delivery_receipts d JOIN message_relay_operations o ON o.identity=d.operation
        JOIN message_relay_families f ON f.namespace=o.namespace
        WHERE d.delivery=? AND f.project_id=? AND f.publisher=? AND f.source_topic=? AND o.source_event=?
        """,
            (row, index) -> MessageRelayCodec.stored(row.getString(1)),
            delivery,
            project,
            publisher,
            topic,
            event)
        .stream()
        .findFirst();
  }

  @Override
  public void retain(Intent intent, MessageRelayPayload.Snapshot snapshot) {
    String receiver =
        "REQUEST_CONFLICT".equals(snapshot.refusal()) ? "message.conflict" : intent.receiver();
    jdbc.update(
        """
        INSERT INTO message_relay_operations(identity,delivery,namespace,client,request_id,source_event,
          receiver,fingerprint,snapshot,occurred_at,relay_causation)
        VALUES(?,?,?,?,?,?,?,?,?,?,?::jsonb)
        """,
        "dispatch:" + intent.delivery(),
        intent.delivery(),
        intent.family().namespace(),
        intent.client(),
        intent.requestId(),
        intent.sourceEvent(),
        receiver,
        intent.fingerprint(),
        MessageRelayCodec.write(snapshot),
        Timestamp.from(intent.occurredAt()),
        RelayCausationCodec.write(intent.causation()));
    jdbc.update(
        "INSERT INTO message_relay_delivery_receipts(delivery,operation) VALUES(?,?)",
        intent.delivery(),
        "dispatch:" + intent.delivery());
  }

  @Override
  public void bind(Intent intent, UUID task, String cursor, long revision) {
    int inserted =
        jdbc.update(
            """
        INSERT INTO message_relay_bindings(task,namespace,client,request_id,source_event,cursor,revision,relay_causation)
        VALUES(?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(task) DO NOTHING
        """,
            task,
            intent.family().namespace(),
            intent.client(),
            intent.requestId(),
            intent.sourceEvent(),
            cursor,
            revision,
            RelayCausationCodec.write(intent.causation()));
    // An initial multi-chunk history remains owed even if no later lifecycle change occurs.
    // A complete initial page is already in its operation receipt. Repeated legacy inspection
    // must not requeue that history or overwrite an existing binding's progress.
    if (inserted == 1 && !MessageRelayPayload.Cursor.read(cursor).follow())
      jdbc.update(
          """
        INSERT INTO message_relay_task_outbox(task,revision) VALUES(?,?)
        ON CONFLICT(task) DO UPDATE SET revision=GREATEST(message_relay_task_outbox.revision,EXCLUDED.revision)
        """,
          task,
          revision);
  }

  @Override
  public Optional<UUID> task(Family family, String client, UUID task, UUID originalRequest) {
    if ((task == null) == (originalRequest == null))
      throw new IllegalArgumentException("Select one native task");
    return jdbc
        .query(
            """
        SELECT id FROM message_external_tasks WHERE account=? AND project=? AND channel=? AND client=?
          AND (?::uuid IS NULL OR id=?) AND (?::uuid IS NULL OR request_id=?)
        """,
            (row, index) -> row.getObject(1, UUID.class),
            family.account(),
            family.project(),
            family.namespace(),
            client,
            task,
            task,
            originalRequest,
            originalRequest)
        .stream()
        .findFirst();
  }

  @Override
  public long head(String message) {
    return Objects.requireNonNull(
        jdbc.queryForObject(
            """
        SELECT COALESCE(max(b.seq),0) FROM message_routes r JOIN message_records b ON b.id=r.message
        WHERE r.reply_to=?
        """,
            Long.class,
            message));
  }

  @Override
  public List<Chunk> chunks(String message, long through, long sequence, int offset) {
    if (through < 0 || sequence < 0 || sequence > through || offset < 0)
      throw new IllegalArgumentException("Invalid native reply page");
    return jdbc.query(
        """
        WITH candidates AS MATERIALIZED (
          SELECT b.seq,b.id,char_length(b.body) AS length,r.final,r.generated,r.ending,b.posted_at
          FROM message_routes r JOIN message_records b ON b.id=r.message
          WHERE r.reply_to=? AND b.seq<=? AND (b.seq>? OR (b.seq=? AND ?>0))
          ORDER BY b.seq LIMIT 2
        ), ranked AS (
          SELECT c.*,row_number() OVER(ORDER BY seq) AS ordinal,
            first_value(length-CASE WHEN seq=? THEN ? ELSE 0 END) OVER(ORDER BY seq) AS remaining
          FROM candidates c
        )
        SELECT c.seq,c.id,substring(b.body FROM CASE WHEN c.seq=? THEN ?+1 ELSE 1 END FOR ?),
          c.length,c.final,c.generated,c.ending,c.posted_at
        FROM ranked c JOIN message_records b ON b.id=c.id
        WHERE c.ordinal=1 OR c.remaining<=? ORDER BY c.seq
        """,
        (row, index) ->
            new Chunk(
                row.getLong(1),
                row.getString(2),
                row.getString(3),
                row.getInt(4),
                row.getBoolean(5),
                row.getBoolean(6),
                row.getString(7),
                row.getTimestamp(8).toInstant()),
        message,
        through,
        sequence,
        sequence,
        offset,
        sequence,
        offset,
        sequence,
        offset,
        MessageRelayPayload.CHUNK_CODEPOINTS,
        MessageRelayPayload.CHUNK_CODEPOINTS);
  }

  @Override
  public Optional<Binding> nextTask() {
    // Only one notification page per binding may be outstanding. Publishing a later chunk first
    // would create a history gap, so an earlier page's backoff or terminal destination failure
    // also fences further capture. Other bindings remain independently selectable.
    return jdbc
        .query(
            """
        SELECT f.*,b.task,b.client,b.request_id,b.source_event,b.cursor,b.revision,b.relay_causation
        FROM message_relay_task_outbox o JOIN message_external_tasks t ON t.id=o.task
        JOIN message_relay_bindings b ON b.task=t.id JOIN message_relay_families f ON f.namespace=b.namespace
        WHERE NOT b.disabled AND o.retry_at<=clock_timestamp() AND NOT EXISTS(
          SELECT 1 FROM message_relay_operations pending WHERE pending.namespace=b.namespace
            AND pending.client=b.client AND pending.request_id=b.request_id
            AND pending.source_event=b.source_event AND NOT pending.published)
        ORDER BY o.retry_at,o.task LIMIT 1 FOR UPDATE OF t SKIP LOCKED
        """,
            (row, index) ->
                new Binding(
                    family(row),
                    row.getObject("task", UUID.class),
                    row.getString("client"),
                    row.getObject("request_id", UUID.class),
                    row.getString("source_event"),
                    row.getString("cursor"),
                    row.getLong("revision"),
                    RelayCausationCodec.read(row.getString("relay_causation"))))
        .stream()
        .findFirst();
  }

  @Override
  public void update(Binding binding, MessageRelayPayload.Snapshot snapshot) {
    String identity =
        "update:"
            + RelayPort.hash(binding.task() + ":" + snapshot.revision() + ":" + binding.cursor());
    jdbc.update(
        """
        INSERT INTO message_relay_operations(identity,namespace,client,request_id,source_event,receiver,
          fingerprint,snapshot,occurred_at,relay_causation)
        VALUES(?,?,?,?,?,'message.update',?,?,clock_timestamp(),?::jsonb) ON CONFLICT(identity) DO NOTHING
        """,
        identity,
        binding.family().namespace(),
        binding.client(),
        binding.requestId(),
        binding.sourceEvent(),
        RelayPort.hash(identity),
        MessageRelayCodec.write(snapshot),
        RelayCausationCodec.write(binding.causation()));
    boolean follow = MessageRelayPayload.Cursor.read(binding.cursor()).follow();
    long revision = follow ? Long.parseLong(snapshot.revision()) : binding.revision();
    jdbc.update(
        "UPDATE message_relay_task_outbox SET retry_at=clock_timestamp() WHERE task=?",
        binding.task());
    jdbc.update(
        "UPDATE message_relay_bindings SET cursor=?,revision=? WHERE task=?",
        snapshot.next() == null ? snapshot.resume() : snapshot.next(),
        revision,
        binding.task());
    if (snapshot.next() == null)
      jdbc.update(
          "DELETE FROM message_relay_task_outbox WHERE task=? AND revision<=?",
          binding.task(),
          revision);
  }

  @Override
  public void deferTask(Binding binding) {
    jdbc.update(
        "UPDATE message_relay_task_outbox SET retry_at=clock_timestamp()+interval '5 seconds' WHERE task=?",
        binding.task());
  }

  @Override
  public Optional<Operation> pending() {
    return jdbc
        .query(
            """
        SELECT f.*,o.identity,o.snapshot,o.occurred_at,o.relay_causation FROM message_relay_operations o
        JOIN message_relay_families f ON f.namespace=o.namespace
        WHERE NOT o.published AND o.destination_failure IS NULL AND o.retry_at<=clock_timestamp()
        ORDER BY o.retry_at,o.identity LIMIT 1 FOR UPDATE OF o SKIP LOCKED
        """,
            (row, index) ->
                new Operation(
                    row.getString("identity"),
                    family(row),
                    MessageRelayCodec.stored(row.getString("snapshot")),
                    row.getTimestamp("occurred_at").toInstant(),
                    RelayCausationCodec.read(row.getString("relay_causation"))))
        .stream()
        .findFirst();
  }

  @Override
  public TaskFence lockTask(Operation operation) {
    var snapshot = operation.snapshot();
    if (snapshot.task() == null) return TaskFence.NOT_REQUIRED;
    boolean retained =
        jdbc.query(
                    """
        SELECT id FROM message_external_tasks WHERE id=? AND account=? AND project=?
          AND channel=? AND client=? FOR UPDATE
        """,
                    (row, index) -> row.getObject(1, UUID.class),
                    snapshot.task().id(),
                    operation.family().account(),
                    operation.family().project(),
                    operation.family().namespace(),
                    snapshot.client())
                .size()
            == 1;
    return retained ? TaskFence.LOCKED : TaskFence.UNAVAILABLE;
  }

  @Override
  public void published(Operation operation) {
    jdbc.update(
        "UPDATE message_relay_operations SET published=true WHERE identity=?",
        operation.identity());
  }

  @Override
  public void unavailable(Operation operation, String reason) {
    if (!List.of("MISSING", "REPLACED", "INVALID_KIND", "TASK_UNAVAILABLE").contains(reason))
      throw new IllegalArgumentException("Invalid native return failure");
    jdbc.update(
        "UPDATE message_relay_operations SET destination_failure=? WHERE identity=?",
        reason,
        operation.identity());
    // A negative receipt owns no task and must never disable another retained request binding.
    if (operation.snapshot().task() == null) return;
    // lockTask ran before append, so this transaction already owns the revision fence. Retain the
    // binding and receipt as evidence, but remove its outstanding worker debt. Revision triggers
    // consult this same flag after acquiring that fence; a writer cannot recreate stale debt.
    jdbc.update(
        """
        WITH disabled AS (
          UPDATE message_relay_bindings SET disabled=true WHERE namespace=? AND client=?
            AND request_id=? AND source_event=? AND task=? RETURNING task
        )
        DELETE FROM message_relay_task_outbox o USING disabled d WHERE o.task=d.task
        """,
        operation.family().namespace(),
        operation.snapshot().client(),
        operation.snapshot().requestId(),
        operation.snapshot().sourceEvent(),
        operation.snapshot().task().id());
  }

  @Override
  public void defer(Operation operation) {
    jdbc.update(
        "UPDATE message_relay_operations SET retry_at=clock_timestamp()+interval '5 seconds' WHERE identity=?",
        operation.identity());
  }

  private static Family family(ResultSet row) throws SQLException {
    return new Family(
        row.getString("namespace"),
        row.getString("account"),
        row.getString("project"),
        row.getLong("project_id"),
        row.getString("publisher"),
        row.getString("source_topic"),
        row.getString("subscriber"),
        row.getObject("source_generation", UUID.class),
        row.getString("channel"),
        row.getString("execution_definition"),
        row.getString("agent"),
        row.getString("reply_topic"),
        row.getObject("reply_generation", UUID.class));
  }
}
