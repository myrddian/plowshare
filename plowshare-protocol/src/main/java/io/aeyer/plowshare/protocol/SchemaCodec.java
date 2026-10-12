package io.aeyer.plowshare.protocol;

import io.aeyer.plowshare.protocol.SchemaDefinition.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict configuration boundary for schemas built by Java tools or loaded from YAML. */
public final class SchemaCodec {
  private SchemaCodec() {}

  private static final Set<String> FIELDS =
      Set.of(
          "type",
          "title",
          "description",
          "$schema",
          "$id",
          "$ref",
          "$anchor",
          "$defs",
          "properties",
          "patternProperties",
          "required",
          "additionalProperties",
          "items",
          "prefixItems",
          "contains",
          "allOf",
          "anyOf",
          "oneOf",
          "not",
          "if",
          "then",
          "else",
          "enum",
          "const",
          "default",
          "examples",
          "minimum",
          "maximum",
          "exclusiveMinimum",
          "exclusiveMaximum",
          "multipleOf",
          "minLength",
          "maxLength",
          "pattern",
          "format",
          "minItems",
          "maxItems",
          "uniqueItems",
          "minProperties",
          "maxProperties",
          "readOnly",
          "writeOnly",
          "deprecated");

  /**
   * Decodes before a schema enters a model request. Cycles, unsupported fields and coercion fail.
   */
  public static SchemaDefinition decode(Map<?, ?> source) {
    return new Decoder().schema(source, "schema", 0);
  }

