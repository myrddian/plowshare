package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.faults.CallerFault;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventPayloadCodecTest {
  private static final Instant AT = Instant.parse("2026-10-05T00:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"text\":\"An observation\\nwith quotes: \\\"hello\\\"\"}"})
  void historical_manual_dtos_round_trip_without_field_loss(String wire) throws Exception {
    var payload = EventPayloadCodec.stored("manual", null, null, null, wire);
    assertEquals(JSON.readTree(wire), JSON.readTree(EventPayloadCodec.write(payload)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"reason\":\"opened\"}",
        "{\"reason\":\"request\",\"message\":\"msg_1\",\"by\":\"person\",\"maxTurns\":24}",
        "{\"direct_message\":\"msg_1\"}",
        "{\"direct_message\":\"msg_1\",\"message_continuation\":\"approval\",\"message_approval\":\"approval_1\",\"utterance\":\"Approved\"}",
        "{\"direct_message\":\"msg_1\",\"message_continuation\":\"delegate_result\",\"utterance\":\"Retained findings\"}"
      })
  void historical_wakes_round_trip_including_empty_defaults_and_continuations(String wire)
      throws Exception {
    var payload = EventPayloadCodec.stored("board.wake", "topic", null, null, wire);
    assertEquals(JSON.readTree(wire), JSON.readTree(EventPayloadCodec.write(payload)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "broken",
        "null",
        "[]",
        "{\"reason\":7}",
        "{\"reason\":\"bogus\"}",
        "{\"maxTurns\":0}",
        "{\"maxTurns\":1.5}",
        "{\"maxTurns\":\"3\"}",
        "{\"direct_message\":\"msg_1\",\"message_continuation\":\"approval\",\"utterance\":\"Approved\"}",
        "{\"direct_message\":\"msg_1\",\"utterance\":\"Unrequested\"}",
        "{\"direct_message\":true}",
        "{\"reason\":\"opened\",\"grant\":\"admin\"}",
        "{\"reason\":\"opened\",\"reason\":\"request\"}",
        "{} {}"
      })
  void malformed_persisted_wakes_are_refused_before_dispatch(String wire) {
    assertThrows(
        IllegalArgumentException.class,
        () -> EventPayloadCodec.stored("board.wake", "topic", null, null, wire));
  }

  @Test
  void scheduled_identity_dates_and_manual_families_are_strict() {
    var payload = new EventPayload.Scheduled("tick", AT);
    assertEquals(
        payload,
        EventPayloadCodec.stored("daily", null, "tick", AT, EventPayloadCodec.write(payload)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EventPayloadCodec.stored("daily", null, "other", AT, EventPayloadCodec.write(payload)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            EventPayloadCodec.stored(
                "daily", null, "tick", AT.plusSeconds(1), EventPayloadCodec.write(payload)));
    assertThrows(
        IllegalArgumentException.class,
        () -> EventPayloadCodec.stored("manual", null, null, null, "{\"arbitrary\":1}"));
    assertThrows(CallerFault.class, () -> EventPayloadCodec.manual(Map.of("text", 7)));
    assertThrows(
        CallerFault.class,
        () -> EventPayloadCodec.manual(Map.of("text", "", "privilege", "admin")));
    assertThrows(
        CallerFault.class, () -> EventPayloadCodec.manual(Map.of("direct_message", "msg_1")));
  }

  @Test
  void malformed_historical_dates_have_fixed_diagnostics_without_payload_causes() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                EventPayloadCodec.stored(
                    "daily",
                    null,
                    "tick",
                    AT,
                    "{\"schedule\":\"tick\",\"fire_at\":\"private-payload-excerpt\"}"));
    assertEquals("unsupported or invalid persisted event DTO", error.getMessage());
    assertNull(error.getCause());
  }

  @Test
  void invalid_input_has_no_repository_or_dispatch_effect() {
    var triggers = mock(TriggerStore.class);
    var firings = mock(FiringStore.class);
    var dispatcher = mock(Dispatcher.class);
    var intake = new Intake(triggers, firings, dispatcher, () -> AT);
    assertThrows(CallerFault.class, () -> intake.emit("event\nforged", new EventPayload.Empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> intake.emit("manual", new EventPayload.Scheduled("tick", AT)));
    verifyNoInteractions(triggers, firings, dispatcher);
  }

  @Test
  void failed_compatibility_inventory_is_an_explicit_cutover_gate() {
    var report =
        new EventCompatibility(
            2,
            1,
            List.of(
                new EventCompatibility.Problem(
                    "fir_1", EventCompatibility.Code.INVALID_EVENT_DTO)));
    var failure = assertThrows(IllegalStateException.class, report::requireCompatible);
    assertTrue(failure.getMessage().contains("fir_1"));
    assertDoesNotThrow(new EventCompatibility(2, 0, List.of())::requireCompatible);
  }
}
