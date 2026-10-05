package io.aeyer.plowshare.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ServerPush;
import io.aeyer.plowshare.protocol.frames.Envelope;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import okhttp3.*;

/**
 * Persistent Plowshare v1 event connection. All application requests use WS; replies retain their
 * complete JSON, including future fields. No mutation replay.
 */
public final class Plowshare implements AutoCloseable {
  public enum Delivery {
    NOT_SUBMITTED,
    UNKNOWN,
    INVALID_RESPONSE
  }

  public static final class TransportException extends IOException {
    private final Delivery delivery;

    TransportException(Delivery delivery, String message) {
      super(message);
      this.delivery = delivery;
    }

    public Delivery delivery() {
      return delivery;
    }
  }

  record Reply(String code, String said, JsonNode payload, JsonNode raw) {
    public boolean successful() {
      return java.util.Set.of("OK", "CREATED", "ACCEPTED", "NO_CONTENT").contains(code);
    }

    public JsonNode requirePayload() throws IOException {
      if (!successful())
        throw new IOException("Plowshare answered " + code + (said == null ? "" : ": " + said));
      if (payload == null || payload.isNull())
        throw new IOException("Plowshare answered without a required payload");
      return payload.deepCopy();
    }
  }

  private static final ObjectMapper JSON = SdkJson.mapper();
  private final OkHttpClient http;
  private final Duration timeout;
  private final String session;

  private record Pending(String type, CompletableFuture<Reply> answer) {}

