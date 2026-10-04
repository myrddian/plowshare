package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class GetDateToolTest {

  private static final Instant NOW = Instant.parse("2026-09-30T14:30:00Z");
  private static final Home HOME = Home.global();

  private final GetDateTool tool = new GetDateTool(() -> NOW, ZoneId.of("UTC"));

  @Test
  void no_arguments_use_the_server_zone_and_report_the_same_instant_two_ways() {
    String answer = tool.run("", HOME);

    assertTrue(answer.contains("Time zone: UTC"), answer);
    assertTrue(answer.contains("Local: 2026-09-30T14:30:00Z"), answer);
    assertTrue(answer.contains("UTC: 2026-09-30T14:30:00Z"), answer);
    assertTrue(answer.contains("Unix epoch seconds:"), answer);
  }

  @Test
  void an_iana_zone_answers_with_its_local_date() {
    String answer = tool.run("{\"time_zone\":\"Australia/Melbourne\"}", HOME);

    assertTrue(answer.contains("Time zone: Australia/Melbourne"), answer);
    assertTrue(answer.contains("Local: 2026-10-01T00:30:00+10:00"), answer);
    assertTrue(answer.contains("UTC: 2026-09-30T14:30:00Z"), answer);
  }

  @Test
  void an_unknown_zone_is_a_correctable_tool_result() {
    String answer = tool.run("{\"time_zone\":\"Mars/Olympus\"}", HOME);

    assertTrue(answer.contains("not a recognised IANA time zone"), answer);
    assertTrue(answer.contains("Mars/Olympus"), answer);
  }

  @Test
  void the_schema_does_not_pretend_a_time_zone_is_required() {
    assertTrue(tool.schema().parameters().toString().contains("time_zone"));
    assertFalse(tool.schema().parameters().toString().contains("required=[time_zone]"));
  }
}
