package io.aeyer.plowshare.server.relay.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Delivery is driven synchronously through a captured observer; nothing waits on a timer. */
class ProjectionRelayToolWakeupsTest {
  private final AtomicReference<ProjectionChangeSignals.Observer> observer =
      new AtomicReference<>();
  private final AtomicReference<Set<Domain>> subscribed = new AtomicReference<>();

  private final ProjectionChangeSignals signals =
      new ProjectionChangeSignals() {
        public Subscription subscribe(Set<Domain> domains, Observer value) {
          subscribed.set(domains);
          observer.set(value);
          return () -> observer.set(null);
        }

        public void start() {}

        public boolean healthy() {
          return true;
        }

        public void close() {}
      };

  @Test
  void relay_and_authority_changes_wake_and_only_authority_is_reported() throws Exception {
    var wakeups = new ProjectionRelayToolWakeups(signals, 4);
    assertEquals(Set.of(Domain.RELAY, Domain.AUTHORITY), subscribed.get());
    try (var wake = wakeups.open()) {
      observer.get().changed(Set.of(Domain.RELAY));
      assertFalse(wake.await(Duration.ofSeconds(30)));
      observer.get().changed(Set.of(Domain.RELAY, Domain.AUTHORITY));
      assertTrue(wake.await(Duration.ofSeconds(30)));
      observer.get().stateChanged(new ProjectionChangeSignals.State(1, false));
      assertTrue(wake.await(Duration.ofSeconds(30)));
      assertFalse(wake.await(Duration.ofMillis(1)));
    }
    wakeups.close();
    assertNull(observer.get());
  }

  @Test
  void a_closed_registration_stops_receiving_and_interrupts_its_wait() {
    var wakeups = new ProjectionRelayToolWakeups(signals, 4);
    var wake = wakeups.open();
    wake.close();
    assertThrows(InterruptedException.class, () -> wake.await(Duration.ofSeconds(30)));
  }

  @Test
  void registrations_beyond_the_bound_degrade_to_a_timed_wait() throws Exception {
    var wakeups = new ProjectionRelayToolWakeups(signals, 1);
    try (var first = wakeups.open();
        var second = wakeups.open()) {
      assertFalse(second.await(Duration.ofMillis(1)));
      observer.get().changed(Set.of(Domain.AUTHORITY));
      assertTrue(first.await(Duration.ofSeconds(30)));
      assertFalse(second.await(Duration.ofMillis(1)));
    }
  }
}
