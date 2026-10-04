package io.aeyer.plowshare.server.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What counts as a tool call written as text, and what does not.
 *
 * <p>The positive shapes are the one measured 2026-09-27 (entry 123 of {@code
 * cnv_317717703EFBC15E}) and the ways a model varies it; the negatives are what keeps the guard
 * from firing on an answer that merely talks about a tool.
 */
class WrittenCallsTest {

  private static final String ORCHESTRATE = "orchestrate_implement_specification";

  @Test
  void the_measured_call_inside_a_fence_is_a_written_call() {
    String answer =
        "Here is how I'll start it:\n\n```python\n"
            + ORCHESTRATE
            + "({\n  \"request\": \"Create a console-based text RPG ...\",\n"
            + "  \"context\": \"\",\n  \"wait\": false\n})\n```\n\nYou can monitor it with ...";

    assertEquals(Optional.of(ORCHESTRATE), WrittenCalls.writtenCall(answer, List.of(ORCHESTRATE)));
  }

  @Test
  void whitespace_and_a_newline_before_the_brace_still_match() {
    assertEquals(
        Optional.of(ORCHESTRATE),
        WrittenCalls.writtenCall(
            ORCHESTRATE + " (\n  {\"request\": \"x\"})", List.of(ORCHESTRATE)));
    assertEquals(
        Optional.of(ORCHESTRATE),
        WrittenCalls.writtenCall("call " + ORCHESTRATE + "(   {", List.of(ORCHESTRATE)));
  }

