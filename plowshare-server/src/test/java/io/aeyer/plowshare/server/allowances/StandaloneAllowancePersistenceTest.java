package io.aeyer.plowshare.server.allowances;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.agents.Budget;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Disposable PostgreSQL only: migration preservation, atomic claims and immutable evidence. */
@Tag("full-db")
@Testcontainers
class StandaloneAllowancePersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;
  private static final UUID HISTORICAL = UUID.randomUUID();
  private static final UUID HISTORICAL_OPERATION = UUID.randomUUID();

  @BeforeAll
  static void migrateIsolatedFixture() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).target("169").load().migrate();
    jdbc = new JdbcTemplate(source);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('migration-owner','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('migration-project')");
    jdbc.update(
        "INSERT INTO conversations(id,project_id,origin,agent,lifecycle,created_at,budget_total,budget_spent,owner_handle) SELECT 'cnv_migration_seat',id,'board','worker','active',now(),NULL,NULL,'migration-owner' FROM projects WHERE name='migration-project'");
    jdbc.update(
        "INSERT INTO conversations(id,project_id,origin,agent,parent_id,lifecycle,created_at,budget_total,budget_spent,owner_handle) SELECT 'cnv_migration_child',id,'delegation','helper','cnv_migration_seat',NULL,now(),NULL,NULL,'migration-owner' FROM projects WHERE name='migration-project'");
    jdbc.update(
        "INSERT INTO board_topics(id,project,parent,root,depth,title,label,account,opener_kind,opener,state,pot_total,pot_spent,reserve,opened_at) VALUES('bdt_migration','migration-project',NULL,'bdt_migration',0,'Review','REVIEW','migration-owner','person','migration-owner','open',10,2,1,now())");
    jdbc.update(
        "INSERT INTO board_seats(topic,occupant,conversation) VALUES('bdt_migration','worker','cnv_migration_seat')");
    jdbc.update(
        "INSERT INTO operation_inference_budgets(operation_id,budget_id,source_conversation) VALUES(?,?,'cnv_historical_input')",
        HISTORICAL_OPERATION,
        HISTORICAL);
    jdbc.update(
        "INSERT INTO inference_admissions(id,operation_id,budget_id,ordinal,call_limit,submitted) VALUES(?,?,?,?,?,true)",
        UUID.randomUUID(),
        HISTORICAL_OPERATION,
        HISTORICAL,
        3,
        10);
    Flyway.configure().dataSource(source).load().migrate();
  }

  @Test
  void migratedBoardSeatsAndDelegatesReferenceTheExistingPotWithoutMergingOldWakeClaims() {
    UUID root =
        jdbc.queryForObject(
            "SELECT allowance_id FROM board_topics WHERE id='bdt_migration'", UUID.class);
    for (String conversation : List.of("cnv_migration_seat", "cnv_migration_child"))
      assertEquals(
          root,
          jdbc.queryForObject(
              "SELECT allowance_id FROM conversations WHERE id=?", UUID.class, conversation));
    assertNotEquals(HISTORICAL, root);
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    assertEquals(3, repository.claim(new AllowanceId(root), 1).orElseThrow().ordinal());
    assertEquals(10, repository.find(new AllowanceId(root)).orElseThrow().ceiling());
  }

  @Test
  void restoringAndReadingHistoryNeverWritesOrCreatesAnAllowance() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    io.aeyer.plowshare.server.archive.ConversationStore conversations =
        new io.aeyer.plowshare.server.archive.ConversationStore(jdbc);
    io.aeyer.plowshare.server.archive.ConversationRecord conversation =
        conversations.open(io.aeyer.plowshare.protocol.Home.of("migration-project"), Budget.of(3));
    org.springframework.jdbc.datasource.DataSourceTransactionManager manager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
    manager.setEnforceReadOnly(true);
    org.springframework.transaction.support.TransactionTemplate read =
        new org.springframework.transaction.support.TransactionTemplate(manager);
    read.setReadOnly(true);
    read.executeWithoutResult(
        status -> {
          Budget restored = repository.restore(conversation.budget().snapshot().id());
          assertEquals(0, restored.spent());
          assertEquals(
              restored.identity(),
              conversations.find(conversation.id()).orElseThrow().budget().identity());
          assertThrows(IllegalStateException.class, () -> repository.attach(Budget.none()));
        });
  }

  @Test
  void counterOverflowIsAnExplicitFailureAndCannotWrapAnUnlimitedSource() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    AllowanceId id = new AllowanceId(UUID.randomUUID());
    repository.retain(new AllowanceSnapshot(id, null, Integer.MAX_VALUE));
    RuntimeException failure = assertThrows(RuntimeException.class, () -> repository.claim(id, 0));
    assertTrue(failure.getMessage().contains("counter exhausted"));
    assertEquals(Integer.MAX_VALUE, repository.find(id).orElseThrow().spent());
    assertThrows(IllegalArgumentException.class, () -> repository.claim(id, 0, -1));
  }

  @Test
  void historicalClaimsRetainTheirIdentityUsageAndSubmissionState() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    AllowanceSnapshot snapshot = repository.find(new AllowanceId(HISTORICAL)).orElseThrow();
    assertEquals(10, snapshot.ceiling());
    assertEquals(3, snapshot.spent());
    assertTrue(
        jdbc.queryForObject(
            "SELECT submitted FROM inference_admissions WHERE budget_id=?",
            Boolean.class,
            HISTORICAL));
    Budget.Claim next = repository.claim(snapshot.id(), 0).orElseThrow();
    assertEquals(4, next.ordinal());
    io.aeyer.plowshare.server.llm.dispatch.InferenceBinding binding =
        new io.aeyer.plowshare.server.llm.dispatch.InferenceBinding(
            UUID.randomUUID(), HISTORICAL_OPERATION, HISTORICAL, next.ordinal(), next.limit());
    io.aeyer.plowshare.server.llm.dispatch.InferenceAdmissions admissions =
        new io.aeyer.plowshare.server.llm.accounting.JdbcInferenceAdmissions(jdbc);
    admissions.retain(binding);
    admissions.require(binding);
    assertEquals(
        "cnv_historical_input",
        jdbc.queryForObject(
            "SELECT source_conversation FROM operation_inference_budgets WHERE operation_id=?",
            String.class,
            HISTORICAL_OPERATION));
  }

  @Test
  void concurrentClaimsShareOneCeilingAcrossIndependentRepositoryObjects() throws Exception {
    AllowanceId id = new AllowanceId(UUID.randomUUID());
    new JdbcAllowanceRepository(jdbc).retain(new AllowanceSnapshot(id, 3, 0));
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Boolean>> attempts = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        attempts.add(
            threads.submit(
                () -> {
                  start.await();
                  return new JdbcAllowanceRepository(jdbc).claim(id, 0).isPresent();
                }));
      start.countDown();
      int accepted = 0;
      for (Future<Boolean> attempt : attempts) if (attempt.get()) accepted++;
      assertEquals(3, accepted);
    }
    assertEquals(3, new JdbcAllowanceRepository(jdbc).find(id).orElseThrow().spent());
  }

  @Test
  void unlimitedDisabledAndAutomaticIncreasesKeepPolicyAndUsageSeparate() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    Budget unlimited = repository.bind(Budget.none());
    assertTrue(unlimited.trySpend());
    assertTrue(unlimited.trySpend());
    Budget restored = repository.restore(new AllowanceId(unlimited.identity()));
    assertFalse(restored.capped());
    assertEquals(2, restored.spent());
    Budget disabled = repository.bind(Budget.noCalls());
    assertFalse(disabled.trySpend());
    assertThrows(
        IllegalStateException.class,
        () -> repository.ceiling(new AllowanceId(disabled.identity()), 10));
    Budget finite = repository.bind(Budget.of(1));
    assertTrue(finite.trySpend());
    repository.increase(new AllowanceId(finite.identity()), 4, 1);
    assertEquals(1, repository.restore(new AllowanceId(finite.identity())).spent());
    assertTrue(finite.trySpend());
    assertEquals(4, finite.limit());
  }

  @Test
  void anExhaustedStaleReaderSeesSharedUsageBeforeAnApplicableAutomaticIncrease() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    Budget first = repository.bind(Budget.of(1));
    Budget stale = repository.restore(first.snapshot().id());
    assertTrue(first.trySpend());
    assertTrue(stale.tryClaim().isEmpty());
    assertEquals(1, stale.spent());
    assertTrue(stale.exhausted());
    repository.increase(first.snapshot().id(), 4, stale.spent());
    assertEquals(2, stale.tryClaim().orElseThrow().ordinal());
    assertEquals(4, stale.limit());
    assertEquals(3, first.tryClaim().orElseThrow().ordinal());
  }

  @Test
  void admissionAcceptsReservedClaimsWithoutChargingAgainAndRejectsStaleClaims() {
    AllowanceRepository repository = new JdbcAllowanceRepository(jdbc);
    AllowanceId id = new AllowanceId(UUID.randomUUID());
    repository.retain(new AllowanceSnapshot(id, 2, 0));
    Budget.Claim claimed = repository.claim(id, 0).orElseThrow();
    repository.accept(claimed);
    assertEquals(1, repository.find(id).orElseThrow().spent());
    repository.accept(new Budget.Claim(id.value(), 2, 2));
    assertEquals(2, repository.find(id).orElseThrow().spent());
    assertThrows(
        RuntimeException.class, () -> repository.accept(new Budget.Claim(id.value(), 3, 2)));
  }
}
