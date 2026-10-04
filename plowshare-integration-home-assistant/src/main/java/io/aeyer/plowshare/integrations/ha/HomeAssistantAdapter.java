package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.aeyer.plowshare.integrations.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.*;

/** HA protocol implementation only. The integration runtime owns routing and receipts. */
public final class HomeAssistantAdapter implements IntegrationAdapter {
  private record Entity(String id, Set<String> attributes) {}

  private final HttpUrl endpoint;
  private final String token;
  private final Duration timeout;
  private final OkHttpClient http;
  private final Map<String, Entity> entities = new LinkedHashMap<>();
  private final Map<String, String> aliases = new HashMap<>();
  private final Set<String> subscriptions = new HashSet<>();
  private final Map<String, JsonNode> actions = new HashMap<>();
  private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final Map<String, JsonNode> states = new LinkedHashMap<>();
  private final List<JsonNode> buffered = new ArrayList<>();
  private volatile WebSocket socket;
  private volatile boolean connected, closed, snapshotting;
  private volatile long nextConnect;
  private volatile String epoch = UUID.randomUUID().toString();
  private Consumer<Observation> listener = ignored -> {};
  private static final int MAX_FRAME = 1024 * 1024;

  public HomeAssistantAdapter(JsonNode configuration, String token, Duration timeout) {
    Json.fields(configuration, "endpoint", "tokenEnv", "entities", "actions", "subscriptions");
    this.token = Objects.requireNonNull(token);
    this.timeout = timeout;
    if (token.isBlank() || timeout.isZero() || timeout.isNegative())
      throw new IllegalArgumentException("positive deadline and HA token required");
    HttpUrl base = HttpUrl.parse(Json.text(configuration, "endpoint"));
    if (base == null
        || !base.encodedPath().equals("/")
        || base.query() != null
        || base.fragment() != null
        || !base.username().isEmpty()
        || !base.password().isEmpty())
      throw new IllegalArgumentException(
          "HA requires an HTTP(S) origin without credentials/path/query/fragment");
    endpoint = base.newBuilder().addPathSegments("api/websocket").build();
    http =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ZERO)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    if (!configuration.path("entities").isObject() || configuration.path("entities").size() > 256)
      throw new IllegalArgumentException("bounded entities object required");
    configuration
        .path("entities")
        .fields()
        .forEachRemaining(
            e -> {
              Configuration.name(e.getKey());
              Json.fields(e.getValue(), "entity", "attributes");
              String id = Json.text(e.getValue(), "entity");
              if (!id.matches("[a-z0-9_]+\\.[a-z0-9_]+"))
                throw new IllegalArgumentException("invalid HA entity");
              Set<String> attrs = new HashSet<>();
              attrs.add("unit_of_measurement");
              if (!e.getValue().path("attributes").isArray())
                throw new IllegalArgumentException("attribute array required");
              for (JsonNode attr : e.getValue().path("attributes")) {
                if (!attr.isTextual())
                  throw new IllegalArgumentException("attribute name required");
                attrs.add(attr.asText());
              }
              entities.put(e.getKey(), new Entity(id, Set.copyOf(attrs)));
              if (aliases.put(id, e.getKey()) != null)
                throw new IllegalArgumentException("duplicate entity aliases");
            });
    if (!configuration.path("subscriptions").isArray())
      throw new IllegalArgumentException("selected subscriptions array required");
    for (JsonNode alias : configuration.path("subscriptions")) {
      if (!alias.isTextual() || !entities.containsKey(alias.asText()))
        throw new IllegalArgumentException("subscription must name readable alias");
      subscriptions.add(alias.asText());
    }
    if (!configuration.path("actions").isObject() || configuration.path("actions").size() > 128)
      throw new IllegalArgumentException("bounded actions object required");
    configuration
        .path("actions")
        .fields()
        .forEachRemaining(
            e -> {
              Configuration.name(e.getKey());
              JsonNode action = e.getValue();
              Json.fields(action, "service", "targets", "parameters", "required", "returnResponse");
              if (action.has("returnResponse") && !action.path("returnResponse").isBoolean())
                throw new IllegalArgumentException("returnResponse must be boolean");
              if (!Json.text(action, "service").matches("[a-z0-9_]+\\.[a-z0-9_]+"))
                throw new IllegalArgumentException("invalid HA service");
              if (!action.path("targets").isArray() || !action.path("parameters").isObject())
                throw new IllegalArgumentException("fixed targets and parameter schemas required");
              for (JsonNode target : action.path("targets"))
                if (!target.isTextual() || !target.asText().matches("[a-z0-9_]+\\.[a-z0-9_]+"))
                  throw new IllegalArgumentException("fixed entity targets required");
              action
                  .path("parameters")
                  .fields()
                  .forEachRemaining(
                      parameter -> {
                        if (Set.of("entity_id", "device_id", "area_id", "target")
                            .contains(parameter.getKey()))
                          throw new IllegalArgumentException(
                              "target parameters cannot be supplied" + " by caller");
                        Json.fields(
                            parameter.getValue(),
                            "type",
                            "minimum",
                            "maximum",
                            "maxLength",
                            "enum");
                        if (!Set.of("string", "number", "integer", "boolean")
                            .contains(Json.text(parameter.getValue(), "type")))
                          throw new IllegalArgumentException("unsupported parameter type");
                        for (String bound : List.of("minimum", "maximum"))
                          if (parameter.getValue().has(bound)
                              && !parameter.getValue().path(bound).isNumber())
                            throw new IllegalArgumentException("numeric parameter bound required");
                        if (parameter.getValue().has("maxLength")
                            && (!parameter.getValue().path("maxLength").isIntegralNumber()
                                || parameter.getValue().path("maxLength").asInt() < 0
                                || parameter.getValue().path("maxLength").asInt() > 65536))
                          throw new IllegalArgumentException("invalid string bound");
                        if (parameter.getValue().has("enum")
                            && !parameter.getValue().path("enum").isArray())
                          throw new IllegalArgumentException("enum array required");
                      });
              if (!action.path("required").isArray())
                throw new IllegalArgumentException("required parameter array required");
              for (JsonNode required : action.path("required"))
                if (!required.isTextual() || !action.path("parameters").has(required.asText()))
                  throw new IllegalArgumentException("unknown required parameter");
              actions.put(e.getKey(), action.deepCopy());
            });
  }

  @Override
  public void start(Consumer<Observation> listener) throws IOException {
    this.listener = Objects.requireNonNull(listener);
    connect();
  }

  private void connect() throws IOException {
    if (closed) throw new IOException("HA adapter closed");
    final String connectionEpoch = UUID.randomUUID().toString();
    synchronized (this) {
      epoch = connectionEpoch;
      buffered.clear();
      snapshotting = true;
    }
    CompletableFuture<Void> authenticated = new CompletableFuture<>();
    WebSocket made =
        http.newWebSocket(
            new Request.Builder().url(endpoint).build(),
            new WebSocketListener() {
              @Override
              public void onMessage(WebSocket ws, String text) {
                if (!epoch.equals(connectionEpoch) || closed) return;
                try {
                  if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_FRAME)
                    throw new IOException("HA frame exceeds limit");
                  JsonNode frame = Json.MAPPER.readTree(text);
                  String type = frame.path("type").asText();
                  if (type.equals("auth_required")) {
                    ws.send(
                        Json.object().put("type", "auth").put("access_token", token).toString());
                  } else if (type.equals("auth_ok")) {
                    connected = true;
                    authenticated.complete(null);
                  } else if (type.equals("auth_invalid")) {
                    authenticated.completeExceptionally(
                        new IOException("HA authentication refused"));
                    ws.cancel();
                  } else if (type.equals("result")) {
                    if (!frame.path("id").isIntegralNumber() || !frame.path("success").isBoolean())
                      throw new IOException("malformed HA result");
                    CompletableFuture<JsonNode> p = pending.get(frame.path("id").asInt());
                    if (p != null) p.complete(frame);
                  } else if (type.equals("event")) event(frame.path("event").path("data"));
                } catch (IOException | RuntimeException failed) {
                  authenticated.completeExceptionally(new IOException("HA protocol unavailable"));
                  lost(connectionEpoch);
                  ws.cancel();
                }
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                lost(connectionEpoch);
                ws.close(code, null);
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                authenticated.completeExceptionally(new IOException("HA disconnected"));
                lost(connectionEpoch);
              }

              @Override
              public void onFailure(WebSocket ws, Throwable failure, Response response) {
                authenticated.completeExceptionally(new IOException("HA connection unavailable"));
                lost(connectionEpoch);
              }
            });
    socket = made;
    try {
      await(authenticated);
      command(Json.object().put("type", "subscribe_events").put("event_type", "state_changed"));
      JsonNode initial = command(Json.object().put("type", "get_states"));
      if (!initial.isArray()) throw new IOException("HA states result must be an array");
      synchronized (this) {
        if (!connected) throw new IOException("HA disconnected during snapshot");
        states.clear();
        for (JsonNode state : initial) {
          String alias = aliases.get(state.path("entity_id").asText());
          if (alias != null) update(alias, state, true, false);
        }
        for (JsonNode delta : buffered) {
          String alias = aliases.get(delta.path("entity_id").asText());
          if (alias != null) update(alias, delta.path("new_state"), true, false);
        }
        buffered.clear();
        snapshotting = false;
        for (String alias : subscriptions)
          emit(alias, states.getOrDefault(alias, missing(alias)), true);
      }
    } catch (IOException failure) {
      lost(connectionEpoch);
      snapshotting = false;
      made.cancel();
      throw failure;
    }
  }

  private synchronized void event(JsonNode data) throws IOException {
    if (!data.isObject()) throw new IOException("HA event data must be an object");
    if (!aliases.containsKey(data.path("entity_id").asText())) return;
    if (snapshotting) {
      if (buffered.size() >= 256) throw new IOException("HA snapshot buffer overflow");
      buffered.add(data.deepCopy());
      return;
    }
    apply(data);
  }

  private void apply(JsonNode data) {
    String alias = aliases.get(data.path("entity_id").asText());
    if (alias == null) return;
    JsonNode state = data.path("new_state");
    update(alias, state, false, true);
  }

  private void update(String alias, JsonNode state, boolean resync, boolean publish) {
    ObjectNode clean = state.isObject() ? project(alias, state) : missing(alias);
    JsonNode previous = states.get(alias);
    String at = clean.path("last_updated").asText();
    String old = previous == null ? "" : previous.path("last_updated").asText();
    if (!at.isBlank() && !old.isBlank()) {
      try {
        if (Instant.parse(at).isBefore(Instant.parse(old))) return;
      } catch (java.time.format.DateTimeParseException invalid) {
        clean.put("availability", "invalid_timestamp");
      }
    }
    states.put(alias, clean);
    if (publish && subscriptions.contains(alias)) emit(alias, clean, resync);
  }

  private ObjectNode project(String alias, JsonNode source) {
    if (!entities.get(alias).id().equals(source.path("entity_id").asText())
        || !source.path("state").isTextual())
      return missing(alias).put("availability", "invalid_state");
    ObjectNode value = Json.object();
    value.put("alias", alias);
    value.put("state", source.path("state").asText());
    value.put(
        "availability",
        Set.of("unknown", "unavailable").contains(source.path("state").asText())
            ? "unavailable"
            : "available");
    ObjectNode attrs = Json.object();
    for (String name : entities.get(alias).attributes())
      if (source.path("attributes").has(name))
        attrs.set(name, source.path("attributes").path(name).deepCopy());
    value.set("attributes", attrs);
    value.put("unit", attrs.path("unit_of_measurement").asText(""));
    for (String timestamp : List.of("last_changed", "last_updated")) {
      String text = source.path(timestamp).asText("");
      value.put(timestamp, text);
      try {
        Instant.parse(text);
      } catch (java.time.format.DateTimeParseException invalid) {
        value.put("availability", "invalid_timestamp");
      }
    }
    value.put("observed_at", Instant.now().toString());
    value.put("epoch", epoch);
    JsonNode ctx = source.path("context");
    if (ctx.isObject()) {
      ObjectNode context = Json.object();
      for (String key : List.of("id", "parent_id"))
        if (ctx.has(key)) context.set(key, ctx.path(key));
      value.set("context", context);
    }
    return value;
  }

  private ObjectNode missing(String alias) {
    return Json.object()
        .put("alias", alias)
        .put("availability", "missing")
        .put("observed_at", Instant.now().toString())
        .put("epoch", epoch);
  }

  private void emit(String alias, JsonNode state, boolean resync) {
    ObjectNode event = (ObjectNode) state.deepCopy();
    event.put("type", "state_changed");
    event.put("resync", resync);
    listener.accept(new Observation(UUID.randomUUID().toString(), event));
  }

  private synchronized void lost(String connectionEpoch) {
    if (!epoch.equals(connectionEpoch) || closed) return;
    if (connected) {
      connected = false;
      nextConnect = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      states.replaceAll(
          (alias, value) -> {
            ObjectNode stale = (ObjectNode) value.deepCopy();
            stale.put("availability", "stale");
            return stale;
          });
      listener.accept(
          new Observation(
              UUID.randomUUID().toString(),
              Json.object()
                  .put("type", "connection.gap")
                  .put("epoch", epoch)
                  .put("observed_at", Instant.now().toString())));
    }
    pending
        .values()
        .forEach(p -> p.completeExceptionally(new IOException("HA delivery unconfirmed")));
  }

  @Override
  public void maintain() throws IOException {
    if (!closed && !connected && System.nanoTime() >= nextConnect) {
      nextConnect = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      // Keep the runtime alive while HA is offline. Failed commands still
      // report unavailable/unknown, and snapshots retain stale evidence.
      try {
        connect();
      } catch (IOException unavailable) {
      }
    }
  }

  @Override
  public void validate(String operation, JsonNode arguments) {
    if (operation.equals("states.read")) {
      Json.fields(arguments, "entities");
      if (!arguments.path("entities").isArray()
          || arguments.path("entities").isEmpty()
          || arguments.path("entities").size() > 256)
        throw new IllegalArgumentException("selected aliases required");
      for (JsonNode alias : arguments.path("entities"))
        if (!alias.isTextual() || !entities.containsKey(alias.asText()))
          throw new IllegalArgumentException("entity alias is not permitted");
    } else if (operation.equals("actions.execute")) {
      Json.fields(arguments, "action", "parameters");
      JsonNode action = actions.get(Json.text(arguments, "action"));
      if (action == null) throw new IllegalArgumentException("action not permitted");
      JsonNode parameters = arguments.path("parameters");
      if (!parameters.isObject()) throw new IllegalArgumentException("parameter object required");
      parameters
          .fields()
          .forEachRemaining(
              e -> {
                JsonNode schema = action.path("parameters").path(e.getKey());
                if (schema.isMissingNode())
                  throw new IllegalArgumentException("parameter not permitted");
                checkParameter(schema, e.getValue());
              });
      for (JsonNode required : action.path("required"))
        if (!parameters.has(required.asText()))
          throw new IllegalArgumentException("required parameter missing");
    } else throw new IllegalArgumentException("unsupported HA operation");
  }

  private static void checkParameter(JsonNode schema, JsonNode value) {
    boolean valid =
        switch (schema.path("type").asText()) {
          case "string" -> value.isTextual();
          case "number" -> value.isNumber();
          case "integer" -> value.isIntegralNumber();
          case "boolean" -> value.isBoolean();
          default -> false;
        };
    if (!valid) throw new IllegalArgumentException("parameter type refused");
    if (value.isTextual() && value.asText().length() > schema.path("maxLength").asInt(4096))
      throw new IllegalArgumentException("parameter exceeds string bound");
    if (value.isNumber()
        && (!Double.isFinite(value.doubleValue())
            || schema.has("minimum") && value.doubleValue() < schema.path("minimum").doubleValue()
            || schema.has("maximum") && value.doubleValue() > schema.path("maximum").doubleValue()))
      throw new IllegalArgumentException("parameter exceeds numeric bound");
    if (schema.has("enum")) {
      boolean found = false;
      for (JsonNode allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
      if (!found) throw new IllegalArgumentException("parameter outside enum");
    }
  }

  @Override
  public Result execute(String operation, JsonNode arguments) throws IOException {
    validate(operation, arguments);
    if (operation.equals("states.read")) {
      if (!connected)
        return new Result(
            "FAILED", Json.object().put("diagnostic", "HA unavailable; cached states are stale"));
      JsonNode latest;
      try {
        latest = command(Json.object().put("type", "get_states"));
      } catch (IOException unavailable) {
        return new Result("FAILED", Json.object().put("diagnostic", "HA state read unavailable"));
      }
      if (!latest.isArray())
        return new Result("FAILED", Json.object().put("diagnostic", "invalid HA state response"));
      ObjectNode selected = Json.object();
      synchronized (this) {
        if (!connected)
          return new Result(
              "FAILED",
              Json.object()
                  .put(
                      "diagnostic",
                      "HA disconnected during state read; cached evidence" + " remains stale"));
        for (JsonNode requested : arguments.path("entities")) {
          String alias = requested.asText();
          JsonNode state = null;
          for (JsonNode candidate : latest)
            if (candidate.path("entity_id").asText().equals(entities.get(alias).id()))
              state = candidate;
          update(alias, state == null ? NullNode.getInstance() : state, true, false);
          selected.set(alias, states.get(alias).deepCopy());
        }
      }
      return new Result("COMPLETED", Json.object().set("states", selected));
    }
    if (!connected)
      return new Result(
          "FAILED", Json.object().put("diagnostic", "HA unavailable; action was not submitted"));
    JsonNode action = actions.get(arguments.path("action").asText());
    String[] service = action.path("service").asText().split("\\.", 2);
    ObjectNode request =
        Json.object()
            .put("type", "call_service")
            .put("domain", service[0])
            .put("service", service[1]);
    request.set("service_data", arguments.path("parameters"));
    if (action.path("returnResponse").asBoolean()) request.put("return_response", true);
    if (!action.path("targets").isEmpty())
      request.set("target", Json.object().set("entity_id", action.path("targets")));
    JsonNode acknowledgment;
    try {
      acknowledgment = command(request);
    } catch (ServiceRefused refused) {
      return new Result(
          "FAILED",
          Json.object().put("acknowledged", false).put("diagnostic", "HA service action refused"));
    } catch (IOException unconfirmed) {
      return new Result(
          "UNKNOWN",
          Json.object()
              .put("acknowledged", false)
              .put("verification", "unconfirmed")
              .put("diagnostic", "HA action delivery unconfirmed; not replayed"));
    }
    ObjectNode result = Json.object().put("acknowledged", true).put("verification", "not_observed");
    if (acknowledgment.isObject()) {
      if (acknowledgment.path("context").isObject()) {
        ObjectNode context = Json.object();
        for (String key : List.of("id", "parent_id"))
          if (acknowledgment.path("context").has(key))
            context.set(key, acknowledgment.path("context").path(key));
        result.set("context", context);
      }
      if (acknowledgment.has("response")) result.set("response", acknowledgment.path("response"));
    }
    return new Result("COMPLETED", result);
  }

  private static final class ServiceRefused extends IOException {}

  private JsonNode command(ObjectNode frame) throws IOException {
    if (!connected || pending.size() >= 64)
      throw new IOException("HA command unavailable; nothing submitted");
    int id = ids.incrementAndGet();
    frame.put("id", id);
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    pending.put(id, future);
    try {
      if (!socket.send(frame.toString())) throw new IOException("HA command send refused");
      JsonNode response = await(future);
      if (!response.path("success").asBoolean()) throw new ServiceRefused();
      return response.path("result");
    } finally {
      pending.remove(id);
    }
  }

  private <T> T await(CompletableFuture<T> future) throws IOException {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA wait interrupted");
    } catch (ExecutionException | TimeoutException failed) {
      throw new IOException("HA response unavailable");
    }
  }

  @Override
  public synchronized JsonNode snapshot() {
    ObjectNode copy = Json.object();
    states.forEach((alias, value) -> copy.set(alias, value.deepCopy()));
    return copy;
  }

  @Override
  public void close() {
    closed = true;
    connected = false;
    pending
        .values()
        .forEach(
            p ->
                p.completeExceptionally(
                    new IOException("HA adapter closed; delivery unconfirmed")));
    if (socket != null) socket.cancel();
    http.dispatcher().executorService().shutdownNow();
    http.connectionPool().evictAll();
  }
}
