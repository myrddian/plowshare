package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class ProjectionTelemetryTest {
  private static final String CACHE = "plowshare.projection.cache";
  private static final String LISTENER = "plowshare.projection.listener";
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.AUTHORITY);

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  private static ProjectionCacheProperties properties() {
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
        1,
        2);
  }

  @Test
  void factoryExportsValidatedOutcomesAndBackingStatisticsWithoutAdditionalSql() {

    var versions = mock(ProjectionVersions.class);
    var token =
        new ProjectionVersions.Token(
            new UUID(0, 1),
            Map.of(ProjectionVersions.Domain.AUTHORITY, 1L),
            List.of(),
            "9",
            null,
            null);
    when(versions.databaseIdentity()).thenReturn(token.database());
    when(versions.capture(DOMAINS)).thenReturn(Optional.of(token));
    when(versions.current(any())).thenReturn(true);
    when(versions.canShare()).thenReturn(true);
    var signals = new VersionedProjectionCacheTest.Signals();
    try (var caches =
        new ProjectionCaches(
            versions, signals, properties(), null, new MicrometerProjectionTelemetry(registry))) {
      var cache = caches.<String>create("authority-snapshot", DOMAINS);
      var key = new SnapshotCache.Key("private-principal", "private-selection");
      for (int i = 0; i < 2; i++)
        assertEquals("value", cache.read(key, true, () -> "value", value -> 64));
      assertEquals(1, event(registry, "load"));
      assertEquals(1, event(registry, "loaded_return"));
      assertEquals(1, event(registry, "versioned_hit"));
      assertEquals(0, event(registry, "trusted_hit"));
      assertEquals(1, registry.get(CACHE + ".entries").gauge().value());
      assertEquals(0, registry.get(CACHE + ".loads.active").gauge().value());
      assertEquals(
          1, registry.get(CACHE + ".backing").tag("event", "hits").functionCounter().count());
      int meters = registry.getMeters().size();
      assertThrows(IllegalStateException.class, () -> caches.create("rejected", DOMAINS));
      assertEquals(meters, registry.getMeters().size(), "Capacity refusal must not add labels");
      clearInvocations(versions);
      for (var meter : registry.getMeters())
        meter.measure().forEach(measurement -> measurement.getValue());
      verifyNoInteractions(versions);
      assertTrue(
          registry.getMeters().stream()
              .flatMap(meter -> meter.getId().getTags().stream())
              .allMatch(tag -> Set.of("projection", "event").contains(tag.getKey())));
      assertFalse(registry.getMeters().toString().contains("private-principal"));
      assertFalse(registry.getMeters().toString().contains("private-selection"));
    }
    assertEquals(0, registry.get(CACHE + ".entries").gauge().value());
  }

  @Test
  void listenerReportsMissingFreshStaleLostAndDisabledProofWithoutSendingProbes() {

    var signals = mock(ProjectionChangeSignals.class);
    var properties =
        new ProjectionNotificationProperties(
            true, Duration.ofMillis(750), Duration.ofMillis(100), Duration.ofSeconds(3));
    new MicrometerProjectionTelemetry(registry).observeListener(signals, properties);
    when(signals.deliveredThrough()).thenReturn(OptionalLong.empty());
    assertEquals(0, gauge(registry, "ready"));
    assertEquals(0, gauge(registry, "proof.available"));
    assertTrue(Double.isNaN(gauge(registry, "proof.age")));
    assertEquals(0.75, gauge(registry, "staleness"));
    assertEquals(1, gauge(registry, "notified.enabled"));
    when(signals.healthy()).thenReturn(true);
    when(signals.deliveredThrough()).thenReturn(OptionalLong.of(System.nanoTime()));
    assertEquals(1, gauge(registry, "ready"));
    assertEquals(1, gauge(registry, "proof.available"));
    assertTrue(gauge(registry, "proof.age") >= 0);
    when(signals.deliveredThrough())
        .thenReturn(OptionalLong.of(System.nanoTime() - 2_000_000_000L));
    assertTrue(gauge(registry, "proof.age") >= 2);
    when(signals.healthy()).thenReturn(false);
    when(signals.deliveredThrough()).thenReturn(OptionalLong.empty());
    when(signals.reconnectAttempts()).thenReturn(3L);
    assertEquals(0, gauge(registry, "proof.available"));
    assertTrue(Double.isNaN(gauge(registry, "proof.age")));
    assertEquals(3, registry.get(LISTENER + ".reconnects").functionCounter().count());
    verify(signals, never()).start();
    verify(signals, never()).subscribe(any(), any());
  }

  @Test
  void disabledProbesDoNotClaimFreshDelivery() {

    var signals = mock(ProjectionChangeSignals.class);
    when(signals.deliveredThrough()).thenReturn(OptionalLong.empty());
    new MicrometerProjectionTelemetry(registry)
        .observeListener(
            signals,
            new ProjectionNotificationProperties(
                false, Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(3)));
    assertEquals(0, gauge(registry, "notified.enabled"));
    assertEquals(0, gauge(registry, "proof.available"));
    assertTrue(Double.isNaN(gauge(registry, "proof.age")));
  }

  @Test
  void configurationUsesTheAvailableRegistryAndSupportsContextsWithoutActuator() {
    var configuration = new ProjectionCacheConfiguration();
    var beans = new DefaultListableBeanFactory();
    assertSame(
        ProjectionTelemetry.NONE,
        configuration.projectionTelemetry(beans.getBeanProvider(MeterRegistry.class)));

    beans.registerSingleton("registry", registry);
    var telemetry = configuration.projectionTelemetry(beans.getBeanProvider(MeterRegistry.class));
    telemetry.observeListener(
        mock(ProjectionChangeSignals.class),
        new ProjectionNotificationProperties(
            true, Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(3)));
    assertNotNull(registry.find(LISTENER + ".ready").gauge());
  }

  private static double event(SimpleMeterRegistry registry, String event) {
    return registry
        .get(CACHE + ".events")
        .tags("projection", "authority-snapshot", "event", event)
        .functionCounter()
        .count();
  }

  private static double gauge(SimpleMeterRegistry registry, String name) {
    return registry.get(LISTENER + "." + name).gauge().value();
  }
}
