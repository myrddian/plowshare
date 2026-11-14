package io.aeyer.plowshare.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.auth.SessionAuthority.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Accepted sessions come from the snapshot; refusals and every check without a usable snapshot are
 * decided by the stored authority.
 */
class CachedSessionAuthorityTest {
  private final SessionAuthority stored = mock(SessionAuthority.class);

  /** alice is active at version 3, bob is disabled at version 1. */
  private static AuthoritySnapshot snapshot() {
    return new AuthoritySnapshot(
        new AuthoritySnapshot.Facts(
            Instant.parse("2026-11-14T00:00:00Z"),
            List.of(
                new AuthoritySnapshot.Account("alice", "USER", true, false, false, 3),
                new AuthoritySnapshot.Account("bob", "USER", false, false, false, 1)),
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
  void anAcceptedSessionNeedsNoSql() {
    var sessions = new CachedSessionAuthority(stored, () -> Optional.of(snapshot()));
    assertTrue(sessions.sessionCurrent("alice", 3));
    assertEquals(
        Set.of(new Session("alice", 3)), sessions.currentSessions(Set.of(new Session("alice", 3))));
    verifyNoInteractions(stored);
  }

  @Test
  void aRefusalIsConfirmedBySqlSoANewerSessionIsNeverRefusedByAnOldSnapshot() {
    when(stored.sessionCurrent("alice", 4)).thenReturn(true);
    when(stored.sessionCurrent("bob", 1)).thenReturn(false);
    var sessions = new CachedSessionAuthority(stored, () -> Optional.of(snapshot()));
    assertTrue(sessions.sessionCurrent("alice", 4), "Issued after the snapshot loaded");
    assertFalse(sessions.sessionCurrent("bob", 1), "Disabled account");
    var alice = new Session("alice", 3);
    var newer = new Session("alice", 4);
    var bob = new Session("bob", 1);
    var stranger = new Session("carol", 0);
    when(stored.currentSessions(Set.of(newer, bob, stranger))).thenReturn(Set.of(newer));
    assertEquals(
        Set.of(alice, newer), sessions.currentSessions(List.of(alice, newer, bob, stranger)));
    verify(stored).currentSessions(Set.of(newer, bob, stranger));
  }

  @Test
  void noSnapshotLeavesEveryCheckInSql() {
    var alice = new Session("alice", 3);
    when(stored.sessionCurrent("alice", 3)).thenReturn(true);
    when(stored.currentSessions(Set.of(alice))).thenReturn(Set.of(alice));
    var sessions = new CachedSessionAuthority(stored, AuthoritySnapshots.none());
    assertTrue(sessions.sessionCurrent("alice", 3));
    assertEquals(Set.of(alice), sessions.currentSessions(Set.of(alice)));
    verify(stored).sessionCurrent("alice", 3);
    verify(stored).currentSessions(Set.of(alice));
  }

  @Test
  void aWriteTransactionKeepsSqlWithoutAskingForASnapshot() {
    var loads = new AtomicInteger();
    var sessions =
        new CachedSessionAuthority(
            stored,
            () -> {
              loads.incrementAndGet();
              return Optional.of(snapshot());
            });
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      sessions.sessionCurrent("alice", 3);
      sessions.currentSessions(Set.of(new Session("alice", 3)));
    } finally {
      TransactionSynchronizationManager.clear();
    }
    assertEquals(0, loads.get());
    verify(stored).sessionCurrent("alice", 3);
    verify(stored).currentSessions(Set.of(new Session("alice", 3)));
  }

  @Test
  void anEmptyRequestNeedsNoSql() {
    var sessions = new CachedSessionAuthority(stored, () -> Optional.of(snapshot()));
    assertEquals(Set.of(), sessions.currentSessions(Set.of()));
    verify(stored, never()).currentSessions(any());
    verify(stored, never()).sessionCurrent(anyString(), anyLong());
  }
}
