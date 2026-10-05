package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionTest {
  @TempDir Path directory;
  final AtomicReference<Instant> time =
      new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
  final Configuration.Retention retention = new Configuration.Retention(10, 60);
  final Configuration.QueuePolicy coalesce = new Configuration.QueuePolicy("same-state", 4);

  ObjectNode event(String state, int second) {
    return Json.object()
        .put("type", "state_changed")
        .put("alias", "temperature")
        .put("state", state)
        .put("availability", "available")
        .put("resync", false)
        .put("epoch", "epoch-one")
        .put("unit", "°C")
        .put("last_updated", time.get().plusSeconds(second).toString())
        .put("observed_at", time.get().plusSeconds(second).toString());
  }

  ObjectNode handler(ObjectNode event) {
    return Json.object()
        .put("kind", "handler")
        .put("handler", "onEvent")
        .put("binding", "house")
        .put("fingerprint", "pinned")
        .put("status", "QUEUED")
        .set("event", event);
  }

  @Test
  void settled_payloads_compact_but_duplicate_identity_and_script_state_survive_restart()
      throws Exception {
    ObjectNode observation = event("30", 0);
    try (Journal journal = new Journal(directory, time::get)) {
      var record = handler(observation);
      record.put("status", "DONE");
      journal.commit(Map.of("original", record), "house", Json.object().put("count", 3));
      time.set(time.get().plusSeconds(9));
      journal.maintain(retention);
      assertFalse(journal.record("original").isMissingNode());
      time.set(time.get().plusSeconds(2));
      journal.maintain(retention);
      assertTrue(journal.record("original").isMissingNode());
      assertTrue(journal.known("original"));
    }
    try (Journal journal = new Journal(directory, time::get)) {
      journal.enqueue("original", handler(observation), coalesce, retention);
      assertTrue(journal.records().isEmpty());
      assertEquals(3, journal.state("house").path("count").asInt());
      assertThrows(
          IOException.class,
          () -> journal.enqueue("original", handler(event("31", 0)), coalesce, retention));
      assertThrows(
          IOException.class,
          () -> journal.write("original", Json.object().put("status", "QUEUED")));
      time.set(time.get().plusSeconds(61));
      journal.maintain(retention);
      assertFalse(journal.known("original"));
    }
  }

  @Test
  void pending_or_unknown_descendants_and_unknown_outgoing_reports_protect_the_whole_lineage()
      throws Exception {
    try (Journal journal = new Journal(directory, time::get)) {
      journal.commit(
          Map.of(
              "root",
              Json.object().put("status", "DONE"),
              "run",
              Json.object().put("status", "DONE").put("invocation", "root"),
              "completion",
              Json.object().put("status", "DONE").put("invocation", "run"),
              "notification",
              Json.object().put("status", "UNKNOWN").put("invocation", "completion"),
              "outgoing",
              Json.object().put("status", "DONE").put("state", "UNKNOWN")),
          null,
          null);
      time.set(time.get().plusSeconds(1000));
      journal.maintain(retention);
      assertEquals(5, journal.records().size());
      assertEquals(0, journal.tombstoneCount());
      assertThrows(IOException.class, () -> journal.prune(Set.of("root")));
      assertThrows(IOException.class, () -> journal.prune(Set.of("outgoing")));
      journal.write(
          "notification", Json.object().put("status", "DONE").put("invocation", "completion"));
      time.set(time.get().plusSeconds(11));
      journal.maintain(retention);
      assertEquals(1, journal.records().size());
      assertEquals(4, journal.tombstoneCount());
    }
  }

  @Test
  void adjacent_equivalent_readings_coalesce_but_changes_and_gaps_are_preserved() throws Exception {
    try (Journal journal = new Journal(directory, time::get)) {
      var original = handler(event("20", 0));
      journal.enqueue("first", original, coalesce, retention);
      journal.enqueue("second", handler(event("20", 1)), coalesce, retention);
      assertEquals(1, journal.records().size());
      assertEquals(1, journal.record("second").path("coalescedCount").asInt());
      journal.enqueue("first", original, coalesce, retention);
      assertEquals(1, journal.records().size());
      journal.enqueue("high", handler(event("30", 2)), coalesce, retention);
      journal.enqueue("low", handler(event("20", 3)), coalesce, retention);
      journal.enqueue(
          "gap",
          handler(Json.object().put("type", "connection.gap").put("epoch", "epoch-one")),
          coalesce,
          retention);
      assertEquals(4, journal.records().size());
      assertThrows(
          IOException.class,
          () -> journal.enqueue("overflow", handler(event("20", 4)), coalesce, retention));
      assertFalse(journal.known("overflow"));
      assertEquals(4, journal.records().size());
    }
  }

  @Test
  void capture_is_atomic_against_coalescing_and_captured_context_cannot_be_superseded()
      throws Exception {
    try (Journal journal = new Journal(directory, time::get)) {
      var original = handler(event("20", 0));
      journal.enqueue("first", original, coalesce, retention);
      var candidate = (ObjectNode) journal.record("first");
      candidate.set("context", Json.object());
      journal.enqueue("second", handler(event("20", 1)), coalesce, retention);
      assertNull(journal.capture("first", candidate));
      candidate = (ObjectNode) journal.record("second");
      candidate.set("context", Json.object().put("captured", true));
      assertNotNull(journal.capture("second", candidate));
      journal.enqueue("third", handler(event("20", 2)), coalesce, retention);
      assertEquals(2, journal.records().size());
      assertTrue(journal.record("second").path("context").path("captured").asBoolean());
    }
  }

  @Test
  void provenance_resync_epoch_attributes_and_other_aliases_are_coalescing_barriers()
      throws Exception {
    List<ObjectNode> barriers =
        List.of(
            event("20", 1).put("epoch", "other"),
            event("20", 1).put("resync", true),
            event("20", 1).put("alias", "occupancy"),
            event("20", 1).set("context", Json.object().put("id", "new-origin")),
            event("20", 1).set("attributes", Json.object().put("power", 5)),
            event("20", 1).put("availability", "stale"));
    int index = 0;
    for (var barrier : barriers) {
      try (Journal journal = new Journal(directory.resolve("case-" + index++), time::get)) {
        journal.enqueue("original", handler(event("20", 0)), coalesce, retention);
        journal.enqueue("barrier", handler(barrier), coalesce, retention);
        assertEquals(2, journal.records().size());
        assertEquals(0, journal.tombstoneCount());
      }
    }
  }

  @Test
  void retention_disabled_preserves_payloads_and_old_journals_start_a_fresh_age_window()
      throws Exception {
    var old = Json.object().put("version", 1);
    old.set("state", Json.object());
    old.set("records", Json.object().set("legacy", Json.object().put("status", "DONE")));
    Files.writeString(directory.resolve("journal.json"), old.toString());
    try (Journal journal = new Journal(directory, time::get)) {
      journal.maintain(Configuration.Retention.disabled());
      assertFalse(journal.record("legacy").has("settledAt"));
      journal.maintain(retention);
      assertFalse(journal.record("legacy").isMissingNode());
      time.set(time.get().plusSeconds(11));
      journal.maintain(retention);
      assertTrue(journal.known("legacy"));
      assertEquals(1, journal.tombstoneCount());
    }
  }

  @Test
  void tombstone_capacity_failure_rolls_back_coalescing_without_losing_the_pending_event()
      throws Exception {
    var old = Json.object().put("version", 1);
    old.set("state", Json.object());
    old.set("records", Json.object());
    ObjectNode tombstones = Json.object();
    for (int i = 0; i < Journal.MAX_TOMBSTONES; i++)
      tombstones.set("retained-" + i, Json.object().put("expiresAt", Long.MAX_VALUE));
    old.set("tombstones", tombstones);
    Files.writeString(directory.resolve("journal.json"), old.toString());
    try (Journal journal = new Journal(directory, time::get)) {
      journal.enqueue("first", handler(event("20", 0)), coalesce, retention);
      assertThrows(
          IOException.class,
          () -> journal.enqueue("second", handler(event("20", 1)), coalesce, retention));
      assertFalse(journal.record("first").isMissingNode());
      assertFalse(journal.known("second"));
      assertEquals(Journal.MAX_TOMBSTONES, journal.tombstoneCount());
    }
    try (Journal journal = new Journal(directory, time::get)) {
      assertFalse(journal.record("first").isMissingNode());
    }
  }

  @Test
  void thousands_of_settled_records_can_rotate_without_changing_ids_or_exceeding_the_payload_bound()
      throws Exception {
    try (Journal journal = new Journal(directory, time::get)) {
      for (int batch = 0; batch < 5; batch++) {
        Map<String, JsonNode> records = new LinkedHashMap<>();
        for (int i = 0; i < 256; i++)
          records.put("event-" + batch + "-" + i, Json.object().put("status", "DONE"));
        journal.commit(records, null, null);
        time.set(time.get().plusSeconds(11));
        journal.maintain(retention);
        assertTrue(journal.records().isEmpty());
      }
      assertEquals(1280, journal.tombstoneCount());
      assertTrue(journal.known("event-0-0"));
    }
  }
}
