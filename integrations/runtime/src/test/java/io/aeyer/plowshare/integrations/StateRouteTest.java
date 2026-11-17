package io.aeyer.plowshare.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.integrations.IntegrationContracts.Empty;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope;
import io.aeyer.plowshare.integrations.IntegrationContracts.Gap;
import io.aeyer.plowshare.integrations.IntegrationContracts.StateChanged;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exact-state predicates use the same durable owner as numeric routes, not another event engine.
 */
class StateRouteTest {
  @TempDir Path directory;
  private final AtomicReference<Instant> clock =
      new AtomicReference<>(Instant.parse("2026-11-17T00:00:00Z"));

  private final class Adapter implements IntegrationAdapter {
    private Consumer<Observation> listener;
    private Reading current;
    private int actions;
    private String actionState = "COMPLETED";

    @Override
    public void start(Consumer<Observation> listener) {
      this.listener = listener;
    }

    @Override
    public void validate(String operation, Arguments arguments) {}

    @Override
    public Result execute(String operation, Arguments arguments) {
      actions++;
      return new Result(
          actionState,
          new IntegrationResult(
              null,
              actionState.equals("COMPLETED") ? true : null,
              actionState.equals("COMPLETED") ? "not_observed" : null,
              actionState.equals("COMPLETED")
                  ? new ActionContext("fixture-action-context", null)
                  : null,
              null));
    }

    @Override
    public Map<String, Reading> snapshot() {
      return current == null ? Map.of() : Map.of("door", current);
    }

    @Override
    public void close() {}

    void emit(String identity, String state, boolean resync, String availability, String epoch) {
      emit(identity, state, resync, availability, epoch, null);
    }

    void emit(
        String identity,
        String state,
        boolean resync,
        String availability,
        String epoch,
        ActionContext context) {
      String at = clock.get().toString();
      current =
          new Reading(
              "door", state, availability, Map.of(), null, at, at, epoch, null, at, context);
      listener.accept(
          new Observation(identity, new EventEnvelope(new StateChanged(current, resync))));
    }

    void gap() {
      emit("stale", "open", false, "stale", "one");
      listener.accept(new Observation("gap", new EventEnvelope(new Gap("one", clock.get()))));
    }

    void seed() {
      listener.accept(new Observation("seed", new EventEnvelope(new Empty())));
    }
  }

  private Configuration config(long hold, String script, Configuration.QueuePolicy queue) {
    var route =
        new Configuration.Route(
            "door_open",
            "door",
            null,
            null,
            5,
            "investigate",
            "coordinator",
            "Explain the captured door observation",
            null,
            hold,
            "open");
    var binding =
        new Configuration.Binding(
            "house",
            "fake",
            "home",
            new IntegrationFixtures.Settings(),
            Map.of("door_open", route),
            Set.of("house"),
            script,
            "state-route-fixture",
            queue,
            new Configuration.FeedbackPolicy(true, 30));
    return new Configuration(
        "https://plowshare.example.test",
        "PLOWSHARE_TOKEN",
        directory,
        Map.of("house", binding),
        new Configuration.Retention(0, 60));
  }

  private Configuration config(long hold, String script) {
    return config(hold, script, Configuration.QueuePolicy.defaults());
  }

  private IntegrationRuntime runtime(
      Configuration configuration, Adapter adapter, Gateway gateway, Journal journal) {
    return new IntegrationRuntime(
        configuration,
        Map.of("house", adapter),
        gateway,
        journal,
        (source, handler, event, context) -> {
          try {
            // Match the production worker budget: these cases test routing and causality,
            // including cold child-JVM startup, rather than the script execution limit.
            return new ScriptHost(Duration.ofSeconds(5)).evaluate(source, handler, event, context);
          } catch (IOException failure) {
            throw new AssertionError("State-route fixture script failed", failure);
          }
        },
        clock::get);
  }

  private void advance(long seconds) {
    clock.set(clock.get().plusSeconds(seconds));
  }

