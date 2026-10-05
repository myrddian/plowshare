package io.aeyer.plowshare.server.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.server.events.EventPayload;
import io.aeyer.plowshare.server.events.FiringRecord;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FiringViewTest {
  @Test
  void v1_retains_the_json_string_and_all_existing_firing_fields() throws Exception {
    var at = Instant.parse("2026-10-05T00:00:00Z");
    var firing =
        new FiringRecord(
            "fir_1",
            "manual",
            new EventPayload.Text("Retained observation"),
            null,
            null,
            null,
            null,
            "unmatched",
            null,
            null,
            null,
            at,
            null,
            null,
            null);
    var json = FrameJson.answering();
    var wire = json.valueToTree(FiringView.of(firing));
    var fields = new java.util.HashSet<String>();
    wire.fieldNames().forEachRemaining(fields::add);
    assertEquals(
        Set.of(
            "id",
            "event",
            "data",
            "schedule",
            "fireAt",
            "trigger",
            "target",
            "status",
            "supersededBy",
            "reason",
            "jobId",
            "arrivedAt",
            "startedAt",
            "finishedAt",
            "topic"),
        fields);
    assertTrue(wire.get("data").isTextual());
    assertEquals(
        "Retained observation",
        json.readTree(wire.get("data").textValue()).get("text").textValue());
    assertEquals("unmatched", wire.get("status").textValue());
    assertTrue(wire.get("jobId").isNull());
  }
}
