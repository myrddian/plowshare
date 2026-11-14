package io.aeyer.plowshare.server.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The listener hands keyed item hints only to observers of the items' domains. */
class ItemChangeSignalsTest {
  private static final ProjectionVersions.Item TREE =
      new ProjectionVersions.Item(ProjectionVersions.Domain.READABILITY, "conversation:root");

  @Test
  void itemHintsReachOnlyObserversOfTheirDomainAlongsideDomainHints() throws Exception {
    var polls = new ArrayBlockingQueue<ProjectionNotificationSource.Changes>(4);
    var session = new Session(polls);
    var versions = mock(ProjectionVersions.class);
    when(versions.capture(anySet()))
        .thenReturn(
            Optional.of(
                new ProjectionVersions.Token(
                    new UUID(0, 1),
                    Map.of(ProjectionVersions.Domain.AUTHORITY, 1L),
                    List.of(),
                    "999999",
                    null,
                    null)));
    when(versions.current(any())).thenReturn(true);
    try (var signals =
        new ListeningProjectionChangeSignals(
            () -> session, versions, 4, Duration.ofMillis(20), Duration.ofMillis(20))) {
      var readability = new Recorder();
      var authority = new Recorder();
      signals.subscribe(Set.of(ProjectionVersions.Domain.READABILITY), readability);
      signals.subscribe(Set.of(ProjectionVersions.Domain.AUTHORITY), authority);
      signals.start();
      assertTrue(readability.ready.await(5, TimeUnit.SECONDS));

      polls.add(
          new ProjectionNotificationSource.Changes(
              Set.of(ProjectionVersions.Domain.READABILITY), Set.of(TREE)));
      assertEquals(Set.of(TREE), readability.items.poll(5, TimeUnit.SECONDS));
      assertEquals(
          Set.of(ProjectionVersions.Domain.READABILITY),
          readability.domains.poll(5, TimeUnit.SECONDS));
      polls.add(
          new ProjectionNotificationSource.Changes(
              Set.of(ProjectionVersions.Domain.AUTHORITY), Set.of()));
      assertEquals(
          Set.of(ProjectionVersions.Domain.AUTHORITY), authority.domains.poll(5, TimeUnit.SECONDS));
      assertNull(authority.items.poll(100, TimeUnit.MILLISECONDS), "No item of its domains");
    }
  }

  private static final class Recorder implements ProjectionChangeSignals.Observer {
    final CountDownLatch ready = new CountDownLatch(1);
    final BlockingQueue<Set<ProjectionVersions.Domain>> domains = new ArrayBlockingQueue<>(8);
    final BlockingQueue<Set<ProjectionVersions.Item>> items = new ArrayBlockingQueue<>(8);

    public void stateChanged(ProjectionChangeSignals.State state) {
      if (state.ready()) ready.countDown();
    }

    public void changed(Set<ProjectionVersions.Domain> changed) {
      domains.add(changed);
    }

    public void itemsChanged(Set<ProjectionVersions.Item> changed) {
      items.add(changed);
    }
  }

  private record Session(BlockingQueue<ProjectionNotificationSource.Changes> polls)
      implements ProjectionNotificationSource.Session {
    public void listen() {}

    public Set<ProjectionVersions.Domain> poll(Duration timeout) {
      return pollChanges(timeout).domains();
    }

    public ProjectionNotificationSource.Changes pollChanges(Duration timeout) {
      try {
        var next = polls.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return next == null ? ProjectionNotificationSource.Changes.NONE : next;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Fixture poll interrupted");
      }
    }

    public void close() {}
  }
}
