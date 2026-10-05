package io.aeyer.plowshare.server.harness;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.boot.convert.DurationStyle;

/** Configuration boundary for declared hook scalars. Raw values do not enter Parameters. */
public final class ParameterValues {
  private ParameterValues() {}

  /**
   * @throws IllegalStateException for a key the hook does not declare or a value that is not of the
   *     declared type, naming both
   */
  public static Parameters decode(
      String hook, List<Parameter> declared, Map<String, Object> given) {
    Map<String, Parameter> byName =
        declared.stream()
            .collect(Collectors.toMap(Parameter::name, p -> p, (a, b) -> a, LinkedHashMap::new));
    Map<String, ParameterValue> values = new LinkedHashMap<>();
    for (Parameter parameter : declared) {
      if (parameter.fallback() != null) {
        values.put(parameter.name(), parameter.fallback());
      }
    }
    for (Map.Entry<String, Object> set : given.entrySet()) {
      Parameter parameter = byName.get(set.getKey());
      if (parameter == null) {
        throw new IllegalStateException(
            "'" + hook + "' has no parameter '" + set.getKey() + "'; it takes " + byName.keySet());
      }
      values.put(set.getKey(), converted(hook, parameter, set.getValue()));
    }
    return new Parameters(values);
  }

  // YAML scalars arrive through Spring's Binder already coerced to Java types when the
  // static type is known (an Integer field binds an Integer), but here the map's value
  // type is Object, so Spring leaves every scalar as the String it read from the file.
  // Stringifying before parsing means this works either way: a literal String "4" and a
  // boxed Integer 4 both become "4" and parse the same.
  private static ParameterValue converted(String hook, Parameter parameter, Object value) {
    if (!(value instanceof String
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof Duration))
      throw new IllegalStateException(
          "'" + hook + "' parameter '" + parameter.name() + "' must be a declared scalar");
    String text = value.toString().strip();
    try {
      return switch (parameter.kind()) {
        case INTEGER -> new ParameterValue.IntegerValue(Integer.parseInt(text));
        case DURATION ->
            new ParameterValue.DurationValue(
                value instanceof Duration duration ? duration : DurationStyle.detectAndParse(text));
        case TEXT -> {
          if (!(value instanceof String))
            throw new IllegalArgumentException("text parameter requires text");
          yield new ParameterValue.TextValue(text);
        }
      };
    } catch (RuntimeException unreadable) {
      throw new IllegalStateException(
          "'"
              + hook
              + "' parameter '"
              + parameter.name()
              + "' is "
              + parameter.kind().name().toLowerCase()
              + ", and '"
              + text
              + "' is not one");
    }
  }
}
