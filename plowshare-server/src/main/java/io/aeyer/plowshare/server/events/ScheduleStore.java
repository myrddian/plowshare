package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The schedules table, and the compare-and-set that makes a tick one server's. A schedule is owned
 * by the account that defined it, on {@link TriggerStore}'s reasoning; listing stays open.
 */
public class ScheduleStore {

  private static final String COLUMNS = "name, cron, zone, emits, paused, next_fire_at, defined_by";

  private static final String EMITTER_AUTHORIZED =
      "EXISTS (SELECT 1 FROM admins a WHERE a.handle=schedules.defined_by"
          + " AND a.enabled AND a.server_admin AND NOT a.bootstrap)";

  private final JdbcTemplate jdbc;

  public ScheduleStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Defines it, or replaces the one of that name if {@code definedBy} defined it; the ownership
   * test is the upsert's own {@code WHERE}, so a declined conflict returns no row.
   */
  public ScheduleRecord define(
      String name, CronSchedule schedule, String emits, String definedBy, Instant now) {
    List<ScheduleRecord> stored =
        jdbc.query(
            """
                INSERT INTO schedules (name, cron, zone, emits, next_fire_at, defined_by)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO UPDATE SET cron = EXCLUDED.cron, zone = EXCLUDED.zone,
                    emits = EXCLUDED.emits, next_fire_at = EXCLUDED.next_fire_at,
                    defined_at = now()
                WHERE schedules.defined_by = EXCLUDED.defined_by
                RETURNING
                """
                + COLUMNS,
            ScheduleStore::row,
            name,
            schedule.cron(),
            schedule.zone().getId(),
            emits,
            Timestamp.from(schedule.nextAfter(now)),
            definedBy);
    if (stored.isEmpty()) {
      throw new CallerFault(
          "a schedule named "
              + name
              + " belongs to another account;"
              + " choose another name. Nothing was defined.");
    }
    return stored.get(0);
  }

  public List<ScheduleRecord> list() {
    return jdbc.query("SELECT " + COLUMNS + " FROM schedules ORDER BY name", ScheduleStore::row);
  }

  public Optional<ScheduleRecord> find(String name) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM schedules WHERE name = ?", ScheduleStore::row, name)
        .stream()
        .findFirst();
  }

  /** Scoped by {@code handle}: another account's schedule answers exactly as none does. */
  public void pause(String name, boolean paused, String handle) {
    if (jdbc.update(
            "UPDATE schedules SET paused = ? WHERE name = ? AND defined_by = ?",
            paused,
            name,
            handle)
        == 0) {
      throw notYours(name);
    }
  }

  /** Scoped by {@code handle}: another account's schedule answers exactly as none does. */
  public void forget(String name, String handle) {
    if (jdbc.update("DELETE FROM schedules WHERE name = ? AND defined_by = ?", name, handle) == 0) {
      throw notYours(name);
    }
  }

  private static ArchiveException notYours(String name) {
    return new ArchiveException("no schedule named " + name + " is yours");
  }

  public List<ScheduleRecord> due(Instant now) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM schedules"
            + " WHERE NOT paused AND "
            + EMITTER_AUTHORIZED
            + " AND next_fire_at <= ? ORDER BY next_fire_at, name",
        ScheduleStore::row,
        Timestamp.from(now));
  }

  /** True for the one caller whose UPDATE moved the fire time; false for every other. */
  public boolean claim(String name, Instant due, Instant following) {
    return jdbc.update(
            "UPDATE schedules SET next_fire_at = ?"
                + " WHERE name = ? AND next_fire_at = ? AND NOT paused AND "
                + EMITTER_AUTHORIZED,
            Timestamp.from(following),
            name,
            Timestamp.from(due))
        == 1;
  }

  /**
   * Missed ticks are not replayed: every fire time older than {@code olderThan} moves to its next
   * future one.
   *
   * <p>A {@code following} that answers the stale fire time unchanged — a broken cron that cannot
   * be parsed — is not counted as moved: {@link #claim} would still answer true for {@code
   * claim(name, due, due)} because the row it matches is still there, so counting it would report a
   * schedule as rolled forward that in fact went nowhere.
   */
  public int rollForward(Instant olderThan, Function<ScheduleRecord, Instant> following) {
    int moved = 0;
    for (ScheduleRecord stale :
        jdbc.query(
            "SELECT " + COLUMNS + " FROM schedules" + " WHERE next_fire_at < ?",
            ScheduleStore::row,
            Timestamp.from(olderThan))) {
      Instant next = following.apply(stale);
      if (!next.equals(stale.nextFireAt()) && claim(stale.name(), stale.nextFireAt(), next)) {
        moved++;
      }
    }
    return moved;
  }

  private static ScheduleRecord row(ResultSet rs, int n) throws SQLException {
    return new ScheduleRecord(
        rs.getString("name"),
        rs.getString("cron"),
        rs.getString("zone"),
        rs.getString("emits"),
        rs.getBoolean("paused"),
        rs.getObject("next_fire_at", OffsetDateTime.class).toInstant(),
        rs.getString("defined_by"));
  }
}
