package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;

/**
 * One chat response, whether it arrived at once or a token at a time.
 *
 * <p>{@code stream} and {@code complete} return the same type on purpose: a streaming call is the
 * same inference resource held for longer, not a different kind of work, and a caller that stops
 * streaming should not have to change what it does with the result.
 *
 * @param content the assembled text. Never null, but legitimately empty: a model that stops on its
 *     first token answers with nothing, which is a result and not a failure. A turn that is
 *     entirely tool calls is the other way to arrive here empty, and {@code toolCalls} is what
 *     tells the two apart.
 * @param finishReason why generation stopped — {@code stop}, {@code length}, {@code tool_calls} —
 *     or null, because a local OpenAI-compatible server may omit it. Worth reading before the
 *     content is trusted: {@code length} means the answer was cut off mid-thought, and a caller
 *     that treats it as a complete reply stores a truncated one.
 * @param usage what the call cost, or {@link TokenUsage#UNKNOWN}. Never null.
 * @param toolCalls what the model asked to call, in the order it asked, empty when it asked for
 *     nothing. Never null, for the reason {@link TokenUsage#UNKNOWN} exists: a null here writes a
 *     null check into every caller forever, and the caller that forgets fails on a lane thread
 *     whose stack names the pool rather than the turn loop that read it.
 *     <p>An array and not one call, and a reader that takes {@code get(0)} is broken against any
 *     model that batches. Measured 2026-08-29 against qwen3.5-9b: it does not batch — 0/4 when
 *     asked for two independent lookups — so more than one element is reachable here only from a
 *     fixture. That is a fact about one model on one box, not about the contract.
 *     <p><b>Both paths populate this, and they must agree.</b> This said "only {@code complete} can
 *     populate this; {@code stream} always reports none", which stopped being true when {@code
 *     OpenAiTransport.stream} learned to accumulate {@code delta.tool_calls} per {@code index}. The
 *     same turn dispatched either way yields the same calls in the same order with the same
 *     unparsed {@code arguments}; {@code the_two_paths_answer_a_tool_call_identically} is the
 *     assertion, and the agreement is what lets a turn loop stream without changing what it does
 *     with a result.
 * @param servedBy which pool and wire model produced this, stamped by {@link LlmDispatcher} once it
 *     has routed the call, or null for a completion that did not pass through one — a transport's
 *     own return, or a fixture. A transport does not know which specifier it was chosen for, so it
 *     is the dispatcher's to say and never the transport's.
 */
public record Completion(
    String content,
    String finishReason,
    TokenUsage usage,
    List<ToolCall> toolCalls,
    Served servedBy,
    @com.fasterxml.jackson.annotation.JsonIgnore InferenceCapture capture) {

  /** The pool the dispatcher routed to, and the model it asked that pool for. */
  public record Served(String pool, String wireModel) {}

  public Completion {
    // Copied for the same reason ChatRequest copies its tools: a completion
    // is built on a lane thread and read on the caller's, and a list the
    // transport still holds a reference to could change under the reader.
    toolCalls = List.copyOf(toolCalls);
  }

  /** A completion as a transport returns it: nobody has said where it was served yet. */
  public Completion(
      String content, String finishReason, TokenUsage usage, List<ToolCall> toolCalls) {
    this(content, finishReason, usage, toolCalls, null, null);
  }

  public Completion(
      String content,
      String finishReason,
      TokenUsage usage,
      List<ToolCall> toolCalls,
      Served servedBy) {
    this(content, finishReason, usage, toolCalls, servedBy, null);
  }

  /** The same completion, attributed to the pool and model that produced it. */
  public Completion servedBy(String pool, String wireModel) {
    return new Completion(
        content, finishReason, usage, toolCalls, new Served(pool, wireModel), capture);
  }

  public Completion captured(InferenceCapture value) {
    return new Completion(content, finishReason, usage, toolCalls, servedBy, value);
  }
}
