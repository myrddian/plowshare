package io.aeyer.plowshare.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.JobDelta;
import io.aeyer.plowshare.protocol.JobEvent;
import io.aeyer.plowshare.protocol.frames.Code;
import io.aeyer.plowshare.protocol.frames.Envelope;
import io.aeyer.plowshare.protocol.frames.Outcome;
import io.aeyer.plowshare.protocol.transport.ConcurrentDispatch;
import io.aeyer.plowshare.server.agents.JobEvents;
import io.aeyer.plowshare.server.agents.Watchers;
import io.aeyer.plowshare.server.events.AccountPushes;
import io.aeyer.plowshare.server.events.SpeakerHandles;
import io.aeyer.plowshare.server.faults.Fault;
import io.aeyer.plowshare.server.faults.Faults;
import io.aeyer.plowshare.server.session.Role;
import io.aeyer.plowshare.server.session.SessionRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The listener role's socket: a client that wants to watch a session's jobs.
 *
 * <h2>What this is</h2>
 *
 * <p>The socket, the attachment, the lifecycle, and — since task 5 — the delivery. A running job
 * publishes through {@link JobEvents}, which this class implements; {@link #publish} finds the
 * socket by asking {@link SessionRegistry} for the session's {@link Role#LISTENER}, which is why
 * what attaches is the {@link WebSocketSession} itself rather than a wrapper this class would then
 * have to keep in step with it. <b>What a connection needs besides the socket lives in that
 * socket's own attribute map</b> and not in a second map beside the registry — the thing {@code
 * FileChannelHandler} removed one role over.
 *
 * <h2>Publishing does not write to the socket, and a job's liveness depends on that</h2>
 *
 * <p>{@link #publish} runs on the virtual thread of the job that produced the event, between a
 * budget claim and a model call. If it wrote to the socket there, a listener whose reader has
 * stalled would hold that job's turn: {@code FileChannelHandler} measured the only real bound on
 * this classpath (tomcat-embed-websocket 10.1.31) at Tomcat's {@code DEFAULT_BLOCKING_SEND_TIMEOUT}
 * of <b>20000 ms</b>, and twenty seconds of a turn spent on a user interface is a job held hostage
 * by one.
 *
 * <p>So a published event is <em>offered to a bounded queue</em> and the write happens on that
 * listener's own virtual thread. Three properties fall out, and each is the answer to a question
 * this channel is otherwise wrong about:
 *
 * <ul>
 *   <li><b>nothing a job does here waits on the network.</b> {@code ArrayBlockingQueue.offer} takes
 *       a lock held only for the enqueue itself — the drain thread releases it while it is parked
 *       in {@code take()} and holds none of it while it writes;
 *   <li><b>one thread per listener, so events arrive in the order they were produced.</b> A pool
 *       would let a burst overtake itself, and an ENDED arriving before the MODEL_CALL it followed
 *       is a lie about a run;
 *   <li><b>a full queue drops, and dropping is the contract.</b> The design spec says the stream is
 *       droppable and not durable: what a run came to is in {@code JobStore}, behind {@code GET
 *       /v1/jobs/&#123;id&#125;} and — since slice 4's console needed something to reconcile a
 *       dropped event against — {@code GET /v1/jobs}. Those are the contractual record and this
 *       stream is not. Buffering without a bound would make one stalled reader this process's
 *       memory problem.
 * </ul>
 *
 * <p><b>A response is not droppable, and the third bullet does not reach it.</b> Everything above
 * is an argument about <em>events</em>, and every step of it rests on the same fact: a listener
 * that lost one can ask {@code GET /v1/jobs/&#123;id&#125;} what the run came to. <b>There is no
 * such question for an answer to a frame.</b> A response's {@code id} is client-generated and this
 * server keeps no record of it, so a dropped response is not a frame a caller can go and fetch — it
 * is an {@code id} that is never resolved and a caller that never fails. Blocking instead is the
 * thing this whole section exists to forbid. What is left is spec §3.4's own sentence — <em>a
 * client that will not drain is disconnected rather than tolerated</em> — so {@link
 * Delivery#answer} closes the socket when the queue will not take a response. A close is observed
 * by the client, and it fails every outstanding {@code id} at once rather than one of them silently
 * and forever. The client reconnects and asks again, which is the thing it could not do if it were
 * still waiting.
 *
 * <p><b>The close is not made on the thread that could not queue.</b> A close frame is a socket
 * write like any other, so it goes on a thread of its own for exactly the reason the rest of this
 * section gives; the thread that was trying to answer returns immediately either way.
 *
 * <h2>Two frame shapes travel out of here, and that is deliberate</h2>
 *
 * <p>A <b>push</b> is a bare {@link JobEvent} serialised straight to JSON, with no envelope around
 * it. A <b>response</b> is an {@link Envelope} carrying the request's own {@code id} and an {@link
 * Outcome} as its payload. <b>The two shapes coexist on this socket on purpose</b>, and the reason
 * is not that pushes were forgotten: the web console consumes the bare shape today, and enveloping
 * it would be a wire-format change to a live consumer made by a slice that promised none.
 *
 * <p>So spec §3.1's "server pushes carry no {@code id}" describes where this is going rather than
 * where it is — a push here carries no envelope to have an {@code id} absent from. Enveloping them
 * is its own slice and it lands when the console moves, which §4 puts last. Until then, a client on
 * this socket tells the two apart by whether the frame has a {@code protocol_version}.
 *
 * <p><b>Interleaving is the client's problem and the spec says so</b> (§3.5): a client may not
 * assume a response arrives before an event its request caused, or after. Both orders really happen
 * — a handler that publishes before it returns produces one, a job that publishes after the answer
 * was queued produces the other — and nothing here imposes an order between them, because both go
 * through the one queue in the order they were offered to it.
 *
 * <p><b>Why not {@code ConcurrentWebSocketSessionDecorator}</b>, which {@code FileChannelHandler}'s
 * {@code send} names as "the right shape for a broadcast nobody is blocked on" — which is exactly
 * what this channel is. It is the right shape and it is not sufficient <b>on the one property this
 * class needs most</b>: it buffers only when it cannot take its flush lock, so the first sender
 * still performs the blocking write inline, on the job's thread. Read off its bytecode on this
 * classpath (spring-websocket 6.1.14) rather than assumed: {@code sendMessage} adds to the buffer
 * and calls {@code tryFlushMessageBuffer}, which on winning {@code Lock.tryLock()} calls the
 * delegate's blocking {@code sendMessage} directly. A queue drained by another thread is the same
 * trade carried one step further, and it is the step that makes "a job cannot be held hostage" true
 * rather than usually true.
 *
 * <h2>Why a second handler rather than a second message type on the file channel</h2>
 *
 * <p>{@code FileChannelHandler} carries a worked-out story about interleaving — a request-reply
 * correlation, a displaced socket, a send lock — and is the one component in this system holding a
 * correctness argument about two answers in flight at once. Putting job events on it would mean
 * editing that frame format and that dispatch. A second path costs the command-line client one
 * extra connection and cannot break the file channel at all, which is the trade the design spec
 * settles.
 *
 * <h2>The id comes off the URI, by one convention rather than two</h2>
 *
 * <p>{@code /v1/events?session=<id>}, read with {@code FileChannelHandler.sessionId} and named by
 * its {@code SESSION_PARAM} — the same method and the same constant the file channel uses, called
 * rather than copied. Two handlers with two private parsers is two conventions that agree until one
 * of them is changed.
 *
 * <h2>Displacement is the file channel's story, one layer down</h2>
 *
 * <p>A second listener under a live id displaces the first: the newer connection is the one a human
 * is looking at. {@link SessionRegistry#attach} hands back what it displaced and closes nothing,
 * because it holds no socket and must not know what a role attached; <b>closing the loser is this
 * class's part</b>, and it is the same reasoning {@code FileChannelHandler} records — a socket left
 * open keeps a Tomcat session alive until its peer goes, which can be minutes on a half-open
 * connection, so a client in a reconnect loop would accumulate them.
 *
 * <p>Nothing is failed on the way out, and that is the difference from the file channel: a
 * displaced listener has no outstanding requests, because a listener never asks for anything. The
 * event stream is droppable by design — a job whose listener has gone finishes and files its
 * outcome exactly as one that never had a listener, and the outcome is readable over {@code GET
 * /v1/jobs/{id}}.
 *
 * <h2>A reattaching listener gets new events and never replayed ones</h2>
 *
 * <p>The design spec lists this as open and says it should be settled with the droppable-stream
 * decision. It is, and this is the settlement: a connection gets an empty queue, and what happened
 * while nobody was attached happened. <b>The alternative is not "replay" but "a second store"</b> —
 * replaying means keeping a per-session history for a client that may never come back, which is a
 * durable record of a run, which {@code JobStore} already is at the only granularity that is
 * contractual. A client that reconnects and wants to know where a job got to asks {@code GET
 * /v1/jobs/&#123;id&#125;}, which is the question it would have been asking the replay.
 *
 * <p>Same answer, same reason, for a listener a burst overran: no replay, no gap-filling, and no
 * sequence number that would invite either.
 */
public final class EventChannelHandler extends TextWebSocketHandler
    implements JobEvents,
        AccountPushes,
        io.aeyer.plowshare.server.information.InformationInvalidations,
        SpeakerHandles,
        SessionPushes,
        org.springframework.web.socket.SubProtocolCapable,
        org.springframework.context.SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EventChannelHandler.class);

  /**
   * Where a client opens the socket. Under {@code /v1} with the rest of the surface, and beside
   * {@code /v1/files} because it is the second role of the same session rather than a second
   * contract.
   */
  public static final String PATH = "/v1/events";

  /**
   * How many events one listener may be behind before the next is dropped.
   *
   * <p><b>A bound on memory and not a promise about completeness</b>, and the number is scaled to
   * two measurements rather than picked because it sounds generous.
   *
   * <ul>
   *   <li><b>what a job produces is small.</b> A run that answers on its first turn is 3 events;
   *       {@code JobEventTest} watches a two-turn run with one tool call produce 5. A run cannot
   *       exceed its definition's own {@code max-turns} model calls plus the tools those turns
   *       asked for, so this holds dozens of <em>complete</em> job histories for a listener that
   *       has stopped reading — which is far more than a human watching a live view could have
   *       fallen behind by and still want;
   *   <li><b>what a full queue costs is measured</b>, on the JVM this build runs — JBR 21.0.8, heap
   *       delta across repeated {@code System.gc()}, {@code -Xmx3g}, ten thousand full queues held
   *       at once. A queue of 256 events retains <b>25.8 KB</b>, which is <b>100 bytes an
   *       event</b>. So a hundred stalled listeners cost about 2.5 MB, and there is one queue per
   *       attached listener rather than per session or per job.
   * </ul>
   *
   * <p><b>Public because the test that measures the dropping needs to overrun it</b>, and a test
   * that hard-coded its own idea of the capacity would pass the day somebody changed this and
   * stopped overrunning anything.
   *
   * <p>A listener that falls this far behind has lost events and is not told so. That is the
   * droppable stream working as specified rather than a gap: the record of what a run came to is
   * {@code JobStore}'s, and a sequence number here would only invite a client to try to fill holes
   * this channel has no way to refill.
   */
  public static final int PENDING = 256;

  /**
   * How many token deltas one connection may fall behind by.
   *
   * <p><b>Small on purpose.</b> Deltas are a typing effect; a client that is behind wants the text
   * as it is now, not a minute of it delivered late. Sixty-four is about a second of one model's
   * output at the rate measured on 2026-09-02, which is long enough to ride out a hiccup and short
   * enough that a genuinely slow reader loses tokens instead of hoarding them.
   *
   * <p>It is <b>not</b> {@link #PENDING} and must not become it: these bounds describe opposite
   * requirements. A full lifecycle queue means a client is losing things it cannot recover; a full
   * token queue means a client is losing things that never mattered.
   */
  public static final int STREAMING = 64;

  /**
   * Where a connection's delivery hangs, on the socket's own attribute map beside the session id.
   * Not a second map in this class: the registry replaced the one {@code FileChannelHandler} had,
   * and a new one here would be that mistake arriving through the other role.
   */
  private static final String DELIVERY = "events.delivery";

  private io.aeyer.plowshare.protocol.transport.PacketBudget packetBudget =
      io.aeyer.plowshare.protocol.transport.SegmentedMessages.PROCESS_BUDGET;

  void usePacketBudget(io.aeyer.plowshare.protocol.transport.PacketBudget budget) {
    packetBudget = java.util.Objects.requireNonNull(budget);
  }

  @Override
  public java.util.List<String> getSubProtocols() {
    return java.util.List.of(io.aeyer.plowshare.protocol.transport.SegmentedMessages.SUBPROTOCOL);
  }

  /** The socket attribute ws.HandleInterceptor copies AuthFilter's handle into. */
  public static final String HANDLE = "handle";

  /**
   * Every socket currently signed in as each account, so a push can reach all of them without a
   * scan of the whole registry.
   */
  private final ConcurrentHashMap<String, Set<WebSocketSession>> byHandle =
      new ConcurrentHashMap<>();

  /**
   * A plain mapper, and still deliberately not a copy of {@code FileChannelHandler}'s.
   *
   * <p><b>This channel is no longer one-way, and the old reason for the difference has gone with
   * that.</b> It used to be that {@link #handleTextMessage} dropped what a listener sent without
   * parsing it, so there was no reading here for a leniency setting to be about. There is reading
   * here now — but not of a typed record: {@link FrameRouter} owns the binding of a frame to an
   * {@link Envelope}, on <em>its</em> mapper, which is lenient for exactly the reason that one's
   * javadoc gives. What this mapper reads is the untyped {@code Map} {@link #correlationOf} pulls
   * an {@code id} and a {@code type} out of, and an untyped map has no unknown property to fail on.
   * So the setting would change nothing here, and adding it would imply a second opinion about
   * frames that this class does not hold.
   *
   * <p>The forward-compatibility this wire does need on the way out is {@code JobEvent}'s — its
   * kinds and its ending travel as strings so a client built against a later server binds a word it
   * has never heard of.
   *
   * <p><b>What it does need on the way out, and did not have, is the HTTP surface's own opinion
   * about dates.</b> {@link FrameJson#answering()} is that opinion, in one place; a bare mapper
   * cannot serialise an {@link java.time.Instant} at all, and a frame answering with an endpoint's
   * page of entries carries two of them. See {@link FrameJson} for what that would have cost.
   * Nothing about {@code JobEvent} changes: it has no temporal component, so a push is written
   * exactly as it always was.
   */
  private final ObjectMapper json = FrameJson.answering();

  private EventReadCapacity readCapacity = new EventReadCapacity(2, 8);
  private boolean readDispatchStarted;
  private ConcurrentRequests concurrentRequests;
  private int requestPerConnection = 2;
  private int requestGlobal = 0;
  private int requestObservers = ConcurrentRequests.DEFAULT_OBSERVERS;

  /**
   * Per-connection call window advertised in the dispatch handshake. Clients adopt it as their own
   * limit, so it is a statement about how much work this server will queue for one connection, not
   * a protocol constant. It bounds queueing, not execution (see {@code requestPerConnection}).
   * Defaults to 8 so a full queue refuses sooner and visibly; the cost is that the Java, Go, Python
   * and .NET SDKs, which fail rather than block at the bound, refuse their 9th concurrent request.
   * See the {@code plowshare.transport.request-depth} comment in {@code EventChannelConfig}.
   */
  private int requestDepth = 8;

  private java.time.Duration requestDrain = java.time.Duration.ofSeconds(10);
  private volatile boolean lifecycleRunning;
  private volatile boolean accepting = true;
  private final AtomicBoolean drainRecorded = new AtomicBoolean();
  private final DispatchTelemetry telemetry = new DispatchTelemetry();
  private volatile DispatchTelemetry.Snapshot lastTraceMetrics;

  /** Aggregate measurements have fixed fields and never include operation/resource identifiers. */
  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${plowshare.transport.request-metrics-interval:PT30S}")
  public void reportDispatchMetrics() {
    if (!log.isTraceEnabled()) return;
    var snapshot = telemetry.snapshot();
    if (snapshot.admitted() == 0 && snapshot.encoding().count() == 0) return;
    if (!snapshot.equals(lastTraceMetrics)) {
      lastTraceMetrics = snapshot;
      log.trace("Event request dispatch measurements {}", snapshot);
    }
  }

  /**
   * Low-frequency operational summary, including idle periods. Counters and timings are cumulative;
   * gauges describe current ownership. Reporting never resets the measurements.
   */
  @org.springframework.scheduling.annotation.Scheduled(
      initialDelayString = "${plowshare.transport.request-metrics-summary-interval:PT10M}",
      fixedDelayString = "${plowshare.transport.request-metrics-summary-interval:PT10M}")
  public void summarizeDispatchMetrics() {
    log.info(
        "Event request dispatch summary (cumulative counters/timings, current gauges) {}",
        telemetry.snapshot());
  }

  DispatchTelemetry.Snapshot dispatchMeasurements() {
    return telemetry.snapshot();
  }

  @Override
  public void start() {
    lifecycleRunning = true;
  }

  @Override
  public boolean isRunning() {
    return lifecycleRunning;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void stop() {
    drainRequests();
    lifecycleRunning = false;
  }

  /** Configure one shared server read bound before attaching segmented sockets. */
  synchronized void useReadConcurrency(int perConnection, int global) {
    if (readDispatchStarted)
      throw new IllegalStateException("Event read concurrency cannot change after dispatch starts");
    readCapacity = new EventReadCapacity(perConnection, global);
  }

  /** Request execution bounds as configured; {@code global == 0} means unlimited. */
  record RequestBounds(int perConnection, int global, int observers) {}

  synchronized RequestBounds requestBounds() {
    return new RequestBounds(requestPerConnection, requestGlobal, requestObservers);
  }

  /**
   * Configures how many domain requests may execute at once, per connection and across the server
   * ({@code 0} = unlimited), independently of event-read concurrency. A global cap here used to be
   * an unwritten stand-in for the database pool; that pool now bounds itself (see {@code
   * spring.datasource.hikari} in {@code application.yml}), so the default is no transport cap. Must
   * be called before dispatch starts.
   */
  synchronized void useRequestBounds(int perConnection, int global, int observers) {
    if (readDispatchStarted) throw new IllegalStateException("Request dispatch already started");
    if (perConnection < 1
        || global < 0
        || (global != 0 && global < perConnection)
        || observers < perConnection)
      throw new IllegalArgumentException(
          "Request bounds need per-connection >= 1, global 0 (unlimited) or >= per-connection, "
              + "and observers >= per-connection");
    requestPerConnection = perConnection;
    requestGlobal = global;
    requestObservers = observers;
  }

  private synchronized ConcurrentRequests concurrentRequests() {
    if (!accepting) throw new IllegalStateException("Request dispatch is stopping");
    readDispatchStarted = true;
    if (concurrentRequests == null)
      concurrentRequests =
          new ConcurrentRequests(
              packetBudget, requestPerConnection, requestGlobal, requestObservers, telemetry);
    return concurrentRequests;
  }

  /**
   * Sets the depth advertised to clients in the dispatch handshake, within {@code [1,
   * ConcurrentDispatch.MAX_PENDING]}. Must be called before dispatch starts, because connections
   * already handshaken would otherwise hold a stale window.
   */
  synchronized void useRequestDepth(int depth) {
    if (readDispatchStarted) throw new IllegalStateException("Request dispatch already started");
    if (depth < 1 || depth > ConcurrentDispatch.MAX_PENDING)
      throw new IllegalArgumentException(
          "Request depth must be between 1 and " + ConcurrentDispatch.MAX_PENDING);
    requestDepth = depth;
  }

  private synchronized int advertisedRequestDepth() {
    return requestDepth;
  }

  synchronized void useRequestDrain(java.time.Duration drain) {
    if (readDispatchStarted) throw new IllegalStateException("Request dispatch already started");
    if (drain == null
        || drain.isNegative()
        || drain.isZero()
        || drain.compareTo(java.time.Duration.ofMinutes(5)) > 0)
      throw new IllegalArgumentException("Request drain must be positive and at most five minutes");
    requestDrain = drain;
  }

  @jakarta.annotation.PreDestroy
  public void drainRequests() {
    ConcurrentRequests requests;
    synchronized (this) {
      accepting = false;
      requests = concurrentRequests;
    }
    if (requests == null) return;
    boolean record = drainRecorded.compareAndSet(false, true);
    long started = System.nanoTime();
    requests.stopAdmission();
    try {
      requests
          .drained()
          .toCompletableFuture()
          .get(requestDrain.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException unfinished) {
      log.warn("Request dispatcher did not finish its shutdown drain");
    } finally {
      requests.close();
      if (record) {
        telemetry.drain.record(System.nanoTime() - started);
        reportDispatchMetrics();
      }
    }
  }

  private synchronized EventReadCapacity readCapacity() {
    readDispatchStarted = true;
    return readCapacity;
  }

  private volatile UsageSubscriptions usageSubscriptions;

  public void useUsageSubscriptions(UsageSubscriptions source) {
    usageSubscriptions = source;
  }

  /**
   * The shape a correlation read wants: whatever the frame's top level is, untyped, because the
   * typed read is {@link FrameRouter}'s.
   */
  private static final TypeReference<Map<String, Object>> AS_MAP = new TypeReference<>() {};

  private final Watchers watchers;
  private final SessionRegistry sessions;

  /**
   * Where the router comes from, asked <b>on the first frame</b> rather than at construction.
   *
   * <h2>This is the edge the boot cycle was broken at, and why this one</h2>
   *
   * <p>The graph closed a loop: {@code jobStore} publishes through this class, so it takes this
   * handler; this handler routed frames, so it took {@code frameRouter}; the router is assembled
   * from every {@link FrameArea}, one of which is {@code AgentFrames}; and that area takes the
   * agent services, which reach {@code jobStore}. Four edges, and a container can only report the
   * loop, not choose which edge was the wrong one.
   *
   * <p><b>Exactly one of those four is not needed when the bean is built.</b> A router turns a
   * client's frame into an {@link Outcome}, and no frame can arrive until the web server is
   * listening, which is after the context has refreshed. Every other edge is a real
   * construction-time dependency: {@code AgentFrames} builds its handlers inside {@code frames()}
   * and hands each one its service, {@code jobStore} composes a runtime it then holds, and {@link
   * FrameRoutingConfig} cannot union maps it has not been given. Deferring any of those would hand
   * something a service that was still half-built — the failure that is silent rather than loud.
   * Deferring this one cannot: nothing here touches the router until {@link #handleTextMessage},
   * and by then every bean is finished.
   *
   * <p>A {@link Supplier} and not an {@code ObjectProvider}: what a context that assembled no table
   * falls back to is {@code EventChannelConfig}'s argument to make and its javadoc to carry, so the
   * {@code getIfAvailable} belongs in the lambda that class passes, and this one stays a socket
   * handler rather than a second place that knows what an empty surface answers. {@code
   * RequestedAgent} taking {@code ObjectProvider<AgentRegistry>} is the same deferral one layer
   * closer to Spring, for the same reason: a dependency that is absent or late must not be one the
   * container has to resolve first.
   *
   * <p>Resolved once and remembered, in {@link #router()}. A supplier asked per frame would rebuild
   * the empty fallback on every message and would let two frames answer through two different
   * tables; the surface a client is talking to does not change while it is talking.
   */
  private final Supplier<FrameRouter> routers;

  /** The resolved router, or null until the first frame asks for one. */
  private volatile FrameRouter router;

  /**
   * @param sessions the one registry for this server
   * @param router what turns a client's frame into an answer, already built. The direct form, for a
   *     caller that has one — see {@link #EventChannelHandler(SessionRegistry, Supplier)} for the
   *     deferred form the application context has to use, and why
   */
  /**
   * <b>{@link Watchers} is required, and the convenience that invented one is gone.</b>
   *
   * <p>It used to default to {@code new Watchers()}, which is how the token stream came to be built
   * on both sides and inert in production: {@code EventChannelConfig} took that default, {@code
   * JobStreamHandler} took the {@code @Component}, and the object recording a subscription was
   * never the object asked about it. {@code streaming(session)} answered false forever and nothing
   * anywhere said so.
   *
   * <p>A collaborator a constructor invents is a collaborator nobody wired, and this one had to be
   * the same instance on both sides to mean anything. So there is no arrangement now in which a
   * caller gets one by accident.
   */
  public EventChannelHandler(SessionRegistry sessions, FrameRouter router, Watchers watchers) {
    this(sessions, held(router), watchers);
  }

  /**
   * @param sessions the one registry for this server. Injected rather than created here: the whole
   *     point of a session is that the two roles find each other in it, so a handler holding a
   *     registry of its own would be a listener attached to a session no job could see
   * @param routers where to get what turns a client's frame into an answer, asked once, on the
   *     first frame. Injected for a second reason on top of that one: the router is the half of
   *     this channel that <b>cannot write to a socket</b> — see its own class javadoc — and a
   *     router this class built for itself would be a routing table nobody outside could read,
   *     which is the property the plan's hand-built map exists to have. Deferred rather than taken
   *     outright for the reason on {@link #routers}
   */
  public EventChannelHandler(
      SessionRegistry sessions, Supplier<FrameRouter> routers, Watchers watchers) {
    this.watchers = Objects.requireNonNull(watchers, "watchers");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.routers = Objects.requireNonNull(routers, "routers");
  }

  private static Supplier<FrameRouter> held(FrameRouter router) {
    Objects.requireNonNull(router, "router");
    return () -> router;
  }

  /**
   * The routing table, asked for once and then remembered.
   *
   * <p>Package-visible rather than private so {@code BootWiringTest} can ask a scanned context's
   * handler which table it found. That assertion is the difference between a cycle broken and a
   * cycle hidden: the fallback this can return is a router claiming no type, and a handler that
   * resolved to <em>that</em> on a fully wired server would answer every frame {@code NOT_FOUND}
   * without failing anything.
   *
   * @return the router every frame on this channel is answered through
   * @throws NullPointerException if the supplier answers null, which is a wiring mistake and not a
   *     surface that declares nothing — {@code EventChannelConfig} answers the latter with a router
   *     claiming no type
   */
  FrameRouter router() {
    FrameRouter known = router;
    if (known == null) {
      synchronized (this) {
        known = router;
        if (known == null) {
          known = Objects.requireNonNull(routers.get(), "router");
          router = known;
        }
      }
    }
    return known;
  }

  private SocketAuthorization socketAuthorization;

  @org.springframework.beans.factory.annotation.Autowired
  public void useSocketAuthorization(SocketAuthorization authorization) {
    this.socketAuthorization = authorization;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) throws IOException {
    if (socketAuthorization != null && !socketAuthorization.attach(socket)) return;
    String id = FileChannelHandler.sessionId(socket.getUri());
    if (id == null || id.isBlank()) {
      // Refused rather than accepted and left to receive nothing. The
      // registry refuses a blank id one layer down, and it is right to;
      // but it can only throw, and the party that needs telling is the
      // client, which is reachable from here and nowhere else.
      log.warn(
          "A client opened {} without a '{}' parameter; closing it, since a listener"
              + " on a session nothing can name is one no job will ever reach.",
          PATH,
          FileChannelHandler.SESSION_PARAM);
      socket.close(
          CloseStatus.BAD_DATA.withReason(
              "open " + PATH + "?" + FileChannelHandler.SESSION_PARAM + "=<id>"));
      return;
    }
    String claimant = FileChannelHandler.handleOn(socket);
    if (!sessions.claim(id, claimant)) {
      // ANOTHER ACCOUNT'S ID, refused before this socket has a delivery, a
      // place in byHandle or the session param its close would act on, and it
      // never displaces the listener there: a session id is held by the first
      // account that claimed it, on both sockets (Enzo's decision of
      // 2026-09-30, spec 2026-09-30-local-hooks-are-served).
      log.warn(
          "Session '{}' is held by another account; refusing a second account's"
              + " listener rather than letting it take over.",
          id);
      FileChannelHandler.refuseAsHeld(socket, id);
      return;
    }
    // The id on the socket, so the close callback can name the session
    // without re-parsing a URI that a container is free to have finished
    // with by then.
    socket.getAttributes().put(FileChannelHandler.SESSION_PARAM, id);
    // The delivery before the attach, and the order is the whole of why
    // publish need not defend itself: a job that finds this socket in the
    // role finds a socket that already has somewhere to put an event, so
    // there is no window in which a listener is attached and unreachable.
    Delivery delivery = new Delivery(socket, id, json, packetBudget, telemetry);
    if (delivery.transport instanceof PacketEventTransport) {
      delivery.requests =
          new EventRequests(
              packetBudget,
              frame -> {
                try {
                  routeFrame(socket, delivery, frame);
                } catch (RuntimeException | Error failure) {
                  delivery.stop();
                  try {
                    socket.close(CloseStatus.SERVER_ERROR.withReason("operation dispatch failed"));
                  } catch (IOException closing) {
                    failure.addSuppressed(closing);
                  }
                  throw failure;
                }
              },
              readCapacity());
    }
    UsageSubscriptions usage = usageSubscriptions;
    if (usage != null) {
      usage.connect(socket.getId(), claimant, delivery::snapshot);
      delivery.whenStopped = () -> usage.disconnect(socket.getId());
    }
    socket.getAttributes().put(DELIVERY, delivery);
    delivery.start();
    Object handle = socket.getAttributes().get(HANDLE);
    if (handle instanceof String account && !account.isBlank()) {
      byHandle.computeIfAbsent(account, a -> ConcurrentHashMap.newKeySet()).add(socket);
    }
    // The cast is this class's own claim being checked: nothing else puts
    // anything in the listener role, so something other than a socket in it
    // is a wiring fault, and a ClassCastException at the seam says so where
    // an ignored attachment would leave a client silently unclosed.
    SessionRegistry.Attached attached = sessions.attach(id, Role.LISTENER, socket, claimant);
    if (attached.refused()) {
      // Not reachable after the claim above, which is never undone; closed
      // rather than trusted if it ever is. Its close stops the delivery.
      log.error("Session '{}' refused a listener it had just claimed.", id);
      FileChannelHandler.refuseAsHeld(socket, id);
      return;
    }
    attached.displaced().ifPresent(displaced -> close((WebSocketSession) displaced, id));
    log.info("Session '{}' opened the event channel.", id);
  }

  /**
   * Answers one request frame.
   *
   * <p><b>This used to drop everything a client sent, unparsed</b>, on the argument that nothing
   * was defined for a listener to send. Something is now: a frame reaching here goes to {@link
   * FrameRouter}, and the {@link Outcome} it hands back is offered to this connection's queue as a
   * response. The old method's other half survives unchanged and matters more than ever — <b>no
   * domain-frame validation exception costs it the socket</b>, because this connection's whole job
   * is still to be there when a job starts producing. A frame this server cannot read is answered
   * with a refusal. Malformed transport packets close the connection and discard partial assemblies
   * before any domain operation runs.
   *
   * <p><b>Stated as "exception" and not "anything", because the catch below is {@link
   * RuntimeException} and not {@link Throwable}.</b> An {@link Error} — an {@code
   * OutOfMemoryError}, a {@code StackOverflowError} — still escapes into Spring's {@code
   * ExceptionWebSocketHandlerDecorator} on legacy sockets; the segmented dispatcher closes its
   * socket on the same failure. That is the right outcome rather than a gap to widen the catch
   * over: a JVM that has just said it cannot continue is not one to answer the next frame from, and
   * the honest signal to a client whose events are about to stop arriving is the socket closing.
   * {@link FrameRouter#route(Envelope, Asking)} draws the same line for the same reason.
   *
   * <p>Segmented operations use a bounded connection-owned dispatcher with audited independent
   * reads. The container's reader must remain free to receive packet credits while an operation
   * waits on persistence; otherwise an unrelated push times out and closes a healthy connection.
   * Legacy JSON keeps inline routing unless concurrent dispatch is explicitly selected. Neither
   * routing path writes the answer inline: {@link Delivery#answer} queues. Queued operations
   * recheck socket authority before execution, and closing discards queued work without replaying
   * or cancelling an operation already started.
   *
   * <p><b>The correlation read is separate from the routing read, and is allowed to fail.</b>
   * {@link FrameRouter#route(String, Asking)} is the one place that decides what a malformed frame
   * or a wrong {@code protocol_version} means, and restating any of that here would be the second
   * mapping the whole slice is built to avoid. But a frame that fails there still has an {@code id}
   * this server would like to answer under, and a router that answers with an {@link Outcome}
   * carries no {@code id} back. So the frame is read a second time, untyped, purely for its {@code
   * id} and {@code type} — a read that is expected to come back empty for a frame that was not JSON
   * at all, and whose failure changes nothing about the answer.
   */
  @Override
  protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
    if (socketAuthorization != null && !socketAuthorization.current(socket)) return;
    Object held = socket.getAttributes().get(DELIVERY);
    if (!(held instanceof Delivery delivery)) {
      // A connection afterConnectionEstablished refused: it has no queue,
      // so there is nowhere to put an answer and it is already closing.
      // The frame's content is not logged — a client's bytes are the
      // client's, and a log is not where they get their second copy.
      log.debug(
          "Session '{}' sent {} bytes on a socket that never attached; dropped.",
          named(socket),
          message.getPayloadLength());
      return;
    }
    String frame;
    try {
      var assembled = delivery.transport.receive(message.getPayload());
      if (assembled.isEmpty()) return;
      frame = assembled.get();
    } catch (IOException | RuntimeException invalidPacket) {
      delivery.stop();
      try {
        socket.close(CloseStatus.BAD_DATA.withReason("invalid packet transport"));
      } catch (IOException closing) {
        log.debug("Packet socket could not close");
      }
      return;
    }
    if (!accepting) {
      delivery.disconnect("server stopping");
      return;
    }
    if (concurrentFrame(socket, delivery, frame)) return;
    if (delivery.requests == null) {
      routeFrame(socket, delivery, frame);
    } else {
      try {
        delivery.requests.offer(frame);
      } catch (IllegalArgumentException capacity) {
        delivery.requests.close();
        delivery.disconnect("request admission");
      }
    }
  }

  /**
   * Private transport control is admitted before domain routing and never grants domain authority.
   */
  private boolean concurrentFrame(WebSocketSession socket, Delivery delivery, String frame) {
    com.fasterxml.jackson.databind.JsonNode body;
    try {
      body = json.readTree(frame);
    } catch (IOException | RuntimeException malformed) {
      if (delivery.concurrent == null) {
        // Unselected peers retain the original correlated refusal for malformed JSON. Dispatch
        // negotiation must not silently turn an old-mode bad request into a connection close.
        delivery.domainSeen = true;
        return false;
      }
      delivery.stop();
      delivery.disconnect("invalid request admission");
      return true;
    }
    try {
      boolean selection =
          body != null && ConcurrentDispatch.TYPE.equals(body.path("type").asText());
      if (!selection && delivery.concurrent == null) {
        delivery.domainSeen = true;
        return false;
      }
      if (body == null || !body.path("id").isTextual())
        throw new IllegalArgumentException("Invalid request ID");
      String id = body.get("id").textValue();
      ConcurrentDispatch.requireId(id);
      Envelope request;
      try {
        request = json.treeToValue(body, Envelope.class);
      } catch (IOException | RuntimeException malformedPayload) {
        if (selection)
          throw new IllegalArgumentException("Invalid dispatch selection", malformedPayload);
        // Valid correlation plus malformed operation input belongs to the router's correlated
        // refusal path. It must not disconnect other independent requests.
        request =
            new Envelope(id, body.path("type").asText("refused"), Envelope.CURRENT_VERSION, null);
      }
      if (selection) {
        synchronized (delivery) {
          if (delivery.domainSeen
              || delivery.concurrent != null
              || body.has("actingFor")
              || request.payload() == null
              || !request.payload().keySet().equals(Set.of("mode"))
              || !ConcurrentDispatch.MODE.equals(request.payload().get("mode")))
            throw new IllegalArgumentException("Dispatch selection must precede domain requests");
          new ConcurrentDispatch.Selection((String) request.payload().get("mode"));
          delivery.concurrent =
              concurrentRequests()
                  .connect(
                      socket.getId(),
                      (value, delivered) -> routeConcurrent(socket, delivery, value, delivered),
                      () -> delivery.disconnect("request execution"),
                      advertisedRequestDepth());
          delivery.validity =
              () -> {
                if (!delivery.running
                    || !socket.isOpen()
                    || socketAuthorization != null && !socketAuthorization.current(socket))
                  throw new io.aeyer.plowshare.server.faults.CallerFault("Query connection ended");
              };
        }
      } else {
        delivery.domainSeen = true;
      }
      try {
        delivery.concurrent.offer(frame, request, (String) socket.getAttributes().get(HANDLE));
      } catch (ConcurrentRequests.CapacityExceededException congested) {
        refuseCongested(delivery, request);
      }
      return true;
    } catch (RuntimeException invalid) {
      delivery.stop();
      delivery.disconnect("invalid request admission");
      return true;
    }
  }

  /**
   * Answers a request the dispatch queue had no room for, leaving the connection and every other
   * in-flight request untouched.
   *
   * <p>Only {@link ConcurrentRequests.CapacityExceededException} reaches here. Malformed frames,
   * duplicate ids and a closed dispatcher are protocol violations or shutdown and still tear the
   * connection down in the caller.
   *
   * <p><b>This reply deliberately bypasses the dispatcher's reply handoff.</b> The refused request
   * was never admitted: it has no {@code active} entry, no {@code PacketBudget} reservation for its
   * body and no claim on {@code Connection.handingOff}. Routing the refusal through the dispatcher
   * would take that single slot, and refusals are most frequent exactly when another reply is
   * mid-handoff, so it would throw "A reply handoff is already settling". It is instead queued on
   * the delivery's own output path with no dispatch callbacks, charged only against that path's
   * ordinary reply bounds. Because it owns nothing, the client may resubmit the same id as soon as
   * capacity frees; nothing here marks the id as used. Nothing was started, so a retry cannot
   * replay work.
   *
   * <p>If the refusal itself cannot be queued or budgeted it is dropped and the connection is kept
   * (see {@link Delivery#refuse}): the refused request goes unanswered and its client's deadline
   * expires, instead of every in-flight request on the connection being torn down.
   */
  private void refuseCongested(Delivery delivery, Envelope request) {
    delivery.refuse(
        request.id(),
        request.type(),
        Outcome.failed(
            Code.CAPACITY_EXHAUSTED, "Request queue is full; nothing was started, retry later"));
  }

  private java.util.concurrent.CompletionStage<Void> routeConcurrent(
      WebSocketSession socket,
      Delivery delivery,
      String frame,
      ConcurrentRequests.Delivery delivered) {
    if (!socket.isOpen()
        || !delivery.running
        || socketAuthorization != null && !socketAuthorization.current(socket)) {
      delivered.run();
      return java.util.concurrent.CompletableFuture.completedFuture(null);
    }
    try {
      var correlation = correlationOf(frame);
      String id = text(correlation.get("id"));
      String type = Optional.ofNullable(text(correlation.get("type"))).orElse(FrameTypes.REFUSED);
      var asking =
          new Asking(
              named(socket),
              (String) socket.getAttributes().get(HANDLE),
              socket.getId(),
              delivery.transport instanceof PacketEventTransport
                  ? io.aeyer.plowshare.server.relay.RelayReadBudget.SEGMENTED
                  : io.aeyer.plowshare.server.relay.RelayReadBudget.LEGACY,
              () -> {
                if (!socket.isOpen()
                    || !delivery.running
                    || socketAuthorization != null && !socketAuthorization.current(socket))
                  throw new io.aeyer.plowshare.server.faults.CallerFault("Query connection ended");
              });
      var answer =
          ConcurrentDispatch.TYPE.equals(type)
              ? java.util.concurrent.CompletableFuture.completedFuture(
                  Outcome.ok(
                      new ConcurrentDispatch.Selected(
                          ConcurrentDispatch.MODE,
                          advertisedRequestDepth(),
                          ConcurrentDispatch.MAX_ID_BYTES)))
              : router().routeAsync(frame, asking);
      return answer.thenAccept(
          outcome -> {
            if (!delivery.running || !socket.isOpen()) {
              delivered.run();
              return;
            }
            delivery.answer(
                id,
                type,
                outcome,
                delivered,
                ConcurrentDispatch.TYPE.equals(type)
                    ? delivery.validity
                    : () -> router().requireDisclosure(frame, asking, outcome),
                delivered::started,
                () -> delivered.executionFinished().toCompletableFuture().join());
            if (usageSubscriptions != null
                && outcome.payload() instanceof io.aeyer.plowshare.protocol.Usage.Initial initial)
              usageSubscriptions.ready(socket.getId(), initial.subscription());
          });
    } catch (RuntimeException invalid) {
      return java.util.concurrent.CompletableFuture.failedFuture(invalid);
    }
  }

  /** Recheck authority at execution, because a queued operation may outlive its account session. */
  private void routeFrame(WebSocketSession socket, Delivery delivery, String frame) {
    if (!socket.isOpen() || !delivery.running) return;
    if (socketAuthorization != null && !socketAuthorization.current(socket)) return;
    String session = named(socket);
    Map<String, Object> correlation = correlationOf(frame);
    var asking =
        new Asking(
            session,
            (String) socket.getAttributes().get(HANDLE),
            socket.getId(),
            delivery.transport instanceof PacketEventTransport
                ? io.aeyer.plowshare.server.relay.RelayReadBudget.SEGMENTED
                : io.aeyer.plowshare.server.relay.RelayReadBudget.LEGACY,
            () -> {
              if (!socket.isOpen())
                throw new io.aeyer.plowshare.server.faults.CallerFault("Query connection ended");
            });
    Outcome outcome;
    try {
      outcome = router().route(frame, asking);
    } catch (RuntimeException unexpected) {
      // A SAFETY NET, NOT A SECOND MAPPING. FrameRouter already turns
      // everything a handler throws into an Outcome, so nothing is
      // expected here — but Spring's ExceptionWebSocketHandlerDecorator
      // turns whatever escapes this method into a session-wide
      // SERVER_ERROR close, which is FileChannelHandler's measured lesson
      // and costs this listener every event it was opened for. What the
      // caller is told still comes from Faults, so the one mapping is
      // still the only one.
      Fault fault = Faults.of(unexpected);
      log.error(
          "Answering a frame on session '{}' failed outside the router.", session, unexpected);
      outcome = Outcome.failed(fault.code(), fault.detail());
    }
    String type = text(correlation.get("type"));
    // Independent reads can complete after a membership change, including while waiting for the
    // physical writer. Retain the same disclosure fence as negotiated concurrent dispatch without
    // changing the synchronous handler contract for older clients. This is a fresh boundary check,
    // not another scheduling admission or permission lookup used to construct a resource key.
    Outcome answer = outcome;
    delivery.answer(
        text(correlation.get("id")),
        type == null ? FrameTypes.REFUSED : type,
        answer,
        () -> {},
        type != null && DispatchPolicy.independentRead(type)
            ? () -> {
              if (!delivery.running
                  || !socket.isOpen()
                  || socketAuthorization != null && !socketAuthorization.current(socket))
                throw new io.aeyer.plowshare.server.faults.CallerFault("Query connection ended");
              router().requireDisclosure(frame, asking, answer);
            }
            : delivery.validity);
    if (usageSubscriptions != null
        && outcome.payload() instanceof io.aeyer.plowshare.protocol.Usage.Initial initial)
      usageSubscriptions.ready(socket.getId(), initial.subscription());
  }

  /**
   * The frame's top level as an untyped map, or an empty one if it was not an object at all. See
   * {@link #handleTextMessage}'s javadoc for why this read exists beside the router's and why
   * failing is one of its answers.
   */
  private Map<String, Object> correlationOf(String frame) {
    try {
      Map<String, Object> fields = json.readValue(frame, AS_MAP);
      return fields == null ? Map.of() : fields;
    } catch (IOException notAnObject) {
      return Map.of();
    }
  }

  /**
   * {@code value} when a client sent a string, and {@code null} when it sent a number, an object or
   * nothing — an {@code id} this server made up would be worse than one it left absent, since a
   * client correlates on it.
   */
  private static String text(Object value) {
    return value instanceof String said ? said : null;
  }

  @Override
  public void handleTransportError(WebSocketSession socket, Throwable error) {
    // Recorded and not acted on. FileChannelHandler records having measured
    // that an abrupt disconnect arrives here as an EOFException and then as a
    // 1006 close a millisecond later; nothing in this task re-measured that,
    // and nothing here needs it to be exact — the detaching is
    // afterConnectionClosed's either way, and this is only the half that says
    // whether the client went away or the network did.
    log.info("The event channel for session '{}' failed: {}", named(socket), error.toString());
  }

  @Override
  public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    // A socket that never routed a frame cannot own an acting context. Do not resolve the lazy
    // routing graph during teardown, especially while the application context is shutting down.
    FrameRouter currentRouter = router;
    if (currentRouter != null) currentRouter.disconnected(socket.getId());
    if (socketAuthorization != null) socketAuthorization.detach(socket);
    // Whatever else happens, this connection's drain thread stops. Before
    // the detach and outside every branch below, because a displaced socket
    // takes the second branch and would otherwise leave a thread parked on a
    // queue nothing will ever offer to again.
    stopDelivery(socket);
    // A SUBSCRIPTION DOES NOT OUTLIVE THE SOCKET THAT ASKED FOR IT. Without
    // this the set grows by one per session that ever asked, for the life of
    // the process, and a client that reconnected would be streaming again
    // without having said so -- which is the one thing opt-in is for.
    watchers.forget(named(socket));
    Object handle = socket.getAttributes().get(HANDLE);
    if (handle instanceof String account) {
      byHandle.computeIfPresent(
          account,
          (a, live) -> {
            live.remove(socket);
            return live.isEmpty() ? null : live;
          });
    }
    Object id = socket.getAttributes().get(FileChannelHandler.SESSION_PARAM);
    if (id == null) {
      // A connection refused above: it never attached, so there is nothing
      // to take out and no session to stamp.
      return;
    }
    if (sessions.detach(id.toString(), Role.LISTENER, socket)) {
      log.info("Session '{}' closed the event channel ({}).", id, status);
      return;
    }
    // Not attached any more, which is the ordinary shape of a socket that was
    // displaced and is only now finishing its close. The registry's detach is
    // identity-checked, so this call took nothing away from the connection
    // that replaced this one — the same call, through the same registry,
    // that FileChannelHandler's close makes for the other role, and the one
    // the mutant in
    // the_displaced_socket_closing_afterwards_leaves_its_replacement removes.
    log.debug(
        "A socket for session '{}' closed ({}) after it had already been replaced.", id, status);
  }

  /**
   * Close a socket this connection displaced.
   *
   * <p><b>The reason names no session id, and the measurement says why.</b> A close reason is a
   * bounded field and a session id is not — the registry asks only that an id be non-blank, so what
   * a client mints is its own business. Putting one in was tried: with the reason built as {@code
   * "...for session " + id} and a 200-character id, nothing is refused and nothing throws — the
   * container silently truncates, and the client receives 121 characters ending in an ellipsis.
   * <b>So the risk is not a crash but a half-id that still reads like one</b>, which a client or an
   * operator comparing it against the real one would find does not match. Naming the session buys
   * little here in the first place: this socket was opened for exactly one session and its client
   * knows which.
   *
   * <p>{@code FileChannelHandler}'s equivalent sentence does name its id. That is a difference of
   * wording and not of behaviour — the displacement itself is the story that class settled, reached
   * one layer down through {@link SessionRegistry}.
   */
  private static void close(WebSocketSession displaced, String id) {
    // Its queue goes with it. The container will call
    // afterConnectionClosed for this socket too and that call stops the
    // delivery again, which is why stop() is idempotent: the two paths
    // overlap and neither may depend on being the only one.
    stopDelivery(displaced);
    try {
      displaced.close(
          CloseStatus.NORMAL.withReason("replaced by a newer connection for this session"));
    } catch (IOException | IllegalStateException already) {
      // The two shapes FileChannelHandler records for "it is already going
      // away", caught here on its account rather than on a measurement of
      // this socket. Neither is a reason to refuse the new connection: the
      // caller is registering the socket a human is actually looking at,
      // and a loser that will not close is a loser that is closing.
      log.debug(
          "The displaced listener for session '{}' would not close: {}", id, already.toString());
    }
  }

  private static String named(WebSocketSession socket) {
    Object id = socket.getAttributes().get(FileChannelHandler.SESSION_PARAM);
    return id == null ? "(unnamed)" : id.toString();
  }

  private io.aeyer.plowshare.server.access.ProjectAuthorization projectAuthorization;

  @org.springframework.beans.factory.annotation.Autowired
  public void useProjectAuthorization(
      io.aeyer.plowshare.server.access.ProjectAuthorization authorization) {
    projectAuthorization = authorization;
  }

  private boolean projectDelivery(String session, String operation, Map<String, Object> payload) {
    return projectAuthorization == null
        || projectAuthorization.allowed(
            operation,
            io.aeyer.plowshare.server.access.AccessRequestDecoder.decode(operation, payload),
            handleOf(session).orElse(null));
  }

  // --- delivery ------------------------------------------------------------

  /**
   * Put one event in front of whoever is watching {@code session}, or drop it.
   *
   * <p><b>Every "no" here is ordinary and none of them is an error.</b> A null session is a job
   * nobody's client asked for; an unknown id is a session nothing ever attached to; a session with
   * only a file provider is the command-line client before it opened its second socket, and a
   * browser is the other way round. All three mean nobody is listening, and the run is unaffected
   * either way — {@link SessionRegistry#find} is what keeps the middle one a lookup that answers
   * rather than one that creates.
   *
   * <p>Returns as soon as the event is queued. See the class javadoc for why that is the
   * requirement and not a nicety.
   */
  @Override
  public void publish(String session, JobEvent event) {
    Objects.requireNonNull(event, "event");
    if (session == null || !projectDelivery(session, "job.status", Map.of("job", event.job()))) {
      return;
    }
    sessions
        .find(session)
        .flatMap(live -> live.attached(Role.LISTENER, WebSocketSession.class))
        .map(socket -> socket.getAttributes().get(DELIVERY))
        .map(Delivery.class::cast)
        .ifPresent(delivery -> delivery.offer(event));
  }

  /**
   * Put one delta in front of whoever is watching, or drop it.
   *
   * <p>The same lookup as {@link #publish} and every one of its "no" answers, plus one more that
   * matters here: <b>a delta reaching a full token queue is dropped and nothing is said about
   * it.</b> That is the contract rather than a shortcoming — the answer is {@code Outcome.text}
   * from the final model call and arrives whole however many of these went missing.
   *
   * <p>Called on the thread reading the model's response, so it does what {@link #publish} does and
   * no more: one lookup, one offer, return.
   */
  @Override
  public void stream(String session, JobDelta delta) {
    Objects.requireNonNull(delta, "delta");
    if (session == null
        || !watchers.watching(session)
        || !projectDelivery(session, "job.status", Map.of("job", delta.job()))) {
      // NOBODY ASKED, SO NOBODY PAYS. The gate is here rather than only at
      // the caller because this is the last place that can enforce it:
      // "a client that did not ask receives nothing" has to be true of
      // every path into this method, including ones written later.
      return;
    }
    sessions
        .find(session)
        .flatMap(live -> live.attached(Role.LISTENER, WebSocketSession.class))
        .map(socket -> socket.getAttributes().get(DELIVERY))
        .map(Delivery.class::cast)
        .ifPresent(delivery -> delivery.stream(delta));
  }

  /**
   * Whether this session asked for deltas.
   *
   * <p>For a caller that would otherwise <b>build</b> them: {@link #stream} drops what nobody asked
   * for, but constructing a {@link JobDelta} per chunk to have it dropped is work on the thread
   * reading the model's response, which is the one thread this whole design is about not slowing.
   */
  @Override
  public boolean streaming(String session) {
    return watchers.watching(session);
  }

  /**
   * Put one bare body in front of every socket signed in as {@code handle}.
   *
   * <p>Every "no" here is ordinary, on the same terms {@link #publish} gives for a job event: a
   * null handle or body is a caller with nothing to say, and an account with no live socket is one
   * nobody happens to be watching from right now. Delivered through {@link #byHandle} rather than a
   * scan of {@link #sessions}, because a push does not name a session — it names an account, which
   * may have more than one socket open across more than one session.
   */
  @Override
  public void push(String handle, io.aeyer.plowshare.protocol.AccountEvent body) {
    if (handle == null || body == null) {
      return;
    }
    for (WebSocketSession socket : byHandle.getOrDefault(handle, Set.of())) {
      Object delivery = socket.getAttributes().get(DELIVERY);
      if (delivery instanceof Delivery live) {
        live.offer(body);
      }
    }
  }

  /** Only authenticated account sockets receive this fixed, content-free refresh hint. */
  @Override
  public void invalidate() {
    informationHint(false);
  }

  @Override
  public void progress() {
    informationHint(true);
  }

  private void informationHint(boolean progressOnly) {
    for (var sockets : byHandle.values()) {
      for (var socket : sockets) {
        if (socket.getAttributes().get(DELIVERY) instanceof Delivery live)
          live.informationInvalidated(progressOnly);
      }
    }
  }

  /**
   * Which account {@code session}'s listener socket is signed in as, or empty when the session has
   * no listener or that listener carries no handle.
   */
  @Override
  public Optional<String> handleOf(String session) {
    return sessions
        .find(session)
        .flatMap(live -> live.attached(Role.LISTENER, WebSocketSession.class))
        .map(socket -> socket.getAttributes().get(HANDLE))
        .filter(String.class::isInstance)
        .map(String.class::cast);
  }

  /**
   * Put one bare body in front of {@code session}'s listener, or drop it — {@link SessionPushes}.
   * The same lookup and the same queue {@link #tell}'s sibling {@link #push} uses for an account
   * push, addressed by session instead: a {@code conversation.appended} push names the session
   * following a conversation, which {@link Watchers#followersOf} answers without knowing which
   * account, if any, that session is signed in as.
   */
  @Override
  public void tell(String session, io.aeyer.plowshare.protocol.ConversationGrowth body) {
    if (session == null || body == null) {
      return;
    }
    if (!projectDelivery(
        session, "conversation.turns", Map.of("conversation", body.conversation()))) return;
    sessions
        .find(session)
        .flatMap(live -> live.attached(Role.LISTENER, WebSocketSession.class))
        .map(socket -> socket.getAttributes().get(DELIVERY))
        .filter(Delivery.class::isInstance)
        .map(Delivery.class::cast)
        .ifPresent(delivery -> delivery.offer(body));
  }

  private static void stopDelivery(WebSocketSession socket) {
    Object delivery = socket.getAttributes().get(DELIVERY);
    if (delivery instanceof Delivery live) {
      live.stop();
    }
  }

  /**
   * One frame waiting for this connection's drain thread.
   *
   * <p><b>The queue holds this rather than a {@link JobEvent} because it now carries two kinds of
   * frame</b>. Droppable pushes are serialized by the writer. Terminal replies are encoded and
   * byte-reserved before queueing, so arbitrary result sizes cannot bypass output admission.
   */
  private sealed interface Pending permits Push, Pushed, Reply, Streamed {}

  /**
   * A server-initiated event, which goes out bare and is droppable — see the class javadoc's two
   * sections on each.
   */
  private record Push(JobEvent event) implements Pending {}

  /**
   * An account push — a bare, arbitrary body — droppable on the same terms as a {@link Push}. See
   * {@link #push(String, Object)}.
   */
  private record Pushed(Object body) implements Pending {}

  /**
   * An answer to a client's frame, which goes out in an {@link Envelope} and is not droppable.
   *
   * @param id the request's own {@code id}, echoed back so the caller can match this answer to the
   *     question it asked. {@code null} when the frame carried none — see {@link #correlationOf}
   * @param type the request's own {@code type}, or {@link FrameTypes#REFUSED} when the frame
   *     carried none this server could read
   * @param frame the once-encoded, byte-reserved terminal response
   */
  private record Reply(
      String id,
      String type,
      String frame,
      Runnable delivered,
      Runnable release,
      Runnable disclosure,
      Runnable started,
      Runnable settled,
      long queuedAt)
      implements Pending {}

  /**
   * A piece of what a model is producing. Goes out bare and drops freely.
   *
   * <p>Its own record rather than a {@link Push} carrying a {@link JobDelta}, because the two are
   * queued separately and the type is what keeps the drain honest about which queue it took
   * something from.
   */
  private record Streamed(JobDelta delta) implements Pending {}

  /**
   * One listener's queue and the thread that empties it.
   *
   * <p>Not a {@code Live} in the registry's role, which is the shape the file channel takes for the
   * same problem, and the difference is what each role's callers need. Nothing outside this class
   * asks about a listener's queue — the question anything above asks is whether a session has a
   * listener at all — while {@code FileChannelHandler.ask} needs the outstanding map every time it
   * is called. So this hangs off the connection and the role keeps the socket, which is also what
   * lets {@code EventChannelTest}'s assertions about what is attached go on saying what they say.
   */
  private static final class Delivery {

    private final WebSocketSession socket;
    private final String session;
    private final ObjectMapper json;
    private final EventTransport transport;
    private final DispatchTelemetry telemetry;
    private final io.aeyer.plowshare.protocol.transport.PacketBudget budget;
    private final java.util.concurrent.atomic.AtomicLong replyBytes =
        new java.util.concurrent.atomic.AtomicLong();
    private final BlockingQueue<Pending> pending = new ArrayBlockingQueue<>(PENDING);

    /**
     * Token deltas, on a queue of their own that cannot touch the one above.
     *
     * <h3>Two queues, and this is the whole reason the design exists</h3>
     *
     * <p>The queue above drops a push when it is full, which is correct for an event a client can
     * recover by asking {@code GET /v1/jobs/&#123;id&#125;}. <b>Put hundreds of deltas per model
     * call through it and a slow client loses an {@code ENDED}</b> — and an ending that never
     * arrives is a turn that never finishes, which cost eighteen minutes of diagnosis on 2026-09-12
     * from a different cause and is not a failure worth reintroducing on purpose.
     *
     * <p>So: <b>a token can never evict an ending.</b> Separate bounds, separate drops, and nothing
     * a firehose does can make the lifecycle queue any fuller than it would have been.
     *
     * <p><b>Do not "simplify" these back into one.</b> They look like duplication and they are not;
     * the duplication is the property.
     *
     * <h3>Smaller than the lifecycle queue, deliberately</h3>
     *
     * <p>A backlog of tokens is worth nothing: they are a typing effect, and a client that is
     * behind wants the CURRENT text rather than a minute of history delivered late. The bound is
     * small enough that falling behind means losing tokens quickly rather than accumulating them.
     */
    private final BlockingQueue<Pending> tokens = new ArrayBlockingQueue<>(STREAMING);

    /**
     * One coalesced refresh hint cannot consume lifecycle/reply or subscription capacity. A full
     * content/authority invalidation upgrades a pending progress hint atomically; progress never
     * downgrades it or hides a revocation. Exactly one permit belongs to a nonempty slot.
     */
    private final java.util.concurrent.atomic.AtomicReference<
            io.aeyer.plowshare.protocol.AccountEvent.InformationInvalidated>
        informationHint = new java.util.concurrent.atomic.AtomicReference<>();

    void informationInvalidated(boolean progressOnly) {
      if (!running) return;
      var next = new io.aeyer.plowshare.protocol.AccountEvent.InformationInvalidated(progressOnly);
      var previous =
          informationHint.getAndUpdate(
              current ->
                  current == null || current.progressOnly() && !progressOnly ? next : current);
      if (previous == null) waiting.release();
    }

    private Pending nextInformationHint() {
      var hint = informationHint.getAndSet(null);
      return hint == null ? null : new Pushed(hint);
    }

    /**
     * How many items are waiting across both queues.
     *
     * <h3>Why a semaphore rather than polling either queue</h3>
     *
     * <p><b>One socket has exactly one writer thread</b>, because {@code
     * WebSocketSession.sendMessage} is not safe to call concurrently — two drain threads would
     * interleave two frames' bytes on the wire. So one thread has to wait on <em>either</em> queue
     * having something, and {@code BlockingQueue.take} can only wait on one.
     *
     * <p>Polling both with a timeout was the obvious alternative and is worse: it wakes every
     * connection on a timer forever, to find nothing, for as long as anybody is connected. This
     * parks until there is genuinely something to write and then takes it — lifecycle first.
     */
    private final Semaphore waiting = new Semaphore(0);

    private final AtomicBoolean overrun = new AtomicBoolean();
    private volatile Thread drain;
    private EventRequests requests;
    private volatile ConcurrentRequests.Connection concurrent;
    private volatile boolean domainSeen;
    private volatile Runnable validity = () -> {};
    private volatile boolean running = true;
    private Runnable whenStopped = () -> {};
    private final Map<String, Object> snapshots = new LinkedHashMap<>();

    /** At most one latest replacement per subscription; replies keep their separate queue. */
    synchronized void snapshot(String subscription, Envelope body) {
      if (!running) return;
      if (body == null) {
        snapshots.remove(subscription);
        return;
      }
      if (!snapshots.containsKey(subscription) && snapshots.size() >= 8) return;
      boolean fresh = !snapshots.containsKey(subscription);
      snapshots.put(subscription, body);
      if (fresh) waiting.release();
    }

    synchronized Pending nextSnapshot() {
      if (snapshots.isEmpty()) return null;
      String id = snapshots.keySet().iterator().next();
      return new Pushed(snapshots.remove(id));
    }

    Delivery(
        WebSocketSession socket,
        String session,
        ObjectMapper json,
        io.aeyer.plowshare.protocol.transport.PacketBudget budget,
        DispatchTelemetry telemetry) {
      this.socket = socket;
      this.session = session;
      this.json = json;
      this.telemetry = telemetry;
      this.budget = budget;
      if (io.aeyer.plowshare.protocol.transport.SegmentedMessages.SUBPROTOCOL.equals(
          socket.getAcceptedProtocol())) {
        socket.setTextMessageSizeLimit(
            io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_PACKET_BYTES);
        transport = new PacketEventTransport(socket, json, budget);
      } else {
        transport =
            new EventTransport() {
              public java.util.Optional<String> receive(String frame) throws IOException {
                // Container text limits count characters; legacy policy counts encoded UTF-8 bytes.
                if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                  throw new IOException("legacy message exceeds wire allowance");
                return java.util.Optional.of(frame);
              }

              public void send(String frame) throws IOException {
                if (frame.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                  throw new IOException("legacy message exceeds wire allowance");
                socket.sendMessage(new TextMessage(frame));
              }

              public void close() {}
            };
      }
    }

    /**
     * Separate from the constructor so the thread cannot see a half-built object: {@code
     * this::drain} started in a constructor is published before the final fields it reads are.
     */
    void start() {
      this.drain = Thread.ofVirtual().name("job-events-" + socket.getId()).start(this::drain);
      if (requests != null) requests.start(socket.getId());
    }

    /**
     * Idempotent: a displaced socket is stopped by the connection that displaced it and then again
     * by its own close callback.
     */
    void stop() {
      running = false;
      if (requests != null) requests.close();
      if (concurrent != null) concurrent.close();
      Pending queued;
      while ((queued = pending.poll()) != null)
        if (queued instanceof Reply reply) reply.release().run();
      transport.close();
      whenStopped.run();
      informationHint.set(null);
      synchronized (this) {
        snapshots.clear();
      }
      Thread thread = drain;
      if (thread != null) {
        // The interrupt is the whole of it — the thread is parked in
        // take() and the flag alone would never be read again.
        thread.interrupt();
      }
    }

    /**
     * Never blocks. A full queue means this listener is further behind than the stream promises to
     * carry, and the event is dropped.
     */
    void offer(JobEvent event) {
      if (pending.offer(new Push(event))) {
        waiting.release();
        return;
      }
      log.debug(
          "Session '{}' is behind by {} events; dropping a '{}' for job {}.",
          session,
          PENDING,
          event.kind(),
          event.job());
    }

    /**
     * Never blocks, and drops on the same terms as {@link #offer(JobEvent)} — an account push is
     * exactly as droppable as a job event: {@link AccountPushes} promises delivery to a socket that
     * is reading, and nothing more.
     */
    void offer(Object body) {
      if (pending.offer(new Pushed(body))) {
        waiting.release();
        return;
      }
      log.debug("Session '{}' is behind by {} events; dropping a push.", session, PENDING);
    }

    /**
     * Never blocks and <b>drops freely</b>, which is the contract.
     *
     * <p>A lost token is cosmetic: the answer is {@code Outcome.text} from the final model call and
     * arrives whole however many deltas went missing on the way. There is deliberately no log line
     * for a drop — these are produced on the thread reading the model's response, and a line per
     * dropped token would be the firehose again, pointed at a log file instead of a socket.
     */
    void stream(JobDelta delta) {
      if (tokens.offer(new Streamed(delta))) {
        waiting.release();
      }
    }

    /**
     * Never blocks either, and <b>does not drop</b> — see the class javadoc's "a response is not
     * droppable". A queue that will not take this answer means this client is not reading, and a
     * client that is not reading is disconnected rather than left holding an {@code id} nothing
     * will ever resolve.
     */
    void answer(String id, String type, Outcome outcome) {
      answer(id, type, outcome, () -> {});
    }

    void answer(String id, String type, Outcome outcome, Runnable delivered) {
      answer(id, type, outcome, delivered, validity);
    }

    void answer(String id, String type, Outcome outcome, Runnable delivered, Runnable disclosure) {
      answer(id, type, outcome, delivered, disclosure, () -> {}, () -> {});
    }

    void answer(
        String id,
        String type,
        Outcome outcome,
        Runnable delivered,
        Runnable disclosure,
        Runnable started,
        Runnable settled) {
      enqueue(id, type, outcome, delivered, disclosure, started, settled, true);
    }

    /**
     * Queues a capacity refusal, and <b>never disconnects</b> when it cannot.
     *
     * <p>A refusal is emitted precisely when its connection is congested, so the three ways a reply
     * can fail to queue (the shared {@code PacketBudget}, the per-connection reply byte bound,
     * {@link #PENDING}) are most likely exactly when a refusal is needed. Tearing the connection
     * down for them would strand every in-flight request to deliver a "retry later". An
     * undeliverable refusal is instead dropped: that one request goes unanswered and its client's
     * deadline expires, which is strictly better than losing every other request on the connection.
     * There is deliberately no reserved budget for refusals; exhausting the whole budget is
     * systemic, and shedding is the defensible response.
     *
     * @return whether the refusal was queued
     */
    boolean refuse(String id, String type, Outcome outcome) {
      return enqueue(id, type, outcome, () -> {}, validity, () -> {}, () -> {}, false);
    }

    /**
     * @param fatal whether failing to queue closes the connection (an answer is owed and a silent
     *     drop would leave its caller waiting forever) or only drops this one reply
     */
    private boolean enqueue(
        String id,
        String type,
        Outcome outcome,
        Runnable delivered,
        Runnable disclosure,
        Runnable started,
        Runnable settled,
        boolean fatal) {
      if (!running) {
        delivered.run();
        return false;
      }
      long encodeStarted = System.nanoTime();
      String frame = answered(id, type, outcome);
      telemetry.encoding.record(System.nanoTime() - encodeStarted);
      if (frame == null) {
        return unqueued(id, fatal);
      }
      // Charge both encoded characters and the retained typed projection used by its disclosure
      // fence; references are dropped together on successful handoff or connection teardown.
      long bytes = 4L * frame.length();
      long maximum = 4L * io.aeyer.plowshare.protocol.transport.SegmentedMessages.MAX_MESSAGE_BYTES;
      long held = replyBytes.addAndGet(bytes);
      if (held > maximum) {
        replyBytes.addAndGet(-bytes);
        return unqueued(id, fatal);
      }
      try {
        budget.acquire(bytes);
      } catch (IllegalArgumentException capacity) {
        replyBytes.addAndGet(-bytes);
        return unqueued(id, fatal);
      }
      var released = new AtomicBoolean();
      telemetry.replyBytes.add(bytes);
      Runnable release =
          () -> {
            if (released.compareAndSet(false, true)) {
              replyBytes.addAndGet(-bytes);
              telemetry.replyBytes.add(-bytes);
              budget.release(bytes);
            }
          };
      var reply =
          new Reply(
              id, type, frame, delivered, release, disclosure, started, settled, System.nanoTime());
      if (pending.offer(reply)) {
        if (!running && pending.remove(reply)) release.run();
        waiting.release();
        return true;
      }
      release.run();
      return unqueued(id, fatal);
    }

    private boolean unqueued(String id, boolean fatal) {
      if (fatal) {
        disconnect(id);
      } else {
        log.debug(
            "Session '{}' could not queue a refusal to '{}'; dropped, connection kept.",
            session,
            id);
      }
      return false;
    }

    /**
     * Closes a connection that has fallen too far behind to be answered, on a thread of its own.
     *
     * <p>Once, however many answers pile up behind the first that could not be queued: a socket
     * closed twice is harmless but a log line per overrun frame is a burst this server would be
     * writing about itself.
     */
    private void disconnect(String id) {
      if (!overrun.compareAndSet(false, true)) {
        return;
      }
      log.warn(
          "Session '{}' is {} frames behind and its answer to '{}' cannot be queued;"
              + " closing it, since a dropped answer is an id its caller would"
              + " wait on forever.",
          session,
          PENDING,
          id);
      // A CLOSE IS A SOCKET WRITE. Doing it here would put the caller that
      // could not queue — the container's inbound thread — on exactly the
      // blocking write this class exists to keep every server-side thread
      // off, and against a peer that is already not reading.
      Thread.ofVirtual()
          .name("job-events-overrun-" + socket.getId())
          .start(
              () -> {
                try {
                  socket.close(
                      CloseStatus.SESSION_NOT_RELIABLE.withReason(
                          "too far behind to be answered; reconnect and ask again"));
                } catch (IOException | IllegalStateException already) {
                  // The two shapes FileChannelHandler records for a socket
                  // that is already going away. Either is this branch's goal
                  // reached by another route.
                  log.debug(
                      "The overrun listener for session '{}' would not close: {}",
                      session,
                      already.toString());
                }
                stop();
              });
    }

    private void drain() {
      // Writer-thread confined: two lower-priority lanes alternate when both stay busy. Neither
      // continuous invalidations nor a long token stream can indefinitely starve the other.
      boolean hintNext = true;
      while (running) {
        try {
          waiting.acquire();
        } catch (InterruptedException stopping) {
          return;
        }
        // LIFECYCLE FIRST, ALWAYS. The permit says something is waiting
        // somewhere; this decides which, and an ending queued behind a
        // thousand tokens still goes out next. The `poll` cannot both
        // return null when a permit was taken, because every release
        // follows a successful offer.
        Pending next = pending.poll();
        if (next == null) {
          next = nextSnapshot();
        }
        if (next == null) {
          boolean servedHint = hintNext;
          next = servedHint ? nextInformationHint() : tokens.poll();
          if (next == null) {
            servedHint = !servedHint;
            next = servedHint ? nextInformationHint() : tokens.poll();
          }
          if (next != null) hintNext = !servedHint;
        }
        if (next == null) {
          // Unreachable on the argument above, and written rather
          // than asserted: a drain thread that threw here would take
          // a listener down over a bookkeeping slip, and a listener
          // that skips one turn of a loop loses nothing.
          continue;
        }
        String frame = frameFor(next);
        if (frame == null) {
          continue;
        }
        try {
          if (!running || !socket.isOpen()) return;
          if (next instanceof Reply reply) reply.disclosure().run();
          if (next instanceof Reply reply) reply.started().run();
          long writeStarted = System.nanoTime();
          if (next instanceof Reply reply)
            telemetry.replyQueue.record(writeStarted - reply.queuedAt());
          try {
            transport.send(frame);
          } finally {
            telemetry.write.record(System.nanoTime() - writeStarted);
          }
          if (next instanceof Reply reply) {
            // A peer can refill its 64-call window before send() returns. Keep the single
            // handoff slot and all body/ID reservations until the handler's queueing stage has
            // also settled, then release bytes before the dispatch drain can complete.
            reply.settled().run();
            reply.release().run();
            reply.delivered().run();
          }
        } catch (IOException | RuntimeException unusable) {
          // The two shapes FileChannelHandler measured for a socket
          // that cannot be written to: an IOException is the write
          // failing, and an IllegalStateException is a send after
          // close. Both end this thread rather than being retried —
          // there is nothing to retry onto, the close callback is
          // already on its way, and unlike the file channel there is
          // nobody waiting on an answer to be told.
          log.debug(
              "The event channel for session '{}' could not be written to ({});"
                  + " it will send nothing more.",
              session,
              unusable.getClass().getSimpleName());
          stop();
          try {
            socket.close(CloseStatus.SERVER_ERROR.withReason("event transport failed"));
          } catch (IOException closing) {
            log.debug("Failed event socket could not close");
          }
          return;
        } finally {
          if (next instanceof Reply reply) reply.release().run();
        }
      }
    }

    /**
     * The JSON for one queued frame, or {@code null} when there is nothing this connection can
     * usefully be sent.
     */
    private String frameFor(Pending next) {
      return switch (next) {
        case Push push -> pushed(push.event());
        case Pushed account -> bare(account.body());
        case Streamed streamed -> streamed(streamed.delta());
        case Reply reply -> reply.frame();
      };
    }

    /**
     * A bare body, with no envelope — an account push. See the class javadoc's "two frame shapes"
     * and {@link #push(String, Object)}.
     *
     * <p>Dropped in silence, on the same terms as {@link #pushed} and {@link #streamed}: nobody but
     * this server built the body, so a caller of {@link #push(String, Object)} that hands it
     * something unwritable is this server's own bug and not this listener's — but it must cost this
     * one push and nothing more. {@link #frameFor} runs on this connection's drain thread outside
     * the {@code sendMessage} try in {@link #drain}, so anything this method let escape would end
     * that thread and, with it, every job event, delta, and reply still to come on this socket —
     * not merely the one push that failed.
     */
    private String bare(Object body) {
      try {
        return json.writeValueAsString(body);
      } catch (JsonProcessingException notSerialisable) {
        log.warn(
            "A push of type '{}' for session '{}' could not be serialised ({});" + " dropped.",
            body.getClass().getSimpleName(),
            session,
            notSerialisable.getClass().getSimpleName());
        return null;
      }
    }

    /**
     * A bare delta, with no envelope and no {@code kind}.
     *
     * <p><b>The absent {@code kind} is what keeps the console out of this.</b> Its handler is an
     * if/else-if over that field with no else, so a frame that has none falls out of the chain
     * without being mentioned — which is how "do not edit the console" is achieved rather than
     * merely intended.
     *
     * <p>A delta that will not serialise is dropped in silence, on the same terms as one dropped
     * for a full queue: nobody is waiting on it and the answer arrives whole regardless. No log
     * line, because these arrive in their hundreds and a line each would be the firehose pointed at
     * a log.
     */
    private String streamed(JobDelta delta) {
      try {
        return json.writeValueAsString(delta);
      } catch (JsonProcessingException notSerialisable) {
        return null;
      }
    }

    /** A bare event, with no envelope — see the class javadoc's "two frame shapes". */
    private String pushed(JobEvent event) {
      try {
        return json.writeValueAsString(event);
      } catch (JsonProcessingException notSerialisable) {
        // An event this server built and cannot write down is this
        // server's bug. It is not the listener's fault and does not
        // cost it the connection, so the next event still goes.
        log.warn(
            "A '{}' event for job {} could not be serialised ({}); dropped.",
            event.kind(),
            event.job(),
            notSerialisable.getClass().getSimpleName());
        return null;
      }
    }

    /**
     * An answer, in an envelope carrying the request's own {@code id}.
     *
     * <p><b>An answer that will not serialise is replaced rather than dropped</b>, which is where
     * {@link #pushed} and this method part company: the frame that could not be written is a
     * handler's payload, and the caller waiting on this {@code id} would otherwise wait on it
     * forever for a reason that is entirely this server's. The replacement carries no payload, so
     * the only thing that could have failed is gone from it.
     */
    private String answered(String id, String type, Outcome outcome) {
      try {
        return json.writeValueAsString(envelope(id, type, outcome));
      } catch (JsonProcessingException | IllegalArgumentException notSerialisable) {
        log.warn(
            "The answer to frame '{}' on session '{}' could not be written down"
                + " ({}); its caller is told so rather than left waiting.",
            id,
            session,
            notSerialisable.getClass().getSimpleName());
      }
      try {
        return json.writeValueAsString(
            envelope(
                id,
                type,
                Outcome.failed(
                    Code.INTERNAL_ERROR,
                    "this server produced an answer it could not write down")));
      } catch (JsonProcessingException | IllegalArgumentException hopeless) {
        // Three strings and an enum, so nothing reaches here without
        // the mapper itself being broken; logged rather than thrown,
        // because throwing would end this listener's whole stream over
        // one frame.
        log.error("Session '{}' could not even be told that its answer failed.", session, hopeless);
        return null;
      }
    }

    /**
     * The response frame: spec §3.1's envelope, carrying spec §3.3's {@code { code, said?, payload?
     * }} as its payload.
     *
     * <p><b>{@link Outcome} has no {@code id} field and this is why it does not need one.</b> The
     * envelope is already the layer that correlates — §3.1 says so of {@code id} in as many words —
     * and an {@code Outcome} is what a {@link FrameHandler} produces, which is the one party in
     * this path that has never seen the request's {@code id} and could not fill the field in.
     * Putting the id there would be asking every handler to carry a value only this method knows.
     *
     * <p>The {@link Outcome} is converted rather than nested directly because {@link
     * Envelope#payload()} is a {@code Map<String, Object>} — {@code plowshare-protocol} keeps
     * Jackson databind off its main classpath, so the record could not name a tree type. The
     * conversion honours {@code Outcome}'s {@code @JsonInclude(NON_NULL)}, which is what keeps a
     * {@code said} the server did not set <em>absent</em> from the wire rather than present and
     * null — the distinction that class's javadoc says a client's own sentences depend on.
     */
    private Envelope envelope(String id, String type, Outcome outcome) {
      return new Envelope(id, type, Envelope.CURRENT_VERSION, json.convertValue(outcome, AS_MAP));
    }
  }
}
