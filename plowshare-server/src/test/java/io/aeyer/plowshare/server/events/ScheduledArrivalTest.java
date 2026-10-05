package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScheduledArrivalTest {
  private static final Instant AT = Instant.parse("2026-10-05T01:00:00Z");

  private static FiringRecord firing(String id, TriggerRecord trigger) {
    return new FiringRecord(
        id,
        "daily",
        new EventPayload.Scheduled("morning", AT),
        "morning",
        AT,
        trigger == null ? null : trigger.name(),
        trigger == null ? null : trigger.target(),
        trigger == null ? "unmatched" : "queued",
        null,
        null,
        null,
        AT,
        null,
        null,
        null);
  }

  @Test
  void queued_recovery_hints_are_independent_and_never_republish() {
    var triggers = mock(TriggerStore.class);
    var firings = mock(FiringStore.class);
    var dispatcher = mock(Dispatcher.class);
    var intake = new Intake(triggers, firings, dispatcher, () -> AT);
    when(firings.scheduledTargetsWaiting()).thenReturn(List.of("trigger:a", "trigger:b"));
    doThrow(new IllegalStateException("unavailable")).when(dispatcher).drain("trigger:a");
    assertDoesNotThrow(intake::retryWaiting);
    verify(dispatcher).drain("trigger:b");
    verifyNoInteractions(triggers);
    verify(firings, never()).arrive(anyString(), any(), any(), any(), any(), any());
  }

  @Test
  void all_fanout_and_queue_policy_are_recorded_before_any_dispatch_and_hints_are_independent() {
    var triggers = mock(TriggerStore.class);
    var firings = mock(FiringStore.class);
    var dispatcher = mock(Dispatcher.class);
    var intake = new Intake(triggers, firings, dispatcher, () -> AT);
    var a = new TriggerRecord("a", "daily", null, null, "bot", "task", 1, 1, 2, false, "operator");
    var b = new TriggerRecord("b", "daily", null, null, "bot", "task", 1, 1, 3, false, "operator");
    var fa = firing("firing-a", a);
    var fb = firing("firing-b", b);
    when(triggers.listening("daily")).thenReturn(List.of(a, b));
    when(firings.arrive("daily", fa.data(), "morning", AT, a, AT)).thenReturn(Optional.of(fa));
    when(firings.arrive("daily", fb.data(), "morning", AT, b, AT)).thenReturn(Optional.of(fb));
    var recorded = intake.record("daily", "morning", AT, AT);
    assertEquals(
        List.of(new ScheduledArrival.Arrival(fa, a), new ScheduledArrival.Arrival(fb, b)),
        recorded);
    verifyNoInteractions(dispatcher);
    var order = inOrder(firings);
    order.verify(firings).lockScheduledQueues(List.of("a", "b"));
    order.verify(firings).arrive("daily", fa.data(), "morning", AT, a, AT);
    order.verify(firings).arrive("daily", fb.data(), "morning", AT, b, AT);
    order.verify(firings).supersedeBeyond("a", 2, fa.id());
    order.verify(firings).supersedeBeyond("b", 3, fb.id());
    doThrow(new IllegalStateException("hint unavailable")).when(dispatcher).dispatch(fa, a);
    assertDoesNotThrow(() -> intake.dispatch(recorded));
    verify(dispatcher).dispatch(fb, b);
  }

  @Test
  void unmatched_occurrence_is_durable_and_never_starts_a_job() {
    var triggers = mock(TriggerStore.class);
    var firings = mock(FiringStore.class);
    var dispatcher = mock(Dispatcher.class);
    var intake = new Intake(triggers, firings, dispatcher, () -> AT);
    var unmatched = firing("unmatched", null);
    when(firings.arrive("daily", unmatched.data(), "morning", AT, null, AT))
        .thenReturn(Optional.of(unmatched));
    var recorded = intake.record("daily", "morning", AT, AT);
    assertEquals(List.of(new ScheduledArrival.Arrival(unmatched, null)), recorded);
    intake.dispatch(recorded);
    verifyNoInteractions(dispatcher);
    verify(firings, never()).supersedeBeyond(anyString(), anyInt(), anyString());
  }
}
