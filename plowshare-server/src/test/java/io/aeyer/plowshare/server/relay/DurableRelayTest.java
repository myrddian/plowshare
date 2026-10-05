package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
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
    when(repository.retained(topic, "event")).thenReturn(java.util.Optional.empty());
    assertEquals(java.util.Optional.empty(), broker.retained(topic, "event"));
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
  void invalid_family_or_read_bounds_fail_before_persistence() {
    var repository = mock(RelayRepository.class);
    Relay broker = new DurableRelay(repository, Instant::now);
    var key = new Relay.TopicKey(1, "schedule.due");
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.configureTopic(key, RelayPayload.Kind.EMPTY, Relay.Policy.systemDefault()));
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.read(new Relay.SubscriptionKey(key, "route-a"), 1001));
    assertThrows(
        IllegalArgumentException.class,
        () -> broker.acknowledgeGap(new Relay.SubscriptionKey(key, "route-a"), 0));
    verifyNoInteractions(repository);
  }
}
