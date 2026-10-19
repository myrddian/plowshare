package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.SUB;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NotificationRelayPublicationSignalsTest {
  @Test
  void remoteHintsWakeEachGroupAndDiscoveryAndCoalesceWithoutAnEventQueue() throws Exception {
    var source = new Source();
    try (var signals =
            new NotificationRelayPublicationSignals(source, new RelayWorkerProperties());
        var first = signals.listen(SUB);
        var second = signals.listen(new Relay.SubscriptionKey(SUB.topic(), "other.group"));
        var discovery = signals.discovery();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < 10000; i++) source.changed();
      for (var waiting : java.util.List.of(first, second, discovery))
        executor
            .submit(
                () -> {
                  waiting.await(Duration.ofSeconds(30));
                  return true;
                })
            .get(1, TimeUnit.SECONDS);
      // A local unrelated topic wakes discovery, but does not enqueue payloads for other groups.
      signals.published(new Relay.TopicKey(999, "another.topic"));
      executor
          .submit(
              () -> {
                discovery.await(Duration.ofSeconds(30));
                return true;
              })
          .get(1, TimeUnit.SECONDS);
      assertEquals(1, source.observers.get());
    }
    assertEquals(0, source.observers.get());
  }

  @Test
  void recoveryWakesExistingReadersAndStaleCallbacksCannotRestoreOldHealth() throws Exception {
    var source = new Source();
    try (var signals =
            new NotificationRelayPublicationSignals(source, new RelayWorkerProperties());
        var waiting = signals.listen(SUB);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Duration fallback = Duration.ofMillis(100);
      assertEquals(fallback, signals.reconciliationWait(fallback));
      source.state(2, true);
      assertEquals(Duration.ofSeconds(30), signals.reconciliationWait(fallback));
      waiting.await(Duration.ofMillis(1));
      source.state(3, false);
      executor
          .submit(
              () -> {
                waiting.await(Duration.ofSeconds(30));
                return true;
              })
          .get(1, TimeUnit.SECONDS);
      source.state(2, true);
      assertEquals(fallback, signals.reconciliationWait(fallback));
      source.state(4, true);
      assertEquals(Duration.ofSeconds(30), signals.reconciliationWait(fallback));
    }
  }

  @Test
  void closeUnblocksWaitersAndReleasesBoundedRegistrations() throws Exception {
    var local = new LocalRelayPublicationSignals(1);
    var first = local.listen(SUB);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      assertThrows(IllegalStateException.class, local::discovery);
      first.close();
      var second = local.discovery();
      var entered = new CountDownLatch(1);
      var parked =
          executor.submit(
              () -> {
                entered.countDown();
                assertThrows(
                    InterruptedException.class, () -> second.await(Duration.ofSeconds(30)));
                return true;
              });
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      local.close();
      assertTrue(parked.get(1, TimeUnit.SECONDS));
      assertThrows(IllegalStateException.class, () -> local.listen(SUB));
    } finally {
      local.close();
    }
  }

  @Test
  void discoveryRescansDurableSourcesWhenAnEmptyScanReceivesARemoteHint() throws Exception {
    var source = new Source();
    var repository = mock(RelaySourceRepository.class);
    var reads = new AtomicInteger();
    var initial = new CountDownLatch(1);
    var rescanned = new CountDownLatch(1);
    when(repository.pendingTopics(any(), anyInt()))
        .thenAnswer(
            call -> {
              if (reads.incrementAndGet() == 1) initial.countDown();
              else rescanned.countDown();
              return java.util.List.of();
            });
    var properties = new RelayWorkerProperties();
    try (var signals = new NotificationRelayPublicationSignals(source, properties);
        var workers =
            new RelayInternalWorkers(
                repository,
                mock(RelayNativeRepository.class),
                mock(RelayConsumerRepository.class),
                mock(Relay.class),
                signals,
                mock(io.aeyer.plowshare.server.events.Dispatcher.class),
                properties,
                true)) {
      source.state(2, true);
      workers.start();
      assertTrue(initial.await(1, TimeUnit.SECONDS));
      source.changed();
      assertTrue(
          rescanned.await(1, TimeUnit.SECONDS),
          "Remote hint must not wait for the 30-second reconciliation");
    }
  }

  static final class Source implements ProjectionChangeSignals {
    final AtomicReference<Observer> observer = new AtomicReference<>();
    final AtomicInteger observers = new AtomicInteger();

    public Subscription subscribe(Set<ProjectionVersions.Domain> domains, Observer observer) {
      assertTrue(domains.contains(ProjectionVersions.Domain.RELAY));
      this.observer.set(observer);
      observers.incrementAndGet();
      observer.stateChanged(new State(1, false));
      return () -> observers.decrementAndGet();
    }

    void state(long generation, boolean ready) {
      observer.get().stateChanged(new State(generation, ready));
    }

    void changed() {
      observer.get().changed(Set.of(ProjectionVersions.Domain.RELAY));
    }

    public void start() {}

    public boolean healthy() {
      return false;
    }

    public void close() {}
  }
}
