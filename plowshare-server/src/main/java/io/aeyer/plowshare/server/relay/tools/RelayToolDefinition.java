package io.aeyer.plowshare.server.relay.tools;

import io.aeyer.plowshare.protocol.RelayPort;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import io.aeyer.plowshare.protocol.ToolSchemas;
import io.aeyer.plowshare.protocol.ToolValue;
import io.aeyer.plowshare.server.llm.dispatch.ToolSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Operator-installed schema and immutable routing authority, independent of agent grants. */
public record RelayToolDefinition(
    String project,
    String provider,
    String account,
    String name,
    String description,
    List<Parameter> parameters,
    int timeoutSeconds,
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        SchemaDefinition inputSchema) {
  public RelayToolDefinition(
      String project,
      String provider,
      String account,
      String name,
      String description,
      List<Parameter> parameters,
      int timeoutSeconds) {
    this(project, provider, account, name, description, parameters, timeoutSeconds, null);
  }

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public RelayToolDefinition {
    RelayPort.identity(project);
    RelayPort.identity(account);
    if (provider == null
        || !provider.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
        || provider.length() > 48) throw new IllegalArgumentException("Invalid tool provider");
    if (name == null || !name.matches("[a-z][a-z0-9]*(?:_[a-z0-9]+)*") || name.length() > 64)
      throw new IllegalArgumentException("Invalid Relay tool name");
    if (description == null
        || description.isBlank()
        || description.length() > 4096
        || description.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid tool description");
    parameters = parameters == null && inputSchema != null ? List.of() : List.copyOf(parameters);
    if (inputSchema != null) {
      if (!parameters.isEmpty())
        throw new IllegalArgumentException("Use scalar parameters or inputSchema, not both");
      ToolSchemas.declaration(inputSchema);
    }
    if (parameters.size() > 32
        || parameters.stream().map(Parameter::name).distinct().count() != parameters.size())
      throw new IllegalArgumentException("Invalid tool parameters");
    if (timeoutSeconds < 1 || timeoutSeconds > 300)
      throw new IllegalArgumentException("Tool timeout must be 1 through 300 seconds");
  }

  public enum Type {
    STRING,
    NUMBER,
    INTEGER,
    BOOLEAN
  }

  /** Closed scalar vocabulary; bounds are enforced by both runtime and SDK provider. */
  public record Parameter(String name, Type type, String description, boolean required) {
    public Parameter {
      if (name == null || !name.matches("[a-zA-Z_][a-zA-Z0-9_]{0,63}"))
        throw new IllegalArgumentException("Invalid parameter name");
      Objects.requireNonNull(type);
      if (description == null || description.length() > 4096 || description.indexOf('\0') >= 0)
        throw new IllegalArgumentException("Invalid parameter description");
    }
  }

  public String requests() {
    return "tool." + provider + "." + name + ".request";
  }

  public String results() {
    return "tool." + provider + "." + name + ".result";
  }

  public static String group() {
    return "tool-provider";
  }

  /** Schema serialization stays at the declaration boundary; the domain stores a typed schema. */
  public ToolSchema schema() {
    if (inputSchema != null) return new ToolSchema(name, description, inputSchema);
    Map<String, Object> properties = new LinkedHashMap<>();
    for (var parameter : parameters) {
      Map<String, Object> field = new LinkedHashMap<>();
      field.put("type", parameter.type().name().toLowerCase(java.util.Locale.ROOT));
      field.put("description", parameter.description());
      if (parameter.type() == Type.STRING) field.put("maxLength", 4096);
      if (parameter.type() == Type.NUMBER || parameter.type() == Type.INTEGER) {
        field.put("minimum", -9007199254740991L);
        field.put("maximum", 9007199254740991L);
      }
      properties.put(parameter.name(), field);
    }
    return ToolSchema.from(
        name,
        description,
        Map.of(
            "type",
            "object",
            "properties",
            properties,
            "required",
            parameters.stream().filter(Parameter::required).map(Parameter::name).toList(),
            "additionalProperties",
            false));
  }

  public void validate(Arguments arguments) {
    ToolSchemas.validate(schema().parameters(), new ToolValue.ObjectValue(arguments.values()));
  }

  private static void validateNumber(java.math.BigDecimal value) {
    if (value.abs().compareTo(java.math.BigDecimal.valueOf(9007199254740991L)) > 0
        || value.stripTrailingZeros().scale() > 18
        || java.math.BigDecimal.valueOf(value.doubleValue()).compareTo(value) != 0)
      throw new IllegalArgumentException("Tool number exceeds portable numeric range");
  }

  public record Arguments(Map<String, ToolValue> values) {
    public Arguments {
      values = Map.copyOf(values);
    }
  }
}
