package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A job is a row that outlives the process that ran it, and ages out.
 *
 * <h2>What this file exists to fail on</h2>
 *
 * <ul>
 *   <li><b>two runs on two different days do not share an id.</b> That is the whole defect {@code
 *       implementation rationale} §2 records — an in-process counter restarting at {@code
 *       job_000001} every boot — and {@link #two_runs_a_restart_apart_do_not_share_an_id} is what
 *       fails if anything ever puts a counter back;
 *   <li><b>the ordering is real.</b> {@code ORDER BY id} has to mean "in the order runs were
 *       started" or the whole reason for minting the way this repository mints shown ids is gone.
 *       Asserted against a listing rather than by inspecting the hex;
 *   <li><b>the four ending columns move together.</b> A row that says it ended and does not say how
 *       is a record that reads as a finished run with no result, and the schema is what refuses it
 *       — asserted through raw SQL, because the point is that a writer bypassing this class cannot
 *       do it either;
 *   <li><b>pruning is a delete and it is safe to call twice</b>, which is what lets it live inside
 *       {@code POST /v1/retention/sweep}.
 * </ul>
 *
 * <h2>The clock is chosen</h2>
 *
 * <p>Every instant here is named, for {@code RetentionTest}'s reason: an age policy tested against
 * {@code Instant.now()} asserts nothing on the day it is written and something different a year
 * later.
 */
@Tag("full-db")
@Testcontainers
class JobLogTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
  private static final Home PAYMENTS = Home.of("payments");

  /**
   * After {@link #NOW} rather than {@code now()}: the fixtures are dated in 2026, so a real clock
   * is BEFORE them and {@code jobs_did_not_end_before_it_started} fires first -- which would make
   * the two refusal tests below pass for the wrong constraint.
   */
  private static final OffsetDateTime ENDED = NOW.plusSeconds(60).atOffset(ZoneOffset.UTC);

  private static JdbcTemplate jdbc;

  private JobLog jobs;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(dataSource()).load().migrate();
    jdbc = new JdbcTemplate(dataSource());
  }

  private static DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @BeforeEach
  void fresh() {
    // Named rather than CASCADE, on ConversationStoreTest's reasoning: a
    // fixture that empties a table nobody in this file knows about hides its
    // own blast radius. `projects` is here because a job names one and this
    // file writes them by writing jobs.
    jdbc.execute(
        "TRUNCATE TABLE digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, jobs, projects CASCADE");
    jobs = new JobLog(jdbc);
  }

  // --- the id ------------------------------------------------------------------

  /**
   * <b>The defect, as a test.</b> {@code JobStore} minted {@code "job_" + String.format("%06d",
   * ids.incrementAndGet())} from an {@code AtomicLong} that starts at zero every boot, so the first
   * run of today and the first run of last Tuesday were both {@code job_000001}. A probe script
   * broke on exactly that.
   *
   * <p>Written as two ids minted a day apart because that is the situation that used to collide,
   * and because it also asserts the property that makes the collision impossible rather than merely
   * unlikely: the ten hex digits are milliseconds from a fixed epoch, so two different days cannot
   * produce the same prefix however many times the process restarts in between.
   */
  @Test
  void two_runs_a_restart_apart_do_not_share_an_id() {
    String tuesday = JobLog.newId(NOW.minus(Duration.ofDays(7)));
    String today = JobLog.newId(NOW);

    assertNotEquals(tuesday, today, "an id that restarts with the process is the defect");
    assertTrue(
        tuesday.compareTo(today) < 0,
        "and the older one sorts first, which is what ORDER BY id means here — "
            + tuesday
            + " vs "
            + today);
    assertTrue(today.startsWith(JobLog.PREFIX), today);
  }

  /**
   * The ordering asserted where it is actually used: a listing, out of the database, ordered by the
   * primary key alone.
   *
   * <p>Written back to front on purpose — the newest row is inserted first — so a listing that came
   * back in insertion order would fail.
   */
  @Test
  void a_listing_ordered_by_id_is_a_listing_in_the_order_runs_were_started() {
    Instant first = NOW.minus(Duration.ofHours(2));
    Instant second = NOW.minus(Duration.ofHours(1));
    String newest = JobLog.newId(NOW);
    jobs.started(newest, "scribe", Home.global(), NOW);
    String middle = JobLog.newId(second);
    jobs.started(middle, "scribe", Home.global(), second);
    String oldest = JobLog.newId(first);
    jobs.started(oldest, "scribe", Home.global(), first);

    assertEquals(List.of(oldest, middle, newest), jobs.all().stream().map(JobRecord::id).toList());
  }

  // --- the row ------------------------------------------------------------------

  /**
   * What a submission writes down, and what it deliberately does not: a run that has not ended says
   * nothing about how it ended.
   */
  @Test
  void a_job_that_started_says_when_where_and_under_what_name() {
    String id = JobLog.newId(NOW);

    jobs.started(id, "promotion_judge", PAYMENTS, NOW);

    JobRecord row = jobs.find(id).orElseThrow();
    assertEquals("promotion_judge", row.agent());
    assertEquals(PAYMENTS, row.home());
    assertEquals(NOW, row.startedAt());
    assertFalse(row.ended(), "nothing has filed an outcome for it");
    assertNull(row.ending());
    assertNull(row.steps());
    assertNull(row.modelCalls());
  }

  /**
   * The global tier is the absence of a project and reads back as one, which is V6's rule for
   * {@code conversations} applied to the same column here.
   */
  @Test
  void a_run_that_named_no_project_is_global_and_not_a_project_called_global() {
    String id = JobLog.newId(NOW);

    jobs.started(id, "scribe", Home.global(), NOW);

    assertTrue(jobs.find(id).orElseThrow().home().isGlobal());
    assertNull(
        jdbc.queryForObject("SELECT project_id FROM jobs WHERE id = ?", Long.class, id),
        "global is a NULL project and not a row in `projects`");
  }

  /**
   * The project is a REFERENCE and not a name in the row, which is what makes a rename survivable —
   * {@code V14}'s surrogate key, and the reason `implementation rationale` §2 refuses to put the
   * project in the id.
   */
  @Test
  void the_project_is_a_reference_so_a_rename_does_not_orphan_the_record() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "promotion_judge", PAYMENTS, NOW);

    jdbc.update("UPDATE projects SET name = 'billing' WHERE name = 'payments'");

    assertEquals(
        Home.of("billing"),
        jobs.find(id).orElseThrow().home(),
        "the row points at the project, so it follows the project's new name");
  }

  /** How it ended, what it did, and the fact that the four absences fill in together. */
  @Test
  void an_outcome_filed_afterwards_lands_on_the_row() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "document_summariser", Home.global(), NOW);

    assertTrue(jobs.ended(id, Ending.CALL_BUDGET, 220, 220, NOW.plusSeconds(1560)));

    JobRecord row = jobs.find(id).orElseThrow();
    assertTrue(row.ended());
    assertEquals(Ending.CALL_BUDGET, row.ending());
    assertEquals(220, row.steps());
    assertEquals(220, row.modelCalls());
    assertEquals(NOW.plusSeconds(1560), row.endedAt());
  }

  /** CALL_FAILURES is an ending the jobs table holds (V56). */
  @Test
  void a_job_that_kept_writing_calls_as_text_is_a_row_this_table_holds() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "interlocutor", Home.global(), NOW);

    assertTrue(jobs.ended(id, Ending.CALL_FAILURES, 4, 4, NOW.plusSeconds(60)));

    assertEquals(Ending.CALL_FAILURES, jobs.find(id).orElseThrow().ending());
  }

  /**
   * {@code AWAITING} is one of {@code jobs_ending_is_known}'s names since V48, for a conductor's
   * turn that ended waiting on a person rather than answering — and it moves the same four columns
   * together as any other ending.
   */
  @Test
  void an_awaiting_ending_lands_on_the_row_like_any_other() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "code_implementation", PAYMENTS, NOW);

    assertTrue(jobs.ended(id, Ending.AWAITING, 4, 4, NOW.plusSeconds(30)));

    JobRecord row = jobs.find(id).orElseThrow();
    assertTrue(row.ended());
    assertEquals(Ending.AWAITING, row.ending());
    assertEquals(4, row.steps());
    assertEquals(4, row.modelCalls());
    assertEquals(NOW.plusSeconds(30), row.endedAt());
  }

  /**
   * Zero is a count and not an absence, which is why the record's fields are boxed. {@code
   * JobStore}'s last-resort clause files an {@code UNAVAILABLE} with two zeroes for a run whose
   * runtime failed before it could do anything, and calls those the honest numbers.
   */
  @Test
  void a_run_that_did_nothing_records_two_zeroes_and_not_two_absences() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "echo", Home.global(), NOW);

    jobs.ended(id, Ending.UNAVAILABLE, 0, 0, NOW);

    JobRecord row = jobs.find(id).orElseThrow();
    assertTrue(row.ended(), "it ended, and it ended having done nothing");
    assertEquals(0, row.steps());
    assertEquals(0, row.modelCalls());
  }

  /**
   * An outcome for a job whose record has already been pruned is not a fault for the run to report.
   * Answered rather than raised: the run really did finish, and a sweep that removed its record is
   * a policy taking effect.
   */
  @Test
  void filing_an_outcome_for_a_row_that_is_gone_says_so_rather_than_raising() {
    assertFalse(jobs.ended(JobLog.newId(NOW), Ending.ANSWERED, 1, 1, NOW));
  }

  /**
   * And a job nobody wrote down is an absence rather than a refusal — the opposite of {@code
   * JobStore.get}, because an id outlives its process now.
   */
  @Test
  void a_job_this_database_never_held_is_an_empty_optional() {
    assertEquals(Optional.empty(), jobs.find("job_NOTHINGHERE"));
  }

  // --- what the schema refuses ------------------------------------------------

  /**
   * The four ending columns move together, held where a writer bypassing this class still meets it.
   *
   * <p>Raw SQL on purpose. A test that went through {@link JobLog} would prove that this class
   * writes all four, which is not the claim: the claim is that a row saying it finished without
   * saying how cannot exist.
   */
  @Test
  void a_row_that_ended_without_saying_how_is_refused() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "echo", Home.global(), NOW);

    // Everything BUT the ending, so exactly one of the three constraints
    // can fire and the assertion names the one it is about. A row with all
    // four missing would violate all three, and Postgres reports whichever
    // it reaches first -- an assertion that passes for the wrong reason.
    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE jobs SET ended_at = ?, steps = 1, model_calls = 1" + " WHERE id = ?",
                    ENDED,
                    id));

    assertTrue(
        refused.getMessage().contains("jobs_an_ended_run_says_how)")
            || refused.getMessage().contains("jobs_an_ended_run_says_how\""),
        refused.getMessage());
  }

  /**
   * And an ending this server cannot read back is refused at the door, so a row is never written
   * successfully and unreadable for ever — {@code turns_ending_is_known}'s argument, one table
   * over.
   */
  @Test
  void an_ending_this_server_does_not_have_a_name_for_is_refused() {
    String id = JobLog.newId(NOW);
    jobs.started(id, "echo", Home.global(), NOW);

    DataIntegrityViolationException refused =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                jdbc.update(
                    "UPDATE jobs SET ended_at = ?, ending = 'GAVE_UP',"
                        + " steps = 1, model_calls = 1 WHERE id = ?",
                    ENDED,
                    id));

    assertTrue(refused.getMessage().contains("jobs_ending_is_known"), refused.getMessage());
  }

  // --- pruning ------------------------------------------------------------------

  /**
   * Older than the cutoff goes; the rest stays; and running it again finds nothing to do.
   *
   * <p><b>Safe to call twice is asserted rather than asserted about</b>, because it is the property
   * that makes an explicit trigger workable at all — an operator who is not sure whether last
   * night's sweep ran can simply run it.
   */
  @Test
  void pruning_removes_what_is_older_than_the_cutoff_and_is_safe_to_call_twice() {
    String old = JobLog.newId(NOW.minus(Duration.ofDays(30)));
    jobs.started(old, "scribe", Home.global(), NOW.minus(Duration.ofDays(30)));
    String recent = JobLog.newId(NOW.minus(Duration.ofDays(2)));
    jobs.started(recent, "scribe", Home.global(), NOW.minus(Duration.ofDays(2)));

    assertEquals(1, jobs.pruneStartedBefore(NOW.minus(Duration.ofDays(7))));
    assertEquals(
        0,
        jobs.pruneStartedBefore(NOW.minus(Duration.ofDays(7))),
        "a second call against the same state finds nothing and reports nothing");
    assertEquals(Optional.empty(), jobs.find(old));
    assertTrue(jobs.find(recent).isPresent(), "and what is inside the age is untouched");
  }

  /**
   * A run lost to a restart is pruned like any other, and that is why the policy is written on
   * {@code started_at}.
   *
   * <p>A row with no {@code ended_at} has nothing on the other column to be older than, so a prune
   * keyed on the ending would keep every lost run for ever — the opposite of what an operator
   * enabling a prune is asking for.
   */
  @Test
  void a_run_that_never_filed_an_outcome_is_pruned_by_when_it_started() {
    String lost = JobLog.newId(NOW.minus(Duration.ofDays(30)));
    jobs.started(lost, "document_summariser", Home.global(), NOW.minus(Duration.ofDays(30)));

    assertEquals(1, jobs.pruneStartedBefore(NOW.minus(Duration.ofDays(7))));
  }
}
