package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.archive.UnitOfWork;
import io.aeyer.plowshare.server.relay.Relay;
import io.aeyer.plowshare.server.relay.RelayPayload;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RelayScheduledPublicationTest {
  private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");
  private final ScheduleStore schedules = mock(ScheduleStore.class);
  private final Relay relay = mock(Relay.class);
  private final ScheduledArrival arrivals = mock(ScheduledArrival.class);
  private final ScheduledPublication.Boundary guard = mock(ScheduledPublication.Boundary.class);
  private final ScheduleRecord selected =
      new ScheduleRecord("morning", "0 0 * * * *", "UTC", "daily", false, AT, "operator");
  private final Relay.TopicKey topic = new Relay.TopicKey(Relay.SystemScope.SERVER, "schedule.due");
  private final Relay.SubscriptionKey subscriber =
      new Relay.SubscriptionKey(topic, "builtin.scheduling");
  private boolean inTransaction;
  private final UnitOfWork transactions =
      new UnitOfWork() {
        public <T> T inTransaction(Supplier<T> work) {
          inTransaction = true;
          try {
            return work.get();
          } finally {
            inTransaction = false;
          }
        }
      };
  private final ScheduledPublication publisher =
      new RelayScheduledPublication(schedules, relay, arrivals, transactions, guard);

  @BeforeEach
  void wire() {
    doAnswer(
            call -> {
              assertFalse(inTransaction);
              return null;
            })
        .when(guard)
        .requireOutsideTransaction();
    when(schedules.claimPublication(selected, AT.plusSeconds(3600)))
        .thenReturn(Optional.of(Relay.SystemScope.SERVER));
    when(relay.subscribe(subscriber, Relay.Start.LATEST))
        .thenReturn(new Relay.Subscription(subscriber, 0, AT));
    when(relay.publish(eq(topic), any()))
        .thenAnswer(
            call -> {
              assertTrue(inTransaction);
              return new Relay.Publication(topic, 1, AT, call.getArgument(1, Relay.Draft.class));
            });
    when(arrivals.record("daily", "morning", AT, AT))
        .thenAnswer(
            call -> {
              assertTrue(inTransaction);
              return List.of();
            });
    doAnswer(
            call -> {
              assertFalse(inTransaction);
              return null;
            })
        .when(arrivals)
        .dispatch(anyList());
  }

  @Test
  void claim_append_firing_admission_and_acknowledgement_commit_before_dispatch() {
    assertTrue(publisher.publish(selected, AT.plusSeconds(3600), AT));
    var order = inOrder(guard, schedules, relay, arrivals);
    order.verify(guard).requireOutsideTransaction();
    order.verify(schedules).claimPublication(selected, AT.plusSeconds(3600));
    order
        .verify(relay)
        .registerTopic(topic, RelayPayload.Kind.SCHEDULE_DUE, Relay.Policy.systemDefault());
    order.verify(relay).subscribe(subscriber, Relay.Start.LATEST);
    var draft = ArgumentCaptor.forClass(Relay.Draft.class);
    order.verify(relay).publish(eq(topic), draft.capture());
    assertEquals("operator", draft.getValue().publisher());
    assertEquals(new RelayPayload.ScheduleDue("morning", "daily", AT), draft.getValue().payload());
    assertTrue(draft.getValue().eventId().matches("schedule:[a-f0-9]{64}"));
    order.verify(arrivals).record("daily", "morning", AT, AT);
    order.verify(relay).advanceSeen(subscriber, 1);
    order.verify(guard).requireOutsideTransaction();
    order.verify(arrivals).dispatch(List.of());
    verify(relay, never()).configureTopic(any(), any(), any());
  }

  @Test
  void losing_claim_does_not_publish_register_or_dispatch() {
    when(schedules.claimPublication(selected, AT.plusSeconds(3600))).thenReturn(Optional.empty());
    assertFalse(publisher.publish(selected, AT.plusSeconds(3600), AT));
    verifyNoInteractions(relay, arrivals);
  }

  @Test
  void failed_durable_admission_or_acknowledgement_never_dispatches() {
    doThrow(new IllegalStateException("storage")).when(arrivals).record("daily", "morning", AT, AT);
    assertThrows(
        IllegalStateException.class, () -> publisher.publish(selected, AT.plusSeconds(3600), AT));
    verify(relay, never()).advanceSeen(any(), anyLong());
    verify(arrivals, never()).dispatch(anyList());
    doReturn(List.of()).when(arrivals).record("daily", "morning", AT, AT);
    when(relay.advanceSeen(subscriber, 1)).thenThrow(new IllegalStateException("cursor"));
    assertThrows(
        IllegalStateException.class, () -> publisher.publish(selected, AT.plusSeconds(3600), AT));
    verify(arrivals, never()).dispatch(anyList());
  }

  @Test
  void an_unadmitted_predecessor_is_not_silently_marked_handled() {
    when(relay.publish(eq(topic), any()))
        .thenAnswer(
            call -> new Relay.Publication(topic, 2, AT, call.getArgument(1, Relay.Draft.class)));
    assertThrows(
        IllegalStateException.class, () -> publisher.publish(selected, AT.plusSeconds(3600), AT));
    verifyNoInteractions(arrivals);
    verify(relay, never()).advanceSeen(any(), anyLong());
  }

  @Test
  void caller_transaction_and_invalid_occurrence_are_refused_before_claim() {
    doThrow(new IllegalStateException("outer transaction")).when(guard).requireOutsideTransaction();
    assertThrows(
        IllegalStateException.class, () -> publisher.publish(selected, AT.plusSeconds(3600), AT));
    assertThrows(IllegalArgumentException.class, () -> publisher.publish(selected, AT, AT));
    verifyNoInteractions(schedules, relay, arrivals);
  }
}
