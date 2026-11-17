package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.cache.BoundedSnapshotCache;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.aeyer.plowshare.server.cache.SnapshotCache;
import io.aeyer.plowshare.server.cache.VersionedProjectionCache;
import io.aeyer.plowshare.server.reads.BoundedReadAdmission;
import io.aeyer.plowshare.server.reads.ReadAdmission;
import io.aeyer.plowshare.server.reads.ReadCapacityProperties;
import io.aeyer.plowshare.server.reads.ReadDeadline;
import io.aeyer.plowshare.server.reads.ReadFailedException;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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
      this(Duration.ofSeconds(2));
    }

    Fixture(Duration budget) {
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
      visibility =
          new JdbcInformationLogVisibility(
              jdbc, cache, versions, admission, LogReadabilityCheck.none(), budget);
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
  void revocationDuringTheConsumerDiscardsTentativeAllowsAndUsesOneAuthoritativeRead() {
    try (var f = new Fixture()) {
      var queries = new AtomicInteger();
      var result =
          f.visibility.read(
              "reader",
              facts -> {
                queries.incrementAndGet();
                if (facts.blocked().isPresent()) {
                  when(f.versions.current(f.token)).thenReturn(false);
                  return List.of("protected");
                }
                assertEquals(1, f.admission.active(ReadAdmission.Work.AUTHORITY));
                return List.<String>of();
              });
      assertEquals(List.of(), result, "The tentative stale allow must never be returned");
      assertEquals(2, queries.get());
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }

  @Test
  void unrelatedDomainChurnDoesNotInvalidateTheAuthoritativeFallback() {
    try (var f = new Fixture()) {
      var queries = new AtomicInteger();
      assertEquals(
          "current page",
          f.visibility.read(
              "reader",
              facts -> {
                queries.incrementAndGet();
                when(f.versions.current(f.token)).thenReturn(false);
                return facts.blocked().isPresent() ? "tentative page" : "current page";
              }));
      assertEquals(2, queries.get(), "One direct read, without a cache reload or retry loop");
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
    }
  }

  @Test
  void concurrentRacedReadsWaitForTheSameAccountsAuthoritativeSeat() throws Exception {
    try (var f = new Fixture();
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var cached = new CountDownLatch(2);
      var firstAuthoritative = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var authoritative = new AtomicInteger();
      Function<InformationLogVisibility.Facts, String> reader =
          facts -> {
            if (facts.blocked().isPresent()) {
              cached.countDown();
              await(cached);
              when(f.versions.current(f.token)).thenReturn(false);
              return "tentative";
            }
            assertEquals(1, f.admission.active(ReadAdmission.Work.AUTHORITY));
            if (authoritative.incrementAndGet() == 1) {
              firstAuthoritative.countDown();
              await(release);
            }
            return "current page";
          };
      var first = callers.submit(() -> f.visibility.read("reader", reader));
      var second = callers.submit(() -> f.visibility.read("reader", reader));
      try {
        assertTrue(firstAuthoritative.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (f.admission.waitingAuthoritative() != 1 && System.nanoTime() - deadline < 0)
          Thread.sleep(1);
        assertEquals(1, f.admission.waitingAuthoritative());
      } finally {
        release.countDown();
      }
      assertEquals("current page", first.get(5, TimeUnit.SECONDS));
      assertEquals("current page", second.get(5, TimeUnit.SECONDS));
      assertEquals(2, authoritative.get());
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
      assertEquals(0, f.admission.waitingAuthoritative());
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  @Test
  void aCallerThatBecomesIneligibleStillFailsClosedWithoutFallback() {
    try (var f = new Fixture()) {
      var queries = new AtomicInteger();
      assertThrows(
          ReadFailedException.class,
          () ->
              f.visibility.read(
                  "reader",
                  facts -> {
                    queries.incrementAndGet();
                    when(f.versions.canShare()).thenReturn(false);
                    return "protected";
                  }));
      assertEquals(1, queries.get(), "Dirty/historical/expiry-spanning callers cannot retry");
    }
  }

  @Test
  void anExpiryCrossedDuringFallbackStillRefusesHistoricalAuthority() {
    try (var f = new Fixture()) {
      var queries = new AtomicInteger();
      assertThrows(
          ReadFailedException.class,
          () ->
              f.visibility.read(
                  "reader",
                  facts -> {
                    queries.incrementAndGet();
                    if (facts.blocked().isPresent())
                      when(f.versions.current(f.token)).thenReturn(false);
                    else when(f.versions.canShare()).thenReturn(false);
                    return "protected";
                  }));
      assertEquals(2, queries.get());
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }

  @Test
  void anAlreadyAuthoritativeCallbackIsNotRefusedByCacheVersionChurn() {
    try (var f = new Fixture()) {
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenReturn(Collections.nCopies(32769, "blocked"));
      var queries = new AtomicInteger();
      assertEquals(
          "current page",
          f.visibility.read(
              "reader",
              facts -> {
                assertTrue(facts.blocked().isEmpty());
                queries.incrementAndGet();
                when(f.versions.current(f.token)).thenReturn(false);
                return "current page";
              }));
      assertEquals(1, queries.get());
    }
  }

  @Test
  void anExistingTransactionDeadlineConstrainsCacheAndBothSqlUnits() {
    try (var f = new Fixture()) {
      var deadlines = new ArrayList<Long>();
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenAnswer(
              call -> {
                deadlines.add(ReadDeadline.deadlineNanos(Duration.ofDays(1)));
                return List.of("restricted");
              });
      ReadDeadline.within(
          Duration.ofSeconds(1),
          () -> {
            long expected = ReadDeadline.deadlineNanos(Duration.ofDays(1));
            assertEquals(
                "current page",
                f.visibility.read(
                    "reader",
                    facts -> {
                      deadlines.add(ReadDeadline.deadlineNanos(Duration.ofDays(1)));
                      when(f.versions.current(f.token)).thenReturn(false);
                      return facts.blocked().isPresent() ? "tentative page" : "current page";
                    }));
            assertEquals(List.of(expected, expected, expected), deadlines);
            return true;
          });
    }
  }

  @Test
  void autocommitCacheWaitingAndFallbackDoNotConsumeAnotherSqlUnitsBudget() {
    try (var f = new Fixture(Duration.ofMillis(100))) {
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenAnswer(
              call -> {
                Thread.sleep(150);
                return List.of("restricted");
              });
      var deadlines = new ArrayList<Long>();
      assertEquals(
          "current page",
          f.visibility.read(
              "reader",
              facts -> {
                long deadline = ReadDeadline.deadlineNanos(Duration.ofDays(1));
                deadlines.add(deadline);
                assertTrue(
                    deadline - System.nanoTime() > Duration.ofMillis(50).toNanos(),
                    "A new autocommit SQL unit starts after its cache/admission wait");
                if (facts.blocked().isPresent()) {
                  long until = System.nanoTime() + Duration.ofMillis(20).toNanos();
                  while (System.nanoTime() - until < 0) Thread.onSpinWait();
                  when(f.versions.current(f.token)).thenReturn(false);
                  return "tentative page";
                }
                return "current page";
              }));
      assertEquals(2, deadlines.size());
      assertTrue(
          deadlines.get(1) > deadlines.getFirst(),
          "Autocommit fallback is a new SQL unit, not a renewed active transaction");
      assertTrue(
          ReadDeadline.deadlineNanos(Duration.ofDays(1)) - System.nanoTime()
              > Duration.ofHours(1).toNanos());
    }
  }

  @Test
  void aLateCachedResultCannotStartFallbackAndALateAuthoritativeResultCannotEscape() {
    for (boolean authoritative : List.of(false, true)) {
      try (var f = new Fixture(Duration.ofMillis(100))) {
        if (authoritative) when(f.versions.capture(DOMAINS)).thenReturn(Optional.empty());
        var queries = new AtomicInteger();
        assertThrows(
            ReadFailedException.class,
            () ->
                f.visibility.read(
                    "reader",
                    facts -> {
                      queries.incrementAndGet();
                      when(f.versions.current(f.token)).thenReturn(false);
                      long deadline = ReadDeadline.deadlineNanos(Duration.ofDays(1));
                      while (System.nanoTime() - deadline < 0) Thread.onSpinWait();
                      return "late page";
                    }));
        assertEquals(1, queries.get());
        assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
      }
    }
  }

  @Test
  void actualCallbackErrorsAndCancellationAreNeverRetried() {
    for (var failure :
        List.of(
            new DataAccessResourceFailureException("SQL cancelled"),
            new ReadUnavailableException(),
            new IllegalStateException("mapper failed"))) {
      try (var f = new Fixture()) {
        var queries = new AtomicInteger();
        assertSame(
            failure,
            assertThrows(
                RuntimeException.class,
                () ->
                    f.visibility.read(
                        "reader",
                        facts -> {
                          queries.incrementAndGet();
                          throw failure;
                        })));
        assertEquals(1, queries.get());
      }
    }
  }

  @Test
  void authoritativeFallbackErrorsAreNotRetriedAgain() {
    try (var f = new Fixture()) {
      var failure = new DataAccessResourceFailureException("SQL cancelled during fallback");
      var queries = new AtomicInteger();
      assertSame(
          failure,
          assertThrows(
              DataAccessResourceFailureException.class,
              () ->
                  f.visibility.read(
                      "reader",
                      facts -> {
                        queries.incrementAndGet();
                        if (facts.blocked().isEmpty()) throw failure;
                        when(f.versions.current(f.token)).thenReturn(false);
                        return "tentative";
                      })));
      assertEquals(2, queries.get());
      assertEquals(0, f.admission.active(ReadAdmission.Work.AUTHORITY));
    }
  }

  @Test
  void actualCacheLoadErrorsAreNotRetriedAsAuthoritativeQueries() {
    try (var f = new Fixture()) {
      var failure = new DataAccessResourceFailureException("connection lost");
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenThrow(failure);
      var queries = new AtomicInteger();
      assertSame(
          failure,
          assertThrows(
              DataAccessResourceFailureException.class,
              () -> f.visibility.read("reader", facts -> queries.incrementAndGet())));
      assertEquals(0, queries.get());
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

  /**
   * Design slice 2: targeted checks are answered per log tree ({@link CachedLogReadability}) and,
   * when that declines, by the caller's predicate. They never load the per-account deny set, which
   * only collection reads consume. (Before slice 2 they shared that set.)
   */
  @Test
  void targetedChecksNeverLoadTheAccountDenySet() {
    try (var f = new Fixture()) {
      var predicate = new AtomicInteger();
      BooleanSupplier authoritative =
          () -> {
            predicate.incrementAndGet();
            return false;
          };
      for (int check = 0; check < 5; check++)
        assertFalse(f.visibility.readable("reader", "restricted", authoritative));
      assertEquals(5, predicate.get(), "No tree check is configured: the predicate decides");
      verifyNoInteractions(f.jdbc);

      assertFalse(f.visibility.readable(null, "open", authoritative));
      assertEquals(6, predicate.get());
      when(f.versions.capture(DOMAINS)).thenReturn(Optional.empty());
      assertFalse(f.visibility.readable("reader", "open", authoritative));
      assertEquals(7, predicate.get(), "A dirty transaction keeps its own statement authority");
      verifyNoInteractions(f.jdbc);

      assertThrows(
          IllegalArgumentException.class, () -> f.visibility.readable(" ", "open", authoritative));
    }
  }

  /**
   * PROD job_32792EE2: a check missed while the account's only AUTHORITY seat was busy, was
   * refused, and ended the job. The deny-set load is the cache's fetch, not the requester's read,
   * so a busy account seat must not refuse a collection read's fill either.
   */
  @Test
  void aBusyAccountAuthoritySeatDoesNotRefuseTheCachesFetch() {
    try (var f = new Fixture()) {
      var busy = f.admission.admit("reader", ReadAdmission.Work.AUTHORITY);
      var other = f.admission.admit("other", ReadAdmission.Work.AUTHORITY);
      try (busy;
          other) {
        assertEquals(
            true,
            f.visibility.read(
                "reader", facts -> facts.blocked().orElseThrow().contains("restricted")));
      }
      verify(f.jdbc, times(1)).queryForList(anyString(), eq(String.class), any(Object[].class));
      assertEquals(0, f.admission.active(ReadAdmission.Work.FETCH));
    }
  }

  @Test
  void aRevokedDenySetIsReloadedBeforeACollectionReadUsesIt() {
    try (var f = new Fixture()) {
      assertEquals(
          false,
          f.visibility.read("reader", facts -> facts.blocked().orElseThrow().contains("open")));
      // A grant revocation or new input moves the version: the stored set no longer validates.
      when(f.versions.current(f.token)).thenReturn(false);
      var revoked =
          new ProjectionVersions.Token(
              f.token.database(),
              Map.of(
                  ProjectionVersions.Domain.READABILITY,
                  2L,
                  ProjectionVersions.Domain.AUTHORITY,
                  2L),
              List.of(),
              "999999",
              null,
              null);
      when(f.versions.capture(DOMAINS)).thenReturn(Optional.of(revoked));
      when(f.versions.current(revoked)).thenReturn(true);
      when(f.jdbc.queryForList(anyString(), eq(String.class), any(Object[].class)))
          .thenReturn(List.of("restricted", "open"));
      assertEquals(
          true,
          f.visibility.read("reader", facts -> facts.blocked().orElseThrow().contains("open")));
      verify(f.jdbc, times(2)).queryForList(anyString(), eq(String.class), any(Object[].class));
    }
  }
}
