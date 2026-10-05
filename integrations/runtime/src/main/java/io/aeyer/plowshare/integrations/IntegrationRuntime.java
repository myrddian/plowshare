package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.Outgoing;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Serial integration engine. Only validated DTOs enter policy; all effects follow durable plans.
 */
public final class IntegrationRuntime implements AutoCloseable {
  private final Configuration config;
  private final Map<String, IntegrationAdapter> adapters;
  private final Gateway gateway;
  private final IntegrationJournal journal;
  private final ScriptEvaluator scripts;
  private final Supplier<Instant> clock;
  private final String session = UUID.randomUUID().toString();
  private long lastHoldClock = Long.MIN_VALUE;
  private volatile IOException intakeFailure;

  public IntegrationRuntime(
      Configuration config,
      Map<String, IntegrationAdapter> adapters,
      Gateway gateway,
      IntegrationJournal journal,
      ScriptEvaluator scripts,
      Supplier<Instant> clock) {
    if (!adapters.keySet().equals(config.bindings().keySet()))
      throw new IllegalArgumentException("every binding needs an adapter");
    this.config = config;
    this.adapters = Map.copyOf(adapters);
    this.gateway = Objects.requireNonNull(gateway);
    this.journal = Objects.requireNonNull(journal);
    this.scripts = Objects.requireNonNull(scripts);
    this.clock = Objects.requireNonNull(clock);
  }

  public void start() throws IOException {
    journal.verifyDtos();
    for (var entry : adapters.entrySet())
      entry.getValue().start(observation -> accept(entry.getKey(), observation));
  }

  private void accept(String binding, IntegrationAdapter.Observation observation) {
    try {
      String id = "event:" + Json.identity(binding + ":" + observation.identity());
      Draft record =
          handler(
              binding, "onEvent", config.bindings().get(binding).fingerprint(), observation.data());
      record.runtimeSession = session;
      journal.enqueueEntry(
          id, record.build(), config.bindings().get(binding).queue(), config.retention());
    } catch (IOException | RuntimeException failed) {
      intakeFailure =
          new IOException("observation intake stopped; inspect journal capacity/configuration");
    }
  }

  private static Draft handler(
      String binding, String handler, String fingerprint, EventEnvelope event) {
    Draft r = new Draft();
    r.kind = "handler";
    r.binding = binding;
    r.handler = handler;
    r.fingerprint = fingerprint;
    r.event = event;
    r.status = "QUEUED";
    return r;
  }

  public synchronized void tick() throws IOException {
    if (intakeFailure != null) throw intakeFailure;
    // Validate the entire inventory before expiration, recovery or any adapter/network effect.
    journal.verifyDtos();
    journal.expireCauses(clock.get().getEpochSecond());
    journal.maintain(config.retention());
    recoverOutgoing();
    for (var adapter : adapters.values()) adapter.maintain();
    for (var e : journal.entries())
      if (e.getValue().kind().equals("handler") && e.getValue().status().equals("QUEUED"))
        plan(e.getKey(), e.getValue());
    queueHeldThresholds();
    for (var e : journal.entries())
      if (e.getValue().heldRoute() != null && e.getValue().status().equals("QUEUED"))
        plan(e.getKey(), e.getValue());
    for (var e : journal.entries())
      if (e.getValue().kind().equals("effect")) dispatch(e.getKey(), e.getValue());
    followRuns();
    for (var b : config.bindings().values()) {
      gateway.advertise(b.project(), List.of(b.peer()));
      var claimed = gateway.claim(b.project(), List.of(b.peer()));
      if (claimed.work() != null) outgoing(b, claimed);
    }
    journal.maintain(config.retention());
  }

  private Map<String, Map<String, Reading>> snapshots(Configuration.Binding b) {
    Map<String, Map<String, Reading>> values = new LinkedHashMap<>();
    for (String name : b.allowBindings())
      values.put(name, IntegrationContracts.readings(adapters.get(name).snapshot()));
    return Map.copyOf(values);
  }

