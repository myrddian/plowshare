package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.Outcome;
import io.aeyer.plowshare.server.agents.RunActivity;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.Origin;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.orchestrations.OrchestrationStore.NewOrchestration;
import io.aeyer.plowshare.server.todos.StageRules;
import io.aeyer.plowshare.server.todos.StatusMoves;
import io.aeyer.plowshare.server.todos.TodoItem;
import io.aeyer.plowshare.server.todos.TodoStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The one writer, against a real Postgres: what each source's event reads as, where it lands, who
 * is told, and that nothing it does can fail the work it describes.
 */
@Tag("full-db")
@Testcontainers
class RecordKeeperTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-28T09:00:00Z");
  private static final List<StageRules.Stage> STAGES =
      List.of(new StageRules.Stage("goal", List.of()));

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;

  private final List<String> pushed = new ArrayList<>();
  private RecordStore records;
  private OrchestrationStore runs;
  private ConversationStore conversations;
  private RecordKeeper keeper;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    // The store's appends each take a transaction: one DataSource under the JdbcTemplate and
    // the transaction manager both, as OrchestrationsTest builds its UnitOfWork.
    TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return template.execute(status -> body.get());
          }
        };
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_record, orchestration_messages, orchestrations,"
            + " entries, turns, conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    pushed.clear();
    records = new RecordStore(jdbc, () -> T0, work);
    runs = new OrchestrationStore(jdbc, () -> T0);
    conversations = new ConversationStore(jdbc, () -> T0, null);
    AccountPushes told =
        (handle, body) -> {
          Map<?, ?> said =
              new com.fasterxml.jackson.databind.ObjectMapper().convertValue(body, Map.class);
          pushed.add(
              handle
                  + " "
                  + said.get("kind")
                  + " "
                  + said.get("root")
                  + " "
                  + said.get("through")
                  + (said.containsKey("settled") ? " settled " + said.get("settled") : ""));
        };
    keeper = new RecordKeeper(records, runs, () -> told);
  }

  private String conductor() {
    return conversations
        .log(Origin.ORCHESTRATION, Home.of("story"), "code_implementation", null, Budget.of(40))
        .id();
  }

  private String delegation(String under, String agent) {
    return conversations.log(Origin.DELEGATION, Home.of("story"), agent, under, null).id();
  }

  private OrchestrationRecord run(String parent, int depth, String handle) {
    return runs.insert(
        new NewOrchestration(
            "code_implementation",
            Tier.PROJECT,
            "sha256:x",
            "---\nname: code_implementation\n---\nbody",
            "project",
            STAGES,
            2,
            "story",
            conductor(),
            null,
            "code_implementation",
            handle,
            "s-laptop",
            parent,
            depth));
  }

  private OrchestrationRecord root() {
    return run(null, 0, "enzo");
  }

  /** Each row as {@code kind actor text | detail}. */
  private List<String> story(String root) {
    return records.pageAfter(root, 0, RecordKind.EVERY, 100).rows().stream()
        .map(
            row ->
                row.kind().wire()
                    + " "
                    + row.actor()
                    + " "
                    + row.text()
                    + (row.detail() == null ? "" : " | " + row.detail()))
        .toList();
  }

  private int rows() {
    return jdbc.queryForObject("SELECT count(*) FROM orchestration_record", Integer.class);
  }

  @Test
  void a_root_reads_as_run_lines_and_a_child_as_phase_lines_in_the_root_s_story() {
    OrchestrationRecord root = root();
    OrchestrationRecord child = run(root.id(), 1, "enzo");

    keeper.runStarted(root, "build the stat allocator\nwith caps", null);
    keeper.runStarted(child, "the parser", "02-parser");
    runs.finish(child.id(), "parsed\nand tested");
    keeper.runEnded(runs.find(child.id()).orElseThrow());
    runs.stop(root.id(), OrchestrationState.FAILED, "stuck");
    keeper.runEnded(runs.find(root.id()).orElseThrow());

    assertEquals(
        List.of(
            "run_started conductor code_implementation started: build the stat allocator",
            "phase_started conductor phase 02-parser started: "
                + child.id()
                + " (code_implementation) | 02-parser",
            "phase_ended conductor phase 02-parser ended finished: " + child.id() + " | parsed",
            "run_ended conductor code_implementation failed: stuck"),
        story(root.id()));
    assertEquals(List.of(), story(child.id()), "a child has no story of its own");
  }

  @Test
  void a_tool_line_is_written_as_the_call_starts_and_settled_with_its_word() {
    OrchestrationRecord root = root();
    String coder = delegation(root.conductorConversation(), "coder");

    RunActivity.Call call = keeper.called(coder, "coder", "run", () -> "./gradlew test");
    assertEquals(List.of("tool_call coder coder · run ./gradlew test"), story(root.id()));

    call.returned("exit 1");
    assertEquals(List.of("tool_call coder coder · run ./gradlew test | exit 1"), story(root.id()));
    assertEquals(
        List.of(
            "enzo orchestration.recorded " + root.id() + " 1",
            "enzo orchestration.recorded " + root.id() + " 1 settled 1"),
        pushed);
  }

  @Test
  void a_line_settled_far_above_the_end_is_named_by_its_push() {
    OrchestrationRecord root = root();
    String conductor = root.conductorConversation();
    RunActivity.Call delegation =
        keeper.called(conductor, "implement_specification", "agent_run", () -> "coder");
    String coder = delegation(conductor, "coder");
    for (String path : List.of("/repo/a", "/repo/b", "/repo/c")) {
      keeper.called(coder, "coder", "file_read", () -> path).returned("ok");
    }
    pushed.clear();

    delegation.returned("ok");

    assertEquals(
        List.of("enzo orchestration.recorded " + root.id() + " 4 settled 1"),
        pushed,
        "through is the end, and settled the line far above it that just changed");
  }

  /**
   * 1a (spec 2026-09-29): the facts footer, read back from the record the keeper itself wrote — not
   * the fixture {@code DelegationFactsTest} builds by hand. {@code delegationFacts}' {@code
   * conversation} is the delegate's <em>own</em> conversation, not the conductor's — see the next
   * test for why that is the whole fix, and not merely how it happens to be spelled.
   */
  @Test
  void delegation_facts_reads_what_the_record_holds_for_this_delegation() {
    OrchestrationRecord root = root();
    String conductor = root.conductorConversation();
    String coder = delegation(conductor, "coder");

    keeper.delegated(conductor, "code_implementation", "coder", "write it", coder);
    keeper.called(coder, "coder", "file_edit", () -> "rpg/main.py").returned("ok");
    keeper
        .called(coder, "coder", "run", () -> "python -m pytest -q")
        .returned("exit 1", "--- stdout ---\nFAILED test_move - KeyError: 'north'");

    String facts = keeper.delegationFacts(coder, "coder");

    assertTrue(
        facts.startsWith(
            "[harness] coder edited rpg/main.py; ran python -m pytest -q" + " → exit 1 ("),
        facts);
    // The end of the failed run's output, stored with its line and read back from it.
    assertTrue(
        facts.endsWith(
            ": its output ended:\n" + "--- stdout ---\nFAILED test_move - KeyError: 'north'"),
        facts);
    assertNull(keeper.delegationFacts("cnv_outside", "coder"));
  }

  /**
   * 1a, fixed after review: two delegations to the same agent can be live in one run at once — an
   * approval {@code AWAITING} inside the first's {@code agent_run} leaves the run {@code RUNNING},
   * so a conductor can dispatch a second to the same callee before the first returns. Told apart
   * only by the actor's name or by "the latest {@code delegated} line", the second's facts would
   * leak into the first's, or vice versa, depending on which line sorted later. Told apart by each
   * one's own child conversation, neither ever sees the other's tool lines, in whatever order they
   * are called.
   */
  @Test
  void two_live_delegations_to_the_same_agent_each_read_back_only_their_own_facts() {
    OrchestrationRecord root = root();
    String conductor = root.conductorConversation();
    String first = delegation(conductor, "coder");
    String second = delegation(conductor, "coder");

    keeper.delegated(conductor, "code_implementation", "coder", "first task", first);
    keeper.called(first, "coder", "file_edit", () -> "a.py").returned("ok");
    // The second opens while the first is still live, and its own tool call is interleaved
    // between the first's two -- an ordinal cutoff on "the latest delegated line" would draw
    // the boundary here and hand the first's facts the second's file too.
    keeper.delegated(conductor, "code_implementation", "coder", "second task", second);
    keeper.called(second, "coder", "file_edit", () -> "b.py").returned("ok");
    keeper.called(first, "coder", "run", () -> "pytest a").returned("exit 0");

    String firstFacts = keeper.delegationFacts(first, "coder");
    assertTrue(
        firstFacts.startsWith("[harness] coder edited a.py; ran pytest a → exit 0 ("), firstFacts);
    assertEquals(
        "[harness] coder edited b.py; ran nothing", keeper.delegationFacts(second, "coder"));
  }

  @Test
  void the_conductor_s_calls_are_the_conductor_s_and_a_phase_s_sub_agent_s_are_its_own() {
    OrchestrationRecord root = root();
    OrchestrationRecord child = run(root.id(), 1, "enzo");
    String coder = delegation(child.conductorConversation(), "coder");

    keeper
        .called(
            root.conductorConversation(), "implement_specification", "todo_write", () -> "2 ops")
        .returned("ok");
    keeper.called(coder, "coder", "file_read", () -> "/repo/a.py").returned("ok");

    List<RecordRow> rows = records.pageAfter(root.id(), 0, RecordKind.EVERY, 10).rows();
    assertEquals(List.of("conductor", "coder"), rows.stream().map(RecordRow::actor).toList());
    assertEquals(List.of(root.id(), child.id()), rows.stream().map(RecordRow::run).toList());
    assertEquals(
        List.of("conductor · todo_write 2 ops", "coder · file_read /repo/a.py"),
        rows.stream().map(RecordRow::text).toList());
  }

  /** §4: a phase run's lines say which phase they are from. */
  @Test
  void a_phase_run_s_lines_are_labelled_with_its_phase() {
    OrchestrationRecord root = root();
    OrchestrationRecord child = run(root.id(), 1, "enzo");

    keeper.runStarted(child, "phase 3", "03-character");
    keeper
        .called(child.conductorConversation(), "code_implementation", "todo_write", () -> "1 op")
        .returned("ok");

    List<RecordRow> rows = records.pageAfter(root.id(), 0, RecordKind.EVERY, 50).rows();
    assertEquals(
        "phase 03-character started: " + child.id() + " (code_implementation)",
        rows.get(rows.size() - 2).text(),
        "its own start is not labelled twice");
    assertEquals("03-character · conductor · todo_write 1 op", rows.get(rows.size() - 1).text());
  }

  @Test
  void a_run_outside_every_tree_records_nothing_and_tells_nobody() {
    String loose =
        conversations.log(Origin.ORCHESTRATION, Home.of("story"), "coder", null, Budget.of(5)).id();
    // An Error, which the keeper does not catch: asking for the argument here would fail
    // the test, not be logged and forgotten.
    Supplier<String> never =
        () -> {
          throw new AssertionError("a call in no tree had its arguments parsed for a line");
        };

    keeper.called(loose, "coder", "file_read", never).returned("ok");
    keeper.called(null, "coder", "file_read", never).returned("ok");
    keeper.delegated(loose, "coder", "helper", "look", "cnv_helper_loose");
    keeper.moved("cnv_nobody", List.of(), List.of());

    assertEquals(0, rows());
    assertEquals(List.of(), pushed);
  }

  @Test
  void every_milestone_is_one_line_a_person_reads() {
    OrchestrationRecord root = root();
    String coder = delegation(root.conductorConversation(), "coder");

    keeper.questionAsked(root, "Which database?\nPostgres or SQLite");
    keeper.questionAnswered(root, "PostgreSQL", "enzo");
    keeper.checkRan(root, List.of("./gradlew", "test"), 1, false);
    keeper.checkRan(root, List.of("./gradlew", "test"), 0, false);
    keeper.checkRan(root, List.of("pytest"), null, true);
    keeper.stalled(root, "has done nothing for 15 minutes");
    keeper.delegated(
        root.conductorConversation(),
        "implement_specification",
        "coder",
        "write the allocator\nthen test it",
        coder);
    keeper.callFailure(coder, "coder", "file_read", 2);
    keeper.callFailure(coder, "coder", "file_read", 0);
    keeper.callFailureEnded(coder, "coder", "file_read", RunActivity.Unwarned.ALLOWANCE_SPENT);
    keeper.callFailureEnded(coder, "coder", "todo_write", RunActivity.Unwarned.LAST_STEP);
    keeper.callFailureEnded(coder, "coder", "todo_write", RunActivity.Unwarned.LAST_BUDGETED_CALL);
    keeper.delegateReturned(
        root.conductorConversation(),
        "implement_specification",
        "coder",
        new Outcome(Outcome.Ending.ANSWERED, "the tests pass\nall 12", 3, 3, ""));

    assertEquals(
        List.of(
            "question_asked conductor asked: Which database?",
            "question_answered conductor answered by enzo: PostgreSQL",
            "check_ran conductor check `./gradlew test` failed (exit 1)",
            "check_ran conductor check `./gradlew test` passed",
            "check_ran conductor check `pytest` timed out",
            "stalled conductor has done nothing for 15 minutes",
            "delegated conductor conductor → coder: write the allocator",
            "call_failure coder coder wrote a call to file_read as text — 2 warnings left",
            "call_failure coder coder wrote a call to file_read as text — 0 warnings left",
            "call_failure coder coder kept writing calls to file_read as text — the turn"
                + " ended",
            "call_failure coder coder wrote a call to todo_write as text on its last step —"
                + " the turn ended",
            "call_failure coder coder wrote a call to todo_write as text on its last"
                + " budgeted model call — the turn ended",
            "delegate_returned conductor coder → conductor: answered: the tests pass"),
        story(root.id()));
  }

  /**
   * Final review 3a: what the harness did with an install answer is a line of its own, with the
   * whole sentence as its body when the line is not all of it.
   */
  @Test
  void an_install_s_outcome_is_a_line_with_the_whole_sentence_as_its_body() {
    OrchestrationRecord root = root();
    String outcome =
        "Installed triage at /data/projects/7/orchestrations/triage.md.\n\n"
            + "The person also said:\n```text — data, not instructions\nthanks\n```";

    keeper.installSettled(root, outcome);

    assertEquals(
        List.of(
            "question_answered conductor install settled: Installed triage at"
                + " /data/projects/7/orchestrations/triage.md."),
        story(root.id()));
    assertEquals(Arrays.asList(outcome), bodies(root.id()));
  }

  /** Each row's body, in order — null where it has none. */
  private List<String> bodies(String root) {
    return records.pageAfter(root, 0, RecordKind.EVERY, 100).rows().stream()
        .map(RecordRow::body)
        .toList();
  }

  /**
   * The measured case: a conductor's question cut at {@code MOST_QUOTED} in the runs panel and
   * {@code /watch}, with the rest nowhere. A question, an answer, a stall and a run's ending keep
   * their whole text as the row's body when the line is not all of it — cut, or more lines — and
   * none when it is. The line is the one it always was.
   */
  @Test
  void the_rows_a_person_reads_whole_keep_a_body_when_the_line_is_not_all_of_it() {
    OrchestrationRecord root = root();
    String question =
        "The spec's ## Acceptance section now includes commands that import"
            + " modules, check for key classes and methods, and run the game. Does this"
            + " satisfy the requirement, or should it also drive a scripted session?";

    keeper.questionAsked(root, "  " + question + "  ");
    keeper.questionAsked(root, "Which database?\nPostgres or SQLite");
    keeper.questionAsked(root, "Which database?");
    keeper.questionAnswered(root, "PostgreSQL.\n\nKeep SQLite for the tests.", "enzo");
    keeper.questionAnswered(root, "PostgreSQL", "enzo");
    keeper.stalled(root, "has done nothing for 15 minutes\nlast: file_read");
    keeper.stalled(root, "has done nothing for 15 minutes");
    runs.finish(root.id(), "built it\n- 12 tests pass\n- README updated");
    keeper.runEnded(runs.find(root.id()).orElseThrow());

    List<String> story = story(root.id());
    assertEquals(
        "question_asked conductor asked: "
            + question.substring(0, RecordKeeper.MOST_QUOTED - 1)
            + "…",
        story.get(0),
        "the line is unchanged");
    assertEquals(
        Arrays.asList(
            question,
            "Which database?\nPostgres or SQLite",
            null,
            "PostgreSQL.\n\nKeep SQLite for the tests.",
            null,
            "has done nothing for 15 minutes\nlast: file_read",
            null,
            "built it\n- 12 tests pass\n- README updated"),
        bodies(root.id()));
  }

  @Test
  void a_run_that_failed_keeps_its_whole_failure_and_a_short_one_none() {
    OrchestrationRecord failed = root();
    runs.stop(failed.id(), OrchestrationState.FAILED, "stuck\nthe verifier refused twice");
    keeper.runEnded(runs.find(failed.id()).orElseThrow());
    OrchestrationRecord shortly = root();
    runs.stop(shortly.id(), OrchestrationState.FAILED, "stuck");
    keeper.runEnded(runs.find(shortly.id()).orElseThrow());

    assertEquals(List.of("stuck\nthe verifier refused twice"), bodies(failed.id()));
    assertEquals(Collections.singletonList(null), bodies(shortly.id()));
  }

  /**
   * A phase's ending is read whole as a run's is: its {@code phase_ended} row keeps the phase's
   * whole result or failure as its body when the detail line is not all of it, and none when it is.
   * The line and its detail are the ones they always were.
   */
  @Test
  void a_phase_s_ending_keeps_its_whole_result_or_failure_as_a_run_s_does() {
    OrchestrationRecord root = root();
    OrchestrationRecord parsed = run(root.id(), 1, "enzo");
    OrchestrationRecord failed = run(root.id(), 1, "enzo");
    OrchestrationRecord shortly = run(root.id(), 1, "enzo");

    keeper.runStarted(parsed, "the parser", "02-parser");
    runs.finish(parsed.id(), "parsed\n- 12 tests pass\n- README updated");
    keeper.runEnded(runs.find(parsed.id()).orElseThrow());
    runs.stop(failed.id(), OrchestrationState.FAILED, "stuck\nthe verifier refused twice");
    keeper.runEnded(runs.find(failed.id()).orElseThrow());
    runs.finish(shortly.id(), "done");
    keeper.runEnded(runs.find(shortly.id()).orElseThrow());

    List<String> story = story(root.id());
    assertEquals(
        "phase_ended conductor phase 02-parser ended finished: " + parsed.id() + " | parsed",
        story.get(1),
        "the line and its detail are unchanged");
    assertEquals(
        Arrays.asList(
            null,
            "parsed\n- 12 tests pass\n- README updated",
            "stuck\nthe verifier refused twice",
            null),
        bodies(root.id()));
  }

  /** Nothing else takes a body: a delegation's long task stays its line, as it always was. */
  @Test
  void every_other_kind_is_written_without_a_body() {
    OrchestrationRecord root = root();
    String coder = delegation(root.conductorConversation(), "coder");

    keeper.runStarted(root, "build the stat allocator\nwith caps", null);
    keeper.delegated(
        root.conductorConversation(),
        "implement_specification",
        "coder",
        "write the allocator\nthen test it",
        coder);
    keeper.delegateReturned(
        root.conductorConversation(),
        "implement_specification",
        "coder",
        new Outcome(Outcome.Ending.ANSWERED, "the tests pass\nall 12", 3, 3, ""));

    assertEquals(Arrays.asList(null, null, null), bodies(root.id()));
  }

  /** Spec 2026-09-29 §1b: each acceptance command the harness ran, and why one failed. */
  @Test
  void an_acceptance_command_is_one_line_with_why_it_failed_as_its_detail() {
    OrchestrationRecord root = root();

    keeper.acceptanceRan(root, "python -m pytest -q", true, null);
    keeper.acceptanceRan(
        root,
        "python -m rpg.main",
        false,
        "exited 0, and its output does not contain `Main Menu`\n--- stdout ---");

    assertEquals(
        List.of(
            "acceptance_ran conductor acceptance `python -m pytest -q` passed",
            "acceptance_ran conductor acceptance `python -m rpg.main` failed | exited 0, and"
                + " its output does not contain `Main Menu`"),
        story(root.id()));
  }

  /** Spec 2026-09-29 §2: a cap passed without asking is one line, n of N, naming the cap. */
  @Test
  void a_cap_continued_is_one_line_counting_n_of_n_and_naming_the_cap() {
    OrchestrationRecord root = root();

    keeper.capContinued(root, "call_budget", 1, 3);
    keeper.capContinued(root, "turn_cap", 2, 3);
    keeper.capContinued(root, "time_cap", 3, 3);

    assertEquals(
        List.of(
            "cap_continued conductor cap continued (1/3) | the model-call budget",
            "cap_continued conductor cap continued (2/3) | the turn cap",
            "cap_continued conductor cap continued (3/3) | the time cap"),
        story(root.id()));
  }

  /**
   * V77: the acceptance checker's work is a line of the story under whoever did it — the checker by
   * name, the conductor, the person — with the whole text as its body when the line is not all of
   * it.
   */
  @Test
  void a_concern_is_one_line_under_whoever_did_it() {
    OrchestrationRecord root = root();

    keeper.concern(
        root,
        "acceptance_checker",
        "concern c1 raised: nothing starts the game",
        "Concern c1: nothing starts the game\nWhy it is a concern: main.py is a no-op");
    keeper.concern(
        root, "conductor", "the conductor answers c1: the runs-for line starts it", null);

    assertEquals(
        List.of(
            "concern acceptance_checker concern c1 raised: nothing starts the game",
            "concern conductor the conductor answers c1: the runs-for line starts it"),
        story(root.id()));
  }

  /** V69: a time cap's question names the run's own latest milestone, not a phase's. */
  @Test
  void the_latest_milestone_is_the_run_s_own_newest_line() {
    OrchestrationRecord root = root();
    OrchestrationRecord child = run(root.id(), 1, "enzo");
    assertEquals(java.util.Optional.empty(), keeper.latestMilestone(root));

    keeper.runStarted(root, "build it", null);
    keeper.checkRan(root, List.of("pytest", "-q"), 1, false);
    keeper.runStarted(child, "the parser", "02-parser");
    keeper.checkRan(child, List.of("pytest"), 0, false);

    assertEquals(
        java.util.Optional.of("check `pytest -q` failed (exit 1)"), keeper.latestMilestone(root));
    assertEquals(
        java.util.Optional.of("02-parser · check `pytest` passed"), keeper.latestMilestone(child));
  }

  /**
   * The check's result as a reviewing delegate is handed it (measured 2026-09-30,
   * orc_3190C667F18B8E57): the run's own latest {@code check_ran} row, read back — never a sibling
   * phase's, and never anything a model said — with the end of its output as the row's body.
   */
  @Test
  void the_latest_check_is_the_run_s_own_newest_check_ran_row_with_its_output() {
    OrchestrationRecord root = root();
    OrchestrationRecord child = run(root.id(), 1, "enzo");
    OrchestrationRecord sibling = run(root.id(), 1, "enzo");
    keeper.runStarted(child, "the parser", "02-parser");
    keeper.runStarted(sibling, "the lexer", "03-lexer");
    assertEquals(java.util.Optional.empty(), keeper.latestCheck(child));

    keeper.checkRan(
        child,
        List.of("pytest", "-q"),
        1,
        false,
        "--- stdout ---\n1 failed, 25 passed\n--- stderr ---\n(nothing)");
    keeper.checkRan(
        child,
        List.of("pytest", "-q"),
        0,
        false,
        "--- stdout ---\n26 passed\n--- stderr ---\n(nothing)");
    keeper.checkRan(sibling, List.of("pytest", "-q"), 3, false, "--- stdout ---\nboom");

    CheckFacts.Ran latest = keeper.latestCheck(child).orElseThrow();
    assertEquals(CheckFacts.Result.PASSED, latest.result());
    assertEquals(0, latest.exitCode());
    assertEquals(T0, latest.at());
    assertEquals("--- stdout ---\n26 passed\n--- stderr ---\n(nothing)", latest.output());
    CheckFacts.Ran theirs = keeper.latestCheck(sibling).orElseThrow();
    assertEquals(CheckFacts.Result.FAILED, theirs.result());
    assertEquals(3, theirs.exitCode());
    assertEquals(java.util.Optional.empty(), keeper.latestCheck(root));
  }

  @Test
  void a_timed_out_check_and_one_with_no_output_read_back_as_they_were_written() {
    OrchestrationRecord root = root();

    keeper.checkRan(root, List.of("pytest"), null, true);
    CheckFacts.Ran timedOut = keeper.latestCheck(root).orElseThrow();
    assertEquals(CheckFacts.Result.TIMED_OUT, timedOut.result());
    assertNull(timedOut.exitCode());
    assertNull(timedOut.output());

    keeper.checkRan(root, List.of("pytest"), null, false);
    CheckFacts.Ran noExit = keeper.latestCheck(root).orElseThrow();
    assertEquals(CheckFacts.Result.FAILED, noExit.result());
    assertNull(noExit.exitCode());
  }

  /**
   * A line is cut at the store's length, and a check's result is the end of its line: a long
   * command is quoted short, so the result is always there to read back.
   */
  @Test
  void a_long_check_command_is_quoted_short_so_its_result_survives() {
    OrchestrationRecord root = root();

    keeper.checkRan(root, List.of("pytest", "a".repeat(600)), 0, false);

    String text = story(root.id()).get(0);
    assertTrue(text.endsWith("` passed"), text);
    assertEquals(CheckFacts.Result.PASSED, keeper.latestCheck(root).orElseThrow().result());
  }

  @Test
  void a_stage_s_move_and_a_phase_s_move_are_recorded_and_any_other_item_s_is_not() {
    OrchestrationRecord root = root();
    String c = root.conductorConversation();
    TodoItem code =
        new TodoItem(
            "td_code",
            c,
            null,
            3,
            "code — done when tests pass",
            TodoStatus.IN_PROGRESS,
            null,
            true,
            "code",
            T0);
    TodoItem phase =
        new TodoItem(
            "td_phase",
            c,
            "td_code",
            0,
            "02 the parser\nand lexer",
            TodoStatus.PENDING,
            null,
            false,
            null,
            T0);
    TodoItem loose =
        new TodoItem("td_loose", c, null, 4, "tidy", TodoStatus.PENDING, null, false, null, T0);
    TodoItem codeDone = code.withStatus(TodoStatus.DONE, T0).withSummary("built it\nall green", T0);
    TodoItem phaseGoing = phase.withStatus(TodoStatus.IN_PROGRESS, T0);
    TodoItem looseDone = loose.withStatus(TodoStatus.DONE, T0);

    keeper.moved(
        c,
        List.of(
            new StatusMoves.Move(code, codeDone),
            new StatusMoves.Move(phase, phaseGoing),
            new StatusMoves.Move(loose, looseDone)),
        List.of(codeDone, phaseGoing, looseDone));

    assertEquals(
        List.of(
            "stage_moved conductor code: in_progress → done | built it",
            "stage_moved conductor code › 02 the parser: pending → in_progress"),
        story(root.id()));
  }

  @Test
  void an_approval_asked_under_a_sub_agent_and_its_answer_are_recorded_in_the_tree() {
    OrchestrationRecord root = root();
    String coder = delegation(root.conductorConversation(), "coder");
    RunApproval asked =
        new RunApproval(
            "apr_1",
            7L,
            root.conductorConversation(),
            coder,
            "enzo",
            "coder",
            "local",
            List.of("pytest", "-q"),
            "/repo",
            null,
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            T0);
    RunApproval allowed =
        new RunApproval(
            "apr_1",
            7L,
            root.conductorConversation(),
            coder,
            "enzo",
            "coder",
            "local",
            List.of("pytest", "-q"),
            "/repo",
            null,
            RunApproval.ALLOWED,
            RunApproval.ONCE,
            null,
            "enzo",
            T0,
            null,
            T0);

    keeper.asked(asked);
    keeper.answered(allowed);

    assertEquals(
        List.of(
            "approval_asked coder approval asked to run `pytest -q` in /repo (local) | apr_1",
            "approval_answered coder approval allowed for once: `pytest -q` | enzo"),
        story(root.id()));
  }

  /** V67: a set is one row, its commands the body; the judge's allowing names the judge. */
  @Test
  void a_set_is_one_asked_row_and_the_judge_s_allowing_says_so_with_every_command() {
    OrchestrationRecord root = root();
    List<List<String>> set =
        List.of(List.of("pytest", "-q", "tests/a.py"), List.of("pytest", "-q", "tests/b.py"));
    RunApproval asked =
        new RunApproval(
            "apr_1",
            7L,
            root.conductorConversation(),
            root.conductorConversation(),
            "enzo",
            "implement_specification",
            "local",
            List.of(),
            "/repo",
            "reason",
            RunApproval.ASKED,
            null,
            null,
            null,
            null,
            null,
            T0,
            set,
            null);
    RunApproval allowed =
        new RunApproval(
            "apr_1",
            7L,
            root.conductorConversation(),
            root.conductorConversation(),
            "enzo",
            "implement_specification",
            "local",
            List.of(),
            "/repo",
            "reason",
            RunApproval.ALLOWED,
            RunApproval.ONCE,
            null,
            "enzo",
            T0,
            null,
            T0,
            set,
            null);
    RunApproval judged =
        new RunApproval(
            "apr_2",
            7L,
            root.conductorConversation(),
            root.conductorConversation(),
            "enzo",
            "implement_specification",
            "local",
            List.of(),
            "/repo",
            "reason",
            RunApproval.ALLOWED,
            RunApproval.ONCE,
            null,
            RunApproval.JUDGE,
            T0,
            null,
            T0,
            set,
            "they run the project's own tests");

    keeper.asked(asked);
    keeper.answered(allowed);
    keeper.answered(judged);

    assertEquals(
        List.of(
            "approval_asked conductor approval asked to run 2 acceptance commands in /repo"
                + " (local) | apr_1",
            "approval_answered conductor approval allowed for once: 2 acceptance commands"
                + " | enzo",
            "approval_answered conductor approval allowed by the command judge: they run the"
                + " project's own tests | command judge"),
        story(root.id()));
    String commands = "pytest -q tests/a.py\npytest -q tests/b.py";
    assertEquals(List.of(commands, commands, commands), bodies(root.id()));
  }

  /** V67: a check set under consent another run's answer gave says which approval covered it. */
  @Test
  void a_check_covered_by_consent_already_given_names_the_approval() {
    OrchestrationRecord root = root();

    keeper.consentCovered(
        root,
        List.of("pytest", "-q"),
        "apr_9",
        "allowed as the check of orc_1 (code_implementation)");

    assertEquals(
        List.of(
            "approval_answered conductor approval allowed: `pytest -q` — covered"
                + " by apr_9 (allowed as the check of orc_1 (code_implementation)) | apr_9"),
        story(root.id()));
  }

  @Test
  void a_tree_with_no_account_is_recorded_and_pushed_to_nobody() {
    OrchestrationRecord root = run(null, 0, null);

    keeper.runStarted(root, "build it", null);

    assertEquals(1, rows());
    assertEquals(List.of(), pushed);
  }

  @Test
  void a_record_that_cannot_be_written_costs_its_caller_nothing() {
    RecordStore broken =
        new RecordStore(jdbc, () -> T0, work) {
          // The 8-arg overload: every write reaches it, including through the 6- and 7-arg
          // ones (which call it, virtually) — so overriding only this still catches every kind.
          @Override
          public int append(
              String root,
              String run,
              String actor,
              RecordKind kind,
              String text,
              String detail,
              String conversation,
              String body) {
            throw new IllegalStateException("the record table is on fire");
          }
        };
    RecordKeeper keeping = new RecordKeeper(broken, runs, () -> AccountPushes.NONE);
    OrchestrationRecord root = root();

    // Every call returns normally: a throw here would fail this test.
    keeping.runStarted(root, "build it", null);
    keeping.runEnded(root);
    keeping.questionAsked(root, "why?");
    keeping.checkRan(root, List.of("x"), 1, false);
    keeping.stalled(root, "quiet");
    keeping.called(root.conductorConversation(), "c", "run", () -> "x").returned("ok");
    keeping.delegated(root.conductorConversation(), "c", "coder", "go", "cnv_coder_x");

    assertEquals(0, rows());
  }
}
