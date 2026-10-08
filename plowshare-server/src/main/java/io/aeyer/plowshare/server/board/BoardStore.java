package io.aeyer.plowshare.server.board;

import io.aeyer.plowshare.protocol.MemoryIds;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every statement against the board's tables — spec 2026-09-29 §4. Plain JdbcTemplate, as {@code
 * OrchestrationStore} is; each state change is a guarded UPDATE that says whether it won.
 */
public class BoardStore {

  public static final String TOPIC_PREFIX = "bdt_";
  public static final String MESSAGE_PREFIX = "bdm_";

  /** One alert per seat per topic — spec §6. */
  static final int ALERTS_PER_SEAT = 1;

  private static final String TOPIC_COLUMNS =
      "id, project, parent, root, depth, title, label,"
          + " account, opener_kind, opener, origin_conversation, state, resolution, pot_total,"
          + " pot_spent, reserve, quiet_notified_at, opened_at, closed_at, swarm_selection";
  private static final String MESSAGE_COLUMNS =
      "id, topic, reply_to, author_kind, author,"
          + " conversation, entry, kind, title, body, alert, mentions, posted_at";
  private static final String SEAT_COLUMNS =
      "topic, occupant, conversation, passed,"
          + " failed_ending, silent_wakes, seen_through, alerts_used";

  public record NewTopic(
      String project,
      String title,
      String label,
      String account,
      String openerKind,
      String opener,
      String originConversation,
      int potTotal,
      int reserve,
      SwarmSelection swarm) {
    public NewTopic(
        String project,
        String title,
        String label,
        String account,
        String openerKind,
        String opener,
        String originConversation,
        int potTotal,
        int reserve) {
      this(
          project,
          title,
          label,
          account,
          openerKind,
          opener,
          originConversation,
          potTotal,
          reserve,
          null);
    }
  }

  public record NewMessage(
      String topic,
      String replyTo,
      String authorKind,
      String author,
      String conversation,
      Integer entry,
      String kind,
      String title,
      String body,
      boolean alert,
      List<String> mentions) {

    public NewMessage {
      mentions = List.copyOf(mentions);
    }
  }

  private final JdbcTemplate jdbc;
  private final Supplier<Instant> clock;

