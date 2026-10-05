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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The firings table, which is the queue. Every transition is a guarded UPDATE. */
public class JdbcFiringStore implements FiringStore {

  public static final String PREFIX = "fir_";

  private static final String COLUMNS =
      "id, event, data::text AS data, schedule, fire_at,"
          + " trigger, target, status, superseded_by, reason, job_id, arrived_at, started_at,"
          + " finished_at, topic";

  private final JdbcTemplate jdbc;

  public JdbcFiringStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Keyset paging bounds memory while including queued, interrupted and terminal firings. */
  @Override
  public EventCompatibility preflight() {
    record Checked(String id, boolean compatible) {}
    long scanned = 0;
    long invalid = 0;
    String after = null;
    var problems = new java.util.ArrayList<EventCompatibility.Problem>();
    while (true) {
      var page =
          jdbc.query(
              "SELECT "
                  + COLUMNS
                  + " FROM firings WHERE (CAST(? AS TEXT) IS NULL OR id > ?) ORDER BY id LIMIT 1000",
              (rs, n) -> {
                String id = rs.getString("id");
                try {
                  row(rs, n);
                  return new Checked(id, true);
                } catch (IllegalArgumentException incompatible) {
                  return new Checked(id, false);
                }
              },
              after,
              after);
      for (var checked : page) {
        scanned++;
        if (!checked.compatible()) {
          invalid++;
          if (problems.size() < 100) {
            String id;
            try {
              id = EventPayload.identity(checked.id(), "firing");
            } catch (IllegalArgumentException invalidId) {
              id = "invalid-row-id";
            }
            problems.add(
                new EventCompatibility.Problem(id, EventCompatibility.Code.INVALID_EVENT_DTO));
          }
        }
      }
      if (page.size() < 1000) break;
      after = page.getLast().id();
    }
    return new EventCompatibility(scanned, invalid, problems);
  }

  public Optional<FiringRecord> arrive(
      String event,
      EventPayload data,
      String schedule,
      Instant fireAt,
      TriggerRecord trigger,
      Instant at) {
    return arrive(event, data, schedule, fireAt, trigger, at, () -> MemoryIds.mint(PREFIX, at));
  }

  /** Package-private overload for testing: allows custom ID supplier to test collision retry. */
  Optional<FiringRecord> arrive(
      String event,
      EventPayload data,
      String schedule,
      Instant fireAt,
      TriggerRecord trigger,
      Instant at,
      Supplier<String> ids) {
    java.util.Objects.requireNonNull(at, "arrival time");
    EventPayloadCodec.require(event, null, schedule, fireAt, data);
    // ON CONFLICT keeps a duplicate tick or ID from aborting the owning scheduler transaction.
    // Catching a PostgreSQL uniqueness exception and continuing would leave that transaction
    // poisoned, preventing the publication, remaining fan-out and seen cursor from committing.
    for (int attempt = 0; attempt < 3; attempt++) {
      String id = ids.get();
      int inserted =
          jdbc.update(
              "INSERT INTO firings (id, event, data, schedule, fire_at, trigger, target,"
                  + " status, arrived_at) VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
              id,
              event,
              EventPayloadCodec.write(data),
              schedule,
              ts(fireAt),
              trigger == null ? null : trigger.name(),
              trigger == null ? null : trigger.target(),
              trigger == null ? "unmatched" : "queued",
              ts(at));
      if (inserted == 1) return find(id);
      if (schedule != null
          && Boolean.TRUE.equals(
              jdbc.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM firings WHERE schedule=? AND fire_at=? AND COALESCE(trigger,'')=COALESCE(?,''))",
                  Boolean.class,
                  schedule,
                  ts(fireAt),
                  trigger == null ? null : trigger.name()))) return Optional.empty();
    }
    throw new DuplicateKeyException(
        "could not allocate a unique firing identity after three attempts");
  }

  @Override
  public void lockScheduledQueues(List<String> triggers) {
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("scheduled queue admission requires an owning transaction");
    var names =
        java.util.Objects.requireNonNull(triggers).stream()
            .map(name -> EventPayload.identity(name, "trigger queue"))
            .distinct()
            .sorted()
            .toList();
    // Different project/system topic locks do not serialize one trigger's queue. Transaction
    // advisory locks cover that inbox without locking definition rows in an inverse order to
    // file reconciliation. Hash collisions only serialize unrelated queues conservatively.
    for (var name : names)
      jdbc.queryForList(
          "SELECT pg_advisory_xact_lock(hashtextextended('scheduled-trigger:' || ?, 0))", name);
  }

  @Override
  public List<String> scheduledTargetsWaiting() {
    return List.copyOf(
        jdbc.queryForList(
            """
        SELECT DISTINCT f.target FROM firings f
        WHERE f.schedule IS NOT NULL AND f.status='queued' AND f.target IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM firings busy WHERE busy.target=f.target
            AND busy.status='started' AND busy.finished_at IS NULL)
        ORDER BY f.target LIMIT 100
        """,
            String.class));
  }

  /** The event a board wake carries — spec 2026-09-29 §4. */
  public static final String WAKE_EVENT = "board.wake";

  /**
   * Owes one wake to a seat: a queued firing with a topic and no trigger. Its data says why
   * (reason, message, author) and is data, never instructions — the seat reads the board through
   * its own tool.
   */
  public Optional<FiringRecord> owe(String topic, String target, EventPayload data, Instant at) {
    EventPayload.identity(topic, "wake topic");
    EventPayload.identity(target, "wake target");
    java.util.Objects.requireNonNull(at, "arrival time");
    if (!target.startsWith("conversation:")
        || target.length() == "conversation:".length()
        || !(data instanceof EventPayload.Seat || data instanceof EventPayload.Message))
      throw new IllegalArgumentException(
          "a wake requires a conversation target and an explicit wake DTO");
    EventPayloadCodec.require(WAKE_EVENT, topic, null, null, data);
    String id = MemoryIds.mint(PREFIX, at);
    jdbc.update(
        "INSERT INTO firings (id, event, data, target, status, arrived_at, topic)"
            + " VALUES (?, ?, ?::jsonb, ?, 'queued', ?, ?)",
        id,
        WAKE_EVENT,
        EventPayloadCodec.write(data),
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

  public Optional<FiringRecord> find(String id) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM firings WHERE id = ?", JdbcFiringStore::row, id)
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
            JdbcFiringStore::row,
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
    // An unexpected historical shape must fail before status or the paid-run identity changes.
    if (find(id).isEmpty()) return false;
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
    preflight().requireCompatible();
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
        JdbcFiringStore::row,
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
        EventPayloadCodec.stored(
            rs.getString("event"),
            rs.getString("topic"),
            rs.getString("schedule"),
            instant(rs, "fire_at"),
            rs.getString("data")),
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
