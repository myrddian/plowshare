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
  void concurrent_identical_intents_survive_restart_with_one_rotation() throws Exception {
    var parent = server(now).issuePair("operator", false);
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var barrier = new java.util.concurrent.CyclicBarrier(2);
      var a =
          executor.submit(
              () -> {
                barrier.await();
                return server(now).refresh(parent.refresh(), intent).orElseThrow();
              });
      var b =
          executor.submit(
              () -> {
                barrier.await();
                return server(now).refresh(parent.refresh(), intent).orElseThrow();
              });
      var one = a.get();
      var two = b.get();
      assertTrue(one.pair().access().equals(two.pair().access()));
      assertTrue(one.pair().refresh().equals(two.pair().refresh()));
      var restarted = server(now.plusSeconds(5)).refresh(parent.refresh(), intent).orElseThrow();
      assertTrue(one.pair().access().equals(restarted.pair().access()));
      assertTrue(one.pair().refresh().equals(restarted.pair().refresh()));
      assertEquals(Duration.ofMinutes(15).minusSeconds(5), restarted.accessLifetime());
      assertEquals(Duration.ofDays(7).minusSeconds(5), restarted.refreshLifetime());
      assertEquals(
          4, jdbc.queryForObject("SELECT count(*) FROM auth_session_grants", Integer.class));
      assertEquals(
          1, jdbc.queryForObject("SELECT count(*) FROM auth_refresh_receipts", Integer.class));
      assertEquals(
          now.plus(Duration.ofDays(7)),
          jdbc.queryForObject(
                  "SELECT expires_at FROM auth_session_chains", java.sql.Timestamp.class)
              .toInstant());
      assertTrue(server(now).validAccess(one.pair().access()));
      String stored =
          jdbc.queryForObject(
              "SELECT row_to_json(r)::text FROM auth_refresh_receipts r", String.class);
      assertFalse(stored.contains(parent.refresh()));
      assertFalse(stored.contains(one.pair().access()));
      assertFalse(stored.contains(one.pair().refresh()));
    }
  }

  @Test
  void differing_missing_and_expired_intents_remain_reuse() {
    for (int mode = 0; mode < 3; mode++) {
      var parent = server(now).issuePair("operator", false);
      var intent = new RefreshIntent(java.util.UUID.randomUUID());
      var one = server(now).refresh(parent.refresh(), intent).orElseThrow();
      boolean refused =
          switch (mode) {
            case 0 ->
                server(now)
                    .refresh(parent.refresh(), new RefreshIntent(java.util.UUID.randomUUID()))
                    .isEmpty();
            case 1 -> server(now).refresh(parent.refresh()).isEmpty();
            default -> server(now.plusSeconds(30)).refresh(parent.refresh(), intent).isEmpty();
          };
      assertTrue(refused);
      assertFalse(server(now).validAccess(one.pair().access()));
    }
  }

  @Test
  void logout_disable_and_session_version_changes_fence_receipts() {
    var parent = server(now).issuePair("operator", false);
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = server(now).refresh(parent.refresh(), intent).orElseThrow();
    server(now).revoke(one.pair().access());
    assertTrue(server(now).refresh(parent.refresh(), intent).isEmpty());
    parent = server(now).issuePair("operator", false);
    one = server(now).refresh(parent.refresh(), intent).orElseThrow();
    jdbc.update("UPDATE admins SET enabled=FALSE WHERE handle='operator'");
    assertTrue(server(now).refresh(parent.refresh(), intent).isEmpty());
    assertFalse(server(now).validAccess(one.pair().access()));
    jdbc.update(
        "UPDATE admins SET enabled=TRUE, session_version=session_version+1 WHERE handle='operator'");
    assertTrue(server(now).refresh(parent.refresh(), intent).isEmpty());
    parent = server(now).issuePair("operator", false);
    one = server(now).refresh(parent.refresh(), intent).orElseThrow();
    jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle='operator'");
    assertTrue(server(now).refresh(parent.refresh(), intent).isEmpty());
    assertFalse(server(now).validAccess(one.pair().access()));
  }

  @Test
  void later_rotation_prevents_old_intent_from_returning_stale_cookies() {
    var parent = server(now).issuePair("operator", false);
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = server(now).refresh(parent.refresh(), intent).orElseThrow();
    var later =
        server(now)
            .refresh(one.pair().refresh(), new RefreshIntent(java.util.UUID.randomUUID()))
            .orElseThrow();
    assertTrue(server(now).refresh(parent.refresh(), intent).isEmpty());
    assertFalse(server(now).validAccess(later.pair().access()));
  }

  @Test
  void a_receipt_waiting_for_the_chain_lock_rechecks_account_authority() throws Exception {
    var parent = server(now).issuePair("operator", false);
    var intent = new RefreshIntent(java.util.UUID.randomUUID());
    var one = server(now).refresh(parent.refresh(), intent).orElseThrow();
    try (var held = source.getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      held.setAutoCommit(false);
      try (var statement =
          held.prepareStatement(
              "SELECT c.id FROM auth_session_chains c JOIN auth_session_grants g ON g.chain_id=c.id WHERE g.digest=? FOR UPDATE OF c")) {
        statement.setString(1, Tokens.hash(parent.refresh()));
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
        }
      }
      var waiting = executor.submit(() -> server(now).refresh(parent.refresh(), intent));
      try {
        // Wait for PostgreSQL to confirm the reader holds a pre-revocation snapshot
        // and is blocked on our chain row, rather than relying on thread scheduling.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        boolean blocked = false;
        while (System.nanoTime() < deadline) {
          blocked =
              Boolean.TRUE.equals(
                  jdbc.queryForObject(
                      "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT c.id, g.expires_at%')",
                      Boolean.class));
          if (blocked) break;
          Thread.sleep(10);
        }
        assertTrue(blocked, "refresh did not wait on the chain lock");
        jdbc.update("UPDATE admins SET session_version=session_version+1 WHERE handle='operator'");
      } finally {
        held.commit();
      }
      assertTrue(waiting.get(5, java.util.concurrent.TimeUnit.SECONDS).isEmpty());
      assertFalse(server(now).validAccess(one.pair().access()));
      assertEquals(
          4, jdbc.queryForObject("SELECT count(*) FROM auth_session_grants", Integer.class));
    }
  }

  @Test
  void receipt_failure_rolls_back_spending_and_successor_issuance() {
    var parent = server(now).issuePair("operator", false);
    jdbc.execute(
        "ALTER TABLE auth_refresh_receipts ADD CONSTRAINT reject_test_receipt CHECK (FALSE) NOT VALID");
    try {
      assertThrows(
          org.springframework.dao.DataIntegrityViolationException.class,
          () ->
              server(now)
                  .refresh(parent.refresh(), new RefreshIntent(java.util.UUID.randomUUID())));
      assertEquals(
          2, jdbc.queryForObject("SELECT count(*) FROM auth_session_grants", Integer.class));
      assertFalse(
          jdbc.queryForObject(
              "SELECT spent FROM auth_session_grants WHERE digest=?",
              Boolean.class,
              Tokens.hash(parent.refresh())));
      assertEquals(
          0, jdbc.queryForObject("SELECT count(*) FROM auth_refresh_receipts", Integer.class));
      assertTrue(server(now).validAccess(parent.access()));
    } finally {
      jdbc.execute("ALTER TABLE auth_refresh_receipts DROP CONSTRAINT reject_test_receipt");
    }
    assertTrue(server(now).refresh(parent.refresh()).isPresent());
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
