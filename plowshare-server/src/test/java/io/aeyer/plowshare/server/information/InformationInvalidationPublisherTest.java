package io.aeyer.plowshare.server.information;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.AccountEvent;
import io.aeyer.plowshare.server.cache.ProjectionChangeSignals;
import io.aeyer.plowshare.server.cache.ProjectionVersions.Domain;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class InformationInvalidationPublisherTest {
  private static final class Signals implements ProjectionChangeSignals {
    Observer observer;
    Set<Domain> domains;
    final AtomicInteger disposed = new AtomicInteger();

    public Subscription subscribe(Set<Domain> selected, Observer listener) {
      domains = selected;
      observer = listener;
      listener.stateChanged(new State(1, false));
      return disposed::incrementAndGet;
    }

    public void start() {}

    public boolean healthy() {
      return true;
    }

    public void close() {}
  }

  private static final class Clock implements InformationInvalidationClock {
    Runnable pending;
    int scheduled;

    public void schedule(Runnable action) {
      assertNull(pending, "Only one pending task across all accounts and sockets");
      pending = action;
      scheduled++;
    }

    void tick() {
      var action = pending;
      pending = null;
      if (action != null) action.run();
    }

    public void close() {}
  }

  @Test
  void stormCoalescesWithoutPostponingRefreshAndRevocationDominatesImmediately() throws Exception {
    var source = new Signals();
    var clock = new Clock();
    var full = new AtomicInteger();
    var content = new AtomicInteger();
    var progress = new AtomicInteger();
    var pushes =
        new InformationInvalidations() {
          public void invalidate() {
            full.incrementAndGet();
          }

          public void refresh() {
            content.incrementAndGet();
          }

          public void progress() {
            progress.incrementAndGet();
          }
        };
    try (var publisher = new InformationInvalidationPublisher(source, pushes, clock)) {
      publisher.start();
      publisher.start();
      assertEquals(
          Set.of(
              Domain.DOCUMENTS,
              Domain.KNOWLEDGE_ACCESS,
              Domain.KNOWLEDGE_REVOCATIONS,
              Domain.AUTHORITY,
              Domain.PROGRESS,
              Domain.TYPES,
              Domain.EXTRACTIONS),
          source.domains);
      assertEquals(1, full.get());
      for (int i = 0; i < 10000; i++) {
        source.observer.changed(Set.of(Domain.PROGRESS));
        source.observer.changed(Set.of(Domain.DOCUMENTS));
        source.observer.changed(Set.of(Domain.KNOWLEDGE_ACCESS));
        source.observer.changed(Set.of(Domain.READABILITY)); // Worker/log visibility is unrelated.
      }
      assertEquals(1, clock.scheduled);
      assertEquals(1, full.get());
      clock.tick();
      assertEquals(1, content.get());
      assertEquals(0, progress.get(), "Content dominates progress in one bounded slot");
      source.observer.changed(Set.of(Domain.PROGRESS));
      clock.tick();
      assertEquals(1, progress.get());
      source.observer.changed(Set.of(Domain.DOCUMENTS));
      source.observer.changed(Set.of(Domain.AUTHORITY));
      assertEquals(2, full.get(), "Revocation must never wait for a soft deadline");
      clock.tick();
      assertEquals(1, content.get(), "Revocation consumes previous soft work");
      source.observer.changed(Set.of(Domain.KNOWLEDGE_REVOCATIONS));
      source.observer.stateChanged(new ProjectionChangeSignals.State(2, true));
      source.observer.stateChanged(new ProjectionChangeSignals.State(1, false));
      assertEquals(4, full.get());
      source.observer.changed(Set.of(Domain.DOCUMENTS));
    }
    source.observer.changed(Set.of(Domain.AUTHORITY));
    clock.tick();
    assertEquals(4, full.get());
    assertEquals(1, content.get());
    assertEquals(1, source.disposed.get());
    var serialized =
        new ObjectMapper()
            .readTree(
                new ObjectMapper().writeValueAsString(new AccountEvent.InformationInvalidated()));
    assertEquals(
        1, serialized.size(), "Absent flags retain the full-invalidation legacy wire form");
  }

  @Test
  void compositionForwardsEveryHintKindThroughTheLateBoundTransport() {
    var source = new Signals();
    var clock = new Clock();
    var beans = new StaticListableBeanFactory();
    var full = new AtomicInteger();
    var content = new AtomicInteger();
    var progress = new AtomicInteger();
    var transport =
        new InformationInvalidations() {
          public void invalidate() {
            full.incrementAndGet();
          }

          public void refresh() {
            content.incrementAndGet();
          }

          public void progress() {
            progress.incrementAndGet();
          }
        };
    try (var publisher =
        new InformationConfig()
            .informationInvalidationPublisher(
                source, beans.getBeanProvider(InformationInvalidations.class), clock)) {
      publisher.start();
      beans.addBean("transport", transport);
      source.observer.changed(Set.of(Domain.PROGRESS));
      clock.tick();
      source.observer.changed(Set.of(Domain.DOCUMENTS));
      clock.tick();
      assertEquals(1, progress.get());
      assertEquals(1, content.get());
      assertEquals(0, full.get(), "Composition must not promote soft hints to revocation");
      source.observer.changed(Set.of(Domain.PROGRESS, Domain.AUTHORITY));
      source.observer.changed(Set.of(Domain.KNOWLEDGE_REVOCATIONS));
      source.observer.stateChanged(new ProjectionChangeSignals.State(2, true));
      assertEquals(3, full.get());
    }
    source.observer.changed(Set.of(Domain.PROGRESS));
    clock.tick();
    assertEquals(1, progress.get());
  }

  @Test
  void synchronousReentrantCloseDisposesTheSubscriptionReturnedAfterItsCallback() {
    var source = new Signals();
    var current = new AtomicReference<InformationInvalidationPublisher>();
    var publisher = new InformationInvalidationPublisher(source, () -> current.get().close());
    current.set(publisher);
    publisher.start();
    publisher.close();
    assertEquals(1, source.disposed.get());
  }

  @Test
  void closeBeforeStartNeverRegisters() {
    var source = new Signals();
    var publisher = new InformationInvalidationPublisher(source, () -> fail("Closed publisher"));
    publisher.close();
    publisher.start();
    assertNull(source.observer);
  }
}
