package io.aeyer.plowshare.server.llm.dispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tool as the model is told about it: a name, a description it reads, and JSON Schema parameters.
 *
 * <p>{@code parameters} is a plain {@link Map} rather than a typed record for the same reason the
 * MCP tool registry does it: JSON Schema is an open format, and a typed subset would silently drop
 * whatever it did not model — a constraint the author wrote, the model never sees, and nobody can
 * find again.
 *
 * <p>Copied into {@link LinkedHashMap}s rather than through {@link Map#copyOf}, which is what the
 * plan asked for. The difference is the one {@code OpenAiTransport.chatBody} already records about
 * its own map: {@code Map.copyOf} does not preserve iteration order, so one schema serialises its
 * properties in a different order between JVM runs — measured across 20 runs, insertion order came
 * back 3 times. That turns a request body someone is reading in a log, or diffing against
 * yesterday's, into a coin flip for no benefit.
 *
 * <p><b>What that copy stopped refusing, {@link #copyValue} refuses instead.</b> {@code Map.copyOf}
 * rejects a null key and a null value; a {@code LinkedHashMap} accepts both, and the two then
 * behave differently — only one of them fails at all. Measured against Jackson 2.17.2, this
 * project's version, rather than assumed: a null key throws {@code JsonMappingException} ("Null key
 * for a Map not allowed in JSON"), on a lane thread, at the first call that offers the tool; a null
 * value does not throw, serialises as {@code {"k":null}}, and reaches the model as part of the
 * schema it is being asked to satisfy. An earlier version of this comment said both merely "fail at
 * serialisation instead", which was wrong about the half that never fails — exactly the
 * unmeasured-library-claim mistake this branch has now made four times.
 *
 * <p>Recursive rather than shallow, which is why it is a method and not two lines in the compact
 * constructor. A JSON Schema's content is nested — {@code properties} is a map of maps — so a
 * top-level check would pass every schema whose null sits where a null actually gets written. The
 * recursion also makes {@code parameters} deeply unmodifiable, so the sentence below is true rather
 * than nearly true: a shallow copy leaves the caller holding the nested maps and able to rewrite a
 * schema already on its way to a transport.
 *
 * <p>Immutable, because a schema is built once and serialised later on whichever lane thread runs
 * the call.
 *
 * <p><b>Accepted limitation: a parameters map that contains itself recurses until the stack runs
 * out.</b> Recorded rather than fixed, in this file's practice of recording a known gap instead of
 * letting a green suite read as coverage, and the reasoning is that an identity visited-set would
 * buy a better message for a case no shipped path can reach. A schema parsed from JSON or from
 * agent frontmatter is a tree — neither format can express a cycle — so the only way in is a map
 * built by hand in Java, which is a programming error, and one with no JSON rendering at all: a
 * visited-set would turn {@code StackOverflowError} into {@code IllegalArgumentException} and could
 * not make such a schema work. Note also which direction this moved: before this method existed the
 * same map reached Jackson, and blew the stack on a lane thread at the first call that offered the
 * tool. It now fails at construction, with {@link #copyValue} on the stack.
 */
public record ToolSchema(String name, String description, Map<String, Object> parameters) {

  public ToolSchema {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    parameters = copyMap(Objects.requireNonNull(parameters, "parameters"), "parameters");
  }

  /**
   * One map, copied deeply into insertion order, with nulls refused.
   *
   * <p>Shared by the constructor and by {@link #copyValue}'s nested case rather than written twice.
   * The parameter is {@code Map<?, ?>} for exactly that reason: the constructor's {@code
   * Map<String, Object>} and the wildcard a nested value arrives as both satisfy it, so the two
   * callers share one body with no unchecked cast between them. A helper typed {@code Map<String,
   * Object>} would have needed one.
   */
  private static Map<String, Object> copyMap(Map<?, ?> source, String path) {
    Map<String, Object> copied = new LinkedHashMap<>(source.size());
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      String key = requireKey(entry.getKey(), path);
      copied.put(key, copyValue(entry.getValue(), path + "." + key));
    }
    return Collections.unmodifiableMap(copied);
  }

  /**
   * One value, copied deeply, with a null anywhere refused and located.
   *
   * <p>{@code path} exists only to name what is wrong. A schema is a nested structure, and "a null
   * value" without a path sends the reader through the whole of it looking for which field.
   */
  private static Object copyValue(Object value, String path) {
    if (value == null) {
      throw new IllegalArgumentException(
          "a tool schema may not contain a null value; found one at " + path);
    }
    if (value instanceof Map<?, ?> map) {
      return copyMap(map, path);
    }
    if (value instanceof List<?> list) {
      List<Object> copied = new ArrayList<>(list.size());
      for (int i = 0; i < list.size(); i++) {
        copied.add(copyValue(list.get(i), path + "[" + i + "]"));
      }
      return Collections.unmodifiableList(copied);
    }
    return value;
  }

  /**
   * The tools as the wire carries them: one {@code {"type": "function", "function": {…}}} entry per
   * schema, in the order given.
   *
   * <p><b>Here rather than inside the transport because it now has two callers, and the two must
   * not be able to disagree.</b> {@code OpenAiTransport.chatBody} builds a request's {@code tools}
   * array out of this, and {@code ContextView} measures how many characters that array is — which
   * is the only honest answer this system has to "what does the tool block cost", there being no
   * tokenizer on this box to give a better one. A measurement of a shape assembled a second time
   * would be a measurement of a copy, and it would go on agreeing for exactly as long as nobody
   * edited either.
   *
   * <p>{@link LinkedHashMap} at both levels, for the reason this record's javadoc gives about
   * {@code parameters}: {@code Map.of} has no iteration order to preserve, so a body built that way
   * serialises its fields differently between JVM runs, and a request somebody is diffing against
   * yesterday's becomes a coin flip.
   *
   * <p>{@code "type": "function"} is written explicitly because it is required rather than
   * defaulted — an entry without it is rejected as a malformed tool rather than read as a function.
   */
  public static List<Map<String, Object>> asDeclared(List<ToolSchema> tools) {
    List<Map<String, Object>> declared = new ArrayList<>(tools.size());
    for (ToolSchema tool : tools) {
      Map<String, Object> function = new LinkedHashMap<>(3);
      function.put("name", tool.name());
      function.put("description", tool.description());
      function.put("parameters", tool.parameters());
      Map<String, Object> entry = new LinkedHashMap<>(2);
      entry.put("type", "function");
      entry.put("function", function);
      declared.add(entry);
    }
    return declared;
  }

  private static String requireKey(Object key, String path) {
    if (key == null) {
      throw new IllegalArgumentException(
          "a tool schema may not contain a null key; found one in " + path);
    }
    return key.toString();
  }
}
