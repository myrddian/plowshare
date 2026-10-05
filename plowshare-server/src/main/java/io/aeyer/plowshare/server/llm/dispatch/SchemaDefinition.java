package io.aeyer.plowshare.server.llm.dispatch;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit supported JSON Schema vocabulary for model requests. Arbitrary extension keywords and
 * object/array-valued constants are refused by {@link SchemaCodec}, never silently omitted.
 * Properties retain insertion order; keyword order is canonical so prompt accounting and transport
 * serialization agree. A schema does not authorize a tool call or validate the model's answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaDefinition(
    Types type,
    String title,
    String description,
    @JsonProperty("$schema") String schemaUri,
    @JsonProperty("$id") String id,
    @JsonProperty("$ref") String ref,
    @JsonProperty("$anchor") String anchor,
    @JsonProperty("$defs") Map<String, SchemaDefinition> definitions,
    Map<String, SchemaDefinition> properties,
    Map<String, SchemaDefinition> patternProperties,
    List<String> required,
    Rule additionalProperties,
    SchemaDefinition items,
    List<SchemaDefinition> prefixItems,
    SchemaDefinition contains,
    List<SchemaDefinition> allOf,
    List<SchemaDefinition> anyOf,
    List<SchemaDefinition> oneOf,
    SchemaDefinition not,
    @JsonProperty("if") SchemaDefinition when,
    SchemaDefinition then,
    @JsonProperty("else") SchemaDefinition otherwise,
    @JsonProperty("enum") List<Literal> enumeration,
    @JsonProperty("const") Literal constant,
    @JsonProperty("default") Literal defaultValue,
    List<Literal> examples,
    BigDecimal minimum,
    BigDecimal maximum,
    BigDecimal exclusiveMinimum,
    BigDecimal exclusiveMaximum,
    BigDecimal multipleOf,
    Integer minLength,
    Integer maxLength,
    String pattern,
    String format,
    Integer minItems,
    Integer maxItems,
    Boolean uniqueItems,
    Integer minProperties,
    Integer maxProperties,
    Boolean readOnly,
    Boolean writeOnly,
    Boolean deprecated) {
  public SchemaDefinition {
    title = text(title, "title", 4096);
    description = text(description, "description", 32768);
    schemaUri = text(schemaUri, "$schema", 4096);
    id = text(id, "$id", 4096);
    ref = text(ref, "$ref", 4096);
    anchor = text(anchor, "$anchor", 4096);
    definitions = schemas(definitions, "$defs");
    properties = schemas(properties, "properties");
    patternProperties = schemas(patternProperties, "patternProperties");
    required = list(required, "required");
    prefixItems = list(prefixItems, "prefixItems");
    allOf = list(allOf, "allOf");
    anyOf = list(anyOf, "anyOf");
    oneOf = list(oneOf, "oneOf");
    enumeration = list(enumeration, "enum");
    examples = list(examples, "examples");
    if (minimum != null && (minimum.precision() > 1000 || Math.abs((long) minimum.scale()) > 1000))
      throw new IllegalArgumentException("minimum exceeds numeric bounds");
    if (maximum != null && (maximum.precision() > 1000 || Math.abs((long) maximum.scale()) > 1000))
      throw new IllegalArgumentException("maximum exceeds numeric bounds");
    if (exclusiveMinimum != null
        && (exclusiveMinimum.precision() > 1000
            || Math.abs((long) exclusiveMinimum.scale()) > 1000))
      throw new IllegalArgumentException("exclusiveMinimum exceeds numeric bounds");
    if (exclusiveMaximum != null
        && (exclusiveMaximum.precision() > 1000
            || Math.abs((long) exclusiveMaximum.scale()) > 1000))
      throw new IllegalArgumentException("exclusiveMaximum exceeds numeric bounds");
    if (multipleOf != null
        && (multipleOf.precision() > 1000 || Math.abs((long) multipleOf.scale()) > 1000))
      throw new IllegalArgumentException("multipleOf exceeds numeric bounds");
    if (minLength != null && minLength < 0)
      throw new IllegalArgumentException("minLength must be nonnegative");
    if (maxLength != null && maxLength < 0)
      throw new IllegalArgumentException("maxLength must be nonnegative");
    pattern = text(pattern, "pattern", 4096);
    format = text(format, "format", 4096);
    if (minItems != null && minItems < 0)
      throw new IllegalArgumentException("minItems must be nonnegative");
    if (maxItems != null && maxItems < 0)
      throw new IllegalArgumentException("maxItems must be nonnegative");
    if (minProperties != null && minProperties < 0)
      throw new IllegalArgumentException("minProperties must be nonnegative");
    if (maxProperties != null && maxProperties < 0)
      throw new IllegalArgumentException("maxProperties must be nonnegative");
    if (required != null) {
      required.forEach(value -> key(value, "required"));
      if (Set.copyOf(required).size() != required.size())
        throw new IllegalArgumentException("required has duplicate names");
    }
    if (multipleOf != null && multipleOf.signum() <= 0)
      throw new IllegalArgumentException("multipleOf must be positive");
    range(minimum, maximum, "numeric");
    range(minLength, maxLength, "length");
    range(minItems, maxItems, "items");
    range(minProperties, maxProperties, "properties");
    if (pattern != null) {
      try {
        java.util.regex.Pattern.compile(pattern);
      } catch (java.util.regex.PatternSyntaxException invalid) {
        throw new IllegalArgumentException("invalid schema pattern", invalid);
      }
    }
    if (patternProperties != null)
      patternProperties
          .keySet()
          .forEach(
              value -> {
                try {
                  java.util.regex.Pattern.compile(value);
                } catch (java.util.regex.PatternSyntaxException invalid) {
                  throw new IllegalArgumentException("invalid property pattern", invalid);
                }
              });
    for (List<SchemaDefinition> combination : java.util.Arrays.asList(allOf, anyOf, oneOf)) {
      if (combination != null && combination.isEmpty())
        throw new IllegalArgumentException("schema combinations must not be empty");
    }
    if (enumeration != null
        && (enumeration.isEmpty() || Set.copyOf(enumeration).size() != enumeration.size()))
      throw new IllegalArgumentException("enum must contain distinct values");
  }

  /** A type constraint preserves scalar versus union spelling on the wire. */
  @JsonSerialize(using = SchemaCodec.TypesSerializer.class)
  public record Types(List<String> values, boolean union) {
    public Types {
      values = Objects.requireNonNull(list(values, "type"), "type");
      if (values.isEmpty()
          || !union && values.size() != 1
          || Set.copyOf(values).size() != values.size())
        throw new IllegalArgumentException("invalid schema types");
      if (!Set.of("object", "array", "string", "number", "integer", "boolean", "null")
          .containsAll(values)) throw new IllegalArgumentException("unknown schema type");
    }
  }

  /** Additional properties are either permitted/refused or constrained by a schema. */
  @JsonSerialize(using = SchemaCodec.RuleSerializer.class)
  public sealed interface Rule permits Allowed, Constrained {}

  public record Allowed(boolean value) implements Rule {}

  public record Constrained(SchemaDefinition schema) implements Rule {
    public Constrained {
      Objects.requireNonNull(schema, "schema");
    }
  }

  /** Scalar constants have explicit types; they cannot contain an arbitrary property bag. */
  @JsonSerialize(using = SchemaCodec.LiteralSerializer.class)
  public sealed interface Literal permits Text, Decimal, Truth, NullValue {}

  public record Text(String value) implements Literal {
    public Text {
      value = Objects.requireNonNull(text(value, "constant", 32768));
    }
  }

  public record Decimal(BigDecimal value) implements Literal {
    public Decimal {
      Objects.requireNonNull(value);
      if (value.precision() > 1000 || Math.abs((long) value.scale()) > 1000)
        throw new IllegalArgumentException("constant exceeds numeric bounds");
    }
  }

  public record Truth(boolean value) implements Literal {}

  public record NullValue() implements Literal {}

  private static String text(String value, String field, int limit) {
    if (value != null && (value.length() > limit || value.indexOf('\0') >= 0))
      throw new IllegalArgumentException(field + " exceeds text bounds");
    return value;
  }

  private static void key(String value, String field) {
    if (value == null
        || value.isEmpty()
        || value.length() > 1024
        || value.codePoints().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException(field + " has an invalid name");
  }

  private static <T> List<T> list(List<T> values, String field) {
    if (values == null) return null;
    if (values.size() > 10000) throw new IllegalArgumentException(field + " has too many entries");
    return List.copyOf(values);
  }

  private static Map<String, SchemaDefinition> schemas(
      Map<String, SchemaDefinition> values, String field) {
    if (values == null) return null;
    if (values.size() > 10000) throw new IllegalArgumentException(field + " has too many entries");
    Map<String, SchemaDefinition> copy = new LinkedHashMap<>();
    values.forEach(
        (name, schema) -> {
          key(name, field);
          copy.put(name, Objects.requireNonNull(schema));
        });
    return Collections.unmodifiableMap(copy);
  }

  private static <T extends Comparable<T>> void range(T minimum, T maximum, String field) {
    if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
      throw new IllegalArgumentException(field + " minimum exceeds maximum");
  }
}
