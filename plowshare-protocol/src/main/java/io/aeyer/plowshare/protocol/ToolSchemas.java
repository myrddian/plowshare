package io.aeyer.plowshare.protocol;

import io.aeyer.plowshare.protocol.SchemaDefinition.*;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded executable profile of the shared schema vocabulary. Format is an annotation; patterns,
 * anchors and resource identifiers are refused rather than interpreted differently across SDKs.
 * References resolve only root $defs, without network access. Validation confers no authority.
 */
public final class ToolSchemas {
  private ToolSchemas() {}

  public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  /** Check the entire declaration, including currently unused definitions and branches. */
  public static void declaration(SchemaDefinition root) {
    Objects.requireNonNull(root);
    if (root.type() == null || !root.type().values().equals(List.of("object")))
      throw new IllegalArgumentException("Tool input schema must have object type");
    check(root, root, new Budget(), 0);
  }

  private static void check(
      SchemaDefinition schema, SchemaDefinition root, Budget budget, int depth) {
    budget.step(depth);
    if (schema.schemaUri() != null && !DIALECT.equals(schema.schemaUri())
        || schema.id() != null
        || schema.anchor() != null
        || schema.pattern() != null
        || schema.patternProperties() != null)
      throw new IllegalArgumentException("Unsupported executable schema feature");
    if (schema.ref() != null) check(reference(root, schema.ref()), root, budget, depth + 1);
    for (BigDecimal number :
        Arrays.asList(
            schema.minimum(),
            schema.maximum(),
            schema.exclusiveMinimum(),
            schema.exclusiveMaximum(),
            schema.multipleOf())) if (number != null) ToolValue.number(number);
    for (var scalar : Arrays.asList(schema.constant(), schema.defaultValue()))
      if (scalar != null) literalValue(scalar, budget, depth);
    for (var list : Arrays.asList(schema.enumeration(), schema.examples()))
      if (list != null) {
        for (int i = 0; i < list.size(); i++) {
          var value = literalValue(list.get(i), budget, depth);
          if (list == schema.enumeration())
            for (int j = 0; j < i; j++)
              if (literal(list.get(j), value))
                throw new IllegalArgumentException("Duplicate enum literal");
        }
      }
    for (var map : Arrays.asList(schema.definitions(), schema.properties()))
      if (map != null) map.values().forEach(value -> check(value, root, budget, depth + 1));
    for (var list :
        Arrays.asList(schema.prefixItems(), schema.allOf(), schema.anyOf(), schema.oneOf()))
      if (list != null) list.forEach(value -> check(value, root, budget, depth + 1));
    for (var child :
        Arrays.asList(
            schema.items(),
            schema.contains(),
            schema.not(),
            schema.when(),
            schema.then(),
            schema.otherwise())) if (child != null) check(child, root, budget, depth + 1);
    if (schema.additionalProperties() instanceof Constrained value)
      check(value.schema(), root, budget, depth + 1);
  }

  private static ToolValue literalValue(Literal value, Budget budget, int depth) {
    budget.step(depth + 1);
    var scalar =
        switch (value) {
          case Text text -> new IntegrationPayload.TextParameter(text.value());
          case Decimal decimal -> new IntegrationPayload.NumberParameter(decimal.value());
          case Truth truth -> new IntegrationPayload.BooleanParameter(truth.value());
          case NullValue ignored -> new ToolValue.Null();
        };
    ToolValue.validate(scalar);
    return scalar;
  }

  private static SchemaDefinition reference(SchemaDefinition root, String ref) {
    if (!ref.startsWith("#/$defs/")
        || ref.substring(8).contains("/")
        || java.util.regex.Pattern.compile("~(?![01])").matcher(ref).find()
        || root.definitions() == null)
      throw new IllegalArgumentException("Tool schema references must select a root definition");
    String key = ref.substring(8).replace("~1", "/").replace("~0", "~");
    var value = root.definitions().get(key);
    if (value == null) throw new IllegalArgumentException("Unresolved tool schema reference");
    return value;
  }

  /** Refuse mismatches and exhausted work before publishing or executing an external invocation. */
  public static void validate(SchemaDefinition schema, ToolValue value) {
    declaration(schema);
    ToolValue.validate(value);
    if (!matches(schema, value, schema, new Budget(), 0))
      throw new IllegalArgumentException("Tool arguments do not satisfy the offered schema");
  }

