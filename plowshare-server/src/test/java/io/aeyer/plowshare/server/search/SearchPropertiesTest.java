package io.aeyer.plowshare.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
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

/**
 * The four live keys, read at the moment they are asked rather than the moment this server booted.
 *
 * <p>Follows {@code DocumentsPropertiesTest} and {@code RuntimeConfigTest}: Testcontainers and a
 * real Postgres, because the point of these tests is that a write through {@link RuntimeConfig} is
 * visible to the next read through {@link SearchProperties}, and a stand-in map would be asserting
 * that this class reads a map the test itself decided how to implement.
 *
 * <p>No Spring context. The wiring — {@code @Autowired(required = false)} on {@link
 * SearchProperties#setLive}, and {@code @ConfigurationProperties} binding the four setters — is
 * Spring's own and is exercised wherever this server boots; what needs pinning here is each
 * accessor's arithmetic, and a properties object plus a store is the whole of it.
 */
@Testcontainers
class SearchPropertiesTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static JdbcTemplate jdbc;

  private RuntimeConfig runtimeConfig;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void freshMap() {
    jdbc.execute("TRUNCATE TABLE runtime_config");
    runtimeConfig = new RuntimeConfig(jdbc);
  }

  private SearchProperties properties() {
    SearchProperties props = new SearchProperties();
    props.setLive(runtimeConfig);
    return props;
  }

  @Test
  void the_ladder_is_read_from_the_map_at_the_moment_it_is_asked() {
    SearchProperties props = properties();
    props.setLadder("searxng");
    assertIterableEquals(List.of("searxng"), props.ladderNow());

    runtimeConfig.put("plowshare.search.ladder", "searxng,brave", "test");

    assertIterableEquals(List.of("searxng", "brave"), props.ladderNow());
  }

  @Test
  void a_ladder_entry_is_trimmed_and_a_blank_entry_is_dropped() {
    SearchProperties props = properties();
    runtimeConfig.put("plowshare.search.ladder", " searxng , , brave ", "test");

    assertIterableEquals(List.of("searxng", "brave"), props.ladderNow());
  }

  @Test
  void an_empty_ladder_is_an_empty_list_and_not_a_list_holding_one_blank() {
    SearchProperties props = properties();
    runtimeConfig.put("plowshare.search.ladder", "", "test");

    assertTrue(props.ladderNow().isEmpty());
  }

  @Test
  void the_ignore_list_is_live_too() {
    SearchProperties props = properties();
    runtimeConfig.put("plowshare.search.ignored-domains", "foxnews.com,*.example.org", "test");

    assertIterableEquals(List.of("foxnews.com", "*.example.org"), props.ignoredDomainsNow());
  }

  @Test
  void an_ignore_pattern_is_trimmed_and_lowercased_because_a_host_is() {
    SearchProperties props = properties();
    runtimeConfig.put("plowshare.search.ignored-domains", " FoxNews.com , *.Example.ORG ", "test");

    assertIterableEquals(List.of("foxnews.com", "*.example.org"), props.ignoredDomainsNow());
  }

  @Test
  void an_empty_ignore_list_is_an_empty_list_and_never_a_list_holding_one_blank() {
    SearchProperties props = properties();
    runtimeConfig.put("plowshare.search.ignored-domains", "", "test");

    assertTrue(props.ignoredDomainsNow().isEmpty());
  }

  @Test
  void a_threshold_that_will_not_parse_falls_back_to_the_bound_value() {
    SearchProperties props = properties();
    props.setFailureThreshold(5);
    runtimeConfig.put("plowshare.search.failure-threshold", "not a number", "test");

    assertEquals(5, props.failureThresholdNow());
  }

  @Test
  void the_threshold_is_live_when_it_parses() {
    SearchProperties props = properties();
    props.setFailureThreshold(5);
    runtimeConfig.put("plowshare.search.failure-threshold", "8", "test");

    assertEquals(8, props.failureThresholdNow());
  }

  @Test
  void the_ttl_is_live_and_parses_a_duration() {
    SearchProperties props = properties();
    props.setResultSetTtl(Duration.ofMinutes(30));
    runtimeConfig.put("plowshare.search.result-set-ttl", "PT5M", "test");

    assertEquals(Duration.ofMinutes(5), props.resultSetTtlNow());
  }

  @Test
  void a_ttl_that_will_not_parse_falls_back_to_the_bound_value() {
    SearchProperties props = properties();
    props.setResultSetTtl(Duration.ofMinutes(30));
    runtimeConfig.put("plowshare.search.result-set-ttl", "not a duration", "test");

    assertEquals(Duration.ofMinutes(30), props.resultSetTtlNow());
  }

  @Test
  void a_properties_object_with_no_runtime_config_returns_its_bound_values() {
    SearchProperties alone = new SearchProperties();
    alone.setLadder("searxng");
    alone.setIgnoredDomains("foxnews.com");
    alone.setFailureThreshold(5);
    alone.setResultSetTtl(Duration.ofMinutes(30));

    assertIterableEquals(List.of("searxng"), alone.ladderNow());
    assertIterableEquals(List.of("foxnews.com"), alone.ignoredDomainsNow());
    assertEquals(5, alone.failureThresholdNow());
    assertEquals(Duration.ofMinutes(30), alone.resultSetTtlNow());
  }
}
