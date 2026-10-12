package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable recursive tool input. Missing object members differ from the explicit Null variant.
 * Integration parameters remain a scalar subset; extending tools never widens integration/1.
 */
public sealed interface ToolValue
    permits IntegrationPayload.Parameter, ToolValue.Null, ToolValue.Array, ToolValue.ObjectValue {
  record Null() implements ToolValue {
    @JsonValue
    public String wireValue() {
      return null;
    }
  }

  record Array(@JsonValue List<ToolValue> values) implements ToolValue {
    public Array {
      values = List.copyOf(values);
      if (values.size() > 1024) throw new IllegalArgumentException("Too many tool items");
    }
  }

  record ObjectValue(@JsonValue Map<String, ToolValue> values) implements ToolValue {
    public ObjectValue {
      if (values.size() > 1024) throw new IllegalArgumentException("Too many tool fields");
      var copy = new LinkedHashMap<String, ToolValue>();
      values.forEach(
          (name, value) -> {
            if (name == null
                || name.isEmpty()
                || name.length() > 1024
                || name.codePoints().anyMatch(Character::isISOControl))
              throw new IllegalArgumentException("Invalid tool field name");
            copy.put(name, Objects.requireNonNull(value));
          });
      values = Collections.unmodifiableMap(copy);
    }
  }

  /** Owning JSON boundary. Duplicate keys must be rejected by the parser before this conversion. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  static ToolValue fromWire(Object value) {
    return new ToolValueDecoder().decode(value, 0);
  }

  /** Enforce global bounds even for trees constructed through typed factories. */
  static void validate(ToolValue value) {
    new ToolValueDecoder().check(value, 0);
  }

  /** Portable decimal spelling, shared with SDKs using IEEE doubles; no precision repair. */
  static void number(BigDecimal value) {
    if (value.abs().compareTo(BigDecimal.valueOf(9007199254740991L)) > 0
        || value.stripTrailingZeros().scale() > 18
        || BigDecimal.valueOf(value.doubleValue()).compareTo(value) != 0)
      throw new IllegalArgumentException("Tool number exceeds portable range");
  }
}

/** Bounded boundary conversion, deliberately not a public schema API. */
final class ToolValueDecoder {
  private int remaining = 10000;
  private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();

  private void bound(int depth) {
    if (depth > 32 || --remaining < 0)
      throw new IllegalArgumentException("Tool value exceeds bounds");
  }

  ToolValue decode(Object source, int depth) {
    bound(depth);
    if (source == null) return new ToolValue.Null();
    if (source instanceof String text) return new IntegrationPayload.TextParameter(text);
    if (source instanceof Boolean flag) return new IntegrationPayload.BooleanParameter(flag);
    if (source instanceof Number value) {
      var decimal = new BigDecimal(value.toString());
      ToolValue.number(decimal);
      return new IntegrationPayload.NumberParameter(decimal);
    }
    if (visiting.put(source, true) != null) throw new IllegalArgumentException("Cyclic tool value");
    try {
      if (source instanceof List<?> list) {
        if (list.size() > 1024) throw new IllegalArgumentException("Too many tool items");
        return new ToolValue.Array(list.stream().map(value -> decode(value, depth + 1)).toList());
      }
      if (source instanceof Map<?, ?> map) {
        if (map.size() > 1024) throw new IllegalArgumentException("Too many tool fields");
        var values = new LinkedHashMap<String, ToolValue>();
        map.forEach(
            (name, value) -> {
              if (!(name instanceof String key))
                throw new IllegalArgumentException("Invalid tool field");
              values.put(key, decode(value, depth + 1));
            });
        return new ToolValue.ObjectValue(values);
      }
      throw new IllegalArgumentException("Unsupported tool value");
    } finally {
      visiting.remove(source);
    }
  }

  void check(ToolValue value, int depth) {
    bound(depth);
    switch (Objects.requireNonNull(value)) {
      case IntegrationPayload.TextParameter ignored -> {}
      case IntegrationPayload.BooleanParameter ignored -> {}
      case IntegrationPayload.NumberParameter decimal -> ToolValue.number(decimal.value());
      case ToolValue.Null ignored -> {}
      case ToolValue.Array array -> array.values().forEach(item -> check(item, depth + 1));
      case ToolValue.ObjectValue object ->
          object.values().values().forEach(item -> check(item, depth + 1));
    }
  }
}
