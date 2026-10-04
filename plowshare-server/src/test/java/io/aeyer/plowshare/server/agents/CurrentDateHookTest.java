package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.server.hooks.LogOpen;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class CurrentDateHookTest {

  @Test
  void log_open_captures_one_local_date_and_says_how_to_refresh_it() {
    CurrentDateHook hook =
        new CurrentDateHook(
            () -> Instant.parse("2026-09-30T14:30:00Z"), ZoneId.of("Australia/Melbourne"));

    LogOpen opened = hook.logOpen(null, null);

    assertEquals(1, opened.additions().size());
    String text = opened.additions().getFirst();
    assertTrue(text.contains("Current date: 2026-10-01"), text);
    assertTrue(text.contains("Time zone: Australia/Melbourne"), text);
    assertTrue(text.contains("captured when the log opened"), text);
    assertTrue(text.contains("use get_date"), text);
    assertTrue(opened.records().isEmpty(), "a system fact is not a user hook decision");
  }
}
