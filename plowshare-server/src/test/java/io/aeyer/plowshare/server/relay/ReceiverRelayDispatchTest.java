package io.aeyer.plowshare.server.relay;

import static io.aeyer.plowshare.server.relay.RelayDispatchFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.ProjectMembers;
import io.aeyer.plowshare.server.archive.ProjectWorkspaces;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReceiverRelayDispatchTest {
  private static final Duration LEASE = Duration.ofSeconds(30);
  private final RelayDeliveries deliveries = mock(RelayDeliveries.class);
  private final RelayReceiver receiver = mock(RelayReceiver.class);
  private final ProjectMembers members = mock(ProjectMembers.class);
  private final ProjectWorkspaces projects = mock(ProjectWorkspaces.class);
  private final RelayDispatch.Boundary boundary = mock(RelayDispatch.Boundary.class);
  private final RelayReceivers registry =
      new RegisteredRelayReceivers(List.of(new RelayReceivers.Binding("relay.publish", receiver)));
  private final RelayDispatch dispatcher =
      new ReceiverRelayDispatch(deliveries, registry, members, projects, boundary);
  private final RelayDeliveries.Delivery claimed = delivery(RelayDeliveries.State.CLAIMED, false);
  private final RelayDeliveries.Delivery prepared =
      delivery(RelayDeliveries.State.DISPATCHING, false);

  @BeforeEach
  void available() {
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(9L);
    when(deliveries.claim(SUB, "worker", LEASE)).thenReturn(Optional.of(claimed));
    when(deliveries.prepareDispatch(claimed.claim())).thenReturn(prepared);
    when(deliveries.renew(claimed.claim(), LEASE)).thenReturn(prepared);
  }

  @Test
  void intent_and_live_lease_precede_the_effect_then_receipt_is_persisted() {
    var accepted = delivery(RelayDeliveries.State.ACCEPTED, false);
    when(receiver.dispatch(any()))
        .thenReturn(new RelayReceiver.Settled(new RelayDeliveries.Accepted(RECEIPT)));
    when(deliveries.accepted(claimed.claim(), RECEIPT)).thenReturn(accepted);
    assertEquals(Optional.of(accepted), dispatcher.next(ACCESS, SUB, "worker", LEASE));
    var order = inOrder(boundary, deliveries, receiver);
    order.verify(boundary).requireOutsideTransaction();
    order.verify(deliveries).claim(SUB, "worker", LEASE);
    order.verify(receiver).require(new RelayReceiver.Request(ACCESS, claimed));
    order.verify(deliveries).prepareDispatch(claimed.claim());
    order.verify(receiver).require(new RelayReceiver.Request(ACCESS, prepared));
    order.verify(deliveries).renew(claimed.claim(), LEASE);
    order.verify(boundary).requireOutsideTransaction();
    order.verify(receiver).dispatch(new RelayReceiver.Request(ACCESS, prepared));
    order.verify(deliveries).accepted(claimed.claim(), RECEIPT);
    verify(receiver, never()).inspect(any());
    verify(members, times(2)).mayWork("project", "operator");
  }

  @Test
  void stopping_during_preflight_cannot_start_an_effect() {
    doAnswer(
            call -> {
              Thread.currentThread().interrupt();
              return null;
            })
        .when(receiver)
        .require(any());
    when(deliveries.failed(claimed.claim(), "consumer.stopped"))
        .thenReturn(delivery(RelayDeliveries.State.FAILED, false));
    try {
      assertEquals(
          RelayDeliveries.State.FAILED,
          dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
      verify(receiver, never()).dispatch(any());
      verify(deliveries, never()).prepareDispatch(any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void unauthorized_scope_and_invalid_lease_fail_without_claiming() {
    when(members.mayWork("project", "operator")).thenReturn(false);
    assertThrows(CallerFault.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE));
    when(members.mayWork("project", "operator")).thenReturn(true);
    when(projects.id("project")).thenReturn(10L);
    assertThrows(CallerFault.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE));
    when(projects.id("project")).thenReturn(9L);
    assertThrows(
        IllegalArgumentException.class,
        () -> dispatcher.next(ACCESS, SUB, "worker", Duration.ZERO));
    assertThrows(
        CallerFault.class,
        () ->
            dispatcher.next(
                new RelayProjectFiles.Access("operator", "project", 10), SUB, "worker", LEASE));
    verify(deliveries, never()).claim(any(), any(), any());
    verifyNoInteractions(receiver);
  }

  @Test
  void no_work_is_empty_and_removed_receiver_has_no_effect() {
    when(deliveries.claim(SUB, "worker", LEASE)).thenReturn(Optional.empty());
    assertEquals(Optional.empty(), dispatcher.next(ACCESS, SUB, "worker", LEASE));
    when(deliveries.claim(SUB, "worker", LEASE)).thenReturn(Optional.of(claimed));
    var unavailable =
        new ReceiverRelayDispatch(
            deliveries, new RegisteredRelayReceivers(List.of()), members, projects, boundary);
    when(deliveries.failed(claimed.claim(), "receiver.unavailable"))
        .thenReturn(delivery(RelayDeliveries.State.FAILED, false));
    assertEquals(
        RelayDeliveries.State.FAILED,
        unavailable.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    verify(deliveries, never()).prepareDispatch(any());
    verifyNoInteractions(receiver);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RegisteredRelayReceivers(
                List.of(
                    new RelayReceivers.Binding("duplicate", receiver),
                    new RelayReceivers.Binding("duplicate", receiver))));
  }

  @Test
  void a_pinned_handler_cannot_be_silently_ignored_by_an_ordinary_receiver() {
    var scripted = delivery(RelayDeliveries.State.CLAIMED, true);
    when(deliveries.claim(SUB, "worker", LEASE)).thenReturn(Optional.of(scripted));
    when(deliveries.failed(scripted.claim(), "receiver.handler.unsupported"))
        .thenReturn(delivery(RelayDeliveries.State.FAILED, true));
    assertEquals(
        RelayDeliveries.State.FAILED,
        dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    verify(deliveries, never()).prepareDispatch(any());
    verify(receiver, never()).dispatch(any());
  }

  @Test
  void preflight_refusal_and_revocation_after_preparation_are_definite_no_effect_failures() {
    doThrow(new RelayReceiver.Refused("destination.refused")).when(receiver).require(any());
    when(deliveries.failed(claimed.claim(), "destination.refused"))
        .thenReturn(delivery(RelayDeliveries.State.FAILED, false));
    assertEquals(
        RelayDeliveries.State.FAILED,
        dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    verify(deliveries, never()).prepareDispatch(any());
    reset(receiver);
    doAnswer(
            invocation -> {
              RelayReceiver.Request request = invocation.getArgument(0);
              if (request.delivery().state() == RelayDeliveries.State.DISPATCHING)
                throw new RelayReceiver.Refused("destination.refused");
              return null;
            })
        .when(receiver)
        .require(any());
    assertEquals(
        RelayDeliveries.State.FAILED,
        dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    verify(deliveries).prepareDispatch(claimed.claim());
    verify(receiver, never()).dispatch(any());
  }

  @Test
  void dispatch_exceptions_and_missing_results_are_uncertain_without_private_error_text() {
    when(receiver.dispatch(any())).thenThrow(new IllegalStateException("private-secret"));
    when(deliveries.uncertain(claimed.claim(), "receiver.exception"))
        .thenReturn(delivery(RelayDeliveries.State.UNCERTAIN, false));
    assertEquals(
        RelayDeliveries.State.UNCERTAIN,
        dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    doReturn(null).when(receiver).dispatch(any());
    assertEquals(
        RelayDeliveries.State.UNCERTAIN,
        dispatcher.next(ACCESS, SUB, "worker", LEASE).orElseThrow().state());
    verify(deliveries, times(2)).uncertain(claimed.claim(), "receiver.exception");
    verify(deliveries, never()).failed(any(), any());
  }

  @Test
  void receipt_write_failure_is_not_reclassified_and_expired_intent_never_dispatches() {
    when(receiver.dispatch(any()))
        .thenReturn(new RelayReceiver.Settled(new RelayDeliveries.Accepted(RECEIPT)));
    var lostWrite = new IllegalStateException("broker write failed");
    when(deliveries.accepted(claimed.claim(), RECEIPT)).thenThrow(lostWrite);
    assertSame(
        lostWrite,
        assertThrows(
            IllegalStateException.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE)));
    verify(deliveries, never()).uncertain(any(), any());
    clearInvocations(receiver);
    when(deliveries.renew(claimed.claim(), LEASE)).thenThrow(new IllegalStateException("expired"));
    assertThrows(IllegalStateException.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE));
    verify(receiver, never()).dispatch(any());
  }

  @Test
  void enclosing_transactions_are_refused_before_claim_or_effect() {
    doThrow(new IllegalStateException("outer transaction"))
        .when(boundary)
        .requireOutsideTransaction();
    assertThrows(IllegalStateException.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE));
    verifyNoInteractions(deliveries, receiver);
    reset(boundary);
    doNothing()
        .doThrow(new IllegalStateException("leaked transaction"))
        .when(boundary)
        .requireOutsideTransaction();
    assertThrows(IllegalStateException.class, () -> dispatcher.next(ACCESS, SUB, "worker", LEASE));
    verify(deliveries).prepareDispatch(claimed.claim());
    verify(receiver, never()).dispatch(any());
  }

  @Test
  void receiver_result_preserves_explicit_no_effect_and_uncertainty() {
    var failed = delivery(RelayDeliveries.State.FAILED, false);
    when(receiver.dispatch(any()))
        .thenReturn(new RelayReceiver.Settled(new RelayDeliveries.Failed("destination.refused")));
    when(deliveries.failed(claimed.claim(), "destination.refused")).thenReturn(failed);
    assertEquals(Optional.of(failed), dispatcher.next(ACCESS, SUB, "worker", LEASE));
    when(receiver.dispatch(any())).thenReturn(new RelayReceiver.Uncertain("destination.unknown"));
    var uncertain = delivery(RelayDeliveries.State.UNCERTAIN, false);
    when(deliveries.uncertain(claimed.claim(), "destination.unknown")).thenReturn(uncertain);
    assertEquals(Optional.of(uncertain), dispatcher.next(ACCESS, SUB, "worker", LEASE));
  }

  @Test
  void reconciliation_reads_only_and_absence_or_errors_preserve_uncertainty() {
    var uncertain = delivery(RelayDeliveries.State.UNCERTAIN, false);
    when(deliveries.delivery(uncertain.key())).thenReturn(Optional.of(uncertain));
    when(receiver.inspect(any())).thenReturn(Optional.empty());
    assertEquals(uncertain, dispatcher.reconcile(ACCESS, uncertain.key()));
    verify(deliveries, never()).reconcile(any(), any());
    when(receiver.inspect(any())).thenThrow(new IllegalStateException("private-secret"));
    var error =
        assertThrows(CallerFault.class, () -> dispatcher.reconcile(ACCESS, uncertain.key()));
    assertNull(error.getCause());
    assertFalse(error.getMessage().contains("private-secret"));
    verify(receiver, never()).dispatch(any());
    verify(deliveries, never()).prepareDispatch(any());
    verify(deliveries, never()).claim(any(), any(), any());
  }

  @Test
  void reconciliation_settles_known_receipt_and_terminal_reads_are_idempotent() {
    var uncertain = delivery(RelayDeliveries.State.UNCERTAIN, false);
    var accepted = delivery(RelayDeliveries.State.ACCEPTED, false);
    var resolution = new RelayDeliveries.Accepted(RECEIPT);
    when(deliveries.delivery(uncertain.key())).thenReturn(Optional.of(uncertain));
    when(receiver.inspect(any())).thenReturn(Optional.of(resolution));
    when(deliveries.reconcile(uncertain.key(), resolution)).thenReturn(accepted);
    assertEquals(accepted, dispatcher.reconcile(ACCESS, uncertain.key()));
    when(deliveries.delivery(uncertain.key())).thenReturn(Optional.of(accepted));
    assertEquals(accepted, dispatcher.reconcile(ACCESS, uncertain.key()));
    verify(receiver, times(1)).inspect(any());
    when(deliveries.delivery(uncertain.key())).thenReturn(Optional.of(prepared));
    assertThrows(CallerFault.class, () -> dispatcher.reconcile(ACCESS, uncertain.key()));
    verify(receiver, never()).dispatch(any());
  }
}
