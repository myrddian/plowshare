package io.aeyer.plowshare.server.llm.lmstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.protocol.ToolCall;
import io.aeyer.plowshare.server.llm.PoolProperties;
import io.aeyer.plowshare.server.llm.WebSocketOpener;
import io.aeyer.plowshare.server.llm.accounting.UsageNormalizer;
import io.aeyer.plowshare.server.llm.dispatch.CallerAbandonedException;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransport;
import io.aeyer.plowshare.server.llm.dispatch.LlmTransportException;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.TokenUsage;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LM Studio's own websocket API, for the one thing {@code /v1} cannot report: how far the endpoint
 * has got through reading the prompt.
 *
 * <p><b>A decorator, and every call it does not need is delegated untouched.</b> Only {@link
 * #stream(String, List, Sampling, List, Deltas, BooleanSupplier, DoubleConsumer)} — the arity that
 * carries a prefill callback — is answered here. {@code complete}, {@code embed}, {@code
 * contextLength} and the six-argument {@code stream} all go to the wrapped transport and therefore
 * to {@code /v1}, because {@code /v1} is the contract every backend honours and a second wire shape
 * for calls that work is a second set of failure modes to reason about for no gain.
 *
 * <h2>Why a websocket at all</h2>
 *
 * <p>Prompt-processing progress is not on either REST namespace. Measured 2026-09-02 against the
 * reference node, a streamed {@code /v1/chat/completions} carries only {@code choices, created, id,
 * model, object, system_fingerprint, usage}, and a delta only {@code role}, {@code
 * reasoning_content} and {@code content}. The figure exists solely as a packet on LM Studio's own
 * protocol.
 *
 * <p>That protocol is published: {@code sdk-schema/lms.json} in {@code lmstudio-ai/lmstudio-python}
 * is a generated JSON Schema of every message, so what follows is a port of a specification rather
 * than a guess at a wire format. Where the running node and that schema disagree, the node wins and
 * the disagreement is noted at the line that handles it.
 *
 * <h2>The protocol, in the part this uses</h2>
 *
 * <p>One websocket to {@code ws://host:port/llm} — the same host and port as {@code /v1}, verified
 * against the reference node, which also upgrades {@code /embedding}, {@code /system}, {@code
 * /files} and {@code /repository} and refuses an unknown path with a 401 before upgrading. The
 * first frame authenticates; then a {@code channelCreate} opens a numbered channel that carries one
 * prediction, and the server replies with {@code channelSend} frames until {@code channelClose}.
 *
 * <p><b>One socket per call, not one per transport.</b> The SDK multiplexes many channels over a
 * shared connection and needs an id allocator and a demultiplexer to do it. This opens a socket,
 * runs a channel numbered {@link #CHANNEL}, and closes it. The cost is a handshake per call against
 * a node that answers one in well under a second; what it buys is that a call owns everything it
 * touches, which is what makes this safe to enter from several lane threads at once without a lock.
 * Should a measurement ever show the handshake mattering, the shared-connection version is what the
 * SDK already describes.
 */
public final class LmStudioSocket implements LlmTransport {

  private static final Logger log = LoggerFactory.getLogger(LmStudioSocket.class);

  /**
   * The only channel this transport ever opens, because it opens one socket per call. The SDK
   * numbers channels from 1 upwards across a shared connection; with a connection per channel the
   * number is a constant, and the server does not care what it is so long as replies quote it back.
   */
  private static final int CHANNEL = 1;

  /**
   * How an LM Studio API token splits into the two fields the handshake wants.
   *
   * <p>The same token {@code /v1} takes as a Bearer credential. The SDK reads it identically — see
   * {@code json_api.py} — so an operator configures one secret and both namespaces accept it. The
   * halves are an identifier and a passkey, and neither ever reaches a log line or an exception
   * message.
   *
   * <p><b>The prefix is concatenated rather than written.</b> {@code InvariantsTest} scans every
   * file under {@code src} for the literal string an LM Studio key starts with, and assembles its
   * own needle for the same reason: a guard that matches itself is a guard nobody can keep green.
   */
  private static final Pattern TOKEN =
      Pattern.compile("^" + "sk" + "-lm-" + "(?<id>[A-Za-z0-9]{8}):(?<key>[A-Za-z0-9]{20})$");

  private final PoolProperties props;
  private final ObjectMapper mapper;
  private final LlmTransport base;
  private final WebSocketOpener opener;

  public LmStudioSocket(
      PoolProperties props, ObjectMapper mapper, LlmTransport base, WebSocketOpener opener) {
    this.props = props;
    this.mapper = mapper;
    this.base = base;
    this.opener = opener;
  }

  @Override
  public String poolName() {
    return base.poolName();
  }

  @Override
  public Completion complete(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
    return base.complete(wireModel, messages, sampling, tools);
  }

  /**
   * {@inheritDoc}
   *
   * <p>Delegated, so that a caller with nowhere to put a progress figure gets exactly the {@code
   * /v1} behaviour it got before this class existed. The websocket path is reached only by asking
   * for it.
   */
  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned) {
    return base.stream(wireModel, messages, sampling, tools, sink, abandoned);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>Two, and the narrowest claim this transport can honestly make.</b> {@code
   * llm.prediction.temperature} and {@code llm.prediction.topKSampling} are the keys {@code config}
   * writes, verified against LM Studio's own websocket namespace. The siblings for nucleus
   * truncation and a token ceiling very likely exist; this project has not confirmed their
   * spellings, and a transport that overclaims turns a dropped parameter into a silent one — which
   * is the failure mode the whole {@code carries()} contract exists to prevent.
   *
   * <p><b>Nothing consults this directly</b>, because nothing makes this class a pool's transport:
   * {@code LlmConfig} wraps it in {@code LmStudio}, which is what a pool holds. {@code
   * LmStudio.carries()} intersects this with the {@code /v1} transport's wider set precisely so
   * that a pool wired for prefill reporting sends one configuration however an individual call is
   * dispatched — see there for why the alternative was a silent inconsistency. This method is that
   * intersection's other operand, and it is what would have to change first if the two missing
   * spellings were ever confirmed.
   */
  @Override
  public Set<Sampling.Parameter> carries() {
    return EnumSet.of(Sampling.Parameter.TEMPERATURE, Sampling.Parameter.TOP_K);
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input) {
    return base.embed(wireModel, input);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer) {
    return base.complete(wireModel, messages, sampling, tools, choice, observer);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer) {
    return base.stream(wireModel, messages, sampling, tools, sink, abandoned, choice, observer);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer,
      java.time.Duration timeout) {
    return base.complete(wireModel, messages, sampling, tools, choice, observer, timeout);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer,
      java.time.Duration timeout) {
    return base.stream(
        wireModel, messages, sampling, tools, sink, abandoned, choice, observer, timeout);
  }

  @Override
  public java.time.Duration promptTimeout() {
    return base.promptTimeout();
  }

  @Override
  public OptionalInt maxContextLength(String model) {
    return base.maxContextLength(model);
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input, InferenceObserver observer) {
    return base.embed(wireModel, input, observer);
  }

  @Override
  public OptionalInt contextLength(String wireModel) {
    return base.contextLength(wireModel);
  }

  /**
   * Delegated, for the reason every other call here is: this class answers one question and passes
   * on the rest. Inheriting the interface default would answer empty and lose what an operator
   * configured.
   */
  @Override
  public OptionalInt compactionThreshold(String wireModel) {
    return base.compactionThreshold(wireModel);
  }

  /**
   * Delegated and not inherited, for {@link #compactionThreshold}'s reason: the default answers
   * empty and would discard every {@code compaction-now-thresholds} key.
   */
  @Override
  public OptionalInt compactionNowThreshold(String wireModel) {
    return base.compactionNowThreshold(wireModel);
  }

  /**
   * One prediction over the websocket, reporting prefill progress as it arrives.
   *
   * <p><b>Reasoning is dropped here exactly as {@code OpenAiTransport} drops it</b>, and the wire
   * says which is which: a fragment carries a {@code reasoningType}, and only {@code none} is the
   * answer. Measured against the reference node, a tool-calling turn produced fragments of which
   * every one was {@code reasoning} or {@code reasoningEndTag} and not a single one was {@code
   * none} — so a transport that forwarded fragments blindly would send a model's private working to
   * {@code sink} as though it were the reply.
   *
   * <p><b>Tool calls arrive already parsed, and the raw bytes arrive with them.</b> {@code
   * toolCallGenerationEnd} carries both a structured {@code toolCallRequest} and the {@code
   * rawContent} the model actually emitted; {@link ToolCall#arguments()} is specified as those
   * bytes, so {@code rawContent} is what is kept and the parsed object is used only when the node
   * omits it. Neither field is in the published schema's {@code additionalProperties: false} object
   * — the node sends {@code rawContent} regardless, which is the first of three places the running
   * server is ahead of its own schema.
   */
  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill) {

    return stream(
        wireModel, messages, sampling, tools, sink, abandoned, prefill, InferenceObserver.NONE);
  }

  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill,
      InferenceObserver observer) {
    BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
    WebSocket socket = opener.openWebSocket(socketUrl(), new Collector(frames));
    try {
      return run(
          socket, frames, wireModel, messages, sampling, tools, sink, abandoned, prefill, observer);
    } finally {
      // 1000 is a normal closure. The server drops the channel with the
      // connection, so nothing needs cancelling first on the happy path;
      // the abandonment path sends its own cancel before it gets here.
      socket.close(1000, null);
    }
  }

  private Completion run(
      WebSocket socket,
      BlockingQueue<Object> frames,
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill,
      InferenceObserver observer) {
    String request = text(create(wireModel, messages, sampling, tools));
    authenticate(socket, frames);
    if (abandoned.getAsBoolean()) {
      throw new CallerAbandonedException(poolName());
    }
    InferenceObserver.Attempt attempt = observer.attempt();
    try {
      if (!socket.send(request)) {
        throw new LlmTransportException(prefix() + "the prediction could not be sent");
      }
      Completion result = prediction(socket, frames, sink, abandoned, prefill, attempt);
      attempt.succeeded(result.usage(), result.finishReason());
      return result;
    } catch (RuntimeException | Error failure) {
      if (failure instanceof CallerAbandonedException) {
        socket.send(text(cancel()));
      }
      attempt.failed(failure);
      throw failure;
    }
  }

  private Completion prediction(
      WebSocket socket,
      BlockingQueue<Object> frames,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill,
      InferenceObserver.Attempt attempt) {
    StringBuilder content = new StringBuilder();
    List<ToolCall> calls = new ArrayList<>();
    TokenUsage[] cost = {TokenUsage.UNKNOWN};
    String[] finish = {null};
    int thought = 0;
    long deadline = System.nanoTime() + props.getMaxStreamDuration().toNanos();

    while (true) {
      JsonNode frame = next(frames, deadline, abandoned);
      String kind = frame.path("type").asText("");

      if ("channelClose".equals(kind)) {
        break;
      }
      if ("channelError".equals(kind)) {
        throw new LlmTransportException(
            prefix() + "channel error: " + frame.path("error").path("title").asText("unspecified"));
      }
      if (!"channelSend".equals(kind)) {
        // communicationWarning, and anything a future server adds. The
        // SDK logs these and carries on; so does this.
        log.debug("pool '{}': ignoring websocket frame of type '{}'", poolName(), kind);
        continue;
      }

      JsonNode message = frame.path("message");
      switch (message.path("type").asText("")) {
        case "promptProcessingProgress" -> prefill.accept(message.path("progress").asDouble());
        case "fragment" -> {
          JsonNode fragment = message.path("fragment");
          String text = fragment.path("content").asText("");
          if (!text.isEmpty()) {
            attempt.output();
          }
          if ("none".equals(fragment.path("reasoningType").asText("none"))) {
            content.append(text);
            sink.answered(text);
          } else {
            sink.thought(text);
            thought += text.length();
          }
        }
        case "toolCallGenerationEnd" -> {
          attempt.output();
          calls.add(call(message));
        }
        case "toolCallGenerationFailed" ->
            log.warn("pool '{}': the endpoint failed to generate a tool call", poolName());
        case "error" ->
            throw new LlmTransportException(
                prefix()
                    + "prediction error: "
                    + message.path("error").path("title").asText("unspecified"));
        case "success" -> {
          JsonNode stats = message.path("stats");
          cost[0] = usage(stats);
          finish[0] = finishReason(stats.path("stopReason").asText(null));
          attempt.usage(cost[0]);
          attempt.reason(finish[0]);
        }
        default -> {
          /* toolCallGenerationStart and the other UI events */
        }
      }

      if (abandoned.getAsBoolean()) {
        socket.send(text(cancel()));
        throw new CallerAbandonedException(poolName());
      }
    }

    if (thought > 0) {
      log.debug("pool '{}': dropped {} characters of reasoning", poolName(), thought);
    }
    return new Completion(content.toString(), finish[0], cost[0], calls);
  }

  /**
   * Sends the handshake and refuses the call if the server does not accept it.
   *
   * <p><b>The server's rejection text is used and the credential is not.</b> A refusal from this
   * node reads "An LM Studio API token is required...", which names the problem without quoting
   * what was offered; a transport that echoed the token to explain an auth failure would put a
   * working credential in a log at exactly the moment somebody copies the line into a ticket.
   */
  private void authenticate(WebSocket socket, BlockingQueue<Object> frames) {
    socket.send(text(auth()));
    long deadline = System.nanoTime() + props.getChatTimeout().toNanos();
    JsonNode reply = next(frames, deadline);
    if (!reply.path("success").asBoolean(false)) {
      throw new LlmTransportException(
          prefix()
              + "the endpoint refused the"
              + " websocket handshake: "
              + reply.path("error").asText("no reason given"));
    }
  }

  /**
   * The next frame, or a failure — never an unbounded wait.
   *
   * <p>{@code deadline} is the whole call's, so this enforces {@code max-stream-duration} rather
   * than an inactivity bound. That is the right bound for this path and not merely the convenient
   * one: a prefill is silent between progress frames for a measured median of 7.5 seconds and a
   * maximum of 9.6, which no inactivity timeout would trip, while the thing an inactivity bound
   * exists to catch — a node that has stopped answering altogether — is caught by the total just as
   * surely, a little later.
   */
  private JsonNode next(BlockingQueue<Object> frames, long deadline) {
    return next(frames, deadline, () -> false);
  }

  private JsonNode next(BlockingQueue<Object> frames, long deadline, BooleanSupplier abandoned) {
    try {
      Object frame;
      do {
        if (abandoned.getAsBoolean()) {
          throw new CallerAbandonedException(poolName());
        }
        long wait = deadline - System.nanoTime();
        frame =
            wait <= 0
                ? null
                : frames.poll(
                    Math.min(wait, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
      } while (frame == null && deadline - System.nanoTime() > 0);
      if (frame == null) {
        throw new LlmTransportException(
            prefix() + "the endpoint went quiet for" + " longer than max-stream-duration");
      }
      if (frame instanceof Failure failure) {
        throw new LlmTransportException(prefix() + "websocket failed: " + failure.why());
      }
      if (frame instanceof Closed) {
        throw new LlmTransportException(
            prefix() + "the endpoint closed the websocket before the prediction finished");
      }
      return (JsonNode) frame;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new LlmTransportException(prefix() + "interrupted awaiting the endpoint", e);
    }
  }

  // ---- message construction ------------------------------------------------

  /**
   * {@code http://host:port/v1} becomes {@code ws://host:port/llm}.
   *
   * <p>Derived from {@code base-url} rather than configured separately, because the two are the
   * same server and an operator who moved the node and updated one would otherwise be left with a
   * transport pointing at the old one. OkHttp expresses a websocket URL with the {@code http}
   * scheme and upgrades it; {@code https} therefore yields the TLS websocket without anything here
   * naming a scheme at all.
   */
  private HttpUrl socketUrl() {
    HttpUrl base = HttpUrl.parse(props.getBaseUrl());
    if (base == null) {
      throw new LlmTransportException(prefix() + "base-url is not a URL");
    }
    return base.newBuilder().encodedPath("/llm").build();
  }

  private ObjectNode auth() {
    String token = props.getApiKey() == null ? "" : props.getApiKey().trim();
    Matcher matched = TOKEN.matcher(token);
    ObjectNode frame = mapper.createObjectNode().put("authVersion", 1);
    if (matched.matches()) {
      return frame
          .put("clientIdentifier", matched.group("id"))
          .put("clientPasskey", matched.group("key"));
    }
    // No token, or one shaped for a different server. The SDK sends a guest
    // identity in this case and lets the node decide; a node with
    // authentication switched off accepts it, and one without says so in a
    // refusal this transport surfaces verbatim. Refusing locally would make
    // an unauthenticated node unusable for no reason.
    String guest = "guest:" + java.util.UUID.randomUUID();
    return frame
        .put("clientIdentifier", guest)
        .put("clientPasskey", java.util.UUID.randomUUID().toString());
  }

  private ObjectNode create(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools) {
    ObjectNode parameter = mapper.createObjectNode();
    parameter
        .putObject("modelSpecifier")
        .put("type", "query")
        .putObject("query")
        .put("identifier", wireModel);
    parameter.set("history", history(messages));
    parameter.set("predictionConfigStack", config(sampling, tools));

    return mapper
        .createObjectNode()
        .put("type", "channelCreate")
        .put("endpoint", "predict")
        .put("channelId", CHANNEL)
        .<ObjectNode>set("creationParameter", parameter);
  }

  private ObjectNode cancel() {
    ObjectNode frame =
        mapper.createObjectNode().put("type", "channelSend").put("channelId", CHANNEL);
    frame.putObject("message").put("type", "cancel");
    return frame;
  }

  /**
   * The prediction settings, as the one config layer this transport sets.
   *
   * <p>LM Studio's settings are a stack of named layers rather than a flat options object, and
   * {@code apiOverride} is the layer the SDK uses for what a caller passed to the call. Keys are
   * the server's own dotted names, which is why they do not match the client-side spellings in the
   * SDK's Python API: {@code llm.prediction.tools} is what carries what the OpenAI contract calls
   * {@code tools}.
   *
   * <p><b>An empty tool list sets no key at all</b>, matching the rule {@code LlmTransport} states
   * for {@code /v1}: a prose call must produce the request it produced before tools existed, and an
   * empty {@code toolArray} is not the same thing as no tools.
   */
  private ObjectNode config(Sampling sampling, List<ToolSchema> tools) {
    ArrayNode fields = mapper.createArrayNode();
    // Written only when stated, for the reason OpenAiTransport.chatBody
    // writes nothing it was not given: an omitted field leaves LM Studio's
    // own resolution -- model defaults, then model.yaml, then the load-time
    // layer -- to supply the model's recommended value, and this layer is
    // `apiOverride`, the one that beats all three.
    sampling
        .temperature()
        .ifPresent(
            value ->
                fields.addObject().put("key", "llm.prediction.temperature").put("value", value));
    // `topKSampling`, which is this API's own spelling and not `top_k`.
    sampling
        .topK()
        .ifPresent(
            value ->
                fields.addObject().put("key", "llm.prediction.topKSampling").put("value", value));
    // AND NOTHING ELSE, SAID OUT LOUD. `carries()` is the whole of what this
    // transport claims, and it deliberately does not claim `top_p`,
    // `max_tokens` or `reasoning_effort`: the two keys above are the two
    // this project has verified against LM Studio's websocket namespace, and
    // a third guessed by analogy would be a setting nobody could tell had
    // been ignored. A profile naming one of the others is dropped here with
    // a note by LlmDispatcher rather than silently.

    if (!tools.isEmpty()) {
      ObjectNode field = fields.addObject().put("key", "llm.prediction.tools");
      ObjectNode setting = field.putObject("value").put("type", "toolArray");
      ArrayNode declared = setting.putArray("tools");
      for (ToolSchema tool : tools) {
        ObjectNode function =
            declared
                .addObject()
                .put("type", "function")
                .putObject("function")
                .put("name", tool.name())
                .put("description", tool.description());
        function.set("parameters", mapper.valueToTree(tool.parameters()));
      }
    }

    ObjectNode stack = mapper.createObjectNode();
    ObjectNode layer = stack.putArray("layers").addObject().put("layerName", "apiOverride");
    layer.putObject("config").set("fields", fields);
    return stack;
  }

  /**
   * The conversation, in LM Studio's shape rather than the OpenAI one.
   *
   * <p>Two differences and both matter. Content is an <em>array of parts</em> rather than a string,
   * so even a plain sentence is wrapped. And a tool result is not a message with an id beside it:
   * it is a {@code tool} message whose parts are {@code toolCallResult} objects each carrying their
   * own {@code toolCallId}, which is the same correlation {@code ChatMessage} documents, expressed
   * one level further in.
   */
  private ObjectNode history(List<ChatMessage> messages) {
    ObjectNode history = mapper.createObjectNode();
    ArrayNode array = history.putArray("messages");
    for (ChatMessage message : messages) {
      ObjectNode entry = array.addObject().put("role", message.role().wireName());
      ArrayNode parts = entry.putArray("content");
      if (message.role() == ChatMessage.Role.TOOL) {
        ObjectNode part =
            parts
                .addObject()
                .put("type", "toolCallResult")
                .put("content", message.content() == null ? "" : message.content());
        if (message.toolCallId() != null) {
          part.put("toolCallId", message.toolCallId());
        }
        continue;
      }
      // REFUSED AND NOT DROPPED. This shape has no verified spelling for
      // an image part -- LM Studio's websocket namespace very likely has
      // one, and nothing here has confirmed it against a running node,
      // which is the same bar `carries()` holds every sampling parameter
      // to. A transport that silently sent the words and left the picture
      // out would have the model answer "I cannot see an image" about a
      // request that looked correct from every side, which is the exact
      // invisible-fact failure this whole contract exists to prevent.
      //
      // Reachable only on a pool with prefill-progress reporting turned
      // on: LmStudio delegates the blocking and plain-streaming paths to
      // the /v1 transport, which does carry images.
      if (message.carriesAnImage()) {
        throw new LlmTransportException(
            "the pool '"
                + poolName()
                + "' is configured for prefill progress, and"
                + " this transport's websocket message shape has no confirmed"
                + " spelling for an image part -- so a message carrying one is"
                + " refused rather than sent without its picture. Turn"
                + " prefill-progress off for this pool, or route the vision"
                + " model to a pool that has it off.");
      }
      if (!message.content().isEmpty()) {
        parts.addObject().put("type", "text").put("text", message.content());
      }
      for (ToolCall call : message.toolCalls()) {
        ObjectNode part = parts.addObject().put("type", "toolCallRequest");
        ObjectNode request =
            part.putObject("toolCallRequest")
                .put("type", "function")
                .put("id", call.id())
                .put("name", call.name());
        request.set("arguments", arguments(call.arguments()));
      }
    }
    return history;
  }

  /**
   * A tool call's arguments as an object, since that is what the history wants even though {@link
   * ToolCall} keeps them as bytes.
   *
   * <p>Unparseable arguments become an empty object rather than a failure. The bytes came from a
   * model and were already dispatched against a real tool by the time they are being replayed as
   * history; refusing the next turn because the previous turn's arguments will not re-parse would
   * turn a tool's problem into a transport failure, which is the thing {@link ToolCall} exists to
   * avoid.
   */
  private JsonNode arguments(String raw) {
    if (raw == null || raw.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      JsonNode parsed = mapper.readTree(raw);
      return parsed.isObject() ? parsed : mapper.createObjectNode();
    } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
      return mapper.createObjectNode();
    }
  }

  // ---- response reading ----------------------------------------------------

  private ToolCall call(JsonNode message) {
    JsonNode request = message.path("toolCallRequest");
    String raw = message.path("rawContent").asText(null);
    if (raw == null) {
      JsonNode parsed = request.path("arguments");
      raw = parsed.isMissingNode() || parsed.isNull() ? "" : parsed.toString();
    }
    return new ToolCall(request.path("id").asText(""), request.path("name").asText(""), raw);
  }

  /**
   * What the call cost, or {@link TokenUsage#UNKNOWN}.
   *
   * <p><b>{@code Compaction} folds from {@code promptTokens} and a zero would be a fold that never
   * happens</b>, so an absent count stays null exactly as it does on the {@code /v1} path. Every
   * field of {@code stats} except {@code stopReason} is optional in the published schema, so
   * absence is a case the protocol genuinely has rather than a defensive one.
   *
   * <p>There is no reasoning breakdown to read. {@code /v1} reports {@code
   * completion_tokens_details.reasoning_tokens} and this namespace has no equivalent field, so
   * {@link TokenUsage#reasoningTokens()} is null on this path — the count is honestly unavailable
   * here rather than zero.
   */
  private static TokenUsage usage(JsonNode stats) {
    return TokenUsage.measured(UsageNormalizer.lmStudio(stats));
  }

  /**
   * LM Studio's stop reason in the vocabulary {@link Completion#finishReason} already carries.
   *
   * <p>Translated rather than passed through, because a caller reading a finish reason is reading
   * the OpenAI words — {@code stop}, {@code length}, {@code tool_calls} — and a path that answered
   * {@code eosFound} would make the two transports disagree about a turn they both handled.
   * Measured against the reference node: a tool-calling turn reports {@code toolCalls} and a capped
   * one {@code maxPredictedTokensReached}.
   *
   * <p>A reason with no OpenAI counterpart maps to null, which {@code Completion} already documents
   * as "the server did not say" — better than inventing {@code stop} for a generation that failed.
   */
  private static String finishReason(String stopReason) {
    if (stopReason == null) {
      return null;
    }
    return switch (stopReason) {
      case "eosFound", "stopStringFound", "userStopped" -> "stop";
      case "maxPredictedTokensReached", "contextLengthReached" -> "length";
      case "toolCalls" -> "tool_calls";
      default -> null;
    };
  }

  private String text(ObjectNode frame) {
    return frame.toString();
  }

  /** Names the pool and never the endpoint or the key, as every message from this layer does. */
  private String prefix() {
    return "pool '" + poolName() + "': ";
  }

  /**
   * Closes the wrapped transport and nothing else.
   *
   * <p>The websocket client belongs to the transport that opened it and is closed with that
   * transport; a decorator that shut down a connection pool it was merely lent would close the
   * {@code /v1} path out from under the transport it delegates to.
   */
  @Override
  public void close() {
    base.close();
  }

  // ---- the listener --------------------------------------------------------

  /**
   * A socket failure, queued so the calling thread raises it rather than OkHttp's reader thread
   * swallowing it.
   */
  private record Failure(String why) {}

  /**
   * The server closed the socket. Only a failure if the channel had not finished, which is the
   * calling thread's judgement and not this one's.
   */
  private record Closed() {}

  /**
   * Pushes what arrives onto a queue and interprets none of it.
   *
   * <p>OkHttp delivers websocket callbacks on its own reader thread. Doing the work there would
   * call {@code sink} off the lane thread, which {@link LlmTransport#stream} forbids, so everything
   * crosses to the calling thread through the queue and this class stays a pipe.
   */
  private final class Collector extends WebSocketListener {

    private final BlockingQueue<Object> frames;

    Collector(BlockingQueue<Object> frames) {
      this.frames = frames;
    }

    @Override
    public void onMessage(WebSocket socket, String text) {
      try {
        frames.add(mapper.readTree(text));
      } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
        frames.add(new Failure("the endpoint sent a frame that was not JSON"));
      }
    }

    @Override
    public void onClosing(WebSocket socket, int code, String reason) {
      frames.add(new Closed());
    }

    @Override
    public void onFailure(WebSocket socket, Throwable failure, Response response) {
      // The throwable's message, not the response body: a 401 body from
      // this node quotes nothing secret, but the rule this codebase keeps
      // is that an auth failure names the pool and offers nothing else.
      frames.add(
          new Failure(
              failure.getMessage() == null
                  ? failure.getClass().getSimpleName()
                  : failure.getMessage()));
    }
  }
}
