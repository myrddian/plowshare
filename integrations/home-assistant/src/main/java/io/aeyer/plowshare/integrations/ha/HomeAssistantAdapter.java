package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.aeyer.plowshare.integrations.*;
import io.aeyer.plowshare.integrations.IntegrationContracts.*;
import io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import okhttp3.*;

/** HA protocol implementation only. The integration runtime owns routing and receipts. */
public final class HomeAssistantAdapter implements IntegrationAdapter {

  private final HttpUrl endpoint;
  private final String token;
  private final Duration timeout;
  private final OkHttpClient http;
  private final Map<String, HomeAssistantSettings.Entity> entities = new LinkedHashMap<>();
  private final Map<String, String> aliases = new HashMap<>();
  private final Set<String> subscriptions = new HashSet<>();
  private final Map<String, HomeAssistantSettings.Action> actions = new HashMap<>();
  private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final Map<String, Reading> states = new LinkedHashMap<>();

  private record StateDelta(String alias, Reading reading) {}

  private final List<StateDelta> buffered = new ArrayList<>();
  private volatile WebSocket socket;
  private volatile boolean connected, closed, snapshotting;
  private volatile long nextConnect;
  private volatile String epoch = UUID.randomUUID().toString();
  private Consumer<Observation> listener = ignored -> {};
  private static final int MAX_FRAME = 1024 * 1024;

