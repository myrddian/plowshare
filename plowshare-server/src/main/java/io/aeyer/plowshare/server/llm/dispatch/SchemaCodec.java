package io.aeyer.plowshare.server.llm.dispatch;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.aeyer.plowshare.server.llm.dispatch.SchemaDefinition.*;
import java.io.IOException;
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

  public static final class TypesSerializer extends JsonSerializer<Types> {
    @Override
    public void serialize(Types value, JsonGenerator out, SerializerProvider provider)
        throws IOException {
      if (!value.union()) out.writeString(value.values().getFirst());
      else {
        out.writeStartArray();
        for (String type : value.values()) out.writeString(type);
        out.writeEndArray();
      }
    }
  }

  public static final class RuleSerializer extends JsonSerializer<Rule> {
    @Override
    public void serialize(Rule value, JsonGenerator out, SerializerProvider provider)
        throws IOException {
      if (value instanceof Allowed allowed) out.writeBoolean(allowed.value());
      else provider.defaultSerializeValue(((Constrained) value).schema(), out);
    }
  }

  public static final class LiteralSerializer extends JsonSerializer<Literal> {
    @Override
    public void serialize(Literal value, JsonGenerator out, SerializerProvider provider)
        throws IOException {
      switch (value) {
        case Text text -> out.writeString(text.value());
        case Decimal number -> out.writeNumber(number.value());
        case Truth truth -> out.writeBoolean(truth.value());
        case NullValue ignored -> out.writeNull();
      }
    }
  }
}
