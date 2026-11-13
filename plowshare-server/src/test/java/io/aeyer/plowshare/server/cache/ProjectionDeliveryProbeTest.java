package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Delivery probes turn "the socket is quiet" into a positive claim: everything committed before a
 * probe has reached observers once its echo is read. An unanswered probe ends the session, so a
 * silently lost connection is recovered within the timeout instead of never.
 */
class ProjectionDeliveryProbeTest {
  private static final Set<ProjectionVersions.Domain> AUTHORITY =
      Set.of(ProjectionVersions.Domain.AUTHORITY);
  private static final Duration HEARTBEAT = Duration.ofMillis(20);
  private static final Duration TIMEOUT = Duration.ofMillis(200);

  /** A session whose queue preserves PostgreSQL's commit-order delivery to one listener. */
  private static final class Session implements ProjectionNotificationSource.Session {
    final BlockingQueue<Object> delivered = new LinkedBlockingQueue<>();
    final AtomicBoolean echoes;
    final boolean probes;
    final CountDownLatch closed = new CountDownLatch(1);
    volatile long answered;

    Session(boolean probes, boolean echoes) {
      this.probes = probes;
      this.echoes = new AtomicBoolean(echoes);
    }

    void commit(Set<ProjectionVersions.Domain> domains) {
      delivered.add(domains);
    }

    @Override
    public void listen() {}

    @Override
    public boolean heartbeat(long sequence) {
      if (!probes) return false;
      if (echoes.get()) delivered.add(sequence);
      return true;
    }

    @Override
    public long heartbeatsReceived() {
      return answered;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<ProjectionVersions.Domain> poll(Duration timeout) {
      try {
        var changed = new HashSet<ProjectionVersions.Domain>();
        Object first = delivered.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        for (Object next = first; next != null; next = delivered.poll()) {
          if (next instanceof Long sequence) answered = Math.max(answered, sequence);
          else changed.addAll((Set<ProjectionVersions.Domain>) next);
        }
        return Set.copyOf(changed);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("closed");
      }
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }

  private static ListeningProjectionChangeSignals listener(List<Session> sessions) {
    var queue = new ConcurrentLinkedQueue<>(sessions);
    ProjectionNotificationSource source =
        () -> {
          var next = queue.poll();
          if (next == null) throw new IllegalStateException("no session");
          return next;
        };
    var versions = mock(ProjectionVersions.class);
    var token =
        new ProjectionVersions.Token(
            new UUID(0, 1),
            Map.of(ProjectionVersions.Domain.AUTHORITY, 1L),
            List.of(),
            "9",
            null,
            null);
    when(versions.capture(anySet())).thenReturn(Optional.of(token));
    when(versions.current(any())).thenReturn(true);
    return new ListeningProjectionChangeSignals(
        source, versions, 4, Duration.ofMillis(50), Duration.ofMillis(10), HEARTBEAT, TIMEOUT);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) fail("condition not reached");
      Thread.sleep(5);
    }
  }

  @Test
  void aProbeIsAnsweredOnlyAfterEveryEarlierChangeReachedObservers() throws Exception {
    var session = new Session(true, true);
    var seen = new ConcurrentLinkedQueue<Set<ProjectionVersions.Domain>>();
    try (var signals = listener(List.of(session))) {
      signals.subscribe(
          AUTHORITY,
          new ProjectionChangeSignals.Observer() {
            public void stateChanged(ProjectionChangeSignals.State state) {}

            public void changed(Set<ProjectionVersions.Domain> domains) {
              seen.add(domains);
            }
          });
      assertTrue(signals.deliveredThrough().isEmpty(), "Nothing is proven before the first echo");
      signals.start();
      await(() -> signals.deliveredThrough().isPresent());

      long revokedAt = System.nanoTime();
      session.commit(AUTHORITY);
      await(() -> signals.deliveredThrough().getAsLong() > revokedAt);
      assertEquals(
          AUTHORITY, seen.peek(), "Delivery past the revocation implies its invalidation ran");
    }
  }

  @Test
  void anUnansweredProbeEndsTheSessionAndRecoveryRestoresDelivery() throws Exception {
    var silent = new Session(true, false);
    var healthy = new Session(true, true);
    try (var signals = listener(List.of(silent, healthy))) {
      signals.subscribe(
          AUTHORITY,
          new ProjectionChangeSignals.Observer() {
            public void stateChanged(ProjectionChangeSignals.State state) {}

            public void changed(Set<ProjectionVersions.Domain> domains) {}
          });
      long started = System.nanoTime();
      signals.start();
      assertTrue(
          silent.closed.await(5, TimeUnit.SECONDS), "A quiet session must not be trusted forever");
      assertTrue(
          System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2),
          "Silent loss is noticed within the probe timeout");
      await(() -> signals.deliveredThrough().isPresent());
      assertTrue(signals.healthy());
    }
  }

  @Test
  void aSessionThatCannotProbeStillDeliversButClaimsNoFreshness() throws Exception {
    var session = new Session(false, false);
    var seen = new ConcurrentLinkedQueue<Set<ProjectionVersions.Domain>>();
    try (var signals = listener(List.of(session))) {
      signals.subscribe(
          AUTHORITY,
          new ProjectionChangeSignals.Observer() {
            public void stateChanged(ProjectionChangeSignals.State state) {}

            public void changed(Set<ProjectionVersions.Domain> domains) {
              seen.add(domains);
            }
          });
      signals.start();
      await(signals::healthy);
      session.commit(AUTHORITY);
      await(() -> !seen.isEmpty());
      assertTrue(signals.deliveredThrough().isEmpty());
    }
  }

  @Test
  void probesMustBeShorterThanTheirTimeout() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ListeningProjectionChangeSignals(
                () -> new Session(true, true),
                mock(ProjectionVersions.class),
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3),
                Duration.ofSeconds(3)));
  }
}
