package io.aeyer.plowshare.server.orchestrations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A run's check: the command the harness runs whenever one of its checked stages is marked done.
 * Set once — the primary key is the run — so the bar cannot be moved once it is written. The one
 * way back is {@link #clear}, for a check a person refused to allow, before any checked stage has
 * passed it.
 */
public final class OrchestrationChecks {

  public static final String OPEN = "open";
  public static final String APPROVAL = "approval";

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * @param consent {@link #OPEN} when the side's mode let commands run unasked when it was set,
   *     {@link #APPROVAL} when a person was asked
   * @param approval the approval's id when {@code consent} is {@link #APPROVAL}, else null
   */
  public record Check(
      String orchestration,
      List<String> argv,
      String side,
      String cwd,
      String consent,
      String approval,
      Instant setAt) {
    public Check {
      argv = List.copyOf(argv);
    }
  }

  private final JdbcTemplate jdbc;

  public OrchestrationChecks(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * @return whether this call stored it; false when the run already had one
   */
  public boolean set(Check check) {
    return jdbc.update(
            "INSERT INTO orchestration_checks (orchestration, argv, side, cwd,"
                + " consent, approval, set_at) VALUES (?, ?::jsonb, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (orchestration) DO NOTHING",
            check.orchestration(),
            json(check.argv()),
            check.side(),
            check.cwd(),
            check.consent(),
            check.approval(),
            Timestamp.from(check.setAt()))
        == 1;
  }

  /**
   * Clear a run's check, but only while it is still the one set under {@code approval} — so a check
   * another call replaced in between is never the one removed. The engine calls this for a check
   * whose approval a person denied or revoked, before setting a new one: spec 2026-09-26 §2, as
   * amended by final review F2.
   *
   * @return whether a check was cleared
   */
  public boolean clear(String orchestration, String approval) {
    return jdbc.update(
            "DELETE FROM orchestration_checks WHERE orchestration = ? AND approval = ?",
            orchestration,
            approval)
        == 1;
  }

  public Optional<Check> find(String orchestration) {
    return jdbc
        .query(
            "SELECT orchestration, argv::text AS argv, side, cwd, consent, approval,"
                + " set_at FROM orchestration_checks WHERE orchestration = ?",
            (row, n) ->
                new Check(
                    row.getString("orchestration"),
                    argv(row.getString("argv")),
                    row.getString("side"),
                    row.getString("cwd"),
                    row.getString("consent"),
                    row.getString("approval"),
                    row.getTimestamp("set_at").toInstant()),
            orchestration)
        .stream()
        .findFirst();
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
      throw new IllegalStateException("orchestration_checks.argv is not a list: " + json, corrupt);
    }
  }
}
