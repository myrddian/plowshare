package io.aeyer.plowshare.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The registered plowshare-integration/1 data contract. Structural checks finish at
 * deserialization; the owning adapter must still allowlist aliases, actions and parameter
 * definitions for its binding before any external I/O. Parameters are primitive service arguments,
 * not a recursive JSON tree.
 */
public final class IntegrationPayload {
  private IntegrationPayload() {}

  public record Request(String schema, String binding, String operation, Arguments arguments) {
    public Request {
      if (!"plowshare-integration/1".equals(schema))
        throw new IllegalArgumentException("unregistered external data schema");
      binding = ContractValues.identity(binding, "integration binding", 256);
      Objects.requireNonNull(arguments, "arguments");
      if ("states.read".equals(operation)) {
        if (arguments.entities() == null
            || arguments.entities().isEmpty()
            || arguments.action() != null
            || arguments.parameters() != null)
          throw new IllegalArgumentException("state reads require only selected entities");
      } else if ("actions.execute".equals(operation)) {
        if (arguments.action() == null
            || arguments.parameters() == null
            || arguments.entities() != null)
          throw new IllegalArgumentException(
              "action execution requires only action and parameters");
      } else throw new IllegalArgumentException("unsupported integration operation");
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Arguments(List<String> entities, String action, Map<String, Parameter> parameters) {
    public Arguments {
      if (entities != null) {
        entities =
            ContractValues.list(entities, "entity aliases", 256).stream()
                .map(v -> ContractValues.identity(v, "entity alias", 256))
                .toList();
        if (entities.stream().distinct().count() != entities.size())
          throw new IllegalArgumentException("duplicate entity aliases");
      }
      action = ContractValues.optionalIdentity(action, "action alias", 256);
      if (parameters != null) {
        if (parameters.size() > 256)
          throw new IllegalArgumentException("too many action parameters");
        parameters = Map.copyOf(parameters);
        for (String name : parameters.keySet()) {
          ContractValues.identity(name, "action parameter name", 256);
          if (!name.matches("[A-Za-z_][A-Za-z0-9_]*"))
            throw new IllegalArgumentException("invalid action parameter name");
        }
      }
    }
  }

  /** A primitive action argument, independently bounded before binding-specific validation. */
  public sealed interface Parameter permits TextParameter, NumberParameter, BooleanParameter {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static Parameter fromWire(Object value) {
      if (value instanceof String text) return new TextParameter(text);
      if (value instanceof Boolean flag) return new BooleanParameter(flag);
      if (value instanceof Number number) {
        try {
          return new NumberParameter(new BigDecimal(number.toString()));
        } catch (NumberFormatException invalid) {
          throw new IllegalArgumentException("invalid action number", invalid);
        }
      }
      throw new IllegalArgumentException("action arguments must be declared primitive parameters");
    }
  }

  public record TextParameter(@JsonValue String value) implements Parameter {
    public TextParameter {
      value = ContractValues.text(value, "action text", 4096, false);
      Objects.requireNonNull(value);
    }
  }

  public record NumberParameter(@JsonValue BigDecimal value) implements Parameter {
    public NumberParameter {
      Objects.requireNonNull(value);
      if (value.precision() > 36 || Math.abs((long) value.scale()) > 18)
        throw new IllegalArgumentException("action number exceeds supported precision or scale");
    }
  }

  public record BooleanParameter(@JsonValue boolean value) implements Parameter {}

  /** Selected entity evidence; attributes are the same declared primitive values as parameters. */
  public record Reading(
      String alias,
      String state,
      String availability,
      Map<String, Parameter> attributes,
      String unit,
      String last_changed,
      String last_updated,
      String epoch,
      Boolean stale,
      String observed_at,
      ActionContext context) {
    public Reading {
      alias = ContractValues.identity(alias, "entity alias", 256);
      state = ContractValues.text(state, "entity state", 4096, false);
      availability = ContractValues.identity(availability, "availability", 256);
      if (!Set.of(
              "available",
              "unavailable",
              "missing",
              "invalid_state",
              "invalid_timestamp",
              "disconnected",
              "stale")
          .contains(availability))
        throw new IllegalArgumentException("invalid entity availability");
      attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
      if (attributes.size() > 256) throw new IllegalArgumentException("too many entity attributes");
      for (String name : attributes.keySet()) ContractValues.identity(name, "attribute name", 256);
      unit = ContractValues.text(unit, "unit", 256, false);
      last_changed = timestamp(last_changed);
      last_updated = timestamp(last_updated);
      epoch = ContractValues.optionalIdentity(epoch, "epoch", 1024);
      observed_at = timestamp(observed_at);
    }

    private static String timestamp(String value) {
      if (value == null || value.isEmpty()) return value;
      java.time.Instant.parse(value);
      return value;
    }
  }

  public record ActionContext(String id, String parent_id) {
    public ActionContext {
      id = ContractValues.optionalIdentity(id, "action context id", 1024);
      parent_id = ContractValues.optionalIdentity(parent_id, "action parent context", 1024);
      if (id == null && parent_id == null)
        throw new IllegalArgumentException("empty action context");
    }
  }
}
