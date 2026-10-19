package io.aeyer.plowshare.server.relay;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A partition advisory lock serializes source order across servers; unrelated topics proceed
 * independently. Outbox removal, publication and native inbox binding share the transaction. A
 * rollback leaves the same event identity for recovery. No external work runs under these locks.
 */
public final class JdbcRelaySourceRepository implements RelaySourceRepository {
  private final JdbcTemplate jdbc;
  private final UnitOfWork transactions;
  private final Relay relay;
  private final RelayNativeRepository nativeInboxes;

  public JdbcRelaySourceRepository(
      JdbcTemplate jdbc,
      UnitOfWork transactions,
      Relay relay,
      RelayNativeRepository nativeInboxes) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.transactions = Objects.requireNonNull(transactions);
    this.relay = Objects.requireNonNull(relay);
    this.nativeInboxes = Objects.requireNonNull(nativeInboxes);
  }

  @Override
  public List<Relay.TopicKey> pendingTopics(String after, int limit) {
    RelayValues.limit(limit, 256);
    // A keyset cursor contains the complete topic plus a project: signed-long scope and '/'.
    if (after != null
        && (after.length() > io.aeyer.plowshare.protocol.RelayPort.MAX_TOPIC_LENGTH + 28
            || after.indexOf('\0') >= 0))
      throw new IllegalArgumentException("invalid publisher cursor");
    String scope = "COALESCE('project:'||project_id::text,'system')";
    var arguments = new java.util.ArrayList<Object>();
    String predicate = "";
    if (after != null) {
      int separator = after.indexOf('/');
      if (separator < 1) throw new IllegalArgumentException("invalid publisher cursor");
      String afterScope = after.substring(0, separator);
      String afterTopic = after.substring(separator + 1);
      try {
        new Relay.TopicKey(RelayScopeCodec.read(afterScope), afterTopic);
      } catch (IllegalStateException invalid) {
        throw new IllegalArgumentException("invalid publisher cursor");
      }
      predicate = " WHERE (" + scope + ",topic)>(?,?)";
      arguments.add(afterScope);
      arguments.add(afterTopic);
    }
    arguments.add(limit);
    return jdbc.query(
        "SELECT DISTINCT "
            + scope
            + " AS scope_key,topic FROM relay_source_events"
            + predicate
            + " ORDER BY scope_key,topic LIMIT ?",
        (row, index) ->
            new Relay.TopicKey(
                RelayScopeCodec.read(row.getString("scope_key")), row.getString("topic")),
        arguments.toArray());
  }

  @Override
  public boolean publishNext(Relay.TopicKey topic) {
    Objects.requireNonNull(topic);
    return transactions.inTransaction(
        () -> {
          boolean owns =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT pg_try_advisory_xact_lock(hashtextextended('relay-source:' || ?,0))",
                      Boolean.class,
                      cursor(topic)));
          if (!owns) return false;
          var next =
              jdbc
                  .query(
                      """
          SELECT id,occurred_at,family,topic,subject,state,context,related,relay_causation FROM relay_source_events WHERE COALESCE(project_id,0)=? AND topic=?
          ORDER BY sequence LIMIT 1 FOR UPDATE
          """,
                      (row, index) -> {
                        var kind = RelayPayload.Kind.valueOf(row.getString("family"));
                        RelayPayload payload =
                            switch (kind) {
                              case LIFECYCLE ->
                                  new RelayPayload.Lifecycle(
                                      row.getString("topic"),
                                      row.getString("subject"),
                                      row.getString("state"),
                                      row.getString("context"),
                                      row.getString("related"));
                              case WAKE_REQUESTED ->
                                  new RelayPayload.WakeRequested(
                                      row.getString("subject"),
                                      row.getString("context"),
                                      RelayPayload.WakeKind.valueOf(row.getString("state")));
                              default ->
                                  throw new IllegalStateException(
                                      "Unsupported captured Relay source");
                            };
                        return new Entry(
                            row.getObject("id", UUID.class),
                            row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                            payload,
                            RelayCausationCodec.read(row.getString("relay_causation")));
                      },
                      topic.scope() instanceof Relay.ProjectScope project
                          ? project.projectId()
                          : 0L,
                      topic.name())
                  .stream()
                  .findFirst();
          if (next.isEmpty()) return false;
          var entry = next.get();
          // Defend privacy again for legacy writers that created mailbox rows in separate commits.
          if (entry.payload() instanceof RelayPayload.Lifecycle change
              && topic.name().startsWith("board.")) {
            String board =
                topic.name().equals("board.message.posted") ? change.context() : change.subject();
            if (Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM board_message_instances WHERE topic=?)",
                    Boolean.class,
                    board))) {
              jdbc.update("DELETE FROM relay_source_events WHERE id=?", entry.id());
              return true;
            }
          }
          relay.registerTopic(topic, entry.payload().kind(), Relay.Policy.systemDefault());
          if (entry.payload() instanceof RelayPayload.WakeRequested wake)
            nativeInboxes.bind(topic, wake);
          relay.publish(
              topic,
              new Relay.Draft(
                  "source:" + entry.id(),
                  "system.source",
                  entry.at(),
                  entry.id().toString(),
                  entry.causation() == null ? null : entry.causation().parentId(),
                  entry.payload(),
                  entry.causation()));
          if (jdbc.update("DELETE FROM relay_source_events WHERE id=?", entry.id()) != 1)
            throw new IllegalStateException("Relay source event changed under lock");
          return true;
        });
  }

  private static String cursor(Relay.TopicKey topic) {
    return RelaySourceRepository.cursor(topic);
  }

  private record Entry(
      UUID id, java.time.Instant at, RelayPayload payload, RelayCausation causation) {}
}
