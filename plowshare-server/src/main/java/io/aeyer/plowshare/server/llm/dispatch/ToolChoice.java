package io.aeyer.plowshare.server.llm.dispatch;

/**
 * Whether the model may end a turn without calling anything.
 *
 * <h2>Why this exists, measured rather than reasoned</h2>
 *
 * <p>2026-09-25, the first orchestration tree to run against a live model. {@code gpt-oss-120b}
 * made 56 {@code todo_write} calls, 32 {@code file_edit}s, 24 {@code file_read}s, started two child
 * orchestrations and polled their status — every tool worked. The two it would not call were {@code
 * orchestration_ask} and {@code orchestration_finish}: <b>the only two that end a turn</b>. It
 * rendered those as prose instead — "Orchestration finished." as the turn's last message, and once
 * a whole message whose content was {@code &#123;"question": "..."&#125;}, a tool call's arguments
 * emitted as text. Two of the three runs in that tree died {@code stuck} with their work complete
 * on disk; the third complied on the last nudge it had.
 *
 * <p>Measured against that endpoint the same day, with one tool offered and a prompt that said
 * <i>"Say you are finished. Do not call anything."</i>:
 *
 * <ul>
 *   <li>no {@code tool_choice}: {@code finish_reason=stop}, no tool calls, content "I'm finished."
 *       — the production failure, in one call;
 *   <li>{@code tool_choice: "required"}: {@code finish_reason=tool_calls}, a well-formed {@code
 *       orchestration_finish} carrying {@code &#123;"result": "I am finished."&#125;}.
 * </ul>
 *
 * <h2>{@code REQUIRED} and not a named tool, which is the design</h2>
 *
 * <p>The provider can force one specific function, and this deliberately does not. <b>The harness
 * is entitled to say that a turn may not end in prose; it is not entitled to say what the model
 * should do next.</b> A conductor that has been nudged might need to ask, or to finish, or to carry
 * on working — that is its judgement, and naming the tool here would take it. What this removes is
 * the one outcome that is never correct: an assistant message that describes a protocol act instead
 * of performing one.
 *
 * <p>This is the Aider separation the orchestration work was argued from — <i>don't force model
 * capability and protocol compliance to be the same operation</i> — applied at the wire rather than
 * in a prompt. The model keeps the whole of the decision; the grammar keeps the protocol.
 *
 * <h2>Absent is not {@code AUTO}</h2>
 *
 * <p>An empty {@code toolChoice} omits the key, which keeps a request byte-identical to the one
 * sent before this existed. {@link #AUTO} sends {@code "auto"} explicitly. They are the same
 * behaviour on every endpoint measured here and they are still two different things to send, on
 * {@code Sampling}'s rule that the absence of a parameter is not a value for it.
 */
public enum ToolChoice {

  /** Send {@code "auto"}: the model chooses whether to call anything. */
  AUTO("auto"),

  /**
   * Send {@code "required"}: the model must call one of the offered tools.
   *
   * <p><b>Never sent with an empty tool list.</b> An endpoint asked to require a call from no tools
   * has nothing it can answer with; {@code OpenAiTransport} drops the key rather than sending a
   * request that can only be refused.
   */
  REQUIRED("required");

  private final String wire;

  ToolChoice(String wire) {
    this.wire = wire;
  }

  /** The value the OpenAI-compatible {@code tool_choice} field takes. */
  public String wire() {
    return wire;
  }
}