  private void plan(String id, Entry saved) throws IOException {
    Draft record = saved.draft();
    String binding = record.binding;
    var b = config.bindings().get(binding);
    if (b == null || !b.fingerprint().equals(record.fingerprint)) {
      record.status = "REJECTED";
      record.diagnostic = "configuration changed before handler planning";
      journal.writeEntry(id, record.build());
      return;
    }
    if (record.heldRoute != null
        && record.context != null
        && journal.captureEntry(id, record.build(), true, null, null).isEmpty()) return;
    if (record.context == null) {
      BindingState base = journal.bindingState(binding);
      Map<String, Entry> runs = new LinkedHashMap<>();
      for (var pending : journal.entries()) {
        Entry work = pending.getValue();
        if (work.run() != null && b.allowBindings().contains(work.binding()))
          runs.put(work.run(), work);
      }
      record.context = new ScriptContext(base.script(), snapshots(b), runs);
      record.baseState = base;
      record.evaluatedAt = clock.get().getEpochSecond();
      EventEnvelope causal = record.event;
      if (record.heldRoute != null) {
        var route = b.routes().get(record.heldRoute);
        Reading reading =
            route == null
                ? null
                : record.context.states().getOrDefault(binding, Map.of()).get(route.entity());
        if (reading != null) causal = new EventEnvelope(new StateChanged(reading, false));
      }
      var captured = journal.captureEntry(id, record.build(), record.heldRoute != null, b, causal);
      if (captured.isEmpty()) return;
      record = captured.get().draft();
    }
    if (!record.baseState.equals(journal.bindingState(binding))) {
      record.status = "FAILED";
      record.diagnostic = "captured handler state is stale; nothing dispatched";
      journal.writeEntry(id, record.build());
      return;
    }
    EventEnvelope event = record.event.coalesced(record.coalescedCount, record.coalescedSince);
    Map<String, RouteState> routes = new LinkedHashMap<>(record.baseState.routes());
    Set<String> eligible = new HashSet<>();
    boolean suppressed = record.causality != null;
    long now = record.evaluatedAt;
    if (record.heldRoute != null) {
      String name = record.heldRoute;
      var route = b.routes().get(name);
      Hold hold = record.hold;
      RouteState current = routes.getOrDefault(name, RouteState.empty());
      Reading reading =
          route == null
              ? null
              : record.context.states().getOrDefault(binding, Map.of()).get(route.entity());
      boolean valid =
          route != null
              && current.high()
              && current.edge() != null
              && current.edge().id().equals(hold.id())
              && current.edge().session().equals(hold.session())
              && current.edge().epoch().equals(hold.epoch())
              && ThresholdPolicy.validHold(route, hold, reading, session, now)
              && now - hold.since() >= route.holdSeconds()
              && ThresholdPolicy.cooled(route, current, now);
      if (!valid) {
        record.status = "REJECTED";
        record.diagnostic = "held threshold no longer has continuous live evidence";
        journal.writeEntry(id, record.build());
        return;
      }
      if (suppressed) routes.put(name, current.cancel());
      else eligible.add(name);
      Held original = (Held) event.event();
      event =
          new EventEnvelope(
              new Held(
                  reading, name, original.heldSeconds(), original.heldSince(), original.resync()),
              null,
              event.coalescedCount(),
              event.coalescedSince());
    } else if (record.handler.equals("onEvent"))
      eligible =
          ThresholdPolicy.observe(
              b,
              routes,
              event,
              id,
              now,
              session,
              session.equals(record.runtimeSession),
              suppressed);
    event = event.withCausality(record.causality);
    ScriptState scriptState = record.baseState.script();
    try {
      List<Effect> effects = new ArrayList<>();
      if (b.script() != null) {
        ScriptOutput output = scripts.evaluate(b.script(), record.handler, event, record.context);
        effects = output.effects();
        scriptState = output.state();
      } else if (record.handler.equals("onEvent")) {
        for (String route : eligible)
          effects.add(new Pipeline(route, new EventEvidence(event), route));
      } else if (event.event() instanceof Completion completion
          && completion.state().equals("completed")
          && completion.completionAction() != null
          && !completion.completionAction().isBlank())
        effects.add(
            new Action(
                binding,
                completion.completionAction(),
                Map.of("message", new TextParameter(completion.reportText())),
                "completion"));
      Map<String, Entry> changes = new LinkedHashMap<>();
      Set<String> keys = new HashSet<>();
      for (Effect effect : effects) {
        String key = effect.key();
        if (!keys.add(key)) throw new IllegalArgumentException("duplicate effect key");
        String target =
            switch (effect) {
              case Action e -> e.binding() == null ? binding : e.binding();
              case Read e -> e.binding() == null ? binding : e.binding();
              case Pipeline e -> binding;
            };
        if (!b.allowBindings().contains(target))
          throw new IllegalArgumentException("unreachable binding");
        var destination = config.bindings().get(target);
        Draft next = new Draft();
        next.kind = "effect";
        next.binding = target;
        next.source = binding;
        next.sourceFingerprint = b.fingerprint();
        next.fingerprint = destination.fingerprint();
        next.status = "QUEUED";
        next.effect = effect;
        next.invocation = id;
        switch (effect) {
          case Pipeline e -> {
            var route = b.routes().get(e.route());
            if (route == null) throw new IllegalArgumentException("unknown route");
            if (!eligible.contains(e.route())) continue;
            routes.put(e.route(), routes.getOrDefault(e.route(), RouteState.empty()).started(now));
            next.payload =
                new Orchestration.Start(
                    route.agent(),
                    route.definition(),
                    route.request()
                        + "\n\nIntegration evidence (data):\n"
                        + IntegrationCodec.writeEvidence(e.input()),
                    null,
                    b.project(),
                    Json.identity(id + ":" + key));
            next.completionAction = route.completionAction();
          }
          case Action e -> adapters.get(target).validate("actions.execute", e.arguments());
          case Read e -> adapters.get(target).validate("states.read", e.arguments());
        }
        String effectId = "effect:" + Json.identity(id + ":" + key);
        if (journal.known(effectId))
          throw new IllegalArgumentException("recorded effect identity cannot be replaced");
        changes.put(effectId, next.build());
      }
      record.status = "DONE";
      changes.put(id, record.build());
      journal.commitEntries(changes, binding, new BindingState(scriptState, routes));
    } catch (IllegalArgumentException | IOException failed) {
      record.status = "FAILED";
      record.diagnostic = "handler failed or produced invalid effects; nothing dispatched";
      Map<String, RouteState> cancelled = new LinkedHashMap<>(record.baseState.routes());
      boolean reset = false;
      if (record.invocation == null)
        for (var route : b.routes().values()) {
          RouteState old = cancelled.get(route.name());
          if (route.holdSeconds() > 0
              && old != null
              && (old.hold() != null || old.edge() != null)) {
            cancelled.put(route.name(), old.cancel());
            reset = true;
          }
        }
      // Failed source handlers break held continuity; script variables and effects stay unchanged.
      journal.commitEntries(
          Map.of(id, record.build()),
          reset ? binding : null,
          reset ? new BindingState(record.baseState.script(), cancelled) : null);
    }
  }

