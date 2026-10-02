package io.aeyer.plowshare.client.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP over stdio: JSON-RPC 2.0, one message per line, on the streams the
 * harness handed this process.
 *
 * <p><b>stdout is the protocol channel.</b> Anything written to it that is not
 * a JSON-RPC message corrupts the stream, and the symptom is a harness that
 * looks broken rather than a server that looks broken. Nothing in this package
 * prints; logging goes to stderr, which is what {@code logback.xml} in this
 * module exists to guarantee.
 *
 * <p>Three methods are enough to be a working MCP server — {@code initialize},
 * {@code tools/list}, {@code tools/call} — plus {@code ping}, which harnesses
 * use as a liveness check and which would otherwise come back "method not
 * found" and read as a dead server.
 *
 * <p>Implemented directly rather than through an SDK: that is the whole of the
 * protocol we need, and the wire format below was checked against a live MCP
 * client on 2026-08-24.
 */
public final class StdioTransport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    /**
     * The one MCP revision this transport implements. The spec says a server
     * that supports the version the client asked for must answer with that same
     * version, and otherwise must answer with one it does support — since we
     * support exactly one, answering with it unconditionally satisfies both
     * halves, and lets the client decide whether it can live with the answer.
     */
    public static final String PROTOCOL_VERSION = "2024-11-05";

    /** The name the harness shows for this server, and the prefix it puts on
     *  every tool it exposes to the model. */
    public static final String SERVER_NAME = "plowshare";

    private static final TypeReference<Map<String, Object>> ARGUMENTS =
            new TypeReference<Map<String, Object>>() {};

    private final ObjectMapper mapper = new ObjectMapper();
    private final String serverVersion;

    public StdioTransport() {
        this(defaultVersion());
    }

    public StdioTransport(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    /**
     * Read requests from {@code in} until it ends, writing one response line to
     * {@code out} for each one that is not a notification.
     *
     * <p>Returns on end of input rather than throwing: stdin closing is how a
     * harness says it is finished with this subprocess, and it is the normal
     * way an MCP server's life ends.
     *
     * <p>Does not close {@code out}. It is this process's stdout, and closing a
     * stream someone else owns turns a clean shutdown into an exception in
     * whatever writes next.
     */
    public void serve(InputStream in, OutputStream out, ToolRegistry registry) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        String line;
        while ((line = reader.readLine()) != null) {
            // A bare newline is not a parse error worth answering: some clients
            // flush a trailing one, and replying with an id-less error to it
            // would put a line on stdout that nothing is waiting for.
            if (line.isBlank()) {
                continue;
            }
            ObjectNode response = handle(line, registry);
            if (response != null) {
                write(writer, response);
            }
        }
    }

    /** @return the response to send, or {@code null} for a message that gets none. */
    private ObjectNode handle(String line, ToolRegistry registry) {
        JsonNode message;
        try {
            message = mapper.readTree(line);
        } catch (JsonProcessingException e) {
            log.warn("unparseable line on stdin: {}", e.getOriginalMessage());
            return JsonRpc.error(null, JsonRpc.PARSE_ERROR, "invalid JSON: " + e.getOriginalMessage());
        }

        if (!message.isObject()) {
            // Includes JSON-RPC batches, which arrive as an array. MCP does not
            // use them, and a half-implemented batch is worse than a refusal.
            return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "a request must be a JSON object");
        }

        if (!message.path("method").isTextual()) {
            if (message.has("result") || message.has("error")) {
                // This is a *response*, not a request. We never send requests of
                // our own, so one should never arrive — but answering it with an
                // error would be answering a response, which is how two peers
                // start bouncing messages at each other forever.
                log.warn("ignoring an unexpected JSON-RPC response on stdin");
                return null;
            }
            return JsonRpc.error(message.get("id"), JsonRpc.INVALID_REQUEST, "no method");
        }

        var request = new JsonRpc.Request(
                message.get("id"), message.get("method").asText(), message.path("params"));

        if (request.isNotification()) {
            // No response line, whatever the method — an unknown notification is
            // still a notification, and the spec forbids answering one even to
            // say so. `notifications/initialized` is the one we actually expect.
            log.debug("notification: {}", request.method());
            return null;
        }

        try {
            return dispatch(request, registry);
        } catch (RuntimeException e) {
            // The loop must survive anything a single message can do to it. A
            // client whose third request kills the server never learns why,
            // because the error went to a stream nobody was reading.
            log.warn("failed to handle {}", request.method(), e);
            return JsonRpc.error(request.id(), JsonRpc.INTERNAL_ERROR, describe(e));
        }
    }

    private ObjectNode dispatch(JsonRpc.Request request, ToolRegistry registry) {
        return switch (request.method()) {
            case "initialize" -> JsonRpc.result(request.id(), initializeResult());
            case "ping" -> JsonRpc.result(request.id(), JsonRpc.object());
            case "tools/list" -> JsonRpc.result(request.id(), toolsList(registry));
            case "tools/call" -> toolsCall(request, registry);
            default -> JsonRpc.error(
                    request.id(), JsonRpc.METHOD_NOT_FOUND, "unknown method: " + request.method());
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = JsonRpc.object();
        result.put("protocolVersion", PROTOCOL_VERSION);
        // An empty `tools` object is the declaration that this server has tools
        // at all — without it a client never calls `tools/list`, and the model
        // sees a server with nothing on it. Empty rather than
        // `{"listChanged":true}`: our table is fixed at startup and we send no
        // list-changed notifications, so claiming otherwise would be a lie a
        // client could act on.
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", serverVersion);
        return result;
    }

    private ObjectNode toolsList(ToolRegistry registry) {
        ObjectNode result = JsonRpc.object();
        ArrayNode tools = result.putArray("tools");
        for (ToolRegistry.Tool tool : registry.tools()) {
            ObjectNode entry = tools.addObject();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            // The field is `inputSchema`, camel-cased, and a client that does not
            // find it treats the tool as taking no arguments at all.
            entry.set("inputSchema", mapper.valueToTree(tool.inputSchema()));
        }
        return result;
    }

    private ObjectNode toolsCall(JsonRpc.Request request, ToolRegistry registry) {
        JsonNode params = request.params();
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            return JsonRpc.error(request.id(), JsonRpc.INVALID_PARAMS, "tools/call needs a tool name");
        }

        ToolRegistry.Tool tool = registry.find(name).orElse(null);
        if (tool == null) {
            // A protocol error, not a tool error: the caller asked for something
            // that was never advertised, so there is no tool whose output could
            // carry the complaint.
            return JsonRpc.error(request.id(), JsonRpc.INVALID_PARAMS, "unknown tool: " + name);
        }

        try {
            Object value = tool.handler().apply(arguments(params.path("arguments")));
            return JsonRpc.result(request.id(), content(render(value), false));
        } catch (Exception e) {
            // Deliberately a *successful* JSON-RPC result carrying isError, not a
            // JSON-RPC error: harnesses swallow transport errors before the model
            // ever sees them, so "the server is unreachable" reported that way
            // reaches the log and nothing else. Put in the result, the model
            // reads the reason and can say so, or retry.
            //
            // Exception and not Throwable: an OutOfMemoryError is not something
            // to answer politely and carry on from.
            log.warn("tool {} failed", name, e);
            return JsonRpc.result(request.id(), content(describe(e), true));
        }
    }

    /** The MCP result shape for a tool call: a list of content blocks. */
    private ObjectNode content(String text, boolean isError) {
        ObjectNode result = JsonRpc.object();
        ObjectNode block = result.putArray("content").addObject();
        block.put("type", "text");
        block.put("text", text);
        if (isError) {
            result.put("isError", true);
        }
        return result;
    }

    private Map<String, Object> arguments(JsonNode node) {
        if (!node.isObject()) {
            // Absent arguments are an empty object, not a failure: a tool whose
            // schema has no required properties is legitimately called bare.
            return Map.of();
        }
        return mapper.convertValue(node, ARGUMENTS);
    }

    /**
     * A handler's return value as tool output. Strings pass through untouched —
     * a tool that already formatted prose for the model must not have it
     * re-quoted — and everything else is rendered as JSON.
     */
    private String render(Object value) throws JsonProcessingException {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return mapper.writeValueAsString(value);
    }

    /** The message a caller should read. Falls back to the type when a throwable
     *  carries no message, which is how an NPE would otherwise arrive as "null".
     *  The stack trace goes to the log, on stderr, for whoever is debugging. */
    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.toString() : message;
    }

    private void write(BufferedWriter writer, ObjectNode response) throws IOException {
        // Serialised in full before anything reaches the stream: a failure part
        // way through would leave a fragment on stdout that no client can
        // resynchronise from. (Responses are built from JsonNodes, so this
        // should not fail — the ordering is what makes that guarantee cheap.)
        String json = mapper.writeValueAsString(response);

        writer.write(json);
        // '\n' and not System.lineSeparator(): the framing is newline-delimited
        // JSON, and a "\r\n" on Windows would put a stray carriage return inside
        // some clients' idea of the message.
        writer.write('\n');
        // Flushed per message, because the client is blocked waiting for this
        // line. A response sitting in an unflushed buffer is a deadlock that
        // presents as a hung harness.
        writer.flush();
    }

    /** The jar's Implementation-Version when there is one; a running-from-source
     *  placeholder otherwise. Only ever shown to the harness in `initialize`. */
    private static String defaultVersion() {
        String version = StdioTransport.class.getPackage().getImplementationVersion();
        return version == null ? "0.0.0-dev" : version;
    }
}
