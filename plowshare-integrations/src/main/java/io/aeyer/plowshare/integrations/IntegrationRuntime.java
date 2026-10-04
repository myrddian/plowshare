package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Serial external integration engine. All side effects follow durable plans. */
public final class IntegrationRuntime implements AutoCloseable {
  private final Configuration config;
  private final Map<String, IntegrationAdapter> adapters;
  private final Gateway gateway;
  private final Journal journal;
  private final ScriptHost scripts;
  private final Supplier<Instant> clock;
  private final String session = UUID.randomUUID().toString();
  private long lastHoldClock = Long.MIN_VALUE;
  private volatile IOException intakeFailure;

  public IntegrationRuntime(
      Configuration config,
      Map<String, IntegrationAdapter> adapters,
      Gateway gateway,
      Journal journal,
      ScriptHost scripts,
      Supplier<Instant> clock) {
    if (!adapters.keySet().equals(config.bindings().keySet()))
      throw new IllegalArgumentException("every binding needs an adapter");
    this.config = config;
    this.adapters = Map.copyOf(adapters);
    this.gateway = gateway;
    this.journal = journal;
    this.scripts = scripts;
    this.clock = clock;
  }

  public void start() throws IOException {
    for (var entry : adapters.entrySet())
      entry.getValue().start(observation -> accept(entry.getKey(), observation));
  }

  private void accept(String binding, IntegrationAdapter.Observation observation) {
    try {
      if (observation.identity() == null
          || observation.identity().length() > 256
          || !observation.data().isObject()) throw new IOException("invalid adapter observation");
      String id = "event:" + Json.identity(binding + ":" + observation.identity());
      ObjectNode record = Json.object();
      record.put("kind", "handler");
      record.put("binding", binding);
      record.put("handler", "onEvent");
      record.put("status", "QUEUED");
      record.put("fingerprint", config.bindings().get(binding).fingerprint());
      record.put("runtimeSession", session);
      record.set("event", observation.data());
      journal.enqueue(id, record, config.bindings().get(binding).queue(), config.retention());
    } catch (IOException | RuntimeException failed) {
      intakeFailure =
          new IOException("observation intake stopped; inspect journal capacity/configuration");
    }
  }

  public synchronized void tick() throws IOException {
    if (intakeFailure != null) throw intakeFailure;
    journal.expireCauses(clock.get().getEpochSecond());
    journal.maintain(config.retention());
    recoverOutgoing();
    for (var adapter : adapters.values()) adapter.maintain();
    for (var entry : journal.records())
      if (entry.getValue().path("kind").asText().equals("handler")
          && entry.getValue().path("status").asText().equals("QUEUED"))
        plan(entry.getKey(), entry.getValue());
    queueHeldThresholds();
    for (var entry : journal.records())
      if (entry.getValue().has("heldRoute")
          && entry.getValue().path("status").asText().equals("QUEUED"))
        plan(entry.getKey(), entry.getValue());
    for (var entry : journal.records())
      if (entry.getValue().path("kind").asText().equals("effect"))
        dispatch(entry.getKey(), entry.getValue());
    followRuns();
    for (var binding : config.bindings().values()) {
      gateway.advertise(binding.project(), List.of(binding.peer()));
      Outgoing.Claimed claimed = gateway.claim(binding.project(), List.of(binding.peer()));
      if (claimed.work() != null) outgoing(binding, claimed);
    }
    journal.maintain(config.retention());
  }

  private ObjectNode snapshots(Configuration.Binding binding) {
    ObjectNode n = Json.object();
    binding.allowBindings().forEach(name -> n.set(name, adapters.get(name).snapshot()));
    return n;
  }

