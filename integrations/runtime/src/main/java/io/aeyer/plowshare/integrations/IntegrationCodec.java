package io.aeyer.plowshare.integrations;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** The only runtime DTO JSON boundary. Keeps journal and script wire shapes stable and strict. */
public final class IntegrationCodec {
  private IntegrationCodec() {}

  private static final ObjectMapper JSON = mapper();

  private static ObjectMapper mapper() {
    ObjectMapper mapper =
        com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .findAndAddModules()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .setSerializationInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
    for (var shape :
        java.util.List.of(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
      mapper
          .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
          .setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    SimpleModule module = new SimpleModule();
    module.addDeserializer(
        EventEnvelope.class,
        new StdDeserializer<EventEnvelope>(EventEnvelope.class) {
          @Override
          public EventEnvelope deserialize(JsonParser p, DeserializationContext c)
              throws IOException {
            return event(c.readTree(p));
          }
        });
    module.addSerializer(
        EventEnvelope.class,
        new StdSerializer<EventEnvelope>(EventEnvelope.class) {
          @Override
          public void serialize(EventEnvelope value, JsonGenerator g, SerializerProvider p)
              throws IOException {
            g.writeTree(eventTree(value));
          }
        });
    module.addDeserializer(
        Effect.class,
        new StdDeserializer<Effect>(Effect.class) {
          @Override
          public Effect deserialize(JsonParser p, DeserializationContext c) throws IOException {
            return effect(c.readTree(p));
          }
        });
    module.addSerializer(
        Effect.class,
        new StdSerializer<Effect>(Effect.class) {
          @Override
          public void serialize(Effect value, JsonGenerator g, SerializerProvider p)
              throws IOException {
            g.writeTree(effectTree(value));
          }
        });
    module.addDeserializer(
        RouteState.class,
        new StdDeserializer<RouteState>(RouteState.class) {
          @Override
          public RouteState deserialize(JsonParser p, DeserializationContext c) throws IOException {
            JsonNode n = c.readTree(p);
            Json.fields(n, "high", "started", "edge", "hold");
            return new RouteState(
                flag(n, "high", false),
                optionalLong(n, "started"),
                n.hasNonNull("edge") ? decode(n.get("edge"), Edge.class) : null,
                n.hasNonNull("hold") ? decode(n.get("hold"), Hold.class) : null);
          }
        });
    module.addDeserializer(
        BindingState.class,
        new StdDeserializer<BindingState>(BindingState.class) {
          @Override
          public BindingState deserialize(JsonParser p, DeserializationContext c)
              throws IOException {
            JsonNode n = c.readTree(p);
            Json.fields(n, "script", "routes");
            ScriptState script =
                n.has("script") ? decode(n.get("script"), ScriptState.class) : ScriptState.empty();
            Map<String, RouteState> routes = new LinkedHashMap<>();
            if (n.has("routes")) {
              if (!n.get("routes").isObject())
                throw new IllegalArgumentException("route state object required");
              n.get("routes")
                  .fields()
                  .forEachRemaining(
                      e -> routes.put(e.getKey(), decode(e.getValue(), RouteState.class)));
            }
            return new BindingState(script, routes);
          }
        });
    module.addDeserializer(
        ScriptContext.class,
        new StdDeserializer<ScriptContext>(ScriptContext.class) {
          @Override
          public ScriptContext deserialize(JsonParser p, DeserializationContext c)
              throws IOException {
            JsonNode n = c.readTree(p);
            Json.fields(n, "state", "states", "runs");
            ScriptState state =
                n.has("state") ? decode(n.get("state"), ScriptState.class) : ScriptState.empty();
            Map<String, Map<String, Reading>> states = new LinkedHashMap<>();
            Map<String, Entry> runs = new LinkedHashMap<>();
            if (n.has("states")) {
              if (!n.get("states").isObject())
                throw new IllegalArgumentException("captured states object required");
              n.get("states")
                  .fields()
                  .forEachRemaining(e -> states.put(e.getKey(), readings(e.getValue())));
            }
            if (n.has("runs")) {
              if (!n.get("runs").isObject())
                throw new IllegalArgumentException("captured runs object required");
              n.get("runs")
                  .fields()
                  .forEachRemaining(
                      e -> {
                        Entry value = entry(e.getValue());
                        if (!e.getKey().equals(value.run()))
                          throw new IllegalArgumentException("run key mismatch");
                        runs.put(e.getKey(), value);
                      });
            }
            return new ScriptContext(state, states, runs);
          }
        });
    return mapper.registerModule(module);
  }

  /**
   * Invalid conversions expose only a fixed diagnostic, never payload excerpts or parser causes.
   */
  public static <T> T decode(JsonNode node, Class<T> type) {
    try {
      return Objects.requireNonNull(JSON.treeToValue(node, type));
    } catch (IOException | RuntimeException invalid) {
      throw new IllegalArgumentException("invalid integration DTO");
    }
  }

  public static <T> T read(String source, Class<T> type) throws IOException {
    try {
      return decode(Json.parse(source), type);
    } catch (IOException | RuntimeException invalid) {
      throw new IOException("invalid integration DTO");
    }
  }

  public static JsonNode tree(Object value) {
    return JSON.valueToTree(value);
  }

  public static String write(Object value) throws IOException {
    return JSON.writeValueAsString(value);
  }

  public static EventEnvelope event(JsonNode source) {
    if (!source.isObject()) throw new IllegalArgumentException("event object required");
    ObjectNode n = (ObjectNode) source.deepCopy();
    Causality cause =
        n.hasNonNull("causality") ? decode(n.get("causality"), Causality.class) : null;
    Long count = optionalLong(n, "coalescedCount"), since = optionalLong(n, "coalescedSince");
    n.remove(List.of("causality", "coalescedCount", "coalescedSince"));
    if (n.isEmpty()) return new EventEnvelope(new Empty(), cause, count, since);
    String type = Json.text(n, "type");
    Event event;
    switch (type) {
      case "state_changed" -> {
        boolean resync = flag(n, "resync", false);
        n.remove(List.of("type", "resync"));
        event = new StateChanged(decode(n, Reading.class), resync);
      }
      case "threshold.held" -> {
        String route = Json.text(n, "route");
        long duration = requiredLong(n, "heldSeconds"), at = requiredLong(n, "heldSince");
        boolean resync = flag(n, "resync", false);
        n.remove(List.of("type", "route", "heldSeconds", "heldSince", "resync"));
        event = new Held(decode(n, Reading.class), route, duration, at, resync);
      }
      case "connection.gap" -> {
        Json.fields(n, "type", "epoch", "observed_at");
        event = new Gap(Json.text(n, "epoch"), Instant.parse(Json.text(n, "observed_at")));
      }
      case "read.result" -> {
        Json.fields(n, "type", "state", "result");
        event =
            new ReadResult(
                Json.text(n, "state"),
                decode(
                    n.path("result"),
                    io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult.class));
      }
      case "completion" -> {
        Json.fields(n, "type", "id", "state", "reportText", "completionAction");
        event =
            new Completion(
                Json.text(n, "id"),
                Json.text(n, "state"),
                optionalText(n, "reportText"),
                optionalText(n, "completionAction"));
      }
      default -> throw new IllegalArgumentException("unregistered integration event family");
    }
    return new EventEnvelope(event, cause, count, since);
  }

  static ObjectNode eventTree(EventEnvelope envelope) {
    ObjectNode n =
        switch (envelope.event()) {
          case Empty e -> Json.object();
          case StateChanged e ->
              ((ObjectNode) tree(e.reading()))
                  .put("type", "state_changed")
                  .put("resync", e.resync());
          case Held e ->
              ((ObjectNode) tree(e.reading()))
                  .put("type", "threshold.held")
                  .put("route", e.route())
                  .put("heldSeconds", e.heldSeconds())
                  .put("heldSince", e.heldSince())
                  .put("resync", e.resync());
          case Gap e ->
              Json.object()
                  .put("type", "connection.gap")
                  .put("epoch", e.epoch())
                  .put("observed_at", e.observedAt().toString());
          case ReadResult e ->
              Json.object()
                  .put("type", "read.result")
                  .put("state", e.state())
                  .set("result", tree(e.result()));
          // Completion historically has no type field. It is inferred only in its typed journal
          // handler.
          case Completion e -> {
            ObjectNode value =
                Json.object()
                    .put("id", e.id())
                    .put("state", e.state())
                    .put("reportText", e.reportText());
            if (e.completionAction() != null) value.put("completionAction", e.completionAction());
            yield value;
          }
        };
    if (envelope.causality() != null) n.set("causality", tree(envelope.causality()));
    if (envelope.coalescedCount() != null) {
      n.put("coalescedCount", envelope.coalescedCount());
      n.put("coalescedSince", envelope.coalescedSince());
    }
    return n;
  }

  static Feedback.Cause cause(JsonNode source) {
    Json.fields(
        source,
        "binding",
        "fingerprint",
        "context",
        "operation",
        "expiresAt",
        "ambiguous",
        "depth",
        "parent");
    ObjectNode n = (ObjectNode) source.deepCopy();
    if (!n.has("ambiguous")) n.put("ambiguous", false);
    if (!n.has("depth")) n.put("depth", 0);
    return decode(n, Feedback.Cause.class);
  }

  public static Entry entry(JsonNode source) {
    ObjectNode n = source.isObject() ? (ObjectNode) source.deepCopy() : null;
    if (n == null) throw new IllegalArgumentException("journal entry object required");
    if ("onCompletion".equals(n.path("handler").asText())
        && n.path("event").isObject()
        && !n.path("event").has("type")) ((ObjectNode) n.path("event")).put("type", "completion");
    return decode(n, Entry.class);
  }

  public static BindingState bindingState(JsonNode source) {
    return decode(source, BindingState.class);
  }

  public static Arguments arguments(JsonNode source) {
    return decode(source, Arguments.class);
  }

  public static ScriptOutput output(JsonNode source) {
    return decode(source, ScriptOutput.class);
  }

  private static Evidence evidence(JsonNode n) {
    if (!n.isObject()) throw new IllegalArgumentException("pipeline evidence object required");
    if (n.has("evidence")) {
      Json.fields(n, "evidence");
      return new WrappedEvidence(event(n.get("evidence")));
    }
    if (n.has("reading")) {
      Json.fields(n, "reading");
      return new ReadingEvidence(readings(n.get("reading")));
    }
    return new EventEvidence(event(n));
  }

  public static String writeEvidence(Evidence e) {
    return evidenceTree(e).toString();
  }

  static JsonNode evidenceTree(Evidence e) {
    return switch (e) {
      case EventEvidence v -> tree(v.event());
      case WrappedEvidence v -> Json.object().set("evidence", tree(v.evidence()));
      case ReadingEvidence v -> Json.object().set("reading", tree(v.reading()));
    };
  }

  public static Map<String, Reading> readings(JsonNode n) {
    if (!n.isObject() || n.size() > 256)
      throw new IllegalArgumentException("bounded readings object required");
    Map<String, Reading> result = new LinkedHashMap<>();
    n.fields().forEachRemaining(e -> result.put(e.getKey(), decode(e.getValue(), Reading.class)));
    return IntegrationContracts.readings(result);
  }

  private static Effect effect(JsonNode n) {
    return switch (Json.text(n, "kind")) {
      case "pipeline.start" -> {
        Json.fields(n, "kind", "route", "input", "key");
        yield new Pipeline(Json.text(n, "route"), evidence(n.path("input")), Json.text(n, "key"));
      }
      case "action" -> {
        Json.fields(n, "kind", "binding", "action", "parameters", "key");
        Arguments a =
            arguments(
                Json.object()
                    .put("action", Json.text(n, "action"))
                    .set("parameters", n.path("parameters")));
        yield new Action(
            optionalText(n, "binding"), a.action(), a.parameters(), Json.text(n, "key"));
      }
      case "read" -> {
        Json.fields(n, "kind", "binding", "entities", "key");
        Arguments a = arguments(Json.object().set("entities", n.path("entities")));
        yield new Read(optionalText(n, "binding"), a.entities(), Json.text(n, "key"));
      }
      default -> throw new IllegalArgumentException("unsupported effect family");
    };
  }

  static ObjectNode effectTree(Effect e) {
    return switch (e) {
      case Pipeline v ->
          Json.object()
              .put("kind", "pipeline.start")
              .put("route", v.route())
              .put("key", v.key())
              .set("input", evidenceTree(v.input()));
      case Action v -> {
        ObjectNode n =
            Json.object().put("kind", "action").put("action", v.action()).put("key", v.key());
        if (v.binding() != null) n.put("binding", v.binding());
        n.set("parameters", tree(v.parameters()));
        yield n;
      }
      case Read v -> {
        ObjectNode n = Json.object().put("kind", "read").put("key", v.key());
        if (v.binding() != null) n.put("binding", v.binding());
        n.set("entities", tree(v.entities()));
        yield n;
      }
    };
  }

  static Long optionalLong(JsonNode n, String field) {
    return n.has(field) ? requiredLong(n, field) : null;
  }

  static long requiredLong(JsonNode n, String field) {
    if (!n.path(field).isIntegralNumber() || !n.path(field).canConvertToLong())
      throw new IllegalArgumentException("integral field required");
    return n.get(field).longValue();
  }

  static boolean flag(JsonNode n, String field, boolean absent) {
    if (!n.has(field)) return absent;
    if (!n.get(field).isBoolean()) throw new IllegalArgumentException("boolean field required");
    return n.get(field).booleanValue();
  }

  static String optionalText(JsonNode n, String field) {
    if (!n.has(field)) return null;
    if (!n.get(field).isTextual()) throw new IllegalArgumentException("text field required");
    return n.get(field).textValue();
  }
}
