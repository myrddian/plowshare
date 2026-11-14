package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atomic reply-thread bindings and oldest-first per-request outbox claims. */
public final class JdbcBoardBridgeRepository implements BoardBridgeRepository {
  // Eligibility is binding-wide. The first eligible outbox row is that binding's oldest answer;
  // locking the binding makes SKIP LOCKED skip all its other rows for concurrent drainers.
  static final String PENDING_SQL =
      """
      SELECT r.request_id,r.reply_project_id,r.reply_topic,r.reply_generation,r.reply_target_kind,r.native_source_event,r.reply_to,r.causation_root,
        r.causation_parent,r.causation_depth,q.terminal,m.id,m.topic,m.body,m.author_kind,m.author,m.posted_at
      FROM board_relay_outbox q JOIN board_relay_requests r ON r.request_id=q.request_id
      JOIN board_messages m ON m.id=q.message_id
      WHERE r.reply_failure IS NULL AND r.next_attempt_at <= CURRENT_TIMESTAMP
      ORDER BY q.message_seq,q.request_id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
      """;

  private final JdbcTemplate jdbc;

  public JdbcBoardBridgeRepository(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  @Override
  public void bind(Binding binding) {
    Objects.requireNonNull(binding);
    var cause = binding.causation();
    jdbc.update(
        """
        INSERT INTO board_relay_requests(request_id,account,message_id,topic_id,reply_project_id,
          reply_topic,reply_generation,reply_target_kind,native_source_event,reply_to,causation_root,causation_parent,causation_depth)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        binding.requestId(),
        binding.account(),
        binding.message(),
        binding.topic(),
        binding.destination() instanceof TopicDestination target ? target.project() : null,
        binding.destination() instanceof TopicDestination target ? target.topic() : null,
        binding.destination() instanceof TopicDestination target ? target.generation() : null,
        binding.destination() instanceof TopicDestination ? "topic" : "native",
        binding.destination() instanceof NativeDestination target ? target.sourceEvent() : null,
        binding.replyTo(),
        cause == null ? null : cause.rootId(),
        cause == null ? null : cause.parentId(),
        cause == null ? null : cause.depth());
    jdbc.update(
        "INSERT INTO board_relay_threads(message_id,request_id) VALUES(?,?)",
        binding.message(),
        binding.requestId());
    // A close request binds after its resolution was inserted. Its terminal must still be owed;
    // ordinary post/open bindings are created while the board's topic lock prevents a close race.
    jdbc.update(
        """
        INSERT INTO board_relay_outbox(request_id,message_id,terminal,message_seq)
        SELECT ?,b.id,true,b.seq FROM board_topics t JOIN board_messages b ON b.id=t.resolution
        WHERE t.id=? AND t.state='closed' AND t.resolution IS NOT NULL
        ON CONFLICT DO NOTHING
        """,
        binding.requestId(),
        binding.topic());
  }

  @Override
  public Optional<Pending> pending() {
    return jdbc
        .query(
            PENDING_SQL,
            (row, number) ->
                new Pending(
                    row.getObject("request_id", UUID.class),
                    row.getString("reply_target_kind").equals("native")
                        ? new NativeDestination(row.getObject("native_source_event", UUID.class))
                        : new TopicDestination(
                            row.getLong("reply_project_id"),
                            row.getString("reply_topic"),
                            row.getObject("reply_generation", UUID.class)),
                    row.getString("reply_to"),
                    row.getString("topic"),
                    row.getString("id"),
                    row.getString("body"),
                    row.getString("author_kind"),
                    row.getString("author"),
                    row.getBoolean("terminal"),
                    row.getObject("posted_at", OffsetDateTime.class).toInstant(),
                    row.getString("causation_root") == null
                        ? null
                        : new RelayCausation(
                            row.getString("causation_root"),
                            row.getString("causation_parent"),
                            row.getInt("causation_depth"))))
        .stream()
        .findFirst();
  }

  @Override
  public void undeliverable(Pending pending, Destination reason) {
    Objects.requireNonNull(pending);
    if (reason == null || reason == Destination.AVAILABLE)
      throw new IllegalArgumentException("A failed reply requires its destination reason");
    int changed =
        jdbc.update(
            "UPDATE board_relay_requests SET reply_failure=?,reply_failed_at=CURRENT_TIMESTAMP WHERE request_id=? AND reply_failure IS NULL",
            reason.name(),
            pending.requestId());
    if (changed != 1) throw new IllegalStateException("Board reply failure claim was lost");
    int archived =
        jdbc.update(
            """
        INSERT INTO board_relay_failed_outbox(request_id,message_id,terminal,message_seq)
        SELECT request_id,message_id,terminal,message_seq FROM board_relay_outbox WHERE request_id=?
        """,
            pending.requestId());
    if (archived < 1) throw new IllegalStateException("Board reply failure lost its owed messages");
    int removed =
        jdbc.update("DELETE FROM board_relay_outbox WHERE request_id=?", pending.requestId());
    if (removed != archived)
      throw new IllegalStateException("Board reply failure archive differs from its debt");
  }

  @Override
  public Optional<Failure> failure(UUID requestId, String account) {
    Objects.requireNonNull(requestId);
    RelayPort.identity(account);
    return jdbc
        .query(
            """
        SELECT r.request_id,r.reply_failure,r.reply_failed_at,count(f.message_id) AS owed
        FROM board_relay_requests r JOIN board_relay_failed_outbox f ON f.request_id=r.request_id
        WHERE r.request_id=? AND r.account=? AND r.reply_failure IS NOT NULL
        GROUP BY r.request_id,r.reply_failure,r.reply_failed_at
        """,
            (row, number) ->
                new Failure(
                    row.getObject("request_id", UUID.class),
                    Destination.valueOf(row.getString("reply_failure")),
                    row.getLong("owed"),
                    row.getObject("reply_failed_at", OffsetDateTime.class).toInstant()),
            requestId,
            account)
        .stream()
        .findFirst();
  }

  @Override
  public void defer(Pending pending, Instant until) {
    jdbc.update(
        """
        UPDATE board_relay_requests r SET next_attempt_at=? WHERE request_id=?
          AND EXISTS (SELECT 1 FROM board_relay_outbox o WHERE o.request_id=r.request_id AND o.message_id=?)
        """,
        OffsetDateTime.ofInstant(Objects.requireNonNull(until), ZoneOffset.UTC),
        pending.requestId(),
        pending.message());
  }

  @Override
  public void published(Pending pending) {
    int removed =
        jdbc.update(
            "DELETE FROM board_relay_outbox WHERE request_id=? AND message_id=? AND terminal=?",
            pending.requestId(),
            pending.message(),
            pending.terminal());
    if (removed != 1) throw new IllegalStateException("Board reply outbox claim was lost");
  }
}
