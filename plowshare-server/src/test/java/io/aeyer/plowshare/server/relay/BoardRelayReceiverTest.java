package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayCausation;
import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.BoardBridge;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BoardRelayReceiverTest {
  private final BoardBridge bridge = mock(BoardBridge.class);
  private final RelayPublisherAccounts publishers = mock(RelayPublisherAccounts.class);
  private final RelayPorts ports = mock(RelayPorts.class);
  private final RelayForwardingHistory history = mock(RelayForwardingHistory.class);
  private final RelayReceiver receiver =
      new BoardRelayReceiver("board.post", bridge, publishers, ports, history, 8);

  private RelayReceiver.Request request(RelayDeliveries.State state) {
    var base = RelayDispatchFixtures.delivery(state, false);
    var publication =
        new Relay.Publication(
            base.publication().topic(),
            1,
            base.publication().publishedAt(),
            new Relay.Draft(
                "source-event",
                RelayPort.publisher("publisher"),
                base.publication().event().occurredAt(),
                null,
                null,
                new RelayPayload.Text(
                    "{\"schema\":\"plowshare-board-request/1\",\"body\":\"Request\",\"reply_to\":\"collector.inbox\"}")));
    var branch =
        new RelayDeliveries.Branch(
            "board", "board.post", null, null, new RelayWork(null, null, null, "bdt_Topic", null));
    var delivery =
        new RelayDeliveries.Delivery(
            base.key(),
            base.admission(),
            publication,
            base.routing(),
            branch,
            state,
            base.fence(),
            base.worker(),
            base.leaseUntil(),
            base.admittedAt(),
            base.updatedAt(),
            base.receipt(),
            base.failureCode());
    return new RelayReceiver.Request(RelayDispatchFixtures.ACCESS, delivery);
  }

  @BeforeEach
  void setup() {
    when(publishers.publisherAccount(RelayPort.publisher("publisher")))
        .thenReturn(Optional.of("publisher"));
    when(ports.authorizePublisher("publisher", "project", "release.observed"))
        .thenReturn(RelayDispatchFixtures.SUB.topic());
    when(history.causation(any(), eq(8)))
        .thenReturn(Optional.of(RelayCausation.root("source-event")));
  }

  @Test
  void actor_is_the_original_publisher_and_delivery_uuid_is_the_forum_request_id() {
    var request = request(RelayDeliveries.State.DISPATCHING);
    when(bridge.accept(any())).thenReturn(new BoardBridge.Receipt("bdt_Topic", "bdm_Post"));
    var result = receiver.dispatch(request);
    var command = ArgumentCaptor.forClass(BoardBridge.Command.class);
    verify(bridge).accept(command.capture());
    assertEquals("publisher", command.getValue().account());
    assertEquals(request.identity(), command.getValue().requestId());
    assertEquals(
        new RelayDeliveries.Accepted(new RelayDeliveries.Receipt("board.message", "bdm_Post")),
        ((RelayReceiver.Settled) result).resolution());
  }

  @Test
  void unrecognized_publishers_are_never_replaced_with_the_worker_identity() {
    when(publishers.publisherAccount(any())).thenReturn(Optional.empty());
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    verifyNoInteractions(bridge, ports);
  }

  @Test
  void known_closed_topic_is_a_definite_preflight_refusal() {
    doThrow(new Board.Refused("closed")).when(bridge).require(any());
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    verify(bridge, never()).accept(any());
  }

  @Test
  void reconciliation_is_read_only_and_survives_ingress_revocation() {
    when(bridge.inspect(any()))
        .thenReturn(Optional.of(new BoardBridge.Receipt("bdt_Topic", "bdm_Post")));
    assertTrue(receiver.inspect(request(RelayDeliveries.State.UNCERTAIN)).isPresent());
    verifyNoInteractions(ports, history);
    verify(bridge, never()).accept(any());
  }

  @Test
  void a_prepared_request_is_required_and_uncertain_effects_are_not_replayed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> receiver.dispatch(request(RelayDeliveries.State.CLAIMED)));
    when(bridge.inspect(any())).thenReturn(Optional.empty());
    assertTrue(receiver.inspect(request(RelayDeliveries.State.UNCERTAIN)).isEmpty());
    verify(bridge, never()).accept(any());
  }
}
