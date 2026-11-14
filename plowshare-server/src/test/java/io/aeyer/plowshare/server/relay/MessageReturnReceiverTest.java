package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.server.messaging.MessageExports;
import io.aeyer.plowshare.server.messaging.MessageRefused;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessageReturnReceiverTest {
  private final MessageExports exports = mock(MessageExports.class);
  private final RelayPublisherAccounts publishers = mock(RelayPublisherAccounts.class);
  private final RelayReceiver receiver = new MessageReturnReceiver(exports, publishers);
  private static final UUID SOURCE = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RETURN = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @BeforeEach
  void setup() {
    when(publishers.publisherAccount(RelayPort.publisher("adapter")))
        .thenReturn(Optional.of("adapter"));
  }

  private RelayReceiver.Request request(RelayDeliveries.State state) {
    var base = RelayDispatchFixtures.delivery(state, false);
    var publication =
        new Relay.Publication(
            base.publication().topic(),
            1,
            base.publication().publishedAt(),
            new Relay.Draft(
                "return-event",
                RelayPort.publisher("adapter"),
                base.publication().event().occurredAt(),
                null,
                null,
                new RelayPayload.Text(
                    "{\"schema\":\"plowshare-message-return/1\",\"requestId\":\""
                        + RETURN
                        + "\",\"sourceEvent\":\""
                        + SOURCE
                        + "\",\"body\":\"Result\",\"finalReply\":true}")));
    var delivery =
        new RelayDeliveries.Delivery(
            base.key(),
            base.admission(),
            publication,
            base.routing(),
            new RelayDeliveries.Branch("channel", "message.return", null),
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

  @Test
  void return_uses_authenticated_actor_and_pinned_route_without_asserted_instance() {
    when(exports.accept(any())).thenReturn(new MessageExports.Receipt("bdm_Return"));
    var result = receiver.dispatch(request(RelayDeliveries.State.DISPATCHING));
    var command = ArgumentCaptor.forClass(MessageExports.Command.class);
    verify(exports).accept(command.capture());
    assertEquals("adapter", command.getValue().adapterAccount());
    assertEquals("channel", command.getValue().channel());
    assertEquals(9, command.getValue().projectId());
    assertEquals(SOURCE, command.getValue().value().sourceEvent());
    assertEquals(RETURN, command.getValue().value().requestId());
    assertEquals(
        new RelayDeliveries.Accepted(new RelayDeliveries.Receipt("message.return", "bdm_Return")),
        ((RelayReceiver.Settled) result).resolution());
  }

  @Test
  void execution_and_unrecognized_publishers_cannot_impersonate_an_adapter() {
    when(publishers.executionPublication(any())).thenReturn(true);
    assertThrows(
        RelayReceiver.Refused.class,
        () -> receiver.require(request(RelayDeliveries.State.CLAIMED)));
    verifyNoInteractions(exports);
    verify(publishers, never()).publisherAccount(any());
  }

  @Test
  void definite_refusal_settles_but_retained_receipt_wins_after_commit_failure() {
    when(exports.accept(any())).thenThrow(new MessageRefused("Refused"));
    var failed =
        (RelayReceiver.Settled) receiver.dispatch(request(RelayDeliveries.State.DISPATCHING));
    assertTrue(failed.resolution() instanceof RelayDeliveries.Failed);
    when(exports.inspect(any())).thenReturn(Optional.of(new MessageExports.Receipt("bdm_Return")));
    var retained =
        (RelayReceiver.Settled) receiver.dispatch(request(RelayDeliveries.State.DISPATCHING));
    assertTrue(retained.resolution() instanceof RelayDeliveries.Accepted);
  }

  @Test
  void inspection_reads_receipt_without_admission_or_mutation() {
    when(exports.inspect(any())).thenReturn(Optional.of(new MessageExports.Receipt("bdm_Return")));
    assertTrue(receiver.inspect(request(RelayDeliveries.State.UNCERTAIN)).isPresent());
    verify(exports, never()).require(any());
    verify(exports, never()).accept(any());
    assertThrows(
        IllegalArgumentException.class,
        () -> receiver.dispatch(request(RelayDeliveries.State.CLAIMED)));
  }
}
