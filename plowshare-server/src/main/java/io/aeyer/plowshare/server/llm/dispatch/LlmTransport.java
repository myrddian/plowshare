package io.aeyer.plowshare.server.llm.dispatch;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * HTTP to one endpoint, and nothing else.
 *
 * <p>No queueing, no routing, no retry policy beyond the connection-level one: a transport is
 * handed a wire model name and told to make the call. That is what lets every concurrency test in
 * this slice run against a fake that blocks and counts, with no endpoint anywhere near it.
 *
 * <p>Implementations must be safe to call from several threads at once.
 *
 * <p><b>And every call must return in bounded time</b>, by way of a connect and read timeout on the
 * underlying client. {@link LlmPool} waits on the result without a deadline of its own, on purpose
 * — a request that has reached the model is left to finish, because cutting it off frees nothing
 * while the box is still busy — so this timeout is the only thing between a wedged endpoint and a
 * Tomcat worker blocked for good. A transport that can block forever turns one unresponsive host
 * into an exhausted container thread pool, which is the same shape of outage this whole slice
 * exists to prevent.
 */
public interface LlmTransport
    extends AutoCloseable, io.aeyer.plowshare.server.llm.counting.PromptCounter {

  /**
   * The pool this transport belongs to, for messages. Never a URL and never a key: an auth failure
   * names which pool refused, not what was offered.
   */
  String poolName();

  /**
   * One blocking chat call, optionally offering the model tools.
   *
   * <p>{@code messages} is the whole conversation, in order — see {@link ChatMessage}. It replaced
   * a loose system prompt and user prompt when the turn loop needed to send an assistant turn and
   * its tool results; a two-message conversation must still produce the request an implementation
   * sent before it did.
   *
   * <p>{@code tools} is never null and is empty for a prose call. An empty list must produce the
   * same request an implementation sent before tools existed — an empty {@code tools} array is not
   * the same as no {@code tools} key, and some OpenAI-compatible servers refuse the former.
   *
   * <p>{@link #stream} answers the same question with the same vocabulary; see there for what is
   * required to stay identical between the two.
   */
  Completion complete(
      String wireModel, List<ChatMessage> messages, Sampling sampling, List<ToolSchema> tools);

  /**
   * The same call, emitting each token to {@code sink} as it arrives, and returning the assembled
   * result.
   *
   * <p>Blocking, like {@link #complete}. A streaming call holds its slot for longer; it does not
   * hold a different kind of slot.
   *
   * <p><b>Tools are offered here exactly as they are to {@link #complete}, and the two must answer
   * alike.</b> A request answered either way has to give the runtime the same {@link Completion}:
   * the same content, the same finish reason, and the same {@link Completion#toolCalls()} in the
   * same order, with {@code arguments} the bytes the model emitted. On the wire the two differ — a
   * blocking response carries {@code message.tool_calls} whole, a stream carries {@code
   * delta.tool_calls} fragments to be accumulated per {@code index} — and that difference is an
   * implementation's problem and nobody else's. A transport that streamed a turn's tool calls into
   * silence would be indistinguishable from a model that chose to call none, which is the failure
   * this used to avoid by refusing tools outright.
   *
   * <p><b>Reasoning is not content.</b> A model that thinks emits it on a separate wire field;
   * whatever an implementation does with that, it must not reach {@code sink} or {@link
   * Completion#content()}, because {@code Outcome.text} is the answer and thinking is not the
   * answer. See {@code OpenAiTransport.stream} for what this project's one implementation does with
   * it and why.
   *
   * <p><b>{@code sink} is called on the lane thread</b>, in order, before this method returns —
   * never on the caller's thread and never on more than one thread. So a sink that blocks holds an
   * inference slot for as long as it blocks, and one that throws fails the call from inside the
   * transport. Note also that the caller may already be gone: a caller interrupted while waiting
   * leaves the call running to completion, and tokens keep arriving here after it has thrown. A
   * sink must therefore tolerate writing to a destination nobody is reading.
   *
   * <p><b>{@code abandoned} is asked during reads, and a true answer stops the read.</b> Built-in
   * SSE and native transports also poll during quiet periods. An implementation must then stop
   * pulling the body, release whatever the call holds, and raise {@link CallerAbandonedException} —
   * never return what it had, which would be a truncated generation indistinguishable from a
   * complete one.
   *
   * <p>It is a separate parameter and not something a throwing sink could cover, and the reason is
   * a measurement rather than taste. On 2026-09-02 a tool-carrying request came back as 63 chunks
   * of which <b>none carried {@code delta.content}</b>: sixty were reasoning and two were tool-call
   * fragments. A sink is handed tokens of the answer, so on that turn — the ordinary turn of an
   * agent that uses tools — a sink would never have been called at all, and a cancellation routed
   * through it would have waited out the whole generation while appearing to work. A guard that
   * cannot fire on the common case is worse than none, because a green suite says otherwise.
   *
   * <p>The granularity is one chunk and no finer, which is worth stating so nobody expects instant.
   * Nothing is asked while the endpoint is silent, so a run cancelled during a long prefill stops
   * when the next chunk arrives — about 1.5 seconds on the reference node — or when {@code
   * max-stream-duration} runs out if none ever does. Must be cheap and must not throw: it is called
   * on the reader thread, per chunk, and an implementation is entitled to treat a throw from it as
   * a stream failure.
   */
  Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned);

  /**
   * The same streaming call, additionally reporting how far the endpoint has got through
   * <em>reading the prompt</em>, before any token exists.
   *
   * <p><b>The default ignores {@code prefill} and delegates to {@link #stream}, which is the honest
   * answer for almost every endpoint.</b> An OpenAI-compatible server has no field for this:
   * measured 2026-09-02 against the reference node, a streamed {@code /v1/chat/completions} carries
   * exactly {@code choices, created, id, model, object, system_fingerprint, usage}, and a delta
   * carries only {@code role}, {@code reasoning_content} and {@code content}. There is nothing to
   * report from, so an implementation that cannot report says so by not overriding rather than by
   * inventing a number. It is a default and not a new parameter on {@link #stream} for the same
   * reason: every implementation of this interface would otherwise have to name an argument it
   * cannot use.
   *
   * <p>Prefill is the window this exists for. Measured 2026-09-02 against qwen3.5-9b on the
   * reference node, over the LM Studio websocket API: a novel 62 kB prompt of 30 415 tokens
   * reported progress steadily, a median 7.5 seconds between frames, and took 119 seconds to its
   * first token. That is two minutes in which an OpenAI-compatible stream of the same request emits
   * nothing whatsoever.
   *
   * <p>The gap between frames <em>grows</em> through most of a prefill — on that run from 4.4
   * seconds to 9.6 by the time it was seven eighths done, then shortening over the last few frames
   * — so a consumer estimating a finish time from the rate so far promises one that is too early
   * for most of the wait, and then arrives sooner than its own last estimate.
   *
   * <p><b>{@code prefill} is called with a fraction from 0.0 to 1.0 inclusive, on the lane thread,
   * in non-decreasing order</b>, under exactly the constraints {@code sink} carries: it may block
   * an inference slot, a throw from it fails the call, and the caller it reports to may already be
   * gone. It stops when generation begins; a caller wanting to know about tokens is already given
   * {@code sink}.
   *
   * <p><b>Neither endpoint of the range is promised.</b> Measured the same day: an uncached prompt
   * did report 0.0 first and 1.0 last, but a prompt whose prefix the node still had cached reported
   * only those two frames and nothing between — 53 970 prompt tokens "processed" in 1.19 seconds. A
   * consumer that waits for intermediate frames before showing anything will show nothing at all on
   * a cache hit, which on this node is the common case for a conversation's second turn.
   */
  default Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill) {
    return stream(wireModel, messages, sampling, tools, sink, abandoned);
  }

  /**
   * The same two calls, with the model forbidden to answer without calling a tool.
   *
   * <h2>Defaults that drop the constraint, and why that is safe here and nowhere else</h2>
   *
   * <p>Both delegate to the arity above, ignoring {@code toolChoice}. <b>Only a transport that
   * builds a wire body can honour it</b>, and exactly one does — {@code OpenAiTransport}, which
   * overrides both. Everything else implementing this interface either wraps that one or is a fake
   * standing in for an endpoint in a test, and a fake has no {@code tool_choice} to send: it
   * decides directly what a completion contains.
   *
   * <p>So the default is not a silent degradation of a live path. It is the one place in this
   * interface where "this implementation cannot express it" is true by construction rather than by
   * omission, which is why it is a default here instead of a method every implementor must write.
   * {@code Sampling.carries()} exists for the opposite case — a real transport that genuinely
   * cannot send a parameter — and {@code ChatRequest.toolChoice} records why this is deliberately
   * not filtered that way.
   *
   * @param toolChoice what to send, or {@code null} to send no {@code tool_choice} key
   */
  default Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice) {
    return complete(wireModel, messages, sampling, tools);
  }

  /**
   * @see #complete(String, List, Sampling, List, ToolChoice)
   */
  default Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice) {
    return stream(wireModel, messages, sampling, tools, sink, abandoned);
  }

  /** A chat call with an explicit global prompt budget; null retains transport defaults. */
  default Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice choice,
      InferenceObserver observer,
      Duration timeout) {
    return complete(wireModel, messages, sampling, tools, choice, observer);
  }

  /** A stream with an explicit whole-call budget, including silent prefill. */
  default Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice choice,
      InferenceObserver observer,
      Duration timeout) {
    return stream(wireModel, messages, sampling, tools, sink, abandoned, choice, observer);
  }

  /** The general streaming prompt budget when no global override was configured. */
  default Duration promptTimeout() {
    return null;
  }

  /** A configured per-model working-context maximum, never a capacity claim. */
  default OptionalInt maxContextLength(String wireModel) {
    return OptionalInt.empty();
  }

  /**
   * Which sampling parameters this transport can actually put on the wire.
   *
   * <h2>A transport declares what it can carry, and the rest is dropped with a note</h2>
   *
   * <p>Different backends accept different subsets, and the difference is <em>silent</em> at the
   * endpoint: a server that does not know {@code top_k} ignores the field rather than refusing the
   * request, so a profile written for one backend quietly becomes a different configuration on
   * another and nothing anywhere says so. {@code LlmDispatcher} filters every request against this
   * set and reports once per pool and parameter what it removed, which turns that silence into a
   * line an operator can read.
   *
   * <p><b>The default is {@code TEMPERATURE} alone</b>, which is what every implementation in this
   * repository sent before there was anything else to send, and it is the conservative direction: a
   * transport that has not thought about {@code top_p} drops it loudly rather than sending it into
   * a server that may or may not read it. Overriding this is a claim, and the claim has to be one
   * somebody verified -- see {@code LmStudioSocket.carries()} for a deliberately narrow one and
   * {@code OpenAiTransport.carries()} for the documented wide one.
   *
   * <p>Must be constant for the life of the transport, and must not depend on the wire model: it
   * describes the protocol, not the model behind it. What a <em>model</em> wants is a profile's
   * business.
   */
  default Set<Sampling.Parameter> carries(String model, Sampling sampling) {
    return carries();
  }

  default Set<Sampling.Parameter> carries() {
    return EnumSet.of(Sampling.Parameter.TEMPERATURE);
  }

  Embeddings embed(String wireModel, List<String> input);

  /**
   * Observe one attempt by default; retrying production transports carry this observer into every
   * exchange.
   */
  default Completion complete(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      ToolChoice toolChoice,
      InferenceObserver observer) {
    return InferenceObserver.once(
        observer,
        () -> complete(wireModel, messages, sampling, tools, toolChoice),
        Completion::usage,
        Completion::finishReason);
  }

  /**
   * The explicit observer is safe across pool workers and streaming callbacks; there is no ambient
   * current call.
   */
  default Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      ToolChoice toolChoice,
      InferenceObserver observer) {
    return InferenceObserver.once(
        observer,
        () -> stream(wireModel, messages, sampling, tools, sink, abandoned, toolChoice),
        Completion::usage,
        Completion::finishReason);
  }

  /** Native prefill streaming uses the same accounting contract as SSE. */
  default Completion stream(
      String wireModel,
      List<ChatMessage> messages,
      Sampling sampling,
      List<ToolSchema> tools,
      Deltas sink,
      BooleanSupplier abandoned,
      DoubleConsumer prefill,
      InferenceObserver observer) {
    return InferenceObserver.once(
        observer,
        () -> stream(wireModel, messages, sampling, tools, sink, abandoned, prefill),
        Completion::usage,
        Completion::finishReason);
  }

  default Embeddings embed(String wireModel, List<String> input, InferenceObserver observer) {
    return InferenceObserver.once(
        observer, () -> embed(wireModel, input), Embeddings::usage, ignored -> null);
  }

  /**
   * How many tokens this endpoint's copy of {@code wireModel} is loaded to accept, or empty if
   * nobody has said.
   *
   * <p><b>A default answering empty, and that is the honest reading of this interface rather than a
   * convenience.</b> A transport is HTTP to one endpoint; {@code /v1} has no field carrying a
   * context length — measured against the reference box, {@code GET /v1/models} returns {@code id},
   * {@code object} and {@code owned_by} and nothing else — so a bare transport genuinely does not
   * know, and saying so is a fact about the contract rather than a stub. It is declared here so
   * that {@link LlmPool} can ask any transport without a cast, which is what keeps {@code
   * llm.dispatch} from having to import the provider package that already imports it.
   *
   * <p><b>{@code io.aeyer.plowshare.server.llm.LlmProvider} re-declares this without a body</b>, so
   * a provider — the thing that exists precisely to answer what {@code /v1} cannot — has to make a
   * decision rather than inherit silence.
   *
   * <p>May open a connection, and must never throw because it could not: a failed probe is an empty
   * answer. Must not be called on the boot path.
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   */
  default OptionalInt contextLength(String wireModel) {
    return OptionalInt.empty();
  }

  /**
   * The half of {@link #contextLength(String)} an operator wrote down, and nothing that was
   * discovered.
   *
   * <p><b>It exists so that a log line can name which tier answered.</b> A context length is
   * resolved through three of them — a {@code context-lengths} entry, then what the node reports,
   * then {@code plowshare.llm.default-context-length} — and {@link LlmDispatcher} is where they are
   * ranked and reported. From there the first two are indistinguishable: {@code
   * LmStudio.contextLength} merges them by design, because a caller sizing a prompt does not care
   * where the number came from. An operator reading a log does: "you set this" and "the box said
   * this" send them to different places.
   *
   * <p><b>Opens no connection, ever</b>, which is the other half of why it is a separate question
   * rather than a flag on the first. There is nothing to discover here — the answer is a map lookup
   * — so asking it costs the same whether or not anybody is going to read the line it feeds.
   *
   * <p>The default answers empty for the same reason {@link #contextLength(String)}'s does: a bare
   * transport is HTTP to one endpoint and holds no configuration.
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   */
  default OptionalInt configuredContextLength(String wireModel) {
    return OptionalInt.empty();
  }

  /**
   * The prompt size, in tokens, at which a conversation on this endpoint's copy of {@code
   * wireModel} should fold its history, or empty if nobody has said.
   *
   * <p><b>Configured only. There is nothing here to discover and there never will be.</b> A context
   * length is a fact the endpoint knows about itself; a fold threshold is a judgement about what a
   * conversation should cost, and no endpoint has an opinion on that. It rides this interface
   * anyway, beside {@link #contextLength(String)}, because it is keyed the same way — per pool, per
   * wire model — and because the box is half of what the judgement was measured against.
   *
   * <p>Empty is the ordinary answer and is not a failure: a caller with no number derives one from
   * the context length. See {@code Compaction} for the fraction it derives and the measurements
   * behind it.
   *
   * <p>Opens no connection, unlike {@link #contextLength(String)}, and may therefore be called
   * anywhere.
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   */
  default OptionalInt compactionThreshold(String wireModel) {
    return OptionalInt.empty();
  }

  /**
   * The prompt size, in tokens, at which a conversation on this endpoint's copy of {@code
   * wireModel} should fold <b>inside a turn</b>, or empty if nobody has said.
   *
   * <p>{@link #compactionThreshold(String)}'s twin, on its terms exactly: configured only, opens no
   * connection, and empty is the ordinary answer — a caller with no number derives one from the
   * context length ({@code FoldThresholds}).
   *
   * @param wireModel the model name as the endpoint knows it, not a class
   */
  default OptionalInt compactionNowThreshold(String wireModel) {
    return OptionalInt.empty();
  }

  /**
   * Releases what the transport holds — for an HTTP one, the connection pool and the threads its
   * dispatcher runs.
   *
   * <p>Must be idempotent, and must not throw. {@link LlmPool#close} calls this once per pool with
   * no guard of its own, and is safe to call twice only because this is; {@link AutoCloseable} asks
   * for the same.
   */
  @Override
  void close();
}
