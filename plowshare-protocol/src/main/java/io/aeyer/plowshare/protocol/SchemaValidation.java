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
public final class SchemaValidation {
  private SchemaValidation() {}

  public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

  public enum Status {
    VALID,
    INVALID,
    BUDGET_EXHAUSTED
  }

  /** Bounded diagnostics identify constraints without copying private instance values. */
  public record Issue(
      String instancePointer, String schemaPointer, String keyword, String message) {
    public Issue {
      Objects.requireNonNull(instancePointer);
      Objects.requireNonNull(schemaPointer);
      Objects.requireNonNull(keyword);
      Objects.requireNonNull(message);
      if (instancePointer.length() > 3500
          || schemaPointer.length() > 3500
          || keyword.length() > 128
          || message.length() > 512
          || !instancePointer.isEmpty() && !instancePointer.startsWith("/")
          || !schemaPointer.isEmpty() && !schemaPointer.startsWith("/"))
        throw new IllegalArgumentException("Invalid bounded schema diagnostic");
    }
  }

  public record Result(Status status, List<Issue> issues, boolean truncated) {
    public Result {
      Objects.requireNonNull(status);
      issues = List.copyOf(issues);
      if (issues.size() > 32
          || status == Status.VALID && (!issues.isEmpty() || truncated)
          || status != Status.VALID && issues.isEmpty())
        throw new IllegalArgumentException("Invalid schema validation result");
      int encoded = 100;
      for (Issue issue : issues) {
        encoded += 100;
        for (String field :
            List.of(
                issue.instancePointer(), issue.schemaPointer(), issue.keyword(), issue.message()))
          encoded +=
              SchemaCodec.compactJson(new SchemaValue.Text(field))
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                  .length;
      }
      if (encoded > 8192)
        throw new IllegalArgumentException("Schema diagnostics exceed byte bound");
    }
  }

  /**
   * Returns the first authoritative mismatch. One issue is within the 32-issue ceiling; failed
   * composition probes are not reported when another branch satisfies the composition. Work is
   * never retried with a fresh budget merely to collect diagnostics.
   */
  public static Result inspect(SchemaDefinition schema, SchemaValue value) {
    declaration(schema);
    SchemaValue.validate(value);
    var budget = new Budget();
    try {
      boolean valid = matches(schema, value, schema, budget, 0, "", "");
      return new Result(
          valid ? Status.VALID : Status.INVALID,
          valid ? List.of() : List.of(budget.issue),
          !valid && budget.truncated);
    } catch (WorkExhausted exhausted) {
      return new Result(
          Status.BUDGET_EXHAUSTED,
          List.of(new Issue("", "", "work", "Schema validation exceeded its work budget")),
          true);
    }
  }

