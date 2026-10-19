package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

class ProjectionCachesTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.SEARCH);

  @Test
  void duplicateProjectionIsRejectedAndItsTemporarySubscriptionIsDisposed() {
    var signals = new VersionedProjectionCacheTest.Signals();
    var factory = new ProjectionCaches(versions(), signals, properties(2));
    factory.create("search", DOMAINS);
    assertThrows(IllegalStateException.class, () -> factory.create("search", DOMAINS));
    assertEquals(1, signals.observers.size());
    assertEquals(1, signals.disposed.get());
    factory.close();
    factory.close();
    assertEquals(0, signals.observers.size());
    assertEquals(2, signals.disposed.get());
    assertThrows(IllegalStateException.class, () -> factory.create("later", DOMAINS));
  }

  @Test
  void projectionCountLimitRejectsBeforeAllocatingAnotherLifecycle() {
    var signals = new VersionedProjectionCacheTest.Signals();
    try (var factory = new ProjectionCaches(versions(), signals, properties(1))) {
      factory.create("search", DOMAINS);
      assertThrows(IllegalStateException.class, () -> factory.create("documents", DOMAINS));
      assertEquals(1, signals.observers.size());
      assertEquals(0, signals.disposed.get());
    }
    assertEquals(1, signals.disposed.get());
  }

  @Test
  void failedSubscriptionDoesNotConsumeTheOnlyProjectionReservation() {
    var signals = spy(new VersionedProjectionCacheTest.Signals());
    doThrow(new IllegalStateException("Subscription fixture unavailable"))
        .doCallRealMethod()
        .when(signals)
        .subscribe(anySet(), any());
    try (var factory = new ProjectionCaches(versions(), signals, properties(1))) {
      assertThrows(IllegalStateException.class, () -> factory.create("first", DOMAINS));
      assertEquals(0, signals.observers.size());
      factory.create("second", DOMAINS);
      assertEquals(1, signals.observers.size());
    }
    assertEquals(1, signals.disposed.get());
  }

  @Test
  void closeRacingWithCreationDisposesLateRegistrationBeforeReturningIt() throws Exception {
    var signals = spy(new VersionedProjectionCacheTest.Signals());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return invocation.callRealMethod();
            })
        .when(signals)
        .subscribe(anySet(), any());
    var factory = new ProjectionCaches(versions(), signals, properties(2));
    try (var creator = Executors.newVirtualThreadPerTaskExecutor()) {
      var creating =
          creator.submit(
              () ->
                  assertThrows(
                      IllegalStateException.class, () -> factory.create("search", DOMAINS)));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        factory.close();
      } finally {
        release.countDown();
      }
      creating.get(5, TimeUnit.SECONDS);
      assertEquals(0, signals.observers.size());
      assertEquals(1, signals.disposed.get());
    } finally {
      factory.close();
    }
  }

  @Test
  void independentVersionPoolUsesConfiguredDatabaseAndExplicitConnectionBoundWithoutOpeningIt() {
    var database = new DataSourceProperties();
    database.setUrl("jdbc:postgresql://database.example.invalid/projection-fixture");
    database.setUsername("fixture-user");
    database.setPassword("fixture-password");
    var configuration = new ProjectionCacheConfiguration();
    try (var connections = configuration.projectionVersionConnections(database, properties(2))) {
      assertEquals(database.getUrl(), connections.source().getJdbcUrl());
      assertEquals(database.getUsername(), connections.source().getUsername());
      assertEquals(2, connections.source().getMaximumPoolSize());
      assertEquals(0, connections.source().getMinimumIdle());
      assertTrue(connections.source().isReadOnly());
      assertNull(
          connections.source().getHikariPoolMXBean(), "Configuration must not contact PostgreSQL");
    }
  }

  @Test
  void authoritativeConnectionsFollowActualPrimaryPoolOverrides() {
    var database = new DataSourceProperties();
    database.setUrl("jdbc:postgresql://generic.example.invalid/unused-fixture");
    database.setUsername("generic-fixture");
    database.setPassword("generic-fixture-password");
    var configuration = new ProjectionCacheConfiguration();
    try (var primary = new com.zaxxer.hikari.HikariDataSource()) {
      primary.setJdbcUrl("jdbc:postgresql://primary.example.invalid/actual-fixture");
      primary.setUsername("actual-fixture");
      primary.setPassword("actual-fixture-password");
      primary.setDriverClassName("org.postgresql.Driver");
      primary.addDataSourceProperty("ApplicationName", "actual-fixture");
      primary.setSchema("fixture_schema");
      try (var connections =
          configuration.projectionVersionConnections(database, properties(2), primary)) {
        var source = connections.source();
        assertEquals(primary.getJdbcUrl(), source.getJdbcUrl());
        assertEquals(primary.getUsername(), source.getUsername());
        assertEquals(primary.getPassword(), source.getPassword());
        assertEquals(primary.getDriverClassName(), source.getDriverClassName());
        assertEquals(primary.getSchema(), source.getSchema());
        assertEquals(primary.getDataSourceProperties(), source.getDataSourceProperties());
        assertNotSame(primary.getDataSourceProperties(), source.getDataSourceProperties());
        assertNull(source.getHikariPoolMXBean());
      }
    }
  }

  private static ProjectionVersions versions() {
    var versions = mock(ProjectionVersions.class);
    when(versions.databaseIdentity()).thenReturn(new UUID(0, 1));
    return versions;
  }

  private static ProjectionCacheProperties properties(int projections) {
    return new ProjectionCacheProperties(
        8,
        2_000_000,
        4,
        4_000_000,
        16,
        8,
        64,
        64_000,
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        32,
        projections,
        2);
  }
}
