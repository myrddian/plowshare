package io.aeyer.plowshare.server.llm.dispatch;

import io.aeyer.plowshare.protocol.ToolCall;
import java.util.List;
import java.util.Objects;

/**
 * One message in a chat request, in the shape the OpenAI contract defines.
 *
 * <h2>Why this exists, and what it replaced</h2>
 *
 * <p>Slice 2 built chat for prose, so a request carried a system prompt and a user prompt and
 * nothing else. Task 1 added tools on the way out and tool calls on the way back, additively, and
 * left the middle of the conversation with nowhere to live. A turn loop needs that middle: after a
 * model asks for tools, the next request has to carry <em>what it asked for</em> and <em>what each
 * call returned</em>, correlated by {@code tool_call_id}.
 *
 * <p>Task 4 first rendered that history as prose inside the user message, which was the only thing
 * possible inside the old contract and was the wrong thing. <b>The measurements this slice rests on
 * were taken against real message arrays</b> — 5/5 clean native tool calls, 3/3 correct multi-turn
 * escalation, recall → read → answer in three turns, all against {@code
 * {"role":"assistant","tool_calls":[…]}} followed by {@code {"role":"tool","tool_call_id":…}}. A
 * rendered transcript is a different input to the model, so none of that evidence transfers to it.
 * Those measurements are what justified "native only, no fallback transport"; shipping a shape they
 * were not taken against would have left that justification standing on nothing.
 *
 * <h2>Parts, and why the accessor is still called {@code content}</h2>
 *
 * <p>A message used to hold a {@code String}. It holds a list of {@link Content} — words, or a
 * picture the server has already fetched out of its own store — because an image reaches a model as
 * a typed part of {@code content} and there is no other shape it can take.
 *
 * <p><b>{@link #parts()} is what the model is shown; {@link #content()} is what this server
 * reads.</b> Two names, because they are two questions, and the day they become one name is the day
 * a base64 payload turns up in a transcript entry, a compaction summary or a log line. Every
 * existing reader in this repository — {@code Compaction} counting characters towards a fold,
 * {@code Transcript} recording what was said, {@code Projection} rendering a conversation — is
 * asking the second question, and every one of them wants the words. So the accessor keeps its name
 * and its type, and answers the same string it always answered for the messages this server has
 * always sent.
 *
 * <p>The alternative was renaming it and letting the compiler visit the hundred call sites. That
 * would have been a rename in which not one reader changed its mind: the answer at every site is
 * "the words", which is what it already asked for. An image is <em>absent</em> from that answer
 * rather than summarised into it — {@link Content.Image#text()} is the empty string, and a stand-in
 * sentence there would be words in a message the model was never sent, which every character count
 * in this server would then be counting.
 *
 * <p><b>An image part is a user turn's and no other role's.</b> A tool message carrying one would
 * be a tool returning bytes, which is the thing this design refuses outright; an assistant message
 * carrying one would be a model emitting a picture, which nothing this server talks to does; a
 * system message carrying one has no meaning in any chat template here. Each is refused by name
 * rather than left representable, because the transport would send it and the endpoint would refuse
 * the whole request.
 *
 * <h2>The correlation is the point</h2>
 *
 * <p>{@link #toolCallId} is what tells the model which of several calls a result answers. Prose
 * rendering lost it — the id could be written into a sentence, but the association was something
 * the model had to infer from text rather than read from a field. Measured 2026-08-29: qwen3.5-9b
 * does not batch (0/4 when asked for two independent lookups), so a request carrying two tool
 * messages is reachable only from a fixture. That is a fact about one model on one box and not
 * about the contract, which is why the correlation is tested at the layer that writes the wire.
 *
 * <h2>What a message may be</h2>
 *
 * <p>The four roles are not interchangeable and the constructor refuses the per-message
 * combinations that are not messages. <b>Per-message only:</b> a message cannot see its neighbours,
 * so whether a {@code tool} message answers a call anything actually asked for is {@link
 * ChatRequest}'s to check, and it does. An earlier version of this sentence said "the combinations
 * that are not messages", which reads as covering more than one message at a time.
 *
 * <ul>
 *   <li>{@code system} and {@code user} carry content and nothing else. Blank is refused: a blank
 *       system turn is not the same input as no system turn, and a small model notices the
 *       difference — so the absence is spelled by leaving the message out, not by sending an empty
 *       one.
 *   <li>{@code assistant} carries content, tool calls, or both. A turn that is entirely tool calls
 *       has empty content, which is one of the two ways {@link Completion} documents arriving with
 *       none.
 *   <li>{@code tool} carries a result and the id of the call it answers. Both are required: a tool
 *       message with no id is one the model cannot place, and a blank result reads to a model as a
 *       tool that does not work — {@code AgentTool} forbids one and {@code JobRuntime} substitutes
 *       a sentence rather than passing one through.
 * </ul>
 *
 * <p>These are checked here rather than in the transport because a malformed message is a call-site
 * mistake, and the transport is several frames and one lane thread away from whoever made it.
 */
