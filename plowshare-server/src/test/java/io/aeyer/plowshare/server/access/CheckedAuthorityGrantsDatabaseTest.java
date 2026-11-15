package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.ScopedGrantRepository.Binding;
import io.aeyer.plowshare.server.cache.BoundedSnapshotCache;
import io.aeyer.plowshare.server.cache.JdbcProjectionVersions;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Token;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.testing.AuthorityFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL alone proves caller snapshots, statement triggers, late commits and savepoints. */
@Tag("full-db")
@Testcontainers
class CheckedAuthorityGrantsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static SingleConnectionDataSource caller;
  private static DriverManagerDataSource independent;
  private JdbcTemplate jdbc;
  private JdbcProjectionVersions versions;
  private GrantRecipient recipient;
  private UUID grant;

  @BeforeAll
  static void migrate() {
    caller =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    caller.setAutoCommit(true);
    independent =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(caller).load().migrate();
  }

  @AfterAll
  static void close() {
    caller.destroy();
  }

  @BeforeEach
  void setup() {
    jdbc = new JdbcTemplate(caller);
    jdbc.execute("ROLLBACK");
    AuthorityFixtures.truncate(jdbc, "TRUNCATE admins,projects CASCADE");
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES('reader','fixture')");
    jdbc.update("INSERT INTO projects(name) VALUES('checked')");
    jdbc.update(
        "INSERT INTO project_members(project_id,handle,role) SELECT id,'reader','MANAGER' FROM projects WHERE name='checked'");
    recipient =
        GrantRecipient.account(
            new AccountId(
                jdbc.queryForObject(
                    "SELECT id FROM authority_accounts WHERE legacy_handle='reader'", UUID.class)));
    grant =
        jdbc.queryForObject(
            "SELECT id FROM authority_grant_records WHERE subject_handle='reader' AND project_name='checked' AND coverage='SELF'",
            UUID.class);
    versions = new JdbcProjectionVersions(jdbc, independent, 128);
  }

  private Token token() {
    return versions.capture(Set.of(Domain.AUTHORITY)).orElseThrow();
  }

  private record Answer(boolean exact, String reason, long version, List<Binding> rows) {}

  /** One JDBC statement, even for a SQL fallback or an empty result. */
  private Answer checked(Token token, boolean lineage) {
    String function = lineage ? "authority_lineage_checked" : "authority_live_grants_checked";
    String selection = lineage ? "?::uuid," : "?::uuid,?::text,";
    return jdbc.query(
        "SELECT c.exact,c.reason,c.checked_version,(c.grant_row).* FROM "
            + function
            + "("
            + selection
            + "?::uuid,?::bigint,?::text[],?::text,?::timestamptz,?::timestamptz) c",
        statement -> {
          int index = 1;
          statement.setObject(index++, lineage ? grant : recipient.account().value());
          if (!lineage) statement.setString(index++, recipient.agent().orElse(null));
          statement.setObject(index++, token == null ? null : token.database());
          statement.setObject(
              index++, token == null ? null : token.counters().get(Domain.AUTHORITY));
          statement.setString(
              index++,
              token == null ? null : "{" + String.join(",", token.pendingTransactions()) + "}");
          statement.setString(index++, token == null ? null : token.futureTransactionsFrom());
          statement.setObject(index++, token == null ? null : offset(token.windowStart()));
          statement.setObject(index, token == null ? null : offset(token.validUntil()));
        },
        (ResultSetExtractor<Answer>)
            rows -> {
              assertTrue(rows.next(), "The function always returns an outcome row");
              boolean exact = rows.getBoolean("exact");
              String reason = rows.getString("reason");
              long version = rows.getLong("checked_version");
              var bindings = new ArrayList<Binding>();
              do {
                assertEquals(exact, rows.getBoolean("exact"));
                assertEquals(reason, rows.getString("reason"));
                assertEquals(version, rows.getLong("checked_version"));
                if (rows.getObject("id", UUID.class) != null)
                  bindings.add(JdbcScopedGrantRepository.read(rows));
              } while (rows.next());
              return new Answer(exact, reason, version, List.copyOf(bindings));
            });
  }

  private static OffsetDateTime offset(Instant value) {
    return value == null ? null : value.atOffset(ZoneOffset.UTC);
  }

  /** Independent copies of the pre-migration query shapes are the equivalence oracle. */
  private List<Binding> sql(boolean lineage) {
    if (lineage)
      return jdbc.query(
          "WITH RECURSIVE chain(id,issuing_grant,depth) AS ("
              + "SELECT g.id,g.issuing_grant,0 FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id WHERE g.id=? "
              + "UNION ALL SELECT p.id,p.issuing_grant,c.depth+1 FROM authority_grants p JOIN chain c ON c.issuing_grant=p.id WHERE c.depth<32) "
              + "SELECT g.* FROM chain c JOIN authority_grant_records g ON g.id=c.id ORDER BY c.depth",
          (row, n) -> JdbcScopedGrantRepository.read(row),
          grant);
    return jdbc.query(
        "SELECT g.* FROM authority_grant_records g JOIN authority_live_grants() l ON l.id=g.id "
            + "WHERE g.subject_account=? AND g.subject_agent IS NOT DISTINCT FROM ? ORDER BY g.created_at,g.id",
        (row, n) -> JdbcScopedGrantRepository.read(row),
        recipient.account().value(),
        recipient.agent().orElse(null));
  }

  private void fallback(Token token, String reason) {
    for (boolean lineage : List.of(false, true)) {
      var answer = checked(token, lineage);
      assertFalse(answer.exact());
      assertEquals(reason, answer.reason());
      assertEquals(sql(lineage), answer.rows());
    }
  }

  private void confirmed(Token token, AuthoritySnapshot snapshot) {
    for (boolean lineage : List.of(false, true)) {
      var answer = checked(token, lineage);
      assertTrue(answer.exact());
      assertEquals("exact", answer.reason());
      assertTrue(answer.rows().isEmpty());
      assertEquals(sql(lineage), lineage ? snapshot.lineage(grant) : snapshot.live(recipient));
    }
  }

  private AuthoritySnapshot snapshot() {
    return new AuthoritySnapshot(
        new JdbcAuthoritySnapshotRepository(jdbc, 10_000).load().orElseThrow());
  }

  @Test
  void confirmsWithoutAuthorityWritesAndPreservesTheOldQueriesIncludingEmptyAnswers() {
    var token = token();
    var snapshot = snapshot();
    jdbc.execute("BEGIN");
    try {
      confirmed(token, snapshot);
      fallback(null, "ineligible");
      grant = UUID.randomUUID();
      recipient = GrantRecipient.account(new AccountId(UUID.randomUUID()));
      fallback(null, "ineligible");
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void dirtyReadsIncludeTheirOwnWritesAndSavepointRollbackRestoresConfirmation() {
    var token = token();
    var snapshot = snapshot();
    jdbc.execute("BEGIN");
    try {
      jdbc.execute("SAVEPOINT before_authority_write");
      jdbc.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      fallback(token, "dirty");
      assertTrue(sql(false).isEmpty());
      jdbc.execute("ROLLBACK TO SAVEPOINT before_authority_write");
      // The sequence allocation remains advanced after savepoint rollback, so the old token
      // must fall back despite the dirty marker correctly disappearing.
      fallback(token, "version");
      var reloaded = token();
      confirmed(reloaded, snapshot);
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void noOpAuthorityStatementsAndUnrelatedProjectionWritesDoNotDirtyAuthority() {
    var token = token();
    var snapshot = snapshot();
    jdbc.execute("BEGIN");
    try {
      jdbc.update("UPDATE admins SET enabled=false WHERE handle='missing'");
      jdbc.queryForObject("SELECT snapshot_cache_record_writer(ARRAY['progress'])", Object.class);
      assertEquals(
          "on",
          jdbc.queryForObject("SELECT current_setting('plowshare.cache_dirty')", String.class));
      confirmed(token, snapshot);
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void uncommittedCommittedAndRolledBackWritersAllRefuseAnOlderAllocation() throws SQLException {
    var token = token();
    try (Connection writer = independent.getConnection()) {
      writer.setAutoCommit(false);
      var other = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
      other.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      fallback(token, "version");
      assertFalse(sql(false).isEmpty(), "An uncommitted revocation is invisible to the caller");
      writer.rollback();
      fallback(token, "version");
      confirmed(token(), snapshot());
      other.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      writer.commit();
      fallback(token, "version");
      assertTrue(sql(false).isEmpty());
    }
  }

  @Test
  void aFutureWriterCommittingWithoutAnotherAllocationInvalidatesTheStoredEvidence()
      throws SQLException {
    // Reproduce the capture interleaving deterministically: capture the MVCC boundary before
    // another writer starts, then observe its allocation while its facts remain uncommitted.
    // This is the V205/V208 late-commit hole that a scalar version cannot close.
    var before = token();
    try (Connection writer = independent.getConnection()) {
      writer.setAutoCommit(false);
      var other = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
      other.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      long allocation =
          jdbc.queryForObject("SELECT last_value FROM snapshot_cache_authority", Long.class);
      var captured =
          new Token(
              before.database(),
              Map.of(Domain.AUTHORITY, allocation),
              before.pendingTransactions(),
              before.futureTransactionsFrom(),
              before.windowStart(),
              before.validUntil());
      var snapshot = snapshot();
      assertTrue(versions.current(captured), "The writer is not yet committed");
      confirmed(captured, snapshot);
      writer.commit();
      assertEquals(
          allocation,
          jdbc.queryForObject("SELECT last_value FROM snapshot_cache_authority", Long.class));
      assertFalse(versions.current(captured));
      fallback(captured, "version");
      assertTrue(sql(false).isEmpty());
    }
  }

  @Test
  void aCapturedPendingWriterCommittingWithoutAnotherAllocationInvalidatesTheSnapshot()
      throws SQLException {
    try (Connection writer = independent.getConnection()) {
      writer.setAutoCommit(false);
      var other = new JdbcTemplate(new SingleConnectionDataSource(writer, true));
      other.update("UPDATE admins SET enabled=false WHERE handle='reader'");
      String writerId = other.queryForObject("SELECT pg_current_xact_id()::text", String.class);
      // A completed later XID advances xmax past this still-active writer, ensuring this case
      // exercises the pending list rather than the independently tested future-XID boundary.
      new JdbcTemplate(independent)
          .queryForObject("SELECT pg_current_xact_id()::text", String.class);
      var captured = token();
      assertTrue(captured.pendingTransactions().contains(writerId));
      confirmed(captured, snapshot());
      writer.commit();
      assertEquals(
          captured.counters().get(Domain.AUTHORITY),
          jdbc.queryForObject("SELECT last_value FROM snapshot_cache_authority", Long.class));
      fallback(captured, "version");
    }
  }

  @Test
  void rejectsHistoricalIsolationAndAuthorityTimeWindows() {
    var token = token();
    jdbc.execute("BEGIN ISOLATION LEVEL REPEATABLE READ");
    try {
      fallback(token, "isolation");
    } finally {
      jdbc.execute("ROLLBACK");
    }
    jdbc.execute("BEGIN");
    try {
      Instant at =
          jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class).toInstant();
      fallback(
          new Token(
              token.database(),
              token.counters(),
              token.pendingTransactions(),
              token.futureTransactionsFrom(),
              at.plusSeconds(1),
              null),
          "window");
      fallback(
          new Token(
              token.database(),
              token.counters(),
              token.pendingTransactions(),
              token.futureTransactionsFrom(),
              null,
              at),
          "window");
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void anActuallyExpiredGrantRemainsVisibleToAnOlderReadCommittedTransaction() {
    UUID expiring = UUID.randomUUID();
    jdbc.execute("BEGIN");
    try {
      Instant started =
          jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class).toInstant();
      var fresh = new JdbcTemplate(independent);
      fresh.update(
          "INSERT INTO authority_grants(id,subject_account,subject_agent,scope_id,role_id,requested_role,"
              + "issuer_account,issuing_grant,expires_at,provenance) "
              + "SELECT ?,parent.subject_account,'expiring',parent.scope_id,role.id,'READER',"
              + "parent.subject_account,parent.id,clock_timestamp()+interval '500 milliseconds','ROLE' "
              + "FROM authority_grants parent CROSS JOIN authority_roles role WHERE parent.id=? AND role.name='READER'",
          expiring,
          grant);
      grant = expiring;
      recipient = GrantRecipient.agent(recipient.account(), "expiring");
      OffsetDateTime expires =
          fresh.queryForObject(
              "SELECT expires_at FROM authority_grants WHERE id=?", OffsetDateTime.class, grant);
      assertTrue(
          started.isBefore(expires.toInstant()),
          "The old transaction begins before the immutable grant expires");
      // This wait deliberately crosses a clock-only expiry; transaction interleavings in the
      // other tests use explicit commits/savepoints and never depend on sleeping.
      fresh.queryForObject(
          "SELECT pg_sleep(greatest(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.01)",
          Object.class,
          expires);
      var afterExpiry =
          new JdbcProjectionVersions(fresh, independent, 128)
              .capture(Set.of(Domain.AUTHORITY))
              .orElseThrow();
      var newerSnapshot =
          new AuthoritySnapshot(
              new JdbcAuthoritySnapshotRepository(fresh, 10_000).load().orElseThrow());
      assertTrue(newerSnapshot.live(recipient).isEmpty());
      assertFalse(sql(false).isEmpty(), "SQL now() still means the old transaction's start");
      fallback(afterExpiry, "window");
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  private static final class CountingJdbc extends JdbcTemplate {
    private final List<String> statements = new ArrayList<>();

    CountingJdbc() {
      super(caller);
    }

    @Override
    public <T> T query(
        String sql, PreparedStatementSetter setter, ResultSetExtractor<T> extractor) {
      statements.add(sql);
      return super.query(sql, setter, extractor);
    }
  }

  @Test
  void typedJdbcReadsIssueExactlyOneCheckedCallPerReadForFastAndSlowAnswers() {
    var token = token();
    var counted = new CountingJdbc();
    var repository = new JdbcScopedGrantRepository(counted);
    jdbc.execute("BEGIN");
    try {
      for (boolean lineage : List.of(false, true)) {
        for (var evidence : List.of(Optional.of(token), Optional.<Token>empty())) {
          List<Binding> expected = evidence.isPresent() ? List.of() : sql(lineage);
          counted.statements.clear();
          for (int index = 0; index < 100; index++) {
            var answer =
                lineage
                    ? repository.lineageChecked(grant, evidence)
                    : repository.liveChecked(recipient, evidence);
            assertEquals(evidence.isPresent(), answer.exact());
            assertEquals(expected, answer.rows());
          }
          assertEquals(100, counted.statements.size());
          String function =
              lineage ? "authority_lineage_checked(" : "authority_live_grants_checked(";
          assertTrue(counted.statements.stream().allMatch(sql -> sql.contains(function)));
        }
      }
      grant = UUID.randomUUID();
      recipient = GrantRecipient.account(new AccountId(UUID.randomUUID()));
      assertTrue(repository.liveChecked(recipient, Optional.empty()).rows().isEmpty());
      assertTrue(repository.lineageChecked(grant, Optional.empty()).rows().isEmpty());
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void productionCompositionConfirmsSeesOwnWritesAndRecoversAfterRollback() throws Exception {
    // Unlike the single-connection SQL fixtures, production uses distinct leases per thread:
    // Spring binds the caller's transaction, and the refresh worker obtains independent leases.
    var app = new JdbcTemplate(independent);
    var appVersions = new JdbcProjectionVersions(app, independent, 128);
    var domains = Set.of(Domain.AUTHORITY);
    var signals = mock(ProjectionChangeSignals.class);
    when(signals.subscribe(eq(domains), any()))
        .thenAnswer(
            call -> {
              ProjectionChangeSignals.Observer observer = call.getArgument(1);
              observer.stateChanged(new ProjectionChangeSignals.State(1, true));
              return (ProjectionChangeSignals.Subscription) () -> {};
            });
    when(signals.deliveredThrough()).thenAnswer(call -> OptionalLong.of(System.nanoTime()));
    var telemetry = mock(AuthorityCheckTelemetry.class);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var inheritedTransaction = new AtomicBoolean(true);
    var failure = new AtomicReference<Throwable>();
    try (var cache =
        new VersionedProjectionCache<Optional<AuthoritySnapshot>>(
            new BoundedSnapshotCache<>(
                new SnapshotCache.Namespace(UUID.randomUUID().toString(), "authority"),
                new SnapshotCache.Limits(
                    8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(5))),
            appVersions,
            signals,
            domains,
            1_000_000,
            Duration.ofSeconds(2))) {
      var snapshots =
          new CachedAuthoritySnapshots(
              cache, new JdbcAuthoritySnapshotRepository(app, 10_000), ReadAdmission.direct());
      AuthoritySnapshots controlledRefresh =
          new AuthoritySnapshots() {
            @Override
            public Optional<AuthoritySnapshot> current() {
              return snapshots.current();
            }

            @Override
            public Optional<Candidate> candidate() {
              return snapshots.candidate();
            }

            @Override
            public void refresh() {
              inheritedTransaction.set(
                  TransactionSynchronizationManager.isActualTransactionActive());
              started.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IllegalStateException("Refresh barrier timed out");
                snapshots.refresh();
              } catch (Throwable stopped) {
                failure.set(stopped);
              } finally {
                finished.countDown();
              }
            }
          };
      try (var refresh = new AsyncAuthoritySnapshotRefresh(controlledRefresh)) {
        var stored = new JdbcScopedGrantRepository(app);
        var cached =
            new CachedScopedGrantRepository(
                stored, stored, controlledRefresh, refresh, telemetry, true);
        var initial = snapshots.current().orElseThrow();
        var expectedLive = initial.live(recipient);
        var expectedLineage = initial.lineage(grant);
        assertFalse(expectedLive.isEmpty());
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(independent));
        transactions.executeWithoutResult(
            status -> {
              assertEquals(expectedLive, cached.live(recipient));
              assertEquals(expectedLineage, cached.lineage(grant));
              app.update("UPDATE admins SET enabled=false WHERE handle='reader'");
              assertTrue(cached.live(recipient).isEmpty());
              assertTrue(cached.lineage(grant).isEmpty());
              status.setRollbackOnly();
            });
        assertEquals(1L, started.getCount(), "Own writes never request shared refresh");
        transactions.executeWithoutResult(
            status -> {
              assertEquals(expectedLive, cached.live(recipient));
              assertEquals(expectedLineage, cached.lineage(grant));
            });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertFalse(inheritedTransaction.get());
        release.countDown();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        if (failure.get() != null)
          throw new AssertionError("Independent refresh failed", failure.get());
        transactions.executeWithoutResult(
            status -> {
              assertEquals(expectedLive, cached.live(recipient));
              assertEquals(expectedLineage, cached.lineage(grant));
            });
        verify(telemetry, times(4)).grant(AuthorityCheckTelemetry.GrantOutcome.CONFIRMED);
        verify(telemetry, times(2)).grant(AuthorityCheckTelemetry.GrantOutcome.SQL_DIRTY);
        verify(telemetry, times(2)).grant(AuthorityCheckTelemetry.GrantOutcome.SQL_VERSION);
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void databaseMismatchAndMissingWriterEvidenceFailClosed() {
    var token = token();
    fallback(
        new Token(
            UUID.randomUUID(),
            token.counters(),
            token.pendingTransactions(),
            token.futureTransactionsFrom(),
            token.windowStart(),
            token.validUntil()),
        "version");
    jdbc.execute("BEGIN");
    try {
      jdbc.update(
          "UPDATE snapshot_cache_writer_retention SET through=?::xid8 WHERE singleton",
          token.futureTransactionsFrom());
      fallback(token, "version");
    } finally {
      jdbc.execute("ROLLBACK");
    }
  }

  @Test
  void everyAuthorityDependencyHasAllFourEnabledStatementTriggersAndFunctionsAreInvokerOnly() {
    var dependencies =
        List.of(
            "admins",
            "service_tokens",
            "service_token_scopes",
            "authority_accounts",
            "authority_scopes",
            "authority_roles",
            "authority_role_actions",
            "authority_grants",
            "project_members",
            "projects",
            "contained_principals");
    for (String table : dependencies) {
      Map<Integer, Long> events = new HashMap<>();
      jdbc.query(
          "SELECT trigger.tgtype::integer FROM pg_trigger trigger JOIN pg_proc function ON function.oid=trigger.tgfoid "
              + "WHERE trigger.tgrelid=?::regclass AND NOT trigger.tgisinternal AND trigger.tgenabled IN ('O','A') "
              + "AND function.proname='snapshot_cache_source_changed' AND encode(trigger.tgargs,'escape') LIKE '%authority%'",
          (RowCallbackHandler) row -> events.merge(row.getInt(1), 1L, Long::sum), table);
      assertEquals(Map.of(4, 1L, 8, 1L, 16, 1L, 32, 1L), events, table);
    }
    assertEquals(
        0L,
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_proc function WHERE function.proname IN "
                + "('authority_grants_check','authority_live_grants_checked','authority_lineage_checked') "
                + "AND (function.prosecdef OR EXISTS(SELECT 1 FROM aclexplode(coalesce(function.proacl,acldefault('f',function.proowner))) acl "
                + "WHERE acl.grantee=0 AND acl.privilege_type='EXECUTE'))",
            Long.class));
  }
}
