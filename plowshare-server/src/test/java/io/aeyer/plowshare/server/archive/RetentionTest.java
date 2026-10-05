package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.LoggedEntry;
import io.aeyer.plowshare.server.agents.Speaker;
import io.aeyer.plowshare.server.data.DataLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demote the record, eject the payload — and never in one step.
 *
 * <h2>The four things worth failing on</h2>
 *
 * <ul>
 *   <li><b>the row survives and only the content goes.</b> A sweep that deleted rows would pass
 *       every assertion about disk and leave a trajectory with holes in it, which is the outcome
 *       the whole design is shaped against. Every test here asserts on what is still there as well
 *       as on what is not;
 *   <li><b>a sweep does not eject what it has just marked.</b> The staged path is only recoverable
 *       if there is a window, and a window inside one method call is not one. {@code
 *       a_sweep_does_not_eject_what_it_has_just_marked} is what fails on a single-pass
 *       implementation, and every other test here that ejects anything has to sweep twice — which
 *       is itself the proof;
 *   <li><b>the tree goes with the root.</b> A delegated child's tool results are the same file
 *       bodies its parent's are, so a fixture with an unswept child would pass a root-only sweep;
 *   <li><b>the export is readable without Plowshare.</b> Asserted by reading the files back with
 *       {@link Files} and nothing else — no exporter, no parser from this codebase — because a test
 *       that used this server's own reader would prove exactly the property that does not matter.
 * </ul>
 *
 * <h2>The clock is chosen</h2>
 *
 * <p>Both the store's and the sweep's, for {@code ConversationStoreTest}'s reason: an age policy
 * tested against {@code Instant.now()} is a test that asserts nothing on the day it is written and
 * something different a year later.
 */
@Tag("full-db")
@Testcontainers
class RetentionTest {

  /**
   * The pgvector image, not stock postgres:16: V1's first line is CREATE EXTENSION vector, and this
   * class runs the whole migration chain.
   */
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Home PAYMENTS = Home.of("payments");
  private static final Instant NOW = Instant.parse("2026-09-04T09:00:00Z");
  private static final String A_FILE = "the whole of a file somebody read";

  private static JdbcTemplate jdbc;

  private final AtomicReference<Instant> clock = new AtomicReference<>(NOW);

