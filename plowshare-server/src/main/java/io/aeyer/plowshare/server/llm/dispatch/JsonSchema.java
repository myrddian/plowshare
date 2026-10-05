package io.aeyer.plowshare.server.llm.dispatch;

import java.util.Map;
import java.util.Objects;

/**
 * Validated response schema for one model request. Provider structured-output support does not
 * replace strict decoding of the returned DTO; transports must report unsupported capabilities.
 */
public record JsonSchema(String name, SchemaDefinition schema) {
  public JsonSchema {
    if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("invalid response schema name");
    Objects.requireNonNull(schema, "schema");
  }

  /** Configuration boundary: refuses an empty or unsupported schema before invoking a provider. */
  public static JsonSchema from(String name, Map<?, ?> schema) {
    if (schema == null || schema.isEmpty())
      throw new IllegalArgumentException("response schema must not be empty");
    return new JsonSchema(name, SchemaCodec.decode(schema));
  }

  /** Provider representation; strict is omitted to retain the established provider contract. */
  public Declared asDeclared() {
    return new Declared("json_schema", this);
  }

  public record Declared(
      String type,
      @com.fasterxml.jackson.annotation.JsonProperty("json_schema") JsonSchema jsonSchema) {
    public Declared {
      if (!"json_schema".equals(type))
        throw new IllegalArgumentException("invalid response format type");
      Objects.requireNonNull(jsonSchema, "jsonSchema");
    }
  }

  /** A bounded name for diagnostics; schema content is never logged. */
  public String described() {
    return "json_schema('" + name + "')";
  }
}
