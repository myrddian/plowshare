package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.Usage;
import io.aeyer.plowshare.protocol.frames.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Exercises the actual frame mapper and its outcome-to-map conversion, without a database. */
class UsageResponseSerializationTest {
  private static final ObjectMapper JSON = FrameJson.answering();
  private static final TypeReference<Map<String, Object>> AS_MAP = new TypeReference<>() {};
  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = FROM.plusSeconds(60);

  @Test
  void minimal_report_and_audit_omit_unselected_filters_and_preserve_nullable_response_fields()
      throws Exception {
    Usage.Filter filter =
        new Usage.Filter(
            "cnv_fixture",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "direct",
            FROM,
            TO,
            List.of(),
            null,
            200);
    JsonNode report = wire(report(new Usage.Resolved("usage.conversation", filter)));
    JsonNode audit =
        wire(new Usage.Audit(new Usage.Resolved("usage.calls", filter), List.of(), null, health()));

    for (JsonNode response : List.of(report, audit)) {
      JsonNode echoed = response.path("filters").path("filter");
      Set<String> fields = new HashSet<>();
      echoed.fieldNames().forEachRemaining(fields::add);
      assertEquals(Set.of("conversation", "scope", "from", "to", "group_by", "limit"), fields);
      assertEquals("cnv_fixture", echoed.path("conversation").textValue());
      assertEquals(FROM.toString(), echoed.path("from").textValue());
      assertEquals(TO.toString(), echoed.path("to").textValue());
      assertTrue(echoed.path("group_by").isEmpty());
      assertEquals(200, echoed.path("limit").intValue());
      assertFalse(echoed.has("project"));
      assertEquals(filter, JSON.treeToValue(echoed, Usage.Filter.class));
      // Response pagination deliberately requires null; omission applies only to Filter.
      assertTrue(response.has("cursor"));
      assertTrue(response.path("cursor").isNull());
    }

    // A build artifact lets the canonical SDK decoder verify these actual Java wire payloads.
    Path fixture = Path.of("build", "test-fixtures", "usage-filter-serialization.json");
    Files.createDirectories(fixture.getParent());
    Files.writeString(
        fixture,
        JSON.writeValueAsString(Map.of("usage.conversation", report, "usage.calls", audit)));
  }

  @Test
  void populated_filter_fields_and_empty_grouping_survive_the_frame_wire_round_trip()
      throws Exception {
    Usage.Filter filter =
        new Usage.Filter(
            "cnv_fixture",
            "fixture-project",
            "fixture-agent",
            "run_fixture",
            "orc_fixture",
            "fixture-model",
            "fixture-pool",
            "fixture-route",
            "subtree",
            FROM,
            TO,
            List.of(),
            "fixture-cursor",
            1);
    JsonNode response = wire(report(new Usage.Resolved("usage.conversation", filter)));
    JsonNode echoed = response.path("filters").path("filter");
    assertEquals(filter, JSON.treeToValue(echoed, Usage.Filter.class));
    assertEquals(14, echoed.size());
    assertEquals("fixture-project", echoed.path("project").textValue());
    assertEquals("fixture-cursor", echoed.path("cursor").textValue());
    assertTrue(echoed.path("group_by").isArray());
    assertTrue(echoed.path("group_by").isEmpty());
  }

  private static JsonNode wire(Object payload) throws Exception {
    // EventChannelHandler performs this conversion before writing its envelope.
    Map<String, Object> outcome = JSON.convertValue(Outcome.ok(payload), AS_MAP);
    return JSON.readTree(JSON.writeValueAsString(outcome)).path("payload");
  }

  private static Usage.Report report(Usage.Resolved query) {
    return new Usage.Report(
        query,
        new Usage.Aggregate(
            null, null, null, null, null, null, null, "0", "0", "0", "0", "0", "0", "0", "0", "0",
            "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", Map.of(), true, true, true,
            null),
        List.of(),
        null,
        health());
  }

  private static Usage.Health health() {
    return new Usage.Health(
        null,
        null,
        "0",
        TO,
        "not_imported",
        true,
        "0",
        "0",
        "1024",
        "NONE",
        "0",
        null,
        0,
        "0",
        false,
        "NONE");
  }
}
