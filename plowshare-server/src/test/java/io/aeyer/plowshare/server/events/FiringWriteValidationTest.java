package io.aeyer.plowshare.server.events;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.aeyer.plowshare.server.board.SeatWake;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class FiringWriteValidationTest {
  @Test
  void alternate_callers_cannot_insert_manual_or_unscoped_content_as_a_wake() {
    var jdbc = mock(JdbcTemplate.class);
    var repository = new JdbcFiringStore(jdbc);
    var at = Instant.parse("2026-10-05T00:00:00Z");
    var seat = new EventPayload.Seat(new SeatWake(null, null, null, null));
    assertThrows(
        IllegalArgumentException.class, () -> repository.owe(null, "conversation:cnv_1", seat, at));
    assertThrows(
        IllegalArgumentException.class, () -> repository.owe("topic", "trigger:some", seat, at));
    assertThrows(
        IllegalArgumentException.class, () -> repository.owe("topic", "conversation:", seat, at));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.owe("topic", "conversation:cnv_1", new EventPayload.Text("manual"), at));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.arrive("manual", seat, null, null, null, at));
    verifyNoInteractions(jdbc);
  }
}