  private void plan(String id, JsonNode saved) throws IOException {
    ObjectNode record = (ObjectNode) saved.deepCopy();
    String binding = Json.text(record, "binding");
    Configuration.Binding b = config.bindings().get(binding);
    if (b == null || !b.fingerprint().equals(record.path("fingerprint").asText())) {
      record.put("status", "REJECTED");
      record.put("diagnostic", "configuration changed before handler planning");
      journal.write(id, record);
      return;
    }
    if (record.has("heldRoute")
        && record.has("context")
        && journal.capture(id, record, true) == null) return;
    if (!record.has("context")) {
      ObjectNode context = Json.object();
      ObjectNode baseState = (ObjectNode) journal.state(binding);
      context.set(
          "state", baseState.path("script").isObject() ? baseState.path("script") : Json.object());
      context.set("states", snapshots(b));
      ObjectNode runs = Json.object();
      for (var pending : journal.records()) {
        JsonNode work = pending.getValue();
        if (work.has("run") && b.allowBindings().contains(work.path("binding").asText()))
          runs.set(work.path("run").asText(), work);
      }
      context.set("runs", runs);
      record.set("context", context);
      record.set("baseState", baseState);
      record.put("evaluatedAt", clock.get().getEpochSecond());
      JsonNode causalEvent = record.path("event");
      if (record.has("heldRoute")) {
        var route = b.routes().get(record.path("heldRoute").asText());
        if (route != null) causalEvent = context.path("states").path(binding).path(route.entity());
      }
      JsonNode captured = journal.capture(id, record, record.has("heldRoute"), b, causalEvent);
      if (captured == null) return;
      record = (ObjectNode) captured;
    }
    if (!record.path("baseState").equals(journal.state(binding))) {
      record.put("status", "FAILED");
      record.put("diagnostic", "captured handler state is stale; nothing dispatched");
      journal.write(id, record);
      return;
    }
    ObjectNode event = (ObjectNode) record.path("event").deepCopy();
    if (record.has("coalescedCount")) {
      event.put("coalescedCount", record.path("coalescedCount").asLong());
      event.put("coalescedSince", record.path("coalescedSince").asLong());
    }
    ObjectNode state = (ObjectNode) record.path("baseState").deepCopy();
    ObjectNode routes =
        state.path("routes").isObject()
            ? (ObjectNode) state.path("routes").deepCopy()
            : Json.object();
    Set<String> eligible = new HashSet<>();
    boolean suppressed = record.path("causality").isObject();
    long now = record.path("evaluatedAt").asLong();
    if (record.has("heldRoute")) {
      String routeName = record.path("heldRoute").asText();
      Configuration.Route route = b.routes().get(routeName);
      JsonNode hold = record.path("hold"), current = routes.path(routeName);
      JsonNode reading =
          route == null
              ? Json.MAPPER.missingNode()
              : record.path("context").path("states").path(binding).path(route.entity());
      boolean valid =
          route != null
              && current.path("high").asBoolean()
              && current.path("edge").path("id").equals(hold.path("id"))
              && current.path("edge").path("session").equals(hold.path("session"))
              && current.path("edge").path("epoch").equals(hold.path("epoch"))
              && ThresholdPolicy.validHold(route, hold, reading, session, now)
              && now - hold.path("since").asLong() >= route.holdSeconds()
              && ThresholdPolicy.cooled(route, current, now);
      if (!valid) {
        record.put("status", "REJECTED");
        record.put("diagnostic", "held threshold no longer has continuous live evidence");
        journal.write(id, record);
        return;
      }
      if (suppressed) ThresholdPolicy.cancel((ObjectNode) routes.path(routeName));
      else eligible.add(routeName);
      // The callback carries the freshest captured reading, with recorded timer provenance.
      ObjectNode latest = (ObjectNode) reading.deepCopy();
      for (String field : List.of("type", "route", "heldSeconds", "heldSince", "resync"))
        latest.set(field, event.path(field));
      event = latest;
    } else if (record.path("handler").asText().equals("onEvent")) {
      eligible =
          ThresholdPolicy.observe(
              b,
              routes,
              event,
              id,
              now,
              session,
              session.equals(record.path("runtimeSession").asText()),
              suppressed);
    }
    if (suppressed) event.set("causality", record.path("causality"));
    else event.remove("causality");
    ArrayNode effects = Json.MAPPER.createArrayNode();
    JsonNode scriptState = state.path("script").isObject() ? state.path("script") : Json.object();
    try {
      if (b.script() != null) {
        JsonNode output =
            scripts.evaluate(
                b.script(), record.path("handler").asText(), event, record.path("context"));
        effects = (ArrayNode) output.path("effects");
        scriptState = output.path("state");
      } else if (record.path("handler").asText().equals("onEvent")) {
        for (String route : eligible) {
          ObjectNode e = Json.object();
          e.put("kind", "pipeline.start");
          e.put("route", route);
          e.put("key", route);
          e.set("input", event);
          effects.add(e);
        }
      } else if (event.path("state").asText().equals("completed")) {
        String action = event.path("completionAction").asText();
        if (!action.isBlank()) {
          ObjectNode e = Json.object();
          e.put("kind", "action");
          e.put("binding", binding);
          e.put("action", action);
          e.put("key", "completion");
          e.set("parameters", Json.object().put("message", event.path("reportText").asText()));
          effects.add(e);
        }
      }
      Map<String, JsonNode> changes = new LinkedHashMap<>();
      Set<String> keys = new HashSet<>();
      for (JsonNode e : effects) {
        String key = Json.text(e, "key");
        Configuration.name(key);
        if (!keys.add(key)) throw new IllegalArgumentException("duplicate effect key");
        String kind = Json.text(e, "kind");
        String target = e.has("binding") ? Json.text(e, "binding") : binding;
        if (!b.allowBindings().contains(target))
          throw new IllegalArgumentException("unreachable binding");
        Configuration.Binding destination = config.bindings().get(target);
        ObjectNode effect = Json.object();
        effect.put("kind", "effect");
        effect.put("binding", target);
        effect.put("source", binding);
        effect.put("sourceFingerprint", b.fingerprint());
        effect.put("fingerprint", destination.fingerprint());
        effect.put("status", "QUEUED");
        effect.set("effect", e.deepCopy());
        effect.put("invocation", id);
        if (kind.equals("pipeline.start")) {
          Json.fields(e, "kind", "route", "input", "key");
          String routeName = Json.text(e, "route");
          Configuration.Route route = b.routes().get(routeName);
          if (route == null) throw new IllegalArgumentException("unknown route");
          if (!eligible.contains(routeName)) continue;
          ObjectNode rs =
              routes.path(routeName).isObject()
                  ? (ObjectNode) routes.path(routeName).deepCopy()
                  : Json.object();
          rs.put("started", now);
          routes.set(routeName, rs);
          ObjectNode payload = Json.object();
          payload.put("project", b.project());
          payload.put("agent", route.agent());
          payload.put("definition", route.definition());
          payload.put(
              "request", route.request() + "\n\nIntegration evidence (data):\n" + e.path("input"));
          payload.put("requestId", Json.identity(id + ":" + key).toString());
          effect.set("payload", payload);
          if (route.completionAction() != null)
            effect.put("completionAction", route.completionAction());
        } else if (kind.equals("action")) {
          Json.fields(e, "kind", "binding", "action", "parameters", "key");
          adapters.get(target).validate("actions.execute", actionArgs(e));
        } else if (kind.equals("read")) {
          Json.fields(e, "kind", "binding", "entities", "key");
          adapters.get(target).validate("states.read", readArgs(e));
        } else throw new IllegalArgumentException("unsupported effect kind");
        String effectId = "effect:" + Json.identity(id + ":" + key);
        if (journal.known(effectId))
          throw new IllegalArgumentException("recorded effect identity cannot be replaced");
        changes.put(effectId, effect);
      }
      state.set("script", scriptState);
      state.set("routes", routes);
      record.put("status", "DONE");
      changes.put(id, record);
      journal.commit(changes, binding, state);
    } catch (IllegalArgumentException | IOException failed) {
      record.put("status", "FAILED");
      record.put("diagnostic", "handler failed or produced invalid effects; nothing dispatched");
      ObjectNode cancelled = (ObjectNode) record.path("baseState").deepCopy();
      boolean reset = false;
      if (!record.has("invocation")) {
        for (var route : b.routes().values()) {
          JsonNode old = cancelled.path("routes").path(route.name());
          if (route.holdSeconds() > 0 && old.isObject() && (old.has("hold") || old.has("edge"))) {
            ThresholdPolicy.cancel((ObjectNode) old);
            reset = true;
          }
        }
      }
      // Failed source handlers break held continuity; script state and effects stay
      // unchanged.
      journal.commit(Map.of(id, record), reset ? binding : null, reset ? cancelled : null);
    }
  }

