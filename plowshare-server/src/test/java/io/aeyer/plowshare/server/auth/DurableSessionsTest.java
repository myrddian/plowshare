package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("full-db")
@Testcontainers
class DurableSessionsTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static DriverManagerDataSource source;
  private static JdbcTemplate jdbc;
  private final Instant now = Instant.parse("2026-10-02T00:00:00Z");

  @BeforeAll
  static void migrate() {
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(source).load().migrate();
    jdbc = new JdbcTemplate(source);
  }

  @BeforeEach
  void clean() {
    jdbc.execute("TRUNCATE admins CASCADE");
    new AdminStore(jdbc).create("operator", "password-hash");
  }

  private TokenStore server(Instant time) {
    Clock clock = Clock.fixed(time, ZoneOffset.UTC);
    return new TokenStore(clock, Duration.ofMinutes(15), Duration.ofDays(7), Duration.ofSeconds(10))
        .withDurableSessions(
            new JdbcDurableSessions(
                jdbc,
                new DataSourceTransactionManager(source),
                clock,
                Duration.ofMinutes(15),
                Duration.ofDays(7)));
  }

  @Test
  void production_auth_configuration_uses_the_durable_store() {
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("jdbc", jdbc);
    beans.addBean("transactions", new DataSourceTransactionManager(source));
    var properties = new AuthProperties();
    var config = new AuthConfig();
    TokenStore one =
        config.tokenStore(
            properties,
            beans.getBeanProvider(JdbcTemplate.class),
            beans.getBeanProvider(
                org.springframework.transaction.PlatformTransactionManager.class));
    TokenStore.Pair pair = one.issuePair("operator", false);
    TokenStore restarted =
        config.tokenStore(
            properties,
            beans.getBeanProvider(JdbcTemplate.class),
            beans.getBeanProvider(
                org.springframework.transaction.PlatformTransactionManager.class));
    assertTrue(restarted.validAccess(pair.access()));
    assertEquals("operator", restarted.handleFor(pair.access()).orElseThrow());
  }

  @Test
  void restart_retains_identity_rotation_restrictions_and_only_digests() {
    TokenStore.Pair first = server(now).issuePair("operator", false);
    TokenStore restarted = server(now.plusSeconds(60));
    assertTrue(restarted.validAccess(first.access()));
    assertEquals("operator", restarted.handleFor(first.access()).orElseThrow());
    TokenStore.Pair next = restarted.refresh(first.refresh()).orElseThrow();
    assertTrue(server(now.plusSeconds(90)).validAccess(next.access()));
    assertTrue(
        jdbc.queryForList("SELECT digest FROM auth_session_grants", String.class).stream()
            .noneMatch(
                value ->
                    value.equals(first.access())
                        || value.equals(first.refresh())
                        || value.equals(next.refresh())));
    TokenStore.Pair restricted = server(now).issuePair("operator", true);
    assertTrue(restarted.chainIsRestricted(restricted.access()));
    TokenStore bootstrap = server(now);
    TokenStore.Pair transientPair = bootstrap.issuePair();
    assertFalse(restarted.validAccess(transientPair.access()));
  }

  @Test
  void reuse_and_logout_remain_revoked_after_restart() {
    TokenStore one = server(now);
    TokenStore.Pair first = one.issuePair("operator", false);
    TokenStore.Pair next = server(now).refresh(first.refresh()).orElseThrow();
    assertTrue(server(now).refresh(first.refresh()).isEmpty());
    assertFalse(server(now).validAccess(next.access()));
    assertTrue(server(now).refresh(next.refresh()).isEmpty());
    TokenStore.Pair other = one.issuePair("operator", false);
    server(now).revoke(other.access());
    assertFalse(server(now).validAccess(other.access()));
    assertTrue(server(now).refresh(other.refresh()).isEmpty());
  }

  @Test
  void expired_access_can_renew_but_expired_refresh_cannot() {
    TokenStore.Pair first = server(now).issuePair("operator", false);
    assertFalse(server(now.plusSeconds(901)).validAccess(first.access()));
    assertTrue(server(now.plusSeconds(901)).refresh(first.refresh()).isPresent());
    TokenStore.Pair other = server(now).issuePair("operator", false);
    assertTrue(server(now.plus(Duration.ofDays(8))).refresh(other.refresh()).isEmpty());
  }

  @Test
  void concurrent_servers_have_one_rotation_winner_and_reuse_revokes_it() throws Exception {
    TokenStore.Pair first = server(now).issuePair("operator", false);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var a = executor.submit(() -> server(now).refresh(first.refresh()));
      var b = executor.submit(() -> server(now).refresh(first.refresh()));
      var one = a.get();
      var two = b.get();
      assertNotEquals(one.isPresent(), two.isPresent());
      var winner = one.orElseGet(two::orElseThrow);
      assertFalse(server(now).validAccess(winner.access()));
    }
  }
}
