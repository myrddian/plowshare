package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableRelayTest {
  @Test
  void receipt_lookup_validates_identity_before_persistence() {
    var repository = mock(RelayRepository.class);
    Relay broker = new DurableRelay(repository, Instant::now);
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.retained(new Relay.TopicKey(1, "release.observed"), "private\n"));
    verifyNoInteractions(repository);
    var topic = new Relay.TopicKey(1, "release.observed");
    when(repository.retained(topic, "event")).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), broker.retained(topic, "event"));
    verify(repository).retained(topic, "event");
  }

  @Test
  void publication_time_comes_from_the_broker_clock_not_the_occurrence() {
    var repository = mock(RelayRepository.class);
    Instant now = Instant.parse("2026-10-05T01:00:00.123456789Z");
    Relay broker = new DurableRelay(repository, () -> now);
    var key = new Relay.TopicKey(1, "release.observed");
    var draft =
        new Relay.Draft(
            "event-1",
            "release-source",
            now.minusSeconds(100),
            null,
            null,
            new RelayPayload.Text("released"));
    var expected = new Relay.Publication(key, 1, now, draft);
    when(repository.append(key, draft, expected.publishedAt())).thenReturn(expected);
    assertEquals(expected, broker.publish(key, draft));
    verify(repository).append(key, draft, Instant.parse("2026-10-05T01:00:00.123456Z"));
  }

  @Test
  void generation_pinned_append_only_emits_a_commit_hint_for_an_actual_publication() {
    var repository = mock(RelayRepository.class);
    var now = Instant.parse("2026-10-05T01:00:00Z");
    var hints = new AtomicInteger();
    Relay broker = new DurableRelay(repository, () -> now, ignored -> hints.incrementAndGet());
    var key = new Relay.TopicKey(1, "release.observed");
    var generation = UUID.randomUUID();
    var draft =
        new Relay.Draft("event-1", "source", now, null, null, new RelayPayload.Text("answer"));
    var unavailable = new Relay.DestinationUnavailable(Relay.DestinationFailure.REPLACED);
    when(repository.append(key, generation, draft, now)).thenReturn(unavailable);
    assertEquals(unavailable, broker.publish(key, generation, draft));
    assertEquals(0, hints.get());
    var appended = new Relay.Appended(new Relay.Publication(key, 1, now, draft));
    when(repository.append(key, generation, draft, now)).thenReturn(appended);
    assertEquals(appended, broker.publish(key, generation, draft));
    assertEquals(1, hints.get());
    when(repository.append(key, generation, draft, now))
        .thenThrow(new IllegalStateException("connection"));
    assertThrows(IllegalStateException.class, () -> broker.publish(key, generation, draft));
    assertEquals(1, hints.get());
  }

  @Test
  void invalid_family_or_read_bounds_fail_before_persistence() {
    var repository = mock(RelayRepository.class);
    Relay broker = new DurableRelay(repository, Instant::now);
    var key = new Relay.TopicKey(1, "release.observed");
    // The wake families are the only ones still bound to a name, and they are Relay's own topics;
    // the schedule.due agreement this used to assert is gone with the literal.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            broker.configureTopic(
                new Relay.TopicKey(Relay.SystemScope.SERVER, "board.wake.requested"),
                RelayPayload.Kind.EMPTY,
                Relay.Policy.systemDefault()));
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.read(new Relay.SubscriptionKey(key, "route-a"), 1001));
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.acknowledgeGap(new Relay.SubscriptionKey(key, "route-a"), 0));
    verifyNoInteractions(repository);
  }
}
