package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.archive.ClientProjects;
import io.aeyer.plowshare.server.archive.ConversationStore;
import io.aeyer.plowshare.server.archive.JdbcJobLog;
import io.aeyer.plowshare.server.archive.ProjectStore;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.session.JdbcWorkspaceBindingRepository;
import io.aeyer.plowshare.server.session.Presence;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The new migrations, PostgreSQL conflict predicates, JSON mapping and recovery claim atomicity.
 */
@Tag("full-db")
@Testcontainers
class WorkspacePersistenceTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  @TempDir Path configuration;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate transactions;

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
  void reset() {
    AuthorityFixtures.truncate(
        jdbc, "TRUNCATE TABLE projects, admins, workspace_recovery_bindings CASCADE");
  }

  @Test
  void recovery_binding_retains_the_first_session_and_refuses_identity_changes() {
    var bindings = new JdbcWorkspaceBindingRepository(jdbc, transactions);
    String key = "a".repeat(64);
    assertEquals(
        "original", bindings.bind(key, "original", "alice", "machine", "/work", "project"));
    assertEquals(
        "original", bindings.bind(key, "reopened", "alice", "machine", "/work", "project"));
    assertThrows(
        CallerFault.class,
        () -> bindings.bind(key, "foreign", "bob", "machine", "/work", "project"));
    assertThrows(
        CallerFault.class,
        () -> bindings.bind(key, "moved", "alice", "machine", "/different", "project"));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM workspace_recovery_bindings", Integer.class));
  }

  @Test
  void recovered_project_listing_batches_verified_attachments_without_borrowing_another_scope() {
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','fixture')");
    var projects =
        new ProjectStore(
            jdbc,
            configuration.resolve("config.json"),
            configuration.resolve("sampling"),
            configuration.resolve("token"),
            configuration.resolve("exports"),
            configuration.resolve("data"));
    var first =
        projects.attachClient("first", "/fixture/first", "machine", "logical-first", "alice");
    var second =
        projects.attachClient("second", "/fixture/second", "machine", "logical-second", "alice");
    var names =
        projects.allForSessions("alice", List.of("logical-first", "logical-second")).stream()
            .map(row -> row.name())
            .filter(ClientProjects::privateProject)
            .sorted()
            .toList();
    assertEquals(List.of(first.name(), second.name()).stream().sorted().toList(), names);
    assertEquals(
        List.of(first.name()),
        projects.allForSession("alice", "logical-first").stream()
            .map(row -> row.name())
            .filter(ClientProjects::privateProject)
            .toList());
  }

  @Test
  void a_lock_wait_expires_without_replacing_the_original_binding() throws Exception {
    var bindings =
        new JdbcWorkspaceBindingRepository(
            jdbc, new TransactionTemplate(transactions.getTransactionManager()));
    String key = "b".repeat(64);
    bindings.bind(key, "original", "alice", "machine", "/work", "project");
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var holding =
          threads.submit(
              () ->
                  transactions.execute(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT session_id FROM workspace_recovery_bindings WHERE key_hash=? FOR UPDATE",
                            String.class,
                            key);
                        locked.countDown();
                        try {
                          assertTrue(release.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(interrupted);
                        }
                        return true;
                      }));
      try {
        assertTrue(locked.await(1, TimeUnit.SECONDS));
        var limited =
            new JdbcWorkspaceBindingRepository(
                jdbc,
                new TransactionTemplate(transactions.getTransactionManager()),
                Duration.ofMillis(100));
        long started = System.nanoTime();
        assertThrows(
            DataAccessException.class,
            () -> limited.bind(key, "replacement", "alice", "machine", "/work", "project"));
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos());
      } finally {
        release.countDown();
      }
      assertTrue(holding.get(2, TimeUnit.SECONDS));
    }
    assertEquals(
        "original", bindings.bind(key, "reopened", "alice", "machine", "/work", "project"));
  }

  @Test
  void a_checkpoint_round_trips_completed_work_and_can_be_claimed_only_once() throws Exception {
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('alice','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('project')");
    Budget budget = Budget.of(12);
    budget.trySpend();
    String root = new ConversationStore(jdbc).open(Home.of("project"), budget, null, "alice").id();
    new JdbcJobLog(jdbc)
        .started("job_fixture", "worker", Home.of("project"), Instant.now(), root, null);
    var repository = new JdbcWorkspacePauseRepository(jdbc, transactions);
    var checkpoint =
        new BudgetCheckpoint(
            "files",
            "alice",
            List.of(new BudgetCheckpoint.Frame(root, "worker", 1, 1, 8, List.of())));
    var outcome = new Outcome(Outcome.Ending.ANSWERED, "retained answer", 1, 1, "");
    var snapshot =
        new WorkspacePauseRepository.Snapshot(
            "job_fixture",
            checkpoint,
            budget.identity().toString(),
            List.of(new Presence("files", "machine", "/work", "project")),
            List.of("a".repeat(64)),
            List.of(1),
            "waiting for files",
            false,
            root,
            outcome,
            null);
    repository.suspended(snapshot, "first-process");
    assertEquals(0, new JdbcJobLog(jdbc).pruneStartedBefore(Instant.now().plusSeconds(1)));
    assertFalse(repository.claim("job_fixture", 1, "recovery-process"));
    jdbc.update("UPDATE workspace_pauses SET lease_until=clock_timestamp()-interval '1 second'");
    assertEquals(snapshot, repository.find("job_fixture").orElseThrow().snapshot());
    assertTrue(repository.awaiting(root));
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var claims =
          threads.invokeAll(
              List.of(
                  () -> repository.claim("job_fixture", 1, "recovery-process"),
                  () -> repository.claim("job_fixture", 1, "recovery-process")));
      assertEquals(
          1,
          claims.stream()
              .filter(
                  claim -> {
                    try {
                      return Boolean.TRUE.equals(claim.get());
                    } catch (Exception failed) {
                      throw new AssertionError(failed);
                    }
                  })
              .count());
    }
    repository.started("job_fixture", 1, "recovery-process");
    assertFalse(repository.claim("job_fixture", 1, "recovery-process"));
    repository.blocked("job_fixture", "Outcome needs inspection");
    var blocked = repository.find("job_fixture").orElseThrow();
    assertTrue(blocked.snapshot().blocked());
    assertEquals("Outcome needs inspection", blocked.snapshot().reason());
    repository.completed("job_fixture", "recovery-process");
    assertFalse(repository.awaiting(root));
    assertTrue(repository.find("job_fixture").isEmpty());
    repository.suspended(snapshot, "recovery-process");
    assertTrue(repository.cancel("job_fixture").isPresent());
    assertFalse(repository.awaiting(root));
    assertFalse(repository.renew("job_fixture", "recovery-process"));
    assertThrows(
        IllegalStateException.class, () -> repository.suspended(snapshot, "recovery-process"));
    assertEquals("cancelled", repository.find("job_fixture").orElseThrow().state());
    assertEquals(1, new JdbcJobLog(jdbc).pruneStartedBefore(Instant.now().plusSeconds(1)));
    assertTrue(repository.find("job_fixture").isEmpty());
  }
}
