package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** PostgreSQL foreign-key locking must compose with the durable event commit-order fence. */
@Tag("full-db")
@Testcontainers
class InformationEventConcurrencyTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:0.8.7-pg16-bookworm");

  static DriverManagerDataSource source;
  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @Test
  void taggingAndWorkerCompletionPublishWithoutInvertingRevisionAndEventLocks() throws Exception {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy',?,'source')",
        resource,
        resource.toString());
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'fixture','text/plain','fixture',1)",
        revision,
        resource);
    var repository = new JdbcInformationCatalogueRepository(jdbc, Clock.systemUTC());
    var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    var locked = new CountDownLatch(1);
    var publishWorker = new CountDownLatch(1);
    String writerName = "information-tag-" + UUID.randomUUID();
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var worker =
          threads.submit(
              () ->
                  transaction.execute(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT id FROM information_revisions WHERE id=? FOR UPDATE",
                            UUID.class,
                            revision);
                        locked.countDown();
                        await(publishWorker);
                        repository.event(revision, 1, null, "derive", "ready", "");
                        return null;
                      }));
      assertTrue(locked.await(10, TimeUnit.SECONDS));
      var tags =
          threads.submit(
              () ->
                  transaction.execute(
                      status -> {
                        jdbc.queryForObject(
                            "SELECT set_config('application_name',?,true)",
                            String.class,
                            writerName);
                        repository.event(revision, 1, null, "metadata", "tags", "");
                        return null;
                      }));
      try {
        // Observe an actual lock wait, not a scheduling delay. Before the fix, the
        // writer held the advisory fence here while its INSERT waited on the FK.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
                Integer.class,
                writerName)
            == 0) {
          assertTrue(System.nanoTime() < deadline, "tag transaction never waited on the revision");
          Thread.sleep(10);
        }
      } finally {
        publishWorker.countDown();
      }
      worker.get(10, TimeUnit.SECONDS);
      tags.get(10, TimeUnit.SECONDS);
    } finally {
      publishWorker.countDown();
    }
    assertEquals(
        java.util.List.of("ready", "tags"),
        jdbc.queryForList(
            "SELECT action FROM information_events WHERE revision_id=? ORDER BY sequence",
            String.class,
            revision));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS))
        throw new AssertionError("worker publication was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }
}
