package io.aeyer.plowshare.client.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two JSON Schema fragments every tool family here builds its {@code
 * inputSchema} out of.
 *
 * <h2>Why these are shared and the rest of the repetition is not</h2>
 *
 * <p>{@code MemoryTools}, {@code AgentTools} and {@code ProjectTools} carried
 * byte-identical copies of both — measured, not assumed: the three were
 * checksum-equal. <b>Three copies is where extraction earns itself</b>, and
 * these two earn it in the way that matters: they carry <em>no policy</em>. A
 * JSON Schema object is a JSON Schema object, there is no per-surface judgement
 * in either, and a fourth tools class would have made a fourth copy of a
 * decision nobody is making.
 *
 * <p><b>The rest of the shared shape deliberately stays duplicated</b>, which is
 * the same line {@code FileTools} draws for its four tools. {@code ask}, {@code
 * describe} and {@code Call<T>} look identical and are not: each builds a
 * different sentence for the same failure — "Nothing was written" where a
 * project would have changed, "this says nothing about the run" where a job
 * would have started — and those differences are the point of having them. A
 * shared {@code ask} taking the sentence as a parameter would be the same three
 * sentences at the same three call sites with a layer of indirection over them,
 * and the first time somebody needed a fourth behaviour it would grow a flag.
 *
 * <p>Package-private, and static methods rather than a builder: the callers
 * static-import them exactly as they already static-import {@code
 * MemoryTools.oneLine}.
 */
final class Schemas {

    private Schemas() {
    }

    /** One string-valued property, with the description a model reads to decide
     *  what to put in it. */
    static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    /**
     * One integer-valued property.
     *
     * <p>{@link #string}'s twin, added when a third family wanted three of these
     * in one schema. Every earlier caller builds the same two-entry map inline;
     * <b>those are deliberately left alone</b>, because each of them carries a
     * comment about the number it declines to spell and folding them into this
     * method would be a change to text a model reads for no gain the model can
     * see.
     */
    static Map<String, Object> integer(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    /**
     * The object a tool's {@code inputSchema} is.
     *
     * <p>{@code properties} is taken as given and is a {@link LinkedHashMap} at
     * every call site, because the order is the order a model is shown the
     * arguments; {@code required} is copied, because a schema handed to the
     * registry outlives the list the caller built it from.
     */
    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(required));
        return schema;
    }
}
