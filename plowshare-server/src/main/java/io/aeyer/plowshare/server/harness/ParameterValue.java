package io.aeyer.plowshare.server.harness;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.Duration;
import java.util.Objects;

/** A declared hook parameter value, converted and checked once by the configuration codec. */
public sealed interface ParameterValue {
  record IntegerValue(@JsonValue int value) implements ParameterValue {
    @Override
    public String toString() {
      return Integer.toString(value);
    }
  }

  record DurationValue(@JsonValue Duration value) implements ParameterValue {
    public DurationValue {
      Objects.requireNonNull(value);
      if (value.isNegative()) throw new IllegalArgumentException("negative hook duration");
    }

    @Override
    public String toString() {
      return value.toString();
    }
  }

  record TextValue(@JsonValue String value) implements ParameterValue {
    public TextValue {
      Objects.requireNonNull(value);
      if (value.length() > 8192 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("invalid hook text");
    }

    @Override
    public String toString() {
      return value;
    }
  }
}
