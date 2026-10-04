package io.aeyer.plowshare.server.llm.lmstudio;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.server.llm.LlmProvider;
import io.aeyer.plowshare.server.llm.OpenAiCompatible;
import io.aeyer.plowshare.server.llm.dispatch.ChatMessage;
import io.aeyer.plowshare.server.llm.dispatch.Completion;
import io.aeyer.plowshare.server.llm.dispatch.Deltas;
import io.aeyer.plowshare.server.llm.dispatch.Embeddings;
import io.aeyer.plowshare.server.llm.dispatch.InferenceObserver;
import io.aeyer.plowshare.server.llm.dispatch.Sampling;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LM Studio: {@code /v1} for every call, and {@code /api/v0/models} for the one fact {@code /v1}
 * has no field for.
 *
 * <p>Every chat, stream and embedding call is handed to the wrapped {@link OpenAiCompatible}
 * untouched. Nothing about talking to this box is vendor-specific; only what can be <em>asked</em>
 * of it is.
 *
 * <h2>Why {@code loaded_context_length} and not {@code max_context_length}</h2>
 *
 * <p>Measured against the reference box on 2026-08-31, {@code GET /api/v0/models} reports both for
 * the chat model: {@code max_context_length} 262144 and {@code loaded_context_length} 128000. The
 * first is what the model is capable of. The second is what the instance currently serving it will
 * accept.
 *
 * <p><b>Reading the bigger one builds prompts that are valid for the model and refused by the
 * server running it</b> — a failure that arrives as a rejected request and reads as a model problem
 * when it is a configuration one. The two fields sit side by side in the same object and the larger
 * is the more attractive number, so the reason is written here, at the line that reads the field,
 * rather than left for the next person to rediscover from a refusal.
 *
 * <p>The same call reports {@code capabilities: ["tool_use"]}, which is the other thing this system
 * currently discovers by trying. It is not read here; this class exists to answer one question and
 * adding a second before anything asks it would be building for a caller that does not exist.
 *
 * <h2>Discovery is opportunistic, never a boot dependency</h2>
 *
 * <p>{@code application.yml} records a deliberate property: a model name the server does not know
 * "fails at the call rather than at startup". A provider that probed during startup and refused
 * when the node was down would trade that away, turning every deployment where the inference box
 * comes up second into a server that will not start. So the probe happens the first time somebody
 * asks for a length that is not configured, and a probe that fails leaves the pool exactly as
 * usable as it was.
 */
public final class LmStudio implements LlmProvider {

  private static final Logger log = LoggerFactory.getLogger(LmStudio.class);

  /**
   * The vendor endpoint, a sibling of {@code /v1} rather than a path under it.
   *
   * <p>{@code /api/v0/tokenize} is deliberately not here. Measured on the same box and the same
   * day: it answers {@code HTTP 200} with {@code {"error":"Unexpected endpoint or method. (GET
   * /api/v0/tokenize)"}} — a 200, which is the part worth recording, because a caller checking
   * status codes would read that as a successful tokenisation of nothing. Counting tokens is the
   * one thing genuinely unavailable here, and it is the one thing not needed: compaction is decided
   * between turns, from the previous turn's measured {@code prompt_tokens}.
   */
  private static final String MODELS = "/api/v0/models";

  private final OpenAiCompatible base;

  /**
   * The websocket transport, or null when this pool has not asked for one.
   *
   * <p>Null rather than an Optional of a disabled thing, because the whole point is that a pool
   * which has not opted in behaves exactly as it did before this class knew what a websocket was —
   * including building nothing.
   */
  private final LmStudioSocket socket;

  /**
   * What the last successful probe found, or null if there has not been one.
   *
   * <p>Volatile and replaced whole rather than mutated, so a reader either sees the previous answer
   * or the new one and never a half-filled map.
   */
  private volatile Map<String, Integer> discovered;

  /**
   * So a node that is down says so once rather than once per turn.
   *
   * <p>Cleared again by a probe that succeeds, so a box that goes away a second time is reported a
   * second time. Without that, a warning would be spent on the first blip and the real outage three
   * days later would be silent.
   */
  private final AtomicBoolean warned = new AtomicBoolean();

  public LmStudio(OpenAiCompatible base) {
    this(base, null);
  }

