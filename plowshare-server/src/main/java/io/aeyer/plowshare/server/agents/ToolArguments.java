package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The argument-reading half of every tool's never-throw contract, in one place.
 *
 * <p>{@link AgentTool}'s javadoc states that rule as something every
 * implementation upholds <em>identically</em>, and a rule stated that way is one
 * the second implementation gets subtly wrong. This class exists so that the
 * next tool copies six lines instead of reinventing a parser, a failure type and
 * a set of messages that would drift from these ones.
 *
 * <h2>The shape a tool's {@code run} takes</h2>
 *
 * <pre>{@code
 * public String run(String argumentsJson, Home home) {
 *     Objects.requireNonNull(argumentsJson, "argumentsJson");   // outside the try:
 *     Objects.requireNonNull(home, "home");                     // the runtime's bug
 *     try {
 *         return answer(argumentsJson, home);
 *     } catch (BadArguments unusable) {
 *         return unusable.getMessage();
 *     }
 * }
 * }</pre>
 *
 * <p><b>Only {@link BadArguments} is caught, and that is load-bearing.</b> It is
 * a nested type of this class, so nothing outside can be thrown into that catch
 * — which is what keeps the clause to a caller's mistakes and lets every
 * infrastructure failure fall through to the job runtime, where a run ends as
 * unavailable rather than as a confident empty answer.
 *
 * <p>Package-private: this is the {@code agents} package's own scaffolding, not
 * a general utility. Deliberately <em>not</em> an abstract base class that owns
 * {@code run} — that would fix the entry shape for tools this task does not
 * write, and it is a change a later task can still make from here.
 */
final class ToolArguments {

    /**
     * One parser for every call on every thread.
     *
     * <p>An {@link ObjectReader} rather than the {@link ObjectMapper} it came
     * from: Jackson documents a reader as immutable and safe to share, and the
     * claim was checked rather than taken on trust — 160,000 parses across 8
     * threads against Jackson 2.17.2, this project's version, all correct. Jobs
     * run concurrently on virtual threads, so this is shared by everything.
     */
    private static final ObjectReader JSON = new ObjectMapper().reader();

    private ToolArguments() {
    }

    /**
     * A validation failure on its way to becoming a tool result.
     *
     * <p>An exception so that a check deep in an argument helper can stop the
     * call without every helper returning a two-valued type. Its visibility is
     * the guard described above: nothing outside this package can throw one, so
     * a tool's catch clause cannot widen by accident.
     */
    static final class BadArguments extends RuntimeException {

        BadArguments(String message) {
            super(message);
        }
    }

