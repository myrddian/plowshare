package io.aeyer.plowshare.server.relay;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.RelayPort;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelayPortEventsTest {
  private static Relay.Publication publication(long position, String text) {
    var now = Instant.parse("2026-10-09T00:00:00Z");
    return new Relay.Publication(
        new Relay.TopicKey(1, "large.events"),
        position,
        now,
        new Relay.Draft(
            "event-" + position, "publisher", now, null, null, new RelayPayload.Text(text)));
  }

  @Test
  void legacy_budget_retains_a_small_prefix_and_refuses_an_undeliverable_first_event() {
    var events =
        RelayPortEvents.bounded(
            List.of(publication(1, "x".repeat(500000)), publication(2, "x".repeat(500000))),
            RelayReadBudget.LEGACY);
    assertEquals(1, events.size());
    assertThrows(
        io.aeyer.plowshare.server.faults.CallerFault.class,
        () ->
            RelayPortEvents.bounded(
                List.of(publication(1, "x".repeat(1024 * 1024))), RelayReadBudget.LEGACY));
  }

  @Test
  void worst_case_escaping_fits_and_next_event_is_left_for_a_later_batch() {
    // Repeated default-size values exercise the same 319 MiB prefix boundary without retaining
    // a single 300 MiB escaped string in the test JVM.
    String largest = "\u0001".repeat(RelayPort.DEFAULT_TEXT_BYTES);
    var offered =
        java.util.stream.LongStream.rangeClosed(1, 11)
            .mapToObj(position -> publication(position, largest))
            .toList();
    var events = RelayPortEvents.bounded(offered);
    assertEquals(10, events.size());
    assertEquals("1", events.getFirst().position());
    assertEquals(largest, events.getFirst().payload().text());
    assertEquals("11", RelayPortEvents.bounded(List.of(offered.getLast())).getFirst().position());
  }
}