  public LmStudio(OpenAiCompatible base, LmStudioSocket socket) {
    this.base = base;
    this.socket = socket;
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
   * <p><b>The websocket path only when the pool asked for it and only when a caller wants the
   * figure.</b> Everything else — including the six-argument {@link #stream}, which is what every
   * caller in this codebase currently uses — goes to {@code /v1} exactly as before.
   *
   * <p>Prompt-processing progress is the one thing {@code /v1} cannot report: measured 2026-09-02
   * against the reference node, a streamed {@code /v1/chat/completions} carries no field for it
   * across any chunk, while LM Studio's own websocket namespace sends it as a packet. So a pool
   * that wants it has to be asked over a different protocol, and a pool that does not is not made
   * to pay for one.
   */
  @Override
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      java.util.function.DoubleConsumer prefill) {
    if (socket == null) {
      return base.stream(wireModel, messages, sampling, tools, sink, abandoned);
    }
    return socket.stream(wireModel, messages, sampling, tools, sink, abandoned, prefill);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>The intersection when a socket is wired, and {@code base}'s set when one is not</b> — so
   * a pool sends one configuration however an individual call happens to be dispatched. A pool with
   * {@code prefill-progress} on routes the seven-argument {@link #stream} over the websocket, whose
   * frame carries two of the five parameters, and everything else over {@code /v1}, whose body
   * carries four. Reporting the wider set would mean a fold and a turn on the same agent and the
   * same model sampling differently, with the difference visible nowhere: the {@code top_p} would
   * be honoured on one and dropped on the other, and neither the profile nor the log would say
   * which call got which.
   *
   * <p>So enabling prefill reporting narrows what this pool can carry, which is a real cost and is
   * stated rather than hidden — {@code LlmDispatcher} warns once per pool and parameter naming
   * exactly what stopped being sent. The alternative was a silent inconsistency, and this project
   * has already paid once for a setting whose effect nothing reported.
   */
  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countChat(
      String model, io.aeyer.plowshare.server.llm.dispatch.ChatRequest request) {
    return socket == null
        ? base.countChat(model, request)
        : io.aeyer.plowshare.server.llm.counting.PromptCount.unknown(
            poolName(), model, "native_template_not_supported");
  }

  @Override
  public io.aeyer.plowshare.server.llm.counting.PromptCount countEmbedding(
      String model, io.aeyer.plowshare.server.llm.dispatch.EmbeddingRequest request) {
    return base.countEmbedding(model, request);
  }

  @Override
  public boolean automaticCounting() {
    return base.automaticCounting();
  }

  @Override
  public Set<Sampling.Parameter> carries() {
    if (socket == null) {
      return base.carries();
    }
    EnumSet<Sampling.Parameter> both = EnumSet.copyOf(base.carries());
    both.retainAll(socket.carries());
    return both;
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
  public Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      java.util.function.DoubleConsumer prefill,
      InferenceObserver observer) {
    return socket == null
        ? base.stream(
            wireModel,
            messages,
            sampling,
            tools,
            sink,
            abandoned,
            (io.aeyer.plowshare.server.llm.dispatch.ToolChoice) null,
            observer)
        : socket.stream(wireModel, messages, sampling, tools, sink, abandoned, prefill, observer);
  }

  @Override
  public Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      io.aeyer.plowshare.server.llm.dispatch.ToolChoice choice,
      InferenceObserver observer,
      Duration timeout) {
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
      Duration timeout) {
    return base.stream(
        wireModel, messages, sampling, tools, sink, abandoned, choice, observer, timeout);
  }

  @Override
  public Duration promptTimeout() {
    return base.promptTimeout();
  }

  @Override
  public Embeddings embed(String wireModel, List<String> input, InferenceObserver observer) {
    return base.embed(wireModel, input, observer);
  }

  @Override
  public OptionalInt maxContextLength(String wireModel) {
    return base.maxContextLength(wireModel);
  }

  /**
   * {@inheritDoc}
   *
   * <p><b>Configured first, and the endpoint is not asked at all when the answer is already
   * known.</b> That ordering is the precedence rule and also what keeps a fully configured fleet
   * from paying for a network call it does not need. Probing first and then letting the configured
   * value win would return the same number and cost a request per pool.
   *
   * <p>With {@code max-context-lengths} configured, the node is also asked: its loaded window may
   * lower a stale declared capacity. The dispatcher then caps that result by the configured working
   * maximum.
   */
  @Override
  public OptionalInt contextLength(String wireModel) {
    OptionalInt configured = configuredContextLength(wireModel);
    if (configured.isPresent() && maxContextLength(wireModel).isEmpty()) {
      return configured;
    }
    // A working maximum caps actual capacity. Discovery may also lower a stale
    // declared capacity when the operator has selected this capped behavior.
    Integer found = discover().get(wireModel);
    if (found == null) {
      return configured;
    }
    return OptionalInt.of(configured.isPresent() ? Math.min(found, configured.getAsInt()) : found);
  }

  /**
   * The wrapped transport's answer, unchanged, and never a discovered one.
   *
   * <p><b>Delegated and not inherited</b>, for the reason {@link #compactionThreshold} gives: the
   * interface default answers empty, and inheriting it here would discard every {@code
   * context-lengths} key on an LM Studio pool — which is every pool the reference deployment has.
   *
   * <p><b>And deliberately not {@link #contextLength(String)}.</b> The two differ by exactly the
   * probe, which is the distinction this method exists to preserve: it is what lets a log line tell
   * an operator that the number bounding their conversations is one they wrote, rather than one the
   * box reported.
   */
  @Override
  public OptionalInt configuredContextLength(String wireModel) {
    return base.configuredContextLength(wireModel);
  }

  /**
   * The wrapped transport's answer, unchanged.
   *
   * <p><b>Delegated and not inherited.</b> {@code LlmTransport}'s default answers empty, and this
   * class decorates a transport that reads the configured value — so inheriting the default here
   * would silently discard every {@code compaction-thresholds} key on an LM Studio pool, which is
   * every pool the reference deployment has. There is nothing to discover: unlike a context length,
   * a fold threshold is a judgement rather than a fact about the node.
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

  /** True: this is the provider that has somewhere to look. */
  @Override
  public boolean canDiscover() {
    return true;
  }

  /**
   * What the box says it has loaded, probed at most once while it keeps saying it.
   *
   * <p>Double-checked against a volatile field, with the probe itself inside the lock: a pool's
   * lanes call this from several threads, and without the lock a cold provider under load would
   * send one metadata request per lane to answer one question.
   *
   * <p><b>A failure is not cached, and that is a decision rather than an omission.</b> Caching it
   * would mean a node that was down when the first conversation started stays "unknown" until the
   * server is restarted — a silent, permanent degradation with no log line at the moment it
   * matters. Not caching it means a retry per call while the box is unreachable, which is bounded
   * by the pool's own {@code retry-max-attempts} and {@code embedding-timeout} and only happens in
   * the state where every other call to that box is failing too. The cheap failure — a host that is
   * up but has no {@code /api/v0}, which is what a pool wrongly declared {@code lmstudio} looks
   * like — is a 404 and is not retried at all.
   *
   * <p>What is <em>not</em> handled is a model reloaded at a different length while the server
   * runs: a successful probe is kept for the life of the process. That is the paragraph to revisit
   * if it ever proves wrong, and the fix would be a time bound rather than a second cache.
   */
  private Map<String, Integer> discover() {
    Map<String, Integer> cached = discovered;
    if (cached != null) {
      return cached;
    }
    synchronized (this) {
      if (discovered != null) {
        return discovered;
      }
      try {
        Map<String, Integer> found = loadedLengths(base.probe(MODELS));
        discovered = found;
        warned.set(false);
        return found;
      } catch (RuntimeException probeFailed) {
        // The message is already safe to print: it came from
        // OpenAiTransport, which withholds a 401 or 403 body whole and
        // withholds any other text that quotes this pool's key. Nothing
        // is re-guarded here, because a second guard would be a second
        // thing to keep correct about the same hazard.
        if (warned.compareAndSet(false, true)) {
          log.warn(
              "pool '{}': could not read {} from the endpoint, so no model's"
                  + " context length is known from it. Conversations will run"
                  + " without one until the node answers, or until"
                  + " plowshare.llm.pools[...].context-lengths says. Reason: {}",
              base.poolName(),
              MODELS,
              probeFailed.getMessage());
        } else {
          log.debug(
              "pool '{}': {} still unreadable: {}",
              base.poolName(),
              MODELS,
              probeFailed.getMessage());
        }
        return Map.of();
      }
    }
  }

  /**
   * Every model in the response that reports a length it is loaded at.
   *
   * <p><b>{@code loaded_context_length}, and never {@code max_context_length}.</b> A model can be
   * listed and not loaded — the same response carries {@code state} — and in that case the only
   * figure present is what the model is capable of, which is not a bound on anything this server
   * will accept. Falling back to it would report 262144 for a model the box is not running, and the
   * resulting prompt is refused by the endpoint with a message about the model. So an entry without
   * the loaded figure contributes nothing, and the length stays honestly unknown.
   *
   * <p>Non-positive is treated as absent for the same reason a zero token count is: a length of
   * nought is not a bound anybody could have meant, and carrying it forward would turn "unknown"
   * into "no room at all" at whatever layer finally divides by it.
   */
  private static Map<String, Integer> loadedLengths(JsonNode root) {
    Map<String, Integer> lengths = new LinkedHashMap<>();
    for (JsonNode model : root.path("data")) {
      JsonNode id = model.path("id");
      JsonNode loaded = model.path("loaded_context_length");
      if (id.isTextual()
          && !id.asText().isBlank()
          && loaded.isIntegralNumber()
          && loaded.intValue() > 0) {
        lengths.put(id.asText(), loaded.intValue());
      }
    }
    return Map.copyOf(lengths);
  }

  @Override
  public void close() {
    base.close();
  }
}
