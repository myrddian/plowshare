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

  /**
   * The server's dispatch refusal. It is flow control, not a fault: the waiting form absorbs it and
   * resubmits, and the caller never sees it. The {@code control} negotiation is exempt.
   */
  private static final String CAPACITY_EXHAUSTED = "CAPACITY_EXHAUSTED";

  private static final String NEGOTIATION = "transport.dispatch";

  /**
   * Resubmission pacing after a capacity refusal, matching the reference SDK's shipped values: the
   * ceiling doubles with the refusal count up to the cap, and the pause is half of it fixed and
   * half random, so a client always pauses and clients released together never retry in lockstep.
   */
  private static final Duration RESUBMIT_BACKOFF_BASE = Duration.ofMillis(100);

  private static final Duration RESUBMIT_BACKOFF_CAP = Duration.ofMillis(5000);

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
   *
   * <p>{@code allowance} is the duration the caller granted, held unspent until the writer arms
   * {@code timer} at the write. {@code absorbs} records the submission form once, at admission, so
   * the reader does not have to re-derive it.
   *
   * <p>An absorbing request keeps {@code envelope}, the fields its frame is encoded from, and
   * re-encodes on a resubmission; {@code frame} and the byte charge it gates are released as soon
   * as the frame is written, and a resubmission charges its bytes again. It deliberately does not
   * cache the encoded frame: that would hold a second copy of the whole request, which for a large
   * payload is megabytes retained from the write until the reply, for a refusal that in almost
   * every request never comes.
   */
  private static final class Pending {
    final String id;
    final String type;
    final long allowance;
    final boolean absorbs;
    final Envelope envelope;
    String frame;
    final int bytes;
    final CompletableFuture<Reply> answer = new CompletableFuture<>();
    boolean submitted;
    boolean terminal;
    boolean sending;
    boolean released;
    boolean armed;
    int refusals;

    /** Pairs with the connection's dispatch count, so releasing it twice is impossible. */
    final java.util.concurrent.atomic.AtomicBoolean dispatching =
        new java.util.concurrent.atomic.AtomicBoolean();

    ScheduledFuture<?> timer;
    ScheduledFuture<?> pause;
    AutoCloseable cancellation;

    Pending(Envelope envelope, String frame, int bytes, long allowance, boolean absorbs) {
      this.id = envelope.id();
      this.type = envelope.type();
      this.envelope = absorbs ? envelope : null;
      this.frame = frame;
      this.bytes = bytes;
      this.allowance = allowance;
      this.absorbs = absorbs;
    }
  }

  /**
   * One caller parked for an admission slot. The slot is charged to the waiter before it is woken,
   * so a caller arriving later cannot steal what FIFO order already granted. {@code settled} is the
   * single record of whether this waiter has been granted or abandoned, so a cancellation racing a
   * grant resolves one way only.
   */
  private static final class SlotWaiter {
    final CompletableFuture<Void> granted = new CompletableFuture<>();
    boolean settled;
    AutoCloseable registration;
  }

  private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, CompletableFuture<Reply>> delivering =
      new ConcurrentHashMap<>();
  private final BlockingQueue<Pending> outgoing = new ArrayBlockingQueue<>(MAX_PENDING);
  // A reservation stays held while completion invokes user continuations. The waiting form is the
  // default, so a nested request submitted from a continuation parks for a permit that the
  // continuation itself is holding. That wait ends when another request settles, when the caller's
  // RequestCancellation fires, or when the connection closes -- never on a deadline, which is armed
  // only at transmit. A continuation that blocks on a nested request while every permit is held by
  // other blocked continuations must therefore pass a cancellation or select
  // RequestOptions.Capacity.NON_BLOCKING, which still fails such a call immediately.
  private final Semaphore completions = new Semaphore(MAX_PENDING);
  private final ExecutorService completionExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private final CompletableFuture<Void> drained = new CompletableFuture<>();
  private final java.util.concurrent.atomic.AtomicBoolean drainScheduled =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final Thread writer;
  private long queuedBytes;
  private int reserved;

  /**
   * Requests currently on the wire. Two counters are kept, as the reference SDK keeps them: {@code
   * reserved} with {@link #slotWaiters} is the advertised window, this is work actually dispatched.
   * A capacity refusal releases only this one, so other already-admitted work can transmit while
   * the refused request backs off, and the refused request keeps its place in the window instead of
   * being overtaken by work that arrived after it.
   *
   * <p>Atomic rather than monitor-guarded on purpose. The writer and the completion executor are
   * virtual threads, and on this release a virtual thread that contends for a monitor pins its
   * carrier, so the per-request path must not add monitor acquisitions to the hot path.
   */
  private final java.util.concurrent.atomic.AtomicInteger dispatched =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * Callers parked for an admission slot, in arrival order. Dispatch counts need no queue: a caller
   * holding an admission is already guaranteed one, so parking for one is unreachable. See {@link
   * #acquireDispatch}.
   */
  private final java.util.ArrayDeque<SlotWaiter> slotWaiters = new java.util.ArrayDeque<>();

  /**
   * Whether {@link #slotWaiters} is nonempty, published so a release can skip {@link
   * #pumpAdmission} without taking the connection monitor. Written only under that monitor,
   * alongside every change to the queue, and read without it; a stale {@code true} costs one
   * needless pump and a stale {@code false} cannot happen, because the flag is set before the
   * waiter that needs pumping can be granted anything.
   */
  private volatile boolean parked;

  /** The resubmission jitter source, injectable so tests are deterministic. */
  private volatile java.util.function.DoubleSupplier random = Math::random;

  /**
   * Call window the server advertised in the dispatch handshake, within {@code [1, MAX_PENDING]}.
   * Written once before the connection is handed to callers; {@code MAX_PENDING} sizes the
   * structures and is the ceiling, while this is the depth actually admitted.
   */
  private volatile int window = MAX_PENDING;

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
                        || reply.payload().path("maxPending").asInt() < 1
                        || reply.payload().path("maxPending").asInt() > MAX_PENDING
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
                if (dispatch == DispatchMode.CONCURRENT)
                  client.window = reply.payload().path("maxPending").asInt();
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

  /**
   * Replaces the resubmission jitter source so a test can stage a deterministic pause. A supplier
   * outside {@code [0, 1]} is clamped rather than trusted; the fixed half of every pause means a
   * client always pauses whatever the source returns.
   */
  void resubmitRandomness(java.util.function.DoubleSupplier source) {
    random = Objects.requireNonNull(source, "source");
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
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(options, "options");
    if (type == null || !type.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))
      throw new IllegalArgumentException("a dotted frame type is required");
    SlotWaiter waiter;
    java.util.List<SlotWaiter> granted;
    synchronized (this) {
      if (!connected())
        return CompletableFuture.failedFuture(
            new TransportException(
                Delivery.NOT_SUBMITTED, "Connection closed or request capacity exceeded"));
      // Strict bounds, and only with an empty queue, so a slot is never taken ahead of a caller
      // already parked for one.
      if (slotWaiters.isEmpty() && reserved < window && completions.tryAcquire()) {
        reserved++;
        waiter = null;
        granted = null;
      } else if (options.capacity() == RequestOptions.Capacity.NON_BLOCKING) {
        return CompletableFuture.failedFuture(
            new TransportException(
                Delivery.NOT_SUBMITTED, "Connection closed or request capacity exceeded"));
      } else {
        waiter = new SlotWaiter();
        slotWaiters.add(waiter);
        parked = true;
        // Draining here, with the waiter already queued and published, is what makes the
        // monitor-free fast path in pumpAdmission safe: a permit released between the failed
        // attempt above and this publication would otherwise find no waiter to hand it to.
        granted = drainWaitersLocked();
      }
    }
    // Serialization stays outside the connection monitor: a large frame must not hold it.
    if (waiter == null) return submitAdmitted(type, payload, selection, options);
    completeGrants(granted);
    // Nothing from here to the write is charged against the request deadline, which the writer arms
    // at the write. The wait is bounded by the caller's cancellation and by the connection closing.
    observe(waiter, options.cancellation());
    return waiter.granted.thenCompose(ready -> submitAdmitted(type, payload, selection, options));
  }

  /**
   * Registers a parked caller's cancellation signal, if it has one. A signal that has already fired
   * abandons the waiter inside {@link RequestCancellation#register}; a grant that won the race
   * leaves nothing to observe, so the registration is closed rather than left behind.
   */
  private void observe(SlotWaiter waiter, RequestCancellation cancellation) {
    if (cancellation == null) return;
    AutoCloseable registration =
        cancellation.register(
            () ->
                abandon(
                    waiter,
                    "Request cancelled while waiting for dispatch capacity; nothing was written"));
    synchronized (this) {
      if (!waiter.settled) {
        waiter.registration = registration;
        return;
      }
    }
    closeRegistration(registration);
  }

  /**
   * Removes a parked caller from the queue and fails its call {@code NOT_SUBMITTED}. The waiter
   * holds no accounting until it is granted, so this leaves the in-flight state exactly as it was:
   * cancellation is never a way to leak capacity.
   */
  private void abandon(SlotWaiter waiter, String why) {
    AutoCloseable registration;
    synchronized (this) {
      if (waiter.settled) return;
      waiter.settled = true;
      slotWaiters.remove(waiter);
      parked = !slotWaiters.isEmpty();
      registration = waiter.registration;
      waiter.registration = null;
    }
    closeRegistration(registration);
    waiter.granted.completeExceptionally(new TransportException(Delivery.NOT_SUBMITTED, why));
  }

  /**
   * Hands freed admission slots to parked callers in FIFO order. It runs from every release, so a
   * failure frees capacity exactly like a reply does, and work that arrived later never overtakes a
   * caller already parked. Nothing is granted once the connection is closed; callers parked then
   * are released by {@link #strand}.
   */
  private void pumpAdmission() {
    // No caller is parked in the overwhelming majority of releases, and the writer and the
    // completion executor are virtual threads whose carriers this monitor would pin. The volatile
    // read keeps that path free of it; requestAsync drains for itself once its waiter is
    // published, so skipping here can never leave a waiter stranded.
    if (!parked) return;
    java.util.List<SlotWaiter> granted;
    synchronized (this) {
      granted = drainWaitersLocked();
    }
    completeGrants(granted);
  }

  /**
   * Charges every admission slot that is free to the callers at the head of the queue. The caller
   * holds the connection monitor; the granted waiters are woken outside it by {@link
   * #completeGrants}.
   */
  private java.util.List<SlotWaiter> drainWaitersLocked() {
    java.util.List<SlotWaiter> granted = null;
    while (!slotWaiters.isEmpty() && connected() && reserved < window && completions.tryAcquire()) {
      SlotWaiter next = slotWaiters.poll();
      next.settled = true;
      reserved++;
      if (granted == null) granted = new java.util.ArrayList<>();
      granted.add(next);
    }
    parked = !slotWaiters.isEmpty();
    return granted;
  }

  /**
   * Wakes granted callers. Their {@code registration} is read without the monitor, which is safe
   * because the drain that granted them ran on this thread and marked them settled, so {@link
   * #observe} and {@link #abandon} will not touch it again.
   */
  private void completeGrants(java.util.List<SlotWaiter> granted) {
    if (granted == null) return;
    for (SlotWaiter waiter : granted) {
      AutoCloseable registration = waiter.registration;
      waiter.registration = null;
      closeRegistration(registration);
      // Completed off the releasing thread, which may be the socket reader: a granted caller goes
      // on to serialize and enqueue its frame, which is not that thread's work.
      Thread.ofVirtual().name("plowshare-sdk-admission").start(() -> waiter.granted.complete(null));
    }
  }

  /** A parked caller has no deadline of its own, so a close is what releases it. */
  private void releaseParked() {
    java.util.List<SlotWaiter> waiting;
    synchronized (this) {
      if (slotWaiters.isEmpty()) return;
      waiting = new java.util.ArrayList<>(slotWaiters);
    }
    for (SlotWaiter waiter : waiting)
      abandon(waiter, "Connection closed while waiting for dispatch capacity");
  }

  private static void closeRegistration(AutoCloseable registration) {
    if (registration == null) return;
    try {
      registration.close();
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * Serializes, registers and enqueues one request whose admission slot is already held.
   *
   * <p>The deadline is not armed here. The caller's allowance travels with the request and the
   * writer arms it at the write, so neither the wait for an admission slot nor the wait in the
   * writer queue is charged against the time the server is given to answer.
   */
  private CompletionStage<Reply> submitAdmitted(
      String type,
      Map<String, ?> payload,
      io.aeyer.plowshare.protocol.ActingContext.Selection selection,
      RequestOptions options) {
    Pending request;
    try {
      Envelope envelope =
          new Envelope(
              UUID.randomUUID().toString(),
              type,
              Envelope.CURRENT_VERSION,
              new java.util.LinkedHashMap<>(payload),
              selection);
      String id = envelope.id();
      String frame = JSON.writeValueAsString(envelope);
      int bytes = frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (bytes
          > (packets == null
              ? 1024 * 1024
              : io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES))
        throw new TransportException(Delivery.NOT_SUBMITTED, "Encoded request exceeds allowance");
      request =
          new Pending(
              envelope,
              frame,
              bytes,
              options.timeout().toNanos(),
              options.capacity() == RequestOptions.Capacity.WAITING && !NEGOTIATION.equals(type));
      synchronized (this) {
        if (!connected() || queuedBytes + bytes > MAX_QUEUED_BYTES || !reserveProcess(bytes))
          throw new TransportException(Delivery.NOT_SUBMITTED, "Request byte capacity exceeded");
        queuedBytes += bytes;
        pending.put(id, request);
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
      pumpAdmission();
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

  /**
   * Takes one of the connection's dispatch counts for one transmit attempt, held from the write
   * until the request settles or is refused.
   *
   * <p>Holding an admission is always enough to be sure of a count, so this never waits. A request
   * holds at most one count and only while it is admitted, so a request that has not transmitted
   * yet sees at most {@code reserved - 1} dispatched, which is below the window. The window itself
   * only ever changes while nothing is admitted: it is written once, after the handshake, before
   * the connection is handed to callers.
   *
   * <p>The remaining branch is therefore an invariant violation, not flow control. It is reported
   * rather than assumed away so the advertised window stays authoritative: the request is refused
   * without transmitting instead of pushing the connection past the depth the server advertised. A
   * caller seeing it has found a bug in this client's accounting, not a busy server.
   */
  private boolean acquireDispatch(Pending request) {
    String refusal = null;
    if (!connected()) refusal = "Connection closed before submission; nothing was written";
    else {
      int bound = window;
      while (true) {
        int held = dispatched.get();
        if (held >= bound) {
          refusal = "Dispatch accounting exceeded the advertised window; no frame was written";
          break;
        }
        if (dispatched.compareAndSet(held, held + 1)) {
          request.dispatching.set(true);
          break;
        }
      }
    }
    if (refusal == null) return true;
    detach(request, null, new TransportException(Delivery.NOT_SUBMITTED, refusal));
    return false;
  }

  /**
   * Returns one dispatch count, at most once per acquisition. It wakes nobody: admission is the
   * only queued capacity, and an admission slot frees on settlement, not here.
   */
  private void releaseDispatch(Pending request) {
    if (request.dispatching.compareAndSet(true, false)) dispatched.decrementAndGet();
  }

  private void writeLoop() {
    try {
      while (!closed && !lost) {
        Pending request = outgoing.take();
        synchronized (request) {
          if (request.terminal) continue;
        }
        if (!acquireDispatch(request)) continue;
        boolean terminal;
        synchronized (request) {
          terminal = request.terminal;
          if (!terminal && !request.armed) {
            // The frame is written below, so this is where the deadline arms, and the only place
            // it ever does: a resubmission finds it already armed and runs against what is left.
            // Installing the timer here, under this lock and before the write, also prevents a
            // fast reply leaking it. A request that has not been transmitted has no deadline to
            // expire; it is bounded by its cancellation and by the connection closing.
            request.armed = true;
            request.timer =
                CLOCK.schedule(
                    () ->
                        detach(
                            request,
                            null,
                            new TransportException(
                                delivery(request),
                                "Request deadline expired; no request was replayed")),
                    request.allowance,
                    TimeUnit.NANOSECONDS);
          }
          if (!terminal) {
            request.sending = true;
            request.submitted = true;
          }
        }
        // The dispatch count is taken before this lock and released outside it: the connection
        // monitor is only ever taken before a request's, never after it.
        if (terminal) {
          releaseDispatch(request);
          continue;
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
    releaseDispatch(request);
    delivering.put(request.id, request.answer);
    pending.remove(request.id, request);
    outgoing.remove(request);
    if (request.timer != null) request.timer.cancel(false);
    if (request.pause != null) request.pause.cancel(false);
    closeRegistration(request.cancellation);
    releaseBytes(request);
    completionExecutor.execute(
        () -> {
          try {
            if (terminalFailure == null) request.answer.complete(reply);
            else request.answer.completeExceptionally(terminalFailure);
          } finally {
            delivering.remove(request.id, request.answer);
            completions.release();
            pumpAdmission();
          }
        });
    synchronized (this) {
      reserved--;
    }
    pumpAdmission();
    checkDrained();
  }

  /**
   * Absorbs one {@code CAPACITY_EXHAUSTED} reply as flow control and paces a resubmission under the
   * same request id, against the deadline already armed.
   *
   * <p>The dispatch count is released so other admitted work can transmit; the admission slot is
   * kept so later arrivals cannot overtake this request. The server refused the work outright,
   * having done none of it, which is the only reason resubmitting is not the replay this transport
   * otherwise forbids.
   */
  private void refused(Pending request) {
    int refusals;
    synchronized (request) {
      if (request.terminal) return;
      // Cleared the moment the refusal is absorbed and before the pause, not at the top of the
      // next attempt: the refused attempt left no work behind, so a deadline or a cancellation
      // landing in the pause -- which is where it almost always lands -- is honestly
      // NOT_SUBMITTED. Clearing it after the pause would report the weaker UNKNOWN instead.
      request.submitted = false;
      refusals = request.refusals++;
    }
    releaseDispatch(request);
    // Only the wakeup belongs on CLOCK. That is one process-wide thread on which every request's
    // deadline fires, for every connection in the JVM, and resubmit re-encodes the whole envelope
    // before it takes the connection monitor: a multi-megabyte re-encode there would delay
    // deadline enforcement for unrelated requests. The handoff costs the pause nothing in
    // accuracy, and cancelling the ScheduledFuture after it has fired is already not what stops a
    // resubmission -- resubmit re-checks request.terminal under the monitor before it offers.
    ScheduledFuture<?> pause =
        CLOCK.schedule(
            () -> Thread.ofVirtual().name("plowshare-sdk-resubmit").start(() -> resubmit(request)),
            pauseNanos(refusals),
            TimeUnit.NANOSECONDS);
    boolean terminal;
    synchronized (request) {
      terminal = request.terminal;
      if (!terminal) request.pause = pause;
    }
    if (terminal) pause.cancel(false);
  }

  /**
   * The pause owed for the refusals so far: half of the ceiling fixed and half random, so a client
   * always pauses and clients released together never retry in lockstep.
   *
   * <p>The exponent is a pacing input, not a bound. The bound is the deadline armed at the first
   * transmit, which is already in force here, so backoff time and the server's own processing time
   * both count against the caller's original timeout and a request refused over and over ends in a
   * timeout rather than retrying forever. There is no retry ceiling and none is wanted.
   */
  private long pauseNanos(int refusals) {
    long ceiling =
        Math.min(
            RESUBMIT_BACKOFF_CAP.toNanos(),
            RESUBMIT_BACKOFF_BASE.toNanos() << Math.min(refusals, 16));
    return ceiling / 2 + (long) (ceiling * Math.clamp(random.getAsDouble(), 0.0, 1.0) / 2);
  }

  /**
   * Rejoins the back of the dispatch queue once a refusal's pause has been waited out.
   *
   * <p>The previous attempt's frame and byte charge were released as soon as it was written, so the
   * frame is re-encoded from the request's own envelope and its bytes charged again here. The
   * encoding is deterministic -- the same fields, the same id, the same mapper -- and its length is
   * checked against the charge the request was admitted under rather than assumed. The connection
   * bound is re-checked too: a resubmission is new outgoing work and is refused like any other if
   * the connection cannot hold it.
   */
  private void resubmit(Pending request) {
    String frame;
    try {
      frame = JSON.writeValueAsString(request.envelope);
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      // The same envelope already encoded once, at admission.
      detach(
          request,
          null,
          new TransportException(
              Delivery.NOT_SUBMITTED, "Resubmitted request could not be encoded again"));
      return;
    }
    Throwable failure = null;
    synchronized (this) {
      synchronized (request) {
        if (request.terminal) return;
        request.pause = null;
        if (closed || lost)
          failure =
              new TransportException(
                  Delivery.NOT_SUBMITTED, "Connection closed while pacing a resubmission");
        else if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length != request.bytes)
          failure =
              new TransportException(
                  Delivery.NOT_SUBMITTED, "Resubmitted request did not re-encode to its own size");
        else if (queuedBytes + request.bytes > MAX_QUEUED_BYTES || !reserveProcess(request.bytes))
          failure =
              new TransportException(Delivery.NOT_SUBMITTED, "Request byte capacity exceeded");
        else {
          queuedBytes += request.bytes;
          request.released = false;
          request.frame = frame;
          if (!outgoing.offer(request))
            failure = new IllegalStateException("Reserved writer capacity unavailable");
        }
      }
    }
    if (failure != null) detach(request, null, failure);
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
      if (CAPACITY_EXHAUSTED.equals(outcome.get("code").asText()) && waiting.absorbs) {
        refused(waiting);
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
    releaseParked();
    for (Pending waiting : pending.values())
      detach(
          waiting,
          null,
          new TransportException(delivery(waiting), "Connection lost; no request was replayed"));
    checkDrained();
  }

  /**
   * How long an interrupted blocking caller waits for the exchange it just cancelled to report
   * where it actually stood. The cancellation settles a parked or queued request on the cancelling
   * thread itself; only a settlement that has to cross the completion executor needs this grace, so
   * it is a bound on a pathology, not a tunable deployment value.
   */
  private static final Duration INTERRUPT_SETTLEMENT = Duration.ofSeconds(1);

  static <T> T await(CompletionStage<T> stage) throws IOException {
    return await(stage, null);
  }

  /**
   * Blocks for the reply, converting an interrupt into the delivery the exchange itself reports
   * rather than assuming the worst.
   *
   * <p>With the waiting submission form as the default, parking for dispatch capacity is the
   * ordinary way a blocking caller meets a full window, and such a request provably has not been
   * written. Reporting {@link Delivery#UNKNOWN} for it would invert the one delivery claim a caller
   * may act on without the replay prohibition. So when the caller owns a {@code cancellation}, the
   * interrupt is turned into that signal and the delivery is read off the resulting settlement:
   * {@code NOT_SUBMITTED} while the request is parked or queued, {@code UNKNOWN} once the frame has
   * been written. A reply that won the race is returned, since discarding a known answer is its own
   * dishonesty.
   *
   * <p>The interrupt is restored only after that wait: re-setting it first would make the wait
   * throw immediately and lose the settlement it exists to read. Without a cancellation signal
   * there is nothing to settle the exchange with, so the outcome genuinely is unknown.
   */
  static <T> T await(CompletionStage<T> stage, RequestCancellation cancellation)
      throws IOException {
    CompletableFuture<T> answer = stage.toCompletableFuture();
    try {
      return answer.get();
    } catch (InterruptedException interrupted) {
      if (cancellation == null) {
        answer.cancel(false);
        Thread.currentThread().interrupt();
        throw new TransportException(Delivery.UNKNOWN, "Interrupted; outcome may be unknown");
      }
      cancellation.cancel();
      try {
        return answer.get(INTERRUPT_SETTLEMENT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (ExecutionException settled) {
        throw interrupted(settled);
      } catch (InterruptedException | TimeoutException | CancellationException unsettled) {
        throw new TransportException(Delivery.UNKNOWN, "Interrupted; outcome may be unknown");
      } finally {
        Thread.currentThread().interrupt();
      }
    } catch (ExecutionException failed) {
      Throwable cause = cause(failed);
      if (cause instanceof IOException io) throw io;
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IOException("Plowshare request failed", cause);
    }
  }

  /** Keeps the cancelled exchange's own delivery and says what ended the wait. */
  private static IOException interrupted(ExecutionException settled) {
    return cause(settled) instanceof TransportException transport
        ? new TransportException(transport.delivery(), "Interrupted; " + transport.getMessage())
        : new TransportException(Delivery.UNKNOWN, "Interrupted; outcome may be unknown");
  }

  private static Throwable cause(ExecutionException failed) {
    Throwable cause = failed.getCause();
    while (cause instanceof CompletionException && cause.getCause() != null)
      cause = cause.getCause();
    return cause;
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
