package io.aeyer.plowshare.integrations.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.IntegrationAdapter.Observation;
import io.aeyer.plowshare.integrations.IntegrationContracts.EventEnvelope;
import io.aeyer.plowshare.integrations.IntegrationContracts.Gap;
import io.aeyer.plowshare.integrations.IntegrationContracts.StateChanged;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Search;
import io.aeyer.plowshare.protocol.IntegrationPayload.Arguments;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.jupiter.api.Test;

class HomeAssistantObservationLifecycleTest {
  private static final Duration SECOND = Duration.ofSeconds(1);

  private static HomeAssistantSettings settings(boolean subscribe) {
    return new HomeAssistantSettings(
        "http://fixture.invalid",
        "FIXTURE_TOKEN",
        Map.of("first", new HomeAssistantSettings.Entity("sensor.first", List.of())),
        Map.of(
            "notify",
            new HomeAssistantSettings.Action(
                "notify.fixture", List.of(), Map.of(), List.of(), null)),
        subscribe ? List.of("first") : List.of());
  }

  private static final class FixtureSockets implements WebSocket.Factory {
    final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final CountDownLatch actionReceived = new CountDownLatch(1);
    final AtomicInteger actions = new AtomicInteger();
    final AtomicInteger reads = new AtomicInteger();
    volatile boolean hangReads;
    volatile boolean hangActions;

    @Override
    public WebSocket newWebSocket(Request request, WebSocketListener listener) {
      Socket socket = new Socket(this, request, listener, sockets.size() + 1);
      sockets.add(socket);
      listener.onMessage(socket, "{\"type\":\"auth_required\"}");
      return socket;
    }
  }

  private static final class Socket implements WebSocket {
    final FixtureSockets factory;
    final Request request;
    final WebSocketListener listener;
    final int generation;
    final AtomicInteger cancellations = new AtomicInteger();

    Socket(FixtureSockets factory, Request request, WebSocketListener listener, int generation) {
      this.factory = factory;
      this.request = request;
      this.listener = listener;
      this.generation = generation;
    }

    @Override
    public Request request() {
      return request;
    }

    @Override
    public long queueSize() {
      return 0;
    }

    @Override
    public void cancel() {
      cancellations.incrementAndGet();
    }

    @Override
    public boolean close(int code, String reason) {
      return true;
    }

    @Override
    public boolean send(ByteString bytes) {
      throw new AssertionError("text fixture only");
    }

    @Override
    public boolean send(String text) {
      try {
        JsonNode frame = Json.parse(text);
        String type = frame.path("type").asText();
        if (type.equals("auth")) {
          listener.onMessage(this, "{\"type\":\"auth_ok\"}");
          return true;
        }
        JsonNode result = Json.object();
        if (type.equals("get_states")) {
          factory.reads.incrementAndGet();
          if (factory.hangReads) return true;
          result = Json.MAPPER.createArrayNode().add(state(Integer.toString(generation)));
        } else if (type.equals("call_service")) {
          factory.actions.incrementAndGet();
          factory.actionReceived.countDown();
          if (factory.hangActions) return true;
        } else if (type.startsWith("config/")) result = Json.MAPPER.createArrayNode();
        var reply = Json.object().put("type", "result").put("success", true);
        reply.set("id", frame.path("id"));
        reply.set("result", result);
        listener.onMessage(this, reply.toString());
        return true;
      } catch (IOException invalid) {
        throw new AssertionError(invalid);
      }
    }

    void event(String value) {
      listener.onMessage(
          this,
          Json.object()
              .put("type", "event")
              .set(
                  "event",
                  Json.object()
                      .put("event_type", "state_changed")
                      .set(
                          "data",
                          Json.object()
                              .put("entity_id", "sensor.first")
                              .set("new_state", state(value))))
              .toString());
    }
  }

  private static JsonNode state(String value) {
    return Json.object()
        .put("entity_id", "sensor.first")
        .put("state", value)
        .put("last_changed", "2026-11-17T00:01:00Z")
        .put("last_updated", "2026-11-17T00:01:00Z")
        .set("attributes", Json.object());
  }

