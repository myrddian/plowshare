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

  @Test
  void sourceAndRecoveryHintsInvalidateWithoutRetainingAnEventQueue() throws Exception {
    var source = new Signals();
    var sent = new AtomicInteger();
    try (var publisher = new InformationInvalidationPublisher(source, sent::incrementAndGet)) {
      publisher.start();
      publisher.start();
      assertEquals(
          Set.of(Domain.DOCUMENTS, Domain.READABILITY, Domain.AUTHORITY, Domain.PROGRESS),
          source.domains);
      assertEquals(1, sent.get());
      source.observer.stateChanged(new ProjectionChangeSignals.State(2, true));
      source.observer.changed(Set.of(Domain.DOCUMENTS));
      source.observer.changed(Set.of(Domain.AUTHORITY));
      assertEquals(4, sent.get());
      source.observer.changed(Set.of(Domain.TRANSCRIPTS));
      for (int i = 0; i < 1000; i++) source.observer.changed(Set.of(Domain.OBSERVATIONS));
      source.observer.stateChanged(new ProjectionChangeSignals.State(1, false));
      assertEquals(4, sent.get(), "Unrelated or reordered callbacks must not invalidate");
      source.observer.stateChanged(new ProjectionChangeSignals.State(3, false));
      source.observer.stateChanged(new ProjectionChangeSignals.State(4, true));
      assertEquals(6, sent.get());
    }
    source.observer.changed(Set.of(Domain.DOCUMENTS));
    assertEquals(6, sent.get());
    assertEquals(1, source.disposed.get());
    var serialized =
        new ObjectMapper()
            .readTree(
                new ObjectMapper().writeValueAsString(new AccountEvent.InformationInvalidated()));
    assertEquals(1, serialized.size());
    assertEquals("information.invalidated", serialized.get("kind").asText());
  }

  @Test
  void progressIsSoftButAnyConcurrentContentOrAuthorityChangeDominates() {
    var source = new Signals();
    var full = new AtomicInteger();
    var progress = new AtomicInteger();
    var pushes =
        new InformationInvalidations() {
          public void invalidate() {
            full.incrementAndGet();
          }

          public void progress() {
            progress.incrementAndGet();
          }
        };
    try (var publisher = new InformationInvalidationPublisher(source, pushes)) {
      publisher.start();
      source.observer.changed(Set.of(Domain.PROGRESS));
      assertEquals(1, progress.get());
      assertEquals(1, full.get());
      source.observer.changed(Set.of(Domain.PROGRESS, Domain.AUTHORITY));
      source.observer.changed(Set.of(Domain.PROGRESS, Domain.READABILITY));
      source.observer.changed(Set.of(Domain.PROGRESS, Domain.DOCUMENTS));
      assertEquals(1, progress.get());
      assertEquals(4, full.get());
    }
    source.observer.changed(Set.of(Domain.PROGRESS));
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
