package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionVersionsTest {
  private static final UUID DATABASE = UUID.fromString("618b82ec-82c8-4484-9a27-cf3c50a9094a");

  @Test
  void identityCoalescesAcrossReadersButRetainsEachLoadersPendingEvidence() {
    var first = token(1, List.of("101"), null, null);
    var second = token(1, List.of("102"), null, null);
    assertEquals(first.sourceVersion(), second.sourceVersion());
    assertNotEquals(first, second, "Stored loader evidence must not be replaced by fresh evidence");
    assertNotEquals(first.sourceVersion(), token(2, List.of("101"), null, null).sourceVersion());
    assertEquals(64, first.sourceVersion().value().length());
    assertEquals(
        java.util.Set.of(new SnapshotCache.Dependency("projection:SEARCH")), first.dependencies());
  }

  @Test
  void authorityWindowsParticipateInIdentityEvenWithoutAMutation() {
    Instant boundary = Instant.parse("2026-10-20T00:00:00Z");
    var before = token(1, List.of(), null, boundary);
    var after = token(1, List.of(), boundary, null);
    assertNotEquals(before.sourceVersion(), after.sourceVersion());
    assertThrows(IllegalArgumentException.class, () -> token(1, List.of(), boundary, boundary));
  }

  @Test
  void capturedMetadataIsDeeplyImmutableAndBounded() {
    var counters = new EnumMap<ProjectionVersions.Domain, Long>(ProjectionVersions.Domain.class);
    counters.put(ProjectionVersions.Domain.SEARCH, 1L);
    var pending = new ArrayList<>(List.of("100"));
    var captured = new ProjectionVersions.Token(DATABASE, counters, pending, null, null);
    counters.put(ProjectionVersions.Domain.SEARCH, 9L);
    pending.clear();
    assertEquals(1, captured.counters().get(ProjectionVersions.Domain.SEARCH));
    assertEquals(List.of("100"), captured.pendingTransactions());
    assertThrows(UnsupportedOperationException.class, () -> captured.counters().clear());
    assertThrows(UnsupportedOperationException.class, () -> captured.pendingTransactions().clear());
    assertThrows(IllegalArgumentException.class, () -> token(-1, List.of(), null, null));
    assertThrows(IllegalArgumentException.class, () -> token(1, List.of("100", "100"), null, null));
    assertThrows(IllegalArgumentException.class, () -> token(1, List.of("0"), null, null));
    assertThrows(IllegalArgumentException.class, () -> token(1, List.of("1,2"), null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> token(1, List.of("18446744073709551616"), null, null));
  }

  private static ProjectionVersions.Token token(
      long value, List<String> pending, Instant start, Instant until) {
    return new ProjectionVersions.Token(
        DATABASE, Map.of(ProjectionVersions.Domain.SEARCH, value), pending, start, until);
  }
}