  /** JSON serialization boundary, shared by the canonical fingerprint and native codecs. */
  public static Map<String, Object> wire(SchemaDefinition schema) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var component : SchemaDefinition.class.getRecordComponents()) {
      try {
        Object value = component.getAccessor().invoke(schema);
        if (value == null) continue;
        var annotation =
            component
                .getAccessor()
                .getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
        result.put(annotation == null ? component.getName() : annotation.value(), wireValue(value));
      } catch (ReflectiveOperationException broken) {
        throw new IllegalStateException("Schema serialization failed", broken);
      }
    }
    return result;
  }

  private static Object wireValue(Object value) {
    if (value instanceof SchemaDefinition schema) return wire(schema);
    if (value instanceof Types types) return types.wireValue();
    if (value instanceof Allowed allowed) return allowed.value();
    if (value instanceof Constrained constrained) return wire(constrained.schema());
    if (value instanceof Text text) return text.value();
    if (value instanceof Decimal decimal) return decimal.value();
    if (value instanceof Truth truth) return truth.value();
    if (value instanceof NullValue) return null;
    if (value instanceof List<?> list) return list.stream().map(SchemaCodec::wireValue).toList();
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> output = new LinkedHashMap<>();
      map.forEach((key, entry) -> output.put((String) key, wireValue(entry)));
      return output;
    }
    return value;
  }

  /**
   * Stable SHA-256 identity: sorted UTF-8 keys, exact decimal tokens, no JSON writer dependence.
   */
  public static String fingerprint(SchemaDefinition schema) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      canonical(wire(schema)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static String canonical(Object value) {
    if (value == null) return "z;";
    if (value instanceof Boolean flag) return flag ? "b1;" : "b0;";
    if (value instanceof Number number) {
      var decimal = new BigDecimal(number.toString()).stripTrailingZeros();
      return "n" + decimal.unscaledValue() + "e" + -decimal.scale() + ";";
    }
    if (value instanceof String text)
      return "s"
          + java.util.HexFormat.of()
              .formatHex(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          + ";";
    if (value instanceof List<?> list)
      return "["
          + list.stream().map(SchemaCodec::canonical).collect(java.util.stream.Collectors.joining())
          + "]";
    if (value instanceof Map<?, ?> map)
      return "{"
          + map.entrySet().stream()
              .sorted(java.util.Comparator.comparing(entry -> canonical(entry.getKey())))
              .map(entry -> canonical(entry.getKey()) + canonical(entry.getValue()))
              .collect(java.util.stream.Collectors.joining())
          + "}";
    throw new IllegalArgumentException("Invalid canonical schema value");
  }

  private static final class Decoder {
    private final IdentityHashMap<Map<?, ?>, Boolean> visiting = new IdentityHashMap<>();
    private int remaining = 10000;

    SchemaDefinition schema(Map<?, ?> source, String path, int depth) {
      if (source == null || depth > 64 || --remaining < 0 || visiting.put(source, true) != null)
        throw new IllegalArgumentException(path + " exceeds schema bounds or has a cycle");
      try {
        for (var entry : source.entrySet()) {
          if (!(entry.getKey() instanceof String key) || !FIELDS.contains(key))
            throw new IllegalArgumentException(path + " has an unsupported keyword");
          if (entry.getValue() == null && !Set.of("const", "default").contains(key))
            throw new IllegalArgumentException(path + "." + key + " must not be null");
        }
        return new SchemaDefinition(
            types(v(source, "type"), path + ".type"),
            string(v(source, "title"), path + ".title"),
            string(v(source, "description"), path + ".description"),
            string(v(source, "$schema"), path + ".$schema"),
            string(v(source, "$id"), path + ".$id"),
            string(v(source, "$ref"), path + ".$ref"),
            string(v(source, "$anchor"), path + ".$anchor"),
            properties(v(source, "$defs"), path + ".$defs", depth + 1),
            properties(v(source, "properties"), path + ".properties", depth + 1),
            properties(v(source, "patternProperties"), path + ".patternProperties", depth + 1),
            strings(v(source, "required"), path + ".required"),
            rule(v(source, "additionalProperties"), path + ".additionalProperties", depth + 1),
            nested(v(source, "items"), path + ".items", depth + 1),
            schemas(v(source, "prefixItems"), path + ".prefixItems", depth + 1),
            nested(v(source, "contains"), path + ".contains", depth + 1),
            schemas(v(source, "allOf"), path + ".allOf", depth + 1),
            schemas(v(source, "anyOf"), path + ".anyOf", depth + 1),
            schemas(v(source, "oneOf"), path + ".oneOf", depth + 1),
            nested(v(source, "not"), path + ".not", depth + 1),
            nested(v(source, "if"), path + ".if", depth + 1),
            nested(v(source, "then"), path + ".then", depth + 1),
            nested(v(source, "else"), path + ".else", depth + 1),
            literals(v(source, "enum"), path + ".enum"),
            source.containsKey("const") ? literal(v(source, "const"), path + ".const") : null,
            source.containsKey("default") ? literal(v(source, "default"), path + ".default") : null,
            literals(v(source, "examples"), path + ".examples"),
            decimal(v(source, "minimum"), path + ".minimum"),
            decimal(v(source, "maximum"), path + ".maximum"),
            decimal(v(source, "exclusiveMinimum"), path + ".exclusiveMinimum"),
            decimal(v(source, "exclusiveMaximum"), path + ".exclusiveMaximum"),
            decimal(v(source, "multipleOf"), path + ".multipleOf"),
            integer(v(source, "minLength"), path + ".minLength"),
            integer(v(source, "maxLength"), path + ".maxLength"),
            string(v(source, "pattern"), path + ".pattern"),
            string(v(source, "format"), path + ".format"),
            integer(v(source, "minItems"), path + ".minItems"),
            integer(v(source, "maxItems"), path + ".maxItems"),
            bool(v(source, "uniqueItems"), path + ".uniqueItems"),
            integer(v(source, "minProperties"), path + ".minProperties"),
            integer(v(source, "maxProperties"), path + ".maxProperties"),
            bool(v(source, "readOnly"), path + ".readOnly"),
            bool(v(source, "writeOnly"), path + ".writeOnly"),
            bool(v(source, "deprecated"), path + ".deprecated"));
      } finally {
        visiting.remove(source);
      }
    }

    SchemaDefinition nested(Object value, String path, int depth) {
      if (value == null) return null;
      if (!(value instanceof Map<?, ?> map)) throw invalid(path);
      return schema(map, path, depth);
    }

    Map<String, SchemaDefinition> properties(Object value, String path, int depth) {
      if (value == null) return null;
      if (!(value instanceof Map<?, ?> map) || map.size() > 10000) throw invalid(path);
      Map<String, SchemaDefinition> result = new LinkedHashMap<>();
      map.forEach(
          (key, entry) -> {
            if (!(key instanceof String name) || entry == null) throw invalid(path);
            result.put(name, nested(entry, path + "." + name, depth));
          });
      return result;
    }

    List<SchemaDefinition> schemas(Object value, String path, int depth) {
      if (value == null) return null;
      List<SchemaDefinition> result = new ArrayList<>();
      for (Object item : array(value, path)) {
        if (item == null) throw invalid(path);
        result.add(nested(item, path, depth));
      }
      return result;
    }

    Rule rule(Object value, String path, int depth) {
      if (value == null) return null;
      return value instanceof Boolean allowed
          ? new Allowed(allowed)
          : new Constrained(nested(value, path, depth));
    }
  }

  private static Object v(Map<?, ?> source, String key) {
    return source.get(key);
  }

  private static IllegalArgumentException invalid(String path) {
    return new IllegalArgumentException(path + " has the wrong schema value type");
  }

  private static String string(Object value, String path) {
    if (value == null) return null;
    if (!(value instanceof String text)) throw invalid(path);
    return text;
  }

  private static Boolean bool(Object value, String path) {
    if (value == null) return null;
    if (!(value instanceof Boolean result)) throw invalid(path);
    return result;
  }

  private static BigDecimal decimal(Object value, String path) {
    if (value == null) return null;
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof BigDecimal
        || value instanceof java.math.BigInteger
        || value instanceof Float
        || value instanceof Double)) throw invalid(path);
    try {
      return new BigDecimal(value.toString());
    } catch (NumberFormatException invalid) {
      throw invalid(path);
    }
  }

  private static Integer integer(Object value, String path) {
    BigDecimal number = decimal(value, path);
    if (number == null) return null;
    try {
      return number.intValueExact();
    } catch (ArithmeticException invalid) {
      throw invalid(path);
    }
  }

  private static List<?> array(Object value, String path) {
    if (!(value instanceof List<?> list) || list.size() > 10000) throw invalid(path);
    return list;
  }

  private static List<String> strings(Object value, String path) {
    if (value == null) return null;
    List<String> result = new ArrayList<>();
    for (Object item : array(value, path)) {
      if (item == null) throw invalid(path);
      result.add(string(item, path));
    }
    return result;
  }

  private static Types types(Object value, String path) {
    if (value == null) return null;
    return value instanceof String name
        ? new Types(List.of(name), false)
        : new Types(strings(value, path), true);
  }

  private static Literal literal(Object value, String path) {
    if (value == null) return new NullValue();
    if (value instanceof String text) return new Text(text);
    if (value instanceof Boolean truth) return new Truth(truth);
    return new Decimal(decimal(value, path));
  }

  private static List<Literal> literals(Object value, String path) {
    if (value == null) return null;
    List<Literal> result = new ArrayList<>();
    for (Object entry : array(value, path)) result.add(literal(entry, path));
    return result;
  }
}
