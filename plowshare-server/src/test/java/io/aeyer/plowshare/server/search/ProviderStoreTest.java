package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.search.AnswerStatus;
import io.aeyer.plowshare.protocol.search.CostClass;
import io.aeyer.plowshare.protocol.search.NetworkTier;
import io.aeyer.plowshare.protocol.search.ProviderFacts;
import io.aeyer.plowshare.protocol.search.Verb;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ProviderStore} against a real Postgres — the upsert-moves-the-url behaviour and the
 * failure counter both live in the schema (V33), not in this class, so a stand-in that
 * reimplemented either would be testing itself rather than the store. Follows {@code
 * RuntimeConfigTest}'s shape: no Spring context, a Testcontainers Postgres migrated once for the
 * class, and a fresh store over a truncated table per test.
 */
@Tag("full-db")
@Testcontainers
class ProviderStoreTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private ProviderStore store;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void emptyRegistry() {
    jdbc.execute("TRUNCATE TABLE search_providers");
    store = new ProviderStore(jdbc);
  }

  private static ProviderFacts facts(String key) {
    return new ProviderFacts(
        key,
        key,
        "0.1.0",
        "d",
        Set.of(Verb.SEARCH),
        CostClass.FREE,
        NetworkTier.INTERNAL_NETWORK,
        25,
        512,
        true);
  }

  @Test
  void a_registration_reads_back_as_it_was_written() {
    store.upsert("searxng", "http://localhost:8086", facts("searxng"));

    Registration r = store.find("searxng").orElseThrow();

    assertEquals("http://localhost:8086", r.baseUrl());
    assertEquals(CostClass.FREE, r.facts().costClass());
    assertTrue(r.facts().domainExclusion());
    assertEquals(0, r.consecutiveFailures());
  }

  @Test
  void registering_the_same_key_twice_moves_the_url_rather_than_adding_a_row() {
    store.upsert("searxng", "http://old:8086", facts("searxng"));

    store.upsert("searxng", "http://new:8086", facts("searxng"));

    assertEquals(1, store.all().size());
    assertEquals("http://new:8086", store.find("searxng").orElseThrow().baseUrl());
  }

  @Test
  void a_failure_increments_and_a_success_clears() {
    store.upsert("brave", "http://localhost:8084", facts("brave"));

    store.recordOutcome("brave", AnswerStatus.FAILED, "timeout");
    store.recordOutcome("brave", AnswerStatus.FAILED, "timeout");
    assertEquals(2, store.find("brave").orElseThrow().consecutiveFailures());

    store.recordOutcome("brave", AnswerStatus.SUCCESS, null);
    assertEquals(0, store.find("brave").orElseThrow().consecutiveFailures());
  }

  @Test
  void re_registering_clears_the_failure_count_because_it_is_a_new_deployment() {
    store.upsert("brave", "http://localhost:8084", facts("brave"));
    store.recordOutcome("brave", AnswerStatus.FAILED, "timeout");

    store.upsert("brave", "http://localhost:8084", facts("brave"));

    assertEquals(0, store.find("brave").orElseThrow().consecutiveFailures());
  }

  @Test
  void removing_one_that_is_not_there_says_so_rather_than_throwing() {
    assertFalse(store.remove("never-registered"));
  }
}