  @Test
  void startup_and_same_state_do_not_start_historical_work_and_cooldown_needs_a_fresh_edge()
      throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(0, null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("bootstrap", "open", true, "available", "one");
      adapter.emit("same-state", "open", false, "available", "one");
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.emit("wrong-case", "OPEN", false, "available", "one");
      adapter.emit("fresh-open", "open", false, "available", "one");
      runtime.tick();
      assertEquals(1, gateway.starts);
      adapter.emit("closed", "closed", false, "available", "one");
      adapter.emit("during-cooldown", "open", false, "available", "one");
      runtime.tick();
      advance(5);
      runtime.tick();
      assertEquals(1, gateway.starts);
      adapter.emit("next-closed", "closed", false, "available", "one");
      adapter.emit("next-open", "open", false, "available", "one");
      runtime.tick();
      assertEquals(2, gateway.starts);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"gap", "resync", "unavailable", "epoch"})
  void continuity_barriers_cancel_exact_state_holds(String barrier) throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(10, null), adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("closed", "closed", true, "available", "one");
      adapter.emit("open", "open", false, "available", "one");
      runtime.tick();
      assertNotNull(journal.bindingState("house").routes().get("door_open").hold());
      advance(9);
      switch (barrier) {
        case "gap" -> adapter.gap();
        case "resync" -> adapter.emit("resync", "open", true, "available", "one");
        case "unavailable" -> adapter.emit("missing", "open", false, "unavailable", "one");
        case "epoch" -> adapter.emit("new-epoch", "open", false, "available", "two");
        default -> throw new AssertionError("Unknown fixture barrier");
      }
      runtime.tick();
      advance(30);
      runtime.tick();
      assertEquals(0, gateway.starts);
      assertNull(journal.bindingState("house").routes().get("door_open").hold());
    }
  }

  @Test
  void held_exact_state_fires_once_and_restart_does_not_count_downtime() throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var configuration = config(10, null);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(configuration, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("bootstrap", "closed", true, "available", "one");
      adapter.emit("pending-open", "open", false, "available", "one");
      runtime.tick();
    }
    advance(100);
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(configuration, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      assertEquals(0, gateway.starts);
      adapter.emit("restart-open", "open", true, "available", "two");
      runtime.tick();
      adapter.emit("fresh-closed", "closed", false, "available", "two");
      adapter.emit("fresh-open", "open", false, "available", "two");
      runtime.tick();
      advance(10);
      runtime.tick();
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void runtime_owned_acknowledged_echo_remains_observable_without_starting_a_workflow()
      throws Exception {
    String script =
        """
        export default {onEvent(e,c){
          if(!e.type)return [c.executeAction('house','notify',{}, {key:'act'})];
          if(e.alias!=='door')return [];
          c.state.observed=(c.state.observed??0)+1;
          if(e.causality)c.state.correlated=true;
          return [c.startPipeline('door_open',{evidence:e},{key:'door'})];
        }};
        """;
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(config(0, script), adapter, gateway, journal)) {
      runtime.start();
      adapter.seed();
      runtime.tick();
      assertEquals(1, adapter.actions);
      adapter.emit(
          "echo",
          "open",
          false,
          "available",
          "one",
          new ActionContext("fixture-echo", "fixture-action-context"));
      runtime.tick();
      assertEquals(0, gateway.starts);
      assertEquals(1, journal.state("house").path("script").path("observed").asInt());
      assertTrue(journal.state("house").path("script").path("correlated").asBoolean());
      adapter.emit("closed", "closed", false, "available", "one");
      adapter.emit("unrelated-open", "open", false, "available", "one");
      runtime.tick();
      assertEquals(1, gateway.starts);
    }
  }

  @Test
  void unknown_runtime_action_is_not_replayed_or_claimed_as_known_causal_evidence_after_restart()
      throws Exception {
    var adapter = new Adapter();
    adapter.actionState = "UNKNOWN";
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var configuration =
        config(
            0,
            "export default {onEvent(e,c){if(!e.type)return"
                + " [c.executeAction('house','notify',{}, {key:'act'})];return []}};");
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(configuration, adapter, gateway, journal)) {
      runtime.start();
      adapter.seed();
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals(0, journal.causeCount());
      assertTrue(journal.entries().stream().anyMatch(e -> "UNKNOWN".equals(e.getValue().status())));
    }
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(configuration, adapter, gateway, journal)) {
      runtime.start();
      runtime.tick();
      assertEquals(1, adapter.actions);
      assertEquals(0, journal.causeCount());
      assertEquals(0, gateway.starts);
    }
  }

  @Test
  void full_selected_event_queue_preserves_retained_transitions_and_stops_intake()
      throws Exception {
    var adapter = new Adapter();
    var gateway = new IntegrationRuntimeTest.FakeGateway();
    var configuration = config(0, null, new Configuration.QueuePolicy("same-state", 2));
    try (var journal = new Journal(directory, clock::get);
        var runtime = runtime(configuration, adapter, gateway, journal)) {
      runtime.start();
      adapter.emit("first", "open", false, "available", "one");
      adapter.emit("second", "closed", false, "available", "one");
      adapter.emit("overflow", "open", false, "available", "one");
      assertThrows(IOException.class, runtime::tick);
      assertEquals(2, journal.entries().size());
      assertEquals(
          Set.of("open", "closed"),
          Set.copyOf(
              journal.entries().stream()
                  .map(e -> e.getValue().event().reading().state())
                  .toList()));
      assertTrue(journal.entries().stream().allMatch(e -> "QUEUED".equals(e.getValue().status())));
      assertFalse(journal.entries().stream().anyMatch(e -> e.getValue().coalescedCount() != null));
      assertEquals(0, gateway.starts);
    }
  }
}
