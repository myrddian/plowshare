package io.aeyer.plowshare.server.llm.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.ToolCall;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a request refuses to be built as.
 *
 * <p>Every one of these is a failure that would otherwise surface at the endpoint, or worse, as a
 * successful call against the wrong model.
 */
class RequestTest {

  @Test
  void a_request_with_no_specifier_is_not_a_request() {
    // A blank specifier would route to whichever pool declared "" — that is,
    // to none, at the endpoint, several layers from the call site that
    // forgot it. The spec's rule is that an unsatisfiable specifier fails
    // loudly; an absent one must not even get that far.
    assertThrows(IllegalArgumentException.class, () -> ChatRequest.of("", "sys", "hello"));
    assertThrows(IllegalArgumentException.class, () -> ChatRequest.of(null, "sys", "hello"));
    assertThrows(IllegalArgumentException.class, () -> EmbeddingRequest.of("  ", List.of("hello")));
  }

  /**
   * What a message refuses to be built as.
   *
   * <p>The role combinations are checked in {@link ChatMessage} rather than in the transport
   * because a malformed message is a call-site mistake, and the transport is several frames and one
   * lane thread away from whoever made it. Each of these would otherwise reach the endpoint as a
   * request it rejects with a message about a field the caller never wrote.
   */
  @Test
  void a_message_refuses_the_shapes_that_are_not_messages() {
    // A blank system or user turn is not the same input as no turn at all,
    // and a small model notices the difference — so the absence is spelled
    // by leaving the message out, not by sending an empty one.
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.system("  "));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user(""));
    // Null as well as blank, and IllegalArgumentException for both: the
    // argument this file makes about the user prompt, one level down.
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.user(null));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.system(null));
    // A tool message the model cannot place.
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("", "a result"));
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool(null, "a result"));
    // A blank tool result reads to a model as a tool that does not work.
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.tool("c1", "   "));
    // An assistant turn with neither content nor calls is a turn that did
    // not happen; with either one it is a real turn.
    assertThrows(IllegalArgumentException.class, () -> ChatMessage.assistant("", List.of()));
    assertEquals(
        "", ChatMessage.assistant("", List.of(new ToolCall("c1", "memory_read", "{}"))).content());
    assertEquals("said", ChatMessage.assistant("said", List.of()).content());
    // Null content on an assistant turn becomes an empty string, because
    // JSON needs one; anything else travels verbatim, whitespace included.
    // Stripping it would show the model something other than its own turn.
    assertEquals(
        "",
        ChatMessage.assistant(null, List.of(new ToolCall("c1", "memory_read", "{}"))).content());
    assertEquals("  ", ChatMessage.assistant("  ", List.of()).content());
    // A role cannot carry another role's field.
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.USER, "hello", List.of(), "c1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatMessage(
                ChatMessage.Role.USER, "hello", List.of(new ToolCall("c1", "t", "{}")), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChatMessage(ChatMessage.Role.ASSISTANT, "said", List.of(), "c1"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ChatMessage(
                ChatMessage.Role.TOOL, "a result", List.of(new ToolCall("c1", "t", "{}")), "c1"));
    assertThrows(NullPointerException.class, () -> new ChatMessage(null, "hello", List.of(), null));
  }

  /**
   * A conversation of nothing but system prompts is a call with nothing to answer — the successor
   * to "a chat request needs a user prompt" once the two loose fields became a list.
   */
  @Test
  void a_chat_request_with_nothing_to_answer_is_not_a_request() {
    assertThrows(IllegalArgumentException.class, () -> ChatRequest.of("fast", List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ChatRequest.of("fast", List.of(ChatMessage.system("be terse"))));
  }

  /**
   * A tool message must answer a call some earlier assistant message asked for.
   *
   * <p>Conversation shape, which is this record's to check because a {@link ChatMessage} cannot see
   * its neighbours. It exists because the turn loop got it wrong: it minted a stand-in id for a
   * call that arrived without one, put the stand-in on the {@code tool} message, and left the
   * {@code assistant} turn declaring the empty id it was given. A strict OpenAI-compatible server
   * rejects that conversation — and rejects it on the <em>next</em> request, so the run would have
   * ended unavailable at a point far from the mistake, for a reason nothing in the message would
   * explain.
   */
  @Test
  void a_tool_message_must_answer_a_call_something_asked_for() {
    IllegalArgumentException orphan =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ChatRequest.of(
                    "fast", List.of(ChatMessage.user("go"), ChatMessage.tool("c1", "a result"))));
    assertTrue(orphan.getMessage().contains("c1"), orphan.getMessage());

    // The id has to match, not merely exist: a conversation carrying one
    // call and a result filed under a different id is the exact bug.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.user("go"),
                    ChatMessage.assistant("", List.of(new ToolCall("c1", "t", "{}"))),
                    ChatMessage.tool("c2", "a result"))));

    // Order matters too: the call must come first, or the model has nothing
    // to attach the result to when it reads them in sequence.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.user("go"),
                    ChatMessage.tool("c1", "a result"),
                    ChatMessage.assistant("", List.of(new ToolCall("c1", "t", "{}"))))));

    // And the well-formed shape is accepted, including a batch of two.
    assertEquals(
        4,
        ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.user("go"),
                    ChatMessage.assistant(
                        "", List.of(new ToolCall("c1", "t", "{}"), new ToolCall("c2", "t", "{}"))),
                    ChatMessage.tool("c1", "first"),
                    ChatMessage.tool("c2", "second")))
            .messages()
            .size());
  }

  /**
   * At most one system message, and it is the first.
   *
   * <p><b>The rule a live node taught this project.</b> A conversation held against {@code
   * qwen3.5-9b} until its history compacted ended every turn after the fold {@code UNAVAILABLE},
   * the endpoint saying so itself: "System message must be at the beginning". {@code Compaction}
   * introduces a seam as a system message and {@code JobRuntime.opening} put the agent's prompt in
   * front of it, so the seam landed at index one and every later turn was refused. The fold is
   * permanent, so the conversation never recovered.
   *
   * <p>The check lives in the constructor because <b>no transport double in this repository and no
   * MockWebServer validates a message list</b>, so a request no backend accepts was green across
   * the whole suite. Here, every test that builds a request is an instrument for it.
   */
  @Test
  void a_chat_request_carries_at_most_one_system_message_and_it_comes_first() {
    // The shape that took production down: the agent's prompt, then a seam,
    // then the conversation.
    IllegalArgumentException late =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ChatRequest.of(
                    "fast",
                    List.of(
                        ChatMessage.system("You answer questions about a codebase."),
                        ChatMessage.system("Turns 1 to 3 were summarised. The summary: …"),
                        ChatMessage.user("and then?"))));
    assertTrue(
        late.getMessage().contains("position 1"),
        "the message says where the second one was — " + late.getMessage());

    // And anywhere else, not merely adjacent to the first: a system message
    // spliced in mid-conversation is the same refusal at the endpoint.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.system("the prompt"),
                    ChatMessage.user("go"),
                    ChatMessage.assistant("done", List.of()),
                    ChatMessage.system("a note"),
                    ChatMessage.user("and then?"))));

    // A system message that is not at the front at all, with no other one.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ChatRequest.of("fast", List.of(ChatMessage.user("go"), ChatMessage.system("a note"))));
  }

  /**
   * The accepted side, and it is built the way production builds it.
   *
   * <p>Standing check 2: the fixture holding the accepted side of a rule must not be written
   * against the rule. So none of these is a list composed to satisfy the check — each is a shape
   * this server actually sends. {@code ChatMessage.conversation} is the production helper for the
   * two-message opening; the third is the shape {@code JobRuntime.opening} now produces for a turn
   * in a compacted conversation, which is the case the rule exists for and which must still be
   * legal.
   */
  @Test
  void the_shapes_this_server_actually_sends_are_all_accepted() {
    assertEquals(
        2,
        ChatRequest.of("fast", ChatMessage.conversation("a prompt", "a task")).messages().size(),
        "the two-message opening every one-shot run sends");

    assertEquals(
        1,
        ChatRequest.of("fast", ChatMessage.conversation(null, "a task")).messages().size(),
        "and the same with no system message at all, which a definition with no body"
            + " produces — zero is ordinary and is not what this rule is about");

    assertEquals(
        4,
        ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.system(
                        "a prompt"
                            + System.lineSeparator()
                            + System.lineSeparator()
                            + "Turns 1 to 3 were summarised. The summary: …"),
                    ChatMessage.user("what does it retry on?"),
                    ChatMessage.assistant("TransientException.", List.of()),
                    ChatMessage.user("and then?")))
            .messages()
            .size(),
        "a turn in a compacted conversation: the seam folded into the one system"
            + " message at the front, and the history after it untouched");
  }

  /**
   * An assistant turn no result answers is allowed, knowingly.
   *
   * <p>The converse of the rule above, and deliberately not enforced. The turn loop always answers
   * every call it was given, so nothing this project builds needs it — but the shape is legitimate
   * for a caller that means it, such as a loop that stops between issuing calls and running them,
   * and refusing it would forbid a turn loop this task did not write. Recorded as a decision rather
   * than left as an omission a reader has to guess about.
   */
  @Test
  void an_assistant_call_that_nothing_answers_is_allowed() {
    assertEquals(
        2,
        ChatRequest.of(
                "fast",
                List.of(
                    ChatMessage.user("go"),
                    ChatMessage.assistant("", List.of(new ToolCall("c1", "t", "{}")))))
            .messages()
            .size());
  }

  /**
   * The conversation is copied, for the reason the tool list and the embedding batch are: a request
   * may already be queued in a pool when the caller appends the next turn to the list it passed,
   * and the model would then be sent a history nobody chose.
   */
  @Test
  void a_chat_request_does_not_share_the_caller_s_conversation() {
    List<ChatMessage> mutable = new ArrayList<>(List.of(ChatMessage.user("hello")));
    ChatRequest request = ChatRequest.of("fast", mutable);
    mutable.add(ChatMessage.user("and another thing"));
    assertEquals(1, request.messages().size());
  }

  /**
   * {@code of(specifier, system, user)} still builds the two-message opening every prose caller
   * sends, and still omits a blank system prompt. This is the source-level half of the transport's
   * byte-identical guard.
   */
  @Test
  void the_prose_entry_point_still_builds_the_two_message_opening() {
    assertEquals(
        List.of(ChatMessage.system("be terse"), ChatMessage.user("hello")),
        ChatRequest.of("fast", "be terse", "hello").messages());
    assertEquals(
        List.of(ChatMessage.user("hello")), ChatRequest.of("fast", null, "hello").messages());
    assertEquals(
        List.of(ChatMessage.user("hello")), ChatRequest.of("fast", "   ", "hello").messages());
  }

  @Test
  void a_chat_request_with_no_user_prompt_is_not_a_request() {
    assertThrows(IllegalArgumentException.class, () -> ChatRequest.of("fast", "sys", ""));
    // Null as well as blank, and asserted separately because a guard
    // spelled `user != null && user.isBlank()` passes the blank case and
    // lets null through to NullPointerException. One caller mistake must
    // not leave this record by two different types. Today both are
    // unclassified by ApiExceptionHandler and answered 500 — the 400 rule
    // for IllegalArgumentException was removed in slice 1, and that it ever
    // existed was the bug BadRequestException was introduced to fix. What
    // makes the difference is later: when an HTTP surface accepts a chat
    // request it will translate IllegalArgumentException at one boundary
    // site, the way MemoryController.resolveHome does for Home.of. That
    // translation converts the guarded spelling and cannot see the
    // NullPointerException, which would go on reading "the server is
    // broken" for a prompt the caller simply forgot to send.
    assertThrows(IllegalArgumentException.class, () -> ChatRequest.of("fast", "sys", null));
  }

  /**
   * No budget means "use the pool's default", and the absence has to be representable — a zero
   * Duration would mean "fail immediately", which is a different and much worse default.
   */
  @Test
  void a_request_carries_no_budget_until_one_is_given() {
    assertNull(ChatRequest.of("fast", null, "hello").submitTimeout());
    assertEquals(
        Duration.ofSeconds(2),
        ChatRequest.of("fast", null, "hello").withBudget(Duration.ofSeconds(2)).submitTimeout());
  }

  /**
   * The caller's list is copied. An embedding batch handed to the pool and then mutated by the
   * caller would embed one set of strings and attach the vectors to another — positional,
   * undetectable, and silent.
   */
  @Test
  void an_embedding_request_does_not_share_the_caller_s_list() {
    List<String> mutable = new ArrayList<>(List.of("one"));
    EmbeddingRequest request = EmbeddingRequest.of("nomic", mutable);
    mutable.add("two");
    assertEquals(List.of("one"), request.input());
  }

  @Test
  void an_embedding_request_with_nothing_to_embed_is_rejected() {
    // Not the same as EmbeddingClient.embedAll(List.of()), which is an
    // ordinary first-run state and returns without a round trip. By the time
    // a request object exists, a caller has decided to call the model.
    assertThrows(IllegalArgumentException.class, () -> EmbeddingRequest.of("nomic", List.of()));
    // And null, which is the same mistake and must not be a different
    // exception: List.copyOf answers a null batch with
    // NullPointerException, so without an explicit check ahead of the copy
    // one caller mistake leaves this record two ways, and only the
    // IllegalArgumentException spelling can be translated to a 400 by the
    // boundary site a chat surface will need. Both are 500 today; only one
    // of them is still a 500 once that site exists.
    assertThrows(IllegalArgumentException.class, () -> EmbeddingRequest.of("nomic", null));
    // A list that contains null is the same problem one level down, and
    // List.copyOf rejects it the same unhelpful way. Note that the guard
    // cannot be written `input.contains(null)`: List.of(...).contains(null)
    // itself throws NullPointerException, so the check meant to prevent one
    // would be the thing that threw it.
    List<String> withNull = new ArrayList<>();
    withNull.add("one");
    withNull.add(null);
    assertThrows(IllegalArgumentException.class, () -> EmbeddingRequest.of("nomic", withNull));
  }

  /**
   * JSON has no encoding for NaN or an infinity, so a non-finite temperature cannot reach the
   * endpoint as the number it is: Jackson writes it as a quoted string by default, which an
   * OpenAI-compatible server rejects as a type error naming the field and nothing about where the
   * value came from. Non-finite only, and deliberately no range check: backends disagree about the
   * ceiling, and llama.cpp reads a temperature at or below zero as greedy sampling rather than as
   * an error. Refusing a value the local server would have honoured is the worse of the two
   * mistakes, so the guard covers what no endpoint can read and stops there.
   *
   * <p>The guard moved to {@code Sampling}, where the value is now written, which is one layer
   * earlier than this record: a temperature reaching a request has already passed through a profile
   * file or an agent file, and failing at whichever of those wrote it names something an operator
   * can edit.
   */
  @Test
  void a_temperature_that_has_no_json_representation_is_rejected() {
    assertThrows(IllegalArgumentException.class, () -> Sampling.NONE.withTemperature(Double.NaN));
    assertThrows(
        IllegalArgumentException.class,
        () -> Sampling.NONE.withTemperature(Double.POSITIVE_INFINITY));
  }

  /**
   * The prose call sends no sampling parameters at all.
   *
   * <p><b>This is the behaviour change at the heart of the sampling work, and it is asserted rather
   * than described.</b> {@code ChatRequest.of} hardcoded {@code 0.0} on every call in the server;
   * nobody chose that number, and it was measured driving the ingest cascade into deterministic
   * repetition loops on a model whose vendor forbids greedy decoding in as many words. Sending
   * nothing hands the decision to the endpoint's own resolution -- the model's {@code model.yaml}
   * carries its vendor's values -- which is strictly better than any constant this record could
   * hold.
   */
  @Test
  void a_request_nobody_gave_sampling_to_carries_none() {
    assertEquals(Sampling.NONE, ChatRequest.of("fast", null, "hello").sampling());
    assertEquals(Sampling.NONE, ChatRequest.of("fast", List.of(ChatMessage.user("hi"))).sampling());
    assertTrue(ChatRequest.of("fast", null, "hello").sampling().isEmpty());
  }

  @Test
  void usage_is_a_value_and_never_a_null() {
    // Compared against a separately constructed instance, not against
    // itself: `assertEquals(UNKNOWN, UNKNOWN)` holds for any type at all,
    // including one whose equality is identity, and a ledger that keys or
    // dedupes on usage would then never match two reports of the same cost.
    assertEquals(TokenUsage.of(null, null, null), TokenUsage.UNKNOWN);
    assertNull(TokenUsage.UNKNOWN.promptTokens());
    assertNull(TokenUsage.UNKNOWN.completionTokens());
    assertNull(TokenUsage.UNKNOWN.totalTokens());
  }

  /**
   * No tools means an empty list and never a null. The transport iterates this without a guard of
   * its own, so a null would arrive as a NullPointerException on a lane thread, in a stack that
   * names the pool rather than the caller that left it out.
   */
  @Test
  void a_chat_request_carries_no_tools_until_some_are_given() {
    assertEquals(List.of(), ChatRequest.of("fast", null, "hello").tools());
  }

  /**
   * Every wither carries the conversation across too — the same hazard as the tools, and the one a
   * message list newly introduced: a wither that dropped it would send the model a request with no
   * history and no error, which reads exactly like a model that forgot what it was doing.
   */
  @Test
  void the_wither_methods_carry_the_conversation_across() {
    ChatRequest request =
        ChatRequest.of(
            "fast",
            List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistant("", List.of(new ToolCall("c1", "t", "{}"))),
                ChatMessage.tool("c1", "a result")));
    assertEquals(3, request.withBudget(Duration.ofSeconds(2)).messages().size());
    assertEquals(3, request.withSampling(Sampling.NONE.withTemperature(0.7d)).messages().size());
    assertEquals(3, request.withTools(List.of()).messages().size());
  }

  /**
   * {@code withBudget} and {@code withSampling} each rebuild the record by listing every component,
   * so a component added later is dropped by whichever of them the author forgot. Silently, and as
   * a model that was offered no tools rather than as an error — which is the same shape of failure
   * as a pool being handed the wrong prompt, and far harder to see.
   */
  @Test
  void the_wither_methods_carry_the_tools_across() {
    ChatRequest request =
        ChatRequest.of("fast", null, "hello")
            .withTools(List.of(ToolSchema.from("memory_recall", "d", Map.of())));
    assertEquals(1, request.withBudget(Duration.ofSeconds(2)).tools().size());
    assertEquals(1, request.withSampling(Sampling.NONE.withTemperature(0.7d)).tools().size());
  }

  /**
   * The caller's list is copied, for the reason the embedding batch is: a request may already be
   * queued in a pool when the caller mutates the list it passed, and the model would then be
   * offered a set of tools nobody chose.
   */
  @Test
  void a_chat_request_does_not_share_the_caller_s_tool_list() {
    List<ToolSchema> mutable = new ArrayList<>();
    mutable.add(ToolSchema.from("memory_recall", "d", Map.of()));
    ChatRequest request = ChatRequest.of("fast", null, "hello").withTools(mutable);
    mutable.clear();
    assertEquals(1, request.tools().size());
  }

  /**
   * And a schema copies its parameter map, which is the same hazard one level down: the map is the
   * JSON Schema the model is shown, and a caller reusing a builder map would rewrite a schema
   * already on its way to a transport.
   */
  @Test
  void a_tool_schema_does_not_share_the_caller_s_parameter_map() {
    Map<String, Object> mutable = new LinkedHashMap<>();
    mutable.put("type", "object");
    ToolSchema schema = ToolSchema.from("memory_recall", "d", mutable);
    mutable.put("type", "array");
    assertEquals("object", schema.parameters().type().values().getFirst());
  }

  /** Property order is retained while schema keywords use a stable typed declaration order. */
  @Test
  void a_tool_schema_keeps_property_order() throws Exception {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(
        "question", Map.of("type", "string", "minLength", 1, "examples", List.of("why")));
    properties.put("limit", Map.of("type", "integer"));
    properties.put("project", Map.of("type", "string"));
    ToolSchema schema =
        ToolSchema.from(
            "memory_recall",
            "d",
            Map.of("type", "object", "properties", properties, "required", List.of("question")));
    assertEquals(
        List.of("question", "limit", "project"),
        List.copyOf(schema.parameters().properties().keySet()));
    var rendered =
        new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(schema.parameters());
    assertEquals("string", rendered.path("properties").path("question").path("type").textValue());
    assertEquals(1, rendered.path("properties").path("question").path("minLength").intValue());
    assertEquals(
        "why", rendered.path("properties").path("question").path("examples").get(0).textValue());
    assertThrows(
        UnsupportedOperationException.class, () -> schema.parameters().properties().clear());
  }

  /**
   * A schema may not carry a null, at any depth.
   *
   * <p>{@code Map.copyOf} refused these and a {@code LinkedHashMap} does not, so {@code ToolSchema}
   * refuses them itself — and the two halves are worth separating, because measured against Jackson
   * 2.17.2 they do not behave alike. A null key throws {@code JsonMappingException} on a lane
   * thread at the first call that offers the tool. <b>A null value throws nothing</b>: it
   * serialises as {@code {"k":null}} and reaches the model inside the schema it is being asked to
   * satisfy, which is why the second assertion here matters more than the first.
   *
   * <p>Nested and not merely top-level, because a JSON Schema's content is nested: {@code
   * properties} is a map of maps, and a shallow guard passes every schema whose null is where a
   * null actually gets written.
   */
  @Test
  void a_tool_schema_may_not_carry_a_null_at_any_depth() {
    Map<String, Object> nullValue = new LinkedHashMap<>();
    nullValue.put("description", null);
    assertThrows(
        IllegalArgumentException.class, () -> ToolSchema.from("memory_recall", "d", nullValue));

    Map<String, Object> nullKey = new LinkedHashMap<>();
    nullKey.put(null, "object");
    assertThrows(
        IllegalArgumentException.class, () -> ToolSchema.from("memory_recall", "d", nullKey));

    Map<String, Object> nested = new LinkedHashMap<>();
    Map<String, Object> question = new LinkedHashMap<>();
    question.put("type", null);
    nested.put("properties", Map.of("question", question));
    IllegalArgumentException deep =
        assertThrows(
            IllegalArgumentException.class, () -> ToolSchema.from("memory_recall", "d", nested));
    // The path, because "a null value" in a nested structure sends the
    // reader through the whole of it looking for which field.
    assertTrue(deep.getMessage().contains("question.type"), deep.getMessage());

    Map<String, Object> inAList = new LinkedHashMap<>();
    List<Object> required = new ArrayList<>();
    required.add(null);
    inAList.put("required", required);
    assertThrows(
        IllegalArgumentException.class, () -> ToolSchema.from("memory_recall", "d", inAList));
  }

  /**
   * And the nested maps are copied, not shared — otherwise the immutability the record claims stops
   * one level down, where a caller reusing a builder map rewrites a schema already on its way to a
   * transport.
   */
  @Test
  void a_tool_schema_copies_its_nested_maps_too() {
    Map<String, Object> question = new LinkedHashMap<>();
    question.put("type", "string");
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("properties", Map.of("question", question));

    ToolSchema schema = ToolSchema.from("memory_recall", "d", parameters);
    question.put("type", "integer");

    assertEquals(
        "string", schema.parameters().properties().get("question").type().values().getFirst());
  }

  /**
   * A completion copies the tool calls it is handed, for the reason ChatRequest copies its tools:
   * it is built on a lane thread out of a list the transport still holds, and read on the caller's.
   * Untested until a review pointed out that ChatRequest's identical guard was pinned and this one
   * was not — dropping it left the whole server suite green.
   */
  @Test
  void a_completion_does_not_share_the_transport_s_tool_call_list() {
    List<ToolCall> mutable = new ArrayList<>();
    mutable.add(new ToolCall("c1", "memory_recall", "{}"));
    Completion completion = new Completion("", "tool_calls", TokenUsage.UNKNOWN, mutable);
    mutable.clear();
    assertEquals(1, completion.toolCalls().size());
  }
}