  private void queueHeldThresholds() throws IOException {
    long now = clock.get().getEpochSecond();
    boolean reversed = now < lastHoldClock;
    lastHoldClock = now;
    for (var b : config.bindings().values())
      for (var route : b.routes().values()) {
        if (route.holdSeconds() == 0) continue;
        BindingState state = journal.bindingState(b.name());
        RouteState rs = state.routes().get(route.name());
        Hold pending = rs == null ? null : rs.hold();
        if (pending == null) continue;
        Reading reading = adapters.get(b.name()).snapshot().get(route.entity());
        Map<String, RouteState> next = new LinkedHashMap<>(state.routes());
        if (reversed || !ThresholdPolicy.validHold(route, pending, reading, session, now)) {
          next.put(route.name(), rs.cancel());
          journal.commitEntries(Map.of(), b.name(), new BindingState(state.script(), next));
          continue;
        }
        if (now - pending.since() < route.holdSeconds()) continue;
        String id = "held:" + Json.identity(b.name() + ":" + route.name() + ":" + pending.id());
        Draft timer =
            handler(
                b.name(),
                "onEvent",
                b.fingerprint(),
                new EventEnvelope(
                    new Held(reading, route.name(), route.holdSeconds(), pending.since(), false)));
        timer.heldRoute = route.name();
        timer.invocation = pending.id();
        timer.hold = pending;
        next.put(route.name(), rs.consumed());
        // Admission and timer consumption are one transaction; source observations retain priority.
        journal.admitTimer(
            id, timer.build(), b.name(), state, new BindingState(state.script(), next));
      }
  }

