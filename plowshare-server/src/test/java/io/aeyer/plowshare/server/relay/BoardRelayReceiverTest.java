package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import io.aeyer.plowshare.server.board.BoardNativeRequests;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.util.Optional;
import java.util.UUID;
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
    when(ports.authorizePublisher("publisher", "project", "collector.inbox"))
        .thenReturn(new Relay.TopicKey(9, "collector.inbox"));
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
    verify(ports).authorizePublisher("publisher", "project", "collector.inbox");
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
  void reply_topic_requires_the_publishers_exact_live_ingress_grant() {
    when(ports.authorizePublisher("publisher", "project", "collector.inbox"))
        .thenThrow(new CallerFault("denied"));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.dispatch(request(RelayDeliveries.State.DISPATCHING)));
    verifyNoInteractions(bridge);
  }

  @Test
  void reply_ingress_revocation_after_preflight_stops_the_forum_mutation() {
    receiver.require(request(RelayDeliveries.State.CLAIMED));
    when(ports.authorizePublisher("publisher", "project", "collector.inbox"))
        .thenThrow(new CallerFault("revoked"));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.dispatch(request(RelayDeliveries.State.DISPATCHING)));
    verify(bridge, never()).accept(any());
  }

  @Test
  void a_reply_grant_cannot_resolve_to_a_different_project_or_topic() {
    when(ports.authorizePublisher("publisher", "project", "collector.inbox"))
        .thenReturn(new Relay.TopicKey(10, "collector.inbox"));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    verifyNoInteractions(bridge);
  }

  @Test
  void execution_provenance_is_never_resolved_as_a_person_publisher() {
    var original = request(RelayDeliveries.State.CLAIMED);
    var old = original.delivery();
    var publication =
        new Relay.Publication(
            old.publication().topic(),
            1,
            old.publication().publishedAt(),
            new Relay.Draft(
                "source-event",
                "execution:" + "a".repeat(64) + ":" + "b".repeat(64),
                old.publication().event().occurredAt(),
                null,
                null,
                old.publication().event().payload()));
    var delivery =
        new RelayDeliveries.Delivery(
            old.key(),
            old.admission(),
            publication,
            old.routing(),
            old.branch(),
            old.state(),
            old.fence(),
            old.worker(),
            old.leaseUntil(),
            old.admittedAt(),
            old.updatedAt(),
            old.receipt(),
            old.failureCode());
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(new RelayReceiver.Request(original.access(), delivery)));
    verifyNoInteractions(ports, bridge);
    verify(publishers, never()).publisherAccount(any());
  }

  @Test
  void historical_execution_receipts_are_not_reclassified_as_human_sdk_actions() {
    when(publishers.executionIdentity(any()))
        .thenReturn(Optional.of(new RelayPublisherAccounts.Execution("publisher", "worker")));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    verifyNoInteractions(ports, bridge);
    verify(publishers, never()).publisherAccount(any());
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

  private RelayReceiver.Request nativeRequest(RelayDeliveries.State state) {
    var old = request(state).delivery();
    var event = UUID.fromString("11111111-1111-1111-1111-111111111111");
    var publication =
        new Relay.Publication(
            old.publication().topic(),
            1,
            old.publication().publishedAt(),
            new Relay.Draft(
                event.toString(),
                "execution:" + "a".repeat(64) + ":" + "b".repeat(64),
                old.publication().event().occurredAt(),
                null,
                null,
                new RelayPayload.Text(
                    "{\"schema\":\"plowshare-board-request/1\",\"body\":\"Request\"}"),
                new RelayCausation("root", "execution-run", 1)));
    var delivery =
        new RelayDeliveries.Delivery(
            old.key(),
            old.admission(),
            publication,
            old.routing(),
            old.branch(),
            state,
            old.fence(),
            old.worker(),
            old.leaseUntil(),
            old.admittedAt(),
            old.updatedAt(),
            old.receipt(),
            old.failureCode());
    return new RelayReceiver.Request(RelayDispatchFixtures.ACCESS, delivery);
  }

  @Test
  void proven_native_preflight_refusal_records_durable_outcome_only_after_dispatch_intent() {
    var nativeRequests = mock(BoardNativeRequests.class);
    var nativeReceiver =
        new BoardRelayReceiver("board.post", bridge, publishers, ports, history, 8, nativeRequests);
    var proof = new RelayPublisherAccounts.Execution("publisher", "worker");
    var actor =
        new BoardBridge.Agent(
            "publisher", "worker", UUID.fromString("11111111-1111-1111-1111-111111111111"));
    when(publishers.executionIdentity(any())).thenReturn(Optional.of(proof));
    when(nativeRequests.actor(any(), any(), any(), any(), any(), anyBoolean()))
        .thenReturn(new BoardNativeRequests.Principal(actor, true));
    when(nativeRequests.refuse(any(), any())).thenReturn(BoardNativeRequests.Refusal.RECORDED);
    doThrow(new Board.Refused("destination revoked")).when(bridge).require(any());
    nativeReceiver.require(nativeRequest(RelayDeliveries.State.CLAIMED));
    verify(nativeRequests, never()).refuse(any(), any());
    doThrow(new Board.Refused("destination revoked")).when(bridge).accept(any());
    var dispatched = nativeRequest(RelayDeliveries.State.DISPATCHING);
    var result = (RelayReceiver.Settled) nativeReceiver.dispatch(dispatched);
    assertEquals(new RelayDeliveries.Failed("board.native.refused"), result.resolution());
    verify(nativeRequests).refuse(dispatched.identity(), actor);
    when(nativeRequests.refused(any())).thenReturn(true);
    assertEquals(
        new RelayDeliveries.Failed("board.native.refused"),
        nativeReceiver.inspect(nativeRequest(RelayDeliveries.State.UNCERTAIN)).orElseThrow());
    verifyNoInteractions(ports);
  }

  @Test
  void execution_without_an_export_cannot_become_a_person_or_claim_native_endpoints() {
    when(publishers.executionIdentity(any()))
        .thenReturn(Optional.of(new RelayPublisherAccounts.Execution("publisher", "worker")));
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(nativeRequest(RelayDeliveries.State.CLAIMED)));
    verify(publishers, never()).publisherAccount(any());
    verifyNoInteractions(ports, bridge);
  }
}
