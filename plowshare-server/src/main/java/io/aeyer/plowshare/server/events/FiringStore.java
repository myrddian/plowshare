package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.protocol.MemoryIds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The firings table, which is the queue. Every transition is a guarded UPDATE. */
public class FiringStore {

  public static final String PREFIX = "fir_";

  private static final String COLUMNS =
      "id, event, data::text AS data, schedule, fire_at,"
          + " trigger, target, status, superseded_by, reason, job_id, arrived_at, started_at,"
          + " finished_at, topic";

  private final JdbcTemplate jdbc;

  public FiringStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<FiringRecord> arrive(
      String event,
      String dataJson,
      String schedule,
      Instant fireAt,
      TriggerRecord trigger,
      Instant at) {
    return arrive(event, dataJson, schedule, fireAt, trigger, at, () -> MemoryIds.mint(PREFIX, at));
  }

  /** Package-private overload for testing: allows custom ID supplier to test collision retry. */
  Optional<FiringRecord> arrive(
      String event,
      String dataJson,
      String schedule,
      Instant fireAt,
      TriggerRecord trigger,
      Instant at,
      Supplier<String> ids) {
    int attempts = 0;
    DuplicateKeyException lastException = null;

    while (attempts < 3) {
      String id = ids.get();
      try {
        jdbc.update(
            "INSERT INTO firings (id, event, data, schedule, fire_at, trigger, target,"
                + " status, arrived_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)",
            id,
            event,
            dataJson,
            schedule,
            ts(fireAt),
            trigger == null ? null : trigger.name(),
            trigger == null ? null : trigger.target(),
            trigger == null ? "unmatched" : "queued",
            ts(at));
        return find(id);
      } catch (DuplicateKeyException dke) {
        lastException = dke;
        // Check if this is a tick collision or an ID collision
        if (isTickCollision(dke)) {
          // Same tick for same trigger: reject once
          return Optional.empty();
        }
        // ID collision: retry with fresh ID
        attempts++;
      }
    }

    // Out of retries: rethrow the last exception
    throw lastException;
  }

  /** The event a board wake carries — spec 2026-09-29 §4. */
  public static final String WAKE_EVENT = "board.wake";

  /**
   * Owes one wake to a seat: a queued firing with a topic and no trigger. Its data says why
   * (reason, message, author) and is data, never instructions — the seat reads the board through
   * its own tool.
   */
  public Optional<FiringRecord> owe(String topic, String target, String dataJson, Instant at) {
    String id = MemoryIds.mint(PREFIX, at);
    jdbc.update(
        "INSERT INTO firings (id, event, data, target, status, arrived_at, topic)"
            + " VALUES (?, ?, ?::jsonb, ?, 'queued', ?, ?)",
        id,
        WAKE_EVENT,
        dataJson,
        target,
        ts(at),
        topic);
    return find(id);
  }

  /**
   * {@link #supersedeBeyond}'s rule for wakes, scoped by target: a seat's conversation is one
   * topic's, so the newest replacing a waiting one loses nothing the seat needs (spec §4, queue cap
   * 1). A wake does carry data — {@code {reason, message, by}} — and the superseded one's is
   * dropped: the newest wake's reason is the one the seat is told. What it would have been told of
   * is not lost with it, since the seat reads every unread message through its own {@code
   * seen_through}, whichever wake survived.
   */
  public int supersedeWakesBeyond(String target, int cap, String newest) {
    return jdbc.update(
        """
                UPDATE firings SET status = 'superseded', superseded_by = ?
                 WHERE target = ? AND topic IS NOT NULL AND status = 'queued' AND id NOT IN (
                     SELECT id FROM firings WHERE target = ? AND topic IS NOT NULL
                        AND status = 'queued'
                      ORDER BY arrived_at DESC, id DESC LIMIT ?)
                """,
        newest,
        target,
        target,
        cap);
  }

  private boolean isTickCollision(DuplicateKeyException dke) {
    Throwable cause = dke.getCause();
    if (cause instanceof PSQLException pse) {
      try {
        String constraintName = pse.getServerErrorMessage().getConstraint();
        return "firings_one_per_tick".equals(constraintName);
      } catch (Exception e) {
        // If we can't read the constraint name, rethrow rather than guess
        throw dke;
      }
    }
    // If not a PSQLException, rethrow
    throw dke;
  }

  public Optional<FiringRecord> find(String id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM firings WHERE id = ?", FiringStore::row, id)
        .stream()
        .findFirst();
  }

