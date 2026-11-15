package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityCheckTelemetry.GrantOutcome;
import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import io.aeyer.plowshare.server.access.CheckedScopedGrants.Checked;
import io.aeyer.plowshare.server.access.CheckedScopedGrants.Reason;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The checked path preserves one statement per grant read, including changes between reads. */
class CheckedScopedGrantsTest {
  private static final UUID GRANT = new UUID(0, 7);
  private static final GrantRecipient RECIPIENT =
      GrantRecipient.account(new AccountId(new UUID(0, 9)));
  private final ScopedGrantRepository stored = mock(ScopedGrantRepository.class);
  private final CheckedScopedGrants checked = mock(CheckedScopedGrants.class);
  private final AuthoritySnapshots snapshots = mock(AuthoritySnapshots.class);
  private final AuthoritySnapshotRefresh refresh = mock(AuthoritySnapshotRefresh.class);
  private final AuthorityCheckTelemetry telemetry = mock(AuthorityCheckTelemetry.class);
  private final ProjectionVersions.Token token =
      new ProjectionVersions.Token(
          new UUID(0, 1),
          Map.of(ProjectionVersions.Domain.AUTHORITY, 3L),
          List.of("19"),
          "22",
          null,
          null);
  private final AuthoritySnapshot snapshot =
      new AuthoritySnapshot(
          new AuthoritySnapshot.Facts(
              Instant.parse("2026-11-14T00:00:00Z"),
              List.of(),
              Map.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of()));

  private CachedScopedGrantRepository repository(boolean enabled) {
    return new CachedScopedGrantRepository(stored, checked, snapshots, refresh, telemetry, enabled);
  }

  private void writeTransaction() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
  }

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void oneHundredWarmReadsEachConfirmTheOriginalLoaderTokenWithoutOtherGrantQueries() {
    writeTransaction();
    var candidate = new AuthoritySnapshots.Candidate(snapshot, token);
    when(snapshots.candidate()).thenReturn(Optional.of(candidate));
    when(checked.liveChecked(RECIPIENT, Optional.of(token)))
        .thenReturn(new Checked(Reason.EXACT, 3, List.of()));
    when(checked.lineageChecked(GRANT, Optional.of(token)))
        .thenReturn(new Checked(Reason.EXACT, 3, List.of()));
    var repository = repository(true);
    for (int i = 0; i < 100; i++) {
      assertEquals(snapshot.live(RECIPIENT), repository.live(RECIPIENT));
      assertEquals(snapshot.lineage(GRANT), repository.lineage(GRANT));
    }
    verify(checked, times(100)).liveChecked(RECIPIENT, Optional.of(token));
    verify(checked, times(100)).lineageChecked(GRANT, Optional.of(token));
    verify(snapshots, never()).current();
    verify(telemetry, times(200)).grant(GrantOutcome.CONFIRMED);
    verifyNoInteractions(stored, refresh);
  }

  @Test
  void aLaterOwnWriteIsNotHiddenByMemoizingTheFirstConfirmation() {
    writeTransaction();
    when(snapshots.candidate())
        .thenReturn(Optional.of(new AuthoritySnapshots.Candidate(snapshot, token)));
    var rows = List.of(mock(ScopedGrantRepository.Binding.class));
    when(checked.liveChecked(RECIPIENT, Optional.of(token)))
        .thenReturn(new Checked(Reason.EXACT, 3, List.of()), new Checked(Reason.DIRTY, 4, rows));
    var repository = repository(true);
    assertEquals(List.of(), repository.live(RECIPIENT));
    assertSame(rows, repository.live(RECIPIENT));
    verify(checked, times(2)).liveChecked(RECIPIENT, Optional.of(token));
    verify(telemetry).grant(GrantOutcome.SQL_DIRTY);
    verifyNoInteractions(stored, refresh);
  }

  @ParameterizedTest
  @EnumSource(
      value = Reason.class,
      names = {"DIRTY", "ISOLATION", "VERSION", "WINDOW", "INELIGIBLE"})
  void everySlowOutcomeReturnsTheSameCallRowsAndRefreshesOnlyRecoverableMisses(Reason reason) {
    writeTransaction();
    when(snapshots.candidate()).thenReturn(Optional.empty());
    var rows = List.of(mock(ScopedGrantRepository.Binding.class));
    when(checked.lineageChecked(GRANT, Optional.empty())).thenReturn(new Checked(reason, 4, rows));
    assertSame(rows, repository(true).lineage(GRANT));
    if (reason == Reason.DIRTY || reason == Reason.ISOLATION) verifyNoInteractions(refresh);
    else verify(refresh).request();
    verifyNoInteractions(stored);
    verify(snapshots, never()).current();
  }

  @Test
  void disabledSwitchPreservesTheOriginalWriteTransactionPath() {
    writeTransaction();
    when(stored.live(RECIPIENT)).thenReturn(List.of());
    assertEquals(List.of(), repository(false).live(RECIPIENT));
    verify(stored).live(RECIPIENT);
    verifyNoInteractions(snapshots, checked, refresh);
  }

  @Test
  void missingCandidateCannotAcceptAnExactMarkerOrIssueASecondQuery() {
    writeTransaction();
    when(snapshots.candidate()).thenReturn(Optional.empty());
    when(checked.liveChecked(RECIPIENT, Optional.empty()))
        .thenReturn(new Checked(Reason.EXACT, 3, List.of()));
    assertThrows(IllegalStateException.class, () -> repository(true).live(RECIPIENT));
    verifyNoInteractions(stored, refresh);
  }

  @Test
  void checkedDatabaseFailurePropagatesWithoutRetryOrRefresh() {
    writeTransaction();
    when(snapshots.candidate()).thenReturn(Optional.empty());
    var failure = new IllegalStateException("database unavailable");
    when(checked.liveChecked(RECIPIENT, Optional.empty())).thenThrow(failure);
    assertSame(
        failure, assertThrows(IllegalStateException.class, () -> repository(true).live(RECIPIENT)));
    verifyNoInteractions(stored, refresh);
  }

  @Test
  void checkedResultsAreImmutableAndRejectRowsOnTheExactPath() {
    var binding = mock(ScopedGrantRepository.Binding.class);
    assertThrows(
        IllegalArgumentException.class, () -> new Checked(Reason.EXACT, 1, List.of(binding)));
    assertThrows(IllegalArgumentException.class, () -> new Checked(Reason.VERSION, -1, List.of()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> new Checked(Reason.DIRTY, 1, List.of()).rows().add(binding));
  }

  @Test
  void grantMetersRegisterEveryFixedOutcomeAndCountTheSelectedOne() {
    var registry = new SimpleMeterRegistry();
    try {
      var meters = new MicrometerAuthorityCheckTelemetry(registry);
      meters.grant(GrantOutcome.CONFIRMED);
      for (var outcome : GrantOutcome.values()) {
        assertEquals(
            outcome == GrantOutcome.CONFIRMED ? 1 : 0,
            registry
                .get(MicrometerAuthorityCheckTelemetry.GRANTS)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .counter()
                .count());
      }
    } finally {
      registry.close();
    }
  }
}
