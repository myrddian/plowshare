package io.aeyer.plowshare.server.orchestrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A run's check, stored once. Spec 2026-09-26, harness-checked stages, task 2. */
@Testcontainers
class OrchestrationChecksTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private OrchestrationChecks checks;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM orchestration_checks");
    checks = new OrchestrationChecks(jdbc);
  }

  private static OrchestrationChecks.Check check(String run, String consent, String approval) {
    return new OrchestrationChecks.Check(
        run,
        List.of("python", "-m", "pytest", "-q"),
        "local",
        "/repo",
        consent,
        approval,
        Instant.parse("2026-09-26T01:00:00Z"));
  }

  @Test
  void a_check_is_set_once_and_read_back() {
    assertTrue(checks.set(check("orc_1", OrchestrationChecks.APPROVAL, "apr_1")));
    assertFalse(checks.set(check("orc_1", OrchestrationChecks.OPEN, null)), "set once");

    OrchestrationChecks.Check found = checks.find("orc_1").orElseThrow();
    assertEquals(List.of("python", "-m", "pytest", "-q"), found.argv());
    assertEquals("apr_1", found.approval());
    assertEquals(OrchestrationChecks.APPROVAL, found.consent());
    assertTrue(checks.find("orc_2").isEmpty());
  }

  /** Final review F2: a check whose approval was denied is cleared by that approval's id only. */
  @Test
  void a_check_is_cleared_only_under_the_approval_it_was_set_with() {
    checks.set(check("orc_1", OrchestrationChecks.APPROVAL, "apr_1"));

    assertFalse(checks.clear("orc_1", "apr_other"), "another approval clears nothing");
    assertTrue(checks.find("orc_1").isPresent());
    assertTrue(checks.clear("orc_1", "apr_1"));
    assertTrue(checks.find("orc_1").isEmpty());
    assertTrue(
        checks.set(check("orc_1", OrchestrationChecks.APPROVAL, "apr_2")),
        "and a new one may then be set");
  }
}