    /**
     * The arguments as an object, or a {@link BadArguments} saying what arrived.
     *
     * <p><b>An empty string is not a parse failure.</b> A model calling a tool
     * with no arguments sends {@code ""} — {@code ToolCall} requires arguments
     * as an empty string rather than a null for exactly that reason — and
     * measured against Jackson 2.17.2, this project's version, {@code
     * readTree("")} returns a {@code MissingNode} rather than throwing or
     * returning null. Treating that as an unreadable document would tell the
     * model its JSON was malformed when it sent none, leaving it nothing to
     * correct, so the missing node is returned as-is: {@code path(name)} on one
     * is itself a missing node, which routes every required argument to the one
     * useful sentence, that the argument was not there.
     *
     * @param example a valid arguments object for this tool, shown in both
     *     failure messages. The two messages must stay distinguishable from each
     *     other — this one names the JSON as unreadable, {@code parse}'s other
     *     branch names the shape — because a test asserting on a phrase both
     *     contain pins neither.
     */
    static JsonNode parse(String argumentsJson, String tool, String example) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(argumentsJson);
        } catch (JsonProcessingException unreadable) {
            throw new BadArguments(tool + " could not read its arguments: they were not valid"
                    + " JSON (" + firstLine(unreadable) + "). Send a JSON object, like "
                    + example + ".");
        }
        if (parsed.isMissingNode()) {
            return parsed;
        }
        if (!parsed.isObject()) {
            throw new BadArguments(tool + " could not read its arguments: they must be a JSON"
                    + " object, not " + parsed + ". Send something like " + example + ".");
        }
        return parsed;
    }

    /**
     * One required string argument.
     *
     * <p>{@code isTextual} rather than {@code asText}, and the difference is not
     * pedantry: measured against Jackson 2.17.2, {@code NullNode.asText()}
     * returns the four-character string {@code "null"}. A tool that read {@code
     * {"question": null}} with {@code asText} would search the archive for the
     * word "null" and report in good faith that nothing was close to it.
     */
    /*
     * Deliberately unbounded in length, and the whole tool layer agrees:
     * memory_recall's `question` and agent_run's `task` are both whatever the
     * model sends. Worth naming because it is inconsistent with the one bounded
     * argument we do have — memory_recall's `limit` caps at MAX_LIMIT and tells
     * the model what happened, so an over-large limit is a correctable tool
     * result. An over-large string is not: it blows a context budget or fails at
     * the transport, and the model sees UNAVAILABLE, which it cannot act on.
     * No cap is added here because a right number needs the model's context
     * window, which lives in configuration this class cannot see.
     */
    static String requireText(JsonNode args, String name, String tool, String what) {
        JsonNode value = args.path(name);
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText().strip();
        }
        // Missing, null, blank and wrong-typed share one message. They are one
        // mistake from where the model stands — it has no usable value in that
        // field — and the fix is the same sentence in every case.
        throw new BadArguments(tool + " needs a '" + name + "': " + what + ". It was missing,"
                + " empty, or not a string.");
    }

    /**
     * One required string argument, kept exactly as it arrived.
     *
     * <p><b>The two differences from {@link #requireText} are both wrong for a
     * path and both right for a payload</b>, which is why this is a second
     * method rather than a flag: {@code file_edit}'s {@code content} is the
     * file, not a description of one.
     *
     * <ul>
     *   <li><b>Never stripped.</b> {@code requireText} calls {@code strip()},
     *       which for a file's contents drops the trailing newline off every
     *       file an agent writes — silently, and with the tool's happy-path test
     *       still green, since a comparison against the sent string would strip
     *       on both sides. {@code content_is_written_exactly_as_it_was_sent} is
     *       what holds it;
     *   <li><b>Empty is accepted.</b> {@code requireText} refuses a blank, which
     *       is the right reading of an empty question or an empty id. Truncating
     *       a file to nothing is an ordinary edit, and refusing it would be a
     *       tool inventing a rule the filesystem does not have —
     *       {@code an_empty_file_is_an_ordinary_thing_to_write}.
     * </ul>
     *
     * <p>Only the field's <em>type</em> is checked, and {@code isTextual} rather
     * than {@code asText} for {@code requireText}'s own measured reason: {@code
     * NullNode.asText()} returns the four-character string {@code "null"}, so
     * {@code {"content": null}} would write the word into the file.
     *
     * <p>Unbounded in length, like every other string this class reads; that
     * paragraph on {@link #requireText} is the one owner of the argument.
     */
    static String requireExactText(JsonNode args, String name, String tool, String what) {
        JsonNode value = args.path(name);
        if (value.isTextual()) {
            return value.asText();
        }
        throw new BadArguments(tool + " needs a '" + name + "': " + what + ". It was missing,"
                + " null, or not a string.");
    }

    /**
     * One optional string argument: the caller's value, or {@code null} when it
     * did not send one.
     *
     * <p>{@link #optionalInt}'s shape — missing and null are not a failure, and
     * the refusal is the caller's to word because the sentence that helps names
     * what the field is for.
     *
     * <p><b>A blank is {@code null} and not an empty filter</b>, which is the
     * one decision here that is not that method's. Every use of an optional
     * string on this surface so far is a narrowing, and a caller that sent an
     * empty one has narrowed nothing; {@code GET /v1/documents} reads its own
     * {@code q} exactly this way, so a model sending {@code ""} and an operator
     * sending {@code ?q=} get the same answer. Refusing it instead would spend
     * a turn teaching a model to omit a field whose meaning when omitted is
     * what it already asked for.
     *
     * <p>{@code isTextual} rather than {@code asText}, for {@link #requireText}'s
     * measured reason: {@code NullNode.asText()} returns the four-character
     * string {@code "null"}, and a filter reading that would report in good
     * faith that nothing was named it.
     *
     * @param unreadable given the node as it arrived, so the refusal can echo
     *     what the model actually typed
     */
    static String optionalText(
            JsonNode args, String name, Function<JsonNode, BadArguments> unreadable) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw unreadable.apply(value);
        }
        String text = value.asText().strip();
        return text.isEmpty() ? null : text;
    }

    /**
     * One optional whole-number argument: the caller's value, or {@code
     * fallback} when it did not send one.
     *
     * <p><b>Only the reading is shared; the refusal is not.</b> {@code
     * unreadable} builds the message, because the sentence that helps names the
     * tool, the bounds and the default — {@code file_read}'s {@code offset} has
     * no upper bound and {@code memory_recall}'s {@code limit} has one, and a
     * message assembled from a signature that carried both would say something
     * true and useless to each. What is worth sharing is the three lines above
     * it, which are measured rather than obvious and which the second
     * implementation is the one to get wrong. That is this class's whole reason
     * for existing.
     *
     * <p><b>A missing or null value is the default and not a failure.</b> A
     * model that omits an optional argument has done nothing wrong, and one that
     * writes {@code null} into it has said the same thing in JSON — {@code
     * a_null_limit_is_the_default_and_not_an_error} is what holds the second.
     *
     * <p>{@code canConvertToInt} before {@code asInt}, and the difference is a
     * refusal that quotes a number nobody sent. Measured against Jackson 2.17.2,
     * this project's version: {@code asInt()} on a value outside {@code int}
     * range truncates or saturates in silence — {@code 99999999999999} comes
     * back {@code 276447231}, {@code 2147483648} comes back {@code
     * -2147483648}, {@code 1e30} comes back {@code 2147483647}.
     *
     * <p>A number sent as a string is accepted. A model does it often enough
     * that refusing would cost a whole turn to learn nothing, and the MCP
     * surface's {@code memory_recall} is lenient the same way.
     *
     * <p><b>Range is not checked here.</b> This says the value is a whole number
     * and nothing about whether it is a usable one; a floor, a ceiling and
     * whether either clamps or refuses are the caller's, because they differ per
     * argument and each has its own sentence.
     *
     * @param unreadable given the node as it arrived, so the refusal can echo
     *     what the model actually typed rather than a value derived from it
     */
    static int optionalInt(
            JsonNode args, String name, int fallback, Function<JsonNode, BadArguments> unreadable) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (value.isNumber()) {
            if (!value.canConvertToInt()) {
                throw unreadable.apply(value);
            }
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.asText().trim());
            } catch (NumberFormatException notANumber) {
                throw unreadable.apply(value);
            }
        }
        throw unreadable.apply(value);
    }

    /**
     * One optional true-or-false argument: the caller's value, or {@code
     * fallback} when it did not send one.
     *
     * <p>{@link #optionalInt}'s shape, and every paragraph there applies —
     * missing and null are the default rather than a failure, the refusal is
     * the caller's to word, and a value sent as a string is accepted because a
     * model does it often enough that refusing would cost a turn to learn
     * nothing.
     *
     * <p><b>What is not shared with that method is the reason this one exists
     * at all: every shortcut to a boolean answers a wrong question in
     * silence.</b> Measured against Jackson 2.17.2, this project's version:
     *
     * <ul>
     *   <li>{@code asBoolean()} on the text {@code "TRUE"} returns <b>false</b>
     *       — it accepts only the lower-case spelling — so a model that
     *       shouted its argument would be told, in effect, that it had asked
     *       for the opposite;
     *   <li>{@code asBoolean()} on the text {@code "no"} returns false and on
     *       {@code "yes"} returns false, so one of the two is right by
     *       accident;
     *   <li>{@code asBoolean()} on any non-zero number returns true and on an
     *       array returns false, so a shape mistake becomes an answer rather
     *       than a sentence.
     * </ul>
     *
     * <p>{@code Boolean.parseBoolean} is the same trap from the other side and
     * was measured too: it returns false for {@code "yes"}, for {@code "1"} and
     * for null, because it is defined as "is this the word true" rather than as
     * a parse. So the two spellings this accepts are named explicitly and
     * anything else is refused — a wrong flag is a search that quietly answers
     * a different question, and there is nothing in the result to say so.
     *
     * <p>Case is folded on the string form with {@code equalsIgnoreCase}, which
     * is {@code String}'s own per-character fold and takes no locale — {@link
     * io.aeyer.plowshare.protocol.Needle#matches} owns the argument for why a
     * locale-sensitive fold has no place in anything two machines run.
     *
     * @param unreadable given the node as it arrived, so the refusal can echo
     *     what the model actually typed
     */
    static boolean optionalFlag(
            JsonNode args,
            String name,
            boolean fallback,
            Function<JsonNode, BadArguments> unreadable) {

        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isTextual()) {
            String sent = value.asText().trim();
            if (sent.equalsIgnoreCase("true")) {
                return true;
            }
            if (sent.equalsIgnoreCase("false")) {
                return false;
            }
        }
        throw unreadable.apply(value);
    }

    /**
     * One optional list-of-strings argument: what the caller sent, or an empty
     * list when it sent nothing.
     *
     * <p>{@link #optionalText}'s shape, one dimension up — missing and null are
     * the default rather than a failure, and a value the caller did send has to
     * be the right shape or the call stops. What differs is that there is no
     * leniency here at all: a bare string where an array was asked for is
     * <b>refused</b> rather than read as a list of one.
     *
     * <p><b>That refusal is the decision, and it goes the other way from {@link
     * #optionalInt}'s "a number sent as a string is accepted".</b> The leniency
     * there is free: a string that parses as an integer means one thing. Here it
     * is not. The first argument to take this shape is {@code agent_run}'s
     * {@code images}, whose elements are opaque ids, and a model that sent
     * {@code "img_a img_b"} would have meant two — so a lenient reading would
     * turn a shape mistake into a run shown one picture named after two, with
     * nothing anywhere to say so. A sentence costs a turn; a wrong picture costs
     * the answer.
     *
     * <p>An element that is not a string, or that is blank, stops the call for
     * the same reason: the caller has named something, and this cannot tell
     * which thing.
     *
     * @param unreadable given the node as it arrived — the whole value for a
     *     shape that is not an array, or the offending element for one that is —
     *     so the refusal can echo what the model actually typed
     */
    static List<String> optionalTexts(
            JsonNode args, String name, Function<JsonNode, BadArguments> unreadable) {
        JsonNode value = args.path(name);
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw unreadable.apply(value);
        }
        List<String> read = new ArrayList<>(value.size());
        for (JsonNode element : value) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw unreadable.apply(element);
            }
            read.add(element.asText().strip());
        }
        return List.copyOf(read);
    }

    /**
     * A string property, as JSON Schema.
     *
     * <p>Moved here from {@code MemoryTools}, where it was private, when {@code
     * AgentRunTool} became the second tool to need it — this class's stated
     * reason for existing: the second implementation of a shape stated as a rule
     * is the one that gets it subtly wrong.
     *
     * <p>A {@link LinkedHashMap} and not {@code Map.of}, and that is measured
     * rather than stylistic: {@code ToolSchema}'s javadoc records that an
     * unordered map serialises a schema's properties in a different order
     * between JVM runs, which turns a request body somebody is reading in a log,
     * or diffing against yesterday's, into a coin flip.
     */
    static Map<String, Object> string(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("description", description);
        return schema;
    }

    /** An integer property, as JSON Schema. The companion to {@link #string} for
     *  the arguments {@link #optionalInt} reads, and ordered for its reason. */
    static Map<String, Object> integer(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("description", description);
        return schema;
    }

    /** A true-or-false property, as JSON Schema. The companion to {@link #string}
     *  for the arguments {@link #optionalFlag} reads, and ordered for its
     *  reason. */
    static Map<String, Object> flag(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "boolean");
        schema.put("description", description);
        return schema;
    }

    /** An array-of-strings property, as JSON Schema. The companion to {@link
     *  #string} for the arguments {@link #optionalTexts} reads, and ordered for
     *  its reason — {@code items} as well as the property itself, so that a
     *  schema diffed against yesterday's is the same bytes. */
    static Map<String, Object> strings(String description) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("description", description);
        schema.put("items", items);
        return schema;
    }

    /** An object schema over {@code properties}, with {@code required} named.
     *  See {@link #string} for why the map is ordered. */
    static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(required));
        return schema;
    }

    /**
     * The full Unicode linebreak set, not the three {@code String.lines()}
     * splits on.
     *
     * <p>Package-private rather than {@code private}: {@link DocumentTools}'s
     * {@code quote} splits a chunk of untrusted text into lines with this exact
     * pattern before prefixing each one, and a second, separately-compiled
     * {@code Pattern.compile("\\R")} over there would be the same regex twice
     * for no reason — {@link #LINE_BREAK} moving here is what lets that call
     * site share the compiled pattern rather than merely agree with it.
     */
    static final Pattern LINE_BREAK = Pattern.compile("\\R");

    /**
     * A field flattened onto one line, so a value carrying a break cannot pass
     * as more than one line of whatever a tool's renderer is building.
     *
     * <p><b>Moved here from {@link DocumentTools}, where {@code FetchTool}
     * copied it byte-for-byte rather than reaching for a shared
     * implementation</b> — precisely the drift this class's own javadoc names
     * {@link #string} for: "the next tool copies six lines instead of
     * reinventing a parser... that would drift from these ones." A flattener is
     * no different from a schema builder in that respect, and the second
     * independent copy is what a review of {@code FetchTool} caught.
     *
     * <p>{@code MemoryTools.oneLine} and {@code AskTool}'s own private copy
     * predate this method and are deliberately left alone here: moving every
     * existing copy in the package is a larger change than closing the one a
     * single review found, and {@code MemoryTools.oneLine} is {@code public}
     * for reasons of its own — {@link AgentRunTool} and {@link JobRuntime} call
     * it across package files, which this package-private method does not need
     * to support.
     */
    static String oneLine(String text) {
        return LINE_BREAK.matcher(text).replaceAll(" ").strip();
    }

    /**
     * The first line of an exception's message, or something sayable when there
     * is none.
     *
     * <p>Package-private so {@code MemoryToolsTest} can pin its two defensive
     * halves directly. Neither is reachable through a tool: Jackson's parse
     * exceptions always carry a message, and it is never empty. They are here
     * because this is what renders a parse failure to a model, and a tool result
     * reading {@code "null"} or reading as an empty sentence tells it nothing
     * about what to fix.
     *
     * <p>The first line only: Jackson appends the source document and a
     * line/column, and the model gains nothing from being shown its own input
     * back with a cursor in it.
     */
    static String firstLine(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            return e.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse("");
    }
}
