package io.aeyer.plowshare.server.llm.dispatch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The shape a model is required to answer in.
 *
 * <h2>Why this stops being a hope and becomes a guarantee</h2>
 *
 * <p><b>Measured 2026-09-07</b> against both nodes this project runs on — {@code
 * mlx-community/gemma-4-e4b-it} on LM Studio and {@code glm-4.7-flash} on
 * llama.cpp b10835. Both enforce {@code response_format: json_schema}, and both
 * emitted {@code {"shape":"square","colour":"red","notes":"\\epsilon"}} — a
 * LaTeX command <em>correctly escaped inside a JSON string</em>.
 *
 * <p>That last detail is the whole reason this type exists. {@code \e} is not
 * one of JSON's nine legal escapes, and {@code ModelJson.withEscapedBackslashes}
 * exists in this repository to repair exactly that after the fact — a model
 * writing {@code \epsilon} into what it believed was JSON and producing a
 * document nothing can parse. It is the failure that forced {@code
 * ask_reviewer} to return prose rather than a structure. Under a schema the
 * sampler is constrained at the token level and <b>cannot emit invalid JSON at
 * all</b>, so the repair is not needed rather than being better at its job.
 *
 * <h2>Per request and never per model</h2>
 *
 * <p>A schema says what <em>this call</em> is asking for. It is not a property
 * of a model family, which is what a sampling profile holds, and {@code
 * SamplingProfiles} deliberately does not read a key for it — see {@link
 * Sampling#responseFormat}, which carries that argument in full because it is
 * the field that would otherwise make it possible.
 *
 * @param name what the schema is called. Required by the OpenAI contract and
 *     restricted here to what every endpoint accepts without quoting rules
 *     entering into it: letters, digits, underscore and hyphen. It is also what
 *     a log line and a refusal name, which is the other reason a blank one is
 *     no use to anybody
 * @param schema the JSON Schema itself, as the nested maps a YAML frontmatter
 *     block parses to. <b>Held as data and never as a string</b>: an agent file
 *     writes it as YAML, this server writes it as JSON, and a string in between
 *     would be a document nothing on either side could check was well-formed
 *     until an endpoint refused it
 */
public record JsonSchema(String name, Map<String, Object> schema) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public JsonSchema {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schema, "schema");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "a response schema is named '" + name + "', which is not a name every"
                            + " endpoint will take: letters, digits, underscore and hyphen, up"
                            + " to 64 of them. The name is on the wire and in every log line"
                            + " about this call, so it is not free-form prose");
        }
        if (schema.isEmpty()) {
            throw new IllegalArgumentException(
                    "the response schema '" + name + "' is empty. An empty schema constrains"
                            + " nothing, so a request carrying one would claim a guarantee it"
                            + " does not have -- which is worse than asking for no schema");
        }
        schema = new LinkedHashMap<>(schema);
    }

    /**
     * The value of {@code response_format} on the wire.
     *
     * <p><b>{@code strict} is deliberately not written.</b> It is OpenAI's own
     * field and neither node was measured with it; a backend that does not know
     * it ignores it, which is precisely the silent difference {@code carries()}
     * exists to make loud rather than to rely on. What was measured is that
     * both endpoints enforce the schema from {@code json_schema} alone, so that
     * is what is sent. The day somebody confirms the spelling against a live
     * endpoint the fix is one line here — which is the same sentence {@code
     * reasoning_effort} carried until 2026-09-07, and it came true.
     */
    public Map<String, Object> asDeclared() {
        Map<String, Object> named = new LinkedHashMap<>(2);
        named.put("name", name);
        named.put("schema", schema);
        Map<String, Object> format = new LinkedHashMap<>(2);
        format.put("type", "json_schema");
        format.put("json_schema", named);
        return format;
    }

    /** What a log line says about this: the name, and never the schema. A
     *  schema is a nested document and a log line is one line. */
    public String described() {
        return "json_schema('" + name + "')";
    }
}
