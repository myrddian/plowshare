package io.aeyer.plowshare.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Compatibility facade for the shared bounded validator; tool wire values and hashes are unchanged.
 */
public final class ToolSchemas {
  private ToolSchemas() {}

  public static final String DIALECT = SchemaValidation.DIALECT;

  /** Check the whole executable declaration, including unused definitions and branches. */
  public static void declaration(SchemaDefinition schema) {
    Objects.requireNonNull(schema);
    if (schema.type() == null || !schema.type().values().equals(List.of("object")))
      throw new IllegalArgumentException("Tool input schema must have object type");
    SchemaValidation.declaration(schema);
  }

  /** Refuse mismatches before an external invocation is admitted or executed. */
  public static void validate(SchemaDefinition schema, ToolValue value) {
    declaration(schema);
    ToolValue.validate(value);
    try {
      SchemaValidation.validate(schema, instance(value));
    } catch (SchemaValidation.WorkExhausted exhausted) {
      throw new IllegalArgumentException("Tool schema validation exceeds work bound", exhausted);
    } catch (IllegalArgumentException mismatch) {
      throw new IllegalArgumentException(
          "Tool arguments do not satisfy the offered schema", mismatch);
    }
  }

  private static SchemaValue instance(ToolValue value) {
    return switch (value) {
      case IntegrationPayload.TextParameter text -> new SchemaValue.Text(text.value());
      case IntegrationPayload.NumberParameter number -> new SchemaValue.Number(number.value());
      case IntegrationPayload.BooleanParameter truth -> new SchemaValue.Truth(truth.value());
      case ToolValue.Null ignored -> new SchemaValue.Null();
      case ToolValue.Array array ->
          new SchemaValue.Array(array.values().stream().map(ToolSchemas::instance).toList());
      case ToolValue.ObjectValue object -> {
        var fields = new LinkedHashMap<String, SchemaValue>();
        object.values().forEach((name, item) -> fields.put(name, instance(item)));
        yield new SchemaValue.ObjectValue(fields);
      }
    };
  }
}
