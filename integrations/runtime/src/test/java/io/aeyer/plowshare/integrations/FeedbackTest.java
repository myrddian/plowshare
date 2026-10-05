package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FeedbackTest {
  @TempDir Path directory;
  final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
  final String script =
      """
export default {onEvent(e,c){
  if(!e.type)return [c.executeAction('house','notify',{message:'fixture'},{key:'act'})];
  if(e.alias!=='temperature')return [];
  c.state.observed=(c.state.observed??0)+1;
  if(e.causality){c.state.verified=(c.state.verified??0)+1;c.state.relation=e.causality.relation;}
  return [c.startPipeline('heat',{evidence:e},{key:'heat'})];
}};
""";

  static class Adapter extends HeldThresholdTest.Adapter {
    public Result execute(
        String operation, io.aeyer.plowshare.protocol.IntegrationPayload.Arguments arguments) {
      super.execute(operation, arguments);
      return IntegrationFixtures.result(
          "COMPLETED",
          Json.object()
              .put("acknowledged", true)
              .set("context", Json.object().put("id", "action-context")));
    }

    void reading(String id, String value, String relation) {
      reading(
          id,
          value,
          "id".equals(relation) ? "action-context" : null,
          "parent_id".equals(relation) ? "action-context" : null);
    }

    void reading(String id, String value, String context, String parent) {
      ObjectNode event =
          Json.object()
              .put("alias", "temperature")
              .put("state", value)
              .put("unit", "°C")
              .put("availability", "available")
              .put("epoch", "one")
              .put("type", "state_changed")
              .put("resync", false);
      ObjectNode lineage = Json.object();
      if (context != null) lineage.put("id", context);
      if (parent != null) lineage.put("parent_id", parent);
      if (!lineage.isEmpty()) event.set("context", lineage);
      readings.set("temperature", event);
      listener.accept(new Observation(id, IntegrationFixtures.event(event)));
    }

    void seed(String id) {
      listener.accept(
          new Observation(id, IntegrationFixtures.event(Json.object().put("type", "seed"))));
    }
  }

  Configuration.Binding binding(String name, boolean enabled, String fingerprint, long hold) {
    var route =
        new Configuration.Route(
            "heat",
            "temperature",
            28.0,
            "°C",
            0,
            "investigate",
            "coordinator",
            "Explain evidence",
            null,
            hold);
    return new Configuration.Binding(
        name,
        "fake",
        "home",
        name + "-peer",
        new IntegrationFixtures.Settings(),
        Map.of("heat", route),
        Set.of(name),
        script,
        fingerprint,
        Configuration.QueuePolicy.defaults(),
        new Configuration.FeedbackPolicy(enabled, 30));
  }

  Configuration config(Configuration.Binding b) {
    return new Configuration(
        "http://localhost:8091",
        "PLOWSHARE_TOKEN",
        directory,
        Map.of("house", b),
        new Configuration.Retention(0, 1));
  }

  IntegrationRuntime runtime(
      Configuration.Binding b, Adapter adapter, Gateway gateway, Journal journal) {
    return new IntegrationRuntime(
        config(b),
        Map.of("house", adapter),
        gateway,
        journal,
        new ScriptHost(Duration.ofSeconds(5)),
        now::get);
  }

  void remember(Journal journal, Configuration.Binding b, String id, String context)
      throws Exception {
    var result =
        IntegrationFixtures.result(
            "COMPLETED",
            Json.object()
                .put("acknowledged", true)
                .set("context", Json.object().put("id", context)));
    journal.completeAction(
        id,
        IntegrationFixtures.actionRecord(b.name(), b.fingerprint(), "DONE")
            .set("result", IntegrationCodec.tree(result.data())),
        b,
        result,
        now.get().getEpochSecond());
  }

  @ParameterizedTest
  @CsvSource({"true,id", "true,parent_id", "false,id", "false,parent_id"})
  void acknowledged_action_echo_keeps_script_evidence_but_suppresses_only_configured_starts(
      boolean enabled, String relation) throws Exception {
    var b = binding("house", enabled, "pinned", 0);
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed("seed");
      runtime.tick();
      assertEquals(1, adapter.actions);
      adapter.reading("high", "30", relation);
      runtime.tick();
      assertEquals(enabled ? 0 : 1, gateway.starts);
      assertEquals(1, journal.state("house").path("script").path("observed").asInt());
      assertEquals(enabled ? 1 : 0, journal.state("house").path("script").path("verified").asInt());
      if (enabled)
        assertEquals(relation, journal.state("house").path("script").path("relation").asText());
      adapter.reading("low", "20", null);
      runtime.tick();
      adapter.reading("unrelated-high", "30", null);
      runtime.tick();
      assertEquals(enabled ? 1 : 2, gateway.starts);
    }
  }

  @Test
  void
      correlation_survives_payload_compaction_and_restart_but_expires_without_extending_the_window()
          throws Exception {
    var b = binding("house", true, "pinned", 0);
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed("seed");
      runtime.tick();
      assertTrue(journal.records().isEmpty());
      assertEquals(1, journal.causeCount());
    }
    now.set(now.get().plusSeconds(2));
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.reading("echo", "30", "parent_id");
      runtime.tick();
      assertEquals(0, gateway.starts);
      now.set(now.get().plusSeconds(29));
      adapter.reading("low", "20", null);
      runtime.tick();
      adapter.reading("expired-echo", "30", "id");
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(0, journal.causeCount());
    }
  }

  @Test
  void binding_configuration_and_ambiguous_contexts_do_not_claim_causality() throws Exception {
    var b = binding("house", true, "pinned", 0);
    JsonNode event = Json.object().set("context", Json.object().put("id", "same-context"));
    try (var journal = new Journal(directory, now::get)) {
      remember(journal, b, "action-one", "same-context");
      assertFalse(journal.cause(b, event, now.get().getEpochSecond()).isMissingNode());
      assertTrue(
          journal
              .cause(binding("other", true, "pinned", 0), event, now.get().getEpochSecond())
              .isMissingNode());
      assertTrue(
          journal
              .cause(binding("house", true, "changed", 0), event, now.get().getEpochSecond())
              .isMissingNode());
      assertTrue(
          journal
              .cause(binding("house", false, "pinned", 0), event, now.get().getEpochSecond())
              .isMissingNode());
      remember(journal, b, "action-two", "same-context");
      assertTrue(journal.cause(b, event, now.get().getEpochSecond()).isMissingNode());
      assertEquals("DONE", journal.record("action-two").path("status").asText());
      assertTrue(journal.record("action-two").has("causalDiagnostic"));
      assertEquals(1, journal.causeCount());
    }
  }

  @Test
  void unknown_or_failed_actions_and_missing_invalid_contexts_create_no_correlation()
      throws Exception {
    var b = binding("house", true, "pinned", 0);
    try (var journal = new Journal(directory, now::get)) {
      for (String state : List.of("UNKNOWN", "FAILED", "REJECTED")) {
        var result =
            IntegrationFixtures.result(
                state,
                Json.object()
                    .put("acknowledged", true)
                    .set("context", Json.object().put("id", "unconfirmed")));
        journal.completeAction(
            state, Json.object().put("status", state), b, result, now.get().getEpochSecond());
      }
      for (JsonNode id :
          List.of(
              Json.MAPPER.nullNode(),
              Json.MAPPER.getNodeFactory().numberNode(12),
              Json.MAPPER.getNodeFactory().textNode("")))
        assertThrows(
            IllegalArgumentException.class,
            () ->
                IntegrationFixtures.result(
                    "COMPLETED",
                    Json.object()
                        .put("acknowledged", true)
                        .set("context", Json.object().set("id", id))));
      var longContext =
          IntegrationFixtures.result(
              "COMPLETED",
              Json.object()
                  .put("acknowledged", true)
                  .set("context", Json.object().put("id", "x".repeat(129))));
      journal.completeAction(
          "long-context",
          IntegrationFixtures.actionRecord("house", "pinned", "DONE"),
          b,
          longContext,
          now.get().getEpochSecond());
      assertEquals(0, journal.causeCount());
    }
  }

  @Test
  void full_context_index_refuses_an_action_before_dispatch_and_expiration_reopens_capacity()
      throws Exception {
    var b = binding("house", true, "pinned", 0);
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      for (int i = 0; i < Feedback.MAX_PER_BINDING; i++)
        remember(journal, b, "action-" + i, "context-" + i);
      runtime.start();
      adapter.seed("full");
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals(Feedback.MAX_PER_BINDING, journal.causeCount());
      now.set(now.get().plusSeconds(31));
      adapter.seed("space");
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals(1, journal.causeCount());
    }
  }

  @Test
  void a_matched_observation_cancels_held_work_and_a_matched_expiry_snapshot_cannot_start_it()
      throws Exception {
    var b = binding("house", true, "pinned", 10);
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed("seed");
      runtime.tick();
      adapter.reading("high", "30", null);
      runtime.tick();
      assertTrue(journal.state("house").path("routes").path("heat").has("hold"));
      adapter.reading("echo", "31", "parent_id");
      runtime.tick();
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
      adapter.reading("low", "20", null);
      adapter.reading("fresh-high", "30", null);
      runtime.tick();
      adapter
          .readings
          .withObject("temperature")
          .set("context", Json.object().put("id", "action-context"));
      now.set(now.get().plusSeconds(10));
      runtime.tick();
      assertEquals(0, gateway.starts);
      assertEquals(2, journal.state("house").path("script").path("verified").asInt());
    }
  }

  @Test
  void lost_outgoing_report_retains_correlation_and_recovery_does_not_repeat_the_action()
      throws Exception {
    var b = binding("house", true, "pinned", 0);
    var adapter = new Adapter();
    UUID id = UUID.randomUUID(), request = UUID.randomUUID();
    var message =
        io.aeyer.plowshare.sdk.ExternalPayloadCodec.message(
            """
{"parts":[{"data":{"schema":"plowshare-integration/1","binding":"house","operation":"actions.execute","arguments":{"action":"notify","parameters":{}}}}]}
""");
    var work =
        new Outgoing.Work(
            id,
            request,
            "house-peer",
            "home",
            null,
            message,
            "DISPATCHED",
            false,
            null,
            null,
            null,
            null,
            1,
            now.get());
    var gateway =
        new IntegrationRuntimeTest.FakeGateway() {
          boolean claimed;
          io.aeyer.plowshare.protocol.ExternalResult saved;

          public Outgoing.Claimed claim(String project, List<String> peers) {
            if (claimed) return new Outgoing.Claimed(null, null);
            claimed = true;
            return new Outgoing.Claimed(work, "send");
          }

          public void report(Outgoing.Report report) throws IOException {
            saved = report.result();
            throw new IOException("response lost after commit");
          }

          public Outgoing.Work outgoing(UUID workId) {
            return new Outgoing.Work(
                id,
                request,
                "house-peer",
                "home",
                null,
                message,
                "COMPLETED",
                false,
                null,
                null,
                saved,
                null,
                2,
                now.get());
          }
        };
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      assertThrows(IOException.class, runtime::tick);
      assertEquals(1, adapter.actions);
      assertEquals(1, journal.causeCount());
      assertEquals("REPORTING", journal.record("outgoing:" + id).path("status").asText());
    }
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      adapter.reading("echo", "30", "id");
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals(0, gateway.starts);
      assertEquals(1, journal.state("house").path("script").path("verified").asInt());
    }
  }

  @Test
  void global_context_bound_protects_other_bindings_without_evicting_unexpired_evidence()
      throws Exception {
    try (var journal = new Journal(directory, now::get)) {
      for (int binding = 0; binding < 4; binding++)
        for (int i = 0; i < Feedback.MAX_PER_BINDING; i++)
          remember(
              journal,
              binding("binding-" + binding, true, "pinned", 0),
              "action-" + binding + "-" + i,
              "context-" + i);
      assertEquals(Feedback.MAX_TOTAL, journal.causeCount());
      assertThrows(
          IllegalArgumentException.class,
          () ->
              journal.checkCauseCapacity(
                  binding("new", true, "pinned", 0), now.get().getEpochSecond()));
      assertFalse(
          journal
              .cause(
                  binding("binding-0", true, "pinned", 0),
                  Json.object().set("context", Json.object().put("id", "context-0")),
                  now.get().getEpochSecond())
              .isMissingNode());
    }
  }

  @Test
  void captured_classification_survives_restart_and_expiry_without_reclassifying_old_evidence()
      throws Exception {
    var b = binding("house", true, "pinned", 0);
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed("seed");
      runtime.tick();
      adapter.reading("captured-echo", "30", "id");
      var entry = journal.records().getFirst();
      ObjectNode record = (ObjectNode) entry.getValue();
      ObjectNode base = (ObjectNode) journal.state("house");
      record.put("evaluatedAt", now.get().getEpochSecond());
      record.set("baseState", base);
      record.set("context", Json.object().set("state", base.path("script")));
      record.set("causality", journal.cause(b, record.path("event"), now.get().getEpochSecond()));
      journal.capture(entry.getKey(), record);
    }
    now.set(now.get().plusSeconds(31));
    try (var journal = new Journal(directory, now::get);
        var runtime = runtime(b, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      assertEquals(0, journal.causeCount());
      assertEquals(0, gateway.starts);
      assertEquals(1, journal.state("house").path("script").path("verified").asInt());
      adapter.reading("fresh-low", "20", null);
      runtime.tick();
      adapter.reading("fresh-expired-high", "30", "id");
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void malformed_causal_index_refuses_reopen_without_overwriting_private_state() throws Exception {
    var b = binding("house", true, "pinned", 0);
    try (var journal = new Journal(directory, now::get)) {
      remember(journal, b, "action", "context");
    }
    Path file = directory.resolve("journal.json");
    ObjectNode data = (ObjectNode) Json.MAPPER.readTree(java.nio.file.Files.readString(file));
    ((ObjectNode) data.path("causes").elements().next()).put("expiresAt", "invalid");
    String corrupt = data.toString();
    java.nio.file.Files.writeString(file, corrupt);
    assertThrows(IOException.class, () -> new Journal(directory));
    assertEquals(corrupt, java.nio.file.Files.readString(file));
  }

  @Test
  void action_result_and_context_index_fail_atomically_at_the_journal_size_limit()
      throws Exception {
    var b = binding("house", true, "pinned", 0);
    try (var journal = new Journal(directory, now::get)) {
      journal.write("action", Json.object().put("status", "DISPATCHED"));
      var result =
          IntegrationFixtures.result(
              "COMPLETED",
              Json.object()
                  .put("acknowledged", true)
                  .set("context", Json.object().put("id", "accepted")));
      assertThrows(
          IOException.class,
          () ->
              journal.completeAction(
                  "action",
                  Json.object().put("status", "DONE").put("large", "x".repeat(Journal.MAX_BYTES)),
                  b,
                  result,
                  now.get().getEpochSecond()));
      assertEquals("DISPATCHED", journal.record("action").path("status").asText());
      assertEquals(0, journal.causeCount());
    }
  }
}
