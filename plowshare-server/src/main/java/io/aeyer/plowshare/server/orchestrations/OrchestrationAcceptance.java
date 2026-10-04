package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A run's acceptance commands (spec 2026-09-29 §1b), as registered from its spec.md: {@link
 * OrchestrationChecks}' shape, one row per command, each with the consent it runs under. Unlike the
 * check, the set is not fixed once written — spec.md is the conductor's to edit until the
 * acceptance stage, and a section that changed is registered again, with new approvals, before it
 * runs: what runs is always what the person was asked about.
 */
public final class OrchestrationAcceptance {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * One registered command, with the consent it runs under.
   *
   * @param orchestration the run
   * @param position its place in the section, from 0
   * @param line the line as written in spec.md
   * @param argv the program and its arguments
   * @param stdin its input, or null
   * @param exit the exit code it must end with
   * @param expect text its output must contain, or null
   * @param side the side it was placed on, and so the side its consent is for
   * @param cwd the directory it runs in
   * @param consent {@link OrchestrationChecks#OPEN} or {@link OrchestrationChecks#APPROVAL}
   * @param approval the approval's id when {@code consent} is {@link OrchestrationChecks#APPROVAL},
   *     else null
   * @param setAt when it was registered
   * @param runsFor how many seconds it must still be running after (spec 2026-10-01 §1), or null
   *     for a command held to {@code exit}
   */
  public record Registered(
      String orchestration,
      int position,
      String line,
      List<String> argv,
      String stdin,
      int exit,
      String expect,
      String side,
      String cwd,
      String consent,
      String approval,
      Instant setAt,
      Integer runsFor) {
    /** Copies {@code argv}, so a registered command is the command that runs. */
    public Registered {
      argv = List.copyOf(argv);
    }

    /** A command held to its exit code — every row before {@code runs-for:} existed. */
    public Registered(
        String orchestration,
        int position,
        String line,
        List<String> argv,
        String stdin,
        int exit,
        String expect,
        String side,
        String cwd,
        String consent,
        String approval,
        Instant setAt) {
      this(
          orchestration,
          position,
          line,
          argv,
          stdin,
          exit,
          expect,
          side,
          cwd,
          consent,
          approval,
          setAt,
          null);
    }
  }

  private final JdbcTemplate jdbc;
  private final UnitOfWork work;

  /**
   * @param jdbc V60's {@code orchestration_acceptance}
   * @param work the transaction a set is replaced in, so no reader sees half of one
   */
  public OrchestrationAcceptance(JdbcTemplate jdbc, UnitOfWork work) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.work = Objects.requireNonNull(work, "work");
  }

  /**
   * The run's set, replaced whole: a section registered again is a new set.
   *
   * @param orchestration the run
   * @param requirements spec.md outside its acceptance section ({@link Acceptance#requirements}),
   *     as it stood when the set was registered — what the set is held to, stored beside it so the
   *     acceptance stage can tell a rewritten spec from the approved one
   * @param commands its commands, in order
   */
  public void replace(String orchestration, String requirements, List<Registered> commands) {
    work.inTransaction(
        () -> {
          jdbc.update(
              "INSERT INTO orchestration_acceptance_requirements (orchestration,"
                  + " requirements, set_at) VALUES (?, ?, now()) ON CONFLICT (orchestration)"
                  + " DO UPDATE SET requirements = EXCLUDED.requirements, set_at = now()",
              orchestration,
              requirements);
          jdbc.update(
              "DELETE FROM orchestration_acceptance WHERE orchestration = ?", orchestration);
          for (Registered c : commands) {
            jdbc.update(
                "INSERT INTO orchestration_acceptance (orchestration, position, line,"
                    + " argv, stdin, exit_code, expect, side, cwd, consent, approval, set_at,"
                    + " runs_for) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                c.orchestration(),
                c.position(),
                c.line(),
                json(c.argv()),
                c.stdin(),
                c.exit(),
                c.expect(),
                c.side(),
                c.cwd(),
                c.consent(),
                c.approval(),
                Timestamp.from(c.setAt()),
                c.runsFor());
          }
          return null;
        });
  }

  /**
   * @param orchestration the run
   * @return its registered commands in the section's order; empty when none are
   */
  public List<Registered> find(String orchestration) {
    return jdbc.query(
        SELECT + " WHERE orchestration = ? ORDER BY position",
        (row, n) -> registered(row),
        orchestration);
  }

  /**
   * @param orchestration the run
   * @return the requirements its set was registered against, or empty when none was
   */
  public Optional<String> requirements(String orchestration) {
    return jdbc
        .query(
            "SELECT requirements FROM orchestration_acceptance_requirements WHERE"
                + " orchestration = ?",
            (row, n) -> row.getString("requirements"),
            orchestration)
        .stream()
        .findFirst();
  }

  /**
   * @param approval an approval's id
   * @return the registered command asked under it, if it is one
   */
  public Optional<Registered> byApproval(String approval) {
    return jdbc
        .query(SELECT + " WHERE approval = ?", (row, n) -> registered(row), approval)
        .stream()
        .findFirst();
  }

  private static final String SELECT =
      "SELECT orchestration, position, line, argv::text AS"
          + " argv, stdin, exit_code, expect, side, cwd, consent, approval, set_at, runs_for"
          + " FROM orchestration_acceptance";

  private static Registered registered(ResultSet row) throws SQLException {
    return new Registered(
        row.getString("orchestration"),
        row.getInt("position"),
        row.getString("line"),
        argv(row.getString("argv")),
        row.getString("stdin"),
        row.getInt("exit_code"),
        row.getString("expect"),
        row.getString("side"),
        row.getString("cwd"),
        row.getString("consent"),
        row.getString("approval"),
        row.getTimestamp("set_at").toInstant(),
        (Integer) row.getObject("runs_for"));
  }

  private static String json(List<String> argv) {
    try {
      return JSON.writeValueAsString(argv);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static List<String> argv(String json) {
    try {
      return JSON.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException corrupt) {
      throw new IllegalStateException(
          "orchestration_acceptance.argv is not a list: " + json, corrupt);
    }
  }
}