  private ConversationStore conversations;
  private EntryStore entries;
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
    // own blast radius. board_topics, board_messages and board_seats: V74
    // gave each a foreign key into conversations. firings: a further hop
    // out, through its V74 foreign key into board_topics. user_inbox: a hop
    // past that, through its pre-existing V40 foreign key into firings.
    jdbc.execute(
        "TRUNCATE TABLE board_message_route_bindings, outgoing_work, message_external_tasks, message_external_contexts, board_message_routes, board_message_instances, board_post_receipts, skill_executions, command_invocations, orchestration_start_receipts, orchestration_script_steps, board_reads, board_notices, board_decisions, digests, digest_children, digest_memories, digest_spans, digest_revisions, memory_provenance, conversations, turns, compactions, entries, jobs, citations, orchestrations, orchestration_messages, board_topics, board_messages, board_seats, firings, user_inbox");
    clock.set(NOW);
    conversations = new ConversationStore(jdbc, clock::get, null);
    entries = new EntryStore(jdbc, clock::get);
    jobs = new JdbcJobLog(jdbc);
  }

  // --- the staged path -------------------------------------------------------

  /**
   * The window is the point. A sweep ejects what an <em>earlier</em> one marked, so a conversation
   * the policy selects tonight keeps every payload until the next sweep — which is the time in
   * which somebody can say no.
   */
  @Test
  void a_sweep_does_not_eject_what_it_has_just_marked(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));

    Retention.SweepReport first = sweeping(exports, Duration.ofDays(7)).sweep();

    assertEquals(1, first.marked());
    assertEquals(0, first.payloads(), "a marked conversation still holds every byte");
    assertEquals(
        ConversationLifecycle.TO_BE_EJECTED,
        conversations.find(old.id()).orElseThrow().lifecycle());
    assertEquals(A_FILE, contentOf(old.id(), 1));
  }

  /**
   * And cancelling in that window really keeps the payload: the next sweep finds an active
   * conversation and leaves it alone.
   */
  @Test
  void a_mark_cancelled_before_the_next_sweep_keeps_the_payload(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();

    conversations.moveTo(old.id(), ConversationLifecycle.ACTIVE);
    Retention.SweepReport second = retention.sweep();

    assertEquals(0, second.payloads(), "cancelling a mark did not save the payload");
    assertEquals(A_FILE, contentOf(old.id(), 1));
  }

  // --- what ejection does and does not take ----------------------------------

  /** The whole of the design in one assertion pair: the content is gone and the row is not. */
  @Test
  void ejection_takes_the_content_and_leaves_the_record(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    EntryRecord stored = entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();

    Retention.SweepReport report = retention.sweep();

    assertEquals(1, report.payloads());
    assertEquals(A_FILE.length(), report.characters());
    EntryRecord row =
        entries
            .redeem(old.id(), stored.handle())
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the row went with the payload, so every handle in this"
                            + " conversation now dangles"));
    assertNull(row.content());
    assertEquals(NOW, row.ejectedAt());
    assertNotNull(row.export());
    assertEquals(stored.ordinal(), row.ordinal(), "the trajectory has a gap in its ordinals");
    assertEquals(stored.toolCallId(), row.toolCallId());
    assertEquals(stored.handle(), row.handle());
    assertEquals(
        ConversationLifecycle.EJECTED, conversations.find(old.id()).orElseThrow().lifecycle());
  }

  /**
   * The size is one of the three facts a stored-result line carries, and {@code length(content)}
   * answers NULL once the content does.
   */
  @Test
  void an_ejected_result_keeps_the_size_it_had(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(
        old.id(), 1, LoggedEntry.answer("", List.of(new ToolCall("c1", "file_read", "{}"))));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    // A fold, so the result is behind a seam and result_list is what names
    // it. The summary is appended at turn 1's reach and supersedes the two
    // entries before it.
    entries.append(old.id(), 1, LoggedEntry.summary("what happened earlier"));
    entries.supersede(old.id(), 0, 1, 3);
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    StoredResults listed = entries.storedResultsBehindASeam(old.id(), 0, 10);

    assertEquals(1, listed.total());
    assertEquals(
        A_FILE.length(),
        listed.listed().get(0).size(),
        "an ejected result listed with no size says less about itself than the row"
            + " beside it, in the listing whose job is to let a model choose");
    assertEquals(
        NOW,
        listed.listed().get(0).ejectedAt(),
        "and it is listed AS ejected, or a model cannot tell 'was never here' from"
            + " 'was here and went'");
  }

  /**
   * Only a tool result. Everything else in the log is what a person said, what a model answered or
   * what the harness noted — the record rather than the liability.
   */
  @Test
  void an_utterance_and_an_answer_are_not_touched(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(
        old.id(), 1, LoggedEntry.utterance("what does the file say", Speaker.person(null)));
    entries.append(old.id(), 1, LoggedEntry.answer("it says this", List.of()));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    assertEquals("what does the file say", contentOf(old.id(), 1));
    assertEquals("it says this", contentOf(old.id(), 2));
    assertNull(contentOf(old.id(), 3));
  }

  // --- the tree --------------------------------------------------------------

  /**
   * A delegated child's payloads go with its root's, and the child is never selected on its own.
   *
   * <p>This is the whole return on putting the lifecycle on the root: nothing marks the child,
   * nothing ages it, and it is ejected because the tree was.
   */
  @Test
  void a_delegated_child_is_ejected_with_the_root_it_follows(@TempDir Path exports) {
    ConversationRecord root = aged(Origin.CURATOR, Duration.ofDays(30));
    ConversationRecord child =
        conversations.log(Origin.DELEGATION, PAYMENTS, "helper", root.id(), null);
    ConversationRecord grandchild =
        conversations.log(Origin.DELEGATION, PAYMENTS, "deeper", child.id(), null);
    entries.append(root.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    entries.append(child.id(), 1, LoggedEntry.toolResult("c2", "what the child read"));
    entries.append(grandchild.id(), 1, LoggedEntry.toolResult("c3", "what it read"));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    assertNull(contentOf(root.id(), 1));
    assertNull(contentOf(child.id(), 1), "a delegated child kept the larger half");
    assertNull(contentOf(grandchild.id(), 1));
    assertNull(
        conversations.find(child.id()).orElseThrow().lifecycle(),
        "and it is still the root's state rather than a copy written onto the child");
  }

  // --- policy by origin ------------------------------------------------------

  /**
   * A person's conversation is never marked automatically, however old it is and whatever a sweep
   * is asked to do.
   */
  @Test
  void a_persons_conversation_is_never_marked_by_a_sweep(@TempDir Path exports) {
    clock.set(NOW.minus(Duration.ofDays(400)));
    ConversationRecord mine = conversations.open(PAYMENTS, Budget.of(4));
    clock.set(NOW);
    entries.append(mine.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(1));

    retention.sweep();
    retention.sweep();

    assertEquals(
        ConversationLifecycle.ACTIVE, conversations.find(mine.id()).orElseThrow().lifecycle());
    assertEquals(A_FILE, contentOf(mine.id(), 1));
  }

  /**
   * But a person who marks their own conversation gets it ejected, which is what "the person
   * chooses; export is explicit" means in practice.
   */
  @Test
  void a_persons_conversation_marked_by_hand_is_ejected(@TempDir Path exports) {
    ConversationRecord mine = conversations.open(PAYMENTS, Budget.of(4));
    entries.append(mine.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    conversations.moveTo(mine.id(), ConversationLifecycle.ARCHIVED);
    conversations.moveTo(mine.id(), ConversationLifecycle.TO_BE_EJECTED);

    sweeping(exports, Duration.ofDays(7)).sweep();

    assertNull(contentOf(mine.id(), 1));
  }

  /**
   * An unconfigured server ejects nothing however often a sweep is run: a default that deletes is a
   * default that has to be right, and there is no arithmetic for this one.
   */
  @Test
  void a_server_with_no_retention_age_marks_nothing(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(400));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention =
        new Retention(
            conversations, entries, jobs, into(exports), RetentionPolicy.NOTHING, clock::get);

    retention.sweep();
    retention.sweep();

    assertEquals(
        ConversationLifecycle.ACTIVE, conversations.find(old.id()).orElseThrow().lifecycle());
    assertEquals(A_FILE, contentOf(old.id(), 1));
  }

  /**
   * A conversation younger than the age is left alone, which is the half of the policy a test that
   * only swept old rows would not see.
   */
  @Test
  void a_conversation_younger_than_the_age_is_not_marked(@TempDir Path exports) {
    ConversationRecord recent = aged(Origin.CURATOR, Duration.ofDays(2));
    entries.append(recent.id(), 1, LoggedEntry.toolResult("c1", A_FILE));

    sweeping(exports, Duration.ofDays(7)).sweep();

    assertEquals(
        ConversationLifecycle.ACTIVE, conversations.find(recent.id()).orElseThrow().lifecycle());
  }

  // --- the export ------------------------------------------------------------

  /**
   * Read back with {@link Files} and nothing else.
   *
   * <p><b>Deliberately not through anything in this codebase.</b> An export nobody can open without
   * the system that wrote it is not an export, and a test that proved the round trip through this
   * server's own reader would prove exactly the property that does not matter.
   */
  @Test
  void the_export_is_a_plain_file_and_a_manifest_anything_can_read(@TempDir Path exports)
      throws IOException {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    EntryRecord stored = entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    Path tree = exports.resolve(old.id());
    Path payload = tree.resolve("payloads").resolve(old.id()).resolve("0001.txt");
    assertEquals(
        A_FILE,
        Files.readString(payload, StandardCharsets.UTF_8),
        "the payload file holds exactly the bytes the tool returned and nothing wrapped"
            + " around them");

    List<String> manifest = Files.readAllLines(tree.resolve("manifest.jsonl"));
    assertEquals(1, manifest.size(), "JSON Lines is one object per line");
    String line = manifest.get(0);
    assertTrue(line.startsWith("{") && line.endsWith("}"), line);
    assertTrue(line.contains("\"conversation\":\"" + old.id() + "\""), line);
    assertTrue(line.contains("\"origin\":\"curator\""), line);
    assertTrue(line.contains("\"handle\":\"" + stored.handle() + "\""), line);
    assertTrue(line.contains("\"chars\":" + A_FILE.length()), line);
    assertTrue(
        line.contains("\"file\":\"payloads/" + old.id() + "/0001.txt\""),
        "the manifest names the file relative to the tree, so the two travel together: " + line);

    assertEquals(
        payload.toString(),
        entries.redeem(old.id(), stored.handle()).orElseThrow().export(),
        "and the row says where the bytes actually went");
  }

  /**
   * A deployment that keeps no export ejects anyway, and the row says there is none rather than
   * naming an empty place.
   */
  @Test
  void a_deployment_that_keeps_no_export_ejects_with_nowhere_named() {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    EntryRecord stored = entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention =
        new Retention(
            conversations,
            entries,
            jobs,
            PayloadExport.NONE,
            new RetentionPolicy(Duration.ofDays(7), null, null),
            clock::get);
    retention.sweep();
    retention.sweep();

    EntryRecord row = entries.redeem(old.id(), stored.handle()).orElseThrow();
    assertNull(row.content());
    assertNotNull(row.ejectedAt());
    assertNull(row.export());
  }

  /**
   * An export that cannot be written stops the sweep with the payload still in the database.
   *
   * <p><b>The order is what is being tested.</b> An exporter that reported a success it did not
   * have would turn a full disk into deleted file bodies, so the row is nulled only after the bytes
   * are on disk — and the way to drive that is to make the disk refuse.
   */
  @Test
  void a_payload_that_could_not_be_exported_is_not_taken_out_of_the_row(@TempDir Path exports)
      throws IOException {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    // A file where the tree's directory has to go, so createDirectories
    // fails. Not a permission bit, which the process may be root for.
    Files.writeString(exports.resolve(old.id()), "in the way");

    assertThrows(UncheckedIOException.class, retention::sweep);

    assertEquals(
        A_FILE,
        contentOf(old.id(), 1),
        "the payload was taken out of the row before it was safely on disk");
    assertEquals(
        ConversationLifecycle.TO_BE_EJECTED,
        conversations.find(old.id()).orElseThrow().lifecycle(),
        "and the conversation is still marked, so the next sweep finishes the job");
  }

  // --- where an export lands, which is the data directory's question ---------

  /**
   * The production binding: an export goes under its <em>project's own id</em> in the data
   * directory.
   *
   * <p>Built here, from {@code DataLayout} and {@code ProjectIds}, exactly as {@code
   * ArchiveConfig.payloadExport} builds it — a lambda of this shape in a test and a different one
   * in the wiring would be two schemes, and the one with the test would not be the one that runs.
   *
   * <p><b>The id is read out of the table rather than assumed to be 1.</b> This class does not
   * truncate {@code projects}, so the row {@code payments} gets is whatever the identity had
   * reached; a fixture that hardcoded a number would pass on a fresh container and fail on the
   * second test to run.
   */
  @Test
  void an_export_lands_under_its_projects_own_id(@TempDir Path data) throws IOException {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    DataLayout layout = new DataLayout(data).initialise();
    Retention retention =
        new Retention(
            conversations,
            entries,
            jobs,
            asWired(layout),
            new RetentionPolicy(Duration.ofDays(7), null, null),
            clock::get);

    retention.sweep();
    retention.sweep();

    long id = idOf("payments");
    Path payload =
        data.resolve("projects")
            .resolve(Long.toString(id))
            .resolve("exports")
            .resolve(old.id())
            .resolve("payloads")
            .resolve(old.id())
            .resolve("0001.txt");
    assertEquals(
        A_FILE,
        Files.readString(payload, StandardCharsets.UTF_8),
        "the tree hangs off the project's id, so `what is in here, and whose is"
            + " it` is a directory listing rather than a scan of every manifest");
  }

  /**
   * And a rename does not move it, which is the whole reason the directory is named for the id.
   *
   * <p><b>This is the decision the layout's javadoc argues hardest for, and it is the one a test
   * can break.</b> A project's name is what a person types and is changeable; its id is not. A tree
   * named for the name would be orphaned by a rename with <em>nothing failing</em> — the next
   * export would simply appear somewhere else, and the earlier ones would sit under a name no
   * project has any more, findable only by whoever thought to look.
   *
   * <p>The rename is a direct {@code UPDATE} rather than {@code ProjectStore.rename} on purpose:
   * bringing a store in here would mean a seven-path constructor in a file that is about retention,
   * and what this test needs from a rename is the one column. {@code ConversationRecord.home} is
   * read back through the join on {@code projects}, so after this the conversation genuinely
   * reports the new name — which is what makes the assertion mean something.
   */
  @Test
  void a_rename_does_not_move_a_projects_exports(@TempDir Path data) throws IOException {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    DataLayout layout = new DataLayout(data).initialise();
    long id = idOf("payments");
    jdbc.update("UPDATE projects SET name = ? WHERE name = ?", "receivables", "payments");
    assertEquals(
        "receivables",
        conversations.find(old.id()).orElseThrow().home().project(),
        "the premise: the conversation reports the new name, since home is a join");

    Retention retention =
        new Retention(
            conversations,
            entries,
            jobs,
            asWired(layout),
            new RetentionPolicy(Duration.ofDays(7), null, null),
            clock::get);
    retention.sweep();
    retention.sweep();

    assertTrue(
        Files.exists(data.resolve("projects").resolve(Long.toString(id))),
        "the id is what names the directory");
    assertFalse(
        Files.exists(data.resolve("projects").resolve("receivables")),
        "and never the name, which a rename would have orphaned with nothing"
            + " failing anywhere");
    // Put it back: `projects` is not truncated between tests in this class.
    jdbc.update("UPDATE projects SET name = ? WHERE name = ?", "payments", "receivables");
  }

  /**
   * The binding {@code ArchiveConfig.payloadExport} makes for a deployment that named no export
   * directory of its own -- the real one, not a lambda of the same shape: two copies of a directory
   * scheme is one copy with a test and one without. {@code ExportDirectories.under} says so on
   * itself.
   */
  private static PayloadExport asWired(DataLayout layout) {
    return new PayloadExport(ExportDirectories.under(layout, new JdbcProjectDirectories(jdbc)));
  }

  private static long idOf(String project) {
    return jdbc.queryForObject("SELECT id FROM projects WHERE name = ?", Long.class, project);
  }

  // --- running it twice ------------------------------------------------------

  /**
   * Safe to call twice, which is what makes an explicit trigger workable at all: an operator who is
   * not sure whether last night's run happened can simply run it.
   */
  @Test
  void a_second_sweep_over_the_same_state_does_nothing_and_says_so(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    entries.append(old.id(), 1, LoggedEntry.toolResult("c1", A_FILE));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    Retention.SweepReport third = retention.sweep();

    assertEquals(0, third.marked());
    assertEquals(0, third.payloads());
    assertEquals(0, third.conversations());
  }

  /**
   * An ejected conversation is terminal, so nothing brings the tree back and no later sweep
   * restamps the date on a payload that has already gone.
   */
  @Test
  void an_ejected_conversation_cannot_be_moved_anywhere(@TempDir Path exports) {
    ConversationRecord old = aged(Origin.CURATOR, Duration.ofDays(30));
    Retention retention = sweeping(exports, Duration.ofDays(7));
    retention.sweep();
    retention.sweep();

    assertThrows(
        ArchiveRefusedException.class,
        () -> conversations.moveTo(old.id(), ConversationLifecycle.ACTIVE));
    assertFalse(
        entries.ejectPayload(old.id(), 1, NOW, "somewhere else"),
        "a payload that is already gone is not ejected a second time");
  }

  // --- pruning the job records ------------------------------------------------

  /**
   * <b>A job is a row and the row ages out</b>, which is the second half of the owner's decision in
   * {@code implementation rationale} §2: "write it to a new table as a decision that gets pruned by
   * policy after a while".
   *
   * <p>It is here rather than in an operation of its own because every reason the sweep is an
   * explicit verb applies to a prune unchanged, and the sweep is already the thing an operator's
   * cron calls.
   */
  @Test
  void a_sweep_prunes_the_job_records_older_than_the_policy(@TempDir Path exports) {
    String old = aJobRun(Duration.ofDays(30));
    String recent = aJobRun(Duration.ofDays(2));

    Retention.SweepReport report = sweeping(exports, null, Duration.ofDays(7)).sweep();

    assertEquals(1, report.prunedJobs());
    assertTrue(jobs.find(old).isEmpty(), "a job older than the policy is gone");
    assertTrue(jobs.find(recent).isPresent(), "and one inside it is untouched");
  }

  /**
   * And it is safe to call twice, which is the property that lets a prune live inside an operation
   * an operator may run because they are not sure whether last night's did.
   */
  @Test
  void a_second_sweep_finds_nothing_left_to_prune(@TempDir Path exports) {
    aJobRun(Duration.ofDays(30));
    Retention retention = sweeping(exports, null, Duration.ofDays(7));
    retention.sweep();

    assertEquals(0, retention.sweep().prunedJobs());
  }

  /**
   * An unconfigured server prunes nothing however old the record and however often a sweep is run —
   * {@code RetentionPolicy}'s rule, which is that a default that deletes is a default that has to
   * be right and there is no arithmetic for this one.
   */
  @Test
  void a_server_with_no_job_age_prunes_nothing(@TempDir Path exports) {
    String ancient = aJobRun(Duration.ofDays(400));
    Retention retention = sweeping(exports, Duration.ofDays(7), null);

    retention.sweep();
    retention.sweep();

    assertEquals(0, retention.sweep().prunedJobs());
    assertTrue(jobs.find(ancient).isPresent());
  }

  /**
   * Pruning a job leaves the conversations that run wrote alone, and that separation is the point
   * of the two verbs.
   *
   * <p>A job record and a conversation are under different policies because they are different
   * things: one is an operational record of a run, the other is the transcript of what was said in
   * it. An operator who prunes job records aggressively has not thereby thrown away a trajectory.
   */
  @Test
  void pruning_a_job_record_does_not_touch_what_the_run_wrote(@TempDir Path exports) {
    aJobRun(Duration.ofDays(30));
    ConversationRecord wrote = aged(Origin.SUBMISSION, Duration.ofDays(30));
    entries.append(wrote.id(), 1, LoggedEntry.toolResult("c1", A_FILE));

    sweeping(exports, null, Duration.ofDays(7)).sweep();

    assertEquals(
        ConversationLifecycle.ACTIVE, conversations.find(wrote.id()).orElseThrow().lifecycle());
    assertEquals(
        A_FILE,
        contentOf(wrote.id(), 1),
        "a job policy is not a conversation policy and must not act as one");
  }

  /**
   * The report says what went, separately, because ejecting empties a column and pruning removes a
   * row and an operator cannot tell those apart from one number.
   */
  @Test
  void the_report_names_pruned_records_apart_from_ejected_payloads(@TempDir Path exports) {
    aJobRun(Duration.ofDays(30));

    String said = sweeping(exports, null, Duration.ofDays(7)).sweep().said();

    assertTrue(said.contains("pruned 1 job record"), said);
    assertTrue(said.contains("ejected 0 stored results"), said);
  }

  // --- fixtures --------------------------------------------------------------

  /**
   * A machine's log of the given age, written on a clock this file chose and then put back so that
   * the sweep runs "now".
   */
  private ConversationRecord aged(Origin origin, Duration old) {
    clock.set(NOW.minus(old));
    ConversationRecord written =
        conversations.log(
            origin, PAYMENTS, "judge", null, origin.ownsItsAllowance() ? Budget.of(4) : null);
    clock.set(NOW);
    return written;
  }

  private Retention sweeping(Path exports, Duration curatorAge) {
    return sweeping(exports, curatorAge, null);
  }

  private Retention sweeping(Path exports, Duration curatorAge, Duration jobAge) {
    return new Retention(
        conversations,
        entries,
        jobs,
        into(exports),
        new RetentionPolicy(curatorAge, null, jobAge),
        clock::get);
  }

  /**
   * An exporter that writes every tree straight into one directory, which is what every test above
   * the layout section asserts against.
   *
   * <p><b>Deliberately not the production binding.</b> Those tests are about {@code Retention} —
   * what is marked, what is ejected, what survives an interruption — and putting a project id in
   * the middle of every path would make each of them assert the directory scheme as well, in twenty
   * places, none of which is where the scheme is decided. The scheme has its own two tests below,
   * driven through the binding {@code ArchiveConfig} actually makes.
   */
  private static PayloadExport into(Path exports) {
    return new PayloadExport(home -> exports);
  }

  /**
   * A job record of the given age, written straight through the store on a clock this file chose —
   * the fixture `aged` is for conversations.
   */
  private String aJobRun(Duration old) {
    Instant at = NOW.minus(old);
    String id = JobLog.newId(at);
    jobs.started(id, "document_ingest", PAYMENTS, at);
    return id;
  }

  /**
   * Straight out of the column, so an assertion about a null is an assertion about the row rather
   * than about a mapper.
   */
  private static String contentOf(String conversation, int ordinal) {
    return jdbc.queryForObject(
        "SELECT content FROM entries WHERE conversation_id = ? AND ordinal = ?",
        String.class,
        conversation,
        ordinal);
  }
}
