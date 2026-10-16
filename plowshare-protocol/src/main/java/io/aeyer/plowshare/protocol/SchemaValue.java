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
 * Immutable portable schema instance, independent of tool or Relay invocation. Missing object
 * members differ from explicit null. Raw conversion belongs to a duplicate-checking JSON boundary.
 */
public sealed interface SchemaValue {
  record Text(@JsonValue String value) implements SchemaValue {
    public Text {
      Objects.requireNonNull(value, "text");
      if (value.length() > 4096 || value.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Schema text exceeds bounds");
    }
  }

  record Number(@JsonValue BigDecimal value) implements SchemaValue {
    public Number {
      Objects.requireNonNull(value, "number");
      portableNumber(value);
      value = value.stripTrailingZeros();
    }
  }

  record Truth(@JsonValue boolean value) implements SchemaValue {}

  record Null() implements SchemaValue {
    @JsonValue
    public String wireValue() {
      return null;
    }
  }

  record Array(@JsonValue List<SchemaValue> values) implements SchemaValue {
    public Array {
      values = List.copyOf(values);
      if (values.size() > 1024) throw new IllegalArgumentException("Too many schema items");
    }
  }

  record ObjectValue(@JsonValue Map<String, SchemaValue> values) implements SchemaValue {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ObjectValue decode(Map<?, ?> source) {
      return (ObjectValue) SchemaValue.fromWire(source);
    }

    public ObjectValue {
      if (values.size() > 1024) throw new IllegalArgumentException("Too many schema fields");
      var copy = new LinkedHashMap<String, SchemaValue>();
      values.forEach(
          (key, value) -> {
            if (key == null
                || key.isEmpty()
                || key.length() > 1024
                || key.codePoints().anyMatch(Character::isISOControl))
              throw new IllegalArgumentException("Invalid schema field name");
            copy.put(key, Objects.requireNonNull(value, "schema field"));
          });
      values = Collections.unmodifiableMap(copy);
    }
  }

  /** The parser must reject duplicate fields and preserve decimals before calling this boundary. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  static SchemaValue fromWire(Object value) {
    return new Decoder().decode(value, 0);
  }

  /** Defends global tree bounds for values built through typed constructors as well as codecs. */
  static void validate(SchemaValue value) {
    new Decoder().check(value, 0);
  }

  static void portableNumber(BigDecimal value) {
    if (value.abs().compareTo(BigDecimal.valueOf(9007199254740991L)) > 0
        || value.stripTrailingZeros().scale() > 18
        || BigDecimal.valueOf(value.doubleValue()).compareTo(value) != 0)
      throw new IllegalArgumentException("Schema number exceeds portable range");
  }

  final class Decoder {
    private int remaining = 10000;
    private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();

    private void step(int depth) {
      if (depth > 32 || --remaining < 0)
        throw new IllegalArgumentException("Schema instance exceeds structural bounds");
    }

    private SchemaValue decode(Object source, int depth) {
      step(depth);
      if (source == null) return new Null();
      if (source instanceof String text) return new Text(text);
      if (source instanceof Boolean flag) return new Truth(flag);
      if (source instanceof java.lang.Number number)
        return new Number(new BigDecimal(number.toString()));
      if (visiting.put(source, true) != null)
        throw new IllegalArgumentException("Cyclic schema instance");
      try {
        if (source instanceof List<?> list) {
          if (list.size() > 1024) throw new IllegalArgumentException("Too many schema items");
          return new Array(list.stream().map(item -> decode(item, depth + 1)).toList());
        }
        if (source instanceof Map<?, ?> map) {
          if (map.size() > 1024) throw new IllegalArgumentException("Too many schema fields");
          var fields = new LinkedHashMap<String, SchemaValue>();
          map.forEach(
              (key, value) -> {
                if (!(key instanceof String name))
                  throw new IllegalArgumentException("Invalid schema key");
                fields.put(name, decode(value, depth + 1));
              });
          return new ObjectValue(fields);
        }
        throw new IllegalArgumentException("Unsupported schema instance");
      } finally {
        visiting.remove(source);
      }
    }

    private void check(SchemaValue value, int depth) {
      step(depth);
      switch (Objects.requireNonNull(value)) {
        case Text ignored -> {}
        case Number number -> portableNumber(number.value());
        case Truth ignored -> {}
        case Null ignored -> {}
        case Array array -> array.values().forEach(item -> check(item, depth + 1));
        case ObjectValue object -> object.values().values().forEach(item -> check(item, depth + 1));
      }
    }
  }
}
