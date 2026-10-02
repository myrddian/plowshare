package io.aeyer.plowshare.server.harness;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.convert.DurationStyle;

/**
 * A hook's parameters as a profile set them, checked against what the hook
 * declares and converted once, at boot.
 */
public final class Parameters {

    private final Map<String, Object> values;

    private Parameters(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /**
     * @throws IllegalStateException for a key the hook does not declare or a value
     *     that is not of the declared type, naming both
     */
    public static Parameters read(String hook, List<Parameter> declared, Map<String, Object> given) {
        Map<String, Parameter> byName = declared.stream()
                .collect(Collectors.toMap(Parameter::name, p -> p, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> values = new LinkedHashMap<>();
        for (Parameter parameter : declared) {
            if (parameter.fallback() != null) {
                values.put(parameter.name(), parameter.fallback());
            }
        }
        for (Map.Entry<String, Object> set : given.entrySet()) {
            Parameter parameter = byName.get(set.getKey());
            if (parameter == null) {
                throw new IllegalStateException("'" + hook + "' has no parameter '" + set.getKey()
                        + "'; it takes " + byName.keySet());
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
    private static Object converted(String hook, Parameter parameter, Object value) {
        String text = String.valueOf(value).strip();
        try {
            return switch (parameter.kind()) {
                case INTEGER -> Integer.parseInt(text);
                case DURATION -> DurationStyle.detectAndParse(text);
                case TEXT -> text;
            };
        } catch (RuntimeException unreadable) {
            throw new IllegalStateException("'" + hook + "' parameter '" + parameter.name()
                    + "' is " + parameter.kind().name().toLowerCase() + ", and '" + text
                    + "' is not one");
        }
    }

    public int integer(String name) {
        return (Integer) required(name);
    }

    public Duration duration(String name) {
        return (Duration) required(name);
    }

    public String text(String name) {
        return (String) required(name);
    }

    /** Whether the profile, or a default, gave this parameter a value. */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    private Object required(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("no value for parameter '" + name + "'");
        }
        return value;
    }
}
