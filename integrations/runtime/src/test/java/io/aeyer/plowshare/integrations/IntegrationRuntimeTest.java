package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IntegrationRuntimeTest {
  @TempDir Path directory;

  static final class FakeAdapter implements IntegrationAdapter {
    Consumer<Observation> listener;
    int actions, reads;
    JsonNode snapshot = Json.object();

    public void start(Consumer<Observation> listener) {
      this.listener = listener;
    }

    public void validate(
        String operation, io.aeyer.plowshare.protocol.IntegrationPayload.Arguments arguments) {
      if (operation.equals("actions.execute") && !arguments.action().equals("notify"))
        throw new IllegalArgumentException("denied");
    }

    public Result execute(
        String operation, io.aeyer.plowshare.protocol.IntegrationPayload.Arguments arguments) {
      if (operation.equals("actions.execute")) actions++;
      if (operation.equals("states.read")) reads++;
      return IntegrationFixtures.result("COMPLETED", Json.object().put("acknowledged", true));
    }

    public Map<String, io.aeyer.plowshare.protocol.IntegrationPayload.Reading> snapshot() {
      return IntegrationFixtures.readings(snapshot);
    }

    public void close() {}

    void emit(String id, String state, boolean resync) {
      listener.accept(
          new Observation(
              id,
              IntegrationFixtures.event(
                  Json.object()
                      .put("alias", "temperature")
                      .put("state", state)
                      .put("unit", "°C")
                      .put("availability", "available")
                      .put("resync", resync))));
    }
  }

  static class FakeGateway implements Gateway {
    int starts;
    boolean loseStart;
    final Map<UUID, io.aeyer.plowshare.protocol.Orchestration.Started> receipts = new HashMap<>();

    public void advertise(String p, List<String> peers) {}

    public Outgoing.Claimed claim(String p, List<String> peers) {
      return new Outgoing.Claimed(null, null);
    }

    public void report(Outgoing.Report report) throws IOException {}

    public Outgoing.Work outgoing(UUID id) throws IOException {
      throw new IOException("not found");
    }

    public io.aeyer.plowshare.protocol.Orchestration.Started start(
        io.aeyer.plowshare.protocol.Orchestration.Start payload) throws IOException {
      starts++;
      UUID id = payload.requestId();
      var receipt =
          new io.aeyer.plowshare.protocol.Orchestration.Started("run-fixture", "running", id);
      receipts.put(id, receipt);
      if (loseStart) {
        loseStart = false;
        throw new IOException("response lost");
      }
      return receipt;
    }

    public Optional<io.aeyer.plowshare.protocol.Orchestration.Started> receipt(UUID id) {
      return Optional.ofNullable(receipts.get(id));
    }

    public io.aeyer.plowshare.protocol.Orchestration.Status status(String run) {
      return IntegrationCodec.decode(
          Json.object()
              .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                  "orchestration",
                  Json.object()
                      .put("id", run)
                      .put("definition", "investigate")
                      .put("tier", "project")
                      .put("project", "home")
                      .put("state", "finished")
                      .put("returnsUsed", 0)
                      .put("maxReturns", 2)
                      .put("nudges", 0)
                      .put("restarts", 0)
                      .put("depth", 0)
                      .put("conductorConversation", "cnv_fixture")
                      .put("createdAt", "2026-10-04T00:00:00Z")
                      .put("result", "Office is hot"))
              .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                  "todos", Json.MAPPER.createArrayNode())
              .<com.fasterxml.jackson.databind.node.ObjectNode>set(
                  "messages", Json.MAPPER.createArrayNode())
              .set("children", Json.MAPPER.createArrayNode()),
          io.aeyer.plowshare.protocol.Orchestration.Status.class);
    }

    public void close() {}
  }

  Configuration config(String script, Set<String> allowed) {
    var route =
        new Configuration.Route(
            "heat",
            "temperature",
            28.0,
            "°C",
            900,
            "investigate",
            "coordinator",
            "Explain the readings",
            "notify");
    var b =
        new Configuration.Binding(
            "house",
            "fake",
            "home",
            "ha-house",
            new IntegrationFixtures.Settings(),
            Map.of("heat", route),
            allowed,
            script,
            "pinned");
    return new Configuration(
        "http://localhost:8091", "PLOWSHARE_TOKEN", directory, Map.of("house", b));
  }

  IntegrationRuntime runtime(
      Configuration c, FakeAdapter adapter, FakeGateway gateway, Journal journal) {
    return new IntegrationRuntime(
        c,
        Map.of("house", adapter),
        gateway,
        journal,
        new ScriptHost(Duration.ofSeconds(5)),
        () -> Instant.parse("2026-10-04T00:00:00Z"));
  }

  @Test
  void queued_readings_preserve_crossings_and_retention_preserves_cooldown_and_deduplication()
      throws Exception {
    var now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    var base = config(null, Set.of("house"));
    var b = base.bindings().get("house");
    var configured =
        new Configuration(
            base.plowshare(),
            base.tokenEnv(),
            directory,
            Map.of(
                "house",
                new Configuration.Binding(
                    b.name(),
                    b.adapter(),
                    b.project(),
                    b.peer(),
                    b.configuration(),
                    b.routes(),
                    b.allowBindings(),
                    b.script(),
                    b.fingerprint(),
                    new Configuration.QueuePolicy("same-state", 4))),
            new Configuration.Retention(10, 60));
    FakeGateway gateway = new FakeGateway();
    FakeAdapter adapter = new FakeAdapter();
    try (Journal journal = new Journal(directory, now::get);
        var runtime =
            new IntegrationRuntime(
                configured,
                Map.of("house", adapter),
                gateway,
                journal,
                new ScriptHost(Duration.ofSeconds(5)),
                now::get)) {
      runtime.start();
      Consumer<String[]> reading =
          values ->
              adapter.listener.accept(
                  new IntegrationAdapter.Observation(
                      values[0],
                      IntegrationFixtures.event(
                          Json.object()
                              .put("type", "state_changed")
                              .put("alias", "temperature")
                              .put("state", values[1])
                              .put("unit", "°C")
                              .put("availability", "available")
                              .put("epoch", "one")
                              .put("resync", false)
                              .put("observed_at", "2026-10-04T00:00:00Z"))));
      reading.accept(new String[] {"low-one", "20"});
      reading.accept(new String[] {"low-two", "20"});
      reading.accept(new String[] {"high", "30"});
      reading.accept(new String[] {"low-three", "20"});
      assertEquals(3, journal.records().size());
      assertEquals(1, journal.tombstoneCount());
      runtime.tick();
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, adapter.actions);
      assertTrue(
          journal.records().stream()
              .anyMatch(
                  e ->
                      e.getValue().path("context").path("event").path("coalescedCount").asInt() == 1
                          || e.getValue().path("coalescedCount").asInt() == 1));
      now.set(now.get().plusSeconds(11));
      runtime.tick();
      assertTrue(journal.records().isEmpty());
      reading.accept(new String[] {"low-one", "20"});
      reading.accept(new String[] {"high", "30"});
      runtime.tick();
      assertTrue(journal.records().isEmpty());
      reading.accept(new String[] {"high-again", "30"});
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, adapter.actions);
    }
  }

  @Test
  void lost_start_response_recovers_one_run_and_completion_once_after_restart() throws Exception {
    FakeGateway gateway = new FakeGateway();
    gateway.loseStart = true;
    FakeAdapter adapter = new FakeAdapter();
    var c = config(null, Set.of("house"));
    try (Journal journal = new Journal(directory);
        var runtime = runtime(c, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("crossing", "30", false);
      assertThrows(IOException.class, runtime::tick);
      assertEquals(1, gateway.starts);
    }
    FakeAdapter replacement = new FakeAdapter();
    try (Journal journal = new Journal(directory);
        var runtime = runtime(c, replacement, gateway, journal)) {
      runtime.start();
      runtime.tick();
      runtime.tick();
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, replacement.actions);
      replacement.emit("crossing", "30", false);
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, replacement.actions);
    }
  }

  @Test
  void resync_edges_and_cooldown_do_not_start_duplicate_investigations() throws Exception {
    FakeGateway gateway = new FakeGateway();
    FakeAdapter adapter = new FakeAdapter();
    try (Journal journal = new Journal(directory);
        var runtime = runtime(config(null, Set.of("house")), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("snapshot", "30", true);
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.emit("low", "20", false);
      runtime.tick();
      adapter.emit("high", "30", false);
      runtime.tick();
      assertEquals(1, gateway.starts);
      adapter.emit("repeat", "31", false);
      runtime.tick();
      adapter.emit("low-again", "20", false);
      runtime.tick();
      adapter.emit("high-again", "30", false);
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void invalid_cross_binding_effect_does_not_commit_script_state_or_start_work() throws Exception {
    String script =
        "export default {onEvent(e,c){c.state.count=1;return"
            + " [c.executeAction('other','notify',{message:'x'},{key:'send'})]}};";
    FakeGateway gateway = new FakeGateway();
    FakeAdapter adapter = new FakeAdapter();
    try (Journal journal = new Journal(directory);
        var runtime = runtime(config(script, Set.of("house")), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("event", "30", false);
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals(0, gateway.starts);
      assertTrue(journal.state("house").isEmpty());
      assertEquals("FAILED", journal.records().getFirst().getValue().path("status").asText());
    }
  }

  @Test
  void committed_action_interrupted_before_report_is_never_replayed() throws Exception {
    FakeGateway gateway = new FakeGateway();
    FakeAdapter adapter = new FakeAdapter();
    try (Journal journal = new Journal(directory);
        var runtime = runtime(config(null, Set.of("house")), adapter, gateway, journal)) {
      var effect =
          Json.object()
              .put("kind", "effect")
              .put("binding", "house")
              .put("fingerprint", "pinned")
              .put("status", "DISPATCHED");
      effect.set(
          "effect",
          Json.object()
              .put("kind", "action")
              .put("binding", "house")
              .put("action", "notify")
              .put("key", "send")
              .set("parameters", Json.object().put("message", "x")));
      journal.write("effect:interrupted", effect);
      runtime.start();
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals("UNKNOWN", journal.record("effect:interrupted").path("status").asText());
    }
  }

  @Test
  void duplicate_effect_keys_refuse_entire_handler_before_any_action() throws Exception {
    String script =
        "export default {onEvent(e,c){return"
            + " [c.executeAction('house','notify',{message:'x'},{key:'same'}),c.executeAction('house','notify',{message:'y'},{key:'same'})]}};";
    FakeAdapter adapter = new FakeAdapter();
    FakeGateway gateway = new FakeGateway();
    try (Journal journal = new Journal(directory);
        var runtime = runtime(config(script, Set.of("house")), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("event", "30", false);
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals(1, journal.records().size());
    }
  }
}
