package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.cache.*;
import io.aeyer.plowshare.server.reads.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class InformationLogVisibilityTest {
  private static final Set<ProjectionVersions.Domain> DOMAINS =
      Set.of(ProjectionVersions.Domain.READABILITY, ProjectionVersions.Domain.AUTHORITY);

  private static final class Fixture implements AutoCloseable {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ProjectionVersions versions = mock(ProjectionVersions.class);
    final ProjectionVersions.Token token =
        new ProjectionVersions.Token(
            UUID.randomUUID(),
            Map.of(
                ProjectionVersions.Domain.READABILITY, 1L, ProjectionVersions.Domain.AUTHORITY, 1L),
            List.of(),
            "999999",
            null,
            null);
    final BoundedReadAdmission admission =
        new BoundedReadAdmission(new ReadCapacityProperties(16, 4, 2, 1, 64, 4));
    final VersionedProjectionCache<InformationLogVisibility.Facts> cache;
    final JdbcInformationLogVisibility visibility;

    Fixture() {
      when(versions.capture(DOMAINS)).thenReturn(Optional.of(token));
      when(versions.current(any())).thenReturn(true);
      when(versions.canShare()).thenReturn(true);
      var signals = mock(ProjectionChangeSignals.class);
      when(signals.subscribe(eq(DOMAINS), any()))
          .thenAnswer(
              call -> {
                ProjectionChangeSignals.Observer observer = call.getArgument(1);
                observer.stateChanged(new ProjectionChangeSignals.State(1, true));
                return (ProjectionChangeSignals.Subscription) () -> {};
              });
      cache =
          new VersionedProjectionCache<>(
              new BoundedSnapshotCache<>(
                  new SnapshotCache.Namespace(token.database().toString(), "log-visibility"),
                  new SnapshotCache.Limits(
                      8, 4_000_000, 2, 4_000_000, 64, 64, 2, 32, Duration.ofSeconds(10))),
              versions,
              signals,
              DOMAINS,
              1_000_000);
      visibility = new JdbcInformationLogVisibility(jdbc, cache, versions, admission);
      when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenReturn(List.of("restricted"));
    }

    public void close() {
      cache.close();
    }
  }

  @Test
  void feedsShareOneGraphButAccountsRemainSeparateAndEveryConsumerIsFenced() {
    try (var f = new Fixture()) {
      for (String feed : List.of("inbox", "unread", "orchestrations", "history"))
        assertEquals(
            feed,
            f.visibility.read(
                "reader",
                facts -> {
                  assertEquals(List.of("restricted"), facts.blocked().orElseThrow());
                  return feed;
                }));
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
      f.visibility.read("other", facts -> "other");
      verify(f.jdbc, times(2)).queryForList(anyString(), eq(String.class), any(Object[].class));
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }

  @Test
  void revocationDuringTheConsumerRefusesWithoutRepeatingItsQuery() {
    try (var f = new Fixture()) {
      var queries = new AtomicInteger();
      assertThrows(
          ReadUnavailableException.class,
          () ->
              f.visibility.read(
                  "reader",
                  facts -> {
                    queries.incrementAndGet();
                    when(f.versions.current(f.token)).thenReturn(false);
                    return "protected";
                  }));
      assertEquals(1, queries.get());
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
    }
  }

  @Test
  void dirtyTransactionsUseStatementAuthorityAndOversizedDenySetsNeverBecomePartialAllows() {
    try (var f = new Fixture()) {
      when(f.versions.capture(DOMAINS)).thenReturn(Optional.empty());
      assertEquals(true, f.visibility.read("reader", facts -> facts.blocked().isEmpty()));
      verifyNoInteractions(f.jdbc);
      when(f.versions.capture(DOMAINS)).thenReturn(Optional.of(f.token));
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenReturn(Collections.nCopies(32769, "blocked"));
      for (int read = 0; read < 2; read++)
        assertEquals(
            "authoritative",
            f.visibility.read(
                "reader",
                facts -> {
                  assertTrue(
                      facts.blocked().isEmpty(),
                      "An oversized deny set must use SQL authority, never a truncated allow set");
                  assertEquals(1, f.admission.active(ReadAdmission.Work.AUTHORITY));
                  return "authoritative";
                }));
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }

  @Test
  void aThousandDuplicateRequestsHaveOnePhysicalGraphFillAndBoundedWaiters() throws Exception {
    try (var f = new Fixture();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenAnswer(
              call -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return List.of("restricted");
              });
      var reads = new ArrayList<Future<Boolean>>();
      for (int request = 0; request < 1000; request++)
        reads.add(
            executor.submit(
                () -> {
                  try {
                    return f.visibility.read(
                        "reader", facts -> facts.blocked().orElseThrow().contains("restricted"));
                  } catch (ReadUnavailableException capacity) {
                    return false;
                  }
                }));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      int accepted = 0;
      for (var read : reads) if (read.get(10, TimeUnit.SECONDS)) accepted++;
      assertTrue(accepted > 0);
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }
}
