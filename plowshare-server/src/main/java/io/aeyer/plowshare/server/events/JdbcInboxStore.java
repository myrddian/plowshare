package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.MemoryIds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One account's user-inbox. Every read and write names the handle; none crosses accounts — but
 * {@link #settle}, which names a question, not an account, and says whose notices it settled.
 *
 * <p><b>A settled notice has left the inbox</b> (V68). A notice asking the person something is
 * written with what it asks {@code about}; once that question is answered, withdrawn, or its run
 * stops asking it, the notice is settled, and no read here lists it, counts it or marks it read.
 * News, written about nothing, is never settled.
 */
public class JdbcInboxStore implements InboxStore {

  public static final String PREFIX = "inb_";

  public static final String KIND_RUN = "run";
  public static final String KIND_SYNC_CONFLICT = "sync.conflict";

  /** A hook's {@code { notify }} (spec 2026-09-28-hooks-reach-the-log decision 8). */
  public static final String KIND_HOOK = "hook";

  private static final String COLUMNS =
      "id, handle, kind, firing, conversation, ending, answer, arrived_at, read_at, about";

  /** Neither read nor settled: what the person still has to look at. */
  private static final String UNREAD = " AND read_at IS NULL AND settled_at IS NULL";

  private static final String SINCE_LAST_TURN =
      """
            FROM user_inbox i, conversations c
             WHERE i.handle = ? AND i.read_at IS NULL AND i.settled_at IS NULL AND c.id = ?
               AND i.arrived_at > COALESCE(c.last_turn_at, c.created_at)
            """;

  private final JdbcTemplate jdbc;

  public JdbcInboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private io.aeyer.plowshare.server.information.InformationJobs information;

  public void useInformationInputs(io.aeyer.plowshare.server.information.InformationJobs inputs) {
    information = inputs;
  }

  private String informationVisible() {
    // The bound account makes this an uncorrelated set, evaluated once before paging/counting.
    // Passing the row's handle would rebuild authority for each notice in the same inbox.
    return information == null
        ? ""
        : " AND (conversation IS NULL OR conversation NOT IN (SELECT log FROM information_unreadable_logs(?)))";
  }

  public InboxItem deliver(
      String handle, String firing, String conversation, String ending, String answer, Instant at) {
    return jdbc.queryForObject(
        "INSERT INTO user_inbox (id, handle, firing, conversation,"
            + " ending, answer, arrived_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING "
            + COLUMNS,
        JdbcInboxStore::row,
        MemoryIds.mint(PREFIX, at),
        handle,
        firing,
        conversation,
        ending,
        answer,
        Timestamp.from(at));
  }

  public InboxItem notice(String handle, String kind, String text, Instant at) {
    return notice(handle, kind, text, null, at);
  }

  /**
   * A notice, asking about a question when {@code about} names one.
   *
   * @param about what question it asks — {@code approval:<id>}, {@code question:<message id>} — so
   *     {@link #settle} can take it out of the inbox when that question is settled; null for news,
   *     which nothing settles
   */
  public InboxItem notice(String handle, String kind, String text, String about, Instant at) {
    return jdbc.queryForObject(
        "INSERT INTO user_inbox (id, handle, kind, answer, about,"
            + " arrived_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING "
            + COLUMNS,
        JdbcInboxStore::row,
        MemoryIds.mint(PREFIX, at),
        handle,
        kind,
        text,
        about,
        Timestamp.from(at));
  }

  public InboxItem noticeFromLog(String handle, String kind, String text, String log, Instant at) {
    return noticeFromLog(handle, kind, text, log, null, at);
  }

  public InboxItem noticeFromLog(
      String handle, String kind, String text, String log, String about, Instant at) {
    if (information != null) information.requireLog(log, handle);
    return jdbc.queryForObject(
        "INSERT INTO user_inbox(id,handle,kind,answer,conversation,about,arrived_at) VALUES(?,?,?,?,?,?,?) RETURNING "
            + COLUMNS,
        JdbcInboxStore::row,
        MemoryIds.mint(PREFIX, at),
        handle,
        kind,
        text,
        log,
        about,
        Timestamp.from(at));
  }

  /** Retrying a question preserves the existing item, including its read and settlement state. */
  public InboxItem noticeOnceFromLog(
      String handle, String kind, String text, String log, String about, Instant at) {
    java.util.Objects.requireNonNull(handle, "handle");
    java.util.Objects.requireNonNull(about, "question identity");
    if (information != null) information.requireLog(log, handle);
    // Reuse notices created before deterministic identities were introduced as well.
    var existing =
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM user_inbox WHERE handle=? AND kind=? AND about=? ORDER BY arrived_at,id LIMIT 1",
            JdbcInboxStore::row,
            handle,
            kind,
            about);
    if (!existing.isEmpty()) return existing.getFirst();
    final String id;
    try {
      var hash = java.security.MessageDigest.getInstance("SHA-256");
      id =
          PREFIX
              + java.util.HexFormat.of()
                  .formatHex(
                      hash.digest(
                          (handle + "\0" + about)
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
    // The primary key fences concurrent retries. Never clear read_at or settled_at on conflict.
    var inserted =
        jdbc.query(
            "INSERT INTO user_inbox(id,handle,kind,answer,conversation,about,arrived_at)"
                + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING RETURNING "
                + COLUMNS,
            JdbcInboxStore::row,
            id,
            handle,
            kind,
            text,
            log,
            about,
            Timestamp.from(at));
    return inserted.isEmpty()
        ? java.util.Objects.requireNonNull(
            jdbc.queryForObject(
                "SELECT " + COLUMNS + " FROM user_inbox WHERE id=? AND handle=?",
                JdbcInboxStore::row,
                id,
                handle))
        : inserted.getFirst();
  }

  @Override
  public boolean claimQuestionAlert(String handle, String id, Instant at, Duration retryInterval) {
    if (retryInterval == null || retryInterval.isNegative() || retryInterval.isZero())
      throw new IllegalArgumentException("Positive question alert interval required");
    return jdbc.update(
            """
        UPDATE user_inbox SET question_alert_attempted_at=?
        WHERE handle=? AND id=? AND about IS NOT NULL AND kind='approval'
          AND read_at IS NULL AND settled_at IS NULL
          AND (question_alert_attempted_at IS NULL OR question_alert_attempted_at<=?)
        """,
            Timestamp.from(at),
            handle,
            id,
            Timestamp.from(at.minus(retryInterval)))
        == 1;
  }

  /** Settles all notices about a decision and returns the accounts whose Inbox counts changed. */
  public List<String> settle(String about, Instant at) {
    return jdbc.queryForList(
        "UPDATE user_inbox SET settled_at = ? WHERE about = ?"
            + " AND settled_at IS NULL RETURNING handle",
        String.class,
        Timestamp.from(at),
        about);
  }

  public List<InboxItem> list(String handle, boolean unreadOnly, int offset, int limit) {
    return list(handle, unreadOnly, offset, limit, false);
  }

  @Override
  public List<InboxItem> unreadForAgent(String handle, int offset, int limit) {
    return list(handle, true, offset, limit, true);
  }

  private List<InboxItem> list(
      String handle, boolean unreadOnly, int offset, int limit, boolean ordinaryOnly) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM user_inbox WHERE handle = ?"
            + " AND settled_at IS NULL"
            + informationVisible()
            + (unreadOnly ? " AND read_at IS NULL" : "")
            + (ordinaryOnly ? " AND kind<>'approval'" : "")
            + " ORDER BY arrived_at DESC, id DESC OFFSET ? LIMIT ?",
        JdbcInboxStore::row,
        information == null
            ? new Object[] {handle, offset, limit}
            : new Object[] {handle, handle, offset, limit});
  }

  public int markRead(String handle, List<String> ids, Instant at) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jdbc.update(
        "UPDATE user_inbox SET read_at = ? WHERE handle = ?"
            + UNREAD
            + informationVisible()
            + " AND id = ANY (?)",
        ps -> {
          ps.setTimestamp(1, Timestamp.from(at));
          ps.setString(2, handle);
          int next = 3;
          if (information != null) ps.setString(next++, handle);
          ps.setArray(next, ps.getConnection().createArrayOf("text", ids.toArray()));
        });
  }

  public int unread(String handle) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM user_inbox WHERE handle = ?" + UNREAD + informationVisible(),
            Integer.class,
            information == null ? new Object[] {handle} : new Object[] {handle, handle});
    return n == null ? 0 : n;
  }

  public int unreadSinceLastTurn(String handle, String conversation) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) "
                + SINCE_LAST_TURN
                + (information == null
                    ? ""
                    : " AND (i.conversation IS NULL OR i.conversation NOT IN (SELECT log FROM information_unreadable_logs(?)))"),
            Integer.class,
            information == null
                ? new Object[] {handle, conversation}
                : new Object[] {handle, conversation, handle});
    return n == null ? 0 : n;
  }

  public Optional<Instant> newestUnreadSinceLastTurn(String handle, String conversation) {
    OffsetDateTime at =
        jdbc.queryForObject(
            "SELECT max(i.arrived_at) "
                + SINCE_LAST_TURN
                + (information == null
                    ? ""
                    : " AND (i.conversation IS NULL OR i.conversation NOT IN (SELECT log FROM information_unreadable_logs(?)))"),
            OffsetDateTime.class,
            information == null
                ? new Object[] {handle, conversation}
                : new Object[] {handle, conversation, handle});
    return Optional.ofNullable(at).map(OffsetDateTime::toInstant);
  }

  private static InboxItem row(ResultSet rs, int n) throws SQLException {
    OffsetDateTime read = rs.getObject("read_at", OffsetDateTime.class);
    return new InboxItem(
        rs.getString("id"),
        rs.getString("handle"),
        rs.getString("kind"),
        rs.getString("firing"),
        rs.getString("conversation"),
        rs.getString("ending"),
        rs.getString("answer"),
        rs.getObject("arrived_at", OffsetDateTime.class).toInstant(),
        read == null ? null : read.toInstant(),
        rs.getString("about"));
  }
}
