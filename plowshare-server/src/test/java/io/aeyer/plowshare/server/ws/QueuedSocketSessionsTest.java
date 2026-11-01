package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.auth.SessionAuthority;
import io.aeyer.plowshare.server.auth.SessionAuthority.Session;
import io.aeyer.plowshare.server.reads.ReadUnavailableException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class QueuedSocketSessionsTest {
  private final SessionAuthority facts = mock(SessionAuthority.class);
  private final Session alice = new Session("alice", 1);
  private final Session bob = new Session("bob", 1);

  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(3, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private static void pending(QueuedSocketSessions queue, int expected) {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (queue.pending() != expected && System.nanoTime() < until) Thread.onSpinWait();
    assertEquals(expected, queue.pending());
  }

  @Test
  void global_and_principal_admission_precede_database_work_across_sockets() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(facts.currentSessions(any()))
        .thenAnswer(
            call -> {
              if (entered.getCount() != 0) {
                entered.countDown();
                await(release);
              }
              return Set.copyOf(call.getArgument(0));
            });
    try (var queue =
            new QueuedSocketSessions(facts, new SocketSessionProperties(3, 2, 3, 0, 2000));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = callers.submit(() -> queue.sessionCurrent("alice", 1));
      await(entered);
      var otherSocket = callers.submit(() -> queue.sessionCurrent("alice", 1));
      pending(queue, 2);
      assertThrows(ReadUnavailableException.class, () -> queue.sessionCurrent("alice", 1));
      var otherPrincipal = callers.submit(() -> queue.sessionCurrent("bob", 1));
      pending(queue, 3);
      assertThrows(ReadUnavailableException.class, () -> queue.sessionCurrent("charlie", 1));
      verify(facts, times(1)).currentSessions(any());
      release.countDown();
      assertTrue(first.get(3, TimeUnit.SECONDS));
      assertTrue(otherSocket.get(3, TimeUnit.SECONDS));
      assertTrue(otherPrincipal.get(3, TimeUnit.SECONDS));
      pending(queue, 0);
      verify(facts).currentSessions(Set.of(alice, bob));
      verify(facts, never()).sessionCurrent(anyString(), anyLong());
    } finally {
      release.countDown();
    }
  }

  @Test
  void queued_checks_share_only_a_new_statement_and_keep_exact_session_versions() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var fresh = new Session("alice", 2);
    when(facts.currentSessions(Set.of(bob)))
        .thenAnswer(
            call -> {
              entered.countDown();
              await(release);
              return Set.of(bob);
            });
    when(facts.currentSessions(Set.of(alice, fresh))).thenReturn(Set.of(fresh));
    try (var queue =
            new QueuedSocketSessions(facts, new SocketSessionProperties(4, 2, 4, 0, 2000));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker = callers.submit(() -> queue.sessionCurrent("bob", 1));
      await(entered);
      var old = callers.submit(() -> queue.sessionCurrent("alice", 1));
      var current = callers.submit(() -> queue.sessionCurrent("alice", 2));
      pending(queue, 3);
      release.countDown();
      assertTrue(blocker.get(3, TimeUnit.SECONDS));
      assertFalse(old.get(3, TimeUnit.SECONDS));
      assertTrue(current.get(3, TimeUnit.SECONDS));
      verify(facts).currentSessions(Set.of(alice, fresh));
    } finally {
      release.countDown();
    }
  }

  @Test
  void a_caller_after_revocation_cannot_join_an_older_inflight_allow() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var revoked = new AtomicBoolean();
    when(facts.currentSessions(Set.of(alice)))
        .thenAnswer(
            call -> {
              boolean allowed = !revoked.get();
              if (allowed) {
                entered.countDown();
                await(release);
              }
              return allowed ? Set.of(alice) : Set.of();
            });
    try (var queue =
            new QueuedSocketSessions(facts, new SocketSessionProperties(2, 2, 2, 0, 2000));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var before = callers.submit(() -> queue.sessionCurrent("alice", 1));
      await(entered);
      revoked.set(true);
      var after = callers.submit(() -> queue.sessionCurrent("alice", 1));
      pending(queue, 2);
      release.countDown();
      assertTrue(before.get(3, TimeUnit.SECONDS));
      assertFalse(after.get(3, TimeUnit.SECONDS));
      assertFalse(queue.sessionCurrent("alice", 1), "No completed allow is cached");
      verify(facts, times(3)).currentSessions(Set.of(alice));
    } finally {
      release.countDown();
    }
  }

  @Test
  void timeout_does_not_release_capacity_while_physical_database_work_is_running()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(facts.currentSessions(Set.of(alice)))
        .thenAnswer(
            call -> {
              entered.countDown();
              await(release);
              return Set.of(alice);
            });
    try (var queue = new QueuedSocketSessions(facts, new SocketSessionProperties(1, 1, 1, 0, 200));
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = callers.submit(() -> queue.sessionCurrent("alice", 1));
      await(entered);
      var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
      assertInstanceOf(ReadUnavailableException.class, failure.getCause());
      assertEquals(1, queue.pending());
      assertThrows(ReadUnavailableException.class, () -> queue.sessionCurrent("bob", 1));
      verify(facts, times(1)).currentSessions(any());
      release.countDown();
      pending(queue, 0);
    } finally {
      release.countDown();
    }
  }

  @Test
  void unavailable_facts_are_not_cached_and_release_admission() {
    when(facts.currentSessions(Set.of(alice)))
        .thenThrow(new IllegalStateException("unavailable"))
        .thenReturn(Set.of(alice));
    try (var queue =
        new QueuedSocketSessions(facts, new SocketSessionProperties(1, 1, 1, 0, 2000))) {
      assertThrows(IllegalStateException.class, () -> queue.sessionCurrent("alice", 1));
      assertEquals(0, queue.pending());
      assertTrue(queue.sessionCurrent("alice", 1));
    }
  }

  @Test
  void shutdown_refuses_queued_and_inflight_checks_without_publishing_an_allow() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(facts.currentSessions(any()))
        .thenAnswer(
            call -> {
              entered.countDown();
              await(release);
              return Set.of(alice, bob);
            });
    var queue = new QueuedSocketSessions(facts, new SocketSessionProperties(2, 1, 2, 0, 2000));
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = callers.submit(() -> queue.sessionCurrent("alice", 1));
      await(entered);
      var second = callers.submit(() -> queue.sessionCurrent("bob", 1));
      pending(queue, 2);
      queue.close();
      assertInstanceOf(
          ReadUnavailableException.class,
          assertThrows(ExecutionException.class, () -> second.get(3, TimeUnit.SECONDS)).getCause());
      assertThrows(ReadUnavailableException.class, () -> queue.sessionCurrent("charlie", 1));
      release.countDown();
      assertInstanceOf(
          ReadUnavailableException.class,
          assertThrows(ExecutionException.class, () -> first.get(3, TimeUnit.SECONDS)).getCause());
      pending(queue, 0);
    } finally {
      release.countDown();
      queue.close();
    }
  }
}
