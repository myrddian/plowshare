package io.aeyer.plowshare.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.client.mcp.StdioTransport;
import io.aeyer.plowshare.client.mcp.ToolRegistry;
import io.aeyer.plowshare.protocol.Home;
import io.aeyer.plowshare.protocol.Memory;
import io.aeyer.plowshare.protocol.Provenance;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Golden contract for a replacement MCP adapter. No server, model or file claim is opened. */
class LegacyMcpCompatibilityTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Path GOLDEN = Path.of("src/test/resources/compatibility/legacy-mcp.json");
    private static final String MISSING_ROOT = "/__plowshare_compatibility_missing_root__";
    private static final UUID DOCUMENT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void legacy_contract_matches_reviewed_fixture() throws Exception {
        assertFalse(Files.exists(Path.of(MISSING_ROOT)), "fixture must never claim a real directory");
        Recording server = new Recording();
        try (ClientPresence presence = new ClientPresence(server.proxy(), null)) {
            ToolRegistry registry = PlowshareClient.tools(server.proxy(), presence);
            ObjectNode actual = JSON.createObjectNode();
            actual.put("formatVersion", 1);
            actual.put("provenance", "Java registry and stdio transport; deterministic synthetic ServerClient responses; no live server");
            actual.set("initialize", exchange(registry, request("initialize", Map.of("protocolVersion", StdioTransport.PROTOCOL_VERSION))));
            actual.set("toolsList", exchange(registry, request("tools/list", Map.of())));
            actual.set("legacyDeclarations", JSON.valueToTree(Capabilities.ALL));
            assertEquals(35, registry.tools().size(), "review migration inventory when legacy tools change");
            ArrayNode cases = actual.putArray("cases");
            for (ToolRegistry.Tool tool : registry.tools()) {
                Map<String, Object> minimal = arguments(tool, false);
                if (tool.name().equals("client_root_project_here")) {
                    add(cases, registry, server, tool.name(), "missing-root", minimal, "ok", false);
                } else {
                    add(cases, registry, server, tool.name(), "minimal", minimal, "ok", true);
                    add(cases, registry, server, tool.name(), "explicit-options", arguments(tool, true), "ok", true);
                    add(cases, registry, server, tool.name(), "unreachable", minimal, "unreachable", false);
                    add(cases, registry, server, tool.name(), "refused", minimal, "refused", false);
                }
                add(cases, registry, server, tool.name(), "empty-arguments", Map.of(), "ok", false);
            }
            for (String mode : List.of("answered", "stopped", "cancelling")) {
                for (String tool : List.of("agent_poll", "agent_result", "agent_cancel")) {
                    add(cases, registry, server, tool, mode, Map.of("job_id", "job_fixture"), mode, true);
                }
            }
            actual.set("unknownTool", exchange(registry, request("tools/call", Map.of("name", "missing_tool", "arguments", Map.of()))));
            actual.set("ping", exchange(registry, request("ping", Map.of())));
            actual.set("notification", exchange(registry, Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
            if ("1".equals(System.getenv("PLOWSHARE_UPDATE_MCP_FIXTURES"))) {
                Files.createDirectories(GOLDEN.getParent());
                JSON.writerWithDefaultPrettyPrinter().writeValue(GOLDEN.toFile(), actual);
            }
            assertTrue(Files.isRegularFile(GOLDEN), "explicitly generate and review legacy fixture first");
            // Compare JSON values rather than Jackson's in-memory LongNode/IntNode distinction.
            JsonNode normalized = JSON.readTree(JSON.writeValueAsBytes(actual));
            assertTrue(JSON.readTree(GOLDEN.toFile()).equals(normalized),
                    "legacy compatibility drift: review changes, then explicitly regenerate the fixture");
        }
    }

    private static void add(ArrayNode cases, ToolRegistry registry, Recording server,
            String tool, String label, Map<String, Object> args, String mode, boolean success) throws Exception {
        server.calls.clear();
        server.mode = mode;
        ObjectNode row = cases.addObject();
        row.put("id", tool + "/" + label);
        row.put("backendMode", mode);
        Map<String, Object> request = request("tools/call", Map.of("name", tool, "arguments", args));
        row.set("request", JSON.valueToTree(request));
        JsonNode response = exchange(registry, request);
        row.set("response", response);
        row.set("backendCalls", JSON.valueToTree(server.calls));
        if (success) assertFalse(response.has("error") || response.path("result").path("isError").asBoolean(),
                () -> tool + "/" + label + ": " + response);
        if (!mode.equals("ok") && !success) {
            assertFalse(server.calls.isEmpty(), "failure must exercise backend: " + tool);
            assertTrue(response.path("result").path("isError").asBoolean(), "tool errors must be visible content");
        }
    }

    private static Map<String, Object> request(String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params);
    }

    private static JsonNode exchange(ToolRegistry registry, Map<String, Object> request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] in = (JSON.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8);
        new StdioTransport("fixture-version").serve(new ByteArrayInputStream(in), out, registry);
        String text = out.toString(StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? JSON.nullNode() : JSON.readTree(text);
    }

    private static Map<String, Object> arguments(ToolRegistry.Tool tool, boolean options) {
        JsonNode schema = JSON.valueToTree(tool.inputSchema());
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(n -> required.add(n.asText()));
        Map<String, Object> args = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode field = entry.getValue();
            if (!options && !required.contains(key)) return;
            Object value = switch (field.path("type").asText()) {
                case "integer", "number" -> 2;
                case "boolean" -> true;
                case "array" -> List.of("fixture");
                default -> "fixture";
            };
            if (field.has("enum")) value = field.path("enum").get(0).asText();
            args.put(key, value);
        });
        if (tool.name().equals("memory_resolve")) args.put("decision", "accept");
        if (tool.name().equals("document_citations")) {
            args.put("conversation", "conversation_fixture");
            args.remove("document");
        }
        if (tool.name().equals("information")) { args.put("operation","list");args.put("payload",Map.of("scope",Map.of("kind","personal"))); }
        if (tool.name().equals("fetch")) args.put("url", "https://example.invalid/fixture");
        if (tool.name().equals("client_root_project_here")) args.put("path", MISSING_ROOT);
        return args;
    }

    /** Captures effective defaults and scope after legacy argument interpretation. */
    private static final class Recording implements InvocationHandler {
        final List<ObjectNode> calls = new ArrayList<>();
        String mode = "ok";
        ServerClient proxy() {
            return (ServerClient) Proxy.newProxyInstance(ServerClient.class.getClassLoader(),
                    new Class<?>[]{ServerClient.class}, this);
        }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("baseUrl")) return "http://fixture.invalid:8091";
            ObjectNode call = JSON.createObjectNode();
            call.put("method", method.getName());
            ObjectNode arguments = call.putObject("arguments");
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) arguments.set(parameters[i].getName(), JSON.valueToTree(args[i]));
            calls.add(call);
            if (mode.equals("unreachable")) {
                call.put("throws", "IOException: fixture connection failed");
                throw new IOException("fixture connection failed");
            }
            if (mode.equals("refused")) {
                call.put("throws", "ServerError: 404 fixture missing");
                throw new ServerClient.ServerError(404, "fixture missing");
            }
            Object result;
            if (method.getReturnType().equals(ServerClient.JobStatus.class)) {
                ServerClient.RunOutcome outcome = switch (mode) {
                    case "answered" -> new ServerClient.RunOutcome("ANSWERED", true, "fixture answer\nsecond line", 2, 2, null);
                    case "stopped" -> new ServerClient.RunOutcome("CALL_BUDGET", false, "fixture partial work", 2, 2, "budget exhausted");
                    default -> null;
                };
                result = new ServerClient.JobStatus("job_fixture", "fixture", outcome == null ? "RUNNING" : "FINISHED",
                        method.getName().equals("cancelJob") || mode.equals("cancelling"), outcome);
            } else result = sample(method.getReturnType());
            call.set("returns", JSON.valueToTree(result));
            return result;
        }
    }

    /** Small deterministic samples, intentionally not evidence of server semantics. */
    private static Object sample(Class<?> type) throws ReflectiveOperationException {
        if (type == void.class) return null;
        if (type == Object.class) return Map.of("retained",true);
        if (type == String.class) return "fixture";
        if (type == int.class || type == Integer.class) return 2;
        if (type == long.class || type == Long.class) return 2L;
        if (type == double.class || type == Double.class) return 0.5;
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == Instant.class) return Instant.parse("2026-10-01T00:00:00Z");
        if (type == UUID.class) return DOCUMENT;
        if (List.class.isAssignableFrom(type)) return List.of();
        if (type == Home.class) return Home.global();
        if (type == Memory.class) return Memory.formed("memory_fixture", "fixture summary", "fixture scope", "fixture body",
                (Provenance) sample(Provenance.class), Home.global());
        if (type.isEnum()) return type.getEnumConstants()[0];
        if (type.isRecord()) {
            RecordComponent[] fields = type.getRecordComponents();
            Class<?>[] types = Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
            Object[] values = new Object[fields.length];
            for (int i = 0; i < fields.length; i++) values[i] = sample(types[i]);
            return type.getDeclaredConstructor(types).newInstance(values);
        }
        throw new IllegalArgumentException("add an explicit synthetic sample for " + type);
    }
}
