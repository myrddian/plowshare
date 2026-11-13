package io.aeyer.plowshare.server.messaging;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.board.BoardBridge;
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import io.aeyer.plowshare.server.relay.RelayCausationCodec;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atomic native board export evidence, bound to the execution ledger and original native route. */
public final class JdbcBoardNativeExportRepository implements BoardNativeExportRepository {
  private final JdbcTemplate jdbc;

  public JdbcBoardNativeExportRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void bind(Binding binding) {
    var policy = binding.policy();
    var request = binding.nativeRequest();
    int added =
        jdbc.update(
            """
        INSERT INTO message_board_exports(source_event,project_id,project,request_topic,channel,receiver,
          definition,request_message,requester,external_participant,account,relay_causation)
        SELECT ?,?,?,?,?,?,?,?,?,?,?,?::jsonb FROM relay_execution_publications e
        JOIN message_instances s ON s.id=? JOIN message_instances d ON d.id=?
        JOIN message_routes r ON r.message=?
        WHERE e.id=? AND e.project_id=? AND e.account=s.account AND e.agent=s.agent
          AND e.conversation_id=s.conversation AND s.account=? AND s.project=? AND s.agent=?
          AND d.account=s.account AND d.project=s.project AND d.lifetime='external'
          AND r.sender=s.id AND r.recipient=d.id AND r.reply_to IS NULL
        """,
            binding.sourceEvent(),
            binding.projectId(),
            policy.project(),
            policy.requestTopic(),
            policy.channel(),
            policy.receiver(),
            binding.definition(),
            request.requestMessage(),
            request.requester(),
            request.external(),
            request.account(),
            RelayCausationCodec.write(binding.causation()),
            request.requester(),
            request.external(),
            request.requestMessage(),
            binding.sourceEvent(),
            binding.projectId(),
            request.account(),
            policy.project(),
            binding.definition());
    if (added != 1) throw new IllegalStateException("Native board export proof was not retained");
  }

  private static final String BINDING_COLUMNS =
      "e.source_event,e.project_id,e.project,e.request_topic,e.channel,e.receiver,e.definition,"
          + "e.request_message,e.requester,e.external_participant,e.account,e.relay_causation::text";

  @Override
  public Optional<Binding> binding(UUID sourceEvent) {
    return jdbc
        .query(
            "SELECT " + BINDING_COLUMNS + " FROM message_board_exports e WHERE e.source_event=?",
            (row, number) -> bindingRow(row),
            Objects.requireNonNull(sourceEvent))
        .stream()
        .findFirst();
  }

  private static Binding bindingRow(ResultSet row) throws SQLException {
    return new Binding(
        row.getObject("source_event", UUID.class),
        row.getLong("project_id"),
        new BoardMessageExportProperties.Policy(
            row.getString("project"),
            row.getString("request_topic"),
            row.getString("channel"),
            row.getString("receiver")),
        row.getString("definition"),
        new NativeMessageExports.Export(
            row.getString("request_message"),
            row.getString("requester"),
            row.getString("external_participant"),
            row.getString("account"),
            row.getString("project")),
        RelayCausationCodec.read(row.getString("relay_causation")));
  }

  @Override
  public BoardNativeRequests.Refusal refuse(UUID delivery, BoardBridge.Agent actor) {
    // Exactly the board receipt lock, before either canonical decision: one effect or one refusal.
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        actor.account() + ":" + actor.effectRequestId());
    if (accepted(actor)) return BoardNativeRequests.Refusal.EFFECT_ACCEPTED;
    var returned =
        UUID.nameUUIDFromBytes(
            ("board-native-refusal/1\n" + delivery).getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        INSERT INTO message_board_outcomes(delivery_id,source_event,return_id,code)
        SELECT ?,source_event,?,'board.native.refused' FROM message_board_exports
        WHERE source_event=? AND account=? AND definition=? ON CONFLICT DO NOTHING
        """,
        delivery,
        returned,
        actor.sourceEvent(),
        actor.account(),
        actor.definition());
    if (!refused(actor)) throw new IllegalStateException("Native board refusal proof differs");
    return BoardNativeRequests.Refusal.RECORDED;
  }

  @Override
  public boolean accepted(BoardBridge.Agent actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM board_post_receipts WHERE account=? AND request_id=?)
        """,
            Boolean.class,
            actor.account(),
            actor.effectRequestId()));
  }

  @Override
  public boolean refused(BoardBridge.Agent actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
        SELECT EXISTS(SELECT 1 FROM message_board_outcomes o JOIN message_board_exports e ON e.source_event=o.source_event
          WHERE e.source_event=? AND e.account=? AND e.definition=?)
        """,
            Boolean.class,
            actor.sourceEvent(),
            actor.account(),
            actor.definition()));
  }

  @Override
  public Optional<Evidence> outcome(UUID delivery, String account) {
    RelayPort.identity(account);
    return jdbc
        .query(
            """
        SELECT o.delivery_id,o.source_event,o.return_id,o.state,o.code,o.native_message
        FROM message_board_outcomes o JOIN message_board_exports e ON e.source_event=o.source_event
        WHERE o.delivery_id=? AND e.account=?
        """,
            (row, number) ->
                new Evidence(
                    row.getObject("delivery_id", UUID.class),
                    row.getObject("source_event", UUID.class),
                    row.getObject("return_id", UUID.class),
                    OutcomeState.valueOf(row.getString("state").toUpperCase(Locale.ROOT)),
                    row.getString("code"),
                    Optional.ofNullable(row.getString("native_message"))),
            delivery,
            account)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Outcome> pendingOutcome() {
    return jdbc
        .query(
            "SELECT o.delivery_id,o.return_id,"
                + BINDING_COLUMNS
                + """
         FROM message_board_outcomes o JOIN message_board_exports e ON e.source_event=o.source_event
         WHERE o.state='pending' AND o.next_attempt_at<=CURRENT_TIMESTAMP
         ORDER BY o.next_attempt_at,o.delivery_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
        """,
            (row, number) ->
                new Outcome(
                    row.getObject("delivery_id", UUID.class),
                    row.getObject("return_id", UUID.class),
                    bindingRow(row)))
        .stream()
        .findFirst();
  }

  @Override
  public void published(Outcome outcome, String message) {
    changed(
        jdbc.update(
            "UPDATE message_board_outcomes SET state='published',native_message=?,settled_at=CURRENT_TIMESTAMP WHERE delivery_id=? AND source_event=? AND state='pending'",
            message,
            outcome.delivery(),
            outcome.binding().sourceEvent()));
  }

  @Override
  public void unavailable(Outcome outcome) {
    changed(
        jdbc.update(
            "UPDATE message_board_outcomes SET state='unavailable',settled_at=CURRENT_TIMESTAMP WHERE delivery_id=? AND source_event=? AND state='pending'",
            outcome.delivery(),
            outcome.binding().sourceEvent()));
  }

  @Override
  public void defer(Outcome outcome, Instant until) {
    jdbc.update(
        "UPDATE message_board_outcomes SET next_attempt_at=? WHERE delivery_id=? AND source_event=? AND state='pending'",
        Timestamp.from(until),
        outcome.delivery(),
        outcome.binding().sourceEvent());
  }

  private static void changed(int count) {
    if (count != 1) throw new IllegalStateException("Native board outcome claim was lost");
  }
}
