package io.aeyer.plowshare.server.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.server.access.JdbcIdentityRepository;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Actual PostgreSQL is required for socket cancellation, transaction rollback and recycled leases.
 */
@Tag("full-db")
@Testcontainers
@Timeout(15)
class ScopedSqlWorkBudgetDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static HikariDataSource main;
  private static HikariDataSource versions;
  private static JdbcTemplate setup;
  private final List<ScopedSqlWorkBudget> budgets = new ArrayList<>();
  private String account;

  @BeforeAll
  @Timeout(60)
  static void database() {
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate sql = new JdbcTemplate(source);
    sql.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    String schema = "sql_scope_" + UUID.randomUUID().toString().replace("-", "");
    sql.execute("CREATE SCHEMA " + schema);
    Properties properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    properties.setProperty("socketTimeout", "2");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    main = pool(source);
    versions = pool(source);
    setup = new JdbcTemplate(main);
    setup.execute("CREATE TABLE sql_scope_effects(id TEXT PRIMARY KEY)");
  }

  private static HikariDataSource pool(DataSource source) {
    HikariConfig config = new HikariConfig();
    config.setDataSource(source);
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    return new HikariDataSource(config);
  }

  @AfterAll
  static void closePools() {
    if (versions != null) versions.close();
    if (main != null) main.close();
  }

  @BeforeEach
  void account() {
    account = "sql_scope_" + UUID.randomUUID().toString().replace("-", "");
    setup.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES(?,'fixture',FALSE,FALSE)",
        account);
  }

  private ScopedSqlWorkBudget budget() {
    var budget =
        new ScopedSqlWorkBudget(
            new SqlWorkBudgetProperties(
                Duration.ofMillis(150), Duration.ofMillis(150), 8, 4, 16, 2),
            new SimpleMeterRegistry());
    budgets.add(budget);
    return budget;
  }

  @AfterEach
  void closeBudgets() {
    budgets.forEach(ScopedSqlWorkBudget::close);
  }

  @Test
  void identityAdmissionSqlIsCancelledBeforeAliasStorageAndDoesNotAbortTheBlocker()
      throws Exception {
    var budget = budget();
    var identities = new JdbcIdentityRepository(new JdbcTemplate(budget.decorate(main)));
    try (Connection blocker = main.getConnection();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE authority_accounts IN ACCESS EXCLUSIVE MODE");
      }
      try {
        var call =
            calls.submit(
                () -> budget.within(Duration.ofMillis(500), () -> identities.account(account)));
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
        assertFalse(blocker.isClosed());
      } finally {
        blocker.rollback();
      }
    }
    assertEquals(1, setup.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void independentProjectionLeaseUsesTheSameScopeAndRecyclesSafelyAfterCancellation()
      throws Exception {
    var budget = budget();
    var projections =
        new JdbcProjectionVersions(
            new JdbcTemplate(budget.decorate(main)), budget.decorate(versions), 128);
    try (Connection blocker = main.getConnection();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE snapshot_cache_identity IN ACCESS EXCLUSIVE MODE");
      }
      try {
        var call =
            calls.submit(
                () -> budget.within(Duration.ofMillis(500), projections::databaseIdentity));
        assertInstanceOf(
            ReadFailedException.class,
            assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
      } finally {
        blocker.rollback();
      }
    }
    assertEquals(1, new JdbcTemplate(versions).queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void timedOutTransactionRollsBackWithoutLateSuccessOrACommittedPartialEffect() throws Exception {
    var budget = budget();
    DataSource scoped = budget.decorate(main);
    JdbcTemplate sql = new JdbcTemplate(scoped);
    TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(scoped));
    String key = UUID.randomUUID().toString();
    long started = System.nanoTime();
    try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var call =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofMillis(500),
                      () ->
                          transactions.execute(
                              status -> {
                                sql.update("INSERT INTO sql_scope_effects(id) VALUES(?)", key);
                                sql.execute("SELECT pg_sleep(2)");
                                return "must not publish";
                              })));
      assertInstanceOf(
          ReadFailedException.class,
          assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
    }
    assertEquals(
        0,
        setup.queryForObject(
            "SELECT count(*) FROM sql_scope_effects WHERE id=?", Integer.class, key));
    // Reusing the same primary key also proves the aborted transaction no longer holds its lock.
    setup.update("INSERT INTO sql_scope_effects(id) VALUES(?)", key);
    assertTrue(
        System.nanoTime() - started < Duration.ofSeconds(1).toNanos(),
        "backend cancellation must release the row lock before pg_sleep(2) finishes");
    assertEquals(
        0,
        setup.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() "
                + "AND state='active' AND query='SELECT pg_sleep(2)'",
            Integer.class));
    // A failed PostgreSQL transaction rejects setup SELECTs. Normal recovery must still issue
    // its actual rollback, preserving the original grammar/integrity failure and clean pool reuse.
    String grammarPartial = UUID.randomUUID().toString();
    assertThrows(
        BadSqlGrammarException.class,
        () ->
            budget.within(
                Duration.ofSeconds(1),
                () ->
                    transactions.execute(
                        status -> {
                          sql.update("INSERT INTO sql_scope_effects(id) VALUES(?)", grammarPartial);
                          sql.execute("SELECT sql_scope_intentionally_missing_column");
                          return "must not publish";
                        })));
    String integrityPartial = UUID.randomUUID().toString();
    assertThrows(
        DuplicateKeyException.class,
        () ->
            budget.within(
                Duration.ofSeconds(1),
                () ->
                    transactions.execute(
                        status -> {
                          sql.update(
                              "INSERT INTO sql_scope_effects(id) VALUES(?)", integrityPartial);
                          sql.update("INSERT INTO sql_scope_effects(id) VALUES(?)", key);
                          return "must not publish";
                        })));
    assertEquals(
        0,
        setup.queryForObject(
            "SELECT count(*) FROM sql_scope_effects WHERE id IN (?,?)",
            Integer.class,
            grammarPartial,
            integrityPartial));
    assertEquals(1, setup.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void mapperMaterializationSharesTheStatementCeiling() throws Exception {
    var budget = budget();
    JdbcTemplate sql = new JdbcTemplate(budget.decorate(main));
    CountDownLatch materializing = new CountDownLatch(1);
    try (var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var call =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofSeconds(1),
                      () ->
                          sql.query(
                              "SELECT 1",
                              (row, index) -> {
                                materializing.countDown();
                                try {
                                  new CountDownLatch(1).await(300, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException interrupted) {
                                  Thread.currentThread().interrupt();
                                }
                                return row.getInt(1);
                              })));
      assertTrue(materializing.await(1, TimeUnit.SECONDS));
      assertInstanceOf(
          ReadFailedException.class,
          assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
    }
    assertEquals(1, setup.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void mainPoolAcquisitionUsesRemainingOperationBudgetAndKeepsOtherLeasesOwned() throws Exception {
    var budget = budget();
    Connection one = main.getConnection();
    try (Connection two = main.getConnection();
        var calls = Executors.newVirtualThreadPerTaskExecutor()) {
      var call =
          calls.submit(
              () ->
                  budget.within(
                      Duration.ofMillis(100),
                      () ->
                          new JdbcTemplate(budget.decorate(main))
                              .queryForObject("SELECT 1", Integer.class)));
      assertThrows(TimeoutException.class, () -> call.get(250, TimeUnit.MILLISECONDS));
      assertFalse(one.isClosed());
      assertFalse(two.isClosed());
      one.close(); // Release one physical borrower; the expired SQL scope refuses its late lease.
      assertInstanceOf(
          ReadFailedException.class,
          assertThrows(ExecutionException.class, () -> call.get(2, TimeUnit.SECONDS)).getCause());
      assertFalse(two.isClosed());
    } finally {
      one.close();
    }
    assertEquals(1, setup.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void scopedAutocommitLimitsPreserveEarlierSettingsAndRestoreBeforeUnscopedReuse()
      throws Exception {
    var budget = budget();
    // Holding the second lease makes pool reuse deterministic without changing production sizing.
    try (Connection held = main.getConnection()) {
      assertFalse(held.isClosed());
      String originalStatement;
      String originalLock;
      try (Connection selected = main.getConnection();
          var statement = selected.createStatement()) {
        try (var values = statement.executeQuery("SHOW statement_timeout")) {
          values.next();
          originalStatement = values.getString(1);
        }
        try (var values = statement.executeQuery("SHOW lock_timeout")) {
          values.next();
          originalLock = values.getString(1);
        }
        statement.execute("SET statement_timeout='75ms'");
        statement.execute("SET lock_timeout='60ms'");
      }
      try {
        int answer =
            budget.within(
                Duration.ofSeconds(1),
                () ->
                    new JdbcTemplate(budget.decorate(main))
                        .queryForObject("SELECT 42", Integer.class));
        assertEquals(42, answer);
        try (Connection selected = main.getConnection();
            var statement = selected.createStatement()) {
          try (var values = statement.executeQuery("SHOW statement_timeout")) {
            values.next();
            assertEquals("75ms", values.getString(1));
          }
          try (var values = statement.executeQuery("SHOW lock_timeout")) {
            values.next();
            assertEquals("60ms", values.getString(1));
          }
        }
      } finally {
        try (Connection selected = main.getConnection();
            var reset =
                selected.prepareStatement(
                    "SELECT set_config('statement_timeout',?,false),set_config('lock_timeout',?,false)")) {
          reset.setString(1, originalStatement);
          reset.setString(2, originalLock);
          reset.execute();
        }
      }
    }
  }
}
