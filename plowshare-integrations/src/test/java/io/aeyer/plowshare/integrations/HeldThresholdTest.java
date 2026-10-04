package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HeldThresholdTest {
  @TempDir Path directory;
  final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));

  static class Adapter implements IntegrationAdapter {
    Consumer<Observation> listener;
    ObjectNode readings = Json.object();
    int actions;
    Runnable onSnapshot;

    public void start(Consumer<Observation> listener) {
      this.listener = listener;
    }

    public void validate(String operation, JsonNode arguments) {}

    public Result execute(String operation, JsonNode arguments) {
      if (operation.equals("actions.execute")) actions++;
      return new Result("COMPLETED", Json.object().put("acknowledged", true));
    }

    public JsonNode snapshot() {
      if (onSnapshot != null) {
        var once = onSnapshot;
        onSnapshot = null;
        once.run();
      }
      return readings.deepCopy();
    }

    public void close() {}

    void emit(String id, String value, boolean resync, String epoch) {
      var reading =
          Json.object()
              .put("alias", "temperature")
              .put("state", value)
              .put("unit", "°C")
              .put("availability", "available")
              .put("epoch", epoch);
      readings.set("temperature", reading);
      listener.accept(
          new Observation(
              id, reading.deepCopy().put("type", "state_changed").put("resync", resync)));
    }

    void gap() {
      ((ObjectNode) readings.path("temperature")).put("availability", "stale");
      listener.accept(
          new Observation("gap", Json.object().put("type", "connection.gap").put("epoch", "one")));
    }
  }

  Configuration config(String script) {
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
            "notify",
            10);
    var binding =
        new Configuration.Binding(
            "house",
            "fake",
            "home",
            "ha-house",
            Json.object(),
            Map.of("heat", route),
            Set.of("house"),
            script,
            "pinned");
    return new Configuration(
        "http://localhost:8091",
        "PLOWSHARE_TOKEN",
        directory,
        Map.of("house", binding),
        new Configuration.Retention(0, 1));
  }

  IntegrationRuntime runtime(
      Configuration config, Adapter adapter, Gateway gateway, Journal journal) {
    return new IntegrationRuntime(
        config,
        Map.of("house", adapter),
        gateway,
        journal,
        new ScriptHost(Duration.ofSeconds(5)),
        clock::get);
  }

  void advance(long seconds) {
    clock.set(clock.get().plusSeconds(seconds));
  }

  @Test
  void held_crossing_fires_without_another_reading_once_and_preserves_cooldown() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("bootstrap", "20", true, "one");
      runtime.tick();
      adapter.emit("crossing", "30", false, "one");
      runtime.tick();
      assertEquals(0, gateway.starts);
      String edge =
          journal.state("house").path("routes").path("heat").path("hold").path("id").asText();
      assertFalse(journal.record(edge).isMissingNode());
      assertThrows(IOException.class, () -> journal.prune(Set.of(edge)));
      advance(9);
      adapter.emit("still-high", "31", false, "one");
      runtime.tick();
      assertEquals(0, gateway.starts);
      advance(1);
      runtime.tick();
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, adapter.actions);
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
      assertTrue(journal.state("house").path("routes").path("heat").has("started"));
      advance(20);
      adapter.emit("low", "20", false, "one");
      adapter.emit("cooldown-high", "30", false, "one");
      runtime.tick();
      advance(900);
      runtime.tick();
      assertEquals(1, gateway.starts);
      adapter.emit("new-low", "20", false, "one");
      adapter.emit("new-high", "30", false, "one");
      runtime.tick();
      advance(10);
      runtime.tick();
      assertEquals(2, gateway.starts);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"low", "gap", "resync", "unavailable", "unit", "epoch", "stale", "clock"})
  void continuity_barriers_cancel_pending_intervals(String barrier) throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      advance(9);
      runtime.tick();
      switch (barrier) {
        case "low" -> adapter.emit("low", "20", false, "one");
        case "gap" -> adapter.gap();
        case "resync" -> adapter.emit("resync", "30", true, "one");
        case "epoch" -> adapter.emit("new-epoch", "30", false, "two");
        case "clock" -> advance(-1);
        default -> {
          ObjectNode value = (ObjectNode) adapter.readings.path("temperature");
          if (barrier.equals("unit")) value.put("unit", "°F");
          else value.put("availability", barrier.equals("stale") ? "stale" : "unavailable");
        }
      }
      runtime.tick();
      advance(30);
      runtime.tick();
      assertEquals(0, gateway.starts);
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
    }
  }

  @Test
  void bootstrap_high_is_disarmed_and_restart_does_not_count_downtime() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var config = config(null);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("bootstrap", "30", true, "one");
      runtime.tick();
      advance(30);
      adapter.emit("repeat", "31", false, "one");
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.emit("low", "20", false, "one");
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      assertTrue(journal.state("house").path("routes").path("heat").has("hold"));
    }
    advance(100);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      adapter.emit("restart-high", "30", false, "one");
      runtime.tick();
      advance(30);
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.emit("restart-low", "20", false, "one");
      adapter.emit("fresh-high", "30", false, "one");
      runtime.tick();
      advance(10);
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void held_js_callback_sees_current_evidence_and_cannot_bypass_duration() throws Exception {
    String script =
        """
                export default {onEvent(e,c){
                  if(e.type==='threshold.held') {
                    c.state.held=(c.state.held??0)+1;
                    c.state.value=e.state;
                    c.state.route=e.route;
                    c.state.seconds=e.heldSeconds;
                  }
                  return [c.startPipeline('heat',{evidence:e},{key:'heat'})];
                }};
                """;
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(script), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.readings.withObject("temperature").put("state", "32");
      advance(10);
      runtime.tick();
      assertEquals(1, gateway.starts);
      var state = journal.state("house").path("script");
      assertEquals(1, state.path("held").asInt());
      assertEquals("32", state.path("value").asText());
      assertEquals("heat", state.path("route").asText());
      assertEquals(10, state.path("seconds").asInt());
    }
  }

  @Test
  void failed_timer_callback_is_consumed_even_after_duplicate_history_expires() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    String script =
        "export default {onEvent(e,c){if(e.type==='threshold.held')throw"
            + " Error('fail');return []}};";
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(script), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      advance(10);
      runtime.tick();
      assertTrue(journal.records().isEmpty());
      assertTrue(journal.state("house").path("script").isEmpty());
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
      advance(20);
      runtime.tick();
      assertTrue(journal.records().isEmpty());
      assertEquals(0, gateway.starts);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void queued_low_high_during_timer_snapshot_restarts_the_interval(int snapshotNumber)
      throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      advance(10);
      adapter.onSnapshot =
          new Runnable() {
            int remaining = snapshotNumber;

            public void run() {
              if (--remaining > 0) {
                adapter.onSnapshot = this;
                return;
              }
              adapter.emit("late-low", "20", false, "one");
              adapter.emit("late-high", "30", false, "one");
            }
          };
      runtime.tick();
      assertEquals(0, gateway.starts);
      runtime.tick();
      assertEquals(0, gateway.starts);
      advance(10);
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void failed_source_handler_cancels_held_continuity_without_committing_script_state()
      throws Exception {
    String script =
        "export default {onEvent(e,c){if(e.type==='connection.gap'){c.state.bad=true;throw"
            + " Error('fail')}return []}};";
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(script), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      // Even a later snapshot that remains high cannot repair a failed gap handler's
      // interval.
      adapter.listener.accept(
          new IntegrationAdapter.Observation("gap", Json.object().put("type", "connection.gap")));
      runtime.tick();
      advance(20);
      runtime.tick();
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
      assertFalse(journal.state("house").path("script").has("bad"));
      assertEquals(0, gateway.starts);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void old_queued_or_captured_source_observations_cannot_arm_a_new_session(boolean captured)
      throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var config = config(null);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("queued-high", "30", false, "one");
      if (captured) {
        var entry = journal.records().getFirst();
        ObjectNode handler = (ObjectNode) entry.getValue();
        handler.put("evaluatedAt", clock.get().getEpochSecond());
        handler.set("baseState", Json.object());
        handler.set(
            "context", Json.object().set("states", Json.object().set("house", adapter.snapshot())));
        journal.capture(entry.getKey(), handler);
      }
    }
    advance(100);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      advance(20);
      runtime.tick();
      assertEquals(0, gateway.starts);
      assertFalse(journal.state("house").path("routes").path("heat").has("hold"));
    }
  }

  @Test
  void a_queued_timer_from_an_older_session_is_rejected_without_running_js() throws Exception {
    String script =
        "export default {onEvent(e,c){if(e.type==='threshold.held')return"
            + " [c.executeAction('house','notify',{message:'expired'},{key:'notify'})];return"
            + " []}};";
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var config = config(script);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      advance(10);
      adapter.onSnapshot =
          new Runnable() {
            int remaining = 2;

            public void run() {
              if (--remaining > 0) {
                adapter.onSnapshot = this;
                return;
              }
              adapter.listener.accept(
                  new IntegrationAdapter.Observation(
                      "other", Json.object().put("type", "state_changed").put("alias", "other")));
            }
          };
      runtime.tick();
      assertTrue(
          journal.records().stream()
              .anyMatch(
                  e ->
                      e.getValue().has("heldRoute")
                          && e.getValue().path("status").asText().equals("QUEUED")));
    }
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      runtime.tick();
      assertEquals(0, adapter.actions);
      assertEquals(0, gateway.starts);
    }
  }

  @Test
  void full_journal_does_not_consume_a_timer_before_admission_succeeds() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      Map<String, JsonNode> busy = new LinkedHashMap<>();
      for (int i = 0; i < Journal.MAX_ENTRIES - 1; i++)
        busy.put("busy-" + i, Json.object().put("status", "UNKNOWN"));
      journal.commit(busy, null, null);
      advance(10);
      assertThrows(IOException.class, runtime::tick);
      assertTrue(journal.state("house").path("routes").path("heat").has("hold"));
      assertEquals(0, gateway.starts);
      for (int i = 0; i < 5; i++) journal.write("busy-" + i, Json.object().put("status", "DONE"));
      journal.prune(Set.of("busy-0", "busy-1", "busy-2", "busy-3", "busy-4"));
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void committed_held_start_recovers_after_restart_with_the_same_receipt() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    gateway.loseStart = true;
    var config = config(null);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("high", "30", false, "one");
      runtime.tick();
      advance(10);
      assertThrows(IOException.class, runtime::tick);
      assertEquals(1, gateway.starts);
    }
    advance(100);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      runtime.tick();
      assertEquals(1, gateway.starts);
      assertEquals(1, adapter.actions);
    }
  }
}
