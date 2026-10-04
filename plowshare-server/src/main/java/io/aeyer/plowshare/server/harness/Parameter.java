package io.aeyer.plowshare.server.harness;

import java.time.Duration;
import java.util.Objects;

/** One tuning parameter a harness hook declares: its name, type and default. */
public record Parameter(String name, Kind kind, Object fallback) {

  public enum Kind {
    INTEGER,
    DURATION,
    TEXT
  }

  public Parameter {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(kind, "kind");
  }

  public static Parameter integer(String name, int fallback) {
    return new Parameter(name, Kind.INTEGER, fallback);
  }

  public static Parameter duration(String name, Duration fallback) {
    return new Parameter(name, Kind.DURATION, fallback);
  }

  public static Parameter text(String name, String fallback) {
    return new Parameter(name, Kind.TEXT, fallback);
  }
}