  private static ObjectNode actionArgs(JsonNode e) {
    ObjectNode a = Json.object();
    a.put("action", Json.text(e, "action"));
    a.set("parameters", e.path("parameters"));
    return a;
  }

  private void queueHeldThresholds() throws IOException {
    long now = clock.get().getEpochSecond();
    boolean clockReversed = now < lastHoldClock;
    lastHoldClock = now;
    for (var binding : config.bindings().values()) {
      for (var route : binding.routes().values()) {
        if (route.holdSeconds() == 0) continue;
        ObjectNode state = (ObjectNode) journal.state(binding.name());
        JsonNode pending = state.path("routes").path(route.name()).path("hold");
        if (!pending.isObject()) continue;
        JsonNode reading = adapters.get(binding.name()).snapshot().path(route.entity());
        ObjectNode next = state.deepCopy();
        ObjectNode routeState = (ObjectNode) next.path("routes").path(route.name());
        if (clockReversed || !ThresholdPolicy.validHold(route, pending, reading, session, now)) {
          ThresholdPolicy.cancel(routeState);
          journal.commit(Map.of(), binding.name(), next);
          continue;
        }
        if (now - pending.path("since").asLong() < route.holdSeconds()) continue;
        String id =
            "held:"
                + Json.identity(
                    binding.name() + ":" + route.name() + ":" + pending.path("id").asText());
        ObjectNode event = (ObjectNode) reading.deepCopy();
        event
            .put("type", "threshold.held")
            .put("route", route.name())
            .put("heldSeconds", route.holdSeconds())
            .put("heldSince", pending.path("since").asLong())
            .put("resync", false);
        ObjectNode timer =
            Json.object()
                .put("kind", "handler")
                .put("binding", binding.name())
                .put("handler", "onEvent")
                .put("status", "QUEUED")
                .put("fingerprint", binding.fingerprint())
                .put("heldRoute", route.name())
                .put("invocation", pending.path("id").asText());
        timer.set("hold", pending.deepCopy());
        timer.set("event", event);
        routeState.remove("hold");
        // Admission consumes the timer even if JS refuses/fails, with no repeated callback.
        journal.queueTimer(id, timer, binding.name(), state, next);
      }
    }
  }

