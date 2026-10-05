package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import io.aeyer.plowshare.protocol.Orchestration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Adapter/runtime contracts. Collections are bounded immutable values, never arbitrary JSON trees.
 */
public final class IntegrationContracts {
  private IntegrationContracts() {}

  public static String identity(String value, int limit) {
    if (value == null
        || value.isBlank()
        || value.length() > limit
        || !value.equals(value.strip())
        || value.chars().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
      throw new IllegalArgumentException("invalid integration identity");
    return value;
  }

  static String optionalId(String value) {
    return value == null ? null : identity(value, 1024);
  }

  static String text(String value, int bound) {
    if (value != null && (value.length() > bound || value.indexOf('\0') >= 0))
      throw new IllegalArgumentException("integration text exceeds its contract");
    return value;
  }

  static <T> Map<String, T> values(Map<String, T> source, int bound) {
    Objects.requireNonNull(source);
    if (source.size() > bound)
      throw new IllegalArgumentException("integration collection exceeds its bound");
    source.keySet().forEach(key -> identity(key, 1024));
    return Map.copyOf(source);
  }

  public static Map<String, Reading> readings(Map<String, Reading> source) {
    Map<String, Reading> result = values(source, 256);
    for (var entry : result.entrySet())
      if (!entry.getKey().equals(entry.getValue().alias()))
        throw new IllegalArgumentException("reading alias mismatch");
    return result;
  }

  public sealed interface Event permits Empty, StateChanged, Gap, Held, ReadResult, Completion {}

  public record Empty() implements Event {}

  public record StateChanged(Reading reading, boolean resync) implements Event {
    public StateChanged {
      Objects.requireNonNull(reading);
    }
  }

  public record Gap(String epoch, Instant observedAt) implements Event {
    public Gap {
      identity(epoch, 1024);
      Objects.requireNonNull(observedAt);
    }
  }

  public record Held(
      Reading reading, String route, long heldSeconds, long heldSince, boolean resync)
      implements Event {
    public Held {
      Objects.requireNonNull(reading);
      Configuration.name(route);
      if (heldSeconds < 1 || heldSeconds > 86400)
        throw new IllegalArgumentException("invalid held duration");
    }
  }

  public record ReadResult(String state, IntegrationResult result) implements Event {
    public ReadResult {
      checkedOutcome(state);
      Objects.requireNonNull(result);
    }
  }

  public record Completion(String id, String state, String reportText, String completionAction)
      implements Event {
    public Completion {
      identity(id, 1024);
      if (!Set.of("completed", "failed", "capped", "cancelled").contains(state))
        throw new IllegalArgumentException("invalid completion state");
      Objects.requireNonNull(reportText);
      text(reportText, 1048576);
      if (completionAction != null && !completionAction.isEmpty())
        Configuration.name(completionAction);
    }
  }

  public record Causality(
      String operation,
      String context,
      String relation,
      String rootContext,
      int depth,
      long expiresAt,
      boolean pipelineStartsSuppressed) {
    public Causality {
      identity(operation, 1024);
      identity(context, 128);
      identity(rootContext, 128);
      if (!Set.of("id", "parent_id").contains(relation)
          || depth < 0
          || depth > 16
          || !pipelineStartsSuppressed)
        throw new IllegalArgumentException("invalid event causality");
    }
  }

  public record EventEnvelope(
      Event event, Causality causality, Long coalescedCount, Long coalescedSince) {
    public EventEnvelope {
      Objects.requireNonNull(event);
      if ((coalescedCount == null) != (coalescedSince == null)
          || coalescedCount != null && coalescedCount < 1)
        throw new IllegalArgumentException("invalid coalescing provenance");
    }

    public EventEnvelope(Event event) {
      this(event, null, null, null);
    }

    public EventEnvelope withCausality(Causality value) {
      return new EventEnvelope(event, value, coalescedCount, coalescedSince);
    }

    public EventEnvelope coalesced(Long count, Long since) {
      return new EventEnvelope(event, causality, count, since);
    }

    public Reading reading() {
      return switch (event) {
        case StateChanged e -> e.reading();
        case Held e -> e.reading();
        default -> null;
      };
    }

    public boolean resync() {
      return switch (event) {
        case StateChanged e -> e.resync();
        case Held e -> e.resync();
        default -> false;
      };
    }

    public ActionContext actionContext() {
      return reading() == null ? null : reading().context();
    }
  }

  /**
   * Primitive, named script variables. Nested objects require an explicitly registered state DTO.
   */
  public sealed interface StateValue permits StateText, StateNumber, StateBoolean {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static StateValue fromWire(Object value) {
      if (value instanceof String s) return new StateText(s);
      if (value instanceof Boolean b) return new StateBoolean(b);
      if (value instanceof Number n) return new StateNumber(new BigDecimal(n.toString()));
      throw new IllegalArgumentException("script state requires primitive variables");
    }
  }

  public record StateText(@JsonValue String value) implements StateValue {
    public StateText {
      Objects.requireNonNull(value);
      text(value, 65536);
    }
  }

  public record StateNumber(@JsonValue BigDecimal value) implements StateValue {
    public StateNumber {
      Objects.requireNonNull(value);
      if (value.precision() > 36 || Math.abs((long) value.scale()) > 18)
        throw new IllegalArgumentException("script number exceeds its bounds");
    }
  }

  public record StateBoolean(@JsonValue boolean value) implements StateValue {}

  public record ScriptState(@JsonValue Map<String, StateValue> variables) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ScriptState {
      variables = values(variables, 256);
      variables.keySet().forEach(Configuration::name);
    }

    public static ScriptState empty() {
      return new ScriptState(Map.of());
    }
  }

