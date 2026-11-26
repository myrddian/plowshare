package io.aeyer.plowshare.server.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.Outcome.Ending;
import io.aeyer.plowshare.server.agents.Turn.Refused;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.TurnStore;
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.goals.ConversationGoals.State;
import io.aeyer.plowshare.server.goals.ConversationGoals.Status;
import io.aeyer.plowshare.server.goals.GoalRepository.Binding;
import io.aeyer.plowshare.server.goals.GoalRepository.Progress;
import io.aeyer.plowshare.server.pool.SqlWorkBudget;
import io.aeyer.plowshare.server.pool.SqlWorkBudgetProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

@Tag("full-db")
@Testcontainers
class JdbcGoalRepositoryTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static JdbcGoalRepository repository;
  private Binding binding;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    repository =
        new JdbcGoalRepository(
            jdbc,
            new UnitOfWork() {
              @Override
              public <T> T inTransaction(Supplier<T> work) {
                return transaction.execute(status -> work.get());
              }
            },
            new SqlWorkBudget() {
              @Override
              public <T> T within(Duration ceiling, Supplier<T> work) {
                return work.get();
              }
            },
            new SqlWorkBudgetProperties(
                Duration.ofSeconds(2), Duration.ofMillis(500), 16, 4, 16, 2));
  }

  @BeforeEach
  void createConversation() {
    String actor = "goal_" + UUID.randomUUID().toString().replace("-", "");
    String project = "goals_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", actor);
    long projectId =
        jdbc.queryForObject(
            "INSERT INTO projects(name) VALUES(?) RETURNING id", Long.class, project);
    var budget = Budget.of(10);
    var conversation = new ConversationStore(jdbc).open(Home.of(project), budget, null, actor);
    UUID admission = UUID.randomUUID();
    jdbc.update(
        """
INSERT INTO operation_admissions(id,executor,executor_handle,scope,project_name,source_account)
SELECT ?,a.id,?,s.id,?,a.id FROM authority_accounts a, authority_scopes s
WHERE a.legacy_handle=? AND s.project_id=?
""",
        admission,
        actor,
        project,
        actor,
        projectId);
    binding =
        new Binding(
            UUID.randomUUID(),
            conversation.id(),
            admission,
            actor,
            "worker",
            GoalText.digest("worker"),
            budget.identity(),
            "Verify the report",
            null);
  }

  @Test
  void concurrent_identical_replies_share_one_receipt_and_one_goal() throws Exception {
    UUID request = UUID.randomUUID();
    Supplier<Status> start =
        () ->
            repository.request(
                binding.executor(),
                request,
                binding.conversation(),
                "fixture",
                () -> {
                  var created = repository.insert(binding, 4);
                  return new Status(
                      binding.id(),
                      binding.conversation(),
                      binding.agent(),
                      binding.objective(),
                      null,
                      State.READY,
                      created.version(),
                      0,
                      4,
                      null,
                      null,
                      null,
                      null,
                      0,
                      10);
                });

    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var left = tasks.submit(start::get);
      var right = tasks.submit(start::get);
      assertEquals(left.get(5, TimeUnit.SECONDS), right.get(5, TimeUnit.SECONDS));
    }

    var another =
        new Binding(
            UUID.randomUUID(),
            binding.conversation(),
            binding.admission(),
            binding.executor(),
            binding.agent(),
            binding.fingerprint(),
            binding.allowance(),
            binding.objective(),
            null);
    assertThrows(Refused.class, () -> repository.insert(another, 4));
    assertEquals(
        binding.id(), repository.latest(binding.conversation()).orElseThrow().binding().id());
  }

  @Test
  void settlement_and_transcript_rollback_together_and_only_one_claim_dispatches() {
    var ready = repository.insert(binding, 4);
    var running =
        new Progress(State.RUNNING, 1, 4, "job_fixture", "job_fixture", null, null, null, 0, null);
    repository.change(ready, running).orElseThrow();
    assertTrue(repository.change(ready, running).isEmpty());

    var turns = new TurnStore(jdbc);
    int ordinal = turns.reserve(binding.conversation());
    Runnable turn =
        () ->
            turns.recordAt(
                binding.conversation(),
                ordinal,
                "Goal task",
                "Verified",
                Ending.ANSWERED,
                null,
                binding.agent(),
                null);
    assertThrows(
        IllegalStateException.class,
        () ->
            repository.settle(
                binding.id(),
                "job_fixture",
                turn,
                current -> {
                  throw new IllegalStateException("Fault after writing turn");
                }));
    assertTrue(turns.forConversation(binding.conversation()).isEmpty());
    assertEquals(State.RUNNING, repository.find(binding.id()).orElseThrow().progress().state());

    repository.settle(
        binding.id(),
        "job_fixture",
        turn,
        current ->
            new Progress(
                State.COMPLETE, 1, 4, null, "job_fixture", ordinal, null, "Verified", 0, null));
    assertEquals(1, turns.forConversation(binding.conversation()).size());
    assertEquals(State.COMPLETE, repository.find(binding.id()).orElseThrow().progress().state());
  }
}
