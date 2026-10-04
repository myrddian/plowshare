package io.aeyer.plowshare.server.archive;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.MemoryIds;
import io.aeyer.plowshare.server.agents.Outcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The durable record that a run happened: {@code jobs}, written at both ends of a job and pruned by
 * policy.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code agents.JobStore} minted job ids from an in-process {@code AtomicLong}, so they
 * restarted at {@code job_000001} on every boot and two unrelated runs on two different days shared
 * a name. {@code V24__jobs.sql} carries the whole argument; the two consequences are that nothing
 * durable could reference a job, and that nothing durable said a run had happened at all — which
 * stopped being tolerable when one job became twenty-six minutes of a document ingest.
 *
 * <h2>Two writes and one of them is optional</h2>
 *
 * <p>{@link #started} at submission and {@link #ended} when an outcome is filed. The second may
 * never come: a process that dies mid-run leaves a row with no ending, and that is the honest state
 * rather than a defect. Nothing here writes a {@code RUNNING}, because after a restart this table
 * cannot know.
 *
 * <p><b>Neither write is on the run's critical path in the sense that matters.</b> {@code JobStore}
 * treats a failure of either the way {@code Compaction.logFor} treats a conversation that could not
 * be opened: the run goes on without a record rather than not at all. The alternative is losing a
 * submission over a database blink, and this table's whole purpose is to be readable afterwards
 * rather than to gate anything.
 *
 * <h2>Where the id is minted</h2>
 *
 * <p>{@link #newId} is static and does not touch the database, because {@code JobStore} must be
 * able to mint one with no store wired at all — three dozen fixtures build a store over a stub
 * runtime and no database, and a job with no id is a job nothing can poll. So the id scheme lives
 * here, next to the table whose ordering depends on it, and the store that has no {@code JobLog}
 * still gets the same ids as the one that has.
 */
public final class JobLog {

  /** {@code MemoryIds}' fourth prefix, after {@code mem_}, {@code prp_} and {@code cnv_}. */
  public static final String PREFIX = "job_";

  private static final String COLUMNS =
      "j.id, p.name AS project, j.agent, j.started_at, j.ended_at, j.ending, j.steps,"
          + " j.model_calls";

  /*
   * LEFT JOIN and not an inner one, for ConversationStore.FROM_CONVERSATIONS'
   * reason: a NULL project is the global tier, which is the ordinary shape of
   * a run nobody named a project for, and an inner join would delete that tier
   * from every read this class does.
   */
  private static final String FROM_JOBS =
      " FROM jobs j LEFT JOIN projects p ON p.id = j.project_id";

  private final JdbcTemplate jdbc;

  public JobLog(JdbcTemplate jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * A fresh job id for a run submitted at {@code at}.
   *
   * <p><b>{@code MemoryIds.mint} and not a counter, a hash or a UUID.</b> The suffix is ten hex
   * digits of milliseconds from a 2020 epoch, zero-padded, then six of randomness — so string order
   * is minting order, and {@code ORDER BY id} over this table means "in the order things happened"
   * with no timestamp column needing to be consulted. A hash would destroy that ordering, and it is
   * the query a trajectory view lives on.
   *
   * <p>The instant is the caller's rather than read from a clock here, so that the id and the
   * {@code started_at} written beside it come from one reading. {@code MemoryIds}' javadoc names
   * what the alternative costs: an id whose embedded timestamp disagrees with the record's own
   * would sort into a position its history contradicts.
   */
  public static String newId(Instant at) {
    return MemoryIds.mint(PREFIX, at);
  }

  /**
   * Write down that a run was submitted.
   *
   * @param id from {@link #newId}, minted from the same instant as {@code at}
   * @param agent what the job is polled under; an agent's name, or the caller's own for work that
   *     is not one agent's run
   * @param home the tier it answers in. {@link Home#global()} is stored as a NULL project and is
   *     ordinary
   * @param at when it was submitted
   */
  public void started(String id, String agent, Home home, Instant at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(agent, "agent");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(at, "at");
    ArchiveUnavailableException.translating(
        "write down that a job started",
        () ->
            jdbc.update(
                "INSERT INTO jobs (id, agent, project_id, started_at)" + " VALUES (?, ?, ?, ?)",
                // Registered before the row that references it, and
                // inside this supplier so a database that is down is
                // reported as this operation rather than as a project
                // lookup -- ConversationStore.write's arrangement and
                // its reason.
                id,
                agent,
                ProjectIds.toWrite(jdbc, home),
                utc(at)));
  }

  /**
   * Write down how it ended and what it did.
   *
   * <p><b>Not conditional on the row still being there.</b> A prune between the two writes would
   * leave nothing to update, and this answers false rather than raising: the run really did finish,
   * and a sweep that removed its record is a deployment's own policy taking effect rather than a
   * fault for the run to report.
   *
   * @param ending which of {@link Outcome.Ending}'s eight
   * @param steps turns taken, as the run's own loop counted
   * @param modelCalls calls made. Zero is ordinary — a run whose runtime failed before it could
   *     count anything files two zeroes, and {@code JobStore} calls those the honest numbers
   * @param at when the outcome was filed. Never before {@code started_at}, which the schema also
   *     refuses
   * @return whether a row was updated
   */
  public boolean ended(String id, Outcome.Ending ending, int steps, int modelCalls, Instant at) {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(ending, "ending");
    Objects.requireNonNull(at, "at");
    return ArchiveUnavailableException.translating(
            "write down how a job ended",
            () ->
                jdbc.update(
                    "UPDATE jobs SET ended_at = ?, ending = ?, steps = ?, model_calls = ?"
                        + " WHERE id = ?",
                    utc(at),
                    ending.name(),
                    steps,
                    modelCalls,
                    id))
        == 1;
  }

  /**
   * The job under {@code id}, if this database still holds it.
   *
   * <p>{@link Optional} and not a refusal, the opposite of {@code JobStore.get}'s choice and for a
   * reason that is this table's whole point: a job id outlives the process that minted it now, so
   * "there is no record of that job" is an ordinary answer — the sweep pruned it, or it was never
   * written because the database was down when the run started.
   */
  public Optional<JobRecord> find(String id) {
    Objects.requireNonNull(id, "id");
    List<JobRecord> found =
        ArchiveUnavailableException.translating(
            "read a job by id",
            () -> jdbc.query("SELECT " + COLUMNS + FROM_JOBS + " WHERE j.id = ?", ROW_MAPPER, id));
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  /**
   * Every job this database holds, in the order they were started.
   *
   * <p>{@code ORDER BY id} and not {@code ORDER BY started_at}, which is the return on minting the
   * way this repository mints shown ids: the two agree by construction, and the primary key answers
   * it without a second index.
   */
  public List<JobRecord> all() {
    return ArchiveUnavailableException.translating(
        "list the jobs",
        () -> jdbc.query("SELECT " + COLUMNS + FROM_JOBS + " ORDER BY j.id", ROW_MAPPER));
  }

  /**
   * Delete every job submitted before {@code cutoff}, and say how many.
   *
   * <h2>A delete, where the conversation archive refuses one</h2>
   *
   * <p>{@code V19__conversation_lifecycle.sql} argues that a conversation is demoted and its
   * payload ejected and that <b>nothing deletes the row</b>, because a trajectory with holes in it
   * is worse than a trajectory that is large. That argument is about a record of what was said. A
   * job row holds no text at all — an identifier, a name, a project, two instants and three counts
   * — so there is nothing to eject, no staged path worth having, and no lifecycle to move it
   * through. It is deleted, which is what "pruned by policy" means, and {@code V24__jobs.sql} sets
   * out why borrowing the archive's vocabulary here would be the mistake rather than the tidy
   * choice.
   *
   * <p><b>{@code started_at} and not {@code ended_at}</b>: a run lost to a restart has no {@code
   * ended_at}, so a policy written on that column would keep every lost run for ever — the opposite
   * of what an operator enabling a prune is asking for.
   *
   * <p><b>Safe to call twice</b>, which is what lets it live inside a sweep: a second call finds
   * nothing older than the cutoff still there and reports nothing done.
   */
  public int pruneStartedBefore(Instant cutoff) {
    Objects.requireNonNull(cutoff, "cutoff");
    return ArchiveUnavailableException.translating(
        "prune old job records",
        () -> jdbc.update("DELETE FROM jobs WHERE started_at < ?", utc(cutoff)));
  }

  private static final RowMapper<JobRecord> ROW_MAPPER =
      (rs, rowNum) -> {
        String project = rs.getString("project");
        Instant ended = instant(rs, "ended_at");
        String ending = rs.getString("ending");
        return new JobRecord(
            rs.getString("id"),
            rs.getString("agent"),
            project == null ? Home.global() : Home.of(project),
            instant(rs, "started_at"),
            ended,
            ending == null ? null : Outcome.Ending.valueOf(ending),
            box(rs, "steps"),
            box(rs, "model_calls"));
      };

  private static OffsetDateTime utc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
    return at == null ? null : at.toInstant();
  }

  /**
   * A count that is absent rather than nought, which {@code getInt} cannot say: see {@link
   * JobRecord}, where zero is an ordinary count.
   */
  private static Integer box(ResultSet rs, String column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? null : value;
  }
}