  @Test
  void a_call_shape_for_a_tool_this_run_is_not_offered_does_not_match() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall(
            ORCHESTRATE + "({\"request\": \"x\"})", List.of("file_read", "todo_write")));
  }

  @Test
  void a_bare_mention_of_an_offered_tool_does_not_match() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall("use " + ORCHESTRATE + " to start it", List.of(ORCHESTRATE)));
  }

  @Test
  void a_name_and_a_paren_without_a_brace_does_not_match() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall(ORCHESTRATE + "() takes a request", List.of(ORCHESTRATE)));
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall(ORCHESTRATE + "(request) takes a request", List.of(ORCHESTRATE)));
  }

  @Test
  void a_longer_name_that_contains_an_offered_one_is_not_a_call_to_it() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall(
            "my_" + ORCHESTRATE + "_v2({\"request\": \"x\"})", List.of(ORCHESTRATE)));
  }

  /**
   * A method on some object is code about that object, not a call to the run's own tool of the same
   * name: {@code client.search({...})} in an example is not {@code search}.
   */
  @Test
  void a_member_call_is_not_a_call_to_the_tool_of_that_name() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenCall(
            "const hits = client.search({ query: \"x\" });", List.of("search")));
    assertEquals(
        Optional.empty(), WrittenCalls.writtenCall("app.run({ port: 8080 })", List.of("run")));
  }

  @Test
  void the_first_offered_name_that_matches_is_the_one_returned() {
    String both = "todo_write({\"items\": []}) then file_read({\"path\": \"a\"})";

    assertEquals(
        Optional.of("file_read"),
        WrittenCalls.writtenCall(both, List.of("run_command", "file_read", "todo_write")));
    assertEquals(
        Optional.of("todo_write"),
        WrittenCalls.writtenCall(both, List.of("todo_write", "file_read")));
  }

  @Test
  void nothing_offered_or_nothing_said_matches_nothing() {
    assertEquals(Optional.empty(), WrittenCalls.writtenCall(ORCHESTRATE + "({})", List.of()));
    assertEquals(Optional.empty(), WrittenCalls.writtenCall("", List.of(ORCHESTRATE)));
    assertEquals(Optional.empty(), WrittenCalls.writtenCall(null, List.of(ORCHESTRATE)));
  }

  // --- a bare tool name, written as the whole answer ----------------------------------------

  /**
   * Rule 5 (spec 2026-09-29 §3): a reply that is only an offered tool's name is a call it did not
   * make. Bold and backticks are how models dress it.
   */
  @Test
  void a_reply_that_is_only_an_offered_tool_s_name_is_a_written_call() {
    List<String> offered = List.of("todo_read", "orchestration_finish");

    assertEquals(Optional.of("todo_read"), WrittenCalls.bareName("todo_read", offered));
    assertEquals(Optional.of("todo_read"), WrittenCalls.bareName("**todo_read**", offered));
    assertEquals(
        Optional.of("orchestration_finish"),
        WrittenCalls.bareName("`orchestration_finish`\n", offered));
    assertEquals(Optional.of("todo_read"), WrittenCalls.bareName("```\ntodo_read()\n```", offered));
  }

  @Test
  void a_name_in_a_sentence_or_one_not_offered_is_not() {
    List<String> offered = List.of("todo_read");

    assertEquals(Optional.empty(), WrittenCalls.bareName("I will call todo_read next.", offered));
    assertEquals(Optional.empty(), WrittenCalls.bareName("file_read", offered));
    assertEquals(Optional.empty(), WrittenCalls.bareName("", offered));
    assertEquals(Optional.empty(), WrittenCalls.bareName(null, offered));
  }

  // --- the arguments alone, written as the whole answer ------------------------------------

  private static ToolSchema schema(String name, List<String> properties, List<String> required) {
    Map<String, Object> props = new java.util.LinkedHashMap<>();
    properties.forEach(property -> props.put(property, Map.of("type", "string")));
    return new ToolSchema(
        name, name, Map.of("type", "object", "properties", props, "required", required));
  }

  private static final List<ToolSchema> CONDUCTOR =
      List.of(
          schema("todo_read", List.of(), List.of()),
          schema("todo_write", List.of("ops"), List.of("ops")),
          schema("file_read", List.of("path", "offset", "limit"), List.of("path")),
          schema("file_stat", List.of("path"), List.of("path")),
          schema("orchestration_finish", List.of("result"), List.of("result")));

  /**
   * A minimal {@link AgentTool} carrying nothing but a schema, for the tests below that need {@code
   * writtenIn}'s offered map rather than {@code writtenArguments}' bare schema list.
   */
  private record Stub(ToolSchema schema) implements AgentTool {
    @Override
    public String run(String argumentsJson, Home home) {
      throw new UnsupportedOperationException("not called in these tests");
    }
  }

  private static Map<String, AgentTool> offeredMap(List<ToolSchema> schemas) {
    Map<String, AgentTool> offered = new LinkedHashMap<>();
    schemas.forEach(schema -> offered.put(schema.name(), new Stub(schema)));
    return offered;
  }

  /**
   * {@code writtenIn} tries every shape in order: a named call, bare arguments, then a bare name --
   * so a conductor that answers with nothing but {@code **todo_read**} is caught by the third,
   * exactly as it would be by {@link #bareName} alone.
   */
  @Test
  void written_in_also_catches_the_bare_name_shape() {
    assertEquals(
        Optional.of("todo_read"), WrittenCalls.writtenIn("**todo_read**", offeredMap(CONDUCTOR)));
  }

  /**
   * Measured 2026-09-28, orc_318408A44038F859 entries 252, 256 and 260: a conductor answered three
   * turns in a row with nothing but todo_write's arguments — no name, no call — and was failed
   * stuck with every phase built. The name-and-paren shape never saw them.
   */
  @Test
  void an_answer_that_is_only_one_offered_tool_s_arguments_is_a_written_call() {
    String answer =
        "{\n  \"ops\": [\n    {\n      \"op\": \"update\",\n"
            + "      \"id\": \"td_31840AA417E938E2\",\n      \"status\": \"done\"\n    }\n  ]\n}";

    assertEquals(Optional.of("todo_write"), WrittenCalls.writtenArguments(answer, CONDUCTOR));
    assertEquals(
        Optional.of("todo_write"),
        WrittenCalls.writtenArguments("```json\n" + answer + "\n```", CONDUCTOR));
  }

  @Test
  void arguments_that_fit_more_than_one_tool_are_not_guessed_at() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenArguments("{\"path\": \"rpg/combat.py\"}", CONDUCTOR));
    assertEquals(
        Optional.of("file_read"),
        WrittenCalls.writtenArguments("{\"path\": \"rpg/combat.py\", \"limit\": 40}", CONDUCTOR));
  }

  @Test
  void json_that_is_not_the_whole_answer_or_fits_no_tool_is_not_a_written_call() {
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenArguments("Here is the update: {\"ops\": []}", CONDUCTOR),
        "prose around it");
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenArguments("{\"ops\": [], \"why\": \"x\"}", CONDUCTOR),
        "a key no tool takes");
    assertEquals(
        Optional.empty(),
        WrittenCalls.writtenArguments("{\"limit\": 40}", CONDUCTOR),
        "a required key missing");
    assertEquals(Optional.empty(), WrittenCalls.writtenArguments("{}", CONDUCTOR), "nothing in it");
    assertEquals(
        Optional.empty(), WrittenCalls.writtenArguments("{\"ops\": [", CONDUCTOR), "not JSON");
    assertEquals(Optional.empty(), WrittenCalls.writtenArguments("[1, 2]", CONDUCTOR));
    assertEquals(Optional.empty(), WrittenCalls.writtenArguments(null, CONDUCTOR));
  }

  // --- a validator's arguments, held to the same key test --------------------------------

  @Test
  void a_validator_s_arguments_fit_a_tool_by_the_same_key_test() {
    ToolSchema fileRead = schema("file_read", List.of("path", "offset", "limit"), List.of("path"));

    assertTrue(WrittenCalls.fits("{\"path\": \"a.py\", \"limit\": 40}", fileRead));
    assertFalse(WrittenCalls.fits("{\"limit\": 40}", fileRead), "a required key missing");
    assertFalse(
        WrittenCalls.fits("{\"path\": \"a.py\", \"why\": 1}", fileRead), "a key it does not take");
    assertFalse(WrittenCalls.fits("[\"a.py\"]", fileRead), "not an object");
    assertFalse(WrittenCalls.fits("{\"path\": ", fileRead), "not JSON");
    assertFalse(WrittenCalls.fits(null, fileRead));
    assertTrue(
        WrittenCalls.fits("{}", schema("todo_read", List.of(), List.of())),
        "a tool that requires nothing takes nothing");
  }

  @Test
  void one_fence_around_the_whole_answer_is_taken_off() {
    assertEquals("{\"a\": 1}", WrittenCalls.unfenced("```json\n{\"a\": 1}\n```"));
    assertEquals("{\"a\": 1}", WrittenCalls.unfenced("  {\"a\": 1}  "));
    assertEquals("", WrittenCalls.unfenced(null));
  }

  @Test
  void the_way_out_written_as_a_call_is_recognised_and_a_mention_of_it_is_not() {
    assertTrue(WrittenCalls.writesTheWayOut("reply_as_written({})"));
    assertTrue(WrittenCalls.writesTheWayOut("```\nreply_as_written( {} )\n```"));
    assertFalse(WrittenCalls.writesTheWayOut("I would use reply_as_written here."));
    assertFalse(WrittenCalls.writesTheWayOut(null));
  }

  /**
   * Jackson reads the first value and ignores what follows it, so {@code {..} {..}} parsed as one
   * object: the whole string would have run as the call's arguments and gone into the history,
   * which a strict server rejects. Two values are not a tool's arguments.
   */
  @Test
  void json_with_trailing_tokens_is_not_one_tool_s_arguments() {
    assertEquals(
        Optional.empty(), WrittenCalls.writtenArguments("{\"ops\": []} {\"x\": 1}", CONDUCTOR));
  }
}