  public boolean busy(String target) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM firings"
                + " WHERE target = ? AND status = 'started' AND finished_at IS NULL)",
            Boolean.class,
            target));
  }

  /**
   * Supersede every queued firing of {@code trigger} beyond its {@code cap}, keeping the newest.
   *
   * <p>Scoped by {@code trigger} and not by target: two triggers can share a target (both named at
   * the same conversation), and each trigger's own queue cap must not reach into the other's queued
   * firings.
   */
  public int supersedeBeyond(String trigger, int cap, String newest) {
    return jdbc.update(
        """
                UPDATE firings SET status = 'superseded', superseded_by = ?
                 WHERE trigger = ? AND status = 'queued' AND id NOT IN (
                     SELECT id FROM firings WHERE trigger = ? AND status = 'queued'
                      ORDER BY arrived_at DESC, id DESC LIMIT ?)
                """,
        newest,
        trigger,
        trigger,
        cap);
  }

  public Optional<FiringRecord> oldestWaiting(String target) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM firings WHERE target = ? AND status = 'queued'"
                + " ORDER BY jsonb_exists(data, 'message_continuation') DESC, arrived_at, id LIMIT 1",
            FiringStore::row,
            target)
        .stream()
        .findFirst();
  }

  /**
   * Claim {@code id} to start now, or answer false if it cannot — either because it is not queued,
   * or because {@code firings_one_running_per_target} refused a second running firing on the same
   * target. The second case is a real race: two overlapping drains (a job ending on one thread, an
   * event arriving on another) can both see a target free and both attempt a claim, and only one
   * may win.
   */
  public boolean claimStart(String id, Instant at) {
    try {
      return jdbc.update(
              "UPDATE firings SET status = 'started', job_id = 'pending',"
                  + " started_at = ? WHERE id = ? AND status = 'queued'",
              ts(at),
              id)
          == 1;
    } catch (DuplicateKeyException alreadyRunning) {
      return false;
    }
  }

  public void startedAs(String id, String jobId) {
    jdbc.update("UPDATE firings SET job_id = ? WHERE id = ? AND status = 'started'", jobId, id);
  }

  /**
   * From a claim whose start turned out to be premature: give the target back the firing, queued,
   * so a later drain can try again. Used when the runner discovers, only after claiming, that its
   * target became busy underneath it.
   */
  public void release(String id) {
    jdbc.update(
        "UPDATE firings SET status = 'queued', job_id = NULL, started_at = NULL"
            + " WHERE id = ? AND status = 'started' AND finished_at IS NULL",
        id);
  }

  /** From queued (never claimed), or from a claim whose start then failed. */
  public void refuse(String id, String reason) {
    jdbc.update(
        "UPDATE firings SET status = 'refused', reason = ?, job_id = NULL,"
            + " started_at = NULL WHERE id = ? AND status IN ('queued', 'started')"
            + " AND finished_at IS NULL",
        reason,
        id);
  }

  public void finish(String id, Instant at) {
    jdbc.update(
        "UPDATE firings SET finished_at = ? WHERE id = ? AND status = 'started'"
            + " AND finished_at IS NULL",
        ts(at),
        id);
  }

  /**
   * At boot, finish every started firing with no end time.
   *
   * <p>This assumes every server sharing the database restarts together, as jobs already do: a job
   * lost to a restart keeps no {@code ended_at}. With several servers, a restart on one can free a
   * trigger whose run is still live on another, allowing one overlapping run.
   */
  public int abandonUnfinished(String reason, Instant at) {
    return jdbc.update(
        "UPDATE firings SET finished_at = ?, reason = ? WHERE status = 'started'"
            + " AND finished_at IS NULL",
        ts(at),
        reason);
  }

  public int refuseWaiting(String trigger, String reason) {
    return jdbc.update(
        "UPDATE firings SET status = 'refused', reason = ? WHERE trigger = ?"
            + " AND status = 'queued'",
        reason,
        trigger);
  }

  /**
   * Refuses every wake still queued on {@code topic} — a topic that closes is not woken into again
   * (spec §6). A wake already started finishes its step; this does not touch it.
   */
  public int refuseWakes(String topic, String reason) {
    return jdbc.update(
        "UPDATE firings SET status = 'refused', reason = ? WHERE topic = ?"
            + " AND status = 'queued'",
        reason,
        topic);
  }

  public List<String> targetsWaiting() {
    return jdbc.queryForList(
        "SELECT DISTINCT target FROM firings WHERE status = 'queued'", String.class);
  }

  public List<FiringRecord> list(String trigger, String status, int offset, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM firings"
            + " WHERE (CAST(? AS TEXT) IS NULL OR trigger = ?)"
            + " AND (CAST(? AS TEXT) IS NULL OR status = ?)"
            + " AND NOT EXISTS (SELECT 1 FROM board_message_instances i WHERE i.topic = firings.topic)"
            + " ORDER BY arrived_at DESC, id DESC OFFSET ? LIMIT ?",
        FiringStore::row,
        trigger,
        trigger,
        status,
        status,
        offset,
        limit);
  }

  private static Timestamp ts(Instant at) {
    return at == null ? null : Timestamp.from(at);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  private static FiringRecord row(ResultSet rs, int n) throws SQLException {
    return new FiringRecord(
        rs.getString("id"),
        rs.getString("event"),
        rs.getString("data"),
        rs.getString("schedule"),
        instant(rs, "fire_at"),
        rs.getString("trigger"),
        rs.getString("target"),
        rs.getString("status"),
        rs.getString("superseded_by"),
        rs.getString("reason"),
        rs.getString("job_id"),
        instant(rs, "arrived_at"),
        instant(rs, "started_at"),
        instant(rs, "finished_at"),
        rs.getString("topic"));
  }
}
