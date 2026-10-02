package io.aeyer.plowshare.client.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The MCP wire contract, driven end to end over in-memory streams.
 *
 * <p>Not a port of anything: Excalibur's MCP surface is FastMCP's, so there is
 * no Python test of the framing to port. The protocol itself is the
 * specification, and the exchange below was checked against a live MCP client
 * on 2026-08-24.
 */
class StdioTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistry registry = new ToolRegistry();

    /** Drives the transport with newline-delimited requests and returns the
     *  response lines. No process spawning, no Docker — this is a pure
     *  protocol test. */
    private List<JsonNode> exchange(String... requests) throws Exception {
        var in = new ByteArrayInputStream(String.join("\n", requests).getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();

        new StdioTransport("0.1.0-test").serve(in, out, registry);

        var parsed = new ArrayList<JsonNode>();
        for (String line : lines(out)) {
            parsed.add(MAPPER.readTree(line));
        }
        return parsed;
    }

    private static List<String> lines(ByteArrayOutputStream out) {
        String written = out.toString(StandardCharsets.UTF_8);
        if (written.isEmpty()) {
            return List.of();
        }
        // Split with -1 and drop the trailing empty field, so a message that
        // forgot its newline — or one that wrote two — changes the count rather
        // than being tidied away by split's default behaviour.
        String[] fields = written.split("\n", -1);
        return List.of(fields).subList(0, fields.length - 1);
    }

    @Test
    void initialize_is_answered_with_server_info_and_tool_capability() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}""");

        assertEquals(1, out.size());
        var result = out.get(0).get("result");
        assertEquals("plowshare", result.get("serverInfo").get("name").asText());
        assertTrue(result.get("capabilities").has("tools"));

        // Not in the plan's version of this test, and the one field a client
        // actually negotiates on: a missing or unexpected protocolVersion is a
        // client that hangs up before it ever asks for the tool list.
        assertEquals("2024-11-05", result.get("protocolVersion").asText());
        assertEquals("2.0", out.get(0).get("jsonrpc").asText());
        assertEquals("0.1.0-test", result.get("serverInfo").get("version").asText());
    }

    /** A notification has no id and must produce no response line. A server that
     *  replies to one desynchronises every client that counts responses. */
    @Test
    void a_notification_produces_no_response() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","method":"notifications/initialized"}""");
        assertTrue(out.isEmpty());
    }

    /** The rule is "no id", not "a method we recognise". An unknown notification
     *  must not draw a method-not-found error either — the client is not waiting
     *  for one, so the error would be read as the answer to whatever it asks
     *  next. `notifications/cancelled` is one a real harness sends unprompted. */
    @Test
    void an_unknown_notification_is_also_answered_with_silence() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""");
        assertTrue(out.isEmpty());
    }

    @Test
    void tools_list_returns_every_registered_tool_with_its_schema() throws Exception {
        registry.register("memory_index", "List every active memory.",
                Map.of("type", "object", "properties", Map.of()), args -> "ok");

        var out = exchange("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");

        var tools = out.get(0).get("result").get("tools");
        assertEquals(1, tools.size());
        assertEquals("memory_index", tools.get(0).get("name").asText());
        assertTrue(tools.get(0).has("inputSchema"));
        assertEquals("List every active memory.", tools.get(0).get("description").asText());
        assertEquals("object", tools.get(0).get("inputSchema").get("type").asText());
    }

    /** Registration order, because `tools/list` is the menu the model reads and
     *  a table that reshuffled between runs would change the prompt for no
     *  reason. */
    @Test
    void tools_are_listed_in_registration_order() throws Exception {
        registry.register("memory_write", "w", Map.of("type", "object"), args -> "ok");
        registry.register("memory_recall", "r", Map.of("type", "object"), args -> "ok");
        registry.register("memory_read", "d", Map.of("type", "object"), args -> "ok");

        var tools = exchange("""
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}""").get(0).get("result").get("tools");

        assertEquals(List.of("memory_write", "memory_recall", "memory_read"),
                List.of(tools.get(0).get("name").asText(),
                        tools.get(1).get("name").asText(),
                        tools.get(2).get("name").asText()));
    }

    @Test
    void an_unknown_method_returns_a_jsonrpc_error_rather_than_closing() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":3,"method":"nonsense"}""");

        assertEquals(3, out.get(0).get("id").asInt());
        assertEquals(-32601, out.get(0).get("error").get("code").asInt());   // method not found
        assertFalse(out.get(0).has("result"));   // never both
    }

    /** A throwing handler must not take the process down, and must not leave a
     *  half-written line on stdout. stdout is the protocol channel: anything on
     *  it that is not a JSON-RPC message corrupts the stream, which is the
     *  single easiest way to break an MCP server — and why logging goes to
     *  stderr. */
    @Test
    void a_handler_that_throws_returns_an_error_result_and_keeps_the_stream_clean() throws Exception {
        registry.register("boom", "Throws.", Map.of("type", "object"), args -> {
            throw new IllegalStateException("the server is unreachable");
        });

        var out = exchange("""
            {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom","arguments":{}}}
            {"jsonrpc":"2.0","id":5,"method":"tools/list"}""");

        assertEquals(2, out.size());                              // the stream survived
        var text = out.get(0).get("result").get("content").get(0).get("text").asText();
        assertTrue(text.contains("unreachable"));                 // the reason reached the caller
        assertEquals(5, out.get(1).get("id").asInt());

        // A *result* carrying isError, and not a JSON-RPC error: harnesses
        // consume transport errors themselves, so a failure reported that way
        // never reaches the model that could act on it.
        assertFalse(out.get(0).has("error"));
        assertTrue(out.get(0).get("result").get("isError").asBoolean());
    }

    /** The handler sees the arguments object, and a successful call carries no
     *  isError — a client that treats a missing flag as false is fine, but one
     *  that reads `isError:false` as an error is not, so we simply omit it. */
    @Test
    void tools_call_passes_arguments_through_to_the_handler() throws Exception {
        var seen = new LinkedHashMap<String, Object>();
        registry.register("memory_recall", "Recall.", Map.of("type", "object"), args -> {
            seen.putAll(args);
            return "one memory";
        });

        var out = exchange("""
            {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"memory_recall",\
            "arguments":{"query":"the WAL retry","limit":3}}}""");

        assertEquals("the WAL retry", seen.get("query"));
        assertEquals(3, seen.get("limit"));
        assertEquals("one memory", out.get(0).get("result").get("content").get(0).get("text").asText());
        assertFalse(out.get(0).get("result").has("isError"));
    }

    /** Absent `arguments` is an empty map, not a crash: a tool with no required
     *  properties is legitimately called bare, and some clients omit the key
     *  entirely rather than sending `{}`. */
    @Test
    void tools_call_with_no_arguments_reaches_the_handler_with_an_empty_map() throws Exception {
        registry.register("memory_index", "Index.", Map.of("type", "object"), args -> "size=" + args.size());

        var out = exchange("""
            {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"memory_index"}}""");

        assertEquals("size=0", out.get(0).get("result").get("content").get(0).get("text").asText());
    }

    /** A tool that was never advertised is the caller's protocol mistake, so it
     *  gets a JSON-RPC error — there is no tool whose output could carry the
     *  complaint — and above all it does not throw the loop. */
    @Test
    void calling_a_tool_that_does_not_exist_is_an_error_not_a_crash() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"memory_forget","arguments":{}}}
            {"jsonrpc":"2.0","id":9,"method":"ping"}""");

        assertEquals(2, out.size());
        assertEquals(-32602, out.get(0).get("error").get("code").asInt());
        assertTrue(out.get(0).get("error").get("message").asText().contains("memory_forget"));
    }

    /** Harnesses use ping as a liveness check. Answering it "method not found"
     *  reads as a server that is up but broken, and the harness tears the
     *  subprocess down. */
    @Test
    void ping_is_answered_with_an_empty_result() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":10,"method":"ping"}""");

        assertTrue(out.get(0).get("result").isObject());
        assertTrue(out.get(0).get("result").isEmpty());
    }

    /** A string id must come back as a string. JSON-RPC allows either spelling,
     *  and a client that sent "a3" and got 0 cannot match the response to the
     *  request it is blocked on. */
    @Test
    void the_id_is_echoed_with_its_json_type_intact() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":"a3","method":"ping"}""");

        assertTrue(out.get(0).get("id").isTextual());
        assertEquals("a3", out.get(0).get("id").asText());
    }

    /** Garbage on stdin is answered and then forgotten. Closing the stream
     *  instead would strand every request queued behind it, and the harness
     *  would report a dead server rather than a bad line. */
    @Test
    void an_unparseable_line_is_a_parse_error_and_the_stream_continues() throws Exception {
        var out = exchange(
                "{not json at all",
                """
                {"jsonrpc":"2.0","id":11,"method":"ping"}""");

        assertEquals(2, out.size());
        assertEquals(-32700, out.get(0).get("error").get("code").asInt());
        assertTrue(out.get(0).get("id").isNull());   // an id that cannot be recovered is null, not absent
        assertEquals(11, out.get(1).get("id").asInt());
    }

    /** Blank lines are skipped rather than answered: some clients flush a
     *  trailing newline, and a parse error for it would be a line on stdout
     *  that nothing is waiting for — which is the desynchronisation this whole
     *  suite exists to prevent. */
    @Test
    void blank_lines_produce_nothing() throws Exception {
        var out = exchange("", "   ", """
            {"jsonrpc":"2.0","id":12,"method":"ping"}""", "");

        assertEquals(1, out.size());
        assertEquals(12, out.get(0).get("id").asInt());
    }

    /** The framing is one message per line, so a tool whose output contains a
     *  newline must not become two lines. It stays escaped inside the JSON
     *  string and arrives whole — the memory bodies Task 11 returns are
     *  multi-line prose, so this is the normal case, not an edge one. */
    @Test
    void multi_line_tool_output_stays_on_one_line() throws Exception {
        registry.register("memory_read", "Read.", Map.of("type", "object"),
                args -> "line one\nline two\n");

        var in = new ByteArrayInputStream("""
            {"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"memory_read","arguments":{}}}"""
                .getBytes(StandardCharsets.UTF_8));
        var out = new ByteArrayOutputStream();
        new StdioTransport("0.1.0-test").serve(in, out, registry);

        String written = out.toString(StandardCharsets.UTF_8);
        assertEquals(1, written.chars().filter(c -> c == '\n').count());
        assertTrue(written.endsWith("\n"));
        assertEquals("line one\nline two\n",
                MAPPER.readTree(written).get("result").get("content").get(0).get("text").asText());
    }

    /** A handler may return something other than a String — Task 11's tools
     *  return records and lists — and it reaches the model as JSON rather than
     *  as a Java toString. */
    @Test
    void a_non_string_return_value_is_rendered_as_json() throws Exception {
        registry.register("memory_index", "Index.", Map.of("type", "object"),
                args -> Map.of("count", 2));

        var out = exchange("""
            {"jsonrpc":"2.0","id":14,"method":"tools/call","params":{"name":"memory_index","arguments":{}}}""");

        assertEquals("{\"count\":2}",
                out.get(0).get("result").get("content").get(0).get("text").asText());
    }

    /** A JSON-RPC *response* is not a request. We never send requests, so one
     *  should never arrive — but answering it with an error would be answering
     *  a response, and two peers that each answer the other's answers never
     *  stop. */
    @Test
    void a_stray_response_on_stdin_is_ignored_rather_than_answered() throws Exception {
        var out = exchange("""
            {"jsonrpc":"2.0","id":15,"result":{}}""");

        assertTrue(out.isEmpty());
    }

    /** Registering the same name twice would leave the model calling a tool
     *  under a description belonging to the one it replaced, with nothing in
     *  the transcript to say so. */
    @Test
    void registering_a_duplicate_tool_name_is_refused() {
        registry.register("memory_write", "First.", Map.of("type", "object"), args -> "ok");

        var thrown = assertThrows(IllegalArgumentException.class, () ->
                registry.register("memory_write", "Second.", Map.of("type", "object"), args -> "ok"));

        assertTrue(thrown.getMessage().contains("memory_write"));
    }
}
