package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RelayDeliveryContractTest {
  @Test
  void publication_destinations_are_explicit_and_bound_to_the_builtin_receiver() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.Branch("forward", "relay.publish", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.Branch("forward", "custom", null, "release.forwarded"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.Branch("forward", "relay.publish", null, "../outside"));
    var first = new RelayDeliveries.Branch("forward", "relay.publish", null, "release.forwarded");
    assertNotEquals(
        first, new RelayDeliveries.Branch("forward", "relay.publish", null, "release.other"));
  }

  private static final RelayDeliveries.SourcePin ROUTE =
      RelayDeliveries.SourcePin.of("release-review/routes.js", "export const routes = [];");
  private static final Relay.SubscriptionKey SUBSCRIPTION =
      new Relay.SubscriptionKey(new Relay.TopicKey(1, "release.observed"), "release-review");

  @Test
  void routing_and_handler_sources_are_pinned_bounded_and_contained() {
    assertEquals(64, ROUTE.sha256().length());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.SourcePin(ROUTE.path(), "changed", ROUTE.sha256()));
    for (String path :
        List.of(
            "../routes.js",
            "/routes.js",
            "release-review/../routes.js",
            "release-review/scripts/../evil.js",
            "release-review\\routes.js")) {
      assertThrows(
          IllegalArgumentException.class, () -> RelayDeliveries.SourcePin.of(path, "code"));
    }
    assertThrows(
        IllegalArgumentException.class, () -> RelayDeliveries.SourcePin.of(ROUTE.path(), "code\0"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RelayDeliveries.SourcePin.of(ROUTE.path(), "code" + (char) 0xD800));
    assertThrows(
        IllegalArgumentException.class,
        () -> RelayDeliveries.SourcePin.of(ROUTE.path(), "é".repeat(65537)));
    var other = RelayDeliveries.SourcePin.of("another/scripts/handler.js", "code");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayDeliveries.Decision(
                ROUTE, List.of(new RelayDeliveries.Branch("branch-a", "receiver-a", other))));
  }

  @Test
  void scripts_are_optional_and_zero_branches_are_a_valid_durable_decision() {
    assertTrue(new RelayDeliveries.Decision(ROUTE, List.of()).branches().isEmpty());
    var branch = new RelayDeliveries.Branch("notify", "agent-inbox", null);
    assertNull(
        new RelayDeliveries.Decision(ROUTE, List.of(branch)).branches().getFirst().handler());
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.Branch("notify", "agent-inbox", ROUTE));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RelayDeliveries.Decision(ROUTE, List.of(branch, branch)));
  }

  @Test
  void fan_out_and_total_pinned_sources_are_bounded_before_persistence() {
    var branches = new ArrayList<RelayDeliveries.Branch>();
    for (int index = 0; index < 33; index++)
      branches.add(new RelayDeliveries.Branch("branch-" + index, "receiver", null));
    assertThrows(
        IllegalArgumentException.class, () -> new RelayDeliveries.Decision(ROUTE, branches));
    branches.clear();
    var source =
        RelayDeliveries.SourcePin.of("release-review/scripts/handler.js", "x".repeat(131072));
    for (int index = 0; index < 8; index++)
      branches.add(new RelayDeliveries.Branch("branch-" + index, "receiver", source));
    assertThrows(
        IllegalArgumentException.class, () -> new RelayDeliveries.Decision(ROUTE, branches));
  }

  @Test
  void admission_copies_lists_and_rejects_foreign_scope() {
    var key = new RelayDeliveries.AdmissionKey(SUBSCRIPTION, 1);
    var draft =
        new Relay.Draft(
            "event-one", "source", Instant.EPOCH, null, null, new RelayPayload.Text("data"));
    var publication = new Relay.Publication(SUBSCRIPTION.topic(), 1, Instant.EPOCH, draft);
    var branch = new RelayDeliveries.Branch("branch", "receiver", null);
    var keys =
        new ArrayList<>(List.of(new RelayDeliveries.DeliveryKey(SUBSCRIPTION, UUID.randomUUID())));
    var admission =
        new RelayDeliveries.Admission(
            key,
            publication,
            new RelayDeliveries.Decision(ROUTE, List.of(branch)),
            Instant.EPOCH,
            keys);
    keys.clear();
    assertEquals(1, admission.deliveries().size());
    assertThrows(UnsupportedOperationException.class, () -> admission.deliveries().clear());
    var foreign =
        new Relay.SubscriptionKey(
            new Relay.TopicKey(2, "release.observed"), SUBSCRIPTION.subscriber());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayDeliveries.Admission(
                key,
                publication,
                admission.decision(),
                Instant.EPOCH,
                List.of(new RelayDeliveries.DeliveryKey(foreign, UUID.randomUUID()))));
  }

  @Test
  void invalid_leases_and_worker_identities_fail_before_repository_calls() {
    var repository = mock(RelayDeliveryRepository.class);
    RelayDeliveries deliveries = new DurableRelayDeliveries(repository, Instant::now);
    for (Duration lease :
        List.of(
            Duration.ZERO,
            Duration.ofMillis(999),
            Duration.ofMinutes(6),
            Duration.ofSeconds(2).plusNanos(1))) {
      assertThrows(
          IllegalArgumentException.class, () -> deliveries.claim(SUBSCRIPTION, "worker", lease));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> deliveries.claim(SUBSCRIPTION, "worker\n", Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class, () -> new RelayDeliveries.Receipt("../domain", "id"));
    verifyNoInteractions(repository);
  }

  @Test
  void admission_time_is_assigned_by_the_broker_and_normalized() {
    var repository = mock(RelayDeliveryRepository.class);
    Instant now = Instant.parse("2026-10-05T01:00:00.123456789Z");
    var key = new RelayDeliveries.AdmissionKey(SUBSCRIPTION, 1);
    var decision = new RelayDeliveries.Decision(ROUTE, List.of());
    var event =
        new Relay.Draft("event", "publisher", now, null, null, new RelayPayload.Text("input"));
    var expected =
        new RelayDeliveries.Admission(
            key,
            new Relay.Publication(SUBSCRIPTION.topic(), 1, now, event),
            decision,
            now,
            List.of());
    when(repository.admit(key, decision, expected.admittedAt())).thenReturn(expected);
    RelayDeliveries deliveries = new DurableRelayDeliveries(repository, () -> now);
    assertEquals(expected, deliveries.admit(key, decision));
    verify(repository).admit(key, decision, Instant.parse("2026-10-05T01:00:00.123456Z"));
  }
}
