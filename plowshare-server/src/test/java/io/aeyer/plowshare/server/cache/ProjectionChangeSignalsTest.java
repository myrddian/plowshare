package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProjectionChangeSignalsTest {
  private static final Set<ProjectionVersions.Domain> SEARCH =
      Set.of(ProjectionVersions.Domain.SEARCH);
  private static final ProjectionVersions.Token TOKEN =
      new ProjectionVersions.Token(
          UUID.fromString("a55d80be-4134-4d0d-98bc-696ac3b32f31"),
          Map.of(ProjectionVersions.Domain.SEARCH, 1L),
          List.of(),
          "999999",
          null,
          null);

  @Test
  void listenCommitsBeforeBaselineAndRetainedChangesAreFilteredAndBounded() throws Exception {
    var source = new Source();
    var session = source.add();
    var versions = versions();
    when(versions.capture(anySet()))
        .thenAnswer(
            invocation -> {
              assertTrue(session.listening.get(), "LISTEN must commit before baseline capture");
              return Optional.of(TOKEN);
            });
    var observer = new RecordingObserver();
    var signals = listener(source, versions, 2);
    try {
      signals.subscribe(SEARCH, observer);
      signals.start();
      await(observer.firstReady);
      assertTrue(signals.healthy());
      session.changes(
          Set.of(ProjectionVersions.Domain.SEARCH, ProjectionVersions.Domain.SCHEDULES));
      assertEquals(SEARCH, observer.changes.poll(2, TimeUnit.SECONDS));
      signals.close();
      assertFalse(signals.healthy());
      await(session.closed);
      assertFalse(observer.states.getLast().ready());
      assertThrows(IllegalStateException.class, signals::start);
    } finally {
      signals.close();
    }
  }

  @Test
  void aDisconnectBlocksReuseAndEveryReconnectListensBeforeANewBaseline() throws Exception {
    var source = new Source();
    var first = source.add();
    var second = source.add();
    var versions = versions();
    var baselines = new AtomicInteger();
    when(versions.capture(anySet()))
        .thenAnswer(
            invocation -> {
              var active = baselines.getAndIncrement() == 0 ? first : second;
              assertTrue(active.listening.get());
              return Optional.of(TOKEN);
            });
    var observer = new RecordingObserver();
    try (var signals = listener(source, versions, 1)) {
      signals.subscribe(SEARCH, observer);
      signals.start();
      await(observer.firstReady);
      first.disconnect();
      await(observer.recovering);
      await(observer.secondReady);
      assertTrue(signals.healthy());
      assertEquals(2, baselines.get());
      long previous = 0;
      for (var state : observer.states) {
        assertTrue(state.generation() > previous);
        previous = state.generation();
      }
      assertEquals(
          List.of(false, true, false, true),
          observer.states.stream().map(ProjectionChangeSignals.State::ready).toList());
    }
  }

  @Test
  void observersMayReenterSubscribeDisposeAndCloseWithoutHoldingLifecycleLocks() throws Exception {
    var source = new Source();
    var session = source.add();
    var closed = new CountDownLatch(1);
    var reference = new AtomicReference<ListeningProjectionChangeSignals>();
    var noop = new RecordingObserver();
    try (var signals = listener(source, versions(), 2)) {
      reference.set(signals);
      signals.subscribe(
          SEARCH,
          new ProjectionChangeSignals.Observer() {
            @Override
            public void stateChanged(ProjectionChangeSignals.State state) {
              if (state.ready()) {
                var temporary = reference.get().subscribe(SEARCH, noop);
                try {
                  assertTrue(reference.get().healthy());
                } finally {
                  temporary.close();
                }
                reference.get().close();
                closed.countDown();
              }
            }

            @Override
            public void changed(Set<ProjectionVersions.Domain> domains) {
              fail("No change was requested");
            }
          });
      signals.start();
      await(closed);
      await(session.closed);
      assertFalse(signals.healthy());
      assertThrows(IllegalStateException.class, () -> signals.subscribe(SEARCH, noop));
    }
  }

  @Test
  void registrationCapacityAndFailureCleanupAreExplicit() throws Exception {
    var source = new Source();
    var session = source.add();
    var observer = new RecordingObserver();
    var signals = listener(source, versions(), 2);
    try {
      var subscription = signals.subscribe(SEARCH, observer);
      ProjectionChangeSignals.Observer invalid = mock(ProjectionChangeSignals.Observer.class);
      doThrow(new IllegalStateException("fixture observer failure"))
          .when(invalid)
          .stateChanged(any());
      assertThrows(IllegalStateException.class, () -> signals.subscribe(SEARCH, invalid));
      var other = signals.subscribe(SEARCH, new RecordingObserver());
      assertThrows(
          IllegalStateException.class, () -> signals.subscribe(SEARCH, new RecordingObserver()));
      other.close();
      other.close();
      signals.start();
      await(observer.firstReady);
      subscription.close();
      var replacement = signals.subscribe(SEARCH, new RecordingObserver());
      try {
        assertTrue(signals.healthy());
      } finally {
        replacement.close();
      }
      signals.close();
      await(session.closed);
    } finally {
      signals.close();
    }
  }

  private static ProjectionVersions versions() {
    var versions = mock(ProjectionVersions.class);
    when(versions.capture(anySet())).thenReturn(Optional.of(TOKEN));
    when(versions.current(TOKEN)).thenReturn(true);
    return versions;
  }

  private static ListeningProjectionChangeSignals listener(
      Source source, ProjectionVersions versions, int observers) {
    return new ListeningProjectionChangeSignals(
        source, versions, observers, Duration.ofMillis(20), Duration.ofMillis(1));
  }

  private static void await(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(2, TimeUnit.SECONDS), "Lifecycle transition did not complete");
  }

  private static final class RecordingObserver implements ProjectionChangeSignals.Observer {
    final java.util.Deque<ProjectionChangeSignals.State> states =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    final BlockingQueue<Set<ProjectionVersions.Domain>> changes = new ArrayBlockingQueue<>(4);
    final CountDownLatch firstReady = new CountDownLatch(1);
    final CountDownLatch secondReady = new CountDownLatch(1);
    final CountDownLatch recovering = new CountDownLatch(1);
    final AtomicInteger readyCount = new AtomicInteger();

    @Override
    public void stateChanged(ProjectionChangeSignals.State state) {
      states.add(state);
      if (state.ready()) {
        if (readyCount.incrementAndGet() == 1) firstReady.countDown();
        else secondReady.countDown();
      } else if (readyCount.get() > 0) recovering.countDown();
    }

    @Override
    public void changed(Set<ProjectionVersions.Domain> domains) {
      assertTrue(changes.offer(domains));
    }
  }

  private static final class Source implements ProjectionNotificationSource {
    final ConcurrentLinkedQueue<Session> sessions = new ConcurrentLinkedQueue<>();

    FakeSession add() {
      var session = new FakeSession();
      sessions.add(session);
      return session;
    }

    @Override
    public Session open() {
      Session next = sessions.poll();
      if (next == null) throw new IllegalStateException("No fixture session");
      return next;
    }
  }

  private record Poll(Set<ProjectionVersions.Domain> changes, boolean disconnect) {}

  private static final class FakeSession implements ProjectionNotificationSource.Session {
    final AtomicBoolean listening = new AtomicBoolean();
    final BlockingQueue<Poll> responses = new ArrayBlockingQueue<>(4);
    final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public void listen() {
      listening.set(true);
    }

    @Override
    public Set<ProjectionVersions.Domain> poll(Duration timeout) {
      try {
        Poll response = responses.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (response == null) return Set.of();
        if (response.disconnect()) throw new IllegalStateException("Fixture disconnect");
        return response.changes();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Fixture poll interrupted");
      }
    }

    void changes(Set<ProjectionVersions.Domain> changes) {
      assertTrue(responses.offer(new Poll(changes, false)));
    }

    void disconnect() {
      assertTrue(responses.offer(new Poll(Set.of(), true)));
    }

    @Override
    public void close() {
      closed.countDown();
    }
  }
}
