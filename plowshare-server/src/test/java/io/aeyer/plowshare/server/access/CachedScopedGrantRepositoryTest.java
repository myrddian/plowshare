package io.aeyer.plowshare.server.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthorityIds.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Live grants and lineage come from the snapshot when one may be used, and from the stored
 * repository otherwise; every other operation, mutations included, always reaches the store.
 */
class CachedScopedGrantRepositoryTest {
  private static final GrantRecipient RECIPIENT =
      GrantRecipient.agent(new AccountId(new UUID(0, 7)), "helper");
  private static final UUID GRANT = new UUID(0, 9);

  private final ScopedGrantRepository stored = mock(ScopedGrantRepository.class);

  /** A snapshot with no grants: every live read is empty, without SQL. */
  private static AuthoritySnapshot empty() {
    return new AuthoritySnapshot(
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
            List.of()));
  }

  @Test
  void aUsableSnapshotAnswersLiveAndLineageWithoutTheStore() {
    var loads = new AtomicInteger();
    var grants =
        new CachedScopedGrantRepository(
            stored,
            () -> {
              loads.incrementAndGet();
              return Optional.of(empty());
            });
    assertEquals(List.of(), grants.live(RECIPIENT));
    assertEquals(List.of(), grants.lineage(GRANT));
    assertEquals(2, loads.get());
    verifyNoInteractions(stored);
  }

  @Test
  void noSnapshotLeavesLiveAndLineageInSql() {
    List<ScopedGrantRepository.Binding> live = List.of();
    List<ScopedGrantRepository.Binding> chain = List.of();
    when(stored.live(RECIPIENT)).thenReturn(live);
    when(stored.lineage(GRANT)).thenReturn(chain);
    var grants = new CachedScopedGrantRepository(stored, AuthoritySnapshots.none());
    assertSame(live, grants.live(RECIPIENT));
    assertSame(chain, grants.lineage(GRANT));
    verify(stored).live(RECIPIENT);
    verify(stored).lineage(GRANT);
  }

  @Test
  void aWriteTransactionKeepsSqlWithoutAskingForASnapshot() {
    var loads = new AtomicInteger();
    var grants =
        new CachedScopedGrantRepository(
            stored,
            () -> {
              loads.incrementAndGet();
              return Optional.of(empty());
            });
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      grants.live(RECIPIENT);
      grants.lineage(GRANT);
    } finally {
      TransactionSynchronizationManager.clear();
    }
    assertEquals(0, loads.get());
    verify(stored).live(RECIPIENT);
    verify(stored).lineage(GRANT);
  }

  @Test
  void mutationsAndOtherReadsAlwaysReachTheStore() {
    var snapshot = Optional.of(empty());
    var grants = new CachedScopedGrantRepository(stored, () -> snapshot);
    var account = new AccountId(new UUID(0, 7));
    grants.globalReadEligible(account);
    grants.projectBindings("one", "alice");
    grants.projectLineages("one", "alice");
    grants.find(GRANT);
    grants.revoke(GRANT, 1, RECIPIENT, GRANT);
    var issue =
        new ScopedGrantRepository.Issue(
            GRANT,
            RECIPIENT,
            new AuthorityIds.ScopeId(new UUID(0, 3)),
            StandardRole.READER,
            GrantRecipient.account(account),
            GRANT,
            Optional.empty(),
            false);
    grants.issue(issue);
    verify(stored).globalReadEligible(account);
    verify(stored).projectBindings("one", "alice");
    verify(stored).projectLineages("one", "alice");
    verify(stored).find(GRANT);
    verify(stored).revoke(GRANT, 1, RECIPIENT, GRANT);
    verify(stored).issue(issue);
  }

  @Test
  void nullInputsAreRefusedBeforeAnyRead() {
    var grants = new CachedScopedGrantRepository(stored, () -> Optional.of(empty()));
    assertThrows(NullPointerException.class, () -> grants.live(null));
    assertThrows(NullPointerException.class, () -> grants.lineage(null));
    verifyNoInteractions(stored);
  }
}