  private void dispatch(String id, Entry saved) throws IOException {
    String status = saved.status();
    if (!Set.of("QUEUED", "STARTING", "DISPATCHED").contains(status)) return;
    Draft r = saved.draft();
    String binding = r.binding;
    var b = config.bindings().get(binding);
    if (status.equals("DISPATCHED")) {
      r.status = "UNKNOWN";
      r.diagnostic = "external delivery unconfirmed; action was not replayed";
      journal.writeEntry(id, r.build());
      return;
    }
    var origin = config.bindings().get(r.source == null ? binding : r.source);
    boolean revoked =
        b == null
            || !b.fingerprint().equals(r.fingerprint)
            || origin == null
            || r.sourceFingerprint != null && !origin.fingerprint().equals(r.sourceFingerprint);
    if (revoked && !status.equals("STARTING")) {
      r.status = "REJECTED";
      journal.writeEntry(id, r.build());
      return;
    }
    if (r.effect instanceof Pipeline) {
      UUID request = r.payload.requestId();
      Optional<Orchestration.Started> receipt =
          status.equals("STARTING") ? gateway.receipt(request) : Optional.empty();
      if (receipt.isEmpty() && revoked) {
        r.status = "REJECTED";
        journal.writeEntry(id, r.build());
        return;
      }
      if (receipt.isEmpty()) {
        r.status = "STARTING";
        journal.writeEntry(id, r.build());
        receipt = Optional.of(gateway.start(r.payload));
      }
      if (!request.equals(receipt.get().requestId()))
        throw new IOException("foreign orchestration start receipt");
      r.run = receipt.get().id();
      r.status = "WATCHING";
      journal.writeEntry(id, r.build());
      return;
    }
    boolean action = r.effect instanceof Action;
    String operation = action ? "actions.execute" : "states.read";
    Arguments arguments = action ? ((Action) r.effect).arguments() : ((Read) r.effect).arguments();
    try {
      adapters.get(binding).validate(operation, arguments);
      if (action) journal.checkCauseCapacity(b, clock.get().getEpochSecond());
    } catch (IllegalArgumentException refused) {
      r.status = "REJECTED";
      journal.writeEntry(id, r.build());
      return;
    }
    r.status = "DISPATCHED";
    journal.writeEntry(id, r.build());
    IntegrationAdapter.Result result;
    try {
      result = adapters.get(binding).execute(operation, arguments);
    } catch (IOException | RuntimeException failure) {
      result =
          IntegrationAdapter.Result.diagnostic(
              action ? "UNKNOWN" : "FAILED",
              action ? "external operation outcome unconfirmed" : "external read unavailable");
    }
    r.status = result.state().equals("COMPLETED") ? "DONE" : result.state();
    r.result = result.data();
    Map<String, Entry> changes = new LinkedHashMap<>();
    changes.put(id, r.build());
    if (!action && origin.script() != null) {
      String callbackId = "read-result:" + Json.identity(id);
      Draft callback =
          handler(
              origin.name(),
              "onEvent",
              origin.fingerprint(),
              new EventEnvelope(new ReadResult(result.state(), result.data())));
      callback.invocation = id;
      if (!journal.known(callbackId)) changes.put(callbackId, callback.build());
    }
    if (action) journal.completeActionEntry(id, r.build(), b, result, clock.get().getEpochSecond());
    else journal.commitEntries(changes, null, null);
  }