  private final Map<String, Pending> pending = new ConcurrentHashMap<>();
  private final BlockingQueue<ServerPush> pushes = new ArrayBlockingQueue<>(256);
  private final ExecutorService callbacks =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("plowshare-sdk-push").factory());
  private volatile WebSocket socket;
  private volatile boolean closed;
  private volatile boolean lost;
  private final java.util.concurrent.atomic.AtomicLong dropped =
      new java.util.concurrent.atomic.AtomicLong();

  private Plowshare(OkHttpClient http, Duration timeout, String session) {
    this.http = http;
    this.timeout = timeout;
    this.session = session;
  }

  /**
   * Java can send bearer authentication on the upgrade; no credential enters the URL. The caller
   * owns token renewal and explicitly opens a fresh connection after loss.
   */
  public static Plowshare connect(
      String origin, String bearer, Duration timeout, Consumer<ServerPush> onPush)
      throws IOException {
    return connect(origin, bearer, UUID.randomUUID().toString(), timeout, onPush);
  }

  public static Plowshare connect(
      String origin, String bearer, String session, Duration timeout, Consumer<ServerPush> onPush)
      throws IOException {
    HttpUrl base = HttpUrl.parse(origin);
    if (base == null
        || !base.encodedPath().equals("/")
        || base.query() != null
        || base.fragment() != null
        || !base.username().isEmpty()
        || !base.password().isEmpty())
      throw new IllegalArgumentException(
          "Plowshare requires an HTTP(S) origin without credentials, path, query or fragment");
    if (timeout.isNegative() || timeout.isZero() || session == null || session.isBlank())
      throw new IllegalArgumentException("positive timeout and a nonblank session required");
    var client =
        new Plowshare(
            new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ZERO)
                .retryOnConnectionFailure(false)
                .build(),
            timeout,
            session);
    var request =
        new Request.Builder()
            .header("User-Agent", "plowshare-sdk")
            .url(
                base.newBuilder()
                    .addPathSegments("v1/events")
                    .addQueryParameter("session", session)
                    .build());
    if (bearer != null && !bearer.isBlank()) request.header("Authorization", "Bearer " + bearer);
    var ready = new CompletableFuture<Void>();
    client.socket =
        client.http.newWebSocket(
            request.build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                ready.complete(null);
              }

              @Override
              public void onMessage(WebSocket ws, String text) {
                client.arrived(text);
              }

              @Override
              public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(code, reason);
              }

              @Override
              public void onClosed(WebSocket ws, int code, String reason) {
                ready.completeExceptionally(new IOException("Plowshare socket closed"));
                client.strand();
              }

              @Override
              public void onFailure(WebSocket ws, Throwable failure, Response response) {
                ready.completeExceptionally(new IOException("Plowshare socket unavailable"));
                client.strand();
              }
            });
    try {
      await(ready, timeout);
    } catch (IOException failure) {
      client.close();
      throw failure;
    }
    Consumer<ServerPush> callback = onPush == null ? push -> {} : onPush;
    client.callbacks.execute(
        () -> {
          try {
            while (!client.closed) {
              ServerPush push = client.pushes.take();
              try {
                callback.accept(push);
              } catch (RuntimeException ignored) {
                /* Isolate consumers. */
              }
            }
          } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
          }
        });
    return client;
  }

  public String session() {
    return session;
  }

  public boolean connected() {
    return !closed && !lost;
  }

  /** Dropped pushes require reconciliation through durable WS reads. */
  public long droppedPushes() {
    return dropped.get();
  }

  /** Internal transport boundary. Public consumers use validated operation facades. */
  Reply request(String type, Map<String, ?> payload) throws IOException {
    Objects.requireNonNull(payload, "payload");
    if (type == null || !type.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))
      throw new IllegalArgumentException("a dotted frame type is required");
    String id = UUID.randomUUID().toString();
    CompletableFuture<Reply> answer = new CompletableFuture<>();
    synchronized (this) {
      if (!connected())
        throw new TransportException(
            Delivery.NOT_SUBMITTED, "Plowshare connection is closed; request was not submitted");
      if (pending.size() >= 64)
        throw new TransportException(
            Delivery.NOT_SUBMITTED, "too many outstanding Plowshare requests");
      String frame =
          JSON.writeValueAsString(
              new Envelope(
                  id, type, Envelope.CURRENT_VERSION, new java.util.LinkedHashMap<>(payload)));
      pending.put(id, new Pending(type, answer));
      if (!socket.send(frame)) {
        pending.remove(id);
        throw new TransportException(
            Delivery.NOT_SUBMITTED, "Plowshare socket refused the request");
      }
    }
    try {
      return await(answer, timeout);
    } catch (TimeoutExceptionWrapper timed) {
      throw new TransportException(
          Delivery.UNKNOWN,
          "Plowshare response timed out; outcome is unknown; no request was replayed");
    } finally {
      pending.remove(id);
    }
  }

  <T> T request(String type, Map<String, ?> payload, Class<T> responseType) throws IOException {
    return SdkJson.decode(JSON, request(type, payload).requirePayload(), responseType);
  }

  // Serialization boundary shared by typed facades; arbitrary payloads are never public SDK APIs.
  <T> T exchange(String type, Object request, Class<T> response) throws IOException {
    Map<String, Object> fields =
        JSON.convertValue(request, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return SdkJson.decode(JSON, request(type, fields).requirePayload(), response);
  }

  private void arrived(String text) {
    try {
      JsonNode frame = JSON.readTree(text);
      if (frame == null || !frame.isObject()) return;
      if (!frame.has("protocol_version")) {
        push(frame);
        return;
      }
      JsonNode id = frame.get("id");
      if (id == null || id.isNull()) {
        if (Envelope.CURRENT_VERSION.equals(frame.path("protocol_version").asText())) push(frame);
        return;
      }
      if (!id.isTextual()) return;
      Pending waiting = pending.remove(id.asText());
      if (waiting == null) return;
      CompletableFuture<Reply> answer = waiting.answer();
      JsonNode outcome = frame.path("payload");
      if (!Envelope.CURRENT_VERSION.equals(frame.path("protocol_version").asText())
          || !waiting.type().equals(frame.path("type").asText())
          || !outcome.isObject()
          || !outcome.path("code").isTextual()
          || outcome.path("code").asText().isBlank()
          || outcome.has("said") && !outcome.get("said").isTextual()) {
        answer.completeExceptionally(
            new TransportException(
                Delivery.INVALID_RESPONSE, "unreadable Plowshare response; outcome is unknown"));
        return;
      }
      answer.complete(
          new Reply(
              outcome.get("code").asText(),
              outcome.has("said") ? outcome.get("said").asText() : null,
              outcome.get("payload"),
              frame.deepCopy()));
    } catch (IOException invalid) {
      /* Uncorrelated malformed input cannot establish an outcome. */
    }
  }

  private void push(JsonNode frame) {
    try {
      if (!pushes.offer(PushDecoder.decode(JSON, frame))) dropped.incrementAndGet();
    } catch (IOException invalid) {
      // Invalid/unsupported hints count as dropped so consumers know to reconcile durable reads.
      dropped.incrementAndGet();
    }
  }

  private synchronized void strand() {
    lost = true;
    pending
        .values()
        .forEach(
            waiting ->
                waiting
                    .answer()
                    .completeExceptionally(
                        new TransportException(
                            Delivery.UNKNOWN,
                            "Plowshare connection lost; outcome is unknown; no request was replayed")));
    pending.clear();
  }

  private static final class TimeoutExceptionWrapper extends IOException {}

  private static <T> T await(CompletableFuture<T> answer, Duration timeout) throws IOException {
    try {
      return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new TransportException(Delivery.UNKNOWN, "interrupted; outcome may be unknown");
    } catch (TimeoutException timed) {
      throw new TimeoutExceptionWrapper();
    } catch (ExecutionException failed) {
      if (failed.getCause() instanceof IOException io) throw io;
      throw new IOException("Plowshare connection failed", failed.getCause());
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    strand();
    if (socket != null) socket.cancel();
    callbacks.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
