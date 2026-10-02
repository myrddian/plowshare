package io.aeyer.plowshare.client.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The JSON-RPC 2.0 envelope, which is all MCP's stdio framing is: one message
 * per line, requests carry an {@code id}, notifications do not.
 *
 * <p>Envelopes are built as Jackson nodes rather than records so that
 * {@link StdioTransport} can serialise a whole response with no chance of a
 * mapping failure part way through — a half-written line on stdout desynchronises
 * the client for the rest of the session.
 */
public final class JsonRpc {

    /** The only version this transport speaks; every envelope carries it. */
    public static final String VERSION = "2.0";

    /** The line was not JSON at all. Answered with a null id, per the spec: the
     *  id cannot be recovered from something that did not parse. */
    public static final int PARSE_ERROR = -32700;

    /** Parsed, but not shaped like a request — no {@code method}, or not an object. */
    public static final int INVALID_REQUEST = -32600;

    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonRpc() {}

    /**
     * One inbound message.
     *
     * <p>{@code id} stays a {@link JsonNode} rather than becoming a {@code long}
     * or a {@code String}: JSON-RPC permits either, and a client that sent
     * {@code "id":"a3"} and got back {@code "id":0} — or sent {@code 1} and got
     * {@code "1"} — cannot match the response to its request. Echoing the node
     * verbatim is the only spelling that is right for both.
     */
    public record Request(JsonNode id, String method, JsonNode params) {

        /** A notification is exactly "no id". It gets no response line of any
         *  kind, not even an error: a server that answers one leaves every
         *  client that counts responses off by one for the rest of the session. */
        public boolean isNotification() {
            return id == null || id.isNull();
        }
    }

    /** A successful response carrying {@code payload} as its result. */
    public static ObjectNode result(JsonNode id, JsonNode payload) {
        ObjectNode response = envelope(id);
        response.set("result", payload);
        return response;
    }

    /** A failed response. Used for protocol faults only — a tool that throws is
     *  reported inside a successful result, see {@link StdioTransport}. */
    public static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = envelope(id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    /** A fresh object node, for building result payloads. */
    public static ObjectNode object() {
        return NODES.objectNode();
    }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode response = NODES.objectNode();
        response.put("jsonrpc", VERSION);
        // The field is required even when the id is unknown, and it must be
        // present as an explicit null rather than omitted.
        response.set("id", id == null ? NODES.nullNode() : id);
        return response;
    }
}
