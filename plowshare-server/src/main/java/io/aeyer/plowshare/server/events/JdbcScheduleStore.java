package io.aeyer.plowshare.server.events;

import io.aeyer.plowshare.server.archive.ArchiveException;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.relay.Relay;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The schedules table, and the compare-and-set that makes a tick one server's. A schedule is owned
 * by the account that defined it, on {@link TriggerStore}'s reasoning; listing stays open.
 */
public class JdbcScheduleStore implements ScheduleStore {

  private static final String COLUMNS = "name, cron, zone, emits, paused, next_fire_at, defined_by";

  private static final String EMITTER_AUTHORIZED = ScheduleSql.emitterAuthorized("schedules");

  private final JdbcTemplate jdbc;

  public JdbcScheduleStore(JdbcTemplate jdbc) {
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
            JdbcScheduleStore::row,
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
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM schedules ORDER BY name", JdbcScheduleStore::row);
  }

  public List<ScheduleRecord> page(String account, String project, SchedulePage page) {
    if (account == null || account.isBlank() || account.length() > 256)
      throw new IllegalArgumentException("Authenticated schedule account required");
    java.util.Objects.requireNonNull(page);
    var args = new java.util.ArrayList<Object>();
    args.add(project == null ? account : project);
    if (page.after() != null) args.add(page.after());
    args.add(page.limit());
    // Timing and pause state are operational projections. This bounded current read deliberately
    // bypasses the definition cache so a page cannot retain an earlier next-fire/claim decision.
    return List.copyOf(
        jdbc.query(
            "SELECT "
                + COLUMNS
                + " FROM schedules WHERE "
                + (project == null
                    ? "defined_by=?"
                    : "EXISTS(SELECT 1 FROM schedule_files f JOIN schedule_sources source ON source.id=f.source_id JOIN projects p ON p.id=source.project_id WHERE f.internal_name=schedules.name AND p.name=?)")
                + (page.after() == null ? "" : " AND schedules.name>?")
                + " ORDER BY schedules.name LIMIT ?",
            JdbcScheduleStore::row,
            args.toArray()));
  }

  public Optional<ScheduleRecord> find(String name) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM schedules WHERE name = ?", JdbcScheduleStore::row, name)
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
            + ", (SELECT f.generation FROM schedule_files f"
            + " WHERE schedules.file_managed AND f.internal_name=schedules.name) AS file_generation"
            + " FROM schedules"
            + " WHERE NOT paused AND "
            + EMITTER_AUTHORIZED
            + " AND next_fire_at <= ? ORDER BY next_fire_at, name",
        (row, index) -> JdbcScheduleStore.row(row, row.getObject("file_generation", UUID.class)),
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
            JdbcScheduleStore::row,
            Timestamp.from(olderThan))) {
      Instant next = following.apply(stale);
      if (!next.equals(stale.nextFireAt()) && claim(stale.name(), stale.nextFireAt(), next)) {
        moved++;
      }
    }
    return moved;
  }

  @Override
  public Optional<Claim> claimPublication(ScheduleRecord selected, Instant following) {
    java.util.Objects.requireNonNull(selected, "selected schedule");
    java.util.Objects.requireNonNull(following, "following fire time");
    if (!following.isAfter(selected.nextFireAt()))
      throw new IllegalArgumentException("the next schedule occurrence must move forward");
    if (selected.fileGeneration() != null) {
      if (!TransactionSynchronizationManager.isActualTransactionActive())
        throw new IllegalStateException("A managed schedule claim requires a transaction");
      // Every reconciliation that changes the file generation writes this schedule row first.
      // A separate statement gets a fresh READ COMMITTED snapshot after any waiting editor,
      // including when an input-only edit left every timing column unchanged.
      if (jdbc.queryForList(
              "SELECT name FROM schedules WHERE name=? FOR UPDATE", String.class, selected.name())
          .isEmpty()) return Optional.empty();
    }
    // Matching timing alone is insufficient: a file/legacy edit can keep the same due time
    // while changing the event or owner. Return only the scope of the definition actually claimed.
    return jdbc
        .query(
            """
        WITH claimed AS (
          UPDATE schedules SET next_fire_at=?
          WHERE name=? AND next_fire_at=? AND cron=? AND zone=? AND emits=? AND defined_by=?
            AND (SELECT f.generation FROM schedule_files f
                WHERE schedules.file_managed AND f.internal_name=schedules.name)
                IS NOT DISTINCT FROM ?::uuid
            AND NOT paused AND
        """
                + EMITTER_AUTHORIZED
                + """
          RETURNING name,file_managed,cron,emits
        )
        SELECT s.project_id,b.generation AS goal_generation,f.definition->>'zone' AS file_zone
        FROM claimed c
        LEFT JOIN schedule_files f ON c.file_managed AND f.internal_name=c.name
        LEFT JOIN schedule_sources s ON s.id=f.source_id
        LEFT JOIN goal_schedule_bindings b ON b.schedule=f.internal_name AND NOT b.paused
          AND c.emits=f.internal_name AND c.cron=f.definition->>'cron'
        """,
            (row, index) -> {
              Long project = row.getObject("project_id", Long.class);
              Relay.Scope scope =
                  project == null ? Relay.SystemScope.SERVER : new Relay.ProjectScope(project);
              return new Claim(scope, goalGeneration(row, selected.zone()));
            },
            Timestamp.from(following),
            selected.name(),
            Timestamp.from(selected.nextFireAt()),
            selected.cron(),
            selected.zone(),
            selected.emits(),
            selected.definedBy(),
            selected.fileGeneration())
        .stream()
        .findFirst();
  }

  private static UUID goalGeneration(ResultSet row, String runtimeZone) throws SQLException {
    UUID generation = row.getObject("goal_generation", UUID.class);
    if (generation == null) return null;
    String fileZone = row.getString("file_zone");
    if (fileZone == null) return null;
    try {
      // Legacy admin controls can alter the runtime row without reconciling its file. They
      // still fire under their existing contract but cannot mint evidence for a Goal binding.
      return runtimeZone.equals(ZoneId.of(fileZone).getId()) ? generation : null;
    } catch (DateTimeException invalid) {
      return null;
    }
  }

  private static ScheduleRecord row(ResultSet rs, int n) throws SQLException {
    return row(rs, null);
  }

  private static ScheduleRecord row(ResultSet rs, UUID generation) throws SQLException {
    return new ScheduleRecord(
        rs.getString("name"),
        rs.getString("cron"),
        rs.getString("zone"),
        rs.getString("emits"),
        rs.getBoolean("paused"),
        rs.getObject("next_fire_at", OffsetDateTime.class).toInstant(),
        rs.getString("defined_by"),
        generation);
  }
}
