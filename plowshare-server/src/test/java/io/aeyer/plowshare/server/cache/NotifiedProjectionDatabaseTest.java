package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL delivery: probe echoes follow commit order behind authority triggers' NOTIFY,
 * other sessions' probes are ignored, and a committed revocation reaches a notification-trusted
 * cache within its staleness bound with no per-read version SQL in between.
 */
@Tag("full-db")
@Testcontainers
class NotifiedProjectionDatabaseTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16");

  private static final Set<ProjectionVersions.Domain> AUTHORITY =
      Set.of(ProjectionVersions.Domain.AUTHORITY);
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    Flyway.configure().dataSource(source()).load().migrate();
    jdbc = new JdbcTemplate(source());
  }

  private static DataSource source() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  @Test
  void anEchoArrivesAfterEveryEarlierCommitAndOtherSessionsProbesAreIgnored() {
    var probes = new PostgresProjectionNotificationSource(source(), Duration.ofSeconds(3));
    try (var mine = probes.open();
        var other = probes.open()) {
      mine.listen();
      other.listen();
      assertTrue(other.heartbeat(7));
      String handle = "probe-" + UUID.randomUUID();
      jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'x')", handle);
      assertTrue(mine.heartbeat(1));

      var seen = java.util.EnumSet.noneOf(ProjectionVersions.Domain.class);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (mine.heartbeatsReceived() < 1) {
        assertTrue(System.nanoTime() < deadline, "The probe must be echoed");
        seen.addAll(mine.poll(Duration.ofMillis(200)));
      }
      assertTrue(seen.contains(ProjectionVersions.Domain.AUTHORITY), "Commit order is preserved");
      assertEquals(1, mine.heartbeatsReceived(), "Another session's probe 7 is not this echo");
    }
  }

  @Test
  void aCommittedRevocationReachesATrustedCacheWithinTheStalenessBound() throws Exception {
    String handle = "revoked-" + UUID.randomUUID();
    jdbc.update("INSERT INTO admins(handle,password_hash) VALUES(?,'x')", handle);
    var versions = new JdbcProjectionVersions(jdbc, source(), 128);
    var staleness = Duration.ofSeconds(2);
    try (var signals =
            new ListeningProjectionChangeSignals(
                new PostgresProjectionNotificationSource(source(), Duration.ofSeconds(1)),
                versions,
                4,
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ofMillis(100),
                Duration.ofSeconds(1));
        var cache =
            new VersionedProjectionCache<Boolean>(
                new BoundedSnapshotCache<>(
                    new SnapshotCache.Namespace(versions.databaseIdentity().toString(), "probe"),
                    new SnapshotCache.Limits(
                        8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(10))),
                versions,
                signals,
                AUTHORITY,
                1_000_000,
                staleness)) {
      signals.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (signals.deliveredThrough().isEmpty()) {
        assertTrue(System.nanoTime() < deadline, "Probing listener must become fresh");
        Thread.sleep(20);
      }
      var loads = new AtomicInteger();
      var key = new SnapshotCache.Key("enabled", handle);
      java.util.function.Supplier<Boolean> read =
          () ->
              cache.read(
                  key,
                  true,
                  () -> {
                    loads.incrementAndGet();
                    return jdbc.queryForObject(
                        "SELECT enabled FROM admins WHERE handle=?", Boolean.class, handle);
                  },
                  () -> fail("Cacheable reads stay cached"),
                  value -> 64);
      assertTrue(read.get());
      for (int check = 0; check < 20; check++) assertTrue(read.get());
      assertEquals(1, loads.get(), "Repeat checks are answered from memory");

      jdbc.update("UPDATE admins SET enabled=false WHERE handle=?", handle);
      long revoked = System.nanoTime();
      while (read.get()) {
        assertTrue(
            System.nanoTime() - revoked < staleness.toNanos(),
            "A revocation must take effect within the staleness bound");
        Thread.sleep(10);
      }
      assertEquals(2, loads.get());
    }
  }
}
