package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {
  @TempDir Path directory;

  private Configuration config(String script, Set<String> allowed) {
    var house =
        new Configuration.Binding(
            "house",
            "fake",
            "home",
            "ha-house",
            Json.object(),
            Map.of(),
            allowed,
            script,
            "pinned");
    var other =
        new Configuration.Binding(
            "other",
            "fake",
            "home",
            "other-peer",
            Json.object(),
            Map.of(),
            Set.of("other"),
            null,
            "other-pinned");
    return new Configuration(
        "http://localhost:8091",
        "PLOWSHARE_TOKEN",
        directory,
        Map.of("house", house, "other", other));
  }

  private IntegrationRuntime runtime(
      Configuration config,
      Journal journal,
      IntegrationRuntimeTest.FakeGateway gateway,
      IntegrationRuntimeTest.FakeAdapter house,
      IntegrationRuntimeTest.FakeAdapter other) {
    return new IntegrationRuntime(
        config,
        Map.of("house", house, "other", other),
        gateway,
        journal,
        new ScriptHost(Duration.ofSeconds(5)),
        Instant::now);
  }

  @Test
  void permitted_second_adapter_receives_effect_and_unreachable_snapshots_are_absent()
      throws Exception {
    String source =
        """
                export default {onEvent(e,c){
                  c.state.private = c.readStates('other',['secret']).secret.availability;
                  return [];
                }};
                """;
    var house = new IntegrationRuntimeTest.FakeAdapter();
    var other = new IntegrationRuntimeTest.FakeAdapter();
    other.snapshot = Json.object().set("secret", Json.object().put("state", "private"));
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory);
        var runtime = runtime(config(source, Set.of("house")), journal, gateway, house, other)) {
      runtime.start();
      house.emit("isolated", "30", false);
      runtime.tick();
      assertEquals("missing", journal.state("house").path("script").path("private").asText());
    }
    String permitted =
        """
export default {onEvent(e,c){
  return [c.executeAction('other','notify',{message:'Mapped result'},{key:'deliver'})];
}};
""";
    try (var journal = new Journal(directory);
        var runtime =
            runtime(config(permitted, Set.of("house", "other")), journal, gateway, house, other)) {
      runtime.start();
      house.emit("permitted", "30", false);
      runtime.tick();
      assertEquals(0, house.actions);
      assertEquals(1, other.actions);
    }
  }

  @Test
  void recovery_uses_captured_evidence_and_refuses_stale_state() throws Exception {
    String source =
        """
export default {onEvent(e,c){c.state.value=c.readStates('house',['temperature']).temperature.state;return []}};
""";
    var house = new IntegrationRuntimeTest.FakeAdapter();
    house.snapshot = Json.object().set("temperature", Json.object().put("state", "live-newer"));
    var other = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory);
        var runtime =
            runtime(
                config(source, Set.of("house")),
                journal,
                new IntegrationRuntimeTest.FakeGateway(),
                house,
                other)) {
      var context = Json.object();
      context.set("state", Json.object());
      context.set(
          "states",
          Json.object()
              .set(
                  "house",
                  Json.object().set("temperature", Json.object().put("state", "captured"))));
      context.set("runs", Json.object());
      var handler =
          Json.object()
              .put("kind", "handler")
              .put("binding", "house")
              .put("handler", "onEvent")
              .put("fingerprint", "pinned")
              .put("status", "QUEUED")
              .put("evaluatedAt", 1);
      handler.set("event", Json.object());
      handler.set("baseState", Json.object());
      handler.set("context", context);
      journal.write("captured-handler", handler);
      runtime.start();
      runtime.tick();
      assertEquals("captured", journal.state("house").path("script").path("value").asText());
      journal.write("stale-handler", handler);
      runtime.tick();
      assertEquals("FAILED", journal.record("stale-handler").path("status").asText());
      assertEquals("captured", journal.state("house").path("script").path("value").asText());
    }
  }

  @Test
  void revoked_pending_action_is_refused_but_dispatched_action_remains_unknown() throws Exception {
    var house = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory);
        var runtime =
            runtime(
                config(null, Set.of("house")),
                journal,
                new IntegrationRuntimeTest.FakeGateway(),
                house,
                new IntegrationRuntimeTest.FakeAdapter())) {
      var effect =
          Json.object()
              .put("kind", "effect")
              .put("binding", "house")
              .put("fingerprint", "old-config")
              .put("status", "QUEUED");
      effect.set(
          "effect",
          Json.object()
              .put("kind", "action")
              .put("action", "notify")
              .set("parameters", Json.object().put("message", "old")));
      journal.write("pending", effect);
      effect.put("status", "DISPATCHED");
      journal.write("dispatched", effect);
      runtime.start();
      runtime.tick();
      assertEquals("REJECTED", journal.record("pending").path("status").asText());
      assertEquals("UNKNOWN", journal.record("dispatched").path("status").asText());
      assertEquals(0, house.actions);
    }
  }

  @Test
  void stranded_claim_stops_before_any_new_external_work() throws Exception {
    UUID id = UUID.randomUUID();
    var gateway =
        new IntegrationRuntimeTest.FakeGateway() {
          @Override
          public Outgoing.Work outgoing(UUID request) {
            return new Outgoing.Work(
                id,
                UUID.randomUUID(),
                "ha-house",
                "home",
                null,
                Map.of(),
                "DISPATCHED",
                false,
                null,
                null,
                null,
                null,
                1,
                Instant.now());
          }
        };
    var house = new IntegrationRuntimeTest.FakeAdapter();
    try (var journal = new Journal(directory);
        var runtime =
            runtime(
                config(null, Set.of("house")),
                journal,
                gateway,
                house,
                new IntegrationRuntimeTest.FakeAdapter())) {
      journal.write(
          "outgoing:" + id,
          Json.object()
              .put("kind", "outgoing")
              .put("binding", "house")
              .put("status", "DISPATCHED"));
      runtime.start();
      assertThrows(IOException.class, runtime::tick);
      assertEquals(0, house.actions);
      assertEquals(0, gateway.starts);
      assertEquals("DISPATCHED", journal.record("outgoing:" + id).path("status").asText());
    }
  }

  @Test
  void reported_claim_recovery_accepts_only_matching_durable_terminal_result() throws Exception {
    UUID id = UUID.randomUUID();
    var gateway =
        new IntegrationRuntimeTest.FakeGateway() {
          @Override
          public Outgoing.Work outgoing(UUID request) {
            return new Outgoing.Work(
                id,
                UUID.randomUUID(),
                "ha-house",
                "home",
                null,
                Map.of(),
                "COMPLETED",
                false,
                null,
                null,
                Map.of("acknowledged", true),
                null,
                2,
                Instant.now());
          }
        };
    try (var journal = new Journal(directory);
        var runtime =
            runtime(
                config(null, Set.of("house")),
                journal,
                gateway,
                new IntegrationRuntimeTest.FakeAdapter(),
                new IntegrationRuntimeTest.FakeAdapter())) {
      var record =
          Json.object()
              .put("kind", "outgoing")
              .put("binding", "house")
              .put("status", "REPORTING")
              .put("state", "COMPLETED");
      record.set("result", Json.object().put("acknowledged", true));
      journal.write("outgoing:" + id, record);
      runtime.start();
      runtime.tick();
      assertEquals("DONE", journal.record("outgoing:" + id).path("status").asText());
    }
  }

  @Test
  void fresh_read_and_result_callback_survive_restart_without_repeating_the_read()
      throws Exception {
    String source =
        """
                export default {onEvent(e,c){
                  if(e.type==='read.result'){c.state.results=(c.state.results??0)+1;return []}
                  if(e.alias!=='temperature')return [];
                  return [c.read('house',['temperature'],{key:'fresh'})];
                }};
                """;
    var house = new IntegrationRuntimeTest.FakeAdapter();
    var other = new IntegrationRuntimeTest.FakeAdapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var config = config(source, Set.of("house"));
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, gateway, house, other)) {
      runtime.start();
      house.emit("fresh-read", "30", false);
      runtime.tick();
      assertEquals(1, house.reads);
      assertEquals(
          1,
          journal.records().stream()
              .filter(
                  e ->
                      e.getValue().path("event").path("type").asText().equals("read.result")
                          && e.getValue().path("status").asText().equals("QUEUED"))
              .count());
    }
    try (var journal = new Journal(directory);
        var runtime = runtime(config, journal, gateway, house, other)) {
      runtime.start();
      runtime.tick();
      runtime.tick();
      assertEquals(1, house.reads);
      assertEquals(1, journal.state("house").path("script").path("results").asInt());
    }
  }

  @Test
  void journal_owner_change_and_pruning_parent_of_unknown_effect_are_refused() throws Exception {
    try (var journal = new Journal(directory)) {
      journal.bindOwner("first-owner");
      journal.bindOwner("first-owner");
      assertThrows(IOException.class, () -> journal.bindOwner("different-owner"));
      journal.commit(
          Map.of(
              "handler",
              Json.object().put("status", "DONE"),
              "effect",
              Json.object().put("status", "UNKNOWN").put("invocation", "handler")),
          null,
          null);
      assertThrows(IOException.class, () -> journal.prune(Set.of("handler")));
      assertEquals("DONE", journal.record("handler").path("status").asText());
    }
  }
}
