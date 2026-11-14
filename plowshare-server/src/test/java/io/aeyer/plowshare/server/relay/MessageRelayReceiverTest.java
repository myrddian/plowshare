package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.faults.CallerFault;
import io.aeyer.plowshare.server.messaging.MessageRelayAccess;
import io.aeyer.plowshare.server.messaging.MessageRelayIngress;
import io.aeyer.plowshare.server.messaging.MessageRelayPayload;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessageRelayReceiverTest {
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Relay.TopicKey SOURCE = RelayDispatchFixtures.SUB.topic();
  private static final Relay.TopicKey RETURN = new Relay.TopicKey(9, "results");
  private final MessageRelayIngress ingress = mock(MessageRelayIngress.class);
  private final MessageRelayAccess access = mock(MessageRelayAccess.class);
  private final RelayPublisherAccounts publishers = mock(RelayPublisherAccounts.class);
  private final RelayRepository topics = mock(RelayRepository.class);
  private final RelayForwardingHistory history = mock(RelayForwardingHistory.class);

  @BeforeEach
  void setup() {
    when(publishers.publisherAccount(RelayPort.publisher("alice")))
        .thenReturn(Optional.of("alice"));
    when(topics.topic(SOURCE))
        .thenReturn(
            new Relay.Topic(
                SOURCE, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 1, 0, ID));
    when(topics.topic(RETURN))
        .thenReturn(
            new Relay.Topic(
                RETURN, RelayPayload.Kind.TEXT, Relay.Policy.systemDefault(), 0, 0, ID));
  }

  private MessageRelayReceiver receiver(String name) {
    return new MessageRelayReceiver(
        name, ingress, access, publishers, topics, history, 8, () -> Instant.EPOCH);
  }

  private RelayReceiver.Request request(
      String receiver,
      String channel,
      String publisher,
      RelayCausation cause,
      RelayDeliveries.State state) {
    var base = RelayDispatchFixtures.delivery(state, false);
    String schema =
        switch (receiver) {
          case "message.receive" -> "plowshare-message-request/1";
          case "message.cancel" -> "plowshare-message-cancel/1";
          default -> "plowshare-message-inspect/1";
        };
    String body =
        "{\"schema\":\""
            + schema
            + "\",\"requestId\":\""
            + ID
            + "\",\"client\":\"client\","
            + switch (receiver) {
              case "message.receive" -> "\"context\":null,\"body\":\"Question\",\"command\":null}";
              case "message.cancel" -> "\"task\":\"" + ID + "\"}";
              default -> "\"task\":null,\"originalRequest\":\"" + ID + "\",\"cursor\":null}";
            };
    var publication =
        new Relay.Publication(
            SOURCE,
            1,
            Instant.EPOCH,
            new Relay.Draft(
                "source-event",
                publisher,
                Instant.EPOCH,
                null,
                null,
                new RelayPayload.Text(body),
                cause));
    var delivery =
        new RelayDeliveries.Delivery(
            base.key(),
            base.admission(),
            publication,
            base.routing(),
            new RelayDeliveries.Branch(
                "selected-" + receiver,
                receiver,
                null,
                null,
                new RelayWork("worker", null, null, null, null, "results", channel)),
            state,
            base.fence(),
            base.worker(),
            base.leaseUntil(),
            base.admittedAt(),
            base.updatedAt(),
            base.receipt(),
            base.failureCode(),
            ID);
    return new RelayReceiver.Request(RelayDispatchFixtures.ACCESS, delivery);
  }

  @Test
  void inspection_copies_null_unknown_and_exhausted_ancestry_without_consuming_an_effect() {
    for (var cause :
        Arrays.asList(
            null, RelayCausation.unknown("old"), new RelayCausation("root", "parent", 8))) {
      receiver("message.inspect")
          .dispatch(
              request(
                  "message.inspect",
                  "shared",
                  RelayPort.publisher("alice"),
                  cause,
                  RelayDeliveries.State.DISPATCHING));
    }
    var admitted = ArgumentCaptor.forClass(MessageRelayIngress.Request.class);
    verify(ingress, times(3)).accept(admitted.capture());
    assertEquals(
        Arrays.asList(null, RelayCausation.unknown("old"), new RelayCausation("root", "parent", 8)),
        admitted.getAllValues().stream().map(value -> value.intent().causation()).toList());
    verifyNoInteractions(history);
  }

  @Test
  void receive_and_cancel_still_require_effect_budget_and_share_inspection_family() {
    when(history.causation(any(), anyInt())).thenReturn(Optional.of(RelayCausation.root("root")));
    receiver("message.receive")
        .dispatch(
            request(
                "message.receive",
                "shared",
                RelayPort.publisher("alice"),
                null,
                RelayDeliveries.State.DISPATCHING));
    receiver("message.cancel")
        .dispatch(
            request(
                "message.cancel",
                "shared",
                RelayPort.publisher("alice"),
                null,
                RelayDeliveries.State.DISPATCHING));
    receiver("message.inspect")
        .dispatch(
            request(
                "message.inspect",
                "shared",
                RelayPort.publisher("alice"),
                null,
                RelayDeliveries.State.DISPATCHING));
    receiver("message.inspect")
        .dispatch(
            request(
                "message.inspect",
                "other",
                RelayPort.publisher("alice"),
                null,
                RelayDeliveries.State.DISPATCHING));
    var values = ArgumentCaptor.forClass(MessageRelayIngress.Request.class);
    verify(ingress, times(4)).accept(values.capture());
    var intents = values.getAllValues().stream().map(MessageRelayIngress.Request::intent).toList();
    assertEquals(intents.get(0).family().namespace(), intents.get(1).family().namespace());
    assertEquals(intents.get(0).family().namespace(), intents.get(2).family().namespace());
    assertNotEquals(intents.get(2).family().namespace(), intents.get(3).family().namespace());
    assertEquals(1, intents.getFirst().causation().depth());
    when(history.causation(any(), anyInt()))
        .thenReturn(Optional.of(new RelayCausation("root", "parent", 8)));
    assertThrows(
        RelayReceiver.Refused.class,
        () ->
            receiver("message.cancel")
                .require(
                    request(
                        "message.cancel",
                        "shared",
                        RelayPort.publisher("alice"),
                        null,
                        RelayDeliveries.State.CLAIMED)));
  }

  @Test
  void exact_execution_proof_is_distinct_and_forgery_or_live_permission_loss_refuses() {
    var execution =
        request(
            "message.inspect",
            "shared",
            "execution:server-owned",
            null,
            RelayDeliveries.State.DISPATCHING);
    assertThrows(RelayReceiver.Refused.class, () -> receiver("message.inspect").require(execution));
    when(publishers.executionIdentity(execution.delivery().publication()))
        .thenReturn(Optional.of(new RelayPublisherAccounts.Execution("alice", "producer")));
    receiver("message.inspect").dispatch(execution);
    var admitted = ArgumentCaptor.forClass(MessageRelayIngress.Request.class);
    verify(ingress).accept(admitted.capture());
    assertEquals("producer", admitted.getValue().intent().family().executionDefinition());
    assertEquals(9, admitted.getValue().intent().family().projectId());
    doThrow(new CallerFault("revoked source or return grant")).when(access).require(any());
    assertThrows(
        RelayReceiver.Refused.class,
        () ->
            receiver("message.inspect")
                .require(
                    request(
                        "message.inspect",
                        "shared",
                        RelayPort.publisher("alice"),
                        null,
                        RelayDeliveries.State.CLAIMED)));
  }

  @Test
  void uncertain_receipt_inspection_needs_no_current_topic_or_mutation() {
    var request =
        request(
            "message.inspect",
            "shared",
            RelayPort.publisher("alice"),
            null,
            RelayDeliveries.State.UNCERTAIN);
    when(ingress.inspect(
            request.identity(), 9, RelayPort.publisher("alice"), SOURCE.name(), "source-event"))
        .thenReturn(Optional.of(mock(MessageRelayPayload.Snapshot.class)));
    assertTrue(receiver("message.inspect").inspect(request).isPresent());
    verifyNoInteractions(topics, access, history);
    verify(ingress, never()).accept(any());
  }

  @Test
  void a_stale_source_incarnation_cannot_be_reinterpreted_as_a_new_channel_family() {
    var captured =
        request(
            "message.inspect",
            "shared",
            RelayPort.publisher("alice"),
            null,
            RelayDeliveries.State.DISPATCHING);
    when(topics.topic(SOURCE))
        .thenReturn(
            new Relay.Topic(
                SOURCE,
                RelayPayload.Kind.TEXT,
                Relay.Policy.systemDefault(),
                0,
                0,
                UUID.randomUUID()));
    assertThrows(RelayReceiver.Refused.class, () -> receiver("message.inspect").dispatch(captured));
    verifyNoInteractions(ingress, history);
  }
}