  public record Edge(String id, String session, String epoch) {
    public Edge {
      identity(id, 1024);
      identity(session, 1024);
      identity(epoch, 1024);
    }
  }

  public record Hold(String id, String session, String epoch, long since) {
    public Hold {
      identity(id, 1024);
      identity(session, 1024);
      identity(epoch, 1024);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RouteState(boolean high, Long started, Edge edge, Hold hold) {
    public RouteState {
      if (hold != null
          && (edge == null
              || !hold.id().equals(edge.id())
              || !hold.session().equals(edge.session())
              || !hold.epoch().equals(edge.epoch())))
        throw new IllegalArgumentException("held interval must belong to its edge");
    }

    public static RouteState empty() {
      return new RouteState(false, null, null, null);
    }

    public RouteState cancel() {
      return new RouteState(high, started, null, null);
    }

    public RouteState started(long now) {
      return new RouteState(high, now, edge, hold);
    }

    public RouteState consumed() {
      return new RouteState(high, started, edge, null);
    }
  }

  public record BindingState(ScriptState script, Map<String, RouteState> routes) {
    public BindingState {
      Objects.requireNonNull(script);
      routes = values(routes, 256);
      routes.keySet().forEach(Configuration::name);
    }

    public static BindingState empty() {
      return new BindingState(ScriptState.empty(), Map.of());
    }
  }

  public record ScriptContext(
      ScriptState state, Map<String, Map<String, Reading>> states, Map<String, Entry> runs) {
    public ScriptContext {
      Objects.requireNonNull(state);
      states = values(states, 32);
      var copy = new LinkedHashMap<String, Map<String, Reading>>();
      states.forEach((key, value) -> copy.put(key, readings(value)));
      states = Map.copyOf(copy);
      runs = values(runs, 1024);
    }

    public static ScriptContext empty() {
      return new ScriptContext(ScriptState.empty(), Map.of(), Map.of());
    }
  }

  public sealed interface Evidence permits EventEvidence, WrappedEvidence, ReadingEvidence {}

  public record EventEvidence(EventEnvelope event) implements Evidence {
    public EventEvidence {
      Objects.requireNonNull(event);
    }
  }

  public record WrappedEvidence(EventEnvelope evidence) implements Evidence {
    public WrappedEvidence {
      Objects.requireNonNull(evidence);
    }
  }

  public record ReadingEvidence(Map<String, Reading> reading) implements Evidence {
    public ReadingEvidence {
      reading = readings(reading);
    }
  }

  public sealed interface Effect permits Pipeline, Action, Read {
    String key();
  }

  public record Pipeline(String route, Evidence input, String key) implements Effect {
    public Pipeline {
      Configuration.name(route);
      Configuration.name(key);
      Objects.requireNonNull(input);
    }
  }

  public record Action(String binding, String action, Map<String, Parameter> parameters, String key)
      implements Effect {
    public Action {
      if (binding != null) Configuration.name(binding);
      Configuration.name(action);
      Configuration.name(key);
      parameters = new Arguments(null, action, parameters).parameters();
      Objects.requireNonNull(parameters);
    }

    public Arguments arguments() {
      return new Arguments(null, action, parameters);
    }
  }

  public record Read(String binding, List<String> entities, String key) implements Effect {
    public Read {
      if (binding != null) Configuration.name(binding);
      Configuration.name(key);
      entities = new Arguments(entities, null, null).entities();
      if (entities == null || entities.isEmpty())
        throw new IllegalArgumentException("selected entities required");
    }

    public Arguments arguments() {
      return new Arguments(entities, null, null);
    }
  }

  public record ScriptOutput(List<Effect> effects, ScriptState state) {
    public ScriptOutput {
      effects = List.copyOf(effects);
      if (effects.size() > 32) throw new IllegalArgumentException("too many script effects");
      Objects.requireNonNull(state);
      if (effects.stream().map(Effect::key).distinct().count() != effects.size())
        throw new IllegalArgumentException("duplicate effect key");
    }
  }

  static void checkedOutcome(String state) {
    if (!Set.of("COMPLETED", "FAILED", "REJECTED", "UNKNOWN").contains(state))
      throw new IllegalArgumentException("invalid adapter outcome");
  }

  /**
   * Immutable durable record. Drafts are private planning state and must validate before storage.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(
      String kind,
      String binding,
      String handler,
      String status,
      String fingerprint,
      String runtimeSession,
      EventEnvelope event,
      ScriptContext context,
      BindingState baseState,
      Long evaluatedAt,
      String heldRoute,
      Hold hold,
      String invocation,
      Causality causality,
      Long coalescedCount,
      Long coalescedSince,
      Effect effect,
      String source,
      String sourceFingerprint,
      Orchestration.Start payload,
      String completionAction,
      String run,
      String state,
      IntegrationResult result,
      Long revision,
      String diagnostic,
      String causalDiagnostic,
      Long createdAt,
      Long settledAt) {
    public Entry {
      if (!Set.of("handler", "effect", "outgoing").contains(kind))
        throw new IllegalArgumentException("invalid journal entry kind");
      Configuration.name(binding);
      if (!kind.equals("outgoing")) identity(fingerprint, 1024);
      else optionalId(fingerprint);
      if (!Set.of(
              "QUEUED",
              "STARTING",
              "DISPATCHED",
              "WATCHING",
              "REPORTING",
              "DONE",
              "FAILED",
              "REJECTED",
              "UNKNOWN")
          .contains(status)) throw new IllegalArgumentException("invalid journal entry status");
      optionalId(runtimeSession);
      optionalId(invocation);
      optionalId(run);
      optionalId(sourceFingerprint);
      if (source != null) Configuration.name(source);
      if (heldRoute != null) Configuration.name(heldRoute);
      if (completionAction != null) Configuration.name(completionAction);
      if (state != null) checkedOutcome(state);
      text(diagnostic, 32768);
      text(causalDiagnostic, 32768);
      if (kind.equals("handler")
          && (effect != null
              || payload != null
              || source != null
              || sourceFingerprint != null
              || run != null
              || state != null
              || result != null
              || revision != null
              || completionAction != null))
        throw new IllegalArgumentException("foreign fields in handler entry");
      if (!kind.equals("handler")
          && (handler != null
              || event != null
              || context != null
              || baseState != null
              || evaluatedAt != null
              || heldRoute != null
              || hold != null
              || runtimeSession != null
              || causality != null
              || coalescedCount != null
              || coalescedSince != null))
        throw new IllegalArgumentException("handler fields in external effect entry");
      if (kind.equals("outgoing")
          && (effect != null
              || payload != null
              || source != null
              || sourceFingerprint != null
              || run != null
              || completionAction != null
              || invocation != null))
        throw new IllegalArgumentException("planned effect fields in outgoing entry");
      if (kind.equals("effect") && (revision != null || state != null))
        throw new IllegalArgumentException("outgoing fields in planned effect entry");
      if (effect != null
          && !(effect instanceof Pipeline)
          && (payload != null || run != null || completionAction != null))
        throw new IllegalArgumentException("pipeline fields in adapter effect");
      String target =
          effect instanceof Action action
              ? action.binding()
              : effect instanceof Read read ? read.binding() : null;
      if (target != null && !target.equals(binding))
        throw new IllegalArgumentException("effect binding differs from durable destination");
      if (kind.equals("handler")
          && (!Set.of("onEvent", "onCompletion").contains(handler) || event == null))
        throw new IllegalArgumentException("handler event required");
      if (kind.equals("effect") && effect == null)
        throw new IllegalArgumentException("planned effect required");
      if (kind.equals("outgoing") && (revision == null || revision < 0))
        throw new IllegalArgumentException("outgoing revision required");
      if ((context == null) != (baseState == null) || context != null && evaluatedAt == null)
        throw new IllegalArgumentException(
            "captured context requires base state and evaluation time");
      if (kind.equals("handler")
          && ((handler.equals("onCompletion")) != (event.event() instanceof Completion)))
        throw new IllegalArgumentException("event family differs from handler");
      if (heldRoute != null
          && (!(event.event() instanceof Held held)
              || !heldRoute.equals(held.route())
              || held.heldSince() != hold.since()))
        throw new IllegalArgumentException("held event differs from timer provenance");
      if (Set.of("STARTING", "WATCHING").contains(status)
          && (!(effect instanceof Pipeline)
              || payload == null
              || status.equals("WATCHING") && run == null))
        throw new IllegalArgumentException("pipeline recovery requires its start identity");
      if ((heldRoute == null) != (hold == null))
        throw new IllegalArgumentException("held callback requires provenance");
      if ((coalescedCount == null) != (coalescedSince == null)
          || coalescedCount != null && coalescedCount < 1)
        throw new IllegalArgumentException("invalid record coalescing provenance");
      if (kind.equals("effect") && effect instanceof Pipeline && payload == null)
        throw new IllegalArgumentException("pipeline effect requires a start payload");
    }

    public Draft draft() {
      return new Draft(this);
    }
  }

  /**
   * Local mutable construction only; no unvalidated draft can cross a journal or script boundary.
   */
  static final class Draft {
    String kind,
        binding,
        handler,
        status,
        fingerprint,
        runtimeSession,
        heldRoute,
        invocation,
        source,
        sourceFingerprint,
        completionAction,
        run,
        state,
        diagnostic,
        causalDiagnostic;
    EventEnvelope event;
    ScriptContext context;
    BindingState baseState;
    Long evaluatedAt, coalescedCount, coalescedSince, revision, createdAt, settledAt;
    Hold hold;
    Causality causality;
    Effect effect;
    Orchestration.Start payload;
    IntegrationResult result;

    Draft() {}

    Draft(Entry e) {
      kind = e.kind();
      binding = e.binding();
      handler = e.handler();
      status = e.status();
      fingerprint = e.fingerprint();
      runtimeSession = e.runtimeSession();
      event = e.event();
      context = e.context();
      baseState = e.baseState();
      evaluatedAt = e.evaluatedAt();
      heldRoute = e.heldRoute();
      hold = e.hold();
      invocation = e.invocation();
      causality = e.causality();
      coalescedCount = e.coalescedCount();
      coalescedSince = e.coalescedSince();
      effect = e.effect();
      source = e.source();
      sourceFingerprint = e.sourceFingerprint();
      payload = e.payload();
      completionAction = e.completionAction();
      run = e.run();
      state = e.state();
      result = e.result();
      revision = e.revision();
      diagnostic = e.diagnostic();
      causalDiagnostic = e.causalDiagnostic();
      createdAt = e.createdAt();
      settledAt = e.settledAt();
    }

    Entry build() {
      return new Entry(
          kind,
          binding,
          handler,
          status,
          fingerprint,
          runtimeSession,
          event,
          context,
          baseState,
          evaluatedAt,
          heldRoute,
          hold,
          invocation,
          causality,
          coalescedCount,
          coalescedSince,
          effect,
          source,
          sourceFingerprint,
          payload,
          completionAction,
          run,
          state,
          result,
          revision,
          diagnostic,
          causalDiagnostic,
          createdAt,
          settledAt);
    }
  }
}
