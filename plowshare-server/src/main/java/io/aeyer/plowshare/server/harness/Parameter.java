package io.aeyer.plowshare.server.harness;

import java.time.Duration;
import java.util.Objects;

/** One tuning parameter a harness hook declares: its name, type and default. */
public record Parameter(String name, Kind kind, ParameterValue fallback) {

  public enum Kind {
    INTEGER,
    DURATION,
    TEXT
  }

  public Parameter {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
    if (name.isBlank() || !name.matches("[a-zA-Z][a-zA-Z0-9_-]{0,63}"))
      throw new IllegalArgumentException("invalid hook parameter name");
    if (fallback != null
        && !(switch (kind) {
          case INTEGER -> fallback instanceof ParameterValue.IntegerValue;
          case DURATION -> fallback instanceof ParameterValue.DurationValue;
          case TEXT -> fallback instanceof ParameterValue.TextValue;
        })) throw new IllegalArgumentException("hook fallback does not match its declared kind");
  }

  public static Parameter integer(String name, int fallback) {
    return new Parameter(name, Kind.INTEGER, new ParameterValue.IntegerValue(fallback));
  }

  public static Parameter duration(String name, Duration fallback) {
    return new Parameter(name, Kind.DURATION, new ParameterValue.DurationValue(fallback));
  }

  public static Parameter text(String name, String fallback) {
    return new Parameter(name, Kind.TEXT, new ParameterValue.TextValue(fallback));
  }
}
