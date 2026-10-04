package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.AgentDefinition;
import io.aeyer.plowshare.server.agents.AgentRegistry;
import io.aeyer.plowshare.server.agents.BoundTools;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition;
import io.aeyer.plowshare.server.agents.OrchestrationDefinition.Tier;
import io.aeyer.plowshare.server.agents.OrchestrationRegistry;
import io.aeyer.plowshare.server.approvals.RunApproval;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.todos.LockedMoves;
import io.aeyer.plowshare.server.todos.TodoBoard;
import io.aeyer.plowshare.server.todos.TodoLists;
import io.aeyer.plowshare.server.todos.TodoNotices;
import io.aeyer.plowshare.server.todos.TodoStatus;
import io.aeyer.plowshare.server.todos.TodoStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
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
 * A conductor's delegation to an agent that judges work it cannot run, while a checked stage is in
 * progress, carries the run's check as the harness last ran it — read from the harness's own store,
 * and from nothing a model said. Measured 2026-09-30, orc_3190C667F18B8E57: the check had just
 * passed 26 of 26, code_reviewer (which runs nothing) reported with "High" confidence that one of
 * those tests fails, and the conductor sent the work back to {@code code} on that claim, twice.
 */
@Tag("full-db")
@Testcontainers
class ReviewerCheckFactsTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Instant T0 = Instant.parse("2026-09-30T14:00:00Z");

  private static final Set<String> TOOLS = Set.of("file_read", "file_write");

  private static final String CHECKED =
      """
            ---
            name: code_implementation
            description: Takes a task to reviewed code.
            model: reasoning
            max-turns: 60
            max-model-calls: 400
            tools: [file_read]
            stages:
              - {id: test_design}
              - {id: code, check: required}
              - {id: review, check: required, may-return-to: [code]}
            artifacts: docs/orchestrations/{date}-{name}-{id}/
            ---
            You are conducting a code implementation.
            """;

  private static final Path SHIPPED = Path.of("src/main/resources/agents");

  private static JdbcTemplate jdbc;
  private static UnitOfWork work;
  private static AgentRegistry agents;

  private OrchestrationStore store;
  private OrchestrationChecks checks;
  private RecordKeeper keeper;
  private Orchestrations engine;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource ds =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(ds).load().migrate();
    jdbc = new JdbcTemplate(ds);
    TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(ds));
    work =
        new UnitOfWork() {
          @Override
          public <T> T inTransaction(Supplier<T> body) {
            return template.execute(status -> body.get());
          }
        };
    agents = AgentRegistry.of(SHIPPED, BoundTools.boundByThisServer());
  }

  @BeforeEach
  void fresh() {
    jdbc.execute(
        "TRUNCATE TABLE orchestration_record, orchestration_checks,"
            + " orchestration_messages, orchestrations, todos, todo_notices, entries, turns,"
            + " conversations, admins CASCADE");
    jdbc.update("INSERT INTO admins (handle, password_hash) VALUES ('enzo', 'h')");
    List<String> events = new ArrayList<>();
    AtomicLong tick = new AtomicLong();
    store = new OrchestrationStore(jdbc, () -> T0.plusMillis(tick.incrementAndGet()), work);
    ConversationStore conversations = new ConversationStore(jdbc, () -> T0, null);
    TodoBoard board =
        new TodoBoard(
            new TodoStore(jdbc),
            work,
            LockedMoves.REFUSE_ALL,
            TodoLists.Changed.NONE,
            () -> T0,
            TodoNotices.NONE,
            c -> -1);
    OrchestrationsTest.RecordingDelivery delivery =
        new OrchestrationsTest.RecordingDelivery(events);
    delivery.store = store;
    engine =
        new Orchestrations(
            store,
            conversations,
            board,
            new OrchestrationsTest.RecordingTodos(board, events),
            work,
            new OrchestrationsTest.FakeVoice(events),
            delivery,
            TOOLS,
            UnaryOperator.identity(),
            session -> session.equals("s-live"),
            () -> T0,
            run -> {},
            conversation -> {},
            2,
            org.mockito.Mockito.mock(io.aeyer.plowshare.server.agents.CallerAccess.class));
    checks = new OrchestrationChecks(jdbc);
    engine.useChecks(
        checks, (run, argv, side, cwd) -> Optional.empty(), id -> Optional.<RunApproval>empty());
    keeper =
        new RecordKeeper(
            new RecordStore(jdbc, () -> T0.plusSeconds(312), work),
            store,
            () -> AccountPushes.NONE);
    engine.useRecorder(keeper);
  }

  private OrchestrationRecord started() {
    OrchestrationDefinition checked =
        OrchestrationRegistry.parsePinned(
            "code_implementation", "test", CHECKED, TOOLS, Tier.PROJECT);
    return engine.start(
        new Orchestrations.Start(
            checked,
            Home.of("story"),
            "build it",
            null,
            null,
            "interlocutor",
            "enzo",
            "s-live",
            null,
            0));
  }

  private void move(OrchestrationRecord run, String stage, TodoStatus to) {
    jdbc.update(
        "UPDATE todos SET status = ? WHERE conversation = ? AND stage_id = ?",
        to.wire(),
        run.conductorConversation(),
        stage);
  }

  /** A run at {@code review}, its check set to {@code make test}. */
  private OrchestrationRecord atReview() {
    OrchestrationRecord run = started();
    checks.set(
        new OrchestrationChecks.Check(
            run.id(),
            List.of("make", "test"),
            "local",
            "/repo",
            OrchestrationChecks.OPEN,
            null,
            T0));
    move(run, "test_design", TodoStatus.DONE);
    move(run, "code", TodoStatus.DONE);
    move(run, "review", TodoStatus.IN_PROGRESS);
    return run;
  }

  private static AgentDefinition reviewer() {
    return agents.get("code_reviewer");
  }

  @Test
  void a_reviewer_at_a_checked_stage_is_handed_the_check_s_latest_pass_from_the_store() {
    OrchestrationRecord run = atReview();
    keeper.checkRan(run, List.of("make", "test"), 1, false, "--- stdout ---\n1 failed");
    keeper.checkRan(
        run,
        List.of("make", "test"),
        0,
        false,
        "--- stdout ---\n26 passed\n--- stderr ---\n(nothing)");

    String block = engine.checkFacts(run.conductorConversation(), reviewer()).orElseThrow();

    assertEquals(
        "[harness] The run's check `make test` last ran at 2026-09-30T14:05:12Z:"
            + " passed (exit 0). The harness ran it itself; this is its result, not anyone's"
            + " account of it. Its output ended:\n--- stdout ---\n26 passed\n--- stderr ---\n"
            + "(nothing)",
        block);
  }

  @Test
  void a_reviewer_is_handed_a_failed_check_with_the_end_of_its_output() {
    OrchestrationRecord run = atReview();
    keeper.checkRan(
        run,
        List.of("make", "test"),
        2,
        false,
        "--- stdout ---\nFAILED test_alien_steps_down\n1 failed, 25 passed");

    String block = engine.checkFacts(run.conductorConversation(), reviewer()).orElseThrow();

    assertTrue(block.contains("last ran at 2026-09-30T14:05:12Z: failed (exit 2)."), block);
    assertTrue(
        block.endsWith(
            "Its output ended:\n--- stdout ---\nFAILED"
                + " test_alien_steps_down\n1 failed, 25 passed"),
        block);
  }

  @Test
  void a_reviewer_before_the_check_has_run_is_told_there_is_no_result() {
    OrchestrationRecord run = atReview();

    assertEquals(
        Optional.of(
            "[harness] The run's check `make test` has not run yet: the"
                + " harness runs it each time a checked stage is marked done, and none has been,"
                + " so there is no result to go on."),
        engine.checkFacts(run.conductorConversation(), reviewer()));
  }

  @Test
  void a_reviewer_in_a_run_with_no_check_is_told_it_has_none() {
    OrchestrationRecord run = started();
    move(run, "test_design", TodoStatus.DONE);
    move(run, "code", TodoStatus.IN_PROGRESS);

    assertEquals(
        Optional.of(
            "[harness] This run has no check yet: no command has been set to"
                + " show the work is done, so the harness has run nothing and has no result to go"
                + " on."),
        engine.checkFacts(run.conductorConversation(), reviewer()));
  }

  /**
   * The coder runs the check's command itself, and is told what failed when a move is refused: the
   * block is for an agent judging work it cannot run.
   */
  @Test
  void a_delegate_that_can_run_commands_is_not_handed_the_block() {
    OrchestrationRecord run = atReview();
    keeper.checkRan(run, List.of("make", "test"), 0, false, "--- stdout ---\n26 passed");

    assertEquals(
        Optional.empty(), engine.checkFacts(run.conductorConversation(), agents.get("coder")));
  }

  @Test
  void no_block_while_the_stage_in_progress_is_not_checked() {
    OrchestrationRecord run = started();
    checks.set(
        new OrchestrationChecks.Check(
            run.id(),
            List.of("make", "test"),
            "local",
            "/repo",
            OrchestrationChecks.OPEN,
            null,
            T0));
    move(run, "test_design", TodoStatus.IN_PROGRESS);
    keeper.checkRan(run, List.of("make", "test"), 0, false, "--- stdout ---\n26 passed");

    assertEquals(Optional.empty(), engine.checkFacts(run.conductorConversation(), reviewer()));
  }

  @Test
  void no_block_outside_a_live_run() {
    OrchestrationRecord run = atReview();

    assertEquals(Optional.empty(), engine.checkFacts("cnv_nobody", reviewer()));
    store.stop(run.id(), OrchestrationState.CANCELLED, "cancelled");
    assertEquals(Optional.empty(), engine.checkFacts(run.conductorConversation(), reviewer()));
  }

  /** Only this run's own check: a sibling phase's result never answers for it. */
  @Test
  void another_run_s_check_is_not_this_run_s_result() {
    OrchestrationRecord run = atReview();
    OrchestrationRecord other = atReview();
    keeper.checkRan(other, List.of("make", "test"), 0, false, "--- stdout ---\n26 passed");

    String block = engine.checkFacts(run.conductorConversation(), reviewer()).orElseThrow();

    assertTrue(block.contains("has not run yet"), block);
    assertFalse(block.contains("passed"), block);
  }
}
