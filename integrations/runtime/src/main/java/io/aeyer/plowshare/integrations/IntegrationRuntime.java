package io.aeyer.plowshare.integrations;

import io.aeyer.plowshare.integrations.IntegrationContracts.Action;
import io.aeyer.plowshare.integrations.IntegrationContracts.BindingState;
import io.aeyer.plowshare.integrations.IntegrationContracts.Completion;
import io.aeyer.plowshare.integrations.IntegrationContracts.Draft;
import io.aeyer.plowshare.integrations.IntegrationContracts.Effect;
import io.aeyer.plowshare.integrations.IntegrationContracts.Entry;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEvidence;
import io.aeyer.plowshare.integrations.IntegrationContracts.Held;
import io.aeyer.plowshare.integrations.IntegrationContracts.Hold;
import io.aeyer.plowshare.integrations.IntegrationContracts.Pipeline;
import io.aeyer.plowshare.integrations.IntegrationContracts.Read;
import io.aeyer.plowshare.integrations.IntegrationContracts.ReadResult;
import io.aeyer.plowshare.integrations.IntegrationContracts.RouteState;
import io.aeyer.plowshare.integrations.IntegrationContracts.ScriptContext;
import io.aeyer.plowshare.integrations.IntegrationContracts.ScriptOutput;
import io.aeyer.plowshare.integrations.IntegrationContracts.ScriptState;
import io.aeyer.plowshare.integrations.IntegrationContracts.StateChanged;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import io.aeyer.plowshare.protocol.Orchestration;
import io.aeyer.plowshare.protocol.RelayLog;
import io.aeyer.plowshare.protocol.RelayPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
  private final Map<String, String> relayAttention = new LinkedHashMap<>();

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
    journal.requireRequestCutover();
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
    journal.requireRequestCutover();
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
      if (b.relay() == null) continue;
      try {
        relayTick(b);
        relayAttention.remove(b.name());
      } catch (IOException | IllegalArgumentException failed) {
        // Failure evidence remains in the binding's cursor/intent. Never let one deferred
        // destination prevent another binding's bounded pass, or log request/credential text.
        relayAttention.put(b.name(), "Relay binding requires retained-evidence inspection");
      }
    }
    journal.maintain(config.retention());
  }

  public synchronized Map<String, String> relayAttention() {
    return Map.copyOf(relayAttention);
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

  private void relayTick(Configuration.Binding binding) throws IOException {
    var policy = binding.relay();
    for (var row : journal.entries()) {
      var retained = row.getValue();
      if (retained.kind().equals("relay")
          && retained.binding().equals(binding.name())
          && !retained.status().equals("DONE")) {
        if (!retained.fingerprint().equals(binding.fingerprint()))
          throw new IOException("Pending Relay work belongs to another binding configuration");
        processRelay(binding, row.getKey(), retained);
        return;
      }
    }
    var checkpoint = journal.relayCheckpoint(binding.name()).orElse(null);
    if (checkpoint != null && !checkpoint.fingerprint().equals(binding.fingerprint()))
      throw new IOException("Relay binding configuration differs from its retained cursor");
    String after = checkpoint == null ? "0" : checkpoint.position();
    var page = log(binding.project(), policy.requests(), policy.requestGeneration(), after);
    if (page.gapThrough() != null)
      throw new IOException("Relay request retention gap requires explicit inspection");
    Map<String, Entry> captured = new LinkedHashMap<>();
    for (var event : page.events()) {
      if (!policy.producers().contains(event.publisher())) continue;
      UUID source;
      try {
        source = UUID.fromString(event.eventId());
        if (!source.toString().equals(event.eventId()) || !event.payload().kind().equals("TEXT"))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException invalid) {
        throw new IOException("Configured producer supplied unsupported source evidence");
      }
      var evidence =
          new RelayRequests.Source(
              binding.project(),
              policy.requests(),
              policy.requestGeneration(),
              source,
              event.position(),
              event.publisher(),
              event.occurredAt(),
              event.payload().text(),
              event.causation());
      IntegrationPayload.Request command = null;
      try {
        var decoded = IntegrationCodec.read(evidence.text(), IntegrationPayload.Request.class);
        if (!decoded.binding().equals(binding.name())) throw new IllegalArgumentException();
        command = decoded;
      } catch (IOException | IllegalArgumentException invalid) {
        // Retain the complete source before recording a safe, explicit refusal. No adapter runs.
      }
      Draft record = new Draft();
      record.kind = "relay";
      record.binding = binding.name();
      record.fingerprint = binding.fingerprint();
      record.status = "QUEUED";
      record.relay = new RelayRequests.Delivery(evidence, command, null, null);
      captured.put(relayId(binding, source), record.build());
    }
    journal.captureRelay(
        binding.name(),
        checkpoint,
        new RelayRequests.Checkpoint(binding.fingerprint(), page.next()),
        captured);
    // Limit each pass to one retained request per binding. A pending/unknown return stops only
    // this binding's request execution, preserving source order and fairness across bindings.
    for (var row : journal.entries()) {
      Entry record = row.getValue();
      if (!record.kind().equals("relay")
          || !record.binding().equals(binding.name())
          || record.status().equals("DONE")) continue;
      if (!record.fingerprint().equals(binding.fingerprint()))
        throw new IOException("Pending Relay work belongs to another binding configuration");
      processRelay(binding, row.getKey(), record);
      break;
    }
  }

  private static String relayId(Configuration.Binding binding, UUID source) {
    return "relay:" + Json.identity(binding.project() + "\n" + binding.name() + "\n" + source);
  }

  private RelayLog.Page log(String project, String topic, UUID generation, String after)
      throws IOException {
    return log(project, topic, generation, after, 100);
  }

  private RelayLog.Page metadata(String project, String topic, UUID generation) throws IOException {
    // The public SDK has no exact-topic metadata-only operation. One event is the smallest log
    // projection; these fresh incarnation checks must not fetch and discard a whole body page.
    return log(project, topic, generation, "0", 1);
  }

  private RelayLog.Page log(String project, String topic, UUID generation, String after, int limit)
      throws IOException {
    var page = gateway.log(new RelayLog.Query(project, false, topic, after, limit));
    if (!page.scope().project().equals(project)
        || page.scope().system()
        || !page.topic().name().equals(topic)
        || !page.topic().kind().equals("TEXT")
        || !generation.toString().equals(page.topic().generation())
        || !page.after().equals(after))
      throw new IOException("Relay log differs from its pinned destination");
    return page;
  }

  private void processRelay(Configuration.Binding binding, String id, Entry saved)
      throws IOException {
    requireRelayBinding(binding, saved.relay());
    Draft record = saved.draft();
    if (record.status.equals("REPORTING")) {
      reconcileRelay(binding, id, record);
      return;
    }
    if (record.status.equals("DISPATCHED")) {
      record.state = "UNKNOWN";
      record.result =
          IntegrationAdapter.Result.diagnostic(
                  "UNKNOWN", "Interrupted execution; inspect retained external effect evidence")
              .data();
      record.status = "QUEUED";
    } else if (record.status.equals("QUEUED") && record.state == null) {
      IntegrationAdapter.Result result;
      boolean action = false;
      try {
        var command = record.relay.request();
        if (command == null) throw new IllegalArgumentException();
        var ancestry = record.relay.source().causation();
        if (ancestry == null
            || ancestry.depth() < 0
            || ancestry.depth() >= binding.relay().maxDepth()) throw new IllegalArgumentException();
        adapters.get(binding.name()).validate(command.operation(), command.arguments());
        action = command.operation().equals("actions.execute");
        if (action) journal.checkCauseCapacity(binding, clock.get().getEpochSecond());
      } catch (IllegalArgumentException denied) {
        result =
            IntegrationAdapter.Result.diagnostic("REJECTED", "Request refused by binding policy");
        record.state = result.state();
        record.result = result.data();
        journal.writeEntry(id, record.build());
        prepareRelayReturn(binding, id, record);
        return;
      }
      record.status = "DISPATCHED";
      // Pin both live incarnations before crossing the physical-effect boundary. The source may
      // have expired after capture; native return ancestry is independently retained server-side.
      metadata(binding.project(), binding.relay().requests(), binding.relay().requestGeneration());
      metadata(binding.project(), binding.relay().returns(), binding.relay().returnGeneration());
      journal.writeEntry(id, record.build());
      try {
        var command = record.relay.request();
        result = adapters.get(binding.name()).execute(command.operation(), command.arguments());
      } catch (IOException | RuntimeException uncertain) {
        result =
            IntegrationAdapter.Result.diagnostic(
                "UNKNOWN", "External operation outcome unconfirmed");
      }
      record.state = result.state();
      record.result = result.data();
      record.status = "QUEUED";
      if (action)
        journal.completeActionEntry(
            id, record.build(), binding, result, clock.get().getEpochSecond());
      else journal.writeEntry(id, record.build());
    }
    prepareRelayReturn(binding, id, record);
  }

  private void prepareRelayReturn(Configuration.Binding binding, String id, Draft record)
      throws IOException {
    var policy = binding.relay();
    // Capture the return-topic head before publication. Absence after uncertainty is never proof
    // of no submission; the retained boundary only bounds read-only reconciliation.
    var head = metadata(binding.project(), policy.returns(), policy.returnGeneration());
    String body =
        IntegrationCodec.write(
            new RelayOutcome(
                "plowshare-integration-result/1", record.state, record.state, record.result, null));
    if (body.getBytes(StandardCharsets.UTF_8).length > policy.maxReturnBytes())
      body =
          IntegrationCodec.write(
              new RelayOutcome(
                  "plowshare-integration-result/1",
                  "REJECTED",
                  record.state,
                  null,
                  "RESULT_TOO_LARGE: inspect the complete retained adapter evidence"));
    UUID mutation = Json.identity("integration-return/1\n" + id);
    String text =
        IntegrationCodec.write(
            new NativeReturn(
                "plowshare-message-return/1", mutation, record.relay.source().event(), body, true));
    var publication =
        new RelayPort.Publish(
            mutation.toString(),
            binding.project(),
            policy.returns(),
            text,
            clock.get().truncatedTo(ChronoUnit.MICROS),
            record.relay.source().event().toString(),
            null,
            null);
    record.relay =
        new RelayRequests.Delivery(
            record.relay.source(), record.relay.request(), publication, head.topic().through());
    record.status = "REPORTING";
    journal.writeEntry(id, record.build());
    var receipt = gateway.publish(publication);
    if (!receipt.requestId().equals(publication.requestId())
        || !receipt.project().equals(binding.project())
        || !receipt.topic().equals(policy.returns()))
      throw new IOException("Relay result acknowledgement differs from retained intent");
    // Public publication has no atomic expected-generation parameter. A changed incarnation here
    // remains uncertain and cannot be accepted as delivery to the retained native requester.
    log(binding.project(), policy.returns(), policy.returnGeneration(), receipt.position(), 1);
    reconcileRelay(binding, id, record);
  }

  private void reconcileRelay(Configuration.Binding binding, String id, Draft record)
      throws IOException {
    requireRelayBinding(binding, record.relay);
    var expected = record.relay.publication();
    String after = record.relay.returnAfter();
    for (int pages = 0; pages < 32; pages++) {
      var page =
          log(
              binding.project(),
              binding.relay().returns(),
              binding.relay().returnGeneration(),
              after);
      if (page.gapThrough() != null)
        throw new IOException("Relay result evidence expired; publication was not replayed");
      for (var event : page.events()) {
        if (!event.eventId().equals(expected.requestId())) continue;
        if (!event.publisher().equals(binding.relay().publisher())
            || !event.payload().kind().equals("TEXT")
            || !event.payload().text().equals(expected.text())
            || !Objects.equals(event.correlationId(), expected.correlationId())
            || !Objects.equals(event.causationId(), expected.parentEventId())
            || !event.occurredAt().equals(expected.occurredAt()))
          throw new IOException("Retained Relay result conflicts with publication evidence");
        record.status = "DONE";
        journal.writeEntry(id, record.build());
        return;
      }
      if (page.next().equals(after)) break;
      // A validated no-match page is read-only progress, never evidence that publication failed.
      // Retain it before reading another page so interruption cannot repeatedly scan the prefix.
      record.relay =
          new RelayRequests.Delivery(
              record.relay.source(), record.relay.request(), expected, page.next());
      journal.writeEntry(id, record.build());
      after = page.next();
      if (after.equals(page.topic().through())) break;
    }
    throw new IOException(
        "Relay result remains unconfirmed; no effect or publication was replayed");
  }

  /** Static owning consistency of retained evidence, before any request/recovery transport I/O. */
  private static void requireRelayBinding(
      Configuration.Binding binding, RelayRequests.Delivery retained) throws IOException {
    var source = retained.source();
    var policy = binding.relay();
    var publication = retained.publication();
    if (!source.project().equals(binding.project())
        || !source.topic().equals(policy.requests())
        || !source.generation().equals(policy.requestGeneration())
        || !policy.producers().contains(source.publisher())
        || publication != null
            && (!publication.project().equals(binding.project())
                || !publication.topic().equals(policy.returns())))
      throw new IOException("Retained Relay request differs from its configured binding");
  }

  private record RelayOutcome(
      String schema, String state, String effectState, IntegrationResult result, String refusal) {}

  private record NativeReturn(
      String schema, UUID requestId, UUID sourceEvent, String body, boolean finalReply) {}

  @Override
  public void close() {
    adapters.values().forEach(IntegrationAdapter::close);
    gateway.close();
  }
}
