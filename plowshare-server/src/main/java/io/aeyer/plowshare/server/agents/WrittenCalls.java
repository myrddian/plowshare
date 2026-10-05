package io.aeyer.plowshare.server.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether an answer wrote a call to one of its run's tools out as text instead of making it.
 *
 * <h2>Why the harness looks for this at all</h2>
 *
 * <p>Measured 2026-09-27: the bot Aristoxenus, asked to use the orchestration to build a text
 * RPG, ended its turn with {@code orchestrate_implement_specification({"request": ...})} inside a
 * {@code ```python} fence and no tool call (entry 123 of {@code cnv_317717703EFBC15E}), then told
 * the person how to monitor a run that never existed. It is the failure {@code ToolChoice} closed
 * for conductors, met in a bot; {@code JobRuntime}'s turn loop is what acts on a match.
 *
 * <h2>What counts, and why it is this narrow</h2>
 *
 * <p>A tool the run is offered, named as a whole word, then {@code (} and <em>then</em> {@code
 * \{} -- whitespace, newlines included, allowed around the parenthesis. The brace is what makes
 * it a call rather than prose: every tool here takes a JSON object, so arguments written out
 * start with one, while "use orchestrate_implement_specification to start it" or a signature
 * like {@code name(request)} does not. A call shape for a tool the run is <em>not</em> offered is
 * not a missed call either: nothing could have run it, and nudging would ask for the impossible.
 * An answer that only claims an action ("I will now launch...") is out of scope.
 *
 * <h2>And the arguments alone, when they are the whole answer</h2>
 *
 * <p>Measured 2026-09-28: a conductor ({@code orc_318408A44038F859}, entries 252, 256 and 260)
 * answered three turns running with nothing but {@code todo_write}'s arguments -- {@code {"ops":
 * [...]}}, no name, no call -- and was failed {@code stuck} with every phase built. So {@link
 * #writtenArguments} also counts an answer that is a JSON object and nothing else, fenced or not,
 * whose keys fit exactly one offered tool: every key one it takes, every key it requires present.
 * JSON with prose around it is an answer that quotes some; keys no tool takes are data; keys that
 * fit two tools ({@code {"path": ...}}) would be a guess, and the guard does not guess.
 *
 * <h2>And a name alone, when that is the whole answer</h2>
 *
 * <p>Rule 5 (spec 2026-09-29 §3): a conductor that ends a phase with {@code
 * **orchestration_finish**} as its answer wrote a call to a tool that takes a {@code result} --
 * and gave none, because a bare name is not the call, only the model's idea of announcing one. So
 * {@link #bareName} also counts an answer that is nothing but an offered tool's name, dressed in
 * bold, italics or backticks and at most an empty pair of parentheses. A name inside a sentence
 * ("I will call {@code todo_read} next") is prose about a tool, not a call to it, and a name
 * nothing offers is not a missed call either.
 */
final class WrittenCalls {

  /**
   * Failing on trailing tokens: Jackson otherwise reads {@code {..} {..}} as its first object, and
   * two values are not one tool's arguments.
   */
  private static final ObjectMapper JSON =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** One code fence around the whole answer, with or without a language after its ticks. */
  private static final Pattern FENCED = Pattern.compile("(?s)^```[\\w-]*\\s*\\n(.*)\\n\\s*```$");

  /**
   * One name, dressed at most in bold, italics or backticks, with at most an empty pair of
   * parentheses: the whole reply.
   */
  private static final Pattern BARE =
      Pattern.compile("^[*_`\\s]*([A-Za-z_][A-Za-z0-9_]*)(?:\\(\\s*\\))?[*_`\\s]*$");

  /**
   * The way out of the forced retry, offered on that one request and on no other.
   *
   * <p>REQUIRED takes away the only legitimate way a model has to decline: ending in prose. A reply
   * that wrote a call out as an <em>illustration</em> -- a bot explaining how {@code
   * orchestrate_implement_specification} is used -- would otherwise be forced into a real call,
   * possibly an orchestration start, which passes no approval gate. Calling this instead delivers
   * the held draft as it was written.
   *
   * <p>Harness-owned and never registered: it is in no runtime's tool layer, so it is in no {@code
   * knownTools()}, no definition can declare it, and no {@code offered} map holds it. The turn loop
   * adds it to the schemas of the forced request alone and recognises it there.
   */
  static final String REPLY_AS_WRITTEN = "reply_as_written";

  static final ToolSchema REPLY_AS_WRITTEN_SCHEMA =
      ToolSchema.from(
          REPLY_AS_WRITTEN,
          "Deliver the reply you already wrote, as written. Use it only if the call in it was an"
              + " example, not something to run.",
          Map.of("type", "object", "properties", Map.of()));

  private WrittenCalls() {}

  /**
   * Whether {@code content} writes a call to {@link #REPLY_AS_WRITTEN} as text, by {@link
   * #writtenCall}'s shape. Asked of the forced request's reply alone, where that tool is offered:
   * there it declines as the real call does, and anywhere else it is only prose.
   */
  static boolean writesTheWayOut(String content) {
    return writtenCall(content, List.of(REPLY_AS_WRITTEN)).isPresent();
  }

  /** Every shape, against the run's offered tools: a named call, bare arguments, a bare name. */
  static Optional<String> writtenIn(String content, Map<String, ? extends AgentTool> offered) {
    return writtenCall(content, offered.keySet())
        .or(
            () ->
                writtenArguments(
                    content, offered.values().stream().map(AgentTool::schema).toList()))
        .or(() -> bareName(content, offered.keySet()));
  }

  /**
   * The one offered tool whose arguments {@code content} is, when it is nothing but a JSON object;
   * empty when it is anything else, fits no tool, or fits more than one. See the class javadoc.
   */
  static Optional<String> writtenArguments(String content, Collection<ToolSchema> offered) {
    if (content == null) {
      return Optional.empty();
    }
    String body = unfenced(content);
    if (!body.startsWith("{") || !body.endsWith("}")) {
      return Optional.empty();
    }
    JsonNode parsed;
    try {
      parsed = JSON.readTree(body);
    } catch (Exception notJson) {
      return Optional.empty();
    }
    if (parsed == null || !parsed.isObject() || parsed.isEmpty()) {
      return Optional.empty();
    }
    Set<String> keys = new HashSet<>();
    parsed.fieldNames().forEachRemaining(keys::add);
    List<String> fits = new ArrayList<>();
    for (ToolSchema schema : offered) {
      if (fits(keys, schema)) {
        fits.add(schema.name());
      }
    }
    return fits.size() == 1 ? Optional.of(fits.get(0)) : Optional.empty();
  }

  /**
   * The JSON object {@code content} is, unfenced, when {@link #writtenIn} holds it by the
   * bare-arguments shape -- no call written out by name, and the whole of it one offered tool's
   * arguments; empty for the named shape or no match. Those arguments are the reply's own and
   * already fit, so a validator's {@code call} verdict runs them (spec 2026-09-28 §5).
   */
  static Optional<String> bareArguments(String content, Map<String, ? extends AgentTool> offered) {
    if (writtenCall(content, offered.keySet()).isPresent()) {
      return Optional.empty();
    }
    return writtenArguments(content, offered.values().stream().map(AgentTool::schema).toList())
        .map(tool -> canonical(unfenced(content)));
  }

  /**
   * The one JSON object {@code body} holds, written back out -- what a call's arguments carry,
   * rather than the reply's own spacing and fence. {@code body} is known to parse.
   */
  private static String canonical(String body) {
    try {
      return JSON.writeValueAsString(JSON.readTree(body));
    } catch (Exception unreachable) {
      return body;
    }
  }

  /**
   * {@code content} stripped, with one code fence around the whole of it taken off; {@code ""} for
   * null.
   */
  static String unfenced(String content) {
    if (content == null) {
      return "";
    }
    String body = content.strip();
    Matcher fenced = FENCED.matcher(body);
    return fenced.matches() ? fenced.group(1).strip() : body;
  }

  /**
   * Whether {@code argumentsJson} is a JSON object whose keys fit {@code schema} by the key test
   * {@link #writtenArguments} uses: every key one the tool takes, every key it requires present.
   * For a validator's {@code call} verdict (spec 2026-09-28-call-failures §5), which names its
   * tool, so an empty object fits a tool that requires nothing.
   */
  static boolean fits(String argumentsJson, ToolSchema schema) {
    if (argumentsJson == null) {
      return false;
    }
    JsonNode parsed;
    try {
      parsed = JSON.readTree(argumentsJson);
    } catch (Exception notJson) {
      return false;
    }
    if (parsed == null || !parsed.isObject()) {
      return false;
    }
    Set<String> keys = new HashSet<>();
    parsed.fieldNames().forEachRemaining(keys::add);
    return fits(keys, schema);
  }

  private static boolean fits(Set<String> keys, ToolSchema schema) {
    return takes(schema).containsAll(keys) && keys.containsAll(requires(schema));
  }

  private static Set<String> takes(ToolSchema schema) {
    return schema.parameters().properties() == null
        ? Set.of()
        : schema.parameters().properties().keySet();
  }

  private static Set<String> requires(ToolSchema schema) {
    return schema.parameters().required() == null
        ? Set.of()
        : Set.copyOf(schema.parameters().required());
  }

  /**
   * The first of {@code offeredNames}, in their own order, that {@code content} writes a call to;
   * empty when it writes none, or when there is no content or nothing offered.
   */
  static Optional<String> writtenCall(String content, Collection<String> offeredNames) {
    if (content == null || content.isEmpty()) {
      return Optional.empty();
    }
    for (String name : offeredNames) {
      // No word character on either side, so `my_file_read({` is not a call to
      // `file_read`: an underscore is a word character, and tool names are snake_case.
      // No dot before it either: `client.search({` or `app.run({` is a method on some
      // object in an example, not the run's own `search` or `run`.
      Pattern call = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "(?!\\w)\\s*\\(\\s*\\{");
      if (call.matcher(content).find()) {
        return Optional.of(name);
      }
    }
    return Optional.empty();
  }

  /**
   * The offered tool {@code content} is nothing but the name of — rule 5 (spec 2026-09-29 §3): a
   * reply of only {@code **todo_read**} made no call, and is warned about like the other shapes. A
   * name inside a sentence is prose; a name nothing offers is not a missed call.
   */
  static Optional<String> bareName(String content, Collection<String> offeredNames) {
    if (content == null || content.isBlank()) {
      return Optional.empty();
    }
    Matcher bare = BARE.matcher(unfenced(content));
    if (!bare.matches()) {
      return Optional.empty();
    }
    String name = bare.group(1);
    return offeredNames.contains(name) ? Optional.of(name) : Optional.empty();
  }
}
