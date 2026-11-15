package io.aeyer.plowshare.server.approvals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.BudgetCheckpoint;
import io.aeyer.plowshare.server.allowances.AllowanceId;
import io.aeyer.plowshare.server.allowances.JdbcAllowanceRepository;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** PostgreSQL locking, rollback, migration constraints and canonical allowance mapping. */
@Tag("full-db")
@Testcontainers
class JdbcBudgetRequestRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;
  private JdbcBudgetRequestRepository repository;
  private BudgetCheckpoint checkpoint;
  private Budget budget;
  private long project;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @BeforeEach
  void fresh() {
    AuthorityFixtures.truncate(jdbc, "TRUNCATE TABLE projects, admins CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('owner','fixture')");
    project =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES('budget-test') RETURNING id", Long.class);
    budget = Budget.of(2);
    budget.trySpend();
    budget.trySpend();
    String root =
        new ConversationStore(jdbc).open(Home.of("budget-test"), budget, null, "owner").id();
    checkpoint =
        new BudgetCheckpoint(
            null, "owner", List.of(new BudgetCheckpoint.Frame(root, "helper", 1, 2, 2, List.of())));
    repository = new JdbcBudgetRequestRepository(jdbc, transactions);
  }

  private BudgetRequestRepository.Request ask(int calls, int steps) {
    return repository.ask(
        project,
        checkpoint,
        new AllowanceId(budget.identity()),
        new BudgetExtension(calls, steps),
        "Finish original work");
  }

  @Test
  void concurrent_answers_grant_once_without_resetting_spending_or_creating_another_task()
      throws Exception {
    var question = ask(4, 2);
    assertEquals(question.approval(), ask(4, 2).approval());
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<BudgetRequestRepository.Request>> answers = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        answers.add(workers.submit(() -> repository.answer(question.approval(), true, "reviewer")));
      for (var answer : answers) assertEquals(6, answer.get().allowance().ceiling());
    }
    var restored =
        new JdbcBudgetRequestRepository(jdbc, transactions).find(question.approval()).orElseThrow();
    assertEquals(checkpoint, restored.checkpoint());
    assertEquals(new AllowanceId(budget.identity()), restored.allowance().id());
    assertEquals(2, restored.allowance().spent());
    assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class));
    var approvals = new RunApprovalStore(jdbc, Instant::now);
    assertEquals(
        new BudgetExtension(4, 2), approvals.find(question.approval()).orElseThrow().budget());
    assertFalse(
        approvals.allow(question.approval(), RunApproval.PROJECT, List.of("run"), "reviewer"));
    assertThrows(
        CallerFault.class, () -> repository.answer(question.approval(), false, "reviewer"));
    repository.acknowledged(question.approval());
    assertTrue(repository.recoverable().isEmpty());
    assertEquals(6, repository.answer(question.approval(), true, "reviewer").allowance().ceiling());
    assertFalse(repository.awaiting(question.root()));
    assertFalse(question.approval().equals(ask(1, 0).approval()));
  }

  @Test
  void grant_and_decision_rollback_together_and_overflow_never_answers_the_question() {
    var question = ask(4, 0);
    transactions.executeWithoutResult(
        status -> {
          repository.answer(question.approval(), true, "reviewer");
          status.setRollbackOnly();
        });
    var rolledBack = repository.find(question.approval()).orElseThrow();
    assertEquals(RunApproval.ASKED, rolledBack.state());
    assertEquals(2, rolledBack.allowance().ceiling());
    new JdbcAllowanceRepository(jdbc)
        .ceiling(new AllowanceId(budget.identity()), Integer.MAX_VALUE);
    assertThrows(CallerFault.class, () -> repository.answer(question.approval(), true, "reviewer"));
    assertEquals(RunApproval.ASKED, repository.find(question.approval()).orElseThrow().state());
  }

  @Test
  void
      unrelated_allowances_and_owners_cannot_request_and_archival_blocks_grants_but_allows_denial() {
    var foreign = Budget.of(9);
    new JdbcAllowanceRepository(jdbc).retain(foreign.snapshot());
    assertThrows(
        CallerFault.class,
        () ->
            repository.ask(
                project,
                checkpoint,
                new AllowanceId(foreign.identity()),
                new BudgetExtension(1, 0),
                "Wrong source"));
    var foreignOwner = new BudgetCheckpoint(null, "another-owner", checkpoint.frames());
    assertThrows(
        CallerFault.class,
        () ->
            repository.ask(
                project,
                foreignOwner,
                new AllowanceId(budget.identity()),
                new BudgetExtension(1, 0),
                "Wrong owner"));
    var question = ask(4, 0);
    jdbc.update("UPDATE conversations SET lifecycle='archived' WHERE id=?", question.root());
    assertThrows(CallerFault.class, () -> repository.answer(question.approval(), true, "reviewer"));
    repository.answer(question.approval(), false, "reviewer", "The task is no longer needed");
    assertEquals(2, repository.find(question.approval()).orElseThrow().allowance().ceiling());
    assertEquals(
        "The task is no longer needed",
        jdbc.queryForObject(
            "SELECT denial_reason FROM run_approvals WHERE id=?",
            String.class,
            question.approval()));
  }
}