  public BoardStore(JdbcTemplate jdbc, Supplier<Instant> clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ---- topics -------------------------------------------------------------------------------

  public BoardTopic openRoot(NewTopic topic) {
    Instant at = clock.get();
    String id = MemoryIds.mint(TOPIC_PREFIX, at);
    jdbc.update(
        "INSERT INTO board_topics (id, project, parent, root, depth, title, label,"
            + " account, opener_kind, opener, origin_conversation, state, pot_total,"
            + " pot_spent, reserve, opened_at, swarm_selection) VALUES (?, ?, NULL, ?, 0, ?, ?, ?, ?, ?, ?,"
            + " 'open', ?, 0, ?, ?, ?::jsonb)",
        id,
        topic.project(),
        id,
        topic.title(),
        topic.label(),
        topic.account(),
        topic.openerKind(),
        topic.opener(),
        topic.originConversation(),
        topic.potTotal(),
        topic.reserve(),
        utc(at),
        encodeSwarm(topic.swarm()));
    return topic(id).orElseThrow();
  }

  public Optional<BoardTopic> topic(String id) {
    return jdbc
        .query(
            "SELECT " + TOPIC_COLUMNS + " FROM board_topics WHERE id = ?", BoardStore::topicRow, id)
        .stream()
        .findFirst();
  }

  /** Private messaging transports are not public board topics. */
  public boolean messagingTopic(String id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM board_message_instances WHERE topic = ?)",
            Boolean.class,
            id));
  }

  /** Viewer reads never change a seat's model read watermark. */
  public record Summary(BoardTopic topic, int messages, int documents) {}

  public List<Summary> summaries(
      String account, String project, boolean active, int offset, int limit) {
    return jdbc.query(
        "SELECT "
            + TOPIC_COLUMNS
            + ","
            + " (SELECT count(*) FROM board_messages m WHERE m.topic = t.id) AS messages,"
            + " (SELECT count(*) FROM board_messages m WHERE m.topic = t.id AND m.kind = 'document') AS documents"
            + " FROM board_topics t WHERE NOT EXISTS (SELECT 1 FROM board_message_instances i WHERE i.topic = t.id)"
            + " AND account = ? AND (?::text IS NULL OR project = ?)"
            + " AND (NOT ? OR closed_at IS NULL) ORDER BY (closed_at IS NOT NULL), opened_at DESC, id"
            + " OFFSET ? LIMIT ?",
        (rs, n) -> new Summary(topicRow(rs, n), rs.getInt("messages"), rs.getInt("documents")),
        account,
        project,
        project,
        active,
        offset,
        limit);
  }

  /** Latest durable wake and root allowance for each inspected seat. */
  public record SeatActivity(
      BoardSeat seat,
      String topicState,
      int remaining,
      int reserve,
      String wake,
      String job,
      String reason) {}

  public List<SeatActivity> inspectSeats(String account, String topic, boolean active) {
    return jdbc.query(
        "SELECT s.*, t.state AS topic_state, r.pot_total - r.pot_spent AS remaining,"
            + " r.reserve, f.status AS wake, f.job_id, f.reason, f.finished_at"
            + " FROM board_seats s JOIN board_topics t ON t.id = s.topic"
            + " JOIN board_topics r ON r.id = t.root"
            + " LEFT JOIN LATERAL (SELECT status, job_id, reason, finished_at FROM firings"
            + " WHERE topic = s.topic AND target = 'conversation:' || s.conversation"
            + " ORDER BY (status = 'started' AND finished_at IS NULL) DESC, arrived_at DESC, id DESC LIMIT 1) f ON TRUE"
            + " WHERE NOT EXISTS (SELECT 1 FROM board_message_instances i WHERE i.topic = t.id)"
            + " AND t.account = ? AND (?::text IS NULL OR t.id = ?)"
            + " AND (NOT ? OR t.closed_at IS NULL) ORDER BY t.opened_at, s.occupant COLLATE \"C\"",
        (rs, n) ->
            new SeatActivity(
                seatRow(rs, n),
                rs.getString("topic_state"),
                rs.getInt("remaining"),
                rs.getInt("reserve"),
                rs.getObject("finished_at") == null || "refused".equals(rs.getString("wake"))
                    ? rs.getString("wake")
                    : null,
                rs.getObject("finished_at") == null ? rs.getString("job_id") : null,
                rs.getString("reason")),
        account,
        topic,
        topic,
        active);
  }

  public List<Decision> decisions(String topic) {
    return jdbc.query(
        "SELECT d.request, d.approved, d.reason, d.child FROM board_decisions d"
            + " JOIN board_messages m ON m.id = d.request WHERE m.topic = ? ORDER BY m.seq",
        (rs, n) ->
            new Decision(
                rs.getString("request"),
                rs.getBoolean("approved"),
                rs.getString("reason"),
                rs.getString("child")),
        topic);
  }

  /**
   * Commits a model-call charge before it is sent, independently of any outer transaction. A later
   * tool failure or rollback cannot refund a request already made to the model.
   */
  public void spend(String root, int calls) {
    if (calls <= 0) {
      return;
    }
    var spending =
        new TransactionTemplate(
            new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "data source")));
    spending.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    spending.executeWithoutResult(
        tx -> {
          if (jdbc.update(
                  "UPDATE board_topics SET pot_spent = pot_spent + ? WHERE id = ?"
                      + " AND parent IS NULL",
                  calls,
                  root)
              != 1) {
            throw new IllegalStateException(root + " is not a root topic to charge");
          }
        });
  }

  /** Open to exhausted, once; answers whether this call moved it. */
  public boolean exhaust(String root) {
    return jdbc.update(
            "UPDATE board_topics SET state = 'exhausted' WHERE id = ?" + " AND state = 'open'",
            root)
        == 1;
  }

  /** Locks a topic until the caller's transaction ends, serializing writes with close. */
  public Optional<BoardTopic> lockTopic(String id) {
    return jdbc
        .query(
            "SELECT " + TOPIC_COLUMNS + " FROM board_topics WHERE id = ? FOR UPDATE",
            BoardStore::topicRow,
            id)
        .stream()
        .findFirst();
  }

  /** Open or exhausted to closed, naming its resolution; answers whether this call closed it. */
  public boolean close(String topic, String resolution) {
    return jdbc.update(
            "UPDATE board_topics SET state = 'closed', resolution = ?,"
                + " closed_at = ? WHERE id = ? AND state <> 'closed'",
            resolution,
            utc(clock.get()),
            topic)
        == 1;
  }

  /** Marks a resolution delivered, once; answers whether this call marked it. */
  public boolean resolutionDelivered(String topic) {
    return jdbc.update(
            "UPDATE board_topics SET resolution_delivered_at = ? WHERE id = ?"
                + " AND resolution IS NOT NULL AND resolution_delivered_at IS NULL",
            utc(clock.get()),
            topic)
        == 1;
  }

  /** Whether {@code topic} is closed with a resolution nobody has been given yet. */
  public boolean resolutionUndelivered(String topic) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM"
                + " board_topics WHERE id = ? AND resolution IS NOT NULL"
                + " AND resolution_delivered_at IS NULL)",
            Boolean.class,
            topic));
  }

  /** Every closed topic whose resolution is still owed, oldest first. */
  public List<BoardTopic> undeliveredResolutions() {
    return jdbc.query(
        "SELECT "
            + TOPIC_COLUMNS
            + " FROM board_topics WHERE resolution IS"
            + " NOT NULL AND resolution_delivered_at IS NULL ORDER BY closed_at, id",
        BoardStore::topicRow);
  }

  /** {@link #undeliveredResolutions()}, for the ones owed to {@code conversation}. */
  public List<BoardTopic> undeliveredResolutionsFor(String conversation) {
    return jdbc.query(
        "SELECT "
            + TOPIC_COLUMNS
            + " FROM board_topics WHERE resolution IS"
            + " NOT NULL AND resolution_delivered_at IS NULL AND origin_conversation = ?"
            + " ORDER BY closed_at, id",
        BoardStore::topicRow,
        conversation);
  }

  /** Every topic not closed, oldest first. */
  public List<BoardTopic> openTopics() {
    return jdbc.query(
        "SELECT "
            + TOPIC_COLUMNS
            + " FROM board_topics WHERE closed_at IS"
            + " NULL AND NOT EXISTS (SELECT 1 FROM board_message_instances i WHERE i.topic = board_topics.id)"
            + " ORDER BY opened_at, id",
        BoardStore::topicRow);
  }

  /** A child shares its root's account and pot; its requester holds the opener seat. */
  public BoardTopic openChild(BoardTopic parent, String title, String label, String opener) {
    Instant at = clock.get();
    String id = MemoryIds.mint(TOPIC_PREFIX, at);
    String account = topic(parent.root()).orElseThrow().account();
    jdbc.update(
        "INSERT INTO board_topics (id, project, parent, root, depth, title, label,"
            + " account, opener_kind, opener, state, opened_at, swarm_selection)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'member', ?, 'open', ?, ?::jsonb)",
        id,
        parent.project(),
        parent.id(),
        parent.root(),
        parent.depth() + 1,
        title,
        label,
        account,
        opener,
        utc(at),
        encodeSwarm(parent.swarm()));
    return topic(id).orElseThrow();
  }

  public record Decision(String request, boolean approved, String reason, String child) {}

  public Optional<Decision> decision(String request) {
    return jdbc
        .query(
            "SELECT request, approved, reason, child FROM board_decisions" + " WHERE request = ?",
            (rs, n) ->
                new Decision(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4)),
            request)
        .stream()
        .findFirst();
  }

  public void decide(String request, boolean approved, String reason, String child) {
    jdbc.update(
        "INSERT INTO board_decisions VALUES (?, ?, ?, ?, ?)",
        request,
        approved,
        reason,
        child,
        utc(clock.get()));
  }

  public Optional<String> requestForChild(String child) {
    return jdbc
        .queryForList("SELECT request FROM board_decisions WHERE child = ?", String.class, child)
        .stream()
        .findFirst();
  }

  /** Open descendants, deepest first, excluding the given topic. */
  public List<BoardTopic> descendants(String topic) {
    return jdbc.query(
        "WITH RECURSIVE children AS (SELECT id FROM board_topics WHERE"
            + " parent = ? UNION ALL SELECT t.id FROM board_topics t JOIN children c"
            + " ON t.parent = c.id) SELECT "
            + TOPIC_COLUMNS
            + " FROM board_topics WHERE id IN (SELECT id FROM children)"
            + " AND state <> 'closed' ORDER BY depth DESC, opened_at, id",
        BoardStore::topicRow,
        topic);
  }

  public List<BoardTopic> openTree(String root) {
    return jdbc.query(
        "SELECT "
            + TOPIC_COLUMNS
            + " FROM board_topics WHERE root = ?"
            + " AND state <> 'closed' ORDER BY depth DESC, opened_at, id",
        BoardStore::topicRow,
        root);
  }

  public void topup(String root, int total, int reserve) {
    jdbc.update(
        "UPDATE board_topics SET pot_total = ?, reserve = ?, state = 'open'"
            + " WHERE id = ? AND state <> 'closed'",
        total,
        reserve,
        root);
  }

  /** A queued or started firing includes ready, blocked and running model steps. */
  public boolean activeWakes(String topic) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM firings"
                + " WHERE topic = ? AND (status = 'queued' OR (status = 'started' AND finished_at IS NULL)))",
            Boolean.class,
            topic));
  }

  public void refuseMemberWakes(String topic, String reason) {
    jdbc.update(
        "UPDATE firings f SET status = 'refused', reason = ?"
            + " FROM board_seats s WHERE f.topic = ? AND s.topic = f.topic"
            + " AND f.target = 'conversation:' || s.conversation"
            + " AND s.occupant <> '@opener' AND f.status = 'queued'",
        reason,
        topic);
  }

  /** Notices do not themselves make a quiet topic newly active. Called under the topic lock. */
  public boolean markQuiet(String topic) {
    return jdbc.update(
            "UPDATE board_topics t SET quiet_notified_at = ?, quiet_through ="
                + " (SELECT id FROM board_messages m WHERE m.topic = t.id AND m.kind <> 'note' AND NOT EXISTS"
                + " (SELECT 1 FROM board_notices n WHERE n.message = m.id) ORDER BY seq DESC LIMIT 1)"
                + " WHERE t.id = ? AND t.state = 'open' AND (quiet_notified_at IS NULL OR"
                + " quiet_through IS DISTINCT FROM (SELECT id FROM board_messages m WHERE"
                + " m.topic = t.id AND m.kind <> 'note' AND NOT EXISTS (SELECT 1 FROM board_notices n WHERE"
                + " n.message = m.id) ORDER BY seq DESC LIMIT 1))",
            utc(clock.get()),
            topic)
        == 1;
  }

  public void notice(BoardMessage message, String kind, boolean delivered) {
    jdbc.update(
        "INSERT INTO board_notices (message, topic, kind, delivered_at) VALUES (?, ?, ?, ?)",
        message.id(),
        message.topic(),
        kind,
        delivered ? utc(clock.get()) : null);
  }

  public Optional<String> noticeKind(String message) {
    return jdbc
        .queryForList("SELECT kind FROM board_notices WHERE message = ?", String.class, message)
        .stream()
        .findFirst();
  }

  /** A notice follows its latest wake, so completed notifications cannot replay on restart. */
  public void noticeWake(String message, String firing) {
    jdbc.update("UPDATE board_notices SET wake = ? WHERE message = ?", firing, message);
  }

  public boolean noticeNeedsRecovery(String message) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM board_notices n"
                + " JOIN firings f ON f.id = n.wake WHERE n.message = ? AND (f.status = 'queued'"
                + " OR (f.status = 'started' AND (f.finished_at IS NULL"
                + " OR f.reason = 'the server restarted during this run'))))",
            Boolean.class,
            message));
  }

  public List<BoardMessage> owedNotices() {
    return jdbc.query(
        "SELECT "
            + MESSAGE_COLUMNS
            + " FROM board_messages WHERE id IN"
            + " (SELECT message FROM board_notices WHERE delivered_at IS NULL) ORDER BY seq",
        BoardStore::messageRow);
  }

  public void noticeDelivered(String message) {
    jdbc.update(
        "UPDATE board_notices SET delivered_at = ? WHERE message = ?" + " AND delivered_at IS NULL",
        utc(clock.get()),
        message);
  }

  // ---- messages -----------------------------------------------------------------------------

  /**
   * Writes one message. A reply names a message on the same topic — a CHECK cannot read another
   * row, so this is where that rule lives (spec §4).
   *
   * @throws IllegalArgumentException for a reply to no message, or to one on another topic
   */
  public BoardMessage post(NewMessage message) {
    if (message.replyTo() != null) {
      String repliedTopic =
          jdbc
              .query(
                  "SELECT topic FROM board_messages WHERE id = ?",
                  (rs, n) -> rs.getString(1),
                  message.replyTo())
              .stream()
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "there is no message " + message.replyTo() + " to reply to"));
      if (!repliedTopic.equals(message.topic())) {
        throw new IllegalArgumentException(
            "message "
                + message.replyTo()
                + " is on topic "
                + repliedTopic
                + ", not "
                + message.topic()
                + "; a reply stays on its own topic");
      }
    }
    Instant at = clock.get();
    String id = MemoryIds.mint(MESSAGE_PREFIX, at);
    jdbc.update(
        connection -> {
          PreparedStatement ps =
              connection.prepareStatement(
                  "INSERT INTO board_messages (id,"
                      + " topic, reply_to, author_kind, author, conversation, entry, kind, title,"
                      + " body, alert, mentions, posted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                      + " ?, ?)");
          ps.setString(1, id);
          ps.setString(2, message.topic());
          ps.setString(3, message.replyTo());
          ps.setString(4, message.authorKind());
          ps.setString(5, message.author());
          ps.setString(6, message.conversation());
          ps.setObject(7, message.entry(), Types.INTEGER);
          ps.setString(8, message.kind());
          ps.setString(9, message.title());
          ps.setString(10, message.body());
          ps.setBoolean(11, message.alert());
          ps.setArray(12, connection.createArrayOf("text", message.mentions().toArray()));
          ps.setObject(13, utc(at));
          return ps;
        });
    return message(id).orElseThrow();
  }

  public Optional<BoardMessage> message(String id) {
    return jdbc
        .query(
            "SELECT " + MESSAGE_COLUMNS + " FROM board_messages WHERE id = ?",
            BoardStore::messageRow,
            id)
        .stream()
        .findFirst();
  }

  /**
   * Every message on a topic, in the order said.
   *
   * <p>Ordered by {@code seq}, not {@code id}: {@code id} is a {@link MemoryIds} id, and {@code
   * MemoryIds.mint} breaks a tie within the same millisecond with three random bytes, so two
   * messages posted in one millisecond can mint ids that sort against the order they were actually
   * inserted in. {@code seq} is Postgres's own identity column and never ties.
   */
  public List<BoardMessage> messages(String topic) {
    return jdbc.query(
        "SELECT " + MESSAGE_COLUMNS + " FROM board_messages WHERE topic = ?" + " ORDER BY seq",
        BoardStore::messageRow,
        topic);
  }

  /**
   * How many messages on {@code topic} come after {@code seenThrough}; all of them for null.
   *
   * <p>Compares {@code seq}, not the watermark's {@code id} directly: see {@link #messages(String)}
   * for why an id ordering is not reliably insertion order.
   */
  public int countAfter(String topic, String seenThrough) {
    Integer count =
        seenThrough == null
            ? jdbc.queryForObject(
                "SELECT count(*) FROM board_messages WHERE topic = ?", Integer.class, topic)
            : jdbc.queryForObject(
                "SELECT count(*) FROM board_messages WHERE topic = ?"
                    + " AND seq > (SELECT seq FROM board_messages WHERE id = ?)",
                Integer.class,
                topic,
                seenThrough);
    return count == null ? 0 : count;
  }

  /** Every message on {@code topic} after {@code seenThrough} (all for null), by sequence. */
  public List<BoardMessage> messagesAfter(String topic, String seenThrough) {
    if (seenThrough == null) {
      return messages(topic);
    }
    return jdbc.query(
        "SELECT "
            + MESSAGE_COLUMNS
            + " FROM board_messages WHERE topic = ?"
            + " AND seq > (SELECT seq FROM board_messages WHERE id = ?) ORDER BY seq",
        BoardStore::messageRow,
        topic,
        seenThrough);
  }

  /** How many messages {@code conversation} wrote on {@code topic} at or after {@code since}. */
  public int messagesSince(String topic, String conversation, Instant since) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM board_messages WHERE topic = ?"
                + " AND conversation = ? AND posted_at >= ?",
            Integer.class,
            topic,
            conversation,
            utc(since));
    return count == null ? 0 : count;
  }

  // ---- seats --------------------------------------------------------------------------------

  /** Takes the seat; false when someone already holds it. */
  public boolean seatIfAbsent(String topic, String occupant, String conversation) {
    return jdbc.update(
            "INSERT INTO board_seats (topic, occupant, conversation) VALUES (?, ?,"
                + " ?) ON CONFLICT (topic, occupant) DO NOTHING",
            topic,
            occupant,
            conversation)
        == 1;
  }

  public Optional<BoardSeat> seat(String topic, String occupant) {
    return jdbc
        .query(
            "SELECT " + SEAT_COLUMNS + " FROM board_seats WHERE topic = ?" + " AND occupant = ?",
            BoardStore::seatRow,
            topic,
            occupant)
        .stream()
        .findFirst();
  }

  public Optional<BoardSeat> seatByConversation(String conversation) {
    return jdbc
        .query(
            "SELECT " + SEAT_COLUMNS + " FROM board_seats WHERE conversation = ?",
            BoardStore::seatRow,
            conversation)
        .stream()
        .findFirst();
  }

  /**
   * Every seat on a topic, ordered by occupant.
   *
   * <p>{@code COLLATE "C"} and not the database's default: {@link BoardSeat#OPENER} is {@code
   * "@opener"}, and a locale collation — {@code en_US.utf8}, the default this project's Postgres
   * image ships with — deprioritises punctuation ahead of letters, sorting it as though it read
   * {@code "opener"} and placing it between {@code critic} and {@code researcher}. A caller (the
   * board's tests, and any future one that renders a seat list) needs an ordering that does not
   * silently change with the server's locale, so this pins the byte-order collation explicitly
   * rather than inheriting whatever initdb chose.
   */
  public List<BoardSeat> seats(String topic) {
    return jdbc.query(
        "SELECT "
            + SEAT_COLUMNS
            + " FROM board_seats WHERE topic = ?"
            + " ORDER BY occupant COLLATE \"C\"",
        BoardStore::seatRow,
        topic);
  }

  /** Spends the seat's one alert; false when it has none left. */
  public boolean useAlert(String topic, String occupant) {
    return jdbc.update(
            "UPDATE board_seats SET alerts_used = alerts_used + 1 WHERE topic = ?"
                + " AND occupant = ? AND alerts_used < ?",
            topic,
            occupant,
            ALERTS_PER_SEAT)
        == 1;
  }

  public void recordSilent(String topic, String occupant) {
    jdbc.update(
        "UPDATE board_seats SET silent_wakes = silent_wakes + 1 WHERE topic = ?"
            + " AND occupant = ?",
        topic,
        occupant);
  }

  public void recordFailure(String topic, String occupant, String ending) {
    jdbc.update(
        "UPDATE board_seats SET failed_ending = ? WHERE topic = ? AND occupant = ?",
        ending,
        topic,
        occupant);
  }

  public void clearFailure(String topic, String occupant) {
    jdbc.update(
        "UPDATE board_seats SET failed_ending = NULL WHERE topic = ?" + " AND occupant = ?",
        topic,
        occupant);
  }

  /** Moves a seat's watermark to {@code through}, never backwards (by sequence). */
  public void advanceSeen(String topic, String occupant, String through) {
    jdbc.update(
        "UPDATE board_seats SET seen_through = ? WHERE topic = ? AND occupant = ?"
            + " AND (seen_through IS NULL OR (SELECT seq FROM board_messages WHERE id ="
            + " seen_through) < (SELECT seq FROM board_messages WHERE id = ?))",
        through,
        topic,
        occupant,
        through);
  }

  /** Marks a seat passed. */
  public void pass(String topic, String occupant) {
    jdbc.update(
        "UPDATE board_seats SET passed = TRUE WHERE topic = ? AND occupant = ?", topic, occupant);
  }

  // ---- wakes -------------------------------------------------------------------------------

  /**
   * The target of every wake still queued anywhere in {@code root}'s tree — what a settled lease
   * drains, since capacity it gives back belongs to the whole tree and any seat in it may be the
   * one left waiting for it. Here, not in {@code FiringStore}: it is the board's tree that scopes
   * it, and only the board knows a topic has a root.
   */
  public List<String> queuedWakeTargets(String root) {
    return jdbc.queryForList(
        "SELECT DISTINCT f.target FROM firings f"
            + " JOIN board_topics t ON t.id = f.topic"
            + " WHERE t.root = ? AND f.status = 'queued'",
        String.class,
        root);
  }

  public Optional<String> ancestorSeen(String conversation, String topic) {
    return jdbc
        .queryForList(
            "SELECT seen_through FROM board_reads WHERE conversation = ?" + " AND topic = ?",
            String.class,
            conversation,
            topic)
        .stream()
        .findFirst();
  }

  public void ancestorSeen(String conversation, String topic, String message) {
    jdbc.update(
        "INSERT INTO board_reads VALUES (?, ?, ?) ON CONFLICT(conversation, topic)"
            + " DO UPDATE SET seen_through = EXCLUDED.seen_through WHERE"
            + " (SELECT seq FROM board_messages WHERE id = board_reads.seen_through) <"
            + " (SELECT seq FROM board_messages WHERE id = EXCLUDED.seen_through)",
        conversation,
        topic,
        message);
  }

  // ---- rows ---------------------------------------------------------------------------------

  private static OffsetDateTime utc(Instant at) {
    return at == null ? null : OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper SWARM_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private static String encodeSwarm(SwarmSelection selection) {
    if (selection == null) return null;
    try {
      return SWARM_JSON.writeValueAsString(selection);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failed) {
      throw new IllegalStateException("The swarm selection could not be encoded", failed);
    }
  }

  private static SwarmSelection decodeSwarm(String value) {
    if (value == null) return null;
    try {
      return SWARM_JSON.readValue(value, SwarmSelection.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException failed) {
      throw new IllegalStateException("The retained swarm selection is invalid", failed);
    }
  }

  private static BoardTopic topicRow(ResultSet rs, int n) throws SQLException {
    return new BoardTopic(
        rs.getString("id"),
        rs.getString("project"),
        rs.getString("parent"),
        rs.getString("root"),
        rs.getInt("depth"),
        rs.getString("title"),
        rs.getString("label"),
        rs.getString("account"),
        rs.getString("opener_kind"),
        rs.getString("opener"),
        rs.getString("origin_conversation"),
        rs.getString("state"),
        rs.getString("resolution"),
        integer(rs, "pot_total"),
        integer(rs, "pot_spent"),
        integer(rs, "reserve"),
        instant(rs, "quiet_notified_at"),
        instant(rs, "opened_at"),
        instant(rs, "closed_at"),
        decodeSwarm(rs.getString("swarm_selection")));
  }

  private static BoardMessage messageRow(ResultSet rs, int n) throws SQLException {
    Array mentions = rs.getArray("mentions");
    List<String> named =
        mentions == null ? List.of() : Arrays.asList((String[]) mentions.getArray());
    return new BoardMessage(
        rs.getString("id"),
        rs.getString("topic"),
        rs.getString("reply_to"),
        rs.getString("author_kind"),
        rs.getString("author"),
        rs.getString("conversation"),
        integer(rs, "entry"),
        rs.getString("kind"),
        rs.getString("title"),
        rs.getString("body"),
        rs.getBoolean("alert"),
        named,
        instant(rs, "posted_at"));
  }

  private static BoardSeat seatRow(ResultSet rs, int n) throws SQLException {
    return new BoardSeat(
        rs.getString("topic"),
        rs.getString("occupant"),
        rs.getString("conversation"),
        rs.getBoolean("passed"),
        rs.getString("failed_ending"),
        rs.getInt("silent_wakes"),
        rs.getString("seen_through"),
        rs.getInt("alerts_used"));
  }
}
