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

  @Test
  void populated_audit_preserves_required_null_call_and_attempt_fields_without_inventing_prices()
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
    Usage.Resolved query = new Usage.Resolved("usage.calls", filter);
    Usage.Attempt attempt =
        new Usage.Attempt(
            "00000000-0000-0000-0000-000000000002",
            1,
            "SUCCEEDED",
            200,
            "stop",
            "10",
            "2",
            "12",
            null,
            null,
            null,
            "PROVIDER",
            "COMPLETE",
            "UNKNOWN",
            null,
            null,
            null,
            null,
            5L,
            0L,
            List.of("MISSING_PRICE"));
    Usage.Call call =
        new Usage.Call(
            "00000000-0000-0000-0000-000000000001",
            FROM,
            TO,
            "SUCCEEDED",
            "fixture-pool",
            "fixture-model",
            null,
            null,
            null,
            "1",
            "project",
            "cnv_fixture",
            null,
            null,
            "fixture-agent",
            "agent.chat",
            "0",
            null,
            1,
            0L,
            null,
            null,
            null,
            List.of(attempt),
            false);
    JsonNode audit = wire(new Usage.Audit(query, List.of(call), null, health()));
    JsonNode serializedCall = audit.path("calls").get(0);
    for (String field :
        List.of(
            "model_family",
            "billing_route",
            "price_version",
            "run_id",
            "orchestration_id",
            "step_ordinal",
            "admission_accounting_millis",
            "preflight_observation",
            "attempt_cursor")) {
      assertTrue(serializedCall.has(field), field);
      assertTrue(serializedCall.path(field).isNull(), field);
    }
    JsonNode serializedAttempt = serializedCall.path("attempts").get(0);
    for (String field :
        List.of(
            "cache_read_tokens",
            "cache_write_tokens",
            "reasoning_tokens",
            "cost_amount",
            "cost_currency",
            "price_version",
            "first_output_millis")) {
      assertTrue(serializedAttempt.has(field), field);
      assertTrue(serializedAttempt.path(field).isNull(), field);
    }
    assertEquals("UNKNOWN", serializedAttempt.path("cost_kind").textValue());
    assertEquals("MISSING_PRICE", serializedAttempt.path("cost_reasons").get(0).textValue());
    assertEquals(call, JSON.treeToValue(serializedCall, Usage.Call.class));
    assertFalse(audit.path("filters").path("filter").has("project"));
    assertTrue(audit.path("cursor").isNull());

    JsonNode attempts =
        wire(new Usage.AttemptPage(query, call.callId(), List.of(attempt), null, health()));
    assertEquals(serializedAttempt, attempts.path("attempts").get(0));
    Path fixture = Path.of("build", "test-fixtures", "usage-audit-serialization.json");
    Files.createDirectories(fixture.getParent());
    Files.writeString(
        fixture, JSON.writeValueAsString(Map.of("audit", audit, "attempts", attempts)));
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
