package io.aeyer.plowshare.server.board;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.EventPayloadCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SeatWakeCodecTest {
  private static SeatWake read(String source) {
    return ((EventPayload.Seat) EventPayloadCodec.stored("board.wake", "topic", null, null, source))
        .wake();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "broken",
        "null",
        "[]",
        "{\"reason\":7}",
        "{\"reason\":\"unknown\"}",
        "{\"by\":false}",
        "{\"maxTurns\":0}",
        "{\"maxTurns\":1.5}",
        "{\"maxTurns\":\"24\"}",
        "{\"reason\":\"opened\",\"reason\":\"request\"}",
        "{\"by\":\"agent\\nforged\"}",
        "{\"grant\":\"admin\"}"
      })
  void malformed_wakes_cannot_be_treated_as_an_opened_wake(String input) {
    assertThrows(IllegalArgumentException.class, () -> read(input));
  }

  @Test
  void legacy_empty_wakes_and_retry_receipts_retain_their_defined_semantics() {
    assertEquals(WakeRules.Reason.OPENED, read("{}").reasonKind());
    var retry = new SeatWake("request", "msg_1", "person", 24);
    assertEquals(retry, read(EventPayloadCodec.write(new EventPayload.Seat(retry))));
    assertFalse(
        EventPayloadCodec.write(
                new EventPayload.Seat(new SeatWake("opened", "msg_1", "person", null)))
            .contains("maxTurns"));
  }
}
