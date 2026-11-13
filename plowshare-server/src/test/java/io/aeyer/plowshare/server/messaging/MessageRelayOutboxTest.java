package io.aeyer.plowshare.server.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MessageRelayOutboxTest {
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final MessageRelayRepository repository = mock(MessageRelayRepository.class);
  private final MessageRelayPages pages = mock(MessageRelayPages.class);
  private final MessageRelayAccess access = mock(MessageRelayAccess.class);
  private final Relay relay = mock(Relay.class);
  private final MessageRelayOutbox outbox =
      new RelayMessageRelayOutbox(repository, pages, access, relay, UnitOfWork.NONE);
  private final MessageRelayRepository.Family family =
      new MessageRelayRepository.Family(
          "relay-family",
          "alice",
          "project",
          9,
          RelayPort.publisher("alice"),
          "commands",
          "subscriber",
          ID,
          "shared",
          null,
          "worker",
          "results",
          ID);
  private final MessageRelayPayload.Snapshot snapshot =
      new MessageRelayPayload.Snapshot(
          "plowshare-message-task/1",
          ID,
          "source-event",
          "client",
          null,
          null,
          "NOT_ACCESSIBLE",
          null,
          null);
  private final MessageRelayRepository.Operation operation =
      new MessageRelayRepository.Operation(
          "dispatch:operation", family, snapshot, Instant.EPOCH, RelayCausation.unknown("old"));

  @BeforeEach
  void taskFence() {
    when(repository.lockTask(any())).thenReturn(MessageRelayRepository.TaskFence.NOT_REQUIRED);
  }

  @Test
  void return_notice_uses_stable_identity_pinned_incarnation_and_unchanged_cause() {
    when(repository.pending()).thenReturn(Optional.of(operation));
    when(relay.publish(eq(family.replies()), eq(ID), any()))
        .thenReturn(new Relay.Appended(mock(Relay.Publication.class)));
    assertTrue(outbox.publishNext());
    var draft = ArgumentCaptor.forClass(Relay.Draft.class);
    verify(relay).publish(eq(family.replies()), eq(ID), draft.capture());
    assertEquals(MessageRelayPayload.PUBLISHER, draft.getValue().publisher());
    assertEquals(
        "message.task:" + RelayPort.hash(operation.identity()), draft.getValue().eventId());
    assertEquals(operation.causation(), draft.getValue().causation());
    assertEquals(Instant.EPOCH, draft.getValue().occurredAt());
    verify(repository).published(operation);
  }

  @Test
  void missing_replaced_or_incompatible_destination_is_terminal_without_execution_replay() {
    for (var failure : Relay.DestinationFailure.values()) {
      when(repository.pending()).thenReturn(Optional.of(operation));
      when(relay.publish(eq(family.replies()), eq(ID), any()))
          .thenReturn(new Relay.DestinationUnavailable(failure));
      assertTrue(outbox.publishNext());
      verify(repository).unavailable(operation, failure.name());
    }
    verify(repository, never()).published(any());
  }

  @Test
  void transient_permission_or_append_failure_defers_only_its_operation() {
    when(repository.pending()).thenReturn(Optional.of(operation)).thenReturn(Optional.empty());
    doThrow(new MessageRefused("private body must never reach logs")).when(access).require(family);
    assertTrue(outbox.publishNext());
    verify(repository).defer(operation);
    verify(relay, never()).publish(any(), any(), any());
    verify(repository, never()).published(any());
    assertFalse(outbox.publishNext());
  }

  @Test
  void capture_failure_retains_progress_and_yields_to_independent_tasks() {
    var binding =
        new MessageRelayRepository.Binding(
            family,
            ID,
            "client",
            ID,
            "source-event",
            new MessageRelayPayload.Cursor(ID, 0, 0, 0, true).encode(),
            1,
            null);
    when(repository.nextTask()).thenReturn(Optional.of(binding)).thenReturn(Optional.empty());
    doThrow(new MessageRefused("revoked")).when(access).require(family);
    assertTrue(outbox.captureNext());
    verify(repository).deferTask(binding);
    verify(repository, never()).update(any(), any());
    assertFalse(outbox.captureNext());
  }

  @Test
  void unavailable_task_is_a_terminal_no_append_outcome() {
    when(repository.pending()).thenReturn(Optional.of(operation));
    when(repository.lockTask(operation)).thenReturn(MessageRelayRepository.TaskFence.UNAVAILABLE);
    assertTrue(outbox.publishNext());
    verify(repository).unavailable(operation, "TASK_UNAVAILABLE");
    verifyNoInteractions(access, relay);
    verify(repository, never()).defer(any());
  }
}