public record ChatMessage(
    Role role, List<Content> parts, List<ToolCall> toolCalls, String toolCallId) {

  /**
   * The four roles the chat contract defines. {@code wireName} rather than {@code
   * name().toLowerCase()} for the reason {@code Lane} has one: the wire spelling is a protocol fact
   * and must not follow a rename of the constant.
   */
  public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    Role(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }
  }

  public ChatMessage {
    Objects.requireNonNull(role, "role");
    // Copied, and rejecting a null element: a message may sit in a pool
    // queue while the caller still holds the list it passed.
    parts = parts == null ? List.of() : List.copyOf(parts);
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    // An EMPTY text part is dropped and a BLANK one is not, and the
    // difference is a measurement rather than tidiness: RequestTest pins
    // that an assistant turn of "  " keeps its two spaces, because what a
    // model said is what a model said. Empty is different -- it is the
    // absence of words, which is how a turn that is entirely tool calls is
    // spelled, and a Text("") in the list would make that turn's parts and
    // its content() disagree about whether it said anything.
    parts =
        parts.stream()
            .filter(part -> !(part instanceof Content.Text text && text.text().isEmpty()))
            .toList();
    String said = textOf(parts);
    boolean picture = parts.stream().anyMatch(part -> part instanceof Content.Image);
    if (picture && role != Role.USER) {
      throw new IllegalArgumentException(
          "a "
              + role.wireName()
              + " message cannot carry an image. A tool message"
              + " with one would be a tool returning bytes, an assistant message"
              + " with one would be a model emitting a picture, and no chat"
              + " template here has a meaning for a system message with one --"
              + " so the endpoint would refuse the whole request");
    }
    switch (role) {
      case SYSTEM, USER -> {
        // IllegalArgumentException and not NullPointerException, for
        // both null and blank. One caller mistake must not leave this
        // record by two different types: a chat surface will translate
        // IllegalArgumentException to a 400 at one boundary site, the
        // way MemoryController.resolveHome does, and that translation
        // cannot see a NullPointerException — which would go on reading
        // "the server is broken" for a prompt the caller forgot to send.
        // RequestTest makes this argument at length for the user prompt.
        //
        // A PICTURE COUNTS AS SOMETHING IN IT. "Describe this" and an
        // image is a message; so is the image alone, which is what a
        // caller sends when the whole instruction is in the system
        // prompt. It is the message with nothing at all in it that is
        // not a message.
        if (!picture && said.isBlank()) {
          throw new IllegalArgumentException(
              "a "
                  + role.wireName()
                  + " message needs something in it; leave the"
                  + " message out rather than sending an empty one");
        }
        requireNo(toolCalls.isEmpty(), role, "tool calls");
        requireNo(toolCallId == null, role, "a tool_call_id");
      }
      case ASSISTANT -> {
        // Content may be empty: a turn that is entirely tool calls is
        // exactly that, and it is not a malformed message.
        requireNo(toolCallId == null, role, "a tool_call_id");
        if (said.isEmpty() && toolCalls.isEmpty()) {
          throw new IllegalArgumentException(
              "an assistant message with neither content nor tool calls says"
                  + " nothing; it is a turn that did not happen");
        }
      }
      case TOOL -> {
        if (toolCallId == null || toolCallId.isBlank()) {
          throw new IllegalArgumentException(
              "a tool message needs the tool_call_id of the call it answers;"
                  + " without it the model cannot tell which call this is");
        }
        if (said.isBlank()) {
          throw new IllegalArgumentException(
              "a tool message needs a result; a blank one reads to a model as a"
                  + " tool that does not work");
        }
        requireNo(toolCalls.isEmpty(), role, "tool calls");
      }
      // Unreachable while Role has four constants, and against this
      // project's habit of not writing branches nothing reaches — kept
      // because it buys something the habit assumes away. A fifth role
      // added later would otherwise fall straight through this switch
      // with no validation at all, which is the one outcome worse than an
      // over-strict guard: a message shape nobody checked, on the wire.
      default -> throw new IllegalStateException("unreachable role " + role);
    }
  }

  /**
   * The message as it was written before parts existed, and the door every caller in this
   * repository still comes through.
   *
   * <p>Not a convenience. It is what keeps this change additive: a null or empty string is no part
   * rather than an empty one, so an assistant turn that is entirely tool calls is spelled here
   * exactly as it was.
   */
  public ChatMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
    this(
        role,
        content == null || content.isEmpty()
            ? List.of()
            : List.<Content>of(new Content.Text(content)),
        toolCalls,
        toolCallId);
  }

  /**
   * What this message says in words, which is what everything except the transport is asking for.
   *
   * <p><b>Byte for byte what this accessor answered when the field was a {@code String}</b>, for
   * every message this server sent before images existed — which is every message it sends now
   * except one built with a picture in it. That is not a coincidence to be maintained by care; a
   * message with one text part is the overwhelmingly common shape and this returns that part's own
   * string.
   *
   * <p>An image contributes nothing here. See the class comment: the alternative is a stand-in
   * sentence, and a stand-in sentence is words the model was never sent that every character count
   * would then count.
   */
  public String content() {
    return textOf(parts);
  }

  /**
   * Whether the model is being shown a picture in this message. The one question the transport asks
   * that {@link #content()} cannot answer, and what decides whether the wire carries a scalar or an
   * array.
   */
  public boolean carriesAnImage() {
    return parts.stream().anyMatch(part -> part instanceof Content.Image);
  }

  private static String textOf(List<Content> parts) {
    if (parts.size() == 1 && parts.get(0) instanceof Content.Text only) {
      return only.text();
    }
    StringBuilder said = new StringBuilder();
    for (Content part : parts) {
      said.append(part.text());
    }
    return said.toString();
  }

  private static void requireNo(boolean ok, Role role, String what) {
    if (!ok) {
      throw new IllegalArgumentException("a " + role.wireName() + " message cannot carry " + what);
    }
  }

  public static ChatMessage system(String content) {
    return new ChatMessage(Role.SYSTEM, content, List.of(), null);
  }

  public static ChatMessage user(String content) {
    return new ChatMessage(Role.USER, content, List.of(), null);
  }

  /**
   * A user turn with pictures in it: the words first, then the images, in the order they were
   * named.
   *
   * <h2>Words first, and it is not arbitrary</h2>
   *
   * <p>The instruction has to be readable before the thing it is about, the same way it would be in
   * prose, and a model that stops attending after the first image has still read the question. It
   * also keeps the prefix a conversation shares with its own next turn as long as it can be: the
   * text is what a later turn repeats, and a picture in front of it would end the shared prefix at
   * the first byte.
   *
   * <p><b>The caller has already resolved every UID to bytes.</b> That is the whole of the design —
   * an agent names a UID and the <em>server</em> attaches — and {@link Content.Image} is where the
   * refusal lives that keeps a URL from ever being one of these.
   *
   * @param content what the agent was asked, which is required: an image with no question is a turn
   *     a model cannot answer
   * @param images what it is being shown. Never empty here — a caller with none wants {@link
   *     #user(String)}, and this method answering the same thing for an empty list would be two
   *     spellings of one message
   */
  public static ChatMessage user(String content, List<Content.Image> images) {
    if (images == null || images.isEmpty()) {
      throw new IllegalArgumentException(
          "a user message with no images is ChatMessage.user(content); this overload"
              + " exists to say that a picture is being attached, and one that"
              + " quietly attached none would be a call site that looks like it"
              + " showed the model something");
    }
    List<Content> parts = new java.util.ArrayList<>(images.size() + 1);
    if (content != null && !content.isEmpty()) {
      parts.add(new Content.Text(content));
    }
    parts.addAll(images);
    return new ChatMessage(Role.USER, parts, List.of(), null);
  }

  /**
   * What the model said and what it asked to call, in the order it asked. Either half may be empty;
   * both may not.
   */
  public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
    return new ChatMessage(Role.ASSISTANT, content, toolCalls, null);
  }

  /** One tool's result, against the id of the call it answers. */
  public static ChatMessage tool(String toolCallId, String content) {
    return new ChatMessage(Role.TOOL, content, List.of(), toolCallId);
  }

  /**
   * The two-message opening every prose caller sends: an optional system prompt and a user prompt.
   *
   * <p>One place builds it, so {@code ChatRequest.of} and every test that calls a transport
   * directly agree about what "no system prompt" means. A null <em>or blank</em> system is no
   * system message at all — which is what {@code OpenAiTransport.chatBody} did with the two loose
   * fields it used to take, and preserving it byte for byte is what keeps this change additive for
   * every slice-2 caller.
   */
  public static List<ChatMessage> conversation(String system, String user) {
    if (user == null || user.isBlank()) {
      throw new IllegalArgumentException("a chat request needs a user prompt");
    }
    return system == null || system.isBlank()
        ? List.of(user(user))
        : List.of(system(system), user(user));
  }
}