  /** Check the entire declaration, including currently unused definitions and branches. */
  public static void declaration(SchemaDefinition root) {
    Objects.requireNonNull(root);
    if (root.type() == null || !root.type().values().equals(List.of("object")))
      throw new IllegalArgumentException("Metadata input schema must have object type");
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
            schema.multipleOf())) if (number != null) SchemaValue.portableNumber(number);
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

  private static SchemaValue literalValue(Literal value, Budget budget, int depth) {
    budget.step(depth + 1);
    var scalar =
        switch (value) {
          case Text text -> new SchemaValue.Text(text.value());
          case Decimal decimal -> new SchemaValue.Number(decimal.value());
          case Truth truth -> new SchemaValue.Truth(truth.value());
          case NullValue ignored -> new SchemaValue.Null();
        };
    SchemaValue.validate(scalar);
    return scalar;
  }

  private static SchemaDefinition reference(SchemaDefinition root, String ref) {
    if (!ref.startsWith("#/$defs/")
        || ref.substring(8).contains("/")
        || java.util.regex.Pattern.compile("~(?![01])").matcher(ref).find()
        || root.definitions() == null)
      throw new IllegalArgumentException("Schema references must select a root definition");
    String key = ref.substring(8).replace("~1", "/").replace("~0", "~");
    var value = root.definitions().get(key);
    if (value == null) throw new IllegalArgumentException("Unresolved tool schema reference");
    return value;
  }

  /** Refuse mismatches and exhausted work without granting execution or document authority. */
  public static void validate(SchemaDefinition schema, SchemaValue value) {
    declaration(schema);
    SchemaValue.validate(value);
    if (!matches(schema, value, schema, new Budget(), 0, "", ""))
      throw new IllegalArgumentException("Schema instance do not satisfy the offered schema");
  }

  private static boolean matches(
      SchemaDefinition s,
      SchemaValue value,
      SchemaDefinition root,
      Budget budget,
      int depth,
      String instance,
      String pointer) {
    budget.step(depth);
    if (s.ref() != null
        && !matches(
            reference(root, s.ref()),
            value,
            root,
            budget,
            depth + 1,
            instance,
            s.ref().substring(1))) return false;
    if (s.type() != null && s.type().values().stream().noneMatch(type -> type(type, value)))
      return budget.fail(instance, pointer, "type");
    if (s.constant() != null && !literal(s.constant(), value))
      return budget.fail(instance, pointer, "const");
    if (s.enumeration() != null && s.enumeration().stream().noneMatch(item -> literal(item, value)))
      return budget.fail(instance, pointer, "enum");
    if (s.allOf() != null)
      for (int index = 0; index < s.allOf().size(); index++)
        if (!matches(
            s.allOf().get(index),
            value,
            root,
            budget,
            depth + 1,
            instance,
            pointer + "/allOf/" + index)) return false;
    if (s.anyOf() != null) {
      boolean found = false;
      for (int index = 0; index < s.anyOf().size(); index++)
        if (matches(
            s.anyOf().get(index),
            value,
            root,
            budget,
            depth + 1,
            instance,
            pointer + "/anyOf/" + index)) {
          found = true;
          break;
        }
      if (!found) return budget.fail(instance, pointer, "anyOf");
    }
    if (s.oneOf() != null) {
      int count = 0;
      for (int index = 0; index < s.oneOf().size(); index++)
        if (matches(
            s.oneOf().get(index),
            value,
            root,
            budget,
            depth + 1,
            instance,
            pointer + "/oneOf/" + index)) count++;
      if (count != 1) return budget.fail(instance, pointer, "oneOf");
    }
    if (s.not() != null
        && matches(s.not(), value, root, budget, depth + 1, instance, pointer + "/not"))
      return budget.fail(instance, pointer, "not");
    if (s.when() != null) {
      boolean condition =
          matches(s.when(), value, root, budget, depth + 1, instance, pointer + "/if");
      var branch = condition ? s.then() : s.otherwise();
      if (branch != null
          && !matches(
              branch,
              value,
              root,
              budget,
              depth + 1,
              instance,
              pointer + (condition ? "/then" : "/else"))) return false;
    }
    if (value instanceof SchemaValue.Text text) {
      int count = text.value().codePointCount(0, text.value().length());
      if (!bounds(count, s.minLength(), s.maxLength()))
        return budget.fail(
            instance,
            pointer,
            s.minLength() != null && count < s.minLength() ? "minLength" : "maxLength");
    }
    if (value instanceof SchemaValue.Number number) {
      var n = number.value();
      if (s.minimum() != null && n.compareTo(s.minimum()) < 0
          || s.maximum() != null && n.compareTo(s.maximum()) > 0
          || s.exclusiveMinimum() != null && n.compareTo(s.exclusiveMinimum()) <= 0
          || s.exclusiveMaximum() != null && n.compareTo(s.exclusiveMaximum()) >= 0
          || s.multipleOf() != null && n.remainder(s.multipleOf()).signum() != 0)
        return budget.fail(
            instance,
            pointer,
            s.minimum() != null && n.compareTo(s.minimum()) < 0
                ? "minimum"
                : s.maximum() != null && n.compareTo(s.maximum()) > 0
                    ? "maximum"
                    : s.exclusiveMinimum() != null && n.compareTo(s.exclusiveMinimum()) <= 0
                        ? "exclusiveMinimum"
                        : s.exclusiveMaximum() != null && n.compareTo(s.exclusiveMaximum()) >= 0
                            ? "exclusiveMaximum"
                            : "multipleOf");
    }
    if (value instanceof SchemaValue.ObjectValue object) {
      var fields = object.values();
      if (!bounds(fields.size(), s.minProperties(), s.maxProperties()))
        return budget.fail(
            instance,
            pointer,
            s.minProperties() != null && fields.size() < s.minProperties()
                ? "minProperties"
                : "maxProperties");
      if (s.required() != null && !fields.keySet().containsAll(s.required()))
        return budget.fail(instance, pointer, "required");
      for (var field : fields.entrySet()) {
        var property = s.properties() == null ? null : s.properties().get(field.getKey());
        if (property != null) {
          if (!matches(
              property,
              field.getValue(),
              root,
              budget,
              depth + 1,
              instance + "/" + escape(field.getKey()),
              pointer + "/properties/" + escape(field.getKey()))) return false;
        } else if (s.additionalProperties() instanceof Allowed allowed && !allowed.value())
          return budget.fail(
              instance + "/" + escape(field.getKey()), pointer, "additionalProperties");
        else if (s.additionalProperties() instanceof Constrained constrained
            && !matches(
                constrained.schema(),
                field.getValue(),
                root,
                budget,
                depth + 1,
                instance + "/" + escape(field.getKey()),
                pointer + "/additionalProperties")) return false;
      }
    }
    if (value instanceof SchemaValue.Array array) {
      var items = array.values();
      if (!bounds(items.size(), s.minItems(), s.maxItems()))
        return budget.fail(
            instance,
            pointer,
            s.minItems() != null && items.size() < s.minItems() ? "minItems" : "maxItems");
      int prefix = s.prefixItems() == null ? 0 : s.prefixItems().size();
      boolean contained = false;
      for (int i = 0; i < items.size(); i++) {
        var item = items.get(i);
        var constraint = i < prefix ? s.prefixItems().get(i) : s.items();
        if (constraint != null
            && !matches(
                constraint,
                item,
                root,
                budget,
                depth + 1,
                instance + "/" + i,
                pointer + (i < prefix ? "/prefixItems/" + i : "/items"))) return false;
        if (s.contains() != null
            && matches(
                s.contains(),
                item,
                root,
                budget,
                depth + 1,
                instance + "/" + i,
                pointer + "/contains")) contained = true;
        if (Boolean.TRUE.equals(s.uniqueItems()))
          for (int j = 0; j < i; j++)
            if (equal(items.get(j), item, budget, depth + 1))
              return budget.fail(instance, pointer, "uniqueItems");
      }
      if (s.contains() != null && !contained) return budget.fail(instance, pointer, "contains");
    }
    return true;
  }

  private static boolean bounds(int value, Integer min, Integer max) {
    return (min == null || value >= min) && (max == null || value <= max);
  }

  private static boolean type(String type, SchemaValue value) {
    return switch (type) {
      case "string" -> value instanceof SchemaValue.Text;
      case "number" -> value instanceof SchemaValue.Number;
      case "integer" ->
          value instanceof SchemaValue.Number n && n.value().stripTrailingZeros().scale() <= 0;
      case "boolean" -> value instanceof SchemaValue.Truth;
      case "null" -> value instanceof SchemaValue.Null;
      case "array" -> value instanceof SchemaValue.Array;
      case "object" -> value instanceof SchemaValue.ObjectValue;
      default -> false;
    };
  }

  private static boolean literal(Literal literal, SchemaValue value) {
    return switch (literal) {
      case Text text -> value instanceof SchemaValue.Text v && text.value().equals(v.value());
      case Decimal number ->
          value instanceof SchemaValue.Number v && number.value().compareTo(v.value()) == 0;
      case Truth truth -> value instanceof SchemaValue.Truth v && truth.value() == v.value();
      case NullValue ignored -> value instanceof SchemaValue.Null;
    };
  }

  private static boolean equal(SchemaValue a, SchemaValue b, Budget budget, int depth) {
    budget.step(depth);
    if (a instanceof SchemaValue.Number x && b instanceof SchemaValue.Number y)
      return x.value().compareTo(y.value()) == 0;
    if (a instanceof SchemaValue.Array x && b instanceof SchemaValue.Array y) {
      if (x.values().size() != y.values().size()) return false;
      for (int i = 0; i < x.values().size(); i++)
        if (!equal(x.values().get(i), y.values().get(i), budget, depth + 1)) return false;
      return true;
    }
    if (a instanceof SchemaValue.ObjectValue x && b instanceof SchemaValue.ObjectValue y) {
      if (!x.values().keySet().equals(y.values().keySet())) return false;
      for (var key : x.values().keySet())
        if (!equal(x.values().get(key), y.values().get(key), budget, depth + 1)) return false;
      return true;
    }
    return a.equals(b);
  }

  private static final class Budget {
    int remaining = 10000;
    Issue issue;
    boolean truncated;

    boolean fail(String instance, String pointer, String keyword) {
      String location = pointer + "/" + keyword;
      // Two pointers plus the fixed message/envelope stay well below the 8 KiB error budget.
      truncated =
          instance.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + location.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > 3500;
      issue =
          new Issue(
              truncated ? "" : instance,
              truncated ? "" : location,
              keyword,
              "Metadata does not satisfy this constraint");
      return false;
    }

    void step(int depth) {
      if (depth > 64 || --remaining < 0) throw new WorkExhausted();
    }
  }

  private static String escape(String key) {
    return key.replace("~", "~0").replace("/", "~1");
  }

  public static final class WorkExhausted extends IllegalArgumentException {
    public WorkExhausted() {
      super("Schema validation exceeds work bound");
    }
  }
}
