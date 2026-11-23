package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.AdapterConfiguration;
import io.aeyer.plowshare.integrations.IntegrationAdapter;
import io.aeyer.plowshare.integrations.IntegrationCodec;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope;
import io.aeyer.plowshare.integrations.IntegrationContracts.Gap;
import io.aeyer.plowshare.integrations.IntegrationContracts.StateChanged;
import io.aeyer.plowshare.integrations.IntegrationResult;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Delivery;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Effect;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Guard;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.NativeRequest;
import io.aeyer.plowshare.integrations.ha.ActionExecutor.Receipt;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Connection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.MetadataSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.StateSnapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.protocol.IntegrationPayload;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** HA protocol implementation only. The integration runtime owns routing and receipts. */
public final class HomeAssistantAdapter implements IntegrationAdapter {

  private final HttpUrl endpoint;
  private final String token;
  private final Duration timeout;
  private final OkHttpClient http;
  private final WebSocket.Factory sockets;
  private final HomeAssistantObservations observations;
  private final Map<String, HomeAssistantSettings.Entity> entities = new LinkedHashMap<>();
  private final Map<String, String> aliases = new HashMap<>();
  private final Set<String> subscriptions = new HashSet<>();
  private final Map<String, HomeAssistantSettings.Action> actions = new HashMap<>();
  private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
  private final AtomicInteger ids = new AtomicInteger();
  private final Semaphore pendingSlots = new Semaphore(64);
  private final String credentialContext = UUID.randomUUID().toString();
  private final Map<String, NativeResources> resourceSources = new ConcurrentHashMap<>();
  private final ReentrantLock metadataSubscriptions = new ReentrantLock();
  private volatile String subscribedMetadataEpoch;
  // Guarded by metadataSubscriptions; at most the five declared metadata event types.
  private String metadataAttemptEpoch;
  private final Set<String> completedMetadataEvents = new HashSet<>();
  private boolean metadataSetupFailed;
  private volatile boolean capabilityDiscoveryActive;
  private long metadataVersion;
  private static final Set<String> METADATA_EVENTS =
      Set.of(
          "entity_registry_updated",
          "device_registry_updated",
          "area_registry_updated",
          "service_registered",
          "service_removed");
  private final Map<String, Reading> states = new LinkedHashMap<>();

  private record StateDelta(String alias, Reading reading) {}

  private final List<StateDelta> buffered = new ArrayList<>();
  private volatile WebSocket socket;
  private volatile boolean connected, closed, snapshotting;
  private boolean retired;
  private volatile long nextConnect;
  private volatile String epoch = UUID.randomUUID().toString();
  private Consumer<Observation> listener = ignored -> {};
  private boolean started;
  private Optional<Observation> pendingGapPublication = Optional.empty();
  private boolean observationPublicationFailed;
  private static final int MAX_FRAME = 1024 * 1024;

  public HomeAssistantAdapter(AdapterConfiguration settings, String token, Duration timeout) {
    this(settings, token, timeout, Duration.ofSeconds(2), Duration.ofSeconds(2), null);
  }

  /** Observer/journal work has its own finite callback and physical cleanup budgets. */
  public HomeAssistantAdapter(
      AdapterConfiguration settings,
      String token,
      Duration timeout,
      Duration observerBudget,
      Duration observerCleanupBudget) {
    this(settings, token, timeout, observerBudget, observerCleanupBudget, null);
  }

  HomeAssistantAdapter(
      AdapterConfiguration settings, String token, Duration timeout, WebSocket.Factory sockets) {
    this(
        settings,
        token,
        timeout,
        Duration.ofSeconds(2),
        Duration.ofSeconds(2),
        Objects.requireNonNull(sockets));
  }

  private HomeAssistantAdapter(
      AdapterConfiguration settings,
      String token,
      Duration timeout,
      Duration observerBudget,
      Duration observerCleanupBudget,
      WebSocket.Factory sockets) {
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
    this.sockets = sockets == null ? http : sockets;
    observations =
        new HomeAssistantObservations(
            event -> listener.accept(event),
            this::observationUnavailable,
            observerBudget,
            observerCleanupBudget);
    entities.putAll(configuration.entities());
    actions.putAll(configuration.actions());
    subscriptions.addAll(configuration.subscriptions());
    entities.forEach((alias, entity) -> aliases.put(entity.entity(), alias));
  }