  private static boolean matches(
      SchemaDefinition s, ToolValue value, SchemaDefinition root, Budget budget, int depth) {
    budget.step(depth);
    if (s.ref() != null && !matches(reference(root, s.ref()), value, root, budget, depth + 1))
      return false;
    if (s.type() != null && s.type().values().stream().noneMatch(type -> type(type, value)))
      return false;
    if (s.constant() != null && !literal(s.constant(), value)) return false;
    if (s.enumeration() != null && s.enumeration().stream().noneMatch(item -> literal(item, value)))
      return false;
    if (s.allOf() != null)
      for (var branch : s.allOf())
        if (!matches(branch, value, root, budget, depth + 1)) return false;
    if (s.anyOf() != null) {
      boolean found = false;
      for (var branch : s.anyOf())
        if (matches(branch, value, root, budget, depth + 1)) {
          found = true;
          break;
        }
      if (!found) return false;
    }
    if (s.oneOf() != null) {
      int count = 0;
      for (var branch : s.oneOf()) if (matches(branch, value, root, budget, depth + 1)) count++;
      if (count != 1) return false;
    }
    if (s.not() != null && matches(s.not(), value, root, budget, depth + 1)) return false;
    if (s.when() != null) {
      var branch = matches(s.when(), value, root, budget, depth + 1) ? s.then() : s.otherwise();
      if (branch != null && !matches(branch, value, root, budget, depth + 1)) return false;
    }
    if (value instanceof IntegrationPayload.TextParameter text) {
      int count = text.value().codePointCount(0, text.value().length());
      if (!bounds(count, s.minLength(), s.maxLength())) return false;
    }
    if (value instanceof IntegrationPayload.NumberParameter number) {
      var n = number.value();
      if (s.minimum() != null && n.compareTo(s.minimum()) < 0
          || s.maximum() != null && n.compareTo(s.maximum()) > 0
          || s.exclusiveMinimum() != null && n.compareTo(s.exclusiveMinimum()) <= 0
          || s.exclusiveMaximum() != null && n.compareTo(s.exclusiveMaximum()) >= 0
          || s.multipleOf() != null && n.remainder(s.multipleOf()).signum() != 0) return false;
    }
    if (value instanceof ToolValue.ObjectValue object) {
      var fields = object.values();
      if (!bounds(fields.size(), s.minProperties(), s.maxProperties())) return false;
      if (s.required() != null && !fields.keySet().containsAll(s.required())) return false;
      for (var field : fields.entrySet()) {
        var property = s.properties() == null ? null : s.properties().get(field.getKey());
        if (property != null) {
          if (!matches(property, field.getValue(), root, budget, depth + 1)) return false;
        } else if (s.additionalProperties() instanceof Allowed allowed && !allowed.value())
          return false;
        else if (s.additionalProperties() instanceof Constrained constrained
            && !matches(constrained.schema(), field.getValue(), root, budget, depth + 1))
          return false;
      }
    }
    if (value instanceof ToolValue.Array array) {
      var items = array.values();
      if (!bounds(items.size(), s.minItems(), s.maxItems())) return false;
      int prefix = s.prefixItems() == null ? 0 : s.prefixItems().size();
      boolean contained = false;
      for (int i = 0; i < items.size(); i++) {
        var item = items.get(i);
        var constraint = i < prefix ? s.prefixItems().get(i) : s.items();
        if (constraint != null && !matches(constraint, item, root, budget, depth + 1)) return false;
        if (s.contains() != null && matches(s.contains(), item, root, budget, depth + 1))
          contained = true;
        if (Boolean.TRUE.equals(s.uniqueItems()))
          for (int j = 0; j < i; j++)
            if (equal(items.get(j), item, budget, depth + 1)) return false;
      }
      if (s.contains() != null && !contained) return false;
    }
    return true;
  }

  private static boolean bounds(int value, Integer min, Integer max) {
    return (min == null || value >= min) && (max == null || value <= max);
  }

  private static boolean type(String type, ToolValue value) {
    return switch (type) {
      case "string" -> value instanceof IntegrationPayload.TextParameter;
      case "number" -> value instanceof IntegrationPayload.NumberParameter;
      case "integer" ->
          value instanceof IntegrationPayload.NumberParameter n
              && n.value().stripTrailingZeros().scale() <= 0;
      case "boolean" -> value instanceof IntegrationPayload.BooleanParameter;
      case "null" -> value instanceof ToolValue.Null;
      case "array" -> value instanceof ToolValue.Array;
      case "object" -> value instanceof ToolValue.ObjectValue;
      default -> false;
    };
  }

  private static boolean literal(Literal literal, ToolValue value) {
    return switch (literal) {
      case Text text ->
          value instanceof IntegrationPayload.TextParameter v && text.value().equals(v.value());
      case Decimal number ->
          value instanceof IntegrationPayload.NumberParameter v
              && number.value().compareTo(v.value()) == 0;
      case Truth truth ->
          value instanceof IntegrationPayload.BooleanParameter v && truth.value() == v.value();
      case NullValue ignored -> value instanceof ToolValue.Null;
    };
  }

  private static boolean equal(ToolValue a, ToolValue b, Budget budget, int depth) {
    budget.step(depth);
    if (a instanceof IntegrationPayload.NumberParameter x
        && b instanceof IntegrationPayload.NumberParameter y)
      return x.value().compareTo(y.value()) == 0;
    if (a instanceof ToolValue.Array x && b instanceof ToolValue.Array y) {
      if (x.values().size() != y.values().size()) return false;
      for (int i = 0; i < x.values().size(); i++)
        if (!equal(x.values().get(i), y.values().get(i), budget, depth + 1)) return false;
      return true;
    }
    if (a instanceof ToolValue.ObjectValue x && b instanceof ToolValue.ObjectValue y) {
      if (!x.values().keySet().equals(y.values().keySet())) return false;
      for (var key : x.values().keySet())
        if (!equal(x.values().get(key), y.values().get(key), budget, depth + 1)) return false;
      return true;
    }
    return a.equals(b);
  }

  private static final class Budget {
    int remaining = 10000;

    void step(int depth) {
      if (depth > 64 || --remaining < 0)
        throw new IllegalArgumentException("Tool schema validation exceeds work bound");
    }
  }
}
