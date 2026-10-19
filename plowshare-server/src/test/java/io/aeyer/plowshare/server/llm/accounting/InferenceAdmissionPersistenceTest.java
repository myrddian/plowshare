package io.aeyer.plowshare.server.llm.accounting;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.llm.dispatch.InferenceBinding;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real uniqueness/immutability and concurrent single submission, not runtime policy. */
@Tag("full-db")
@Testcontainers
class InferenceAdmissionPersistenceTest {
  @Container
  static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @Test
  void evidence_survives_restart_and_cannot_be_rebound_or_replayed() {
    var records = new JdbcInferenceAdmissions(jdbc);
    var b = new InferenceBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 1);
    records.retain(b);
    records.submitted(b);
    var restarted = new JdbcInferenceAdmissions(jdbc);
    restarted.require(b);
    assertThrows(IllegalStateException.class, () -> restarted.submitted(b));
    assertThrows(
        RuntimeException.class,
        () ->
            records.retain(
                new InferenceBinding(UUID.randomUUID(), b.operation(), UUID.randomUUID(), 2, 2)));
    UUID call = UUID.randomUUID();
    restarted.accounted(b, call);
    restarted.accounted(b, call);
    assertThrows(IllegalStateException.class, () -> restarted.accounted(b, UUID.randomUUID()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE inference_admissions SET operation_id=? WHERE id=?",
                UUID.randomUUID(),
                b.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            records.retain(
                new InferenceBinding(
                    UUID.randomUUID(), b.operation(), b.budget(), b.ordinal(), b.limit())));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM inference_admissions WHERE id=?", b.id()));
  }

  @Test
  void concurrent_submission_has_exactly_one_winner() throws Exception {
    var records = new JdbcInferenceAdmissions(jdbc);
    var b = new InferenceBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null);
    records.retain(b);
    var start = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new ArrayList<Future<Boolean>>();
      for (int i = 0; i < 4; i++)
        tasks.add(
            threads.submit(
                () -> {
                  start.await();
                  try {
                    records.submitted(b);
                    return true;
                  } catch (IllegalStateException refused) {
                    return false;
                  }
                }));
      start.countDown();
      int winners = 0;
      for (var result : tasks) if (result.get(10, TimeUnit.SECONDS)) winners++;
      assertEquals(1, winners);
    }
  }
}