  private static void blockIgnoringInterrupts(CountDownLatch release) {
    boolean interrupted = false;
    while (true) {
      try {
        release.await();
        break;
      } catch (InterruptedException expiry) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  private static void awaitBlocked(Thread callback) {
    long deadline = System.nanoTime() + SECOND.toNanos();
    while (callback.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    assertEquals(
        Thread.State.BLOCKED,
        callback.getState(),
        "callback must reach generation mutation monitor");
  }

  @Test
  void blockedGapObserverDoesNotHoldTimeoutCancellationOrOtherPendingCommands() throws Exception {
    FixtureSockets factory = new FixtureSockets();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    try (var adapter =
            new HomeAssistantAdapter(settings(false), "fixture", Duration.ofMillis(100), factory);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      adapter.start(
          observation -> {
            if (observation.data().event() instanceof Gap) {
              entered.countDown();
              blockIgnoringInterrupts(release);
            }
          });
      Socket socket = factory.sockets.getFirst();
      HomeAssistantResourceSource source = adapter.resourceSource("blocked-gap");
      var catalog =
          new HomeAssistantResourceCatalog(
              source,
              HomeAssistantDiscoveryPolicy.fromSettings(
                  source.binding(), "policy", settings(false)),
              SECOND);
      var page =
          catalog.search(
              new Search("", Optional.empty(), Optional.empty(), 100, Optional.empty()),
              OperationBudget.start(SECOND));
      factory.hangActions = true;
      factory.hangReads = true;
      var action =
          workers.submit(
              () -> adapter.execute("actions.execute", new Arguments(null, "notify", Map.of())));
      assertTrue(factory.actionReceived.await(1, TimeUnit.SECONDS));
      var read =
          workers.submit(
              () ->
                  catalog.read(
                      new ReadRequest(
                          page.revision(), List.of(new Reference(source.binding(), "first"))),
                      OperationBudget.start(Duration.ofMillis(30))));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      ExecutionException failedRead =
          assertThrows(ExecutionException.class, () -> read.get(1, TimeUnit.SECONDS));
      assertTrue(failedRead.getCause() instanceof IOException);
      assertEquals("UNKNOWN", action.get(1, TimeUnit.SECONDS).state());
      assertTrue(socket.cancellations.get() > 0);
      assertEquals(1, factory.actions.get());
      assertEquals(2, factory.reads.get());
      release.countDown();
    } finally {
      release.countDown();
    }
  }

  @Test
  void blockedStateObserverDoesNotOwnConnectionMonitorAndGapRemainsOrdered() throws Exception {
    FixtureSockets factory = new FixtureSockets();
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        gap = new CountDownLatch(1);
    List<String> delivered = new CopyOnWriteArrayList<>();
    try (var adapter =
            new HomeAssistantAdapter(settings(true), "fixture", Duration.ofMillis(100), factory);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      adapter.start(
          observation -> {
            if (observation.data().event() instanceof StateChanged changed && !changed.resync()) {
              entered.countDown();
              blockIgnoringInterrupts(release);
              delivered.add("state");
            } else if (observation.data().event() instanceof Gap) {
              delivered.add("gap");
              gap.countDown();
            }
          });
      Socket socket = factory.sockets.getFirst();
      socket.event("live");
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertEquals(
          "live",
          workers.submit(() -> adapter.snapshot().get("first").state()).get(1, TimeUnit.SECONDS));
      factory.hangReads = true;
      assertEquals(
          "FAILED",
          workers
              .submit(
                  () -> adapter.execute("states.read", new Arguments(List.of("first"), null, null)))
              .get(1, TimeUnit.SECONDS)
              .state());
      assertTrue(socket.cancellations.get() > 0);
      assertThrows(
          IOException.class,
          adapter::connect,
          "old physical observer/FIFO must settle before new generation");
      assertEquals(1, factory.sockets.size());
      assertFalse(gap.await(20, TimeUnit.MILLISECONDS));
      release.countDown();
      assertTrue(gap.await(1, TimeUnit.SECONDS));
      assertEquals(List.of("state", "gap"), delivered);
    } finally {
      release.countDown();
    }
  }

  @Test
  void oldStateCallbackBlockedAtMonitorCannotOverwriteReplacementGeneration() throws Exception {
    FixtureSockets factory = new FixtureSockets();
    try (var adapter =
        new HomeAssistantAdapter(settings(false), "fixture", Duration.ofSeconds(2), factory)) {
      adapter.start(ignored -> {});
      Socket old = factory.sockets.getFirst();
      HomeAssistantResourceSource source = adapter.resourceSource("fixture");
      var catalog =
          new HomeAssistantResourceCatalog(
              source,
              HomeAssistantDiscoveryPolicy.fromSettings(
                  source.binding(), "policy", settings(false)),
              SECOND);
      catalog.search(
          new Search("", Optional.empty(), Optional.empty(), 100, Optional.empty()),
          OperationBudget.start(SECOND));
      Thread callback = new Thread(() -> old.event("STALE_OLD_GENERATION"));
      synchronized (adapter) {
        callback.start();
        awaitBlocked(callback);
        adapter.connect();
        assertEquals("2", adapter.snapshot().get("first").state());
      }
      callback.join(1000);
      assertFalse(callback.isAlive());
      assertEquals("2", adapter.snapshot().get("first").state());
      assertEquals(0, source.connection().stateVersion());
    }
  }

  @Test
  void oldAuthenticationCallbackCannotReviveClosedReplacementConnection() throws Exception {
    FixtureSockets factory = new FixtureSockets();
    try (var adapter =
        new HomeAssistantAdapter(settings(false), "fixture", Duration.ofSeconds(2), factory)) {
      adapter.start(ignored -> {});
      Socket old = factory.sockets.getFirst();
      HomeAssistantResourceSource source = adapter.resourceSource("fixture");
      Thread callback = new Thread(() -> old.listener.onMessage(old, "{\"type\":\"auth_ok\"}"));
      synchronized (adapter) {
        callback.start();
        awaitBlocked(callback);
        adapter.connect();
        Socket replacement = factory.sockets.getLast();
        replacement.listener.onClosed(replacement, 1000, "fixture");
      }
      callback.join(1000);
      assertFalse(callback.isAlive());
      assertFalse(source.connection().connected());
      assertAuthenticationFlagStayedFenced(adapter);
      assertEquals(0, source.connection().stateVersion());
    }
  }

  @Test
  void lateAuthenticationCannotReviveTheSameRetiredAttempt() throws Exception {
    FixtureSockets factory = new FixtureSockets();
    try (var adapter =
        new HomeAssistantAdapter(settings(false), "fixture", Duration.ofSeconds(2), factory)) {
      adapter.start(ignored -> {});
      Socket original = factory.sockets.getFirst();
      HomeAssistantResourceSource source = adapter.resourceSource("fixture");
      String epoch = source.connection().epoch();
      Thread callback =
          new Thread(
              () ->
                  original.listener.onMessage(
                      original, Json.object().put("type", "auth_ok").toString()));
      synchronized (adapter) {
        callback.start();
        awaitBlocked(callback);
        adapter.abortConnection(epoch, original);
      }
      callback.join(1000);
      assertFalse(callback.isAlive());
      assertEquals(epoch, source.connection().epoch());
      assertFalse(
          source.connection().connected(),
          "a retired attempt stays retired without requiring a new epoch");
      assertAuthenticationFlagStayedFenced(adapter);
      assertTrue(original.cancellations.get() > 0);
    }
  }

  private static void assertAuthenticationFlagStayedFenced(HomeAssistantAdapter adapter)
      throws Exception {
    // Inspect the owning mutation itself: a retired-status projection must not mask a late
    // connected=true write, which would also incorrectly suppress transport reconnection.
    Field connected = HomeAssistantAdapter.class.getDeclaredField("connected");
    connected.setAccessible(true);
    assertFalse(
        connected.getBoolean(adapter),
        "late authentication must not revive the internal connected flag");
  }

  private static Observation observation(String id, boolean gap) {
    if (gap) return new Observation(id, new EventEnvelope(new Gap("epoch", Instant.EPOCH)));
    Reading reading =
        new Reading(
            "first",
            id,
            "available",
            Map.of(),
            null,
            null,
            null,
            "epoch",
            false,
            Instant.EPOCH.toString(),
            null);
    return new Observation(id, new EventEnvelope(new StateChanged(reading, false)));
  }

  @Test
  void boundedFifoReservesGapWithoutDroppingAcceptedTransitions() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    List<String> delivered = new CopyOnWriteArrayList<>();
    try (var owner =
        new HomeAssistantObservations(
            value -> {
              if (value.identity().equals("first")) {
                entered.countDown();
                blockIgnoringInterrupts(release);
              }
              delivered.add(value.identity());
            },
            ignored -> {},
            Duration.ofSeconds(2),
            SECOND)) {
      owner.offer(observation("first", false));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      for (int index = 0; index < 256; index++) owner.offer(observation("queued-" + index, false));
      assertThrows(IOException.class, () -> owner.offer(observation("overflow", false)));
      var gap = owner.offer(observation("gap", true));
      release.countDown();
      gap.get(1, TimeUnit.SECONDS);
      assertEquals(258, delivered.size());
      assertEquals("first", delivered.getFirst());
      assertEquals("queued-255", delivered.get(256));
      assertEquals("gap", delivered.getLast());
    } finally {
      release.countDown();
    }
  }

  @Test
  void observerExpiryRetainsPhysicalSlotAndOriginalIdentityUntilLateReturnAndGap()
      throws Exception {
    CountDownLatch entered = new CountDownLatch(1),
        release = new CountDownLatch(1),
        expired = new CountDownLatch(1);
    AtomicInteger callbacks = new AtomicInteger();
    try (var owner =
        new HomeAssistantObservations(
            value -> {
              callbacks.incrementAndGet();
              if (value.identity().equals("blocked")) {
                entered.countDown();
                blockIgnoringInterrupts(release);
              }
            },
            ignored -> expired.countDown(),
            Duration.ofMillis(30),
            SECOND)) {
      var original = owner.offer(observation("blocked", false));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertTrue(expired.await(1, TimeUnit.SECONDS));
      assertThrows(ExecutionException.class, () -> original.get(1, TimeUnit.SECONDS));
      assertThrows(IOException.class, owner::check);
      assertFalse(owner.readyForConnection());
      var gap = owner.offer(observation("gap", true));
      release.countDown();
      gap.get(1, TimeUnit.SECONDS);
      assertTrue(owner.readyForConnection());
      assertEquals(2, callbacks.get(), "late original callback is never invoked twice");
    } finally {
      release.countDown();
    }
  }

  @Test
  void cleanupDeadlineDoesNotPretendAnUncooperativeCallbackSettled() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    var owner =
        new HomeAssistantObservations(
            value -> {
              calls.incrementAndGet();
              entered.countDown();
              blockIgnoringInterrupts(release);
            },
            ignored -> {},
            Duration.ofSeconds(5),
            Duration.ofMillis(30));
    try {
      var acknowledged = owner.offer(observation("physical-work", false));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      long started = System.nanoTime();
      owner.close();
      assertTrue(System.nanoTime() - started < SECOND.toNanos());
      assertThrows(ExecutionException.class, () -> acknowledged.get(1, TimeUnit.SECONDS));
      assertFalse(owner.readyForConnection());
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      owner.close();
    }
  }

  @Test
  void uncertainObserverFailureNeverRetriesOrRunsAroundRetainedGap() throws Exception {
    AtomicInteger callbacks = new AtomicInteger();
    CountDownLatch failed = new CountDownLatch(1);
    try (var owner =
        new HomeAssistantObservations(
            value -> {
              callbacks.incrementAndGet();
              throw new IllegalStateException("fixture uncertain journal commit");
            },
            ignored -> failed.countDown(),
            SECOND,
            SECOND)) {
      var original = owner.offer(observation("original", false));
      assertThrows(ExecutionException.class, () -> original.get(1, TimeUnit.SECONDS));
      assertTrue(failed.await(1, TimeUnit.SECONDS));
      var gap = owner.offer(observation("gap", true));
      assertThrows(IOException.class, owner::check);
      assertFalse(owner.readyForConnection());
      assertFalse(gap.isDone());
      assertEquals(1, callbacks.get());
    }
  }
}
