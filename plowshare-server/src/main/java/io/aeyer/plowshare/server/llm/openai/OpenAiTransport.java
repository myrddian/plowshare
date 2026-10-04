package io.aeyer.plowshare.server.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.WebSocketOpener;
import io.aeyer.plowshare.server.llm.accounting.CallLifecycle;
import io.aeyer.plowshare.server.llm.accounting.UsageNormalizer;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Content;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolChoice;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LlmTransport} over one OpenAI-compatible host.
 *
 * <p>This is {@code OpenAiEmbeddingClient} moved behind the dispatcher, and deliberately the same
 * HTTP shape as Anchor's {@code LMStudioClient}: one client per read timeout, a Bearer header only
 * when an api-key is configured, retry on connection-level failures with the connection pool
 * evicted between attempts, and no retry at all on a 4xx/5xx — the model is not going to start
 * liking a request it already refused. The two projects run against one LM Studio, so a second HTTP
 * shape here would be a second set of timeout and retry behaviours to reason about for the same
 * endpoint.
 *
 * <p>What has actually changed in the move is the ownership: this is built per pool rather than per
 * application, so every message it produces leads with the pool name. A pool is the only handle an
 * operator has on which of several hosts misbehaved.
 *
 * <p>The pool name is what <em>leads</em>, not the whole of what may appear, and the distinction is
 * worth stating exactly because an earlier draft of this comment overstated it. A base URL is
 * diagnostic and does appear: {@link #url(String)} prints it, since an operator cannot fix an
 * unparseable setting without seeing what it was set to, and a connect failure carries OkHttp's own
 * "Failed to connect to host/address:port". <b>A key is the thing that never appears</b> — not its
 * value, not a prefix, not its length, and not a copy of it quoted back at us by the endpoint.
 * {@link #detail(int, String, String)} covers the last of those on the refusal path, and {@link
 * #streamDetail} and {@link #executeWithRetry} cover it on the two others: a server controls text
 * inside a client library's exceptions as well as inside a response body, so "the refusal body is
 * the only text the endpoint controls" — which this said — was wrong in two places, both now
 * guarded by the same helper.
 *
 * <p>No queueing and no state beyond the clients. {@link
 * io.aeyer.plowshare.server.llm.dispatch.LlmPool} counts what is running and queued and the
 * dispatcher routes on those figures; a transport that held work back internally would make both of
 * them a lie.
 */
public final class OpenAiTransport implements LlmTransport, WebSocketOpener {

  private static final Logger log = LoggerFactory.getLogger(OpenAiTransport.class);
  private static final MediaType JSON = MediaType.get("application/json");

  /**
   * Connect and write budgets, shared by all three clients.
   *
   * <p>Not derived from any of the read timeouts: getting a socket to a box on the LAN and pushing
   * a request body up it are the same short operation whether the answer takes 30 seconds or 90.
   * Only the wait for the answer differs, and that is what the three read timeouts are for.
   */
  private static final Duration CONNECT_AND_WRITE = Duration.ofSeconds(10);

  /** How much of a refusal body may reach a message. See {@link #refusalBody(Response)}. */
  private static final long MAX_REFUSAL_BODY_BYTES = 4096L;

  /**
   * {@code okhttp3.Dispatcher}'s own default, restated because {@link #streamCeiling(int)} may only
   * ever raise it.
   */
  private static final int OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST = 5;

  /**
   * The endpoint said {@code data: [DONE]}. Sentinels rather than nulls, because {@link
   * BlockingQueue} does not carry nulls.
   */
  private static final Object DONE_MARKER = new Object();

  /**
   * The connection closed without a {@code [DONE]}. Distinct from {@link #DONE_MARKER} because the
   * two mean different things about whether the answer is complete — see {@link #stream}.
   */
  private static final Object CLOSED_MARKER = new Object();

  /**
   * The caller said it no longer wanted the stream. A third terminal and not a {@link Failure},
   * because nothing failed: it becomes {@link CallerAbandonedException} and never an {@link
   * LlmTransportException}.
   */
  private static final Object ABANDONED_MARKER = new Object();

  /**
   * How much generated text one stream may buffer, in {@code char}s — about 7.6 MiB as UTF-16, two
   * bytes to the {@code char}. (This said "roughly 4 MiB", which counted a byte per character and
   * was out by two.)
   *
   * <p>The same reasoning as {@link #MAX_REFUSAL_BODY_BYTES}, applied to text that can be far
   * longer. "The reader only ever buffers one completion's worth" was the justification for an
   * unbounded queue, and it is an assumption about the endpoint rather than something this class
   * enforces: {@link #chatBody} sets no {@code max_tokens}, so "one completion" is whatever the
   * server decides to send, and a model in a repetition loop decides on a great deal. Four million
   * characters is several times the largest context window in service here spent entirely on
   * output, so reaching it means something is wrong rather than long.
   *
   * <p>Enforced in the producer and never by bounding the queue — see {@link #stream} for why a
   * bounded queue is the worse failure.
   */
  private static final int MAX_STREAM_CHARS = 4_000_000;

  private final io.aeyer.plowshare.server.llm.counting.VllmPromptCounter promptCounter;
  private final PoolProperties props;
  private final ObjectMapper mapper;
  private final OkHttpClient chatHttp;
  private final OkHttpClient streamingHttp;
  private final OkHttpClient embeddingHttp;

  /**
   * The websocket client, and the reason it is a fourth rather than a reuse.
   *
   * <p><b>{@code streamingHttp} carries a call timeout and a websocket must not.</b> That client
   * bounds a whole call at {@code streaming-timeout}, which is the right bound for an SSE response
   * and fatal for a socket meant to stay open: measured 2026-09-02 against the reference node, a
   * novel 30 415-token prompt took 119 seconds before its first token, so a socket bounded at the
   * 90-second default would be cut off mid-prefill every time the feature it exists for was
   * actually needed.
   *
   * <p>Read timeout is off for the same reason — a prefill is legitimately silent between progress
   * frames — and the bound that replaces both lives in the caller, which enforces {@code
   * max-stream-duration} against a deadline of its own. Pings keep a connection that a proxy would
   * otherwise reap from looking idle while the model reads.
   */
  private final OkHttpClient socketHttp;

  /**
   * Three clients because a read timeout is immutable on an OkHttp client and the three calls do
   * not deserve the same wait — a batch of a hundred summaries and a single question are the same
   * endpoint but not the same patience, and a stream's timeout is per chunk rather than per call.
   *
   * <p>Derived with {@code newBuilder()} rather than built separately, so the three share one
   * connection pool and one dispatcher. Three independent builders would open three idle connection
   * pools and three keep-alive threads to a single box, and this class is constructed once per pool
   * rather than once per process, so that multiplies by however many hosts are declared.
   */
  public OpenAiTransport(PoolProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
    this.promptCounter = new io.aeyer.plowshare.server.llm.counting.VllmPromptCounter(mapper);
    // The one dispatcher the three clients share, configured before any of
    // them is built. See streamCeiling for why the default is wrong here.
    Dispatcher dispatcher = new Dispatcher();
    int streamCeiling = streamCeiling(props.getChat());
    dispatcher.setMaxRequestsPerHost(streamCeiling);
    dispatcher.setMaxRequests(Math.max(dispatcher.getMaxRequests(), streamCeiling));
    OkHttpClient base =
        new OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectTimeout(CONNECT_AND_WRITE)
            .writeTimeout(CONNECT_AND_WRITE)
            // True, and a correction to the class this replaces.
            //
            // OpenAiEmbeddingClient set this false, reasoning that retry is
            // handled below with backoff and an eviction, which OkHttp's own
            // retry does neither of. That reasoning is right about retry and
            // wrong about what this flag controls: it also governs whether a
            // call may try the *next address* a hostname resolved to. With
            // it false, one connect failure is fatal to that call — and
            // worse, OkHttp postpones the failed route in a RouteDatabase
            // that lives as long as the client, so the next call starts on
            // the other address and there is nothing to fall back to.
            //
            // On `localhost` that is a permanent wedge from one blip.
            // `localhost` resolves to both 127.0.0.1 and ::1 here, LM Studio
            // binds only 127.0.0.1, and http://localhost:1234/v1 is the very
            // base-url url(...) below tells operators to write. So a single
            // dropped socket flips the transport to ::1 for good: every
            // later embedding fails with ConnectException, no configuration
            // changed, and only a restart clears it. See
            // a_transient_disconnect_does_not_wedge_the_transport, which
            // fails with this set false.
            //
            // The cost is that the two retry layers compose rather than
            // replace, and an earlier draft of this comment got the
            // arithmetic wrong in the one parameter that matters. It said
            // "2 routes", reasoning from dual-stack localhost. The
            // multiplier is not two: OkHttp will try *every* route the
            // address resolved to, so it is however many A and AAAA records
            // DNS returns. A gateway hostname behind 8 of each is 16 routes,
            // 16 x 10s of connect timeout for one attempt, times
            // retry-max-attempts — nearly six minutes on a lane slot, for a
            // host that was never going to answer. That is the number
            // callCeiling(...) exists to make irrelevant.
            .retryOnConnectionFailure(true)
            .build();
    // Three clients over one base, so they share its connection pool and
    // dispatcher, differing only in how long they wait for an answer.
    //
    // The shared ConnectionPool's default of 5 is the other 5 in this
    // picture and is not admission control, so it needs no adjusting
    // alongside streamCeiling: it caps how many *idle* connections are kept
    // warm, and a call that finds none simply opens one. Concurrency is
    // bounded by the dispatcher above and by the pool's lanes, never here.
    this.chatHttp =
        base.newBuilder()
            .readTimeout(props.getChatTimeout())
            .callTimeout(callCeiling(props.getChatTimeout()))
            .build();
    this.embeddingHttp =
        base.newBuilder()
            .readTimeout(props.getEmbeddingTimeout())
            .callTimeout(callCeiling(props.getEmbeddingTimeout()))
            .build();
    // A call timeout equal to the read timeout. It bounds getting the
    // response open and nothing after that, which is deliberate on okhttp's
    // side rather than a quirk: RealEventSource.processResponse calls
    // RealCall.timeoutEarlyExit() as soon as the content type checks out,
    // commented "This is a long-lived response. Cancel full-call timeouts."
    // So okhttp's javadoc for callTimeout ("includes ... reading the response
    // body") is true of an ordinary call and deliberately untrue of an event
    // source. Naming the method matters because it is what shows up in a diff
    // if that ever changes, and this branch has been burned three times
    // asserting library semantics from memory. Measured as well as read, and
    // re-measured on 2026-09-02 with the read and call timeouts set to
    // DIFFERENT values so that each outcome could be attributed to one of
    // them — which the original measurement could not do, because it moved
    // both at once. Six runs against MockWebServer, okhttp 4.x:
    //
    //   read 5s, call 1s, headers delayed 2s -> failed at 1019ms,
    //       InterruptedIOException("timeout"). The ceiling bounds the
    //       pre-response phase.
    //   read 5s, call 1s, body delayed 2s    -> COMPLETED at 2013ms. The
    //       ceiling does not bound anything after the response opens; this
    //       is timeoutEarlyExit, seen from outside.
    //   read 1s, call 0,  headers delayed 2s -> failed at 1003ms,
    //       SocketTimeoutException("Read timed out").
    //   read 1s, call 0,  body delayed 2s    -> failed at 1005ms, likewise.
    //   read 5s, call 0,  either delay 2s    -> completed, both controls.
    //
    // The third run corrects this comment. It used to claim "a control with
    // no ceiling and the same delay completed", offered as proof that the
    // cancellation was the ceiling rather than the delay. Removing the
    // ceiling and leaving the read timeout where it was does NOT complete —
    // the read timeout bounds the header wait on its own, at the same number.
    // The conclusion the claim was supporting is still right, and run one is
    // what supports it; the control as described does not reproduce.
    //
    // What that buys is the connect phase, which had no bound at all: the
    // read timeout does not run while okhttp is still working through routes,
    // so a hostname resolving to sixteen unroutable addresses spends sixteen
    // connect timeouts on a lane slot before the first read is attempted.
    // That is the blowup callCeiling(...) covers on the other two clients.
    //
    // The value is callCeiling(...) of streamingTimeout, as on the other two
    // clients, and it used to be streamingTimeout itself. The argument for
    // the bare value was that it "adds no new way for a healthy call to fail
    // — the read timeout already caps time-to-first-byte at this same
    // number". That is very nearly true and not quite: the read timeout caps
    // the *header wait*, while this caps the header wait PLUS connect and
    // request-write, so a call that connects slowly and then waits almost the
    // whole read budget for its first byte is cancelled here while the read
    // timeout would have allowed it. A narrow band, and it sits exactly where
    // a long silent prefill puts a healthy call. callCeiling's extra twenty
    // seconds is what the band is worth, and it is the same
    // read-budget-plus-two-connect-and-writes the chat and embedding clients
    // already use.
    //
    // The testability argument that chose the bare value — that callCeiling
    // puts the ceiling out of a loopback test's reach — decided nothing,
    // because no test reaches this ceiling either way. See below.
    //
    // What no test covers is that this ceiling does anything, and deleting
    // the line leaves the suite green, for the same reason and with the same
    // cause as callCeiling: reaching it needs a hostname resolving to enough
    // unroutable addresses to outlast the phase it bounds, and a MockWebServer
    // on loopback is one route that connects instantly.
    //
    // **This is not the stream's total bound and must not be read as one.**
    // Because timeoutEarlyExit fires the moment the body starts, an endpoint
    // that keeps emitting is bounded by nothing here — not by this, and not
    // by a read timeout that measures inactivity. That bound is
    // max-stream-duration, enforced in stream(...) on both sides of the
    // queue. An earlier version of this comment presented the ceiling as
    // closing the gap; it closed the connect half and left the half that
    // holds a Tomcat worker for good.
    this.streamingHttp =
        base.newBuilder()
            .readTimeout(props.getStreamingTimeout())
            .callTimeout(callCeiling(props.getStreamingTimeout()))
            .build();
    this.socketHttp =
        base.newBuilder()
            .readTimeout(Duration.ZERO)
            .callTimeout(Duration.ZERO)
            .pingInterval(SOCKET_PING)
            .build();
  }

  /**
   * A reasoning delta on its way through the queue that carries the answer.
   *
   * <p><b>A wrapper and not a second queue</b>, because the order is the point: reasoning and
   * answer interleave as the model produces them, and two queues would let a sink see them in an
   * order the model never used. A bare {@code String} is an answer delta; this is the other kind.
   *
   * @param text the reasoning this chunk added, never empty
   */
  private record Thought(String text) {}

  /**
   * A chunk's thinking, under whichever name this server gives it.
   *
   * <p><b>The field name is not standard and differs by model.</b> Measured on one node on
   * 2026-09-02: {@code qwen3.5-9b} sends {@code reasoning_content} and {@code gpt-oss-20b} sends
   * {@code reasoning} — 120 deltas of it on a question whose answer took 145. Nothing in the OpenAI
   * specification names either, so this reads both rather than choosing.
   *
   * <p><b>Why it matters that this is counted rather than merely read.</b> Thinking is dropped
   * unless somebody asks for it, but its volume is charged against the runaway cap either way:
   * measured, one model produced 6 571 characters of it against 1 965 of answer, so a cap watching
   * {@code content} alone watches under a quarter of what a box generates. Reading only one
   * spelling puts a model back in that position without any test noticing, because the answer still
   * arrives.
   *
   * <p><b>"Dropped" became "dropped by default" when {@link Deltas} arrived</b>, and the
   * distinction is the whole of what changed here: a sink that overrides {@code thought} is handed
   * this text as it comes, and one that does not — which is a bare lambda, and therefore nearly
   * every caller — sees exactly what it saw before. <b>The cap is not part of that choice.</b>
   * Reasoning counts against it whether or not anyone is listening, because a generation that will
   * not stop is a generation that will not stop.
   *
   * <p>Both are read off every chunk. A server that sends neither yields the empty string and costs
   * nothing; one that sends both is charged for both, which is the safe direction for a bound.
   */
  private static String thinking(JsonNode delta) {
    String named = delta.path("reasoning_content").asText("");
    return named.isEmpty() ? delta.path("reasoning").asText("") : named;
  }

  /**
   * How often an idle websocket is pinged. Well inside the interval any intermediary would call
   * idle, and far cheaper than the prefill it keeps alive.
   */
  private static final Duration SOCKET_PING = Duration.ofSeconds(20);

  /**
   * {@inheritDoc}
   *
   * <p>On this transport's own client, which is the whole point of the interface: a vendor package
   * names the URL and this class supplies the connection, exactly as it does for {@link
   * #metadata(String)}.
   */
  @Override
  public okhttp3.WebSocket openWebSocket(okhttp3.HttpUrl url, okhttp3.WebSocketListener listener) {
    return socketHttp.newWebSocket(new Request.Builder().url(url).build(), listener);
  }

  /**
   * A hard ceiling on one call, independent of how many routes DNS offers.
   *
   * <p>The read timeout alone does not bound a call: it restarts on every byte and does not run at
   * all while OkHttp is still working through routes, so the connect phase costs routes x
   * connect-timeout with routes set by whoever runs the DNS zone. {@link LlmTransport} makes this
   * transport's timeouts the only thing between a wedged endpoint and a Tomcat worker blocked for
   * good — {@code LlmPool} waits on the result with no deadline of its own — so "bounded" has to
   * mean bounded by a number this class chose, not by a number a hostname's operator chose.
   *
   * <p>The read budget plus two connect-and-write budgets: enough to establish a connection, lose
   * it to one bad route, and then still spend the whole wait the operator configured. Anything past
   * that is a host that is not going to answer.
   *
   * <p><b>No test covers this, and the reason is worth stating so nobody concludes the ceiling is
   * redundant from a green suite.</b> Reaching it requires a host whose name resolves to enough
   * unroutable addresses to outlast the read timeout, and a MockWebServer on loopback is one route:
   * in every test here the read timeout fires long first, so deleting {@code callTimeout} leaves
   * all of them passing. Constructing the multi-route case would mean a test that depends on real
   * DNS, which this slice forbids for better reasons than this one is worth. Per the same lesson as
   * the eviction above, that is evidence about what a loopback server can simulate, not about
   * whether the guard carries anything.
   */
  private static Duration callCeiling(Duration readTimeout) {
    return readTimeout.plus(CONNECT_AND_WRITE.multipliedBy(2));
  }

  /**
   * How many calls OkHttp may have in flight to this host — which has to be the pool's business
   * rather than OkHttp's.
   *
   * <p>{@code Dispatcher.maxRequestsPerHost} defaults to <b>5</b> and applies only to calls made
   * through {@code enqueue}. Everything in the embedding half goes through {@code execute()}, which
   * is not counted against it at all, so the default was invisible and harmless until now. {@code
   * okhttp-sse} enqueues, so a stream is the first thing here it can touch.
   *
   * <p>Left at 5, a pool declaring six or more chat slots would have its sixth stream sitting in
   * {@code readyAsyncCalls} <em>inside OkHttp</em>. {@code LlmPool.queueDepth} counts what is
   * waiting in the lane and cannot see that queue; the lane would report a slot as
   * occupied-and-working when the request had not left the process, and {@code LlmDispatcher}
   * routes on exactly {@code load}. A transport that queues internally makes the pool's numbers a
   * lie, which the design forbids outright — so the ceiling is derived from the pool's own slot
   * count instead.
   *
   * <p>Twice the slot count, and the doubling is the part that is easy to drop as superstition. A
   * stream's lane slot is released the moment this class returns the assembled {@link Completion},
   * but the OkHttp call behind it is still counted as running until its reader thread unwinds from
   * the {@code cancel()} in {@code stream}'s finally block. So the next stream can start —
   * legitimately, with a free lane slot — while its predecessor still occupies a dispatcher slot.
   * At exactly {@code chat} the two would collide and queue internally on every hand-over, which is
   * the very fault this method exists to prevent, arrived at by fixing it too tightly.
   *
   * <p>Never below OkHttp's own default: nothing here should be able to <em>reduce</em>
   * concurrency, and {@code chat: 1} is the common case.
   *
   * <p><b>The doubling is headroom and not a proof, and no test distinguishes it from no doubling
   * at all.</b> {@code more_streams_than_okhttps_default_reach_the_host_at_once} sets {@code chat:
   * 8} and needs eight, which a plain {@code 1L *} also delivers; the hand-over race it is really
   * about needs a reader unwinding more slowly than its successor starts, which is timing this
   * suite cannot stage. Said plainly because every other unmeasured guard in this file carries an
   * admission, and a green suite around a justification reads as coverage. The honest bound is
   * weaker than "cannot queue internally" too: if cancelled readers unwind slower than their
   * successors complete, N lane slots can accumulate more than 2N unreaped calls and OkHttp queues
   * anyway, at a higher threshold. The lane is the real bound on concurrency; this only stops
   * OkHttp's default from being a second, invisible one.
   *
   * <p>Read once in the constructor, which departs from the rule {@link #url(String)} and {@link
   * #apiKeySnapshot()} both state, so it is stated rather than left as an inconsistency: a
   * dispatcher's ceiling is fixed when the client is built and there is no per-call moment at which
   * to re-read it. The rule is about values consulted per call.
   *
   * <p>Not the file's <em>only</em> such departure, as this used to claim. The three read timeouts
   * are read from the same mutable bean in the same constructor and for the same reason — an OkHttp
   * client's timeouts are immutable once built. The rule and its exceptions are about when a value
   * can still be acted on, not about how many there are.
   */
  private static int streamCeiling(int chatSlots) {
    // Widened to long before doubling: chat is not validated on this class's
    // side of the boundary, and 2 * Integer.MAX_VALUE is a negative ceiling
    // that Math.max would silently accept.
    long doubled = 2L * Math.max(1, chatSlots);
    return (int)
        Math.min(Integer.MAX_VALUE, Math.max(OKHTTP_DEFAULT_MAX_REQUESTS_PER_HOST, doubled));
  }

  /** {@inheritDoc} */
  @Override
  public String poolName() {
    return props.getName();
  }

  @Override
  public Completion complete(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
    return complete(wireModel, messages, sampling, tools, null);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice) {
    return complete(wireModel, messages, sampling, tools, toolChoice, InferenceObserver.NONE);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      InferenceObserver observer) {
    return complete(wireModel, messages, sampling, tools, toolChoice, observer, null);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      InferenceObserver observer,
      Duration timeout) {
    // One read of the key for the whole call, for the reason spelled out on
    // embed below.
    String apiKey = apiKeySnapshot();
    Request request =
        request(
            url("/chat/completions"),
            chatBody(wireModel, messages, sampling, false, tools, toolChoice),
            apiKey);
    return executeWithRetry(
        withTimeout(chatHttp, timeout),
        request,
        "chat",
        apiKey,
        observer,
        (body, attempt) -> {
          JsonNode root = read(body);
          attempt.usage(usage(root));
          attempt.reason(finishReason(root.path("choices").path(0)));
          attempt.response(null, providerRequestId(root.path("id").asText(null), apiKey));
          return parseCompletion(root);
        },
        timeout);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>One shot: a stream is never retried.</b> {@link #executeWithRetry} is deliberately not on
   * this path. A second attempt would replay the answer from its first token to a sink that has
   * already been handed a prefix of it, and a sink has no way to tell a replay from a continuation
   * — it would append the whole answer to the part of the answer it already wrote. The blocking
   * path retries a dropped socket because nobody has seen anything yet; here somebody may have.
   *
   * <p><b>The sink runs on the calling thread</b>, which is the contract {@link
   * LlmTransport#stream} states and the thing a direct port of Anchor's {@code completeStreaming}
   * does not do. {@code EventSources} calls its listener back on an OkHttp dispatcher thread, so
   * the listener here does not touch {@code sink} at all: it hands each delta to a queue, and this
   * thread drains that queue and calls the sink from it. A sink that blocks therefore blocks the
   * lane thread — holding the inference slot the pool is counting, which is what makes {@code
   * LlmPool.load} true — instead of blocking an OkHttp reader thread the pool knows nothing about.
   *
   * <p>The alternative was to call {@code execute()} and frame the SSE body by hand on this thread.
   * That is rejected for the reason the build file gives for depending on {@code okhttp-sse} in the
   * first place: {@code data: [DONE]}, multi-line data fields, comment lines and retry directives
   * are a wire format with an implementation already on the classpath, and a second one here would
   * be a second thing to get wrong about it.
   *
   * <p><b>What the plan proposed is a third shape, and it cannot work.</b> Its code block keeps
   * Anchor's listener — {@code tokenHandler.accept(delta)} called from {@code onEvent} — and makes
   * the method blocking by ending with {@code future.get()}. That makes the <em>method</em>
   * synchronous, which is not what the contract is about: the sink still runs on an OkHttp
   * dispatcher thread and the calling thread merely parks until the whole thing is over. A queue in
   * between is what actually moves the sink, and once there is one, blocking on a future is
   * redundant. Recorded because the plan's block is the obvious thing to reach for and it reads as
   * though it satisfies the contract.
   *
   * <p>The queue is unbounded, which is a deliberate choice against backpressure. A bounded one
   * would push back on the reader thread, which sounds better until this thread leaves the loop
   * early — a sink that threw — and leaves that reader parked on {@code put} forever, holding an
   * OkHttp dispatcher thread that nothing will ever release. Unbounded, the reader is stopped by
   * the {@code cancel()} below and, before that, by the producer's own {@link #MAX_STREAM_CHARS}
   * cap.
   *
   * <p>This paragraph used to end "the reader only ever buffers one completion's worth of text",
   * offered as the reason unbounded was safe. {@link #MAX_STREAM_CHARS} quotes that sentence in
   * order to refute it — it is an assumption about the endpoint rather than anything this class
   * enforces, since {@link #chatBody} sets no {@code max_tokens} — and the cap was added to make it
   * true. Both sentences then sat three hundred lines apart, one asserting what the other had
   * already disproved; the claim is deleted here and the bound named instead.
   *
   * <p>An interrupt on the calling thread abandons the stream, re-sets the flag and fails. The flag
   * is the load-bearing half, for the reason {@code
   * an_interrupt_during_backoff_stops_the_call_and_stays_interrupted} gives about the blocking
   * path: {@code LlmPool.submit} has an {@code InterruptedException} branch of its own that sheds
   * the request and re-interrupts, and that branch is unreachable if a transport several frames
   * down swallows the interrupt.
   *
   * <h2>Tools, and how this path is held to the blocking one's answer</h2>
   *
   * <p>This used to send no tools and report none, because reassembling {@code tool_calls} deltas
   * was work the slice that wrote it did not do. {@link ToolCallDeltas} does it now: the fragments
   * are accumulated per {@code tool_calls[].index} on the reader thread and turned into {@link
   * ToolCall}s by {@link #toolCall}, which is the same method the blocking path ends in. One
   * constructor, so the two cannot drift apart in what they build; what they still do separately is
   * the reading, which is what {@code the_two_paths_answer_a_tool_call_identically} pins.
   *
   * <p><b>The accumulator is written on the reader thread and read on this one, and the queue is
   * what makes that safe.</b> Every fragment is absorbed before the terminal event is put on {@code
   * events}, and this thread reads the accumulator only after taking that terminal — {@code
   * LinkedBlockingQueue} gives the happens-before, exactly as it does for the deltas themselves.
   * Its methods are synchronized anyway, and the reason is stated as the limit of what was checked
   * rather than as a claim about okhttp-sse: nothing here establishes that no event is delivered
   * after the terminal, and if one ever is, the difference between synchronized and not is a stale
   * read against a data race. It costs one uncontended monitor per chunk on a path that is already
   * doing JSON parsing.
   *
   * <h2>Reasoning is read, counted, and dropped</h2>
   *
   * <p>{@code delta.reasoning_content} was referenced nowhere in this codebase before this method:
   * the system paid for thousands of reasoning tokens, waited ~96 seconds for them, and discarded
   * them by accident. It is still dropped, and now on purpose, for three reasons that hold
   * together:
   *
   * <ul>
   *   <li><b>It is not the answer.</b> {@code Outcome.text} is a run's answer and {@code sink} is
   *       what a caller is told the answer is; a model's working shown to either would change what
   *       an answer is, which this slice deliberately does not do. Nothing above this line has a
   *       field for it, and inventing a consumer for it here would be the transport deciding a
   *       product question.
   *   <li><b>What it costs is kept.</b> {@code completion_tokens_details.reasoning_tokens} is read
   *       by {@link #usage(JsonNode)} into {@link TokenUsage#reasoningTokens()} and reaches the
   *       ledger with every other count, so the largest thing this system pays for is now a number
   *       an operator can see. Dropping the text while dropping the measurement too is the thing
   *       that was wrong before; only the first half of that is still true.
   *   <li><b>Its volume is bounded.</b> Reasoning counts against {@link #MAX_STREAM_CHARS}
   *       alongside content and arguments. That is a fix rather than bookkeeping: measured on
   *       2026-09-02 the reasoning was 6 571 characters against 1 965 of content, so a cap that
   *       watched content alone was watching under a quarter of what the box was generating, and a
   *       model stuck in a reasoning loop would have run to {@code max-stream-duration} instead of
   *       being cut off as the runaway it is.
   * </ul>
   *
   * <p>A count of the characters thought is logged at debug when the stream finishes, which is the
   * cheapest way for someone chasing a slow turn to see that the time went on thinking. The text
   * itself never reaches a log line: it is model output about a user's prompt, and a debug log is
   * not where that belongs.
   */
  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned) {
    return stream(wireModel, messages, sampling, tools, sink, abandoned, (ToolChoice) null);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice) {
    return stream(
        wireModel, messages, sampling, tools, sink, abandoned, toolChoice, InferenceObserver.NONE);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      InferenceObserver observer) {
    return stream(
        wireModel, messages, sampling, tools, sink, abandoned, toolChoice, observer, null);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      InferenceObserver observer,
      Duration timeout) {
    String apiKey = apiKeySnapshot();
    Request request =
        request(
                url("/chat/completions"),
                // Tools go out here exactly as they do on the blocking
                // path, and stream_options rides along inside chatBody —
                // see there for why a stream that does not ask for usage
                // is never told.
                chatBody(wireModel, messages, sampling, true, tools, toolChoice),
                apiKey)
            .newBuilder()
            .header("Accept", "text/event-stream")
            .build();

    if (abandoned.getAsBoolean()) {
      throw new CallerAbandonedException(props.getName());
    }
    InferenceObserver.Attempt attempt = observer.attempt();
    try {
      Completion completion =
          consumeStream(
              observedRequest(request, observer),
              observedHttp(withTimeout(streamingHttp, timeout), observer),
              sink,
              abandoned,
              apiKey,
              attempt,
              timeout);
      attempt.succeeded(completion.usage(), completion.finishReason());
      return completion;
    } catch (RuntimeException | Error failure) {
      attempt.failed(failure);
      throw failure;
    }
  }

  @Override
  public Duration promptTimeout() {
    return props.getMaxStreamDuration();
  }

  /** A global budget covers silent prefill without a shorter pool read timeout. */
  private static OkHttpClient withTimeout(OkHttpClient http, Duration timeout) {
    return timeout == null
        ? http
        : http.newBuilder().readTimeout(timeout).callTimeout(timeout).build();
  }

  private Completion consumeStream(
      Request request,
      OkHttpClient http,
      Deltas sink,
      BooleanSupplier abandoned,
      String apiKey,
      InferenceObserver.Attempt attempt,
      Duration timeout) {
    // Wall clock, fixed before the call starts, and the only total bound a
    // stream has. nanoTime and not currentTimeMillis, so a clock step cannot
    // extend or collapse it.
    Duration budget = timeout == null ? props.getMaxStreamDuration() : timeout;
    String budgetKey = timeout == null ? "max-stream-duration" : "prompt-timeout/fold-timeout";
    long deadline = System.nanoTime() + budget.toNanos();

    // Each element is a String delta, a Failure, DONE_MARKER or CLOSED_MARKER.
    BlockingQueue<Object> events = new LinkedBlockingQueue<>();
    // Exactly one terminal event reaches the queue: onClosed fires after a
    // [DONE] that already ended the stream, and the cancel() below makes the
    // reader throw into onFailure after a normal finish.
    //
    // No test distinguishes this from an unconditional add, and that is
    // recorded rather than left for the next reader to discover as a cheap
    // missing test. The drain loop below stops at the first terminal it
    // takes, so a second one lands in a queue nobody reads and is inert
    // today, whichever order the two arrive in. What the CAS buys is that
    // "one terminal per stream" is a property of the queue rather than an
    // accident of the loop leaving early — and the loop is what a later
    // change would touch. Per the slice's own lesson, a surviving mutant is
    // evidence about the tests and not about which guard carries the
    // guarantee.
    //
    // Guarding against *zero* terminals is a different matter and is not
    // done here. It is the consumer's poll bound below, because no guard on
    // the producer helps when the producer is what failed to run.
    AtomicBoolean terminated = new AtomicBoolean();
    AtomicReference<String> finishReason = new AtomicReference<>();
    AtomicReference<TokenUsage> cost = new AtomicReference<>(TokenUsage.UNKNOWN);
    // Only ever touched on the reader thread, of which okhttp-sse runs one
    // per event source, so a plain counter is enough and an atomic would
    // only suggest otherwise. Boxed in an array because a lambda-captured
    // local cannot be reassigned.
    //
    // buffered counts everything generated — content, thinking and tool-call
    // arguments — because MAX_STREAM_CHARS is about a generation that will
    // not stop, and a model in a reasoning loop is one. reasoned counts only
    // the thinking, for the debug line at the end.
    long[] buffered = {0L};
    long[] reasoned = {0L};
    // Read on this thread after the terminal, and synchronized for the
    // chunk that arrives after one. See the javadoc.
    ToolCallDeltas drafts = new ToolCallDeltas();

    EventSourceListener listener =
        new EventSourceListener() {
          @Override
          public void onOpen(EventSource source, Response response) {
            attempt.response(
                response.code(),
                providerRequestId(
                    response.header("x-request-id", response.header("apim-request-id")), apiKey));
          }

          @Override
          public void onEvent(EventSource source, String id, String type, String data) {
            if ("[DONE]".equals(data)) {
              terminate(events, terminated, DONE_MARKER);
              return;
            }
            // Both caps live here rather than in the consumer because this
            // is the only side that can stop the endpoint. Failing on the
            // consumer alone would abandon the call and leave the box
            // generating into a socket nobody reads, still holding an
            // inference slot on the host — the outage this exists to
            // prevent, moved one hop away.
            //
            // Deleting this check leaves the suite green, because the
            // consumer's poll bound below fires on the same deadline and
            // reports the same thing. The two mask each other from outside
            // and no test in this file can separate them: what distinguishes
            // them is whether the endpoint is told to stop, which is not
            // observable through a MockWebServer without counting sockets.
            // Per the slice's own lesson that is evidence about the tests. The
            // argument is one-directional and has to live here because no
            // test can carry it — the consumer bound alone is not sufficient
            // (it abandons rather than cancels) and this check alone is not
            // sufficient either (onEvent never runs for a heartbeat-only
            // stream). Neither is redundant.
            if (System.nanoTime() - deadline >= 0) {
              terminate(events, terminated, new Failure(overran(budget, budgetKey), null));
              source.cancel();
              return;
            }
            // Asked once per chunk, and before the chunk is parsed: a caller
            // that has gone has no use for what is in it.
            //
            // Here rather than in the sink, because a sink is not called for
            // a chunk that carries no content and the measured tool-carrying
            // turn had none at all — sixty reasoning deltas and two tool-call
            // deltas across 63 chunks, 2026-09-02. Cancellation routed
            // through the sink would therefore never have fired on an
            // agent's ordinary turn while looking, from outside, as though
            // it worked.
            //
            // cancel() and not merely a terminal: the point is to stop the
            // box generating into a socket nobody reads, which is the same
            // argument the deadline above makes. The finally block cancels
            // too and this is not redundant with it — that one runs when the
            // lane thread unwinds, and the lane thread is still parked in
            // poll() until this terminal reaches it.
            if (abandoned.getAsBoolean()) {
              terminate(events, terminated, ABANDONED_MARKER);
              source.cancel();
              return;
            }
            JsonNode chunk;
            try {
              chunk = mapper.readTree(data);
            } catch (JsonProcessingException e) {
              terminate(events, terminated, new Failure("a chunk was not JSON", e));
              source.cancel();
              return;
            }
            JsonNode choice = chunk.path("choices").path(0);
            String reason = finishReason(choice);
            if (reason != null) {
              finishReason.set(reason);
              attempt.reason(reason);
            }
            TokenUsage reported = usage(chunk);
            if (chunk.has("usage")) {
              // Usage is a snapshot, including an explicit unknown/invalid final snapshot.
              cost.set(reported);
              attempt.usage(reported);
            }
            JsonNode delta = choice.path("delta");

            // Thinking. Counted and then let go of — see the javadoc for
            // the argument, and note that a chunk carrying reasoning
            // carried nothing else in every one of the sixty measured on
            // 2026-09-02. That is not assumed here: this reads all three
            // fields off every chunk, so a server that packs them together
            // loses nothing.
            String reasoning = thinking(delta);
            int thought = reasoning.length();
            reasoned[0] += thought;
            if (!reasoning.isEmpty()) {
              // DOWN THE SAME QUEUE AS THE ANSWER, wrapped so the drain
              // can tell which it has. One queue and not two because the
              // ORDER matters -- reasoning and answer interleave, and a
              // second queue would let a sink see them out of the order
              // the model produced them. It is also the same hand-off, so
              // this listener still does no work on the OkHttp thread
              // beyond an add.
              events.add(new Thought(reasoning));
            }

            // Tool-call fragments, accumulated per index. Returns how many
            // characters it absorbed, so that arguments count against the
            // runaway cap like everything else the box generated.
            int arguments = drafts.accept(delta);

            String token = delta.path("content").asText("");
            if (thought > 0
                || arguments > 0
                || !token.isEmpty()
                || !delta.path("tool_calls").isEmpty()) {
              attempt.output();
            }
            buffered[0] += (long) thought + arguments + token.length();
            if (buffered[0] > MAX_STREAM_CHARS) {
              terminate(
                  events,
                  terminated,
                  new Failure(
                      "the answer passed "
                          + MAX_STREAM_CHARS
                          + " characters, which is a"
                          + " generation that is not going to stop rather than a long"
                          + " one",
                      null));
              source.cancel();
              return;
            }
            if (token.isEmpty()) {
              // Nothing for the sink. A chunk that was pure reasoning or
              // pure tool-call delta is not an empty token to be
              // forwarded: a sink is told what the answer is, and neither
              // of those is the answer.
              return;
            }
            events.add(token);
          }

          @Override
          public void onClosed(EventSource source) {
            // Closed, not finished — the consumer decides which of those it
            // was, because only it knows whether a finish reason arrived.
            terminate(events, terminated, CLOSED_MARKER);
          }

          @Override
          public void onFailure(EventSource source, Throwable t, Response response) {
            // Checked here and not only inside terminate: streamDetail reads
            // the response body off the wire, and a terminal that is going to
            // be discarded should not pay for a read on the reader thread.
            if (terminated.get()) {
              return;
            }
            // Read inside the callback: okhttp-sse hands the response over
            // inside its own try-with-resources and closes it as soon as
            // this returns, so a body deferred to the lane thread would be
            // gone by the time it was wanted.
            if (response != null) {
              attempt.response(
                  response.code(),
                  providerRequestId(
                      response.header("x-request-id", response.header("apim-request-id")), apiKey));
            }
            terminate(events, terminated, new Failure(streamDetail(t, response, apiKey), t));
          }
        };

    // Held rather than re-read from the field. The spec defers a fix in which
    // this class rebuilds its OkHttp client on a connection failure; a
    // rebuild kills in-flight *asynchronous* calls, and SSE is the only
    // asynchronous thing here. Written this way, that later change cannot
    // tear down a live stream.
    EventSource source = EventSources.createFactory(http).newEventSource(request, listener);

    StringBuilder content = new StringBuilder();
    try {
      while (true) {
        // poll and not take, bounded by what is left of the budget. Two
        // separate failures need this and they need different halves of
        // it.
        //
        // A terminal can be lost outright. RealEventSource.processResponse
        // catches Exception, so a listener RuntimeException does become
        // onFailure — but an Error does not: RealCall$AsyncCall.run sets
        // signalledCallback = true before calling onResponse, so its
        // if (!signalledCallback) fallback never runs and no callback is
        // delivered at all. The dispatcher slot is released and this
        // thread is not, and LlmPool.submit then waits on task.get() with
        // no deadline of its own. The realistic Error is an
        // OutOfMemoryError, which is what MAX_STREAM_CHARS exists to
        // prevent — the two are the same incident from opposite ends.
        //
        // And a stream can deliver bytes without ever delivering an
        // event: okhttp-sse discards SSE comment lines, so a proxy
        // heartbeat keeps the read timeout satisfied while onEvent, where
        // the producer-side deadline lives, is never called at all.
        // Nothing on the reader thread can notice that. This does.
        //
        // Bounding it tighter than the remaining budget would be wrong
        // rather than merely conservative: the heartbeat case is
        // legitimate while a model is still processing a long prompt, and
        // a per-event bound near streamingTimeout would fail those calls
        // for being slow to first token.
        long remaining = deadline - System.nanoTime();
        if (abandoned.getAsBoolean()) {
          throw new CallerAbandonedException(props.getName());
        }
        Object event =
            remaining <= 0
                ? null
                : events.poll(
                    Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
        if (event == null && deadline - System.nanoTime() > 0) {
          continue;
        }
        if (event == null) {
          throw new LlmTransportException(
              prefix() + "streaming chat failed: " + overran(budget, budgetKey));
        }
        if (event instanceof Thought reasoning) {
          // Never appended to `content`: reasoning is not the answer,
          // and a `Completion` that carried it would make the outcome
          // disagree with itself.
          sink.thought(reasoning.text());
        } else if (event instanceof String delta) {
          content.append(delta);
          // Anything this throws leaves through the caller's frame
          // unwrapped, which is what LlmTransport.stream promises: a
          // sink failure is the caller's own fault and must not be
          // dressed up as the endpoint's.
          sink.answered(delta);
        } else if (event == ABANDONED_MARKER) {
          // Not an LlmTransportException: the endpoint was fine and
          // the caller left. See CallerAbandonedException for why the
          // two must not be able to look like each other, and why what
          // this does to the caller's own work is not decided here.
          throw new CallerAbandonedException(props.getName());
        } else if (event instanceof Failure failure) {
          throw new LlmTransportException(
              prefix() + "streaming chat failed: " + failure.detail(), failure.cause());
        } else if (event == CLOSED_MARKER && finishReason.get() == null) {
          // A connection that closed with neither [DONE] nor a finish
          // reason did not deliver an answer, and handing back what
          // arrived would make a truncated generation indistinguishable
          // from a complete one whose endpoint reported no reason —
          // defeating the check Completion.finishReason exists to
          // enable. The same hazard as reading an explicit JSON null as
          // the string "null", arriving by another door.
          throw new LlmTransportException(
              prefix()
                  + "streaming chat failed: the endpoint closed the stream after "
                  + content.length()
                  + " characters without finishing it");
        } else {
          // A terminal that means the answer is whole: [DONE], or a
          // close that followed a finish reason. Everything the reader
          // thread absorbed happened before it put this on the queue,
          // so the accumulator is settled and reading it here needs no
          // further synchronisation of its own.
          //
          // cost.get() is TokenUsage.UNKNOWN unless some chunk carried
          // a usage object, which is what a stream that ended without
          // one must report: never a zero, because Compaction reads
          // prompt_tokens to decide a fold and a zero would read as an
          // empty history that never needs one.
          List<ToolCall> asked = drafts.assemble();
          if (reasoned[0] > 0) {
            // The count and never the text: reasoning is model
            // output about someone's prompt, and a log is not where
            // that goes. What an operator needs from it is where the
            // wall clock went, which is this ratio.
            log.debug(
                "pool '{}': {} characters of thinking before {} of answer;"
                    + " the thinking is measured and not carried",
                props.getName(),
                reasoned[0],
                content.length());
          }
          return new Completion(content.toString(), finishReason.get(), cost.get(), asked);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmTransportException(prefix() + "interrupted mid-stream", e);
    } finally {
      // On every exit, including the normal one. The lane slot is released
      // the moment this method returns, but the reader thread is still
      // inside the response body until something stops it, holding a
      // connection and one of the dispatcher slots streamCeiling(...)
      // counts.
      //
      // The abnormal exits are where this earns its place rather than the
      // [DONE] one: a sink that threw on the first token of a long
      // generation would otherwise leave the reader pulling the remaining
      // few thousand tokens into a queue nobody will ever drain.
      //
      // No test distinguishes it from its absence, and the honest reason
      // is not that a loopback server cannot stage the case. It can:
      // throttleBody makes a body outlive the call reading it, which is
      // how the streaming call timeout above was measured. The reason is
      // that the consequence has no cheap assertion — what this prevents
      // is a reader thread and a dispatcher slot held longer than the lane
      // slot, and nothing outside this class can tell "released now" from
      // "released when the server closes the socket" without reaching into
      // OkHttp's dispatcher to count running calls. The line stands on the
      // argument, not on a surviving mutant.
      source.cancel();
    }
  }

  /**
   * One wording for the total bound, because the producer and the consumer both report it and an
   * operator should not have to work out that those are one fault seen from two sides.
   */
  private static String overran(Duration budget, String setting) {
    return "the stream was still going after "
        + budget.toMillis()
        + "ms, which is its whole budget ("
        + setting
        + ")";
  }

  /** The queued end of a stream, whichever way it ended. */
  private static void terminate(
      BlockingQueue<Object> events, AtomicBoolean terminated, Object terminal) {
    if (terminated.compareAndSet(false, true)) {
      events.add(terminal);
    }
  }

  /** Why a stream ended badly, in words already safe to print — see {@link #streamDetail}. */
  private record Failure(String detail, Throwable cause) {}

  /**
   * {@code delta.tool_calls} fragments, accumulated into what {@code message.tool_calls} would have
   * carried whole.
   *
   * <h2>What the endpoint actually sent</h2>
   *
   * <p>Measured 2026-09-02, one tool-carrying request over 63 chunks: sixty {@code
   * reasoning_content} deltas, no {@code content} deltas, then two {@code tool_calls} deltas and
   * {@code finish_reason: tool_calls}. No chunk carried two kinds, and {@code arguments} arrived as
   * {@code ''} then {@code {"city":"Paris"}} — a concatenation of two pieces, not a
   * character-by-character fragmentation.
   *
   * <p><b>One sample is one sample, so none of that is assumed here.</b> This accumulates per
   * {@code index} and concatenates every {@code arguments} fragment, so a stream that splits
   * arguments one character to a chunk, or interleaves two calls, or mixes reasoning and tool calls
   * in one chunk, assembles the same way. The measured shape is the easy path through this code
   * rather than the only path it has.
   *
   * <h2>{@code id} and {@code name}: first usable value wins</h2>
   *
   * <p>Not concatenated, which is the asymmetry worth defending. OpenAI sends both once, on the
   * first fragment for an index, and other OpenAI-compatible servers repeat them unchanged on every
   * fragment — concatenating would turn {@code call_1} into {@code call_1call_1} against those,
   * silently, and a turn loop would then file a tool result under an id the assistant turn does not
   * declare. Taking the first usable value is right for both of those and wrong only for a server
   * that split a tool's <em>name</em> across chunks, which nothing specifies and nothing observed
   * does. {@code arguments} is the field the format says is fragmented, and it is the one treated
   * as fragmented.
   *
   * <p>"Usable" is {@link #text(JsonNode)}: absent, null, non-string and blank all read as nothing
   * said, so an empty {@code "id":""} on the first fragment does not lock out a real one on the
   * second.
   *
   * <h2>Order</h2>
   *
   * <p>By {@code index} ascending, because that is the model's order and a runtime that reordered
   * two calls would run them in an order the model did not ask for — the same guarantee {@link
   * #toolCalls(JsonNode)} gets for free from array order. A fragment with no usable {@code index}
   * is filed under 0: a server that omits it has no way to say which of several calls a fragment
   * belongs to, so it is a server sending one.
   */
  private final class ToolCallDeltas {

    /**
     * Sorted, so {@link #assemble()} yields index order whatever order the fragments arrived in.
     */
    private final Map<Integer, Draft> byIndex = new TreeMap<>();

    private static final class Draft {
      private String id;
      private String name;
      private final StringBuilder arguments = new StringBuilder();
    }

    /**
     * Absorb one chunk's {@code delta}.
     *
     * @return how many characters of {@code arguments} this took in, so the caller can count them
     *     against its runaway cap. Zero for a delta with no tool calls in it, which is most of
     *     them.
     */
    synchronized int accept(JsonNode delta) {
      JsonNode calls = delta.path("tool_calls");
      if (!calls.isArray()) {
        return 0;
      }
      int absorbed = 0;
      for (JsonNode call : calls) {
        JsonNode index = call.path("index");
        Draft draft =
            byIndex.computeIfAbsent(
                index.isIntegralNumber() ? index.intValue() : 0, unused -> new Draft());
        JsonNode function = call.path("function");
        if (draft.id == null) {
          draft.id = text(call.path("id"));
        }
        if (draft.name == null) {
          draft.name = text(function.path("name"));
        }
        // Through the same helper the blocking path uses, so an
        // arguments object sent instead of a string is re-rendered the
        // same way on both, and an absent one contributes nothing
        // rather than the four characters of "null".
        String fragment = arguments(function.path("arguments"));
        draft.arguments.append(fragment);
        absorbed += fragment.length();
      }
      return absorbed;
    }

    /**
     * What the model asked for, in index order, or an empty list if it asked for nothing.
     *
     * <p>Through {@link #toolCall}, so a fragment stream that never carried an id or a name fails
     * here exactly as a blocking response missing one fails — naming the pool, rather than reaching
     * a runtime that can only report it as the model asking for a tool that does not exist.
     */
    synchronized List<ToolCall> assemble() {
      if (byIndex.isEmpty()) {
        return List.of();
      }
      List<ToolCall> parsed = new ArrayList<>(byIndex.size());
      for (Draft draft : byIndex.values()) {
        parsed.add(toolCall(draft.id, draft.name, draft.arguments.toString()));
      }
      return parsed;
    }
  }

  /**
   * What to say about a stream that failed, with the same guard on the endpoint's own words that
   * {@link #detail(int, String, String)} applies to a blocking refusal.
   *
   * <p>A stream's refusal arrives through {@code EventSourceListener.onFailure} and not through the
   * retry loop, so it is a second route from a response body into an exception message — and being
   * in the same class buys it nothing. A gateway that quotes the offered token back would otherwise
   * reach a 503 body by a path {@code detail} never sees.
   *
   * <p><b>The status branch is for a refusal only, and that is a correction.</b> It used to run
   * whenever {@code response} was non-null, which discarded the throwable on every failure after
   * the response had opened — and {@code okhttp-sse} passes a response on that path too, rebuilt
   * with an empty body. So the commonest streaming failure there is, a connection dropping
   * mid-generation, described itself as {@code HTTP 200} and nothing else, while {@code
   * ProtocolException: unexpected end of stream} survived only as a cause nobody prints. The
   * paragraph that used to sit here named three throwables as passed through unguarded, and all
   * three took the branch that threw them away — in a comment whose point was that the list had
   * been checked rather than assumed.
   *
   * <p>So: an unsuccessful status is a refusal, described by its status and its guarded body.
   * Anything else — a drop mid-body, a wrong content type, a rejected enqueue — is described by the
   * throwable, whose message is the only thing that says what happened.
   *
   * <p><b>The throwable's message goes through the same key guard as a response body</b>, which is
   * the second correction. It used to be passed through on the strength of an enumeration of
   * another library's exception types — the same style of reasoning that produced the error above.
   * And it is a real route, not hardening: {@code RealEventSource} refuses a non-event-stream
   * response with {@code IllegalStateException("Invalid content-type: " + contentType)}, and the
   * content type is the server's to choose, so a media type parameter is enough to put arbitrary
   * text of the endpoint's into a message this class prints. Pinned by {@code
   * a_stream_refused_on_its_content_type_never_repeats_the_key}. One rule covering both branches is
   * worth more than a list that has already been wrong once.
   */
  private String streamDetail(Throwable t, Response response, String apiKey) {
    if (response != null && !response.isSuccessful()) {
      return "HTTP "
          + response.code()
          + " "
          + detail(response.code(), refusalBody(response), apiKey);
    }
    String message = t == null ? null : t.getMessage();
    return message == null || message.isBlank()
        ? "the endpoint ended the stream without saying why"
        : withheldIfItQuotesTheKey(message, apiKey);
  }

  /**
   * The chat request body, matching Anchor's.
   *
   * <p>The conversation arrives as a list of {@link ChatMessage} and is written out in order. It
   * used to be a system prompt and a user prompt, and the rule those two encoded — a system message
   * only when the system prompt is non-blank, because a blank system turn is not the same input as
   * no system turn and a small model notices — now lives in {@code ChatMessage.conversation}, which
   * is what every prose caller still builds its two messages through.
   *
   * <p>{@code LinkedHashMap} rather than {@code Map.of} for the messages, so the JSON comes out in
   * a fixed order. {@code Map.of}'s iteration order varies per JVM run, which turns a request body
   * someone is reading in a log or asserting on in a test into a coin flip for no benefit.
   *
   * <p><b>{@code tools} appears only when there are some.</b> An empty {@code tools} array is not
   * the same thing as no {@code tools} key: some OpenAI-compatible servers refuse the former, so a
   * caller that never heard of tools would start being refused because the runtime grew a feature
   * it does not use. {@code a_request_with_no_tools_is_byte_identical_to_what_slice_two_sent} pins
   * the whole body against what this method produced before tools existed.
   */
  @Override
  public boolean automaticCounting() {
    return props.getCounting().isAutomatic();
  }

  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countChat(
      String model, io.aeyer.plowshare.server.llm.dispatch.ChatRequest request) {
    if (props.getCounting().getUrl() == null)
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "unsupported_counter");
    if (request.messages().stream().anyMatch(ChatMessage::carriesAnImage))
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "unsupported_images");
    if (request.toolChoice() != null && !request.tools().isEmpty()
        || request.sampling().reasoningEffort().isPresent())
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "unsupported_template_fields");
    Map<String, Object> wire =
        chatBody(
            model,
            request.messages(),
            request.sampling(),
            false,
            request.tools(),
            request.toolChoice());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("messages", wire.get("messages"));
    if (wire.containsKey("tools")) body.put("tools", wire.get("tools"));
    if (wire.containsKey("chat_template_kwargs"))
      body.put("chat_template_kwargs", wire.get("chat_template_kwargs"));
    body.put("add_generation_prompt", true);
    body.put("continue_final_message", false);
    body.put("add_special_tokens", false);
    return counted(model, List.of(body), request.attribution());
  }

  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countEmbedding(
      String model, io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest request) {
    if (!props.getCounting().isEmbeddings())
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "unsupported_embedding_counter");
    List<Map<String, Object>> bodies =
        request.input().stream()
            .<Map<String, Object>>map(
                text ->
                    Map.of(
                        "model",
                        model,
                        "prompt",
                        text,
                        "add_special_tokens",
                        props.getCounting().isEmbeddingSpecialTokens()))
            .toList();
    return counted(model, bodies, request.attribution());
  }

  private io.aeyer.plowshare.server.llm.counting.PromptCount counted(
      String model,
      List<Map<String, Object>> bodies,
      io.aeyer.plowshare.server.llm.accounting.UsageAttribution owner) {
    var settings = props.getCounting();
    if (settings.getUrl() == null)
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "unsupported_counter");
    try {
      settings.validate(props.getBaseUrl());
    } catch (IllegalArgumentException invalid) {
      return io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
          poolName(), model, "counter_invalid_configuration");
    }
    return promptCounter.count(
        poolName(),
        model,
        bodies,
        owner,
        settings,
        List.of(
            props.getBaseUrl(),
            props.getApiKey(),
            props.getChatTemplateKwargs(),
            props.getApiAuth()),
        (body, remaining) -> {
          byte[] encoded = mapper.writeValueAsBytes(body);
          if (encoded.length > 1024 * 1024) throw new java.io.IOException("counter request bound");
          var client =
              chatHttp
                  .newBuilder()
                  .retryOnConnectionFailure(false)
                  .followRedirects(false)
                  .followSslRedirects(false)
                  .connectTimeout(remaining)
                  .readTimeout(remaining)
                  .writeTimeout(remaining)
                  .callTimeout(remaining)
                  .build();
          var posted =
              authorized(HttpUrl.get(settings.getUrl()), props.getApiKey())
                  .post(RequestBody.create(encoded, MediaType.get("application/json")))
                  .build();
          try (Response response = client.newCall(posted).execute()) {
            if (!response.isSuccessful() || response.body() == null)
              throw new java.io.IOException("counter refused");
            byte[] bytes = response.body().byteStream().readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024)
              throw new java.io.IOException("counter response bound");
            return mapper.readTree(bytes);
          }
        });
  }

  private Map<String, Object> chatBody(
      String wireModel,
      List<ChatMessage> conversation,
      Sampling sampling,
      boolean stream,
      List<ToolSchema> tools,
      ToolChoice toolChoice) {
    boolean cloud = props.getRequestStyle() == PoolProperties.RequestStyle.CLOUD;
    var capabilities = props.capabilitiesFor(wireModel);
    if (cloud) capabilities.require(sampling, !tools.isEmpty());
    sampling = sampling.carriedBy(carries(wireModel, sampling));
    List<Map<String, Object>> messages = new ArrayList<>(conversation.size());
    for (ChatMessage message : conversation) {
      messages.add(wire(message));
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", wireModel);
    body.put("messages", messages);
    if (!cloud && !props.getChatTemplateKwargs().isEmpty())
      body.put("chat_template_kwargs", props.getChatTemplateKwargs());
    // ABSENT MEANS ABSENT. A key is written only when the caller stated a
    // value, and a request that states nothing carries no sampling keys at
    // all -- which is not a fallback to conservative numbers, it is the
    // absence of numbers. LM Studio resolves a setting model defaults ->
    // model.yaml -> load-time -> inference-time, later winning, so an
    // omitted key runs the model at whatever its own packaging recommends.
    // The runaway measured on 2026-09-04 was this method writing a
    // hardcoded 0.0 over a correct value, so the omission is the fix.
    sampling.temperature().ifPresent(value -> body.put("temperature", value));
    sampling.topP().ifPresent(value -> body.put("top_p", value));
    // A VENDOR EXTENSION AND NOT OPENAI'S. LM Studio documents `top_k` in
    // the accepted set for /v1/chat/completions; OpenAI's own API has no
    // such field. A backend that does not know it ignores it rather than
    // refusing -- which is exactly the silent difference `carries()` exists
    // to make loud, so nothing here is relying on that tolerance.
    sampling.topK().ifPresent(value -> body.put("top_k", value));
    if (cloud)
      body.put(
          capabilities.getOutputLimit().name().toLowerCase(Locale.ROOT),
          sampling.maxTokens().orElse(capabilities.getDefaultOutputTokens()));
    else sampling.maxTokens().ifPresent(value -> body.put("max_tokens", value));
    // PARSED SINCE THE PROFILES LANDED, AND SENT ONLY SINCE 2026-09-07.
    // `SamplingProfiles` has always read `reasoning_effort` into
    // `Sampling.Effort`, and no transport wrote it -- so `gpt-oss.yaml`'s
    // `reasoning_effort: low` was inert from the day it was committed, and
    // read as configuration while doing nothing. That is worse than an
    // absent feature, because the file said the question had been settled.
    //
    // Written here and not in `LmStudioSocket` because this is the shared
    // path: `LmStudio.complete` delegates to `OpenAiCompatible`, so both a
    // llama.cpp pool and an LM Studio one are reached through this method.
    // The socket carries prefill progress and nothing else, which is what
    // its own comment says it deliberately does not claim.
    //
    // A backend that does not know the field ignores it, which is the same
    // tolerance `top_k` above relies on and the same reason `carries()`
    // exists rather than trusting it.
    sampling.reasoningEffort().ifPresent(effort -> body.put("reasoning_effort", effort.wire()));
    // MEASURED ON BOTH NODES, 2026-09-07, which is the condition
    // `reasoning_effort` above was held to and eventually met. LM Studio
    // serving mlx-community/gemma-4-e4b-it and llama.cpp b10835 serving
    // glm-4.7-flash both enforce this, and both emitted a LaTeX command
    // correctly escaped inside a JSON string -- the case that forced
    // ask_reviewer to return prose, because \e is not one of JSON's nine
    // legal escapes and ModelJson.withEscapedBackslashes exists to repair
    // it after the fact. Under a schema the sampler cannot emit invalid
    // JSON at all.
    //
    // Declared in carries() as well as written here, and the two halves are
    // one decision: the dispatcher filters on carries(), so a transport
    // that wrote what it did not declare would have its own body's field
    // stripped from the Sampling before it ever got here.
    sampling.responseFormat().ifPresent(schema -> body.put("response_format", schema.asDeclared()));
    if (stream) {
      body.put("stream", true);
      // Asked for, because a stream that does not ask gets none.
      //
      // Measured 2026-09-02 against the reference node: without
      // stream_options not one chunk of a streaming response carried a
      // usage object at all, and with it the counts arrive on the final
      // chunk. That silence is the expensive kind — Compaction decides
      // folds from usage.prompt_tokens and TokenUsage.UNKNOWN is how "the
      // endpoint said nothing" is spelled, so a streaming path that never
      // asked would leave every conversation unable to decide when to fold
      // and nothing anywhere would report it.
      //
      // A nested Map.of rather than a LinkedHashMap: it is one key, so
      // there is no iteration order for it to make unpredictable, which is
      // the whole of the reason the messages above are ordered.
      body.put("stream_options", Map.of("include_usage", true));
    }
    if (!tools.isEmpty()) {
      // ToolSchema.asDeclared and not twelve lines here, because the shape
      // has a second reader: the context surface measures how many
      // characters this array is, and a measurement of a separately
      // assembled copy would agree only until somebody edited one of them.
      // That method carries the reasoning about "type": "function" and
      // about the map implementation.
      body.put("tools", ToolSchema.asDeclared(tools));
      // AFTER `tools` AND INSIDE ITS GUARD, WHICH IS THE WHOLE OF THE CARE
      // THIS KEY NEEDS.
      //
      // An endpoint told to require a tool call with no tools offered has
      // nothing it could answer with, and answers 400 -- so the key lives
      // inside the emptiness check rather than beside it. A caller that
      // asked for the constraint and offered nothing gets the request it
      // would have sent anyway, which is the only reading of that pair
      // that is not an error.
      //
      // Measured against vLLM serving openai/gpt-oss-120b, 2026-09-25,
      // one tool offered and a prompt that said "Do not call anything":
      // without this key, finish_reason=stop and the content was "I'm
      // finished."; with "required", finish_reason=tool_calls and a
      // well-formed orchestration_finish. That pair is why ToolChoice
      // exists -- see its javadoc for the production failure it is the
      // control for.
      if (toolChoice != null) {
        body.put("tool_choice", toolChoice.wire());
      }
    }
    return body;
  }

  /**
   * One message as the wire carries it.
   *
   * <p><b>{@code role} then {@code content}, and nothing else for the two roles slice 2 sent.</b>
   * That order and that key set are what keep a two-message conversation byte-identical to the body
   * this method produced before {@link ChatMessage} existed, which {@code
   * a_request_with_no_tools_is_byte_identical_to_what_slice_two_sent} pins. The two extra keys
   * appear only for the two roles that need them.
   *
   * <p>{@code tool_calls} is written back in the shape it was parsed from — {@code {"id",
   * "type":"function", "function":{"name","arguments"}}} — with {@code arguments} as the raw string
   * the model emitted. Not re-serialised from a parsed object: {@code ToolCall} keeps that string
   * unparsed on purpose, and round-tripping it through Jackson would rewrite the model's own bytes
   * into whatever this runtime's formatter prefers. A model shown arguments it did not write is
   * being shown a turn it did not take.
   *
   * <p><b>{@code content} is a scalar string unless the message carries a picture, and that is a
   * compatibility requirement rather than a preference.</b> The OpenAI contract admits both a
   * string and an array of typed parts for this field, and a hosted endpoint takes either — but
   * some OpenAI-compatible servers refuse an array for a plain text turn, and this project targets
   * the narrow set every backend accepts, which is the same rule {@code
   * ChatRequest.requireAtMostOneSystemMessageAndItFirst} was written under. Switching
   * unconditionally would also rewrite the request body of every one of the three and a half
   * thousand tests in this suite for no behavioural gain, and would break {@code
   * a_request_with_no_tools_is_byte_identical_to_what_slice_two_sent}, which is the regression that
   * protects the rest. So: an image present makes it an array, and nothing else does. See {@link
   * #content(ChatMessage)}.
   *
   * <p>{@code tool_call_id} on a tool message is the correlation the whole change exists for: it is
   * how the model knows which of several calls a result answers. Measured 2026-08-29 — qwen3.5-9b
   * does not batch (0/4) — so more than one tool message per turn is reachable only from a fixture,
   * which is why the ordering and the ids are asserted here at the layer that writes them rather
   * than left to an end-to-end run to reveal.
   */
  private static Map<String, Object> wire(ChatMessage message) {
    Map<String, Object> body = new LinkedHashMap<>(4);
    body.put("role", message.role().wireName());
    body.put("content", content(message));
    if (!message.toolCalls().isEmpty()) {
      List<Map<String, Object>> calls = new ArrayList<>(message.toolCalls().size());
      for (ToolCall call : message.toolCalls()) {
        Map<String, Object> function = new LinkedHashMap<>(2);
        function.put("name", call.name());
        function.put("arguments", call.arguments());
        Map<String, Object> entry = new LinkedHashMap<>(3);
        entry.put("id", call.id());
        // Required rather than defaulted, the same as on the way out in
        // chatBody: an entry without it is rejected as malformed rather
        // than read as a function.
        entry.put("type", "function");
        entry.put("function", function);
        calls.add(entry);
      }
      body.put("tool_calls", calls);
    }
    if (message.toolCallId() != null) {
      body.put("tool_call_id", message.toolCallId());
    }
    return body;
  }

  /**
   * What goes in {@code content}: a string, or an array of typed parts.
   *
   * <h2>The scalar is the default and the array is the exception</h2>
   *
   * <p>A message with no image serialises exactly as it did before parts existed — {@link
   * ChatMessage#content()} returns the same string the field used to hold, and it is written as a
   * bare JSON string. That is what keeps every fixture in this suite valid and every
   * OpenAI-compatible backend this project targets willing to answer.
   *
   * <p>With an image the field becomes the array the contract's other half defines: {@code
   * {"type":"text","text":…}} for the words and {@code
   * {"type":"image_url","image_url":{"url":"data:…"}}} for the picture, in the order the message
   * holds them. <b>Measured 2026-09-07</b> against {@code mlx-community/gemma-4-e4b-it} on LM
   * Studio: this exact shape, with a base64 data URI, answered "A red square is in the image."
   * about a hand-built 64×64 PNG in 3.0 s. It is not a shape read off documentation.
   *
   * <p><b>{@code image_url} and yet no URL is ever fetched.</b> The key is the contract's name for
   * the part and not an instruction to resolve anything: what goes in it is a {@code data:} URI
   * carrying the bytes inline, and {@link Content.Image} refuses to hold anything else. This
   * transport has no code that reads a URL out of a message, so there is nothing here for an SSRF
   * to reach — see {@code a_message_with_an_image_never_makes_the_transport_open_a_connection}.
   *
   * <p>An empty text part is not written. {@code ChatMessage} drops those, so this only ever sees
   * words somebody meant.
   */
  private static Object content(ChatMessage message) {
    if (!message.carriesAnImage()) {
      return message.content();
    }
    List<Map<String, Object>> parts = new ArrayList<>(message.parts().size());
    for (Content part : message.parts()) {
      if (part instanceof Content.Image image) {
        Map<String, Object> url = new LinkedHashMap<>(1);
        url.put("url", image.dataUri());
        Map<String, Object> entry = new LinkedHashMap<>(2);
        entry.put("type", "image_url");
        entry.put("image_url", url);
        parts.add(entry);
      } else {
        Map<String, Object> entry = new LinkedHashMap<>(2);
        entry.put("type", "text");
        entry.put("text", part.text());
        parts.add(entry);
      }
    }
    return parts;
  }

  private Completion parseCompletion(JsonNode root) {
    JsonNode choices = root.path("choices");
    if (!choices.isArray() || choices.isEmpty()) {
      throw new LlmTransportException(prefix() + "chat response had no choices");
    }
    JsonNode choice = choices.get(0);
    JsonNode message = choice.path("message");
    return new Completion(
        message.path("content").asText(""), finishReason(choice), usage(root), toolCalls(message));
  }

  /**
   * Every entry of {@code message.tool_calls}, in the order it arrived.
   *
   * <p>Order is preserved because it is the model's order, and a runtime that reordered two calls
   * would run them in an order the model did not ask for. Measured 2026-08-29 against qwen3.5-9b:
   * it emits at most one call per turn (0/4 when asked to batch two independent lookups), so a
   * second element is reachable only from a fixture — which is why the array is pinned by a test
   * here, at the one layer that reads the wire, rather than left to a runtime that would never see
   * it locally.
   *
   * <p><b>Where the line falls between this transport's problem and a tool's.</b> {@code arguments}
   * is passed through untouched, unparsed and unvalidated: there is no schema for an arbitrary tool
   * here, and rejecting bad JSON would throw away a completion the box already generated and was
   * paid for, turning a tool's problem into a transport failure. {@code id} and {@code name} are
   * the opposite — they are the wire protocol, which this class does own. Nothing downstream can
   * dispatch a call with no name or return a result for a call with no id, and defaulting either to
   * "" would report the endpoint's fault as the agent asking for a tool that does not exist. So
   * those two fail here, naming the pool.
   */
  private List<ToolCall> toolCalls(JsonNode message) {
    JsonNode calls = message.path("tool_calls");
    if (!calls.isArray() || calls.isEmpty()) {
      // Empty and never null. A null would put a null check in every
      // caller forever, and the caller that forgets fails on a lane
      // thread whose stack names the pool rather than the turn loop.
      return List.of();
    }
    List<ToolCall> parsed = new ArrayList<>(calls.size());
    for (JsonNode call : calls) {
      JsonNode function = call.path("function");
      parsed.add(
          toolCall(
              text(call.path("id")),
              text(function.path("name")),
              arguments(function.path("arguments"))));
    }
    // Returned as built. Completion's compact constructor runs List.copyOf
    // over this, so wrapping it here was a guard the next line undid.
    return parsed;
  }

  /**
   * One tool call, from whichever path read it.
   *
   * <p><b>The single place either path turns three fields into a {@link ToolCall}, and that is what
   * holds the two to the same answer.</b> The blocking path reads them out of {@code
   * message.tool_calls} and the streaming path out of accumulated {@code delta.tool_calls}
   * fragments, and both arrive here — so "a request answered either way gives the runtime the same
   * thing" is a property of there being one constructor rather than of two implementations having
   * been written to match. Where they can still disagree is upstream of this line, in the reading,
   * which is what {@code the_two_paths_answer_a_tool_call_identically} exercises end to end.
   *
   * <p>{@code id} and {@code name} are the wire protocol and this class owns them: nothing
   * downstream can dispatch a call with no name or return a result for a call with no id, and
   * defaulting either to {@code ""} would report the endpoint's fault as the agent asking for a
   * tool that does not exist. So a null of either fails here, naming the pool. {@code arguments} is
   * the opposite and is passed through untouched — see {@link #arguments(JsonNode)}.
   *
   * @param id the call's id, or null if nothing usable carried one
   * @param name the tool's name, or null if nothing usable carried one
   */
  private ToolCall toolCall(String id, String name, String arguments) {
    if (id == null || name == null) {
      throw new LlmTransportException(
          prefix()
              + "chat response contained a tool call with no "
              + (name == null ? "name" : "id"));
    }
    return new ToolCall(id, name, arguments);
  }

  /**
   * A usable string field, or null.
   *
   * <p>Null when the field is absent, JSON null, or not a string — the same discipline as {@link
   * #finishReason}, which explains why a non-string scalar must not be invented into one.
   *
   * <p><b>And null when it is a string with nothing in it</b>, which is a fourth case the two
   * callers actually depend on and an earlier version of this comment did not mention. {@code
   * "id":""} is not an id: the turn loop has to send a tool result back keyed by it, and every call
   * in a batch would carry the same empty key. {@code "name":""} names no tool. Treating either as
   * present would push a fault this method can see down into a runtime that can only report it as
   * the model asking for a tool that does not exist. Pinned by {@code
   * a_tool_call_whose_id_is_empty_is_a_transport_failure}.
   */
  private static String text(JsonNode node) {
    return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }

  /**
   * The arguments as the model emitted them.
   *
   * <p>Three cases, and the middle one is the reason this is not a one-liner. A string is passed
   * through verbatim, which is what OpenAI specifies and what qwen3.5-9b sent in all five clean
   * runs on 2026-08-29. An absent or null field becomes {@code ""} — a zero-parameter call is a
   * thing a model legitimately makes, and {@code "{}"} would be this class claiming the model said
   * something it did not.
   *
   * <p>Anything else is re-rendered as JSON, which is defensive rather than measured: some
   * OpenAI-compatible servers send {@code arguments} as an object instead of a string. It earns its
   * line because the alternative fails silently — {@code JsonNode.asText()} returns the empty
   * string for an object node, so without this the tool would be told its own call had no
   * parameters.
   */
  private static String arguments(JsonNode node) {
    if (node.isTextual()) {
      return node.asText();
    }
    return node.isMissingNode() || node.isNull() ? "" : node.toString();
  }

  /**
   * Why generation stopped, or null — and never a value {@link Completion#finishReason()} does not
   * admit.
   *
   * <p>A stream that has not finished sends {@code "finish_reason": null} on every chunk, and the
   * string {@code "null"} reaching a caller would say an unfinished generation finished, for a
   * reason nobody can act on. {@link Completion#finishReason()} exists precisely so a caller can
   * check for {@code length} before trusting the content; a value outside that vocabulary defeats
   * the check rather than informing it.
   *
   * <p><b>The plan's account of how that happens is wrong for this Jackson, and the correction
   * matters because it changes what this method has to defend against.</b> The plan — and Anchor's
   * {@code !"null".equals(fr)} guard, which is where it comes from — say that {@code asText(null)}
   * returns the string "null" for an explicit JSON null. It does not: {@code NullNode} overrides
   * the one-argument {@code asText(String)} to return the default, so {@code asText(null)} yields a
   * Java null for a JSON null <em>and</em> for a missing node. Verified against 2.17.2, this
   * project's version, rather than assumed. It is the <em>no-argument</em> {@code asText()} that
   * returns "null", because {@code NullNode} renders itself as its own text — so the trap is real,
   * but it is one overload along from where the plan points, and Anchor's string comparison has
   * been dead code for as long as it has been on Jackson 2.x.
   *
   * <p>So what {@code isTextual()} buys over {@code asText(null)} is narrower than the plan claims
   * and is stated exactly: they agree on a JSON null and on a missing field, and differ only on a
   * scalar that is not a string — {@code "finish_reason": 7} becomes "7" under {@code asText(null)}
   * and nothing here. That is the whole of the difference, and {@code
   * a_finish_reason_that_is_not_a_string_is_not_a_reason} is the only test that can tell the two
   * apart.
   */
  private static String finishReason(JsonNode choice) {
    JsonNode node = choice.path("finish_reason");
    return node.isTextual() ? node.asText() : null;
  }

  /**
   * {@inheritDoc}
   *
   * <h2>Six, and each was added the day something confirmed it</h2>
   *
   * <p>LM Studio documents its OpenAI-compatible endpoint as accepting <i>"model, top_p, top_k,
   * messages, temperature, max_tokens, stream, stop, presence_penalty, frequency_penalty,
   * logit_bias, repeat_penalty, seed"</i>, so the four written by {@code chatBody} are the four
   * that list names. {@code top_k} is a vendor extension and not OpenAI's — a hosted OpenAI
   * endpoint has no such field — which is exactly why it is declared here rather than assumed: this
   * claim is true of the backend this project runs against, and a different {@code /v1} provider
   * behind the same transport would need this method to say something else.
   *
   * <p><b>This paragraph used to say {@code reasoning_effort} was absent because nothing here had
   * verified it</b>, and that the fix would be one enum constant on the day somebody did. That is
   * what happened, twice: {@code reasoning_effort} on 2026-09-07 against llama.cpp b10835, and
   * {@code response_format} the same day against both nodes. The rule the old paragraph stated is
   * the part worth keeping — an unverified field written onto the wire is a field that may be
   * ignored with nothing saying so, and a declaration is a claim about a running endpoint rather
   * than about a document.
   *
   * <p>{@code response_format} carries {@link io.aeyer.plowshare.server.llm.dispatch.JsonSchema},
   * which is a nested document rather than a scalar. That changes nothing about this contract: the
   * question is whether the endpoint honours the field, and both of them were watched doing it.
   */
  @Override
  public Set<Sampling.Parameter> carries(String model, Sampling sampling) {
    if (props.getRequestStyle() == PoolProperties.RequestStyle.CLOUD) {
      var capabilities = props.capabilitiesFor(model);
      // Validate before the dispatcher can omit an undeclared effort from carried sampling.
      capabilities.require(sampling, false);
      return capabilities.carries(sampling);
    }
    return carries();
  }

  @Override
  public Set<Sampling.Parameter> carries() {
    if (props.getRequestStyle() == PoolProperties.RequestStyle.CLOUD)
      return props.getDefaultCapabilities().carries(Sampling.NONE);
    return EnumSet.of(
        Sampling.Parameter.TEMPERATURE,
        Sampling.Parameter.TOP_P,
        Sampling.Parameter.TOP_K,
        Sampling.Parameter.MAX_TOKENS,
        // Added 2026-09-07, on the condition the old test named: "the
        // day somebody confirms the spelling against a live endpoint".
        // Confirmed against llama.cpp b10835 serving GLM-4.7-Flash --
        // `reasoning_effort: none` returned in 1.3s with no reasoning
        // where every other value ran 17s and truncated. A field the
        // endpoint ignored could not have produced that difference.
        Sampling.Parameter.REASONING_EFFORT,
        // Confirmed the same day against BOTH nodes, which is a stronger
        // basis than the one above had: LM Studio and llama.cpp each
        // returned a document matching a schema they were given, and
        // each escaped a backslash inside a string correctly. An
        // endpoint ignoring the field could not have done either.
        Sampling.Parameter.RESPONSE_FORMAT);
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input) {
    return embed(wireModel, input, InferenceObserver.NONE);
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input, InferenceObserver observer) {
    // One read of the key for the whole call, threaded through to the header
    // that sends it and to the guard that has to recognise it coming back.
    //
    // hasApiKey() followed by getApiKey() was two reads of a non-volatile
    // field on a mutable Spring bean, in a class documented safe for
    // concurrent use — the same argument url(...) makes for re-reading
    // base-url, applied to the one field where being wrong matters most.
    // They disagree two ways and both are bad: cleared between the reads,
    // the guard is handed an empty needle; rotated between them, the guard
    // looks for the new key while this request's header carried the old one,
    // so the old key is the one quoted back and the one not recognised.
    String apiKey = apiKeySnapshot();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", wireModel);
    body.put("input", input);

    return executeWithRetry(
        embeddingHttp,
        request(url("/embeddings"), body, apiKey),
        "embedding",
        apiKey,
        observer,
        (response, attempt) -> {
          JsonNode root = read(response);
          attempt.usage(usage(root));
          attempt.response(null, providerRequestId(root.path("id").asText(null), apiKey));
          return parseEmbeddings(root, input.size());
        });
  }

  private Embeddings parseEmbeddings(JsonNode root, int inputSize) {
    JsonNode data = root.path("data");
    if (!data.isArray() || data.isEmpty()) {
      throw new LlmTransportException(prefix() + "embedding response had no data array");
    }
    List<float[]> vectors = new ArrayList<>(data.size());
    for (JsonNode item : data) {
      JsonNode vector = item.path("embedding");
      if (!vector.isArray() || vector.isEmpty()) {
        throw new LlmTransportException(prefix() + "embedding response had an empty vector");
      }
      float[] values = new float[vector.size()];
      for (int i = 0; i < vector.size(); i++) {
        values[i] = (float) vector.get(i).asDouble();
      }
      vectors.add(values);
    }
    if (vectors.size() != inputSize) {
      // The response is positional — nothing in it names the input it came
      // from — so a short array cannot be repaired, only detected. Storing
      // it would attach every vector to the wrong text, silently and
      // permanently. DispatchingEmbeddingClient checks this again on the
      // way out; that is belt-and-braces at a boundary where the mistake
      // leaves no trace, and neither half is redundant.
      throw new LlmTransportException(
          prefix() + "asked for " + inputSize + " embeddings and got " + vectors.size());
    }
    return new Embeddings(vectors, usage(root));
  }

  /**
   * An authenticated {@code GET} of a path beside this pool's {@code /v1} root, parsed as JSON.
   *
   * <p><b>This is here rather than in the provider that wants it, and the reason is a containment
   * invariant.</b> {@code InvariantsTest} names the three files in {@code main} that may hold an
   * {@code OkHttpClient}, of which this is the pool's one. A vendor provider that opened its own
   * would be a fourth place configuring timeouts, retries and a connection pool for the same box —
   * so the vendor knows <em>which</em> URL and this class knows <em>how</em> to call it: the key,
   * the header, the retry policy, and every guard on what the endpoint says back.
   *
   * <p><b>Reusing {@link #executeWithRetry} is the load-bearing part, not a convenience.</b>
   * Measured against the reference box on 2026-08-31: {@code GET /api/v0/models} with a token it
   * does not like answers <b>401</b> with {@code "Malformed LM Studio API token provided: "} and
   * the offered token's first ten characters. A masked prefix defeats {@link #quotesTheKey}
   * outright — there is nothing byte-identical left to match — and what drops it is {@link
   * #detail(int, String, String)}'s status branch, which withholds a 401 or 403 body whole. A probe
   * that built its own call and its own error message would have to get that right a second time,
   * and this endpoint is the one that actually does the quoting.
   *
   * <p><b>{@code path} is resolved against the host root, not appended to the base URL.</b> {@code
   * /api/v0} is a sibling of {@code /v1} on the box that serves it, so a pool based at {@code
   * http://host:1234/v1} is probed at {@code http://host:1234/api/v0/models}. A deployment that
   * puts {@code /v1} behind a gateway path — {@code https://gw/proxy/v1} — will therefore be probed
   * at {@code https://gw/api/v0/models}, where the vendor endpoint almost certainly is not. That is
   * a failed probe, which is a state the design already handles: the length stays unknown and the
   * pool stays usable.
   *
   * <p>Sent on {@code embeddingHttp} rather than on a fourth client of its own. Its read timeout is
   * the shortest of the three, and a metadata read is the shape an embedding call is — a short
   * request with no generation behind it — where a chat timeout is patience for a model that is
   * thinking. A fourth client would mean a fourth wait an operator cannot see and cannot set, for a
   * call whose answer is optional by design.
   *
   * <p>Retried on the same terms as every other call here, which for an unreachable host means
   * {@code retry-max-attempts} connect budgets before it gives up. The caller is expected to cache
   * the outcome rather than pay that per request — see {@code LmStudio}, which does.
   *
   * @param path an absolute path on the host, beginning with {@code /}
   */
  public JsonNode metadata(String path) {
    String apiKey = apiKeySnapshot();
    Request request = authorized(beside(path), apiKey).get().build();
    return read(executeWithRetry(embeddingHttp, request, "metadata", apiKey));
  }

  /**
   * A path on the same host as the configured base URL, with the base URL's own path replaced.
   *
   * <p>The query and fragment are cleared rather than carried over. {@code LlmConfig} refuses a
   * base URL with either at boot, on the grounds that they silently swallow an appended path — but
   * {@link PoolProperties} is a mutable bean and this class re-reads it per call for exactly that
   * reason, so the boot check is not a guarantee here.
   */
  private HttpUrl beside(String path) {
    // Read once and used twice, on url(String)'s reasoning: two reads of a
    // mutable field could report a failure to parse one value while quoting
    // another.
    String baseUrl = props.getBaseUrl();
    HttpUrl parsed = HttpUrl.parse(baseUrl);
    if (parsed == null) {
      throw new LlmTransportException(
          "pool '"
              + props.getName()
              + "': base-url is not a URL this client can call: '"
              + PoolProperties.withoutUserInfo(baseUrl)
              + "'. It needs a scheme — set"
              + " plowshare.llm.pools[...].base-url to something like"
              + " http://localhost:1234/v1");
    }
    return parsed.newBuilder().encodedPath(path).query(null).fragment(null).build();
  }

  /**
   * The configured endpoint, or an {@link LlmTransportException} naming the setting that is wrong.
   *
   * <p>{@code Request.Builder.url(String)} throws {@code IllegalArgumentException} on anything it
   * cannot parse, and a base URL with no scheme — {@code LLM_BASE_URL=localhost:1234/v1}, one
   * missing {@code http://} — is exactly that. That exception is the wrong currency twice over.
   * {@code DispatchingEmbeddingClient} turns an {@link LlmTransportException} into the {@code
   * EmbeddingException} that {@code Archive.embed} catches narrowly, and the agent is told a side
   * service is down and the archive is intact — a 503. An {@code IllegalArgumentException} instead
   * escapes that narrow catch, destroying the memory on the way, and reaches {@code
   * ApiExceptionHandler} unclassified as a 500 saying the server is broken. Both readings are wrong
   * about what happened; only one of them also loses a write.
   *
   * <p>Not checked once in the constructor: {@link PoolProperties} is a mutable bean and this class
   * must be safe to call from several threads at once, so the check belongs where the value is
   * read.
   */
  private HttpUrl url(String path) {
    // Read once and used twice. Two reads of this mutable bean's field could
    // report a failure to parse one value while quoting another, which sends
    // the operator to look at a setting that is fine.
    String baseUrl = props.getBaseUrl();
    HttpUrl parsed = HttpUrl.parse((baseUrl == null ? "" : baseUrl.replaceAll("/+$", "")) + path);
    if (parsed == null) {
      throw new LlmTransportException(
          "pool '"
              + props.getName()
              + "': base-url is not a URL this client can call: '"
              + PoolProperties.withoutUserInfo(baseUrl)
              + "'. It needs a scheme — set"
              + " plowshare.llm.pools[...].base-url to something like"
              + " http://localhost:1234/v1");
    }
    return parsed;
  }

  /** The POST, over the shared {@link #authorized} builder. */
  private Request request(HttpUrl url, Object body, String apiKey) {
    return authorized(url, apiKey).post(RequestBody.create(toJson(body), JSON)).build();
  }

  /**
   * A request to {@code url} carrying this pool's credential, with a Bearer header only when there
   * is one: an unauthenticated local box must not be handed an empty {@code Authorization} header
   * to reject.
   *
   * <p>Split out of {@link #request(HttpUrl, Object, String)} so that {@link #metadata(String)}'s
   * {@code GET} authenticates through the same code rather than through a second copy of it —
   * including the translation below, which is the only thing standing between an operator's key
   * file with a trailing newline and a 500 blamed on the model.
   */
  private Request.Builder authorized(HttpUrl url, String apiKey) {
    Request.Builder builder = new Request.Builder().url(url);
    if (!apiKey.isBlank()) {
      try {
        if (props.getApiAuth() == PoolProperties.ApiAuth.AZURE_API_KEY)
          builder.header("api-key", apiKey);
        else builder.header("Authorization", "Bearer " + apiKey);
      } catch (IllegalArgumentException illegalHeader) {
        // The same failure url(String) exists to prevent, one field over
        // in the same method, and it was the one call here left
        // untranslated. OkHttp validates header values and throws
        // IllegalArgumentException for any character outside the
        // permitted range — measured on 4.12, a trailing newline gives
        // "Unexpected char 0x0a at 22 in Authorization value".
        //
        // A trailing newline is not exotic: it is the ordinary shape of
        // LM_STUDIO_API_KEY=$(cat keyfile) and of a Docker secret read
        // from a file. Untranslated it escapes DispatchingEmbeddingClient's
        // narrow LlmException catch and Archive.embed's narrower
        // EmbeddingException one, and reaches the agent as a 500 "the
        // server is broken" instead of a 503 "a side service is down" —
        // telling a model its own proposal was at fault for an
        // operator's key file.
        //
        // The cause is deliberately dropped rather than attached.
        // OkHttp suppresses the value itself for sensitive header names,
        // so the key does not reach the text — but the message carries
        // the offending index, and "at 22" for "Bearer " plus the key is
        // the key's exact length. The slice's one absolute rule is not
        // the value, not a prefix, and not a length, so this keeps the
        // fault and discards the arithmetic.
        throw new LlmTransportException(
            prefix()
                + "the configured api-key cannot be sent as an HTTP header: it contains a"
                + " character no header value may carry. The usual cause is a trailing"
                + " newline from reading the key out of a file — set"
                + " plowshare.llm.pools[...].api-key to the key alone");
      }
    }
    return builder;
  }

  /**
   * The pool's key as one immutable value for the duration of a call.
   *
   * <p>Never null, matching {@link PoolProperties#setApiKey}'s own coalescing, so every caller can
   * treat blank as "no key" without a second check. The null branch is unreachable through that
   * setter today and is kept as a defence against this class's assumption about another class's
   * field, not against a state the bean can currently be in.
   *
   * <p>No test covers the race this exists to close: it needs a write to the bean interleaved
   * between two reads inside one call, which is not something a deterministic test can stage
   * without instrumenting the property object itself. The argument for it is the same one {@link
   * #url(String)} makes and has to stand on being read.
   */
  private String apiKeySnapshot() {
    String key = props.getApiKey();
    return key == null ? "" : key;
  }

  /**
   * Run the request, retrying connection-level failures only.
   *
   * <p>An HTTP status error is returned prose from a server that understood the request and said
   * no; retrying it just spends the caller's budget twice — and the budget is the only bound there
   * is, since {@code LlmPool} waits on the result with no deadline of its own. A dropped socket is
   * the endpoint restarting, which a second attempt genuinely fixes.
   *
   * <p>Every message starts with the pool name, because by the time one of these is read there may
   * be several hosts and the reader's first question is which of them it was.
   */
  private String executeWithRetry(OkHttpClient http, Request request, String label, String apiKey) {
    return executeWithRetry(
        http, request, label, apiKey, InferenceObserver.NONE, (body, attempt) -> body);
  }

  private <T> T executeWithRetry(
      OkHttpClient client,
      Request original,
      String label,
      String apiKey,
      InferenceObserver observer,
      java.util.function.BiFunction<String, InferenceObserver.Attempt, T> decode) {
    return executeWithRetry(client, original, label, apiKey, observer, decode, null);
  }

  private <T> T executeWithRetry(
      OkHttpClient client,
      Request original,
      String label,
      String apiKey,
      InferenceObserver observer,
      java.util.function.BiFunction<String, InferenceObserver.Attempt, T> decode,
      Duration timeout) {
    long deadline = timeout == null ? 0 : System.nanoTime() + timeout.toNanos();
    OkHttpClient http = observedHttp(client, observer);
    Request request = observedRequest(original, observer);
    int maxAttempts = Math.max(1, props.getRetryMaxAttempts());
    long backoff = Math.max(0L, props.getRetryInitialBackoff().toMillis());
    IOException lastFailure = null;

    for (int number = 1; number <= maxAttempts; number++) {
      InferenceObserver.Attempt attempt = observer.attempt();
      okhttp3.Call call = http.newCall(request);
      if (timeout != null) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          LlmTransportException expired =
              new LlmTransportException(
                  prefix() + label + " prompt exceeded its timeout of " + timeout);
          attempt.failed(expired);
          throw expired;
        }
        call.timeout().timeout(remaining, TimeUnit.NANOSECONDS);
      }
      try (Response response = call.execute()) {
        attempt.response(
            response.code(),
            providerRequestId(
                response.header("x-request-id", response.header("apim-request-id")), apiKey));
        if (!response.isSuccessful()) {
          // The status is examined before the body is touched, and the
          // body is then read with its own IOException swallowed.
          // Both halves are needed for the claim above to be true: the
          // previous order called string() first, so a refusal whose
          // body was truncated mid-read raised an IOException that the
          // catch below could not tell from a dropped socket, and the
          // refusal was retried after all. Harmless for an idempotent
          // POST, but the comment asserted otherwise, and a comment
          // that is wrong about control flow is worse than none.
          throw new LlmTransportException(
              prefix()
                  + label
                  + " request failed: HTTP "
                  + response.code()
                  + " "
                  + detail(response.code(), refusalBody(response), apiKey));
        }
        ResponseBody responseBody = response.body();
        T result = decode.apply(responseBody == null ? "" : responseBody.string(), attempt);
        attempt.finished(
            result instanceof Completion completion
                    && "content_filter".equals(completion.finishReason())
                ? CallLifecycle.REFUSED
                : CallLifecycle.SUCCEEDED);
        return result;
      } catch (IOException e) {
        attempt.failed(e);
        lastFailure = e;
        // withheldIfItQuotesTheKey, on streamDetail's reasoning, which
        // was never back-ported to this path when Task 6 tightened that
        // one. A server controls text inside the exceptions a client
        // library raises — okhttp's "unexpected end of stream" and
        // "Unexpected status line: ..." both carry bytes the peer chose
        // — so an endpoint that echoes an Authorization header into a
        // malformed status line puts the key into this log line and into
        // the throw below. Narrow, and exactly as narrow as the stream
        // case that is already guarded.
        log.warn(
            "pool '{}' {} request attempt {}/{} failed: {}",
            props.getName(),
            label,
            number,
            maxAttempts,
            withheldIfItQuotesTheKey(e.getMessage(), apiKey));
        if (number < maxAttempts) {
          // A half-broken pooled connection would be handed straight
          // back to the retry, which would then fail the same way.
          //
          // No test distinguishes this line from its absence, and that
          // is recorded here so the next reader does not mistake it
          // for a missing test they can supply cheaply. The retry
          // tests reach this path through DISCONNECT_AT_START, where
          // no connection was ever pooled for an eviction to matter,
          // and a test that did pool one and then break it would be
          // measuring OkHttp's own isHealthy() check and the route
          // recovery that retryOnConnectionFailure(true) now enables
          // — both of which cover the same case from underneath, and
          // neither of which this line is allowed to depend on.
          //
          // What it still buys is the case those two cannot see: a
          // peer that has half-closed a connection in a way that only
          // fails on write. Per the slice's own lesson, survival of a
          // mutant is evidence about the tests and not about which
          // guard carries the guarantee. Remove it knowingly or not at
          // all.
          http.connectionPool().evictAll();
          try {
            if (timeout != null
                && deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(backoff)) {
              throw new LlmTransportException(
                  prefix() + label + " prompt exceeded its timeout of " + timeout, e);
            }
            Thread.sleep(backoff);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmTransportException(
                prefix() + "interrupted during retry backoff", interrupted);
          }
          backoff = backoff > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : backoff * 2;
        }
      } catch (RuntimeException | Error failure) {
        attempt.failed(failure);
        throw failure;
      }
    }
    throw new LlmTransportException(
        prefix()
            + label
            + " request failed after "
            + maxAttempts
            + " attempts: "
            + withheldIfItQuotesTheKey(lastFailure.getMessage(), apiKey),
        lastFailure);
  }

  /**
   * Accounted POSTs cannot be replayed invisibly by connection recovery, redirects, or HTTP
   * follow-ups.
   */
  private static OkHttpClient observedHttp(OkHttpClient http, InferenceObserver observer) {
    return !observer.enabled()
        ? http
        : http.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
  }

  private static Request observedRequest(Request request, InferenceObserver observer) {
    if (!observer.enabled() || request.body() == null) {
      return request;
    }
    RequestBody delegate = request.body();
    // OkHttp checks isOneShot before its 503/421 follow-ups too. Explicit retries below
    // intentionally create another Call over these buffered bytes and another durable attempt.
    RequestBody body =
        new RequestBody() {
          @Override
          public MediaType contentType() {
            return delegate.contentType();
          }

          @Override
          public long contentLength() throws IOException {
            return delegate.contentLength();
          }

          @Override
          public boolean isOneShot() {
            return true;
          }

          @Override
          public void writeTo(okio.BufferedSink sink) throws IOException {
            delegate.writeTo(sink);
          }
        };
    return request.newBuilder().method(request.method(), body).build();
  }

  private String providerRequestId(String value, String apiKey) {
    return value == null || (!apiKey.isBlank() && quotesTheKey(value, apiKey)) ? null : value;
  }

  /**
   * As much of a refusal body as is safe to carry, and never an exception.
   *
   * <p>Bounded, because {@code string()} buffers whatever arrives: an endpoint behind a
   * misconfigured proxy answers a POST with an HTML error page, and this text is on its way into an
   * exception message, a log line, and a 503 response body. A status code and four kilobytes of
   * context is the useful part of any of them.
   *
   * <p>Silent on failure, because by this point the status is already in hand and it is the fact
   * that matters. A body that cannot be read must not be allowed to turn a refusal into a retry.
   */
  private static String refusalBody(Response response) {
    try {
      return response.peekBody(MAX_REFUSAL_BODY_BYTES).string();
    } catch (IOException e) {
      return "";
    }
  }

  /**
   * The endpoint's own words about a refusal — unless they might contain the credential, in which
   * case they are dropped whole.
   *
   * <p>A refusal body can quote the credential that was presented: gateways in front of an
   * OpenAI-compatible endpoint do echo the offered token back in their error prose. <b>Where this
   * text is going is the reason the bar is this high.</b> It becomes an {@code
   * LlmTransportException} message, which {@code DispatchingEmbeddingClient} turns into an {@code
   * EmbeddingException}, which {@code ApiExceptionHandler} interpolates into the body of a <b>503
   * response</b> — egress to whoever called the API, not a log file with an access list. {@link
   * PoolProperties#apiKey} says the key is never echoed back in any form, and "in any form" has to
   * include the form the endpoint chose.
   *
   * <p><b>Withhold rather than redact, and that is the correction this method has been through.</b>
   * It used to call {@code text.replace(key, "[redacted]")} and pass the rest along. {@code
   * String.replace} matches bytes, so that removed exactly one rendering of the key — the
   * byte-identical one — and silently passed every other: case-changed, percent-encoded, base64,
   * split across a line break or an HTML tag, or JSON-escaped if the key contains a character JSON
   * escapes. Those are not fragments getting through. They are <b>whole keys</b> getting through,
   * into a 503 body. Detection can afford to be broader than redaction ever could, because a false
   * positive costs a withheld sentence while a false negative costs the credential.
   *
   * <p>So: two mechanisms, neither sufficient alone.
   *
   * <p>The first is a status list. 401 and 403 are the statuses that are <em>definitionally</em>
   * about credentials, so their bodies are dropped whether or not anything matches — which is what
   * covers a gateway quoting a truncated key ({@code sk-abc...}) that no matching could recognise.
   * Nothing is lost: the fix for a 401 is to look at that pool's key, and nothing the server says
   * changes that.
   *
   * <p>407 was on this list and has been taken off, which is worth recording so it is not added
   * back on the same reasoning. A proxy demanding its own authentication looks like the same event
   * one hop earlier, but OkHttp never lets a 407 reach this method on a connection that is not
   * proxied: {@code RetryAndFollowUpInterceptor} turns it into {@code ProtocolException: Received
   * HTTP_PROXY_AUTH (407) code while not using proxy}, verified against 4.12 rather than assumed.
   * This transport configures no proxy, so the branch was unreachable in every client it builds —
   * and because that ProtocolException is an {@code IOException}, a 407 is retried by the loop
   * above as though it were a dropped socket. A guard that cannot run is worse than no guard,
   * because it reads as coverage.
   *
   * <p>The second is {@link #quotesTheKey}, for every other status, because a status list is the
   * wrong shape on its own: nothing obliges a gateway to answer a bad key with 401, and several
   * answer 400. A body that survives it keeps its prose intact — "no such model" is the single most
   * useful thing a local endpoint ever says, and it is what tells an operator that a pool's {@code
   * models} list disagrees with what is loaded.
   *
   * <p><b>The residual, stated to match the code and not to flatter it.</b> What gets through is
   * any rendering of the key that is not byte-identical to the configured value and is not one of
   * the three {@link #renderings(String)} produces, under a status that is not 401 or 403 — base64,
   * a key broken across a line break or an HTML tag, a hash. Not a fragment: a whole key, into a
   * 503 body. An earlier version of this paragraph described the residual as partial keys only,
   * which understated it by the entire difference between a fragment and a credential. Closing the
   * rest means withholding every refusal body from every keyed pool, which costs the only
   * diagnostic the endpoint provides for pools that mostly are not local. That trade is deliberate
   * and it is the paragraph to revisit if it ever proves wrong.
   *
   * <p>Note what the plan's own test does not do: {@code a_401_names_the_pool_and_never_the_key}
   * passes whether this method guards anything at all, because MockWebServer is told to say only
   * "unauthorized".
   */
  private String detail(int code, String text, String apiKey) {
    if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
      return "(withheld: an auth failure is the reply most likely to quote the"
          + " credential back)";
    }
    return withheldIfItQuotesTheKey(text, apiKey);
  }

  /**
   * Text this class did not write, dropped whole if it looks like it contains the credential.
   *
   * <p>Split out of {@link #detail(int, String, String)} so that the other thing arriving from
   * outside — the message on a throwable that ended a stream, see {@link #streamDetail} — rests on
   * the same rule rather than on an enumeration of which exceptions are believed harmless. That
   * enumeration existed, and it was wrong about which branch its own examples took.
   *
   * <p>A pool with no key sent nothing that could come back, so there is nothing to withhold and
   * the text passes through untouched. This is the common case — the reference box has no key — and
   * it is also why the old redaction needed this guard for a different reason: {@code replace("",
   * r)} inserts {@code r} between every character, so an unauthenticated pool had its refusals
   * shredded into confetti by the line meant to protect it. Withholding cannot do that, but the
   * early return still earns its place by keeping the common path free of any matching at all.
   */
  private String withheldIfItQuotesTheKey(String text, String apiKey) {
    if (text == null) {
      // An IOException is allowed a null message and some of OkHttp's
      // have one. Guarded here rather than at each call site because
      // quotesTheKey lowercases without checking, so a null would leave
      // this method as a NullPointerException — an unclassified 500
      // raised by the very helper that exists to keep a failure in the
      // right currency.
      return "no reason given";
    }
    if (apiKey.isBlank()) {
      return text;
    }
    return quotesTheKey(text, apiKey)
        ? "(withheld: this pool sent a key and the reply appears to quote it)"
        : text;
  }

  /**
   * Whether {@code text} appears to contain {@code apiKey} in any rendering this class knows how to
   * produce.
   *
   * <p>Case-insensitively, and against each of {@link #renderings(String)}. Matching is
   * deliberately eager: the cost of a false positive is one withheld sentence, and the cost of a
   * false negative is a credential in an HTTP response body, so there is no symmetry to preserve
   * here. A real key has enough entropy that a body contains it only by having been told it.
   *
   * <p>A pathologically short key — {@link PoolProperties#hasApiKey} asks only that it be non-blank
   * — will match a great deal and withhold most refusal bodies. That is the safe direction, and the
   * fix belongs where the key is configured rather than here.
   */
  private boolean quotesTheKey(String text, String apiKey) {
    String haystack = text.toLowerCase(Locale.ROOT);
    for (String rendering : renderings(apiKey)) {
      if (!rendering.isBlank() && haystack.contains(rendering.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The forms a key can plausibly take in someone else's error prose.
   *
   * <p>The key is transformed and the body is searched, rather than the body being decoded and
   * searched once. That direction is not a style choice: it never parses attacker-controlled text,
   * so it cannot throw, cannot be made quadratic, and cannot be tricked by a body that decodes two
   * ways.
   *
   * <p>The percent-encoded form is for a gateway that echoes a URL it built — some endpoints accept
   * the key as a query parameter. The JSON-escaped form matters only when the key contains a
   * character JSON escapes, which the usual alphanumeric key does not; it is here because it is
   * reachable and cheap, unlike the 407 branch above, which was neither.
   */
  private List<String> renderings(String apiKey) {
    List<String> renderings = new ArrayList<>(3);
    renderings.add(apiKey);
    renderings.add(URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
    try {
      String quoted = mapper.writeValueAsString(apiKey);
      renderings.add(quoted.substring(1, quoted.length() - 1));
    } catch (JsonProcessingException e) {
      // Unreachable for a String, and if it ever happens the other two
      // renderings still apply. Not a reason to fail the call.
      log.debug("pool '{}': could not render the key as JSON for matching", props.getName());
    }
    return renderings;
  }

  /**
   * The response as JSON, or a failure that names the pool and quotes nothing.
   *
   * <p>The body is deliberately not interpolated into the message, and the cause is worth a note
   * because it looks like a way round that. A Jackson parse error carries a snippet of what it was
   * parsing — which for a keyed pool is a body that may quote the credential, arriving by a route
   * {@link #detail(int, String, String)} never sees, since this is the 2xx path. Two things make it
   * safe rather than one: {@code getMessage()} on the thrown exception is this class's own text and
   * never the cause's, and Jackson disables {@code StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION} by
   * default from 2.16, so even a stack trace prints "Source: REDACTED". Checked against 2.17.2.
   * Re-enabling that feature, or a handler that walks the cause chain into a response body, would
   * each undo half of it.
   */
  private JsonNode read(String responseBody) {
    try {
      return mapper.readTree(responseBody);
    } catch (JsonProcessingException e) {
      throw new LlmTransportException(prefix() + "response was not JSON", e);
    }
  }

  /**
   * What the call cost, or {@link TokenUsage#UNKNOWN}.
   *
   * <p>Never null, and this is the only place that can promise it. {@link Embeddings} and {@link
   * Completion} both document {@code usage} as non-null but neither enforces it, and {@code
   * LlmDispatcher} hands it straight to the {@code TokenLedger} — so a local server that omits the
   * {@code usage} object entirely, which LM Studio does on some builds, would put a
   * NullPointerException inside whichever ledger implementation is wired up when someone finally
   * wires up billing, months from the change that caused it. Each count stays nullable because a
   * server may report some and not others; only the record itself is guaranteed.
   *
   * <p><b>Read by both chat paths, which is what makes them agree about cost.</b> A blocking
   * response carries {@code usage} at the root and a stream carries it on one late chunk, but the
   * object underneath is the same object and this is the only method that reads it. {@code
   * completion_tokens_details.reasoning_tokens} therefore reaches {@link
   * TokenUsage#reasoningTokens()} from either — the measured shape on 2026-09-02 was {@code
   * {"prompt_tokens":278,"completion_tokens":88,
   * "total_tokens":366,"completion_tokens_details":{"reasoning_tokens":60}}}, and it is the number
   * that makes what thinking costs measurable rather than merely paid.
   *
   * <p>A missing {@code completion_tokens_details} leaves that count null and nothing else changes,
   * so an endpoint that does not break reasoning out is reported exactly as it was before this
   * field existed.
   */
  private static TokenUsage usage(JsonNode root) {
    return TokenUsage.measured(UsageNormalizer.openAi(root.get("usage")));
  }

  private byte[] toJson(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (JsonProcessingException e) {
      throw new LlmTransportException(prefix() + "failed to encode the request body", e);
    }
  }

  /**
   * Which pool this is, on the front of every message. Never the base URL and never anything
   * derived from the key.
   */
  private String prefix() {
    return "pool '" + props.getName() + "': ";
  }

  /**
   * {@inheritDoc}
   *
   * <p>Idempotent without a flag, because both halves already are: {@code ExecutorService.shutdown}
   * on a terminated executor is a no-op and {@code evictAll} on an empty pool closes nothing. The
   * three clients share one connection pool and one dispatcher today, so the loop repeats itself
   * twice — kept anyway, because the day one of them is built independently for a timeout OkHttp
   * cannot express per call, a loop over all three is already the correct code and a single {@code
   * chatHttp.close()} would silently leak the other two.
   *
   * <p><b>What this does not do is prevent further use, and the asymmetry that follows is kept on
   * purpose.</b> Nothing here sets a closed flag, so a synchronous call after close still works:
   * {@code execute()} does not go through the dispatcher's executor, so a shut-down executor is
   * invisible to it, and the connection pool simply opens a fresh connection. An asynchronous call
   * does not — {@link #stream} enqueues onto exactly that executor. So one {@code close()} leaves
   * two post-close behaviours in one class.
   *
   * <p>Task 6 decided that rather than inheriting it, and decided to leave it. The failure mode a
   * closed flag would be protecting against is a stream that hangs on a callback nobody will
   * deliver, and that is not what happens: OkHttp's {@code AsyncCall.executeOn} catches the {@code
   * RejectedExecutionException} itself and delivers {@code InterruptedIOException("executor
   * rejected")} through {@code onFailure}, so the call fails promptly and by this pool's name.
   * Verified against 4.12 rather than assumed, and pinned by {@code
   * a_stream_after_close_fails_rather_than_hanging}. A flag would buy a tidier message for a state
   * {@code LlmPool.close} already makes unreachable — it drains both lanes before calling this — at
   * the price of a second piece of lifecycle state to keep correct under concurrent close.
   *
   * <p>The per-client catch is what makes the "must not throw" half true. {@code LlmPool.close}
   * calls this with no guard of its own, and a throw here would abandon whichever clients had not
   * been reached yet — leaking the connection pool and the dispatcher threads, once per shutdown,
   * with nothing left running to report it.
   */
  @Override
  public void close() {
    promptCounter.clear();
    for (OkHttpClient http : List.of(chatHttp, streamingHttp, embeddingHttp, socketHttp)) {
      try {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
      } catch (RuntimeException e) {
        log.warn("pool '{}': an HTTP client did not shut down cleanly", props.getName(), e);
      }
    }
  }
}
