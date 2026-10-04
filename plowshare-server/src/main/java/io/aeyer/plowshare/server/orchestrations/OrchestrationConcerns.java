package io.aeyer.plowshare.server.orchestrations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V77's {@code orchestration_concerns}: the acceptance checker's concerns, one row per concern per
 * run (spec 2026-10-01, the acceptance checker §2). Written by the harness only; what a concern
 * went through — each WHY, each answer, each verdict — is also a {@code concern} line of the run's
 * record, so the row is its state now and the record its story.
 */
public final class OrchestrationConcerns implements Concerns {

  private final JdbcTemplate jdbc;
  private final Supplier<Instant> clock;

  public OrchestrationConcerns(JdbcTemplate jdbc, Supplier<Instant> clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<Concern> of(String run) {
    return jdbc.query(
        SELECT + " WHERE orchestration = ? ORDER BY created_at," + " substring(id FROM 2)::int",
        (row, n) -> concern(row),
        run);
  }

  /**
   * The next id is the run's highest plus one, in the same statement as the insert. A run's
   * concerns are raised by its own conductor's tool calls, one at a time; two writes racing for one
   * id would meet the primary key, and the second would fail rather than overwrite.
   */
  @Override
  public Concern raise(String run, String about, String why, String raised, String question) {
    Timestamp now = Timestamp.from(clock.get());
    String state = question == null ? OPEN : ASKED;
    int rounds = question == null ? 0 : 1;
    return jdbc.queryForObject(
        "INSERT INTO orchestration_concerns (orchestration, id, about,"
            + " why, raised, state, rounds, question, created_at, updated_at)"
            + " SELECT ?, 'c' || (COALESCE(MAX(substring(id FROM 2)::int), 0) + 1), ?, ?, ?, ?,"
            + " ?, ?, ?, ? FROM orchestration_concerns WHERE orchestration = ?"
            + " RETURNING "
            + COLUMNS,
        (row, n) -> concern(row),
        run,
        about,
        why,
        raised,
        state,
        rounds,
        question,
        now,
        now,
        run);
  }

  @Override
  public void answered(String run, String id, String reason) {
    update(run, id, "state = 'answered', reason = ?", reason);
  }

  @Override
  public void resolved(String run, String id) {
    update(run, id, "state = 'resolved'");
  }

  @Override
  public void askedAgain(String run, String id, String objection, String question) {
    update(
        run,
        id,
        "state = 'asked', rounds = LEAST(rounds + 1, "
            + MOST_ROUNDS
            + "),"
            + " objection = ?, question = ?",
        objection,
        question);
  }

  @Override
  public void forThePerson(String run, String id, String objection) {
    update(run, id, "state = 'for_the_person', verdict = NULL, objection = ?", objection);
  }

  @Override
  public void checked(String run, String id, String verdict, String finding, String personCheck) {
    String state = CANNOT_CHECK.equals(verdict) ? FOR_THE_PERSON : CHECKED;
    update(
        run,
        id,
        "state = ?, verdict = ?, finding = ?, person_check = ?",
        state,
        verdict,
        finding,
        personCheck);
  }

  @Override
  public void personAnswered(String run, String id, String answer, boolean accepted) {
    update(run, id, "state = ?, person_answer = ?", accepted ? RESOLVED : OPEN, answer);
  }

  private void update(String run, String id, String set, Object... values) {
    Object[] all = new Object[values.length + 3];
    System.arraycopy(values, 0, all, 0, values.length);
    all[values.length] = Timestamp.from(clock.get());
    all[values.length + 1] = run;
    all[values.length + 2] = id;
    jdbc.update(
        "UPDATE orchestration_concerns SET "
            + set
            + ", updated_at = ?"
            + " WHERE orchestration = ? AND id = ?",
        all);
  }

  private static final String COLUMNS =
      "orchestration, id, about, why, raised, state, rounds,"
          + " question, reason, objection, verdict, finding, person_check, person_answer,"
          + " updated_at";

  private static final String SELECT = "SELECT " + COLUMNS + " FROM orchestration_concerns";

  private static Concern concern(ResultSet row) throws SQLException {
    return new Concern(
        row.getString("orchestration"),
        row.getString("id"),
        row.getString("about"),
        row.getString("why"),
        row.getString("raised"),
        row.getString("state"),
        row.getInt("rounds"),
        row.getString("question"),
        row.getString("reason"),
        row.getString("objection"),
        row.getString("verdict"),
        row.getString("finding"),
        row.getString("person_check"),
        row.getString("person_answer"),
        row.getTimestamp("updated_at").toInstant());
  }
}
