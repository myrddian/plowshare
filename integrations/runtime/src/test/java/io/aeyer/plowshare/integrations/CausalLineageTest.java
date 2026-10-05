package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CausalLineageTest {
  @TempDir Path directory;
  FeedbackTest fixture;

  @BeforeEach
  void setup() {
    fixture = new FeedbackTest();
    fixture.directory = directory;
  }

  Configuration.Binding binding(int depth) {
    var b = fixture.binding("house", true, "pinned", 0);
    return new Configuration.Binding(
        b.name(),
        b.adapter(),
        b.project(),
        b.peer(),
        b.configuration(),
        b.routes(),
        b.allowBindings(),
        b.script(),
        b.fingerprint(),
        b.queue(),
        new Configuration.FeedbackPolicy(true, 30, depth));
  }

  ObjectNode event(String id, String parent) {
    ObjectNode context = Json.object();
    if (id != null) context.put("id", id);
    if (parent != null) context.put("parent_id", parent);
    return Json.object().set("context", context);
  }

  JsonNode capture(Journal journal, Configuration.Binding b, String id, ObjectNode event)
      throws Exception {
    journal.write(id, Json.object().put("status", "QUEUED").set("event", event));
    ObjectNode candidate = (ObjectNode) journal.record(id);
    candidate.set("context", Json.object());
    candidate.put("evaluatedAt", fixture.now.get().getEpochSecond());
    return journal.capture(id, candidate, false, b, event);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3})
  void runtime_keeps_observations_and_stops_suppressing_beyond_the_configured_depth(int depth)
      throws Exception {
    var b = binding(depth);
    var adapter = new FeedbackTest.Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, fixture.now::get);
        var runtime = fixture.runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed("seed");
      runtime.tick();
      String parent = "action-context";
      for (int step = 1; step <= depth + 1; step++) {
        adapter.reading("low-" + step, "20", null);
        runtime.tick();
        String child = "context-" + step;
        adapter.reading("high-" + step, "30", child, parent);
        runtime.tick();
        assertEquals(step > depth ? 1 : 0, gateway.starts);
        parent = child;
      }
      assertEquals(depth, journal.state("house").path("script").path("verified").asInt());
      assertEquals(2 * (depth + 1), journal.state("house").path("script").path("observed").asInt());
      assertEquals(depth == 1 ? 1 : depth + 1, journal.causeCount());
    }
  }

  @Test
  void links_survive_compaction_and_restart_without_extending_root_expiry() throws Exception {
    var b = binding(3);
    long originalExpiry = fixture.now.get().getEpochSecond() + 30;
    try (var journal = new Journal(directory, fixture.now::get)) {
      fixture.remember(journal, b, "action", "root");
      var child = capture(journal, b, "child", event("child", "root"));
      assertEquals(1, child.path("causality").path("depth").asInt());
      assertEquals("root", child.path("causality").path("rootContext").asText());
      assertEquals("action", child.path("causality").path("operation").asText());
      ObjectNode done = (ObjectNode) child;
      done.put("status", "DONE");
      journal.write("child", done);
      journal.maintain(fixture.config(b).retention());
      assertTrue(journal.records().isEmpty());
    }
    fixture.now.set(fixture.now.get().plusSeconds(20));
    try (var journal = new Journal(directory, fixture.now::get)) {
      var grandchild = capture(journal, b, "grandchild", event("grandchild", "child"));
      assertEquals(2, grandchild.path("causality").path("depth").asInt());
      assertEquals(originalExpiry, grandchild.path("causality").path("expiresAt").asLong());
      // Remembered IDs also match when a subsequent observation omits its parent.
      assertEquals(
          2,
          journal
              .cause(b, event("grandchild", null), fixture.now.get().getEpochSecond())
              .path("depth")
              .asInt());
      fixture.now.set(fixture.now.get().plusSeconds(11));
      assertTrue(
          journal
              .cause(b, event("leaf", "grandchild"), fixture.now.get().getEpochSecond())
              .isMissingNode());
      // Already captured classifications stay frozen, even after all links expire.
      journal.expireCauses(fixture.now.get().getEpochSecond());
      assertEquals(
          grandchild,
          journal.capture(
              "grandchild", (ObjectNode) grandchild, false, b, event("leaf", "grandchild")));
      assertEquals(0, journal.causeCount());
    }
  }

  @Test
  void missing_links_invalid_ids_and_unrelated_roots_do_not_infer_ancestry() throws Exception {
    var b = binding(3);
    try (var journal = new Journal(directory, fixture.now::get)) {
      fixture.remember(journal, b, "action", "root");
      assertTrue(
          capture(journal, b, "missing", event("leaf", "unobserved")).path("causality").isNull());
      assertFalse(capture(journal, b, "no-id", event(null, "root")).path("causality").isNull());
      assertFalse(
          capture(journal, b, "invalid-id", event("x".repeat(129), "root"))
              .path("causality")
              .isNull());
      assertEquals(1, journal.causeCount());
      assertTrue(
          journal
              .cause(b, event("leaf", null), fixture.now.get().getEpochSecond())
              .isMissingNode());
      assertTrue(
          journal
              .cause(
                  fixture.binding("other", true, "pinned", 0),
                  event("leaf", "root"),
                  fixture.now.get().getEpochSecond())
              .isMissingNode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ack", "parent", "cycle", "cycle-descendant", "roots"})
  void ambiguous_ancestors_disable_already_learned_descendants(String collision) throws Exception {
    var b = binding(4);
    try (var journal = new Journal(directory, fixture.now::get)) {
      fixture.remember(journal, b, "action", "root");
      capture(journal, b, "child", event("child", "root"));
      capture(journal, b, "grandchild", event("grandchild", "child"));
      switch (collision) {
        case "ack" -> fixture.remember(journal, b, "another-action", "root");
        case "parent" ->
            assertTrue(
                capture(journal, b, "conflict", event("child", "unrelated"))
                    .path("causality")
                    .isNull());
        case "cycle" ->
            assertTrue(
                capture(journal, b, "cycle", event("child", "child")).path("causality").isNull());
        case "cycle-descendant" ->
            assertTrue(
                capture(journal, b, "cycle", event("root", "grandchild"))
                    .path("causality")
                    .isNull());
        case "roots" -> {
          fixture.remember(journal, b, "another-action", "other-root");
          assertTrue(
              capture(journal, b, "conflict", event("root", "other-root"))
                  .path("causality")
                  .isNull());
        }
      }
      assertTrue(
          journal
              .cause(b, event("grandchild", "child"), fixture.now.get().getEpochSecond())
              .isMissingNode());
      assertTrue(
          journal
              .cause(b, event("great-grandchild", "grandchild"), fixture.now.get().getEpochSecond())
              .isMissingNode());
    }
    try (var journal = new Journal(directory, fixture.now::get)) {
      assertTrue(
          journal
              .cause(b, event("grandchild", null), fixture.now.get().getEpochSecond())
              .isMissingNode());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capacity_and_size_failure_leave_both_observation_and_index_unchanged(boolean global)
      throws Exception {
    var b = binding(3);
    try (var journal = new Journal(directory, fixture.now::get)) {
      for (int i = 0; i < (global ? 1 : Feedback.MAX_PER_BINDING); i++)
        fixture.remember(journal, b, "action-" + i, "root-" + i);
      if (global)
        for (int other = 0; other < 4; other++)
          for (int i = 0; i < (other == 3 ? 63 : 64); i++)
            fixture.remember(
                journal,
                fixture.binding("other-" + other, true, "pinned", 0),
                "other-action-" + other + "-" + i,
                "context-" + i);
      assertThrows(
          IOException.class, () -> capture(journal, b, "queued", event("child", "root-0")));
      assertFalse(journal.record("queued").has("context"));
      assertEquals("QUEUED", journal.record("queued").path("status").asText());
      assertEquals(global ? Feedback.MAX_TOTAL : Feedback.MAX_PER_BINDING, journal.causeCount());
      fixture.now.set(fixture.now.get().plusSeconds(31));
      journal.expireCauses(fixture.now.get().getEpochSecond());
      fixture.remember(journal, b, "new-action", "new-root");
      ObjectNode candidate = (ObjectNode) journal.record("queued");
      candidate.set("context", Json.object().put("large", "x".repeat(Journal.MAX_BYTES)));
      candidate.put("evaluatedAt", fixture.now.get().getEpochSecond());
      ObjectNode oldEvent = (ObjectNode) candidate.path("event");
      // Test size rollback using a fresh matching input.
      oldEvent.set("context", Json.object().put("id", "child").put("parent_id", "new-root"));
      journal.write("queued", Json.object().put("status", "QUEUED").set("event", oldEvent));
      assertThrows(
          IOException.class, () -> journal.capture("queued", candidate, false, b, oldEvent));
      assertEquals(1, journal.causeCount());
      assertFalse(journal.record("queued").has("context"));
    }
  }

  @Test
  void the_maximum_depth_is_bounded_and_each_retained_parent_is_required() throws Exception {
    var b = binding(Feedback.MAX_DEPTH);
    try (var journal = new Journal(directory, fixture.now::get)) {
      fixture.remember(journal, b, "action", "root");
      String parent = "root";
      for (int depth = 1; depth <= Feedback.MAX_DEPTH; depth++) {
        String id = "descendant-" + depth;
        var captured = capture(journal, b, id, event(id, parent));
        assertEquals(depth, captured.path("causality").path("depth").asInt());
        parent = id;
      }
      assertTrue(
          journal
              .cause(b, event("too-deep", parent), fixture.now.get().getEpochSecond())
              .isMissingNode());
    }
    // A structurally valid link whose parent is missing cannot be used as proof.
    Path file = directory.resolve("journal.json");
    ObjectNode data = (ObjectNode) Json.MAPPER.readTree(Files.readString(file));
    ((ObjectNode) data.path("causes")).remove(Feedback.key("house", "descendant-8"));
    Files.writeString(file, data.toString());
    try (var journal = new Journal(directory, fixture.now::get)) {
      assertTrue(
          journal
              .cause(b, event("descendant-16", null), fixture.now.get().getEpochSecond())
              .isMissingNode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.5", "17", "-1", "18446744073709551616"})
  void malformed_persisted_depth_refuses_reopen_without_overwriting_state(String depth)
      throws Exception {
    var b = binding(3);
    try (var journal = new Journal(directory, fixture.now::get)) {
      fixture.remember(journal, b, "action", "root");
      capture(journal, b, "child", event("child", "root"));
    }
    Path file = directory.resolve("journal.json");
    ObjectNode data = (ObjectNode) Json.MAPPER.readTree(Files.readString(file));
    ((ObjectNode) data.path("causes").path(Feedback.key("house", "child")))
        .set("depth", Json.parse(depth));
    String corrupt = data.toString();
    Files.writeString(file, corrupt);
    assertThrows(IOException.class, () -> new Journal(directory));
    assertEquals(corrupt, Files.readString(file));
  }
}