  private static ObjectNode readArgs(JsonNode e) {
    ObjectNode a = Json.object();
    a.set("entities", e.path("entities"));
    return a;
  }

  private void dispatch(String id, JsonNode saved) throws IOException {
    String status = saved.path("status").asText();
    String kind = saved.path("effect").path("kind").asText();
    if (!Set.of("QUEUED", "STARTING", "DISPATCHED").contains(status)) return;
    ObjectNode r = (ObjectNode) saved.deepCopy();
    String binding = Json.text(r, "binding");
    var b = config.bindings().get(binding);
    if (status.equals("DISPATCHED")) {
      r.put("status", "UNKNOWN");
      r.put("diagnostic", "external delivery unconfirmed; action was not replayed");
      journal.write(id, r);
      return;
    }
    var origin = config.bindings().get(r.path("source").asText(binding));
    boolean revoked =
        b == null
            || !b.fingerprint().equals(r.path("fingerprint").asText())
            || origin == null
            || r.has("sourceFingerprint")
                && !origin.fingerprint().equals(r.path("sourceFingerprint").asText());
    if (revoked && !status.equals("STARTING")) {
      r.put("status", "REJECTED");
      journal.write(id, r);
      return;
    }
    if (kind.equals("pipeline.start")) {
      Map<String, Object> payload = Json.map(r.path("payload"));
      UUID request = UUID.fromString((String) payload.get("requestId"));
      JsonNode receipt = null;
      if (status.equals("STARTING")) receipt = gateway.receipt(request);
      if (receipt == null && revoked) {
        r.put("status", "REJECTED");
        journal.write(id, r);
        return;
      }
      if (receipt == null) {
        r.put("status", "STARTING");
        journal.write(id, r);
        receipt = gateway.start(payload);
      }
      String run = Json.text(receipt, "id");
      r.put("run", run);
      r.put("status", "WATCHING");
      journal.write(id, r);
      return;
    }
    String operation = kind.equals("action") ? "actions.execute" : "states.read";
    JsonNode arguments =
        kind.equals("action") ? actionArgs(r.path("effect")) : readArgs(r.path("effect"));
    try {
      adapters.get(binding).validate(operation, arguments);
      if (kind.equals("action")) journal.checkCauseCapacity(b, clock.get().getEpochSecond());
    } catch (IllegalArgumentException refused) {
      r.put("status", "REJECTED");
      journal.write(id, r);
      return;
    }
    r.put("status", "DISPATCHED");
    journal.write(id, r);
    IntegrationAdapter.Result result;
    try {
      result = adapters.get(binding).execute(operation, arguments);
    } catch (IOException | RuntimeException failure) {
      result =
          new IntegrationAdapter.Result(
              kind.equals("action") ? "UNKNOWN" : "FAILED",
              Json.object()
                  .put(
                      "diagnostic",
                      kind.equals("action")
                          ? "external operation outcome unconfirmed"
                          : "external read unavailable"));
    }
    r.put("status", result.state().equals("COMPLETED") ? "DONE" : result.state());
    r.set("result", result.data());
    Map<String, JsonNode> changes = new LinkedHashMap<>();
    changes.put(id, r);
    if (kind.equals("read") && origin.script() != null) {
      String callbackId = "read-result:" + Json.identity(id);
      ObjectNode callback =
          Json.object()
              .put("kind", "handler")
              .put("handler", "onEvent")
              .put("binding", origin.name())
              .put("fingerprint", origin.fingerprint())
              .put("status", "QUEUED")
              .put("invocation", id);
      callback.set(
          "event",
          Json.object()
              .put("type", "read.result")
              .put("state", result.state())
              .set("result", result.data()));
      if (!journal.known(callbackId)) changes.put(callbackId, callback);
    }
    if (kind.equals("action"))
      journal.completeAction(id, r, b, result, clock.get().getEpochSecond());
    else journal.commit(changes, null, null);
  }

