package io.aeyer.plowshare.server.llm.dispatch;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated tool declaration used by model requests. Parameters are a typed schema, never a mutable
 * configuration map. This declaration describes input; tool ownership and authorization are still
 * enforced by the tool's execution boundary.
 */
public record ToolSchema(String name, String description, SchemaDefinition parameters) {
  public ToolSchema {
    if (name == null || !name.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("invalid tool name");
    if (description == null || description.length() > 32768 || description.indexOf('\0') >= 0)
      throw new IllegalArgumentException("invalid tool description");
    Objects.requireNonNull(parameters, "parameters");
  }

  /** Configuration/builder boundary: validates all schema fields before accepting a declaration. */
  public static ToolSchema from(String name, String description, Map<?, ?> parameters) {
    return new ToolSchema(name, description, SchemaCodec.decode(parameters));
  }

  /** The same typed wire declaration is used by transport serialization and prompt accounting. */
  public static List<Declared> asDeclared(List<ToolSchema> tools) {
    return tools.stream().map(tool -> new Declared("function", tool)).toList();
  }

  public record Declared(String type, ToolSchema function) {
    public Declared {
      if (!"function".equals(type))
        throw new IllegalArgumentException("invalid tool declaration type");
      Objects.requireNonNull(function, "function");
    }
  }
}
