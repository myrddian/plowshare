package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Atomic reply-thread bindings and oldest-first per-request outbox claims. */
public final class JdbcBoardBridgeRepository implements BoardBridgeRepository {
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
          reply_topic,reply_to,causation_root,causation_parent,causation_depth)
        VALUES(?,?,?,?,?,?,?,?,?,?)
        """,
        binding.requestId(),
        binding.account(),
        binding.message(),
        binding.topic(),
        binding.replyProject(),
        binding.replyTopic(),
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
        INSERT INTO board_relay_outbox(request_id,message_id,terminal)
        SELECT ?,resolution,true FROM board_topics WHERE id=? AND state='closed' AND resolution IS NOT NULL
        ON CONFLICT DO NOTHING
        """,
        binding.requestId(),
        binding.topic());
  }

  @Override
  public Optional<Pending> pending() {
    return jdbc
        .query(
            """
        SELECT r.request_id,r.reply_project_id,r.reply_topic,r.reply_to,r.causation_root,
          r.causation_parent,r.causation_depth,q.terminal,m.id,m.topic,m.body,m.author_kind,m.author,m.posted_at
        FROM board_relay_requests r JOIN LATERAL (
          SELECT o.message_id,o.terminal,b.seq FROM board_relay_outbox o
          JOIN board_messages b ON b.id=o.message_id WHERE o.request_id=r.request_id
          ORDER BY b.seq LIMIT 1
        ) q ON true JOIN board_messages m ON m.id=q.message_id
        WHERE r.next_attempt_at <= CURRENT_TIMESTAMP
        ORDER BY q.seq,r.request_id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
        """,
            (row, number) ->
                new Pending(
                    row.getObject("request_id", UUID.class),
                    row.getLong("reply_project_id"),
                    row.getString("reply_topic"),
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