  private void followRuns() throws IOException {
    for (var entry : journal.records()) {
      JsonNode saved = entry.getValue();
      if (!saved.path("status").asText().equals("WATCHING")) continue;
      JsonNode run = gateway.status(Json.text(saved, "run")).path("orchestration");
      if (!run.path("id").asText().equals(saved.path("run").asText())
          || !run.path("project").asText().equals(saved.path("payload").path("project").asText()))
        throw new IOException("foreign orchestration status");
      String state = run.path("state").asText();
      if (!Set.of("finished", "failed", "capped", "cancelled").contains(state)) continue;
      ObjectNode event = Json.object();
      event.put("id", run.path("id").asText());
      event.put("state", state.equals("finished") ? "completed" : state);
      event.put("reportText", run.path("result").asText(""));
      event.put("completionAction", saved.path("completionAction").asText(""));
      ObjectNode handler = Json.object();
      handler.put("kind", "handler");
      handler.put("handler", "onCompletion");
      handler.put("binding", saved.path("binding").asText());
      handler.put("fingerprint", saved.path("fingerprint").asText());
      handler.put("status", "QUEUED");
      handler.put("invocation", entry.getKey());
      handler.set("event", event);
      ObjectNode done = (ObjectNode) saved.deepCopy();
      done.put("status", "DONE");
      String handlerId = "completion:" + Json.identity(entry.getKey());
      Map<String, JsonNode> changes = new LinkedHashMap<>();
      changes.put(entry.getKey(), done);
      if (!journal.known(handlerId)) changes.put(handlerId, handler);
      journal.commit(changes, null, null);
    }
  }

