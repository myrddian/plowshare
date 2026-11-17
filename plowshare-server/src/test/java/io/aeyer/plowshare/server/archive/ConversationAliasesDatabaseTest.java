package io.aeyer.plowshare.server.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.agents.Budget;
import io.aeyer.plowshare.server.agents.TurnCap;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository.Key;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository.Opened;
import io.aeyer.plowshare.server.archive.ConversationAliasRepository.Opening;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Only actual PostgreSQL can prove migration/FKs, advisory-lock races, rollback and cancellation.
 */
@Tag("full-db")
@Testcontainers
class ConversationAliasesDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withUrlParam("sslmode", "disable")
          .withUrlParam("gssEncMode", "disable");

  private static HikariDataSource pool;
  private static JdbcTemplate jdbc;
  private static JdbcTransactionManager transactions;
  private final List<JdbcConversationAliasRepository> repositories = new ArrayList<>();
  private JdbcConversationAliasRepository repository;
  private Key key;

  @BeforeAll
  static void migrate() {
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    String schema = "aliases_" + UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate setup = new JdbcTemplate(source);
    setup.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
    setup.execute("CREATE SCHEMA " + schema);
    Properties properties = new Properties();
    properties.setProperty("currentSchema", schema + ",public");
    source.setConnectionProperties(properties);
    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
    HikariConfig config = new HikariConfig();
    config.setDataSource(source);
    config.setMaximumPoolSize(8);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(5000);
    pool = new HikariDataSource(config);
    jdbc = new JdbcTemplate(pool);
    transactions = new JdbcTransactionManager(pool);
  }

  @AfterAll
  static void closePool() {
    if (pool != null) pool.close();
  }

  @BeforeEach
  void fixture() {
    key = new Key(project(), account(), "ha.assist", "Caller/Session");
    repository = repository(Duration.ofSeconds(2), Duration.ofSeconds(1));
  }

  @AfterEach
  void closeRepositories() {
    repositories.forEach(JdbcConversationAliasRepository::close);
  }

  private JdbcConversationAliasRepository repository(Duration timeout, Duration queryTimeout) {
    var selected =
        new JdbcConversationAliasRepository(
            jdbc,
            new ConversationStore(jdbc),
            transactions,
            new ConversationAliasProperties(timeout, queryTimeout),
            new SimpleMeterRegistry());
    repositories.add(selected);
    return selected;
  }

  private static long project() {
    return jdbc.queryForObject(
        "INSERT INTO projects(name) VALUES(?) RETURNING id",
        Long.class,
        "alias-project-" + UUID.randomUUID());
  }

  private static AccountId account() {
    String handle = "alias_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,must_change_password,server_admin) VALUES(?,'fixture',FALSE,FALSE)",
        handle);
    return new AccountId(
        jdbc.queryForObject(
            "SELECT id FROM authority_accounts WHERE legacy_handle=?", UUID.class, handle));
  }

  @Test
  void concurrentFirstOpenCreatesExactlyOneRootAndCanonicalAllowance() throws Exception {
    int initialAllowances = count("execution_allowances");
    var start = new CountDownLatch(1);
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      List<Future<Opened>> pending = new ArrayList<>();
      Opening options = new Opening(Budget.of(40), null);
      for (int i = 0; i < 8; i++)
        pending.add(
            executor.submit(
                () -> {
                  assertTrue(start.await(2, TimeUnit.SECONDS));
                  return repository.open(key, options);
                }));
      start.countDown();
      Set<String> targets = new HashSet<>();
      int created = 0;
      for (Future<Opened> future : pending) {
        Opened opened = future.get(5, TimeUnit.SECONDS);
        targets.add(opened.conversation().id());
        if (opened.created()) created++;
      }
      assertEquals(1, created);
      assertEquals(1, targets.size());
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM conversations WHERE project_id=?",
              Integer.class,
              key.projectId()));
      assertEquals(initialAllowances + 1, count("execution_allowances"));
      assertEquals(
          1,
          jdbc.queryForObject(
              "SELECT count(*) FROM conversation_id_map WHERE project_id=?",
              Integer.class,
              key.projectId()));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRANSACTION_REPEATABLE_READ", "TRANSACTION_SERIALIZABLE"})
  void firstOpenWaitersSeeTheWinnerEvenWhenThePoolDefaultRetainsSnapshots(String isolation)
      throws Exception {
    String application = "alias_isolation_" + UUID.randomUUID().toString().replace("-", "");
    HikariConfig config = new HikariConfig();
    config.setDataSource(pool.getDataSource());
    config.setMaximumPoolSize(4);
    config.setMinimumIdle(4);
    config.setConnectionTimeout(2000);
    config.setTransactionIsolation(isolation);
    config.setConnectionInitSql("SET application_name='" + application + "'");
    int expectedIsolation =
        isolation.equals("TRANSACTION_REPEATABLE_READ")
            ? Connection.TRANSACTION_REPEATABLE_READ
            : Connection.TRANSACTION_SERIALIZABLE;
    try (var isolatedPool = new HikariDataSource(config)) {
      // Warm all four connections before starting the bounded race. This also proves that the
      // regression really starts from the configured non-default physical connection state.
      List<Connection> warmed = new ArrayList<>();
      try {
        for (int i = 0; i < 4; i++) {
          var connection = isolatedPool.getConnection();
          warmed.add(connection);
          assertEquals(expectedIsolation, connection.getTransactionIsolation());
        }
      } finally {
        for (var connection : warmed) connection.close();
      }

      var isolatedJdbc = new JdbcTemplate(isolatedPool);
      var ordinary = new ConversationStore(isolatedJdbc);
      var leader = new CountDownLatch(1);
      var commitAllowed = new CountDownLatch(1);
      ConversationCreation controlled =
          new ConversationCreation() {
            @Override
            public ConversationRecord open(Home home, Budget budget, TurnCap cap, String owner) {
              leader.countDown();
              try {
                if (!commitAllowed.await(750, TimeUnit.MILLISECONDS))
                  throw new IllegalStateException(
                      "Fixture waiters did not reach the advisory lock");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Fixture creation interrupted", interrupted);
              }
              return ordinary.open(home, budget, cap, owner);
            }

            @Override
            public Optional<ConversationRecord> find(String id) {
              return ordinary.find(id);
            }
          };
      try (var aliases =
          new JdbcConversationAliasRepository(
              isolatedJdbc,
              controlled,
              new JdbcTransactionManager(isolatedPool),
              new ConversationAliasProperties(Duration.ofSeconds(2), Duration.ofSeconds(1)),
              new SimpleMeterRegistry())) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
          var start = new CountDownLatch(1);
          List<Future<Opened>> pending = new ArrayList<>();
          Opening choices = new Opening(Budget.of(20), null);
          for (int i = 0; i < 4; i++)
            pending.add(
                executor.submit(
                    () -> {
                      if (!start.await(1, TimeUnit.SECONDS))
                        throw new IllegalStateException("Fixture start expired");
                      return aliases.open(key, choices);
                    }));
          start.countDown();
          assertTrue(leader.await(1, TimeUnit.SECONDS));
          try {
            // The first creator has not inserted/committed anything. All three other transactions
            // must already have completed scope SELECT and be waiting on this same advisory lock.
            // A retained pre-wait MVCC snapshot would therefore miss the winner deterministically.
            long until = System.nanoTime() + Duration.ofMillis(500).toNanos();
            int waiting;
            do {
              waiting =
                  jdbc.queryForObject(
                      """
                  SELECT count(*) FROM pg_locks l
                  JOIN pg_stat_activity a ON a.pid=l.pid
                  WHERE a.application_name=? AND l.locktype='advisory' AND NOT l.granted
                  """,
                      Integer.class,
                      application);
              if (waiting < 3) Thread.sleep(5);
            } while (waiting < 3 && System.nanoTime() < until);
            assertEquals(3, waiting);
          } finally {
            commitAllowed.countDown();
          }
          Set<String> targets = new HashSet<>();
          int created = 0;
          for (var result : pending) {
            var selected = result.get(5, TimeUnit.SECONDS);
            targets.add(selected.conversation().id());
            if (selected.created()) created++;
          }
          assertEquals(1, created);
          assertEquals(1, targets.size());
          assertEquals(
              1,
              jdbc.queryForObject(
                  "SELECT count(*) FROM conversations WHERE project_id=?",
                  Integer.class,
                  key.projectId()));
        } finally {
          commitAllowed.countDown();
          executor.shutdownNow();
          assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
      }
      // Spring must restore each connection's pool-level isolation on transaction cleanup.
      try (var restored = isolatedPool.getConnection()) {
        assertEquals(expectedIsolation, restored.getTransactionIsolation());
      }
    }
  }

  @Test
  void completeKeyPartitionsAndReusedOpeningNeverSharesAllowanceIdentity() {
    Opening sameChoices = new Opening(Budget.of(20), null);
    List<Key> keys =
        List.of(
            key,
            new Key(key.projectId(), key.account(), "ha.ai-task", key.externalConversation()),
            new Key(key.projectId(), account(), key.source(), key.externalConversation()),
            new Key(project(), key.account(), key.source(), key.externalConversation()));
    Set<String> targets = new HashSet<>();
    Set<UUID> budgets = new HashSet<>();
    for (Key selected : keys) {
      var opened = repository.open(selected, sameChoices);
      targets.add(opened.conversation().id());
      budgets.add(opened.conversation().budget().identity());
      assertEquals(opened.conversation().id(), repository.find(selected).orElseThrow().id());
    }
    assertEquals(4, targets.size());
    assertEquals(4, budgets.size());
    assertTrue(
        repository
            .find(new Key(key.projectId(), key.account(), key.source(), "different"))
            .isEmpty());
  }

  @Test
  void anAliasInsertFailureRollsBackBothOrdinaryRootAndAllowance() {
    int roots = count("conversations");
    int allowances = count("execution_allowances");
    // This fixture fails after ordinary creation, at the final alias INSERT, which cannot be
    // proven atomic by mocks. Its private schema is discarded with the class-owned container.
    jdbc.execute(
        """
        CREATE FUNCTION fixture_reject_alias() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.source='fixture.fail' THEN RAISE EXCEPTION 'fixture' USING ERRCODE='23514'; END IF;
        RETURN NEW; END $$
        """);
    jdbc.execute(
        "CREATE TRIGGER fixture_reject BEFORE INSERT ON conversation_id_map FOR EACH ROW EXECUTE FUNCTION fixture_reject_alias()");
    try {
      Key failing = new Key(key.projectId(), key.account(), "fixture.fail", "once");
      assertThrows(
          DataAccessException.class,
          () -> repository.open(failing, new Opening(Budget.of(20), null)));
      assertEquals(roots, count("conversations"));
      assertEquals(allowances, count("execution_allowances"));
      assertTrue(repository.find(failing).isEmpty());
    } finally {
      jdbc.execute("DROP TRIGGER fixture_reject ON conversation_id_map");
      jdbc.execute("DROP FUNCTION fixture_reject_alias()");
    }
  }

  @Test
  void databaseConstraintsProtectShapeRebindingAndDirectTargetDeletion() {
    var first = repository.open(key, new Opening(Budget.of(20), null)).conversation();
    var second =
        repository
            .open(
                new Key(key.projectId(), key.account(), key.source(), "second"),
                new Opening(Budget.of(20), null))
            .conversation();
    var foreign =
        repository
            .open(
                new Key(project(), key.account(), key.source(), "foreign"),
                new Opening(Budget.of(20), null))
            .conversation();
    assertNotEquals(first.id(), second.id());
    assertThrows(DataAccessException.class, () -> insertAlias(key, "foreign-target", foreign.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "INSERT INTO conversation_id_map(project_id,owner_account_id,source,external_conversation_id,conversation_id) VALUES(?,?,'INVALID','id',?)",
                key.projectId(),
                key.account().value(),
                first.id()));
    assertThrows(DataAccessException.class, () -> insertAlias(key, "has whitespace", first.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE conversation_id_map SET conversation_id=? WHERE conversation_id=?",
                second.id(),
                first.id()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.update("DELETE FROM conversations WHERE id=?", first.id()));
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.update(
                "UPDATE conversations SET project_id=? WHERE id=?",
                key.projectId() + 99999,
                first.id()));
    // Existing retention changes lifecycle and clears payloads, retaining the row and alias.
    jdbc.update("UPDATE conversations SET lifecycle='ejected' WHERE id=?", first.id());
    assertEquals(ConversationLifecycle.EJECTED, repository.find(key).orElseThrow().lifecycle());
  }

  @Test
  void ownerAndExplicitProjectPurgeRemoveNamespacesWithoutTargetReuse() {
    var retained = repository.open(key, new Opening(Budget.of(20), null)).conversation();
    String owner =
        jdbc.queryForObject(
            "SELECT legacy_handle FROM authority_accounts WHERE id=?",
            String.class,
            key.account().value());
    jdbc.update("DELETE FROM admins WHERE handle=?", owner);
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM conversation_id_map WHERE owner_account_id=?",
            Integer.class,
            key.account().value()));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM conversations WHERE id=?", Integer.class, retained.id()));

    Key purge = new Key(project(), account(), "ha.assist", "purge");
    repository.open(purge, new Opening(Budget.of(20), null));
    // A populated project has existing non-cascading target FKs. An explicit maintenance
    // transaction can delete its roots and namespace together; no new public purge API is added.
    new TransactionTemplate(transactions)
        .execute(
            status -> {
              jdbc.update("DELETE FROM conversations WHERE project_id=?", purge.projectId());
              jdbc.update("DELETE FROM projects WHERE id=?", purge.projectId());
              return null;
            });
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM conversation_id_map WHERE project_id=?",
            Integer.class,
            purge.projectId()));
  }

  // The server timeout and client watchdog can win independently. Deterministic mock cases prove
  // both classifications; a finite repetition also exercises the actual driver/rollback race.
  @RepeatedTest(5)
  void blockedLookupIsCancelledAndRolledBackWithoutCreatingAnOrphan() throws Exception {
    var bounded = repository(Duration.ofMillis(400), Duration.ofMillis(150));
    try (Connection blocker = pool.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.createStatement()) {
        statement.execute("LOCK TABLE conversation_id_map IN ACCESS EXCLUSIVE MODE");
      }
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<Opened> pending =
            executor.submit(() -> bounded.open(key, new Opening(Budget.of(20), null)));
        ExecutionException refusal =
            assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
        assertTrue(
            refusal.getCause() instanceof ArchiveUnavailableException,
            () -> failureTypes(refusal.getCause()));
      } finally {
        blocker.rollback();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
      }
    }
    assertTrue(repository.find(key).isEmpty());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM conversations WHERE project_id=?",
            Integer.class,
            key.projectId()));
  }

  @Test
  void poolAcquisitionConsumesTheSameDeadlineAndDoesNotAbortAnotherBorrower() throws Exception {
    var bounded = repository(Duration.ofMillis(200), Duration.ofMillis(150));
    List<Connection> held = new ArrayList<>();
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    try {
      for (int i = 0; i < 8; i++) held.add(pool.getConnection());
      Future<?> pending = executor.submit(() -> bounded.find(key));
      ExecutionException refusal =
          assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
      assertTrue(
          refusal.getCause() instanceof ArchiveUnavailableException,
          () -> failureTypes(refusal.getCause()));
      for (Connection connection : held) assertFalse(connection.isClosed());
    } finally {
      for (Connection connection : held) connection.close();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
    }
    assertTrue(repository.find(key).isEmpty());
    try (Connection reused = pool.getConnection();
        var statement = reused.createStatement()) {
      assertEquals(0, reused.getNetworkTimeout());
      try (var result = statement.executeQuery("SELECT 1")) {
        assertTrue(result.next());
      }
    }
  }

  private static void insertAlias(Key key, String external, String target) {
    jdbc.update(
        "INSERT INTO conversation_id_map(project_id,owner_account_id,source,external_conversation_id,conversation_id) VALUES(?,?,?,?,?)",
        key.projectId(),
        key.account().value(),
        key.source(),
        external,
        target);
  }

  private static int count(String table) {
    if (!Set.of("conversations", "execution_allowances").contains(table))
      throw new IllegalArgumentException("fixture table");
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static String failureTypes(Throwable failure) {
    StringBuilder types = new StringBuilder("Unexpected cancellation classification: ");
    for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
      types.append(failure.getClass().getSimpleName());
      if (failure instanceof SQLException sql)
        types.append('[').append(sql.getSQLState()).append(']');
      types.append(" -> ");
    }
    return types.toString();
  }
}
