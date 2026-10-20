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
  /** Legacy is an explicit compatibility choice; no possibly submitted request changes encoding. */
  public enum TransportMode {
    SEGMENTED,
    LEGACY
  }

  /** Operation ordering is negotiated independently from the packet encoding. */
  public enum DispatchMode {
    CONCURRENT,
    ORDERED
  }

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

  private static final int MAX_PENDING = 64;
  private static final long MAX_QUEUED_BYTES =
      io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_CONNECTION_BYTES;
  private static final java.util.concurrent.atomic.AtomicLong PROCESS_BYTES =
      new java.util.concurrent.atomic.AtomicLong();
  private static final long MAX_PROCESS_BYTES = 2L * 1024 * 1024 * 1024;
  private static final ScheduledThreadPoolExecutor CLOCK = clock();
  private static final Semaphore OPENINGS = new Semaphore(64);

  private static ScheduledThreadPoolExecutor clock() {
    var clock =
        new ScheduledThreadPoolExecutor(
            1, Thread.ofPlatform().daemon().name("plowshare-sdk-deadline").factory());
    clock.setRemoveOnCancelPolicy(true);
    return clock;
  }

  /**
   * A request's terminal state is independent of transport ownership. A timed-out send keeps its
   * bytes reserved until the physical sender finishes; a queued cancellation writes nothing.
   */
  private static final class Pending {
    final String id;
    final String type;
    String frame;
    final int bytes;
    final long deadline;
    final CompletableFuture<Reply> answer = new CompletableFuture<>();
    boolean submitted;
    boolean terminal;
    boolean sending;
    boolean released;
    ScheduledFuture<?> timer;
    AutoCloseable cancellation;

    Pending(String id, String type, String frame, int bytes, long deadline) {
      this.id = id;
      this.type = type;
      this.frame = frame;
      this.bytes = bytes;
      this.deadline = deadline;
    }
  }

  private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CompletableFuture<Reply>> delivering =
      new ConcurrentHashMap<>();
  private final BlockingQueue<Pending> outgoing = new ArrayBlockingQueue<>(MAX_PENDING);
  // A reservation stays held while completion invokes user continuations. Nested requests
  // either reserve independent capacity or fail immediately; they never await their own permit.
  private final Semaphore completions = new Semaphore(MAX_PENDING);
  private final ExecutorService completionExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final CompletableFuture<Void> drained = new CompletableFuture<>();
  private final java.util.concurrent.atomic.AtomicBoolean drainScheduled =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Thread writer;
  private long queuedBytes;
  private int reserved;
  private boolean writerStopped;

  private final BlockingQueue<ServerPush> pushes = new ArrayBlockingQueue<>(256);
  private final ExecutorService callbacks =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("plowshare-sdk-push").factory());
  private volatile WebSocket socket;
  private final Object packetWrite = new Object();
  private io.aeyer.plowshare.protocol.transport.SegmentedMessages packets;
  private final ObjectMapper packetJson =
      JSON.copy()
          .enable(
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
              com.fasterxml.jackson.databind.DeserializationFeature
                  .FAIL_ON_MISSING_CREATOR_PROPERTIES,
              com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
          .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private volatile boolean closed;
  private volatile boolean lost;
  private final java.util.concurrent.atomic.AtomicLong dropped =
      new java.util.concurrent.atomic.AtomicLong();

  private Plowshare(OkHttpClient http, Duration timeout, String session) {
    this.http = http;
    this.timeout = timeout;
    this.session = session;
    writer = Thread.ofVirtual().name("plowshare-sdk-writer").start(this::writeLoop);
    packetJson
        .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
    packetJson
        .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
        .setCoercion(
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
            com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
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
    return connect(origin, bearer, session, timeout, onPush, TransportMode.SEGMENTED);
  }

  public static Plowshare connect(
      String origin,
      String bearer,
      String session,
      Duration timeout,
      Consumer<ServerPush> onPush,
      TransportMode mode)
      throws IOException {
    return await(
        connectAsync(origin, bearer, session, timeout, onPush, mode, DispatchMode.CONCURRENT));
  }

  public static Plowshare connect(
      String origin,
      String bearer,
      String session,
      Duration timeout,
      Consumer<ServerPush> onPush,
      TransportMode mode,
      DispatchMode dispatch)
      throws IOException {
    return await(connectAsync(origin, bearer, session, timeout, onPush, mode, dispatch));
  }

  /** Opens and negotiates without blocking a caller or the receive loop. No downgrade or replay. */
  public static CompletionStage<Plowshare> connectAsync(
      String origin, String bearer, Duration timeout, Consumer<ServerPush> onPush) {
    return connectAsync(
        origin,
        bearer,
        UUID.randomUUID().toString(),
        timeout,
        onPush,
        TransportMode.SEGMENTED,
        DispatchMode.CONCURRENT);
  }

  public static CompletionStage<Plowshare> connectAsync(
      String origin,
      String bearer,
      String session,
      Duration timeout,
      Consumer<ServerPush> onPush,
      TransportMode mode,
      DispatchMode dispatch) {
    Objects.requireNonNull(dispatch, "dispatch");
    long started = System.nanoTime();
    Objects.requireNonNull(mode, "mode");
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
    timeout.toNanos();
    if (!OPENINGS.tryAcquire())
      return CompletableFuture.failedFuture(
          new TransportException(
              Delivery.NOT_SUBMITTED, "SDK connection opening capacity exceeded"));
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
    if (mode == TransportMode.SEGMENTED)
      request.header(
          "Sec-WebSocket-Protocol",
          io.aeyer.plowshare.protocol.transport.SegmentedMessages.SUBPROTOCOL);
    var ready = new CompletableFuture<Void>();
    var openingDeadline =
        CLOCK.schedule(
            () ->
                ready.completeExceptionally(
                    new TransportException(
                        Delivery.NOT_SUBMITTED, "Plowshare opening deadline expired")),
            timeout.toNanos(),
            TimeUnit.NANOSECONDS);
    client.socket =
        client.http.newWebSocket(
            request.build(),
            new WebSocketListener() {
              @Override
              public void onOpen(WebSocket ws, Response response) {
                if (mode == TransportMode.SEGMENTED) {
                  if (!io.aeyer.plowshare.protocol.transport.SegmentedMessages.SUBPROTOCOL.equals(
                      response.header("Sec-WebSocket-Protocol"))) {
                    ready.completeExceptionally(
                        new TransportException(
                            Delivery.NOT_SUBMITTED,
                            "server does not support segmented transport; explicitly select legacy mode for compatibility"));
                    ws.cancel();
                    return;
                  }
                  client.socket = ws;
                  client.packets =
                      new io.aeyer.plowshare.protocol.transport.SegmentedMessages(
                          new io.aeyer.plowshare.protocol.transport.PacketWire() {
                            public void segment(
                                io.aeyer.plowshare.protocol.transport.MessageSegment packet)
                                throws IOException {
                              client.packet(packet);
                            }

                            public void credit(
                                io.aeyer.plowshare.protocol.transport.SegmentCredit packet)
                                throws IOException {
                              client.packet(packet);
                            }
                          },
                          io.aeyer.plowshare.protocol.transport.SegmentedMessages.PROCESS_BUDGET,
                          client::close);
                }
                ready.complete(null);
              }

              @Override
              public void onMessage(WebSocket ws, String text) {
                if (client.packets == null) {
                  client.arrived(text);
                  return;
                }
                try {
                  client.packetArrived(text);
                } catch (IOException | RuntimeException invalid) {
                  client.strand();
                  ws.cancel();
                }
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
    var opened = new CompletableFuture<Plowshare>();
    ready.whenComplete(
        (ignored, failure) -> {
          if (failure != null) {
            openingDeadline.cancel(false);
            client.completeOpening(opened, null, failure);
            client.close();
            return;
          }
          CompletionStage<Reply> negotiated =
              dispatch == DispatchMode.ORDERED
                  ? CompletableFuture.completedFuture(null)
                  : client.requestAsync(
                      "transport.dispatch",
                      Map.of("mode", "concurrent-v1"),
                      (io.aeyer.plowshare.protocol.ActingContext.Selection) null,
                      new RequestOptions(
                          Duration.ofNanos(
                              Math.max(1, timeout.toNanos() - (System.nanoTime() - started))),
                          null));
          negotiated.whenComplete(
              (reply, negotiationFailure) -> {
                openingDeadline.cancel(false);
                if (negotiationFailure != null) {
                  client.completeOpening(opened, null, negotiationFailure);
                  client.close();
                  return;
                }
                if (dispatch == DispatchMode.CONCURRENT
                    && (!reply.successful()
                        || reply.payload() == null
                        || !reply.payload().isObject()
                        || !reply.payload().path("mode").isTextual()
                        || !"concurrent-v1".equals(reply.payload().path("mode").asText())
                        || !reply.payload().path("maxPending").isIntegralNumber()
                        || !reply.payload().path("maxPending").canConvertToInt()
                        || reply.payload().path("maxPending").asInt() != MAX_PENDING
                        || !reply.payload().path("maxIdBytes").isIntegralNumber()
                        || !reply.payload().path("maxIdBytes").canConvertToInt()
                        || reply.payload().path("maxIdBytes").asInt() != 128)) {
                  client.completeOpening(
                      opened,
                      null,
                      new TransportException(
                          Delivery.NOT_SUBMITTED, "Server did not negotiate concurrent dispatch"));
                  client.close();
                  return;
                }
                Consumer<ServerPush> callback = onPush == null ? push -> {} : onPush;
                client.callbacks.execute(
                    () -> {
                      try {
                        while (!client.closed) {
                          ServerPush push = client.pushes.take();
                          try {
                            callback.accept(push);
                          } catch (RuntimeException ignoredCallback) {
                            /* Isolate notification consumers. */
                          }
                        }
                      } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                      }
                    });
                client.completeOpening(opened, client, null);
              });
        });
    // Cancelling opening never cancels an already admitted application request: none exists yet.
    opened.whenComplete(
        (value, failure) -> {
          if (opened.isCancelled()) client.close();
        });
    return opened;
  }

  private <T> void completeOpening(CompletableFuture<T> future, T value, Throwable failure) {
    Thread.ofVirtual()
        .name("plowshare-sdk-open-completion")
        .start(
            () -> {
              try {
                if (failure == null) future.complete(value);
                else future.completeExceptionally(failure);
              } finally {
                OPENINGS.release();
              }
            });
  }

  public String session() {
    return session;
  }

  RequestOptions defaultOptions() {
    return RequestOptions.defaults(timeout);
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
    return AsyncSupport.await(defaultOptions(), options -> requestAsync(type, payload, options));
  }

  Reply requestActing(
      String type,
      Map<String, ?> payload,
      io.aeyer.plowshare.protocol.ActingContext.Selection selection)
      throws IOException {
    return AsyncSupport.await(
        defaultOptions(), options -> requestAsync(type, payload, selection, options));
  }

  CompletionStage<Reply> requestAsync(String type, Map<String, ?> payload) {
    return requestAsync(
        type,
        payload,
        (io.aeyer.plowshare.protocol.ActingContext.Selection) null,
        RequestOptions.defaults(timeout));
  }

  CompletionStage<Reply> requestAsync(String type, Map<String, ?> payload, RequestOptions options) {
    return requestAsync(
        type,
        payload,
        (io.aeyer.plowshare.protocol.ActingContext.Selection) null,
        options == null ? RequestOptions.defaults(timeout) : options);
  }

  CompletionStage<Reply> requestActingAsync(
      String type,
      Map<String, ?> payload,
      io.aeyer.plowshare.protocol.ActingContext.Selection selection,
      RequestOptions options) {
    return requestAsync(
        type, payload, selection, options == null ? RequestOptions.defaults(timeout) : options);
  }

  <T> CompletionStage<T> requestAsync(
      String type, Map<String, ?> payload, Class<T> response, RequestOptions options) {
    return AsyncSupport.map(
        requestAsync(type, payload, options),
        reply -> SdkJson.decode(JSON, reply.requirePayload(), response));
  }

  <T> CompletionStage<T> exchangeAsync(
      String type, Object request, Class<T> response, RequestOptions options) {
    Map<String, Object> fields =
        JSON.convertValue(request, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    return requestAsync(type, fields, response, options);
  }

  CompletionStage<Reply> requestAsync(
      String type,
      Map<String, ?> payload,
      io.aeyer.plowshare.protocol.ActingContext.Selection selection,
      RequestOptions options) {
    long started = System.nanoTime();
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(options, "options");
    if (type == null || !type.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))
      throw new IllegalArgumentException("a dotted frame type is required");
    synchronized (this) {
      if (!connected() || reserved == MAX_PENDING || !completions.tryAcquire())
        return CompletableFuture.failedFuture(
            new TransportException(
                Delivery.NOT_SUBMITTED, "Connection closed or request capacity exceeded"));
      reserved++;
    }
    Pending request;
    try {
      String id = UUID.randomUUID().toString();
      String frame =
          JSON.writeValueAsString(
              new Envelope(
                  id,
                  type,
                  Envelope.CURRENT_VERSION,
                  new java.util.LinkedHashMap<>(payload),
                  selection));
      int bytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (bytes
          > (packets == null
              ? 1024 * 1024
              : io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES))
        throw new TransportException(Delivery.NOT_SUBMITTED, "Encoded request exceeds allowance");
      request = new Pending(id, type, frame, bytes, started + options.timeout().toNanos());
      synchronized (this) {
        if (!connected() || queuedBytes + bytes > MAX_QUEUED_BYTES || !reserveProcess(bytes))
          throw new TransportException(Delivery.NOT_SUBMITTED, "Request byte capacity exceeded");
        queuedBytes += bytes;
        pending.put(id, request);
        // Installing the timer before publishing to the writer prevents fast replies leaking it.
        request.timer =
            CLOCK.schedule(
                () ->
                    detach(
                        request,
                        null,
                        new TransportException(
                            delivery(request),
                            "Request deadline expired; no request was replayed")),
                Math.max(0, request.deadline - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        AutoCloseable registration =
            options.cancellation() == null
                ? null
                : options
                    .cancellation()
                    .register(
                        () ->
                            detach(
                                request,
                                null,
                                new TransportException(
                                    delivery(request),
                                    "Request cancelled; no request was replayed")));
        synchronized (request) {
          if (request.terminal && registration != null) {
            try {
              registration.close();
            } catch (Exception impossible) {
              throw new IllegalStateException(impossible);
            }
          } else request.cancellation = registration;
          if (!request.terminal && !outgoing.offer(request))
            throw new IllegalStateException("Reserved writer capacity unavailable");
        }
      }
    } catch (IOException | RuntimeException failure) {
      synchronized (this) {
        reserved--;
      }
      completions.release();
      checkDrained();
      return CompletableFuture.failedFuture(failure);
    }
    return request.answer;
  }

  private static boolean reserveProcess(int bytes) {
    while (true) {
      long held = PROCESS_BYTES.get();
      if (held + bytes > MAX_PROCESS_BYTES) return false;
      if (PROCESS_BYTES.compareAndSet(held, held + bytes)) return true;
    }
  }

  private static Delivery delivery(Pending request) {
    synchronized (request) {
      return request.submitted ? Delivery.UNKNOWN : Delivery.NOT_SUBMITTED;
    }
  }

  private void writeLoop() {
    try {
      while (!closed && !lost) {
        Pending request = outgoing.take();
        synchronized (request) {
          if (request.terminal) continue;
          if (closed || lost || request.deadline - System.nanoTime() <= 0) {
            detach(
                request,
                null,
                new TransportException(Delivery.NOT_SUBMITTED, "Queued request deadline expired"));
            continue;
          }
          request.sending = true;
          request.submitted = true;
        }
        try {
          if (packets != null) packets.send(request.frame);
          else if (!socket.send(request.frame)) {
            // OkHttp's false return proves that this frame was not accepted. A timeout which
            // already won while send was in progress keeps its conservative UNKNOWN outcome.
            synchronized (request) {
              if (!request.terminal) request.submitted = false;
            }
            throw new IOException("Socket refused request");
          }
        } catch (IOException | RuntimeException failure) {
          detach(
              request,
              null,
              new TransportException(Delivery.UNKNOWN, "Send failed; no request was replayed"));
        } finally {
          synchronized (request) {
            request.sending = false;
            request.frame = null;
          }
          releaseBytes(request);
        }
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } finally {
      synchronized (this) {
        writerStopped = true;
      }
      checkDrained();
    }
  }

  private void releaseBytes(Pending request) {
    synchronized (request) {
      if (request.released || request.sending || !request.terminal && request.frame != null) return;
      request.released = true;
      request.frame = null;
    }
    synchronized (this) {
      queuedBytes -= request.bytes;
    }
    PROCESS_BYTES.addAndGet(-request.bytes);
  }

  private void detach(Pending request, Reply reply, Throwable failure) {
    Throwable terminalFailure;
    synchronized (request) {
      if (request.terminal) return;
      request.terminal = true;
      // Submission and terminal selection share this lock: a timeout cannot report NOT_SUBMITTED
      // after the writer won the race and may have emitted bytes.
      terminalFailure =
          failure instanceof TransportException transport
                  && transport.delivery() != Delivery.INVALID_RESPONSE
              ? new TransportException(
                  request.submitted ? Delivery.UNKNOWN : Delivery.NOT_SUBMITTED,
                  transport.getMessage())
              : failure;
    }
    delivering.put(request.id, request.answer);
    pending.remove(request.id, request);
    outgoing.remove(request);
    if (request.timer != null) request.timer.cancel(false);
    if (request.cancellation != null) {
      try {
        request.cancellation.close();
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }
    releaseBytes(request);
    completionExecutor.execute(
        () -> {
          try {
            if (terminalFailure == null) request.answer.complete(reply);
            else request.answer.completeExceptionally(terminalFailure);
          } finally {
            delivering.remove(request.id, request.answer);
            completions.release();
          }
        });
    synchronized (this) {
      reserved--;
    }
    checkDrained();
  }

  private void checkDrained() {
    if (!(closed || lost)) return;
    boolean ready;
    synchronized (this) {
      ready = writerStopped && reserved == 0 && queuedBytes == 0;
    }
    if (ready && drainScheduled.compareAndSet(false, true)) finishDrain();
  }

  private void finishDrain() {
    // Future state becomes done before arbitrary continuations return. Observe that state rather
    // than joining callbacks, so an uncooperative callback cannot hold transport shutdown open.
    if (delivering.values().stream().anyMatch(answer -> !answer.isDone())) {
      CLOCK.schedule(this::finishDrain, 1, TimeUnit.MILLISECONDS);
      return;
    }
    completionExecutor.shutdown();
    Thread.ofVirtual().name("plowshare-sdk-drain-completion").start(() -> drained.complete(null));
  }

  /** Transport drain excludes arbitrary user callbacks, which never hold a socket or registry. */
  public CompletionStage<Void> closeAsync() {
    close();
    return drained.minimalCompletionStage();
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

  private void packet(Object value) throws IOException {
    synchronized (packetWrite) {
      if (!socket.send(packetJson.writeValueAsString(value)))
        throw new IOException("socket refused packet");
    }
  }

  private void packetArrived(String text) throws IOException {
    if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
        > io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_PACKET_BYTES)
      throw new IOException("oversized packet");
    var tree = packetJson.readTree(text);
    if (tree == null || !tree.isObject() || !tree.path("kind").isTextual())
      throw new IOException("invalid packet");
    switch (tree.get("kind").textValue()) {
      case "transport.segment" ->
          packets
              .receive(
                  packetJson.treeToValue(
                      tree, io.aeyer.plowshare.protocol.transport.MessageSegment.class))
              .ifPresent(this::arrived);
      case "transport.credit" ->
          packets.credit(
              packetJson.treeToValue(
                  tree, io.aeyer.plowshare.protocol.transport.SegmentCredit.class));
      default -> throw new IOException("unsupported packet kind");
    }
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
      Pending waiting = pending.get(id.asText());
      if (waiting == null) return;
      JsonNode outcome = frame.path("payload");
      if (!Envelope.CURRENT_VERSION.equals(frame.path("protocol_version").asText())
          || !waiting.type.equals(frame.path("type").asText())
          || !outcome.isObject()
          || !outcome.path("code").isTextual()
          || outcome.path("code").asText().isBlank()
          || outcome.has("said") && !outcome.get("said").isTextual()) {
        detach(
            waiting,
            null,
            new TransportException(
                Delivery.INVALID_RESPONSE, "unreadable Plowshare response; outcome is unknown"));
        return;
      }
      detach(
          waiting,
          new Reply(
              outcome.get("code").asText(),
              outcome.has("said") ? outcome.get("said").asText() : null,
              outcome.get("payload"),
              frame.deepCopy()),
          null);
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

  private void strand() {
    synchronized (this) {
      lost = true;
    }
    if (packets != null) packets.close();
    writer.interrupt();
    for (Pending waiting : pending.values())
      detach(
          waiting,
          null,
          new TransportException(delivery(waiting), "Connection lost; no request was replayed"));
    checkDrained();
  }

  static <T> T await(CompletionStage<T> stage) throws IOException {
    try {
      return stage.toCompletableFuture().get();
    } catch (InterruptedException interrupted) {
      stage.toCompletableFuture().cancel(false);
      Thread.currentThread().interrupt();
      throw new TransportException(Delivery.UNKNOWN, "Interrupted; outcome may be unknown");
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      while (cause instanceof CompletionException && cause.getCause() != null)
        cause = cause.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IOException("Plowshare request failed", cause);
    }
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
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    strand();
    if (socket != null) socket.cancel();
    callbacks.shutdownNow();
    http.dispatcher().executorService().shutdown();
    http.connectionPool().evictAll();
  }
}
