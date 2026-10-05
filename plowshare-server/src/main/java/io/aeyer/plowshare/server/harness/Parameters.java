package io.aeyer.plowshare.server.harness;

import java.time.Duration;
import java.util.Map;

/**
 * A hook's parameters as a profile set them, checked against what the hook declares and converted
 * once, at boot.
 */
public final class Parameters {

  private final Map<String, ParameterValue> values;

  Parameters(Map<String, ParameterValue> values) {
    this.values = Map.copyOf(values);
  }

  public int integer(String name) {
    if (required(name) instanceof ParameterValue.IntegerValue value) return value.value();
    throw new IllegalArgumentException("parameter '" + name + "' is not integer");
  }

  public Duration duration(String name) {
    if (required(name) instanceof ParameterValue.DurationValue value) return value.value();
    throw new IllegalArgumentException("parameter '" + name + "' is not duration");
  }

  public String text(String name) {
    if (required(name) instanceof ParameterValue.TextValue value) return value.value();
    throw new IllegalArgumentException("parameter '" + name + "' is not text");
  }

  /** Whether the profile, or a default, gave this parameter a value. */
  public boolean has(String name) {
    return values.containsKey(name);
  }

  private ParameterValue required(String name) {
    ParameterValue value = values.get(name);
    if (value == null) {
      throw new IllegalArgumentException("no value for parameter '" + name + "'");
    }
    return value;
  }
}