  private void followRuns() throws IOException {
    for (var entry : journal.entries()) {
      Entry saved = entry.getValue();
      if (!saved.status().equals("WATCHING")) continue;
      var run = gateway.status(saved.run()).orchestration();
      if (!run.id().equals(saved.run())
          || !Objects.equals(run.project(), saved.payload().project()))
        throw new IOException("foreign orchestration status");
      if (!Set.of("finished", "failed", "capped", "cancelled").contains(run.state())) continue;
      EventEnvelope event =
          new EventEnvelope(
              new Completion(
                  run.id(),
                  run.state().equals("finished") ? "completed" : run.state(),
                  run.result() == null ? "" : run.result(),
                  saved.completionAction()));
      Draft handler = handler(saved.binding(), "onCompletion", saved.fingerprint(), event);
      handler.invocation = entry.getKey();
      Draft done = saved.draft();
      done.status = "DONE";
      String id = "completion:" + Json.identity(entry.getKey());
      Map<String, Entry> changes = new LinkedHashMap<>();
      changes.put(entry.getKey(), done.build());
      if (!journal.known(id)) changes.put(id, handler.build());
      journal.commitEntries(changes, null, null);
    }
  }

  private void recoverOutgoing() throws IOException {
    for (var entry : journal.entries()) {
      Entry record = entry.getValue();
      if (!record.kind().equals("outgoing")
          || !Set.of("DISPATCHED", "REPORTING").contains(record.status())) continue;
      var work = gateway.outgoing(UUID.fromString(entry.getKey().substring("outgoing:".length())));
      var b = config.bindings().get(record.binding());
      if (b == null || !b.project().equals(work.project()) || !b.peer().equals(work.peer()))
        throw new IOException("foreign outgoing recovery record");
      if (record.state() != null
          && record.state().equals(work.state())
          && Objects.equals(record.result(), work.result())) {
        Draft done = record.draft();
        done.status = "DONE";
        journal.writeEntry(entry.getKey(), done.build());
      } else
        throw new IOException(
            "stranded outgoing claim; inspect delivery before replacing session; nothing was replayed");
    }
  }

  private void outgoing(Configuration.Binding b, Outgoing.Claimed claimed) throws IOException {
    var work = claimed.work();
    if (!"send".equals(claimed.action())
        || !b.project().equals(work.project())
        || !b.peer().equals(work.peer())) throw new IOException("unexpected outgoing claim");
    String id = "outgoing:" + work.id();
    Draft record = new Draft();
    record.kind = "outgoing";
    record.binding = b.name();
    record.fingerprint = b.fingerprint();
    record.status = "DISPATCHED";
    record.revision = work.revision();
    journal.writeEntry(id, record.build());
    IntegrationAdapter.Result result;
    boolean action = false;
    try {
      var parts = work.message().parts();
      if (parts.size() != 1 || parts.getFirst().data() == null)
        throw new IllegalArgumentException("one registered integration part required");
      var body = parts.getFirst().data();
      if (!body.binding().equals(b.name()))
        throw new IllegalArgumentException("integration binding mismatch");
      String operation = body.operation();
      action = operation.equals("actions.execute");
      adapters.get(b.name()).validate(operation, body.arguments());
      if (action) journal.checkCauseCapacity(b, clock.get().getEpochSecond());
      result = adapters.get(b.name()).execute(operation, body.arguments());
    } catch (IllegalArgumentException refused) {
      result =
          IntegrationAdapter.Result.diagnostic(
              "REJECTED", "integration request refused by binding policy");
    } catch (IOException | RuntimeException failed) {
      result =
          IntegrationAdapter.Result.diagnostic("UNKNOWN", "external operation outcome unconfirmed");
    }
    record.state = result.state();
    record.result = result.data();
    record.status = "REPORTING";
    if (action)
      journal.completeActionEntry(id, record.build(), b, result, clock.get().getEpochSecond());
    else journal.writeEntry(id, record.build());
    try {
      gateway.report(
          new Outgoing.Report(
              work.id(), work.revision(), result.state(), null, null, result.data(), null));
    } catch (IOException failure) {
      throw new IOException(
          "outgoing report unconfirmed; inspect durable status before replacing session");
    }
    record.status = "DONE";
    journal.writeEntry(id, record.build());
  }

  @Override
  public void close() {
    adapters.values().forEach(IntegrationAdapter::close);
    gateway.close();
  }
}
