package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.aeyer.plowshare.integrations.*;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;

/** Validated HA binding policy. Fixed targets and parameter schemas are independent of callers. */
public record HomeAssistantSettings(
    String endpoint,
    String tokenEnv,
    Map<String, Entity> entities,
    Map<String, Action> actions,
    List<String> subscriptions)
    implements AdapterConfiguration {
  public HomeAssistantSettings {
    IntegrationContracts.identity(endpoint, 4096);
    URI origin = URI.create(endpoint);
    if (!Set.of("http", "https").contains(origin.getScheme())
        || origin.getHost() == null
        || origin.getUserInfo() != null
        || origin.getQuery() != null
        || origin.getFragment() != null
        || !Set.of("", "/").contains(origin.getPath())
        || origin.getPort() == 0
        || origin.getPort() > 65535)
      throw new IllegalArgumentException(
          "HA requires an explicit HTTP(S) origin without credentials/path/query/fragment");
    if (tokenEnv == null || !tokenEnv.matches("[A-Z][A-Z0-9_]{0,127}"))
      throw new IllegalArgumentException("HA tokenEnv must name an environment variable");
    entities = Map.copyOf(entities);
    actions = Map.copyOf(actions);
    subscriptions = List.copyOf(subscriptions);
    if (entities.size() > 256
        || actions.size() > 128
        || subscriptions.size() > 256
        || subscriptions.stream().distinct().count() != subscriptions.size())
      throw new IllegalArgumentException("HA binding exceeds its bounds");
    entities.keySet().forEach(Configuration::name);
    actions.keySet().forEach(Configuration::name);
    if (entities.values().stream().map(Entity::entity).distinct().count() != entities.size())
      throw new IllegalArgumentException("duplicate entity aliases");
    for (String alias : subscriptions)
      if (!entities.containsKey(alias))
        throw new IllegalArgumentException("subscription must name a readable alias");
  }

  public record Entity(String entity, List<String> attributes) {
    public Entity {
      haName(entity);
      attributes = List.copyOf(attributes);
      if (attributes.size() > 256 || attributes.stream().distinct().count() != attributes.size())
        throw new IllegalArgumentException("invalid attribute selection");
      attributes.forEach(value -> IntegrationContracts.identity(value, 256));
    }

    public Set<String> selectedAttributes() {
      Set<String> result = new HashSet<>(attributes);
      result.add("unit_of_measurement");
      return Set.copyOf(result);
    }
  }

  public record Action(
      String service,
      List<String> targets,
      Map<String, ParameterPolicy> parameters,
      List<String> required,
      Boolean returnResponse) {
    public Action {
      haName(service);
      targets = List.copyOf(targets);
      parameters = Map.copyOf(parameters);
      required = List.copyOf(required);
      if (targets.size() > 256
          || parameters.size() > 256
          || required.size() > 256
          || targets.stream().distinct().count() != targets.size()
          || required.stream().distinct().count() != required.size())
        throw new IllegalArgumentException("action exceeds its bounds");
      targets.forEach(HomeAssistantSettings::haName);
      for (String name : parameters.keySet()) {
        if (name == null
            || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,255}")
            || Set.of("entity_id", "device_id", "area_id", "target").contains(name))
          throw new IllegalArgumentException("caller target parameters are forbidden");
      }
      if (!parameters.keySet().containsAll(required))
        throw new IllegalArgumentException("unknown required action parameter");
      if (Boolean.TRUE.equals(returnResponse))
        throw new IllegalArgumentException(
            "returnResponse requires a registered service-response DTO; this binding supports action receipts only");
    }

    public void validate(Map<String, Parameter> values) {
      if (!parameters.keySet().containsAll(values.keySet())
          || !values.keySet().containsAll(required))
        throw new IllegalArgumentException(
            "action parameters are not permitted or required fields are absent");
      values.forEach((name, value) -> parameters.get(name).validate(value));
    }
  }

  public record ParameterPolicy(
      String type,
      BigDecimal minimum,
      BigDecimal maximum,
      Integer maxLength,
      @JsonProperty("enum") List<Parameter> choices) {
    public ParameterPolicy {
      if (!Set.of("string", "number", "integer", "boolean").contains(type))
        throw new IllegalArgumentException("unsupported parameter type");
      if (minimum != null) new NumberParameter(minimum);
      if (maximum != null) new NumberParameter(maximum);
      if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0)
        throw new IllegalArgumentException("inverted parameter bounds");
      if (maxLength != null && (maxLength < 0 || maxLength > 4096))
        throw new IllegalArgumentException("parameter string bound must be 0..4096");
      if ((minimum != null || maximum != null) && !Set.of("number", "integer").contains(type)
          || maxLength != null && !type.equals("string"))
        throw new IllegalArgumentException("parameter bound does not match its type");
      if (choices != null) {
        choices = List.copyOf(choices);
        if (choices.isEmpty() || choices.size() > 256)
          throw new IllegalArgumentException("invalid parameter enum");
        for (Parameter value : choices) check(type, minimum, maximum, maxLength, value);
      }
    }

    public void validate(Parameter value) {
      check(type, minimum, maximum, maxLength, value);
      if (choices != null && choices.stream().noneMatch(choice -> equal(choice, value)))
        throw new IllegalArgumentException("parameter outside enum");
    }

    private static boolean equal(Parameter a, Parameter b) {
      return a instanceof NumberParameter x && b instanceof NumberParameter y
          ? x.value().compareTo(y.value()) == 0
          : a.equals(b);
    }

    private static void check(
        String type, BigDecimal min, BigDecimal max, Integer length, Parameter value) {
      boolean valid =
          switch (type) {
            case "string" -> value instanceof TextParameter;
            case "boolean" -> value instanceof BooleanParameter;
            case "number" -> value instanceof NumberParameter;
            case "integer" ->
                value instanceof NumberParameter n && n.value().stripTrailingZeros().scale() <= 0;
            default -> false;
          };
      if (!valid) throw new IllegalArgumentException("parameter type refused");
      if (value instanceof TextParameter t && t.value().length() > (length == null ? 4096 : length))
        throw new IllegalArgumentException("parameter exceeds string bound");
      if (value instanceof NumberParameter n
          && (min != null && n.value().compareTo(min) < 0
              || max != null && n.value().compareTo(max) > 0))
        throw new IllegalArgumentException("parameter exceeds numeric bound");
    }
  }

  private static void haName(String value) {
    if (value == null || value.length() > 256 || !value.matches("[a-z0-9_]+\\.[a-z0-9_]+"))
      throw new IllegalArgumentException("invalid HA entity/service");
  }
}