  @Override
  public void start(Consumer<Observation> listener) throws IOException {
    synchronized (this) {
      if (started || closed) throw new IOException("HA adapter already started or closed");
      this.listener = Objects.requireNonNull(listener);
      started = true;
    }
    connect();
  }

  void connect() throws IOException {
    OperationBudget budget = OperationBudget.start(timeout);
    if (closed) throw new IOException("HA adapter closed");
    final String connectionEpoch = UUID.randomUUID().toString();
    observations.check();
    synchronized (this) {
      if (closed || pendingGapPublication.isPresent() || !observations.readyForConnection())
        throw new IOException("HA observation delivery must settle before reconnect");
      epoch = connectionEpoch;
      retired = false;
      connected = false;
      socket = null;
      metadataVersion = 0;
      resourceSources.values().forEach(source -> source.stateVersion = 0);
      subscribedMetadataEpoch = null;
      buffered.clear();
      snapshotting = true;
    }
    CompletableFuture<Void> authenticated = new CompletableFuture<>();
    WebSocket made =
        sockets.newWebSocket(
            new Request.Builder().url(endpoint).build(),
            new WebSocketListener() {
              @Override
              public void onMessage(WebSocket ws, String text) {
                if (!epoch.equals(connectionEpoch) || closed) return;
                try {
                  if (text.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME)
                    throw new IOException("HA frame exceeds limit");
                  JsonNode frame = Json.MAPPER.readTree(text);
                  String type = frame.path("type").asText();
                  if (type.equals("auth_required")) {
                    synchronized (HomeAssistantAdapter.this) {
                      if (!epoch.equals(connectionEpoch) || retired || closed) return;
                    }
                    ws.send(
                        Json.object().put("type", "auth").put("access_token", token).toString());
                  } else if (type.equals("auth_ok")) {
                    synchronized (HomeAssistantAdapter.this) {
                      if (!epoch.equals(connectionEpoch) || retired || closed) return;
                      connected = true;
                    }
                    authenticated.complete(null);
                  } else if (type.equals("auth_invalid")) {
                    authenticated.completeExceptionally(
                        new IOException("HA authentication refused"));
                    abortConnection(connectionEpoch, ws);
                  } else if (type.equals("result")) {
                    if (!frame.path("id").isIntegralNumber()
                        || !frame.path("id").canConvertToInt()
                        || frame.path("id").intValue() < 1
                        || !frame.path("success").isBoolean())
                      throw new IOException("malformed HA result");
                    CompletableFuture<JsonNode> p;
                    synchronized (HomeAssistantAdapter.this) {
                      if (!epoch.equals(connectionEpoch) || retired || closed) return;
                      p = pending.get(frame.path("id").asInt());
                    }
                    if (p != null) p.complete(frame);
                  } else if (type.equals("event")) {
                    JsonNode event = frame.path("event");
                    String eventType = event.path("event_type").asText();
                    if (eventType.isEmpty() || eventType.equals("state_changed"))
                      event(connectionEpoch, event.path("data"));
                    else if (METADATA_EVENTS.contains(eventType))
                      metadataChanged(connectionEpoch, eventType, event.path("data"));
                  }
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
    synchronized (this) {
      if (!epoch.equals(connectionEpoch) || retired || closed) {
        made.cancel();
        throw new IOException("HA connection superseded");
      }
      socket = made;
    }
    try {
      await(authenticated, budget, connectionEpoch, made);
      command(
          Json.object().put("type", "subscribe_events").put("event_type", "state_changed"), budget);
      JsonNode initial = command(Json.object().put("type", "get_states"), budget);
      if (!initial.isArray()) throw new IOException("HA states result must be an array");
      List<CompletableFuture<Void>> initialObservations = new ArrayList<>();
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
          initialObservations.add(emit(alias, states.getOrDefault(alias, missing(alias)), true));
      }
      for (CompletableFuture<Void> acknowledged : initialObservations)
        HomeAssistantObservations.await(acknowledged, budget);
    } catch (IOException | IllegalArgumentException failure) {
      lost(connectionEpoch);
      synchronized (this) {
        if (epoch.equals(connectionEpoch)) snapshotting = false;
      }
      made.cancel();
      throw new IOException("HA snapshot unavailable");
    }
  }

  private synchronized void event(String expectedEpoch, JsonNode data) throws IOException {
    if (!connected || retired || closed || !epoch.equals(expectedEpoch)) return;
    if (!data.isObject()) throw new IOException("HA event data must be an object");
    if (!data.path("entity_id").isTextual())
      throw new IOException("HA event entity identity required");
    String alias = aliases.get(data.get("entity_id").textValue());
    if (alias == null) return;
    JsonNode source = data.get("new_state");
    if (source == null) throw new IOException("HA event state required");
    String entityId = data.get("entity_id").textValue();
    resourceSources.values().stream()
        .filter(sourceView -> sourceView.observedEntities.contains(entityId))
        .forEach(
            sourceView -> sourceView.stateVersion = Math.incrementExact(sourceView.stateVersion));
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

  private void update(String alias, Reading clean, boolean resync, boolean publish)
      throws IOException {
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

  private CompletableFuture<Void> emit(String alias, Reading state, boolean resync)
      throws IOException {
    return observations.offer(
        new Observation(
            UUID.randomUUID().toString(), new EventEnvelope(new StateChanged(state, resync))));
  }

  private record Loss(List<CompletableFuture<JsonNode>> pending, Optional<Observation> gap) {}

  /**
   * Fast generation fence only; no observer, journal or transport callback runs under this lock.
   */
  private synchronized Loss fenceLoss(String connectionEpoch) {
    if (!epoch.equals(connectionEpoch) || closed) return new Loss(List.of(), Optional.empty());
    retired = true;
    Optional<Observation> gap = Optional.empty();
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
      gap =
          Optional.of(
              new Observation(
                  UUID.randomUUID().toString(), new EventEnvelope(new Gap(epoch, Instant.now()))));
      pendingGapPublication = gap;
    }
    return new Loss(List.copyOf(pending.values()), gap);
  }

  private void finishLoss(Loss loss) {
    loss.pending()
        .forEach(
            future -> future.completeExceptionally(new IOException("HA delivery unconfirmed")));
    if (loss.gap().isEmpty()) return;
    synchronized (this) {
      if (!pendingGapPublication.equals(loss.gap())) return;
      try {
        observations.offer(loss.gap().get());
        pendingGapPublication = Optional.empty();
      } catch (IOException unavailable) {
        // The original Gap stays retained in pendingGapPublication. Reconnect refuses until
        // reconciliation; no arbitrary observer is invoked to rescue this failure path.
        observationPublicationFailed = true;
      }
    }
  }

  private void lost(String connectionEpoch) {
    finishLoss(fenceLoss(connectionEpoch));
  }

  private void observationUnavailable(Observation observation) {
    String expectedEpoch =
        switch (observation.data().event()) {
          case Gap gap -> gap.epoch();
          case StateChanged state -> state.reading().epoch();
          default -> throw new IllegalArgumentException("unsupported native observation");
        };
    WebSocket active;
    synchronized (this) {
      if (!epoch.equals(expectedEpoch) || closed) return;
      active = socket;
    }
    abortConnection(expectedEpoch, active);
  }

  @Override
  public void maintain() throws IOException {
    observations.check();
    synchronized (this) {
      if (observationPublicationFailed)
        throw new IOException("HA Gap publication unavailable; reconciliation required");
    }
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
    new IntegrationPayload.Request("plowshare-integration/1", "validation", operation, arguments);
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

  private static final class ServiceRefused extends IOException {
    private final String code;

    private ServiceRefused(String code) {
      this.code = code;
    }
  }

  private JsonNode command(ObjectNode frame) throws IOException {
    return command(frame, OperationBudget.start(timeout));
  }

  /**
   * Slot waiting, send and reply share the original caller's deadline, never per-command timers.
   */
  private JsonNode command(ObjectNode frame, OperationBudget budget) throws IOException {
    return command(frame, budget, null, null);
  }

  private static final class ActionAttempt {
    private final String expectedEpoch;
    private Optional<NativeRequest> identity = Optional.empty();
    private boolean possibleSend;

    private ActionAttempt(String expectedEpoch) {
      this.expectedEpoch = expectedEpoch;
    }
  }

  private JsonNode command(
      ObjectNode frame, OperationBudget budget, Guard guard, ActionAttempt attempt)
      throws IOException {
    observations.check();
    boolean acquired;
    try {
      acquired = pendingSlots.tryAcquire(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA command admission interrupted");
    }
    if (!acquired)
      throw new IOException("HA command admission deadline expired; nothing submitted");
    int id = 0;
    try {
      budget.remaining();
      // This guard is local/nonblocking and runs outside the adapter monitor. Slot waiting has
      // already consumed the original budget; no nested network read or policy refresh is allowed.
      if (guard != null) guard.require();
      budget.remaining();
      if (attempt != null && Thread.currentThread().isInterrupted())
        throw new IOException("HA action admission interrupted; nothing submitted");
      CompletableFuture<JsonNode> future = new CompletableFuture<>();
      WebSocket sending;
      String sendingEpoch;
      synchronized (this) {
        observations.check();
        if (!connected || retired || closed)
          throw new IOException("HA command unavailable; nothing submitted");
        if (attempt != null && !attempt.expectedEpoch.equals(epoch))
          throw new IOException("HA action connection generation changed; nothing submitted");
        id = ids.incrementAndGet();
        if (id < 1) throw new IOException("HA command identity limit exhausted; nothing submitted");
        frame.put("id", id);
        sending = socket;
        sendingEpoch = epoch;
        if (attempt != null) attempt.identity = Optional.of(new NativeRequest(sendingEpoch, id));
        pending.put(id, future);
      }
      budget.remaining();
      if (attempt != null && Thread.currentThread().isInterrupted())
        throw new IOException("HA action transmission interrupted; nothing submitted");
      // Conservatively mark possible transmission before calling the native client. Neither a
      // thrown send nor closure/timeout afterwards establishes that HA did not perform the action.
      if (attempt != null && sending != null) attempt.possibleSend = true;
      if (sending == null || !sending.send(frame.toString()))
        throw new IOException("HA command send refused");
      JsonNode response = await(future, budget, sendingEpoch, sending);
      budget.remaining();
      if (!response.path("success").booleanValue())
        throw new ServiceRefused(response.path("error").path("code").asText());
      return response.path("result");
    } finally {
      if (id > 0) pending.remove(id);
      pendingSlots.release();
    }
  }

  private <T> T await(
      CompletableFuture<T> future, OperationBudget budget, String expectedEpoch, WebSocket active)
      throws IOException {
    try {
      T value = future.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
      budget.remaining();
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      abortConnection(expectedEpoch, active);
      throw new IOException("HA wait interrupted; delivery unconfirmed");
    } catch (TimeoutException expired) {
      // HA has no per-request cancellation command. Closing this shared connection is the
      // supported abort; every other possibly submitted command becomes uncertain, never replayed.
      abortConnection(expectedEpoch, active);
      throw new IOException("HA response deadline expired; delivery unconfirmed");
    } catch (ExecutionException failed) {
      throw new IOException("HA response unavailable");
    } catch (IOException expired) {
      if (!future.isDone()) abortConnection(expectedEpoch, active);
      throw expired;
    }
  }

  // Cancellation owns the captured attempt, never whichever connection happens to be current.
  void abortConnection(String expectedEpoch, WebSocket active) {
    Loss loss = fenceLoss(expectedEpoch);
    try {
      if (active != null) active.cancel();
    } finally {
      finishLoss(loss);
    }
  }

  private synchronized void metadataChanged(String current, String eventType, JsonNode data) {
    if (!connected || retired || !epoch.equals(current)) return;
    if (eventType.equals("entity_registry_updated")
        && !capabilityDiscoveryActive
        && !aliases.containsKey(data.path("entity_id").asText())) return;
    if (eventType.equals("service_registered") || eventType.equals("service_removed")) {
      String service = data.path("domain").asText() + "." + data.path("service").asText();
      if (actions.values().stream().noneMatch(action -> action.service().equals(service))) return;
    }
    // Device/area relationships can affect a selected entity indirectly. Their event is an
    // invalidation hint, not public metadata. Opaque tokens never expose a count of private events.
    metadataVersion = nextMetadataVersion();
  }

  private long nextMetadataVersion() {
    long next = ThreadLocalRandom.current().nextLong(1, 1L << 53);
    return next == metadataVersion ? next - 1 : next;
  }

  /**
   * Share the configured native connection with discovery/provider composition. A new adapter
   * instance always supplies a fresh opaque credential generation, including token rotation.
   */
  public synchronized HomeAssistantResourceSource resourceSource(String bindingName) {
    Binding binding = new Binding(bindingName, credentialContext);
    NativeResources existing = resourceSources.get(bindingName);
    if (existing != null) return existing;
    if (resourceSources.size() >= 128)
      throw new IllegalArgumentException("HA resource source bound reached");
    NativeResources created = new NativeResources(binding);
    resourceSources.put(bindingName, created);
    return created;
  }

  /**
   * A separate opt-in read projection over this same native owner. The capability source borrows
   * the socket, owns its identity store, and never changes the legacy fixed action targets.
   */
  HomeAssistantCapabilitySource capabilitySource(
      HomeAssistantProviderConfiguration.Discovery policy,
      HomeAssistantSettings settings,
      HomeAssistantCapabilityIdentityStore identities) {
    requireCapabilitySettings(settings);
    capabilityDiscoveryActive = true;
    return new NativeCapabilities(policy, settings, identities);
  }

  void requireCapabilitySettings(HomeAssistantSettings settings) {
    HttpUrl configured =
        Objects.requireNonNull(HttpUrl.parse(settings.endpoint()))
            .newBuilder()
            .addPathSegments("api/websocket")
            .build();
    if (!configured.equals(endpoint)
        || !settings.entities().equals(entities)
        || !settings.actions().equals(actions)
        || !Set.copyOf(settings.subscriptions()).equals(subscriptions))
      throw new IllegalArgumentException("Capability settings do not belong to this HA source");
  }

  private final class NativeCapabilities implements HomeAssistantCapabilitySource {
    private final HomeAssistantProviderConfiguration.Discovery policy;
    private final HomeAssistantSettings settings;
    private final HomeAssistantCapabilityIdentityStore identities;
    private final ReentrantLock collection = new ReentrantLock();
    private volatile boolean closing;

    NativeCapabilities(
        HomeAssistantProviderConfiguration.Discovery policy,
        HomeAssistantSettings settings,
        HomeAssistantCapabilityIdentityStore identities) {
      this.policy = Objects.requireNonNull(policy);
      this.settings = Objects.requireNonNull(settings);
      this.identities = Objects.requireNonNull(identities);
    }

    @Override
    public Snapshot snapshot(OperationBudget budget) throws IOException {
      if (closing || collection.isHeldByCurrentThread())
        throw new IOException("Capability collection is unavailable");
      boolean acquired;
      try {
        acquired = collection.tryLock(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("Capability collection interrupted");
      }
      if (!acquired) throw new IOException("Capability collection budget expired");
      try {
        if (closing) throw new IOException("Capability collection is closing");
        String before;
        long metadataBefore;
        synchronized (HomeAssistantAdapter.this) {
          if (!connected || retired || closed || snapshotting)
            throw new IOException("HA capability connection unavailable");
          before = epoch;
        }
        subscribeMetadata(before, budget);
        Set<String> selected = selection(budget);
        // Canonical exposure lookup may materialize defaults and emit registry updates. Those
        // events establish this observation's baseline rather than invalidating its own first read.
        synchronized (HomeAssistantAdapter.this) {
          if (!connected || !epoch.equals(before))
            throw new IOException("HA capability source changed");
          metadataBefore = metadataVersion;
        }
        Registry entities = registry("config/entity_registry/list", budget);
        if (entities.availability() != RegistryAvailability.AVAILABLE)
          throw new IOException("HA registry identity capability unavailable");
        JsonNode withAliases;
        try {
          Set<String> locators =
              HomeAssistantCapabilityWire.selectedLocators(
                  policy, settings, identities, entities.data(), selected);
          if (locators.isEmpty()) withAliases = entities.data();
          else {
            var request = Json.object().put("type", "config/entity_registry/get_entries");
            var ids = request.putArray("entity_ids");
            locators.stream().sorted().forEach(ids::add);
            withAliases =
                HomeAssistantCapabilityWire.withAliases(
                    entities.data(), command(request, budget), locators);
          }
        } catch (IllegalArgumentException malformed) {
          throw new IOException("HA selected alias capability unavailable");
        }
        Registry devices = registry("config/device_registry/list", budget);
        Registry areas = registry("config/area_registry/list", budget);
        Registry configuration = registry("get_config", budget);
        if (configuration.availability() == RegistryAvailability.AVAILABLE
            && !configuration.data().isObject())
          throw new IOException("HA unit configuration unavailable");
        JsonNode states = command(Json.object().put("type", "get_states"), budget);
        // Climate values in this HA baseline use installation units without carrying a unit on the
        // state. Recheck the unit projection after values so a concurrent unit change cannot label
        // a Fahrenheit value as Celsius when config-event subscriptions are unavailable.
        Registry afterConfiguration = registry("get_config", budget);
        if (configuration.availability() != afterConfiguration.availability()
            || !configuration
                .data()
                .path("unit_system")
                .equals(afterConfiguration.data().path("unit_system")))
          throw new IOException("HA measurement units changed during collection");
        // Metadata subscriptions may be refused by HA. A second registry projection is a deliberate
        // identity-freshness check after the state batch, not a per-entity refetch or a retry.
        Registry afterEntities = registry("config/entity_registry/list", budget);
        if (afterEntities.availability() != RegistryAvailability.AVAILABLE
            || !entities.data().equals(afterEntities.data())
            || !selected.equals(selection(budget)))
          throw new IOException("HA capability identity or selection changed during collection");
        requireEpoch(before, metadataBefore);
        Snapshot result;
        try {
          result =
              HomeAssistantCapabilityWire.snapshot(
                  before,
                  policy,
                  settings,
                  identities,
                  states,
                  withAliases,
                  devices.data(),
                  areas.data(),
                  configuration.data(),
                  selected,
                  Instant.now(),
                  budget);
        } catch (IllegalArgumentException malformed) {
          throw new IOException("HA capability collection contains invalid selected data");
        }
        budget.remaining();
        requireEpoch(before, metadataBefore);
        return result;
      } finally {
        collection.unlock();
      }
    }

    private Set<String> selection(OperationBudget budget) throws IOException {
      if (policy.mode().equals("fixed")) return Set.of();
      try {
        return HomeAssistantCapabilityWire.exposed(
            command(Json.object().put("type", "plowshare/discovery/selection"), budget));
      } catch (ServiceRefused | IllegalArgumentException malformed) {
        throw new IOException("HA effective exposure capability unavailable");
      }
    }

    private void requireEpoch(String expected, long metadata) throws IOException {
      synchronized (HomeAssistantAdapter.this) {
        if (!connected
            || retired
            || closed
            || snapshotting
            || !epoch.equals(expected)
            || metadataVersion != metadata)
          throw new IOException("HA capability source changed during collection");
      }
    }

    @Override
    public void close() throws IOException {
      closing = true;
      if (!collection.tryLock())
        throw new IOException("Capability collection still owns physical work");
      try {
        identities.close();
      } finally {
        collection.unlock();
      }
    }
  }

  /**
   * One action attempt over the same explicitly configured native connection. The returned executor
   * is credential/binding isolated and accepts only the exact original configured profile. Its
   * caller owns durable SDK intent and the final local offer/revocation guard.
   */
  public ActionExecutor actionExecutor(String bindingName) {
    Binding binding = resourceSource(bindingName).binding();
    return (request, guard, budget) -> executeAction(binding, request, guard, budget);
  }

  private Receipt executeAction(
      Binding binding, ActionExecutor.Request request, Guard guard, OperationBudget budget)
      throws IOException {
    Objects.requireNonNull(request);
    Objects.requireNonNull(guard);
    Objects.requireNonNull(budget);
    var offered = request.action();
    if (!binding.equals(offered.revision().binding())
        || !offered.profile().equals(actions.get(offered.alias())))
      throw new IllegalArgumentException(
          "HA action does not match the configured credential/profile");
    offered.profile().validate(request.parameters());
    String fingerprint = HomeAssistantActionProfiles.fingerprint(offered.profile());
    String[] service = offered.profile().service().split("\\.", 2);
    ObjectNode frame =
        Json.object()
            .put("type", "call_service")
            .put("domain", service[0])
            .put("service", service[1]);
    frame.set("service_data", IntegrationCodec.tree(request.parameters()));
    frame.set(
        "target",
        Json.object().set("entity_id", IntegrationCodec.tree(offered.profile().targets())));
    ActionAttempt attempt = new ActionAttempt(offered.revision().epoch());
    Delivery delivery;
    Optional<ActionContext> context = Optional.empty();
    try {
      JsonNode acknowledgement = command(frame, budget, guard, attempt);
      context = HomeAssistantWire.actionContext(acknowledgement);
      delivery = Delivery.ACKNOWLEDGED;
    } catch (ServiceRefused refused) {
      // HA service implementations may have produced partial effects before reporting failure.
      delivery = Delivery.REFUSED;
    } catch (IOException | RuntimeException uncertain) {
      delivery = attempt.possibleSend ? Delivery.UNKNOWN : Delivery.NOT_SUBMITTED;
    }
    Effect effect =
        delivery == Delivery.ACKNOWLEDGED
            ? Effect.NOT_OBSERVED
            : delivery == Delivery.NOT_SUBMITTED ? Effect.NOT_STARTED : Effect.UNKNOWN;
    return new Receipt(
        request.invocationId(),
        binding,
        offered.alias(),
        fingerprint,
        offered.profile().targets(),
        delivery,
        effect,
        attempt.identity,
        context,
        Instant.now());
  }

  private final class NativeResources implements HomeAssistantResourceSource {
    private final Binding binding;
    private Set<String> observedEntities = Set.of();
    private long stateVersion;

    private NativeResources(Binding binding) {
      this.binding = binding;
    }

    @Override
    public Binding binding() {
      return binding;
    }

    @Override
    public Connection connection() {
      synchronized (HomeAssistantAdapter.this) {
        return new Connection(
            epoch,
            metadataVersion,
            stateVersion,
            connected && !retired && !closed && !snapshotting);
      }
    }

    // The configured alias fixes entity targets. Attribute/metadata disclosure belongs to the
    // catalogue's separate trusted operator policy; fromSettings retains the legacy ceiling.
    // This seam never accepts model/tool-request attribute lists or a caller-provided policy.
    private void validateSelections(List<Selection> selections) {
      if (selections.size() > HomeAssistantResources.MAX_RESOURCES
          || selections.stream().map(Selection::reference).distinct().count() != selections.size())
        throw new IllegalArgumentException("invalid HA source selection");
      for (Selection selection : selections) {
        HomeAssistantSettings.Entity configured = entities.get(selection.reference().alias());
        if (!binding.equals(selection.reference().binding())
            || configured == null
            || !configured.entity().equals(selection.entityId()))
          throw new IllegalArgumentException("HA source selection is not available");
      }
    }

    @Override
    public MetadataSnapshot metadata(List<Selection> selections, OperationBudget budget)
        throws IOException {
      selections = List.copyOf(selections);
      validateSelections(selections);
      budget.remaining();
      Connection before = connection();
      if (!before.connected()) throw new IOException("HA resource connection unavailable");
      if (selections.isEmpty()) return new MetadataSnapshot(before, List.of());
      synchronized (HomeAssistantAdapter.this) {
        Set<String> selected =
            selections.stream().map(Selection::entityId).collect(Collectors.toUnmodifiableSet());
        if (!observedEntities.equals(selected)) stateVersion = 0;
        observedEntities = selected;
      }
      subscribeMetadata(before.epoch(), budget);
      Registry entities = registry("config/entity_registry/list", budget);
      Registry devices = registry("config/device_registry/list", budget);
      Registry areas = registry("config/area_registry/list", budget);
      Registry services = registry("get_services", budget);
      if (services.availability() == RegistryAvailability.AVAILABLE && !services.data().isObject())
        throw new IOException("HA service catalogue object required");
      Registries availability =
          new Registries(
              entities.availability(),
              devices.availability(),
              areas.availability(),
              services.availability());
      try {
        var descriptions =
            HomeAssistantWire.resourceDescriptions(
                selections, entities.data(), devices.data(), areas.data(), availability);
        budget.remaining();
        Connection after = connection();
        if (!after.connected()
            || !before.epoch().equals(after.epoch())
            || before.metadataVersion() != after.metadataVersion())
          throw new IOException("HA metadata changed during collection");
        return new MetadataSnapshot(after, descriptions);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("HA selected metadata invalid");
      }
    }

    @Override
    public StateSnapshot read(List<Selection> selections, OperationBudget budget)
        throws IOException {
      selections = List.copyOf(selections);
      validateSelections(selections);
      budget.remaining();
      Connection before = connection();
      if (!before.connected()) throw new IOException("HA resource connection unavailable");
      if (selections.isEmpty()) throw new IllegalArgumentException("HA read selection is empty");
      JsonNode states = command(Json.object().put("type", "get_states"), budget);
      try {
        var projected = HomeAssistantWire.resourceStates(selections, states, Instant.now());
        budget.remaining();
        if (!before.equals(connection()))
          throw new IOException("HA state changed during collection");
        return new StateSnapshot(before, projected);
      } catch (IllegalArgumentException invalid) {
        throw new IOException("HA selected state invalid");
      }
    }
  }

  /** Vendor values remain inside the native transport/codec boundary. */
  private record Registry(RegistryAvailability availability, JsonNode data) {}

  private Registry registry(String command, OperationBudget budget) throws IOException {
    try {
      return new Registry(
          RegistryAvailability.AVAILABLE, command(Json.object().put("type", command), budget));
    } catch (ServiceRefused refused) {
      RegistryAvailability availability;
      if (Set.of("unauthorized", "not_allowed").contains(refused.code))
        availability = RegistryAvailability.DENIED;
      else if (Set.of("unknown_command", "not_found").contains(refused.code))
        availability = RegistryAvailability.UNSUPPORTED;
      else throw new IOException("HA metadata capability probe refused");
      return new Registry(availability, Json.MAPPER.createArrayNode());
    }
  }

  private void subscribeMetadata(String expectedEpoch, OperationBudget budget) throws IOException {
    boolean acquired;
    try {
      acquired = metadataSubscriptions.tryLock(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("HA metadata subscription interrupted");
    }
    if (!acquired) throw new IOException("HA metadata subscription deadline expired");
    try {
      if (expectedEpoch.equals(subscribedMetadataEpoch)) return;
      WebSocket attempt;
      synchronized (this) {
        if (!expectedEpoch.equals(epoch) || !connected)
          throw new IOException("HA connection changed");
        attempt = socket;
      }
      if (!expectedEpoch.equals(metadataAttemptEpoch)) {
        metadataAttemptEpoch = expectedEpoch;
        completedMetadataEvents.clear();
        metadataSetupFailed = false;
      }
      if (metadataSetupFailed) throw new IOException("HA metadata setup fenced until reconnect");
      for (String event : METADATA_EVENTS) {
        if (completedMetadataEvents.contains(event)) continue;
        budget.remaining();
        try {
          command(Json.object().put("type", "subscribe_events").put("event_type", event), budget);
        } catch (ServiceRefused refused) {
          if (!Set.of("unauthorized", "not_allowed", "unknown_command", "not_found")
              .contains(refused.code)) {
            metadataSetupFailed = true;
            abortConnection(expectedEpoch, attempt);
            throw new IOException("HA metadata subscription refused");
          }
          // Known unsupported/denied subscriptions are settled. Maximum metadata age bounds
          // reuse; this never broadens to a wildcard/admin subscription.
        } catch (IOException unconfirmed) {
          metadataSetupFailed = true;
          abortConnection(expectedEpoch, attempt);
          throw unconfirmed;
        }
        completedMetadataEvents.add(event);
      }
      budget.remaining();
      if (!expectedEpoch.equals(epoch) || !connected)
        throw new IOException("HA connection changed");
      subscribedMetadataEpoch = expectedEpoch;
    } finally {
      metadataSubscriptions.unlock();
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
    observations.close();
    http.dispatcher().executorService().shutdownNow();
    http.connectionPool().evictAll();
  }
}