  public HomeAssistantAdapter(AdapterConfiguration settings, String token, Duration timeout) {
    if (!(settings instanceof HomeAssistantSettings configuration))
      throw new IllegalArgumentException("HA settings required");
    this.token = Objects.requireNonNull(token);
    this.timeout = Objects.requireNonNull(timeout);
    if (token.isBlank()
        || token.length() > 16384
        || token.chars().anyMatch(Character::isISOControl)
        || timeout.isZero()
        || timeout.isNegative())
      throw new IllegalArgumentException("positive deadline and valid HA token required");
    HttpUrl base = Objects.requireNonNull(HttpUrl.parse(configuration.endpoint()));
    endpoint = base.newBuilder().addPathSegments("api/websocket").build();
    http =
        new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ZERO)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    entities.putAll(configuration.entities());
    actions.putAll(configuration.actions());
    subscriptions.addAll(configuration.subscriptions());
    entities.forEach((alias, entity) -> aliases.put(entity.entity(), alias));
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
            new okhttp3.Request.Builder().url(endpoint).build(),
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
                    if (!frame.path("id").isIntegralNumber()
                        || !frame.path("id").canConvertToInt()
                        || frame.path("id").intValue() < 1
                        || !frame.path("success").isBoolean())
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
        if (!connected || !epoch.equals(connectionEpoch))
          throw new IOException("HA disconnected during snapshot");
        Map<String, Reading> projected = projectAll(initial);
        // Validate the complete vendor batch before publishing any new cache evidence.
        states.clear();
        for (var value : projected.entrySet())
          update(value.getKey(), value.getValue(), true, false);
        for (StateDelta delta : buffered) update(delta.alias(), delta.reading(), true, false);
        buffered.clear();
        snapshotting = false;
        for (String alias : subscriptions)
          emit(alias, states.getOrDefault(alias, missing(alias)), true);
      }
    } catch (IOException | IllegalArgumentException failure) {
      lost(connectionEpoch);
      snapshotting = false;
      made.cancel();
      throw new IOException("HA snapshot unavailable");
    }
  }

  private synchronized void event(JsonNode data) throws IOException {
    if (!data.isObject()) throw new IOException("HA event data must be an object");
    if (!data.path("entity_id").isTextual())
      throw new IOException("HA event entity identity required");
    String alias = aliases.get(data.get("entity_id").textValue());
    if (alias == null) return;
    JsonNode source = data.get("new_state");
    if (source == null) throw new IOException("HA event state required");
    Reading clean = source.isNull() ? missing(alias) : project(alias, source);
    if (snapshotting) {
      if (buffered.size() >= 256) throw new IOException("HA snapshot buffer overflow");
      buffered.add(new StateDelta(alias, clean));
      return;
    }
    update(alias, clean, false, true);
  }

  private Map<String, Reading> projectAll(JsonNode source) throws IOException {
    if (!source.isArray()) throw new IOException("HA states array required");
    Map<String, Reading> result = new LinkedHashMap<>();
    for (JsonNode value : source) {
      if (!value.isObject() || !value.path("entity_id").isTextual())
        throw new IOException("HA state entity identity required");
      String alias = aliases.get(value.get("entity_id").textValue());
      if (alias != null && result.put(alias, project(alias, value)) != null)
        throw new IOException("duplicate selected HA entity");
    }
    return Map.copyOf(result);
  }

  private void update(String alias, Reading clean, boolean resync, boolean publish) {
    Reading previous = states.get(alias);
    if (clean.last_updated() != null
        && !clean.last_updated().isEmpty()
        && previous != null
        && previous.last_updated() != null
        && !previous.last_updated().isEmpty()
        && Instant.parse(clean.last_updated()).isBefore(Instant.parse(previous.last_updated())))
      return;
    states.put(alias, clean);
    if (publish && subscriptions.contains(alias)) emit(alias, clean, resync);
  }

  /** Project vendor state into the declared scalar evidence contract before it reaches policy. */
  private Reading project(String alias, JsonNode source) {
    return HomeAssistantWire.reading(alias, entities.get(alias), source, epoch, Instant.now());
  }

  private Reading missing(String alias) {
    return new Reading(
        alias,
        null,
        "missing",
        Map.of(),
        null,
        null,
        null,
        epoch,
        null,
        Instant.now().toString(),
        null);
  }

  private void emit(String alias, Reading state, boolean resync) {
    listener.accept(
        new Observation(
            UUID.randomUUID().toString(), new EventEnvelope(new StateChanged(state, resync))));
  }

  private synchronized void lost(String connectionEpoch) {
    if (!epoch.equals(connectionEpoch) || closed) return;
    if (connected) {
      connected = false;
      nextConnect = System.nanoTime() + Duration.ofSeconds(2).toNanos();
      states.replaceAll(
          (alias, value) ->
              new Reading(
                  value.alias(),
                  value.state(),
                  "stale",
                  value.attributes(),
                  value.unit(),
                  value.last_changed(),
                  value.last_updated(),
                  value.epoch(),
                  true,
                  value.observed_at(),
                  value.context()));
      listener.accept(
          new Observation(
              UUID.randomUUID().toString(), new EventEnvelope(new Gap(epoch, Instant.now()))));
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
  public void validate(String operation, Arguments arguments) {
    // Shared Request validates the discriminated operation shape before alias authorization.
    new io.aeyer.plowshare.protocol.IntegrationPayload.Request(
        "plowshare-integration/1", "validation", operation, arguments);
    if (operation.equals("states.read")) {
      for (String alias : arguments.entities())
        if (!entities.containsKey(alias))
          throw new IllegalArgumentException("entity alias is not permitted");
    } else {
      var action = actions.get(arguments.action());
      if (action == null) throw new IllegalArgumentException("action not permitted");
      action.validate(arguments.parameters());
    }
  }

  @Override
  public Result execute(String operation, Arguments arguments) throws IOException {
    validate(operation, arguments);
    if (operation.equals("states.read")) {
      if (!connected) return Result.diagnostic("FAILED", "HA unavailable; cached states are stale");
      JsonNode latest;
      String readEpoch = epoch;
      try {
        latest = command(Json.object().put("type", "get_states"));
      } catch (IOException unavailable) {
        return Result.diagnostic("FAILED", "HA state read unavailable");
      }
      synchronized (this) {
        if (!connected || !epoch.equals(readEpoch))
          return Result.diagnostic(
              "FAILED", "HA disconnected during state read; cached evidence remains stale");
        Map<String, Reading> projected;
        try {
          projected = projectAll(latest);
        } catch (IOException | IllegalArgumentException invalid) {
          return Result.diagnostic(
              "FAILED", "invalid HA state response; no selected cache updates published");
        }
        Map<String, Reading> selected = new LinkedHashMap<>();
        for (String alias : arguments.entities()) {
          update(alias, projected.getOrDefault(alias, missing(alias)), true, false);
          selected.put(alias, states.get(alias));
        }
        return new Result("COMPLETED", new IntegrationResult(selected, null, null, null, null));
      }
    }

    if (!connected) return Result.diagnostic("FAILED", "HA unavailable; action was not submitted");
    HomeAssistantSettings.Action action = actions.get(arguments.action());
    String[] service = action.service().split("\\.", 2);
    ObjectNode request =
        Json.object()
            .put("type", "call_service")
            .put("domain", service[0])
            .put("service", service[1]);
    request.set("service_data", IntegrationCodec.tree(arguments.parameters()));
    if (!action.targets().isEmpty())
      request.set(
          "target", Json.object().set("entity_id", IntegrationCodec.tree(action.targets())));
    JsonNode acknowledgment;
    try {
      acknowledgment = command(request);
    } catch (ServiceRefused refused) {
      return new Result(
          "FAILED", new IntegrationResult(null, false, null, null, "HA service action refused"));
    } catch (IOException unconfirmed) {
      return new Result(
          "UNKNOWN",
          new IntegrationResult(
              null, false, "unconfirmed", null, "HA action delivery unconfirmed; not replayed"));
    }
    try {
      return new Result("COMPLETED", HomeAssistantWire.acknowledgment(acknowledgment));
    } catch (IllegalArgumentException invalid) {
      return Result.diagnostic(
          "UNKNOWN", "HA action acknowledgment invalid; delivery unconfirmed; not replayed");
    }
  }

  private static final class ServiceRefused extends IOException {}

  private JsonNode command(ObjectNode frame) throws IOException {
    if (!connected || pending.size() >= 64)
      throw new IOException("HA command unavailable; nothing submitted");
    int id = ids.incrementAndGet();
    if (id < 1) throw new IOException("HA command identity limit exhausted; nothing submitted");
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
  public synchronized Map<String, Reading> snapshot() {
    return Map.copyOf(states);
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
