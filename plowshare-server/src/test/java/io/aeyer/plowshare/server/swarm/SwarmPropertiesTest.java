package io.aeyer.plowshare.server.swarm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.aeyer.plowshare.server.config.RuntimeConfig;
import java.time.Duration;
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
 * The live keys, read at the moment they are asked. Every assertion writes a value that differs
 * from the bound one first — a test writing back the bound value would pass whether the accessor
 * consulted the map or not ({@code Live}'s own warning).
 */
@Testcontainers
class SwarmPropertiesTest {

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

  @Test
  void the_quantum_is_read_from_the_map_when_asked() {
    SwarmProperties props = new SwarmProperties();
    props.setLive(runtimeConfig);
    props.setQuantum(4);
    assertEquals(4, props.quantumNow());
    runtimeConfig.put("plowshare.swarm.quantum", "2", "test");
    assertEquals(2, props.quantumNow());
  }

  @Test
  void a_quantum_below_one_in_the_map_falls_back_to_the_bound_one() {
    SwarmProperties props = new SwarmProperties();
    props.setLive(runtimeConfig);
    props.setQuantum(4);
    runtimeConfig.put("plowshare.swarm.quantum", "0", "test");
    assertEquals(4, props.quantumNow());
  }

  @Test
  void the_wait_warning_is_read_from_the_map_when_asked() {
    SwarmProperties props = new SwarmProperties();
    props.setLive(runtimeConfig);
    props.setWaitWarning(Duration.ofMinutes(10));
    assertEquals(Duration.ofMinutes(10), props.waitWarningNow());
    runtimeConfig.put("plowshare.swarm.wait-warning", "PT2M", "test");
    assertEquals(Duration.ofMinutes(2), props.waitWarningNow());
    runtimeConfig.put("plowshare.swarm.wait-warning", "soon", "test");
    assertEquals(Duration.ofMinutes(10), props.waitWarningNow());
  }

  @Test
  void the_wake_cap_is_read_from_the_map_and_below_one_falls_back() {
    SwarmProperties props = new SwarmProperties();
    props.setLive(runtimeConfig);
    props.setWakeCap(12);
    runtimeConfig.put("plowshare.swarm.wake-cap", "6", "test");
    assertEquals(6, props.wakeCapNow());
    runtimeConfig.put("plowshare.swarm.wake-cap", "0", "test");
    assertEquals(12, props.wakeCapNow());
  }

  @Test
  void the_closing_reserve_is_a_percent_read_from_the_map_and_out_of_range_falls_back() {
    SwarmProperties props = new SwarmProperties();
    props.setLive(runtimeConfig);
    props.setClosingReserve(10);
    runtimeConfig.put("plowshare.swarm.closing-reserve", "25", "test");
    assertEquals(25, props.closingReserveNow());
    runtimeConfig.put("plowshare.swarm.closing-reserve", "100", "test");
    assertEquals(10, props.closingReserveNow());
  }
}