  private void recoverOutgoing() throws IOException {
    for (var entry : journal.records()) {
      JsonNode record = entry.getValue();
      if (!record.path("kind").asText().equals("outgoing")
          || !Set.of("DISPATCHED", "REPORTING").contains(record.path("status").asText())) continue;
      Outgoing.Work work =
          gateway.outgoing(UUID.fromString(entry.getKey().substring("outgoing:".length())));
      Configuration.Binding binding = config.bindings().get(record.path("binding").asText());
      if (binding == null
          || !binding.project().equals(work.project())
          || !binding.peer().equals(work.peer()))
        throw new IOException("foreign outgoing recovery record");
      if (record.has("state")
          && record.path("state").asText().equals(work.state())
          && Json.map(record.path("result")).equals(work.result())) {
        ObjectNode done = (ObjectNode) record.deepCopy();
        done.put("status", "DONE");
        journal.write(entry.getKey(), done);
      } else
        throw new IOException(
            "stranded outgoing claim; inspect delivery before replacing session;"
                + " nothing was replayed");
    }
  }

  private void outgoing(Configuration.Binding b, Outgoing.Claimed claimed) throws IOException {
    var work = claimed.work();
    if (!"send".equals(claimed.action())
        || !b.project().equals(work.project())
        || !b.peer().equals(work.peer())) throw new IOException("unexpected outgoing claim");
    String id = "outgoing:" + work.id();
    ObjectNode record = Json.object();
    record.put("kind", "outgoing");
    record.put("binding", b.name());
    record.put("status", "DISPATCHED");
    record.put("revision", work.revision());
    journal.write(id, record);
    IntegrationAdapter.Result result;
    boolean actionRequest = false;
    try {
      JsonNode message = Json.MAPPER.valueToTree(work.message());
      JsonNode parts = message.path("parts");
      if (!parts.isArray() || parts.size() != 1)
        throw new IllegalArgumentException("one structured integration part required");
      Json.fields(parts.get(0), "data");
      JsonNode body = parts.get(0).path("data");
      Json.fields(body, "schema", "binding", "operation", "arguments");
      if (!body.path("schema").asText().equals("plowshare-integration/1")
          || !body.path("binding").asText().equals(b.name()))
        throw new IllegalArgumentException("integration schema/binding mismatch");
      String operation = Json.text(body, "operation");
      actionRequest = operation.equals("actions.execute");
      adapters.get(b.name()).validate(operation, body.path("arguments"));
      if (operation.equals("actions.execute"))
        journal.checkCauseCapacity(b, clock.get().getEpochSecond());
      result = adapters.get(b.name()).execute(operation, body.path("arguments"));
    } catch (IllegalArgumentException refused) {
      result =
          new IntegrationAdapter.Result(
              "REJECTED",
              Json.object().put("diagnostic", "integration request refused by binding policy"));
    } catch (IOException | RuntimeException failed) {
      result =
          new IntegrationAdapter.Result(
              "UNKNOWN", Json.object().put("diagnostic", "external operation outcome unconfirmed"));
    }
    record.put("state", result.state());
    record.set("result", result.data());
    record.put("status", "REPORTING");
    if (actionRequest) journal.completeAction(id, record, b, result, clock.get().getEpochSecond());
    else journal.write(id, record);
    try {
      gateway.report(
          new Outgoing.Report(
              work.id(),
              work.revision(),
              result.state(),
              null,
              null,
              Json.map(result.data()),
              null));
    } catch (IOException failure) {
      throw new IOException(
          "outgoing report unconfirmed; inspect durable status before replacing session");
    }
    record.put("status", "DONE");
    journal.write(id, record);
  }

  @Override
  public void close() {
    adapters.values().forEach(IntegrationAdapter::close);
    gateway.close();
  }
}
