package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Actual migration/sequence, MVCC, expiry, savepoint and transactional LISTEN/NOTIFY behavior. */
@Tag("full-db")
@Testcontainers
class ProjectionVersionsDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Set<ProjectionVersions.Domain> SEARCH =
      Set.of(ProjectionVersions.Domain.SEARCH);
  private static DataSource data;
  private static JdbcTemplate jdbc;
  private static JdbcTemplate outside;
  private static JdbcProjectionVersions versions;

  @BeforeAll
  static void migrate() {
    data = source();
    Flyway.configure().dataSource(data).load().migrate();
    jdbc = new JdbcTemplate(data);
    outside = new JdbcTemplate(source());
    versions = new JdbcProjectionVersions(jdbc, source(), 128);
  }

  private UUID informationStep() {
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy',?,'source')",
        resource,
        resource.toString());
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'fixture','text/plain','fixture',1)",
        revision,
        resource);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state,lease_token,lease_until) VALUES(?,1,'extract','running',?,clock_timestamp()+interval '5 minutes')",
        revision,
        UUID.randomUUID());
    return revision;
  }

  @Test
  void informationStepHeartbeatsWakeProcWithoutInvalidatingCorpusOrAuthority() {
    UUID revision = informationStep();
    var corpus =
        versions
            .capture(
                Set.of(
                    ProjectionVersions.Domain.DOCUMENTS,
                    ProjectionVersions.Domain.SEARCH,
                    ProjectionVersions.Domain.READABILITY,
                    ProjectionVersions.Domain.AUTHORITY))
            .orElseThrow();
    var observations =
        versions.capture(Set.of(ProjectionVersions.Domain.OBSERVATIONS)).orElseThrow();
    try (var listener = new PostgresProjectionNotificationSource(source()).open()) {
      listener.listen();
      for (int i = 0; i < 100; i++)
        jdbc.update(
            "UPDATE information_steps SET lease_until=lease_until+interval '1 second' WHERE revision_id=?",
            revision);
      assertTrue(versions.current(corpus), "Lease traffic must not retire document/authority DTOs");
      assertFalse(versions.current(observations), "PROC lease deadlines must remain current");
      assertEquals(
          Set.of(ProjectionVersions.Domain.OBSERVATIONS), listener.poll(Duration.ofSeconds(2)));
      var work = new TransactionTemplate(new DataSourceTransactionManager(data));
      work.executeWithoutResult(
          status -> {
            jdbc.update(
                "UPDATE information_steps SET lease_until=NULL WHERE revision_id=?", revision);
            assertFalse(versions.canShare(), "A heartbeat still dirties its caller transaction");
            assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
            status.setRollbackOnly();
          });
      assertTrue(versions.current(corpus));
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty(), "Rollback sends no wakeup");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "state='ready'",
        "attempt=attempt+1",
        "error='failure'",
        "fingerprint='changed'",
        "lease_token=gen_random_uuid()",
        "started_at=clock_timestamp()",
        "finished_at=clock_timestamp()",
        "generation=generation+1",
        "stage='derive'"
      })
  void informationStepMetadataAndIdentityChangesStillInvalidateReaders(String assignment) {
    UUID first = informationStep(), second = informationStep();
    var corpus =
        versions
            .capture(Set.of(ProjectionVersions.Domain.DOCUMENTS, ProjectionVersions.Domain.SEARCH))
            .orElseThrow();
    try (var listener = new PostgresProjectionNotificationSource(source()).open()) {
      listener.listen();
      // Exercise transition-table batches, including a lease-only row beside a metadata change.
      // The assignment comes exclusively from the closed parameterized fixture above.
      var column = assignment.split("=", 2);
      jdbc.update(
          "UPDATE information_steps SET "
              + column[0]
              + "=CASE WHEN revision_id=? THEN "
              + column[1]
              + " ELSE "
              + column[0]
              + " END,lease_until=lease_until+interval '1 second' WHERE revision_id IN (?,?)",
          first,
          first,
          second);
      assertFalse(versions.current(corpus));
      assertEquals(
          Set.of(
              ProjectionVersions.Domain.DOCUMENTS,
              ProjectionVersions.Domain.SEARCH,
              ProjectionVersions.Domain.OBSERVATIONS),
          listener.poll(Duration.ofSeconds(2)));
    }
  }

  @Test
  void observationFactsInvalidateOnRuntimeWritesAndClockOnlyLeaseExpiry() throws Exception {
    var domains = Set.of(ProjectionVersions.Domain.OBSERVATIONS);
    var before = versions.capture(domains).orElseThrow();
    String job = "cache-job-" + UUID.randomUUID();
    jdbc.update("INSERT INTO jobs(id,agent,started_at) VALUES(?,'fixture',now())", job);
    assertFalse(versions.current(before));
    var committed = versions.capture(domains).orElseThrow();
    assertTrue(versions.current(committed));
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          jdbc.update("UPDATE jobs SET started_at=started_at+interval '1 second' WHERE id=?", job);
          assertFalse(versions.canShare());
          status.setRollbackOnly();
        });
    // Sequence allocation is conservative on rollback; a fresh committed snapshot remains usable.
    var rolledBack = versions.capture(domains).orElseThrow();
    assertTrue(versions.current(rolledBack));
    UUID resource = UUID.randomUUID(), revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy',?,'source')",
        resource,
        resource.toString());
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size) VALUES(?,?,1,'fixture','text/plain','fixture',1)",
        revision,
        resource);
    jdbc.update(
        "INSERT INTO information_steps(revision_id,generation,stage,state,lease_token,lease_until) VALUES(?,1,'extract','running',?,clock_timestamp()+interval '2 seconds')",
        revision,
        UUID.randomUUID());
    var running = versions.capture(domains).orElseThrow();
    assertNotNull(running.validUntil());
    assertTrue(versions.current(running));
    long sleep =
        Math.max(
            0, Duration.between(Instant.now(), running.validUntil().plusMillis(100)).toMillis());
    assertTrue(sleep < 3000, "The observation window must include the running lease");
    TimeUnit.MILLISECONDS.sleep(sleep);
    assertFalse(
        versions.current(running),
        "Expiry must retire running/pending counts without a row update");
  }

  @Test
  void bootCompositionPreservesThePrimaryPoolMigratesBeforeRecoveryAndClosesOwnedResources() {
    var primaryPool = new AtomicReference<HikariDataSource>();
    var versionPool = new AtomicReference<HikariDataSource>();
    var listening = new AtomicReference<ProjectionChangeSignals>();
    var factory = new AtomicReference<ProjectionCaches>();
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class,
                FlywayAutoConfiguration.class))
        .withUserConfiguration(ProjectionCacheConfiguration.class)
        .withPropertyValues(
            "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            "spring.datasource.driver-class-name=org.postgresql.Driver",
            "spring.datasource.hikari.maximum-pool-size=3",
            "spring.datasource.hikari.schema=public",
            "spring.datasource.hikari.catalog=" + POSTGRES.getDatabaseName(),
            "spring.datasource.hikari.data-source-properties.ApplicationName=projection-cache-boot-fixture",
            "plowshare.projection-cache.version-connections=2",
            "plowshare.projection-cache.notification-poll=20ms",
            "plowshare.projection-cache.reconnect-delay=20ms",
            "plowshare.projection-cache.timeout=10s")
        .run(
            context -> {
              assertNull(context.getStartupFailure(), "Minimal Boot cache composition must start");
              assertEquals(
                  1,
                  context.getBeansOfType(DataSource.class).size(),
                  "Wrapping the version pool must not make Boot back off its primary DataSource");
              var primary =
                  assertInstanceOf(HikariDataSource.class, context.getBean(DataSource.class));
              var independent =
                  context.getBean(ProjectionCacheConfiguration.VersionConnections.class).source();
              primaryPool.set(primary);
              versionPool.set(independent);
              assertNotSame(primary, independent);
              assertEquals(3, primary.getMaximumPoolSize());
              assertEquals(2, independent.getMaximumPoolSize());
              assertTrue(independent.isReadOnly());
              assertEquals(primary.getSchema(), independent.getSchema());
              assertEquals(primary.getCatalog(), independent.getCatalog());
              assertEquals(
                  "projection-cache-boot-fixture",
                  independent.getDataSourceProperties().getProperty("ApplicationName"));
              assertNotNull(
                  context.getBean(Flyway.class).info().current(),
                  "Flyway must initialize the schema before the cache namespace/listener baseline");

              var signals = context.getBean(ProjectionChangeSignals.class);
              listening.set(signals);
              var ready = new CountDownLatch(1);
              var subscription =
                  signals.subscribe(
                      SEARCH,
                      new ProjectionChangeSignals.Observer() {
                        @Override
                        public void stateChanged(ProjectionChangeSignals.State state) {
                          if (state.ready()) ready.countDown();
                        }

                        @Override
                        public void changed(Set<ProjectionVersions.Domain> domains) {}
                      });
              try {
                assertTrue(
                    ready.await(5, TimeUnit.SECONDS),
                    "Listener did not recover after LISTEN commit");
                assertTrue(signals.healthy());
                var caches = context.getBean(ProjectionCaches.class);
                factory.set(caches);
                ProjectionCache<String> projection = caches.create("boot-projection-v1", SEARCH);
                assertTrue(projection.enabled());
                // Observe the existing bookkeeping contract to wait for an actual join, rather than
                // guessing that a second task has already completed its authoritative capture SQL.
                if (!(ReflectionTestUtils.getField(projection, "cache")
                    instanceof SnapshotCache<?> backing))
                  throw new AssertionError("Missing bounded projection backing");
                var key = new SnapshotCache.Key("boot-fixture", "selected-fixture");
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var loads = new AtomicInteger();
                try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                  var first =
                      callers.submit(
                          () ->
                              projection.read(
                                  key,
                                  true,
                                  () -> {
                                    loads.incrementAndGet();
                                    entered.countDown();
                                    awaitLoaderRelease(release);
                                    return "cached-boot-projection";
                                  },
                                  () ->
                                      fail(
                                          "A recovered projection unexpectedly bypassed shared admission"),
                                  value -> 64));
                  try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    var second =
                        callers.submit(
                            () ->
                                projection.read(
                                    key,
                                    true,
                                    () -> fail("A coalesced caller must not load again"),
                                    () -> fail("A coalesced caller must not bypass"),
                                    value -> 64));
                    CacheTestSupport.awaitStatistics(
                        backing, statistics -> statistics.coalesced() == 1);
                    release.countDown();
                    assertEquals("cached-boot-projection", first.get(5, TimeUnit.SECONDS));
                    assertEquals("cached-boot-projection", second.get(5, TimeUnit.SECONDS));
                  } finally {
                    release.countDown();
                  }
                }
                assertEquals(
                    "cached-boot-projection",
                    projection.read(
                        key,
                        true,
                        () -> fail("A warm typed projection must reuse its immutable DTO"),
                        () -> fail("A warm typed projection unexpectedly bypassed"),
                        value -> 64));
                assertEquals(1, loads.get());
                assertTrue(backing.statistics().hits() >= 1);
              } finally {
                subscription.close();
              }
            });
    assertTrue(primaryPool.get().isClosed());
    assertTrue(versionPool.get().isClosed());
    assertFalse(listening.get().healthy());
    assertThrows(IllegalStateException.class, listening.get()::start);
    assertThrows(IllegalStateException.class, () -> factory.get().create("after-close", SEARCH));
  }

  private static void awaitLoaderRelease(CountDownLatch release) {
    try {
      if (!release.await(5, TimeUnit.SECONDS))
        throw new AssertionError("Composition loader was not released");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Composition loader interrupted", interrupted);
    }
  }

  @Test
  void migrationInitializesCountersAndTheFirstObservedWriteInvalidatesItsBaseline() {
    assertTrue(jdbc.queryForObject("SELECT is_called FROM snapshot_cache_search", Boolean.class));
    var before = versions.capture(SEARCH).orElseThrow();
    assertEquals(versions.databaseIdentity(), before.database());
    assertTrue(versions.current(before));
    bump(jdbc, "code");
    assertFalse(versions.current(before));
    var after = versions.capture(SEARCH).orElseThrow();
    assertTrue(
        after.counters().get(ProjectionVersions.Domain.SEARCH)
            > before.counters().get(ProjectionVersions.Domain.SEARCH));
    assertTrue(versions.current(after));
  }

  @Test
  void anOlderAllocationCommittingAfterANewerOneCannotHideBehindTheSameCounter() throws Exception {
    try (var older = transaction();
        var newer = transaction()) {
      var oldJdbc = view(older);
      var newJdbc = view(newer);
      Long visible =
          outside.queryForObject(
              "SELECT version FROM embedding_slots WHERE slot='code'", Long.class);
      bump(oldJdbc, "code");
      String oldXid = oldJdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class);
      bump(newJdbc, "prose");
      newer.commit();

      var loaded = versions.capture(SEARCH).orElseThrow();
      assertTrue(loaded.pendingTransactions().contains(oldXid));
      assertEquals(
          visible,
          outside.queryForObject(
              "SELECT version FROM embedding_slots WHERE slot='code'", Long.class));
      assertTrue(versions.current(loaded));
      older.commit();

      var fresh = versions.capture(SEARCH).orElseThrow();
      assertEquals(
          loaded.counters(), fresh.counters(), "Commit does not allocate another sequence value");
      assertEquals(
          loaded.sourceVersion(),
          fresh.sourceVersion(),
          "Fresh capture cannot launder an old DTO's evidence");
      assertFalse(
          versions.current(loaded),
          "The cached loader token, not the fresh token, must be checked");
      assertTrue(versions.current(fresh));
    }
  }

  @Test
  void abortedPendingWritersDoNotPretendToHaveCommittedButTheirEarlierAllocationIsConservative()
      throws Exception {
    var before = versions.capture(SEARCH).orElseThrow();
    try (var writer = transaction()) {
      bump(view(writer), "code");
      var pending = versions.capture(SEARCH).orElseThrow();
      assertFalse(
          versions.current(before), "Allocation alone conservatively retires older versions");
      assertTrue(versions.current(pending));
      writer.rollback();
      assertTrue(
          versions.current(pending), "An aborted captured writer cannot expose a late commit");
    }
  }

  @Test
  void aSingleTailWriterIsCapturedWithoutAnyNewerWriterCommit() throws Exception {
    try (var writer = transaction()) {
      var pendingWriter = view(writer);
      bump(pendingWriter, "code");
      String xid = pendingWriter.queryForObject("SELECT pg_current_xact_id()::text", String.class);
      // No other writer allocates/commits here. Capture itself must finish its probe transaction
      // after observing the counter, otherwise a tail XID can be absent from snapshot xip.
      var loaded = versions.capture(SEARCH).orElseThrow();
      assertTrue(
          loaded.pendingTransactions().contains(xid),
          "Completed capture probe must expose the active tail writer");
      assertTrue(versions.current(loaded));
      writer.commit();
      var after = versions.capture(SEARCH).orElseThrow();
      assertEquals(loaded.counters(), after.counters());
      assertEquals(loaded.sourceVersion(), after.sourceVersion());
      assertFalse(
          versions.current(loaded),
          "Commit with no new allocation still retires the old loader token");
      assertTrue(versions.current(after));
    }
  }

  @Test
  void captureProbeCanCommitOnAnAuthoritativeConnectionEnforcingDatabaseReadOnlyMode() {
    var readOnly =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var properties = new java.util.Properties();
    properties.setProperty("options", "-c default_transaction_read_only=on");
    readOnly.setConnectionProperties(properties);
    assertEquals(
        "on",
        new JdbcTemplate(readOnly).queryForObject("SHOW transaction_read_only", String.class));
    var guarded = new JdbcProjectionVersions(jdbc, readOnly, 128);
    var captured = guarded.capture(SEARCH).orElseThrow();
    assertTrue(guarded.current(captured));
  }

  @Test
  void dirtyOuterTransactionsAndSavepointRollbackAreCheckedOnTheCallerConnection() {
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          assertTrue(versions.canShare());
          Object savepoint = status.createSavepoint();
          bump(jdbc, "code");
          assertFalse(versions.canShare());
          assertTrue(versions.capture(SEARCH).isEmpty());
          status.rollbackToSavepoint(savepoint);
          status.releaseSavepoint(savepoint);
          assertTrue(
              versions.canShare(), "SET LOCAL dirty marker rolls back with the source mutation");
          assertTrue(versions.capture(SEARCH).isPresent());
          bump(jdbc, "prose");
          assertFalse(versions.canShare());
          status.setRollbackOnly();
        });
    assertTrue(versions.canShare(), "Outer rollback does not poison the next caller");
    work.executeWithoutResult(status -> bump(jdbc, "code"));
    assertTrue(versions.canShare(), "Outer commit also resets the transaction-local marker");
  }

  @Test
  void aReadLockXidIsExcludedOnlyForItsSafeCallerAndDoesNotDestroyCrossRequestHits() {
    var captured = new java.util.concurrent.atomic.AtomicReference<ProjectionVersions.Token>();
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          jdbc.queryForObject(
              "SELECT slot FROM embedding_slots WHERE slot='code' FOR SHARE", String.class);
          String own =
              jdbc.queryForObject("SELECT pg_current_xact_id_if_assigned()::text", String.class);
          assertNotNull(own);
          var token = versions.capture(SEARCH).orElseThrow();
          assertFalse(token.pendingTransactions().contains(own));
          assertTrue(versions.current(token));
          captured.set(token);
        });
    assertTrue(
        versions.current(captured.get()), "Committing a safe read lock does not retire its DTO");
    assertEquals(
        captured.get().sourceVersion(), versions.capture(SEARCH).orElseThrow().sourceVersion());
  }

  @Test
  void repeatableReadCannotAdmitAndCannotHideAConcurrentCommitFromCurrent() {
    var token = versions.capture(SEARCH).orElseThrow();
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    work.executeWithoutResult(
        status -> {
          Long historical =
              jdbc.queryForObject(
                  "SELECT version FROM embedding_slots WHERE slot='code'", Long.class);
          assertFalse(versions.canShare());
          assertTrue(versions.capture(SEARCH).isEmpty());
          bump(outside, "code");
          assertEquals(
              historical,
              jdbc.queryForObject(
                  "SELECT version FROM embedding_slots WHERE slot='code'", Long.class));
          assertFalse(
              versions.current(token), "Fresh authority leases never reuse the caller snapshot");
        });
  }

  @Test
  void pendingCapacityBypassesCaptureButDatabaseIdentityRemainsAvailable() throws Exception {
    try (var first = transaction();
        var second = transaction()) {
      view(first).queryForObject("SELECT pg_current_xact_id()::text", String.class);
      view(second).queryForObject("SELECT pg_current_xact_id()::text", String.class);
      var bounded = new JdbcProjectionVersions(jdbc, source(), 1);
      assertNotNull(bounded.databaseIdentity());
      assertTrue(bounded.capture(SEARCH).isEmpty());
      first.rollback();
      second.rollback();
      assertTrue(bounded.capture(SEARCH).isPresent());
    }
  }

  @Test
  void noOpRegistrationDoesNotAdvanceCountersOrMarkTheCallerDirtyButTruncateDoes() {
    var token = versions.capture(SEARCH).orElseThrow();
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          assertEquals(
              0,
              jdbc.update(
                  "INSERT INTO embedding_slots(slot) VALUES('code') ON CONFLICT DO NOTHING"));
          assertTrue(versions.canShare());
          assertTrue(versions.current(token));
        });
    jdbc.execute("TRUNCATE chunks_embedding_staging");
    assertFalse(versions.current(token));
  }

  @Test
  void unrelatedInferenceAndRuntimeTicksDoNotFlushFileMetadataOrSearchProjections() {
    UUID budget = UUID.randomUUID();
    String owner = "cache-tick-owner-" + UUID.randomUUID();
    String schedule = "cache-tick-" + UUID.randomUUID();
    jdbc.update("INSERT INTO execution_allowances(id,call_limit) VALUES(?,10)", budget);
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'fixture')", owner);
    jdbc.update(
        "INSERT INTO schedules(name,cron,zone,emits,next_fire_at,defined_by) VALUES(?,'0 0 * * * *','UTC','fixture',clock_timestamp(),?)",
        schedule,
        owner);
    var all = Set.of(ProjectionVersions.Domain.values());
    var before = versions.capture(all).orElseThrow();
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          assertEquals(
              1,
              jdbc.queryForObject(
                  "SELECT ordinal FROM execution_allowance_claim(?,0,NULL)",
                  Integer.class,
                  budget));
          assertEquals(
              1,
              jdbc.update(
                  "UPDATE schedules SET next_fire_at=next_fire_at+interval '1 minute' WHERE name=?",
                  schedule));
          assertTrue(versions.canShare(), "Unrelated writes are not dirty projection sources");
        });
    assertTrue(versions.current(before));
    assertEquals(before.counters(), versions.capture(all).orElseThrow().counters());
  }

  @Test
  void documentProcessingAllowanceChangesMetadataButDoNotChangeSearchCandidates() {
    UUID resource = UUID.randomUUID();
    UUID revision = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO information_resources(id,namespace,source_name,kind) VALUES(?,'legacy',?,'source')",
        resource,
        resource.toString());
    jdbc.update(
        "INSERT INTO information_revisions(id,resource_id,ordinal,title,media_type,content_hash,byte_size,allowance_total) VALUES(?,?,1,'fixture','text/plain','fixture',1,10)",
        revision,
        resource);
    UUID budget =
        jdbc.queryForObject(
            "SELECT processing_budget_id FROM information_revisions WHERE id=?",
            UUID.class,
            revision);
    var document = versions.capture(Set.of(ProjectionVersions.Domain.DOCUMENTS)).orElseThrow();
    var search = versions.capture(SEARCH).orElseThrow();
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT ordinal FROM execution_allowance_claim(?,0,NULL)", Integer.class, budget));
    assertFalse(versions.current(document));
    assertTrue(versions.current(search));
  }

  @Test
  void expiryChangesTheTokenWindowAndAnOldCallerTransactionCannotAdmitItsHistoricalAuthority() {
    String owner = "cache-owner-" + UUID.randomUUID();
    String principal = "@service/" + UUID.randomUUID();
    UUID credential = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password,server_admin) VALUES(?,'fixture','SERVICE',false,false),(?,'fixture','SERVICE_TOKEN',false,false)",
        owner,
        principal);
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,?,'fixture',?,?,clock_timestamp()+interval '1 minute')",
        credential,
        owner,
        principal,
        "fixture-" + credential);
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    var captured = new java.util.concurrent.atomic.AtomicReference<ProjectionVersions.Token>();
    work.executeWithoutResult(
        status -> {
          jdbc.queryForObject("SELECT transaction_timestamp()", OffsetDateTime.class);
          outside.update(
              "UPDATE service_tokens SET expires_at=clock_timestamp()+interval '1 second' WHERE id=?",
              credential);
          var token = versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).orElseThrow();
          assertNotNull(token.validUntil());
          captured.set(token);
          waitUntil(token.validUntil());
          assertFalse(versions.current(token));
          assertFalse(versions.canShare());
          assertTrue(versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).isEmpty());
        });
    var fresh = versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).orElseThrow();
    assertEquals(captured.get().counters(), fresh.counters());
    assertNotEquals(captured.get().sourceVersion(), fresh.sourceVersion());
    assertTrue(versions.current(fresh));
  }

  @Test
  void transcriptDomainFencesNoOpsRollbackTruncateAndCommitNotifications() throws Exception {
    var domains = Set.of(ProjectionVersions.Domain.TRANSCRIPTS);
    var baseline = versions.capture(domains).orElseThrow();
    jdbc.update("UPDATE entries SET ordinal=ordinal WHERE false");
    assertTrue(versions.current(baseline), "An empty write must not invalidate transcript DTOs");
    var notifications = new PostgresProjectionNotificationSource(source());
    var listener = notifications.open();
    try (var writer = transaction()) {
      listener.listen();
      view(writer).execute("TRUNCATE entries CASCADE");
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      writer.rollback();
      assertFalse(versions.current(baseline), "Rolled back sequence allocations are conservative");
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      var afterRollback = versions.capture(domains).orElseThrow();
      view(writer).execute("TRUNCATE entries CASCADE");
      writer.commit();
      assertFalse(versions.current(afterRollback));
      assertTrue(
          listener.poll(Duration.ofSeconds(2)).contains(ProjectionVersions.Domain.TRANSCRIPTS));
      assertTrue(versions.current(versions.capture(domains).orElseThrow()));
    } finally {
      listener.close();
    }
  }

  @Test
  void listenerUnderstandsEveryVersionDomainIncludingObservationWakeups() {
    var source = new PostgresProjectionNotificationSource(source());
    try (var listener = source.open()) {
      listener.listen();
      for (var domain : ProjectionVersions.Domain.values()) {
        jdbc.queryForObject(
            "SELECT pg_notify(?,?)",
            String.class,
            domain == ProjectionVersions.Domain.READABILITY
                ? PostgresProjectionNotificationSource.READABILITY_CHANNEL
                : PostgresProjectionNotificationSource.CHANNEL,
            domain.name().toLowerCase(java.util.Locale.ROOT));
        assertEquals(Set.of(domain), listener.poll(Duration.ofSeconds(2)));
      }
    }
  }

  @Test
  void notificationsRequireCommitAndRollbackEmitsNoHintOnTheIndependentListener() throws Exception {
    var notifications = new PostgresProjectionNotificationSource(source());
    var listener = notifications.open();
    try (var writer = transaction()) {
      listener.listen();
      bump(view(writer), "code");
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      writer.commit();
      assertTrue(listener.poll(Duration.ofSeconds(2)).contains(ProjectionVersions.Domain.SEARCH));
      bump(view(writer), "prose");
      writer.rollback();
      assertTrue(listener.poll(Duration.ofMillis(20)).isEmpty());
      listener.close();
      assertThrows(IllegalStateException.class, () -> listener.poll(Duration.ofMillis(1)));
    } finally {
      listener.close();
    }
  }

  @Test
  void expiryBetweenTheCallerGuardAndCapturedWindowCannotAdmitAWarmReadWithHistoricalNow() {
    String owner = "cache-window-owner-" + UUID.randomUUID();
    String principal = "@service/" + UUID.randomUUID();
    UUID credential = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO admins(handle,password_hash,account_kind,must_change_password,server_admin) VALUES(?,'fixture','SERVICE',false,false),(?,'fixture','SERVICE_TOKEN',false,false)",
        owner,
        principal);
    jdbc.update(
        "INSERT INTO service_tokens(id,owner_handle,name,principal_handle,digest,expires_at) VALUES(?,?,'fixture',?,?,clock_timestamp()+interval '1 second')",
        credential,
        owner,
        principal,
        "fixture-" + credential);
    Instant expiry =
        outside
            .queryForObject(
                "SELECT expires_at FROM service_tokens WHERE id=?",
                OffsetDateTime.class,
                credential)
            .toInstant();
    var leases = new AtomicInteger();
    var statements = new AtomicInteger();
    var crossed = new java.util.concurrent.atomic.AtomicBoolean();
    DataSource staged =
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            leases.incrementAndGet();
            Connection connection = data.getConnection();
            return (Connection)
                java.lang.reflect.Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (proxy, method, arguments) -> {
                      if (method.getName().equals("createStatement")
                          || method.getName().equals("prepareStatement"))
                        statements.incrementAndGet();
                      if (method.getName().equals("prepareStatement")
                          && ((String) arguments[0]).contains("pg_snapshot_xip")) {
                        // Spring may retain one independent lease until the caller transaction
                        // ends.
                        // Stage the SECOND statement, not the second checkout: its probe must
                        // already
                        // be committed even when this same physical autocommit connection is
                        // reused.
                        assertEquals(
                            2,
                            statements.get(),
                            "Pending window must follow the completed counter statement");
                        assertTrue(connection.getAutoCommit());
                        int backend =
                            connection.unwrap(org.postgresql.PGConnection.class).getBackendPID();
                        assertTrue(
                            outside.queryForObject(
                                "SELECT state='idle' AND backend_xid IS NULL FROM pg_stat_activity WHERE pid=?",
                                Boolean.class,
                                backend),
                            "Capture probe must commit before the pending-window statement");
                        assertTrue(
                            ((String) arguments[0]).contains("pg_snapshot_xip"),
                            "Fixture must intercept the pending-window statement");
                        waitUntil(expiry);
                        crossed.set(true);
                      }
                      try {
                        return method.invoke(connection, arguments);
                      } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
          }

          @Override
          public Connection getConnection(String username, String password) throws SQLException {
            throw new java.sql.SQLFeatureNotSupportedException(
                "Fixture uses configured connection credentials");
          }
        };
    var guarded = new JdbcProjectionVersions(jdbc, staged, 128);
    var work = new TransactionTemplate(new DataSourceTransactionManager(data));
    work.executeWithoutResult(
        status -> {
          assertTrue(guarded.canShare(), "Fixture must start before the expiry boundary");
          var captured = guarded.capture(Set.of(ProjectionVersions.Domain.AUTHORITY));
          assertTrue(crossed.get(), "Fixture must exercise expiry between the two capture stages");
          assertEquals(
              2, statements.get(), "Counter/probe and pending/window are distinct statements");
          assertTrue(
              leases.get() >= 1 && leases.get() <= 2,
              "Independent autocommit statements may reuse a synchronized lease");
          assertTrue(
              captured.isEmpty(),
              () ->
                  "The new authority window cannot use historical caller now(): expiry="
                      + expiry
                      + ", token="
                      + captured);
        });
    assertTrue(
        versions.capture(Set.of(ProjectionVersions.Domain.AUTHORITY)).isPresent(),
        "A later caller whose SQL clock belongs to the new window may capture normally");
  }

  @Test
  void aHealthyIndependentPoolWithAnotherDatabaseNamespaceCannotFenceTheCallersData() {
    String schema = "cache_mismatch_" + UUID.randomUUID().toString().replace("-", "");
    // Generated fixture identifiers contain only this literal prefix and UUID hexadecimal digits.
    outside.execute("CREATE SCHEMA " + schema);
    try {
      outside.execute(
          "CREATE TABLE "
              + schema
              + ".snapshot_cache_identity(singleton boolean PRIMARY KEY,database_id uuid NOT NULL)");
      UUID different = UUID.randomUUID();
      outside.update(
          "INSERT INTO " + schema + ".snapshot_cache_identity VALUES(true,?)", different);
      var other =
          new DriverManagerDataSource(
              POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
      var properties = new java.util.Properties();
      properties.setProperty("currentSchema", schema + ",public");
      other.setConnectionProperties(properties);
      var mismatched = new JdbcProjectionVersions(jdbc, other, 128);
      assertEquals(different, mismatched.databaseIdentity());
      assertNotEquals(versions.databaseIdentity(), mismatched.databaseIdentity());
      // The public counter-capture function resolves this independent session's schema identity;
      // sequence values can match exactly, but that namespace must never authorize RAM admission.
      assertTrue(mismatched.capture(SEARCH).isEmpty());
      assertTrue(versions.capture(SEARCH).isPresent());
    } finally {
      outside.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private static DataSource source() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection transaction() throws SQLException {
    Connection connection = data.getConnection();
    connection.setAutoCommit(false);
    return connection;
  }

  private static JdbcTemplate view(Connection connection) {
    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
  }

  private static void bump(JdbcTemplate target, String slot) {
    assertEquals(
        1,
        target.update(
            "UPDATE embedding_slots SET active_space_id=active_space_id WHERE slot=?", slot));
  }

  private static void waitUntil(Instant until) {
    outside.queryForObject(
        "SELECT pg_sleep(greatest(0,extract(epoch FROM (?::timestamptz-clock_timestamp())))+0.02)",
        (row, index) -> true,
        until.atOffset(ZoneOffset.UTC));
  }
}
