package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.server.access.AuthoritySnapshot;
import io.aeyer.plowshare.server.access.AuthoritySnapshots;
import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class RequestSessionValidityTest {
  private static final Duration BOUND = Duration.ofSeconds(2);
  private final SessionAuthority accounts = mock(SessionAuthority.class);
  private final SocketAuthorization authorization = new SocketAuthorization(accounts);
  private final WebSocketSession socket = mock(WebSocketSession.class);
  private final AtomicLong clock = new AtomicLong(1_000_000_000L);

  RequestSessionValidityTest() {
    var attributes = new HashMap<String, Object>();
    attributes.put("plowshare.sessionVersion", 1L);
    attributes.put(EventChannelHandler.HANDLE, "alice");
    when(socket.getAttributes()).thenReturn(attributes);
    when(accounts.sessionCurrent(anyString(), anyLong())).thenReturn(true);
  }

  private final AuthoritySnapshot first = mock(AuthoritySnapshot.class);
  private final AuthoritySnapshot second = mock(AuthoritySnapshot.class);
  private volatile Optional<AuthoritySnapshot> snapshot = Optional.of(first);
  private final AuthoritySnapshots snapshots = () -> snapshot;

  /** Seeded by one real check, as routeConcurrent's entry check does. */
  private RequestSessionValidity validity() {
    var validity = new RequestSessionValidity(authorization, socket, clock::get, BOUND, snapshots);
    validity.verify();
    clearInvocations(accounts);
    return validity;
  }

  @Test
  void oneCheckForARequestInsideTheBound() {
    var validity = validity();
    clock.addAndGet(Duration.ofMillis(100).toNanos());
    for (int i = 0; i < 4; i++) validity.run();
    clock.addAndGet(Duration.ofMillis(800).toNanos());
    validity.run();
    // The seeding check happened at the caller; nothing inside the bound asks again.
    verify(accounts, times(0)).sessionCurrent(anyString(), anyLong());
  }

  @Test
  void noSnapshotMeansEveryRunChecks() {
    var validity = validity();
    snapshot = Optional.empty();
    validity.run();
    validity.run();
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
  }

  @Test
  void aChangedSnapshotInstanceForcesARecheck() {
    var validity = validity();
    validity.run();
    verify(accounts, times(0)).sessionCurrent(anyString(), anyLong());
    snapshot = Optional.of(second);
    validity.run();
    verify(accounts, times(1)).sessionCurrent("alice", 1L);
    // The success re-recorded under the new instance; it is reused again.
    validity.run();
    verify(accounts, times(1)).sessionCurrent("alice", 1L);
  }

  @Test
  void aSuccessIsNotRecordedWhenTheSnapshotChangedDuringTheCheck() {
    var validity = validity();
    clock.addAndGet(BOUND.toNanos() + 1);
    when(accounts.sessionCurrent("alice", 1L))
        .thenAnswer(
            call -> {
              snapshot = Optional.of(second);
              return true;
            });
    validity.run();
    validity.run();
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
  }

  @Test
  void recheckAfterTheBound() {
    var validity = validity();
    clock.addAndGet(BOUND.toNanos() + 1);
    validity.run();
    validity.run();
    verify(accounts, times(1)).sessionCurrent("alice", 1L);
    clock.addAndGet(BOUND.toNanos() + 1);
    validity.run();
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
  }

  @Test
  void revokedSessionStopsDeliveryWithinBound() {
    var validity = validity();
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(false);
    assertDoesNotThrow(validity::run);
    clock.addAndGet(BOUND.toNanos() + 1);
    assertThrows(CallerFault.class, validity::run);
  }

  @Test
  void refusalIsNeverRemembered() {
    var validity = validity();
    clock.addAndGet(BOUND.toNanos() + 1);
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(false);
    assertThrows(CallerFault.class, validity::run);
    assertThrows(CallerFault.class, validity::run);
    verify(accounts, times(2)).sessionCurrent("alice", 1L);
    // A later success does restart the bound, but only a success.
    when(accounts.sessionCurrent("alice", 1L)).thenReturn(true);
    assertDoesNotThrow(validity::run);
    assertDoesNotThrow(validity::run);
    verify(accounts, times(3)).sessionCurrent("alice", 1L);
  }
}
