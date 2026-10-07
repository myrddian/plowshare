package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.protocol.RelayCausation;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ForwardRelayReceiverTest {
  private final Relay relay = mock(Relay.class);
  private final Relay.TopicKey target = new Relay.TopicKey(9, "release.forwarded");
  private final RelayForwardingHistory history = mock(RelayForwardingHistory.class);
  private final RelayReceiver forward = new ForwardRelayReceiver(relay, history, 8);

  @BeforeEach
  void registered() {
    when(history.causation(any(), eq(8)))
        .thenReturn(Optional.of(RelayCausation.root("release-event")));
    when(relay.topic(target))
        .thenReturn(
            new Relay.Topic(target, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 0, 0));
  }

  @Test
  void exhausted_or_missing_forwarding_ancestry_refuses_effects_but_not_receipt_inspection() {
    var request =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.DISPATCHING, false));
    when(history.causation(any(), eq(8)))
        .thenReturn(Optional.of(new RelayCausation("root", "parent", 8)));
    assertThrows(RelayReceiver.Refused.class, () -> forward.dispatch(request));
    when(history.causation(any(), eq(8))).thenReturn(Optional.empty());
    assertThrows(RelayReceiver.Refused.class, () -> forward.dispatch(request));
    verify(relay, never()).publish(any(), any());
    clearInvocations(history);
    var uncertain =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.UNCERTAIN, false));
    when(relay.retained(target, "relay:" + uncertain.identity())).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), forward.inspect(uncertain));
    verifyNoInteractions(history);
  }

  @Test
  void review_requests_and_sdk_reply_descendants_cannot_be_forwarded() {
    var root = RelayReviewCausation.root("00000000-0000-4000-8000-000000000001");
    var request =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.DISPATCHING, false));
    for (var cause : java.util.List.of(root, root.next("review-request", 8))) {
      when(history.causation(any(), eq(8))).thenReturn(Optional.of(cause));
      assertEquals(
          "receiver.review-protocol.refused",
          assertThrows(RelayReceiver.Refused.class, () -> forward.require(request)).code());
      assertEquals(
          "receiver.review-protocol.refused",
          assertThrows(RelayReceiver.Refused.class, () -> forward.dispatch(request)).code());
    }
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void incompatible_topic_is_refused_before_publication_without_changing_its_policy() {
    when(relay.topic(target))
        .thenReturn(
            new Relay.Topic(target, RelayPayload.Kind.EMPTY, Relay.Policy.systemDefault(), 0, 0));
    assertThrows(
        RelayReceiver.Refused.class,
        () ->
            forward.require(
                new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.CLAIMED, false))));
    verify(relay, never()).publish(any(), any());
    verify(relay, never()).configureTopic(any(), any(), any());
  }

  @Test
  void forwarding_preserves_input_provenance_and_uses_the_stable_delivery_identity() {
    var request =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.DISPATCHING, false));
    when(relay.publish(eq(target), any()))
        .thenAnswer(
            invocation -> {
              Relay.Draft draft = invocation.getArgument(1);
              assertEquals("relay:" + request.identity(), draft.eventId());
              assertEquals("relay:relay.publish", draft.publisher());
              assertEquals(request.delivery().publication().event().payload(), draft.payload());
              assertEquals("cycle", draft.correlationId());
              assertEquals("release-event", draft.causationId());
              assertEquals(Instant.EPOCH, draft.occurredAt());
              return new Relay.Publication(target, 1, Instant.EPOCH.plusSeconds(60), draft);
            });
    assertEquals(
        new RelayReceiver.Settled(new RelayDeliveries.Accepted(RECEIPT)),
        forward.dispatch(request));
    verify(relay, never()).configureTopic(any(), any(), any());
  }

  @Test
  void retained_receipts_reconcile_without_republishing_and_absence_is_inconclusive() {
    var request =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.UNCERTAIN, false));
    when(relay.retained(target, "relay:" + request.identity())).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), forward.inspect(request));
    var event =
        new Relay.Draft(
            "relay:" + request.identity(),
            "relay:relay.publish",
            Instant.EPOCH,
            "cycle",
            "release-event",
            request.delivery().publication().event().payload());
    when(relay.retained(target, event.eventId()))
        .thenReturn(Optional.of(new Relay.Publication(target, 1, Instant.EPOCH, event)));
    assertEquals(Optional.of(new RelayDeliveries.Accepted(RECEIPT)), forward.inspect(request));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void a_conflicting_receipt_never_settles_an_uncertain_effect() {
    var request =
        new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.UNCERTAIN, false));
    var wrong =
        new Relay.Draft(
            "relay:" + request.identity(),
            "other-publisher",
            Instant.EPOCH,
            null,
            null,
            new RelayPayload.Text("other input"));
    when(relay.retained(target, wrong.eventId()))
        .thenReturn(Optional.of(new Relay.Publication(target, 1, Instant.EPOCH, wrong)));
    assertThrows(RelayReceiver.Refused.class, () -> forward.inspect(request));
    verify(relay, never()).publish(any(), any());
  }

  @Test
  void cross_project_self_publication_handlers_and_unprepared_calls_are_refused() {
    var claimed = new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.CLAIMED, false));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayReceiver.Request(
                new RelayProjectFiles.Access("operator", "other-project", 10), claimed.delivery()));
    var self =
        new RelayReceiver.Request(
            ACCESS, delivery(RelayDeliveries.State.CLAIMED, false, SUB.topic().name()));
    assertThrows(RelayReceiver.Refused.class, () -> forward.require(self));
    assertThrows(
        RelayReceiver.Refused.class,
        () ->
            forward.require(
                new RelayReceiver.Request(ACCESS, delivery(RelayDeliveries.State.CLAIMED, true))));
    assertThrows(IllegalArgumentException.class, () -> forward.dispatch(claimed));
    assertThrows(IllegalArgumentException.class, () -> forward.inspect(claimed));
    verifyNoInteractions(relay);
  }
}
