package io.aeyer.plowshare.client.mcp;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The tools this process advertises over MCP, and where {@code tools/call}
 * dispatches to.
 *
 * <p>Holds no state beyond the table: the handlers are what talk to the server,
 * and the client keeps nothing durable of its own.
 */
public final class ToolRegistry {

    /**
     * One tool as MCP describes it.
     *
     * <p>{@code inputSchema} is a plain {@link Map} rather than a typed record
     * because it is a JSON Schema document that goes to the model verbatim —
     * every tool's is a different shape, and modelling it would buy nothing
     * except a translation layer to get wrong.
     *
     * <p>Build it from a {@link LinkedHashMap} and not {@code Map.of}: the
     * registry preserves whatever order it is handed, but {@code Map.of} has
     * none to preserve, so a schema built that way comes out shuffled — and
     * differently shuffled between JVM runs, since its iteration order depends
     * on a per-run hash seed. The model reads these fields in order.
     */
    public record Tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, Object> handler) {}

    // Insertion-ordered: `tools/list` is the menu the model reads, and a table
    // whose order changed between two runs of the same binary would change the
    // prompt for no reason — and make two transcripts impossible to diff.
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Add a tool.
     *
     * @throws IllegalArgumentException if {@code name} is already registered.
     *     A silent overwrite would leave the model calling a tool whose
     *     description belongs to the one it replaced, and nothing in the
     *     transcript would say so.
     */
    public void register(
            String name,
            String description,
            Map<String, Object> schema,
            Function<Map<String, Object>, Object> handler) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a tool needs a name");
        }
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("tool already registered: " + name);
        }
        // An unmodifiable *copy* of a LinkedHashMap, not Map.copyOf: Map.copyOf
        // gives back an unordered map, and the schema's key order is the order
        // the model reads the tool's arguments in.
        Map<String, Object> frozen = schema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(schema));

        tools.put(name, new Tool(name, description == null ? "" : description, frozen, handler));
    }

    /** Every registered tool, in registration order. */
    public Collection<Tool> tools() {
        return tools.values();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }
}
