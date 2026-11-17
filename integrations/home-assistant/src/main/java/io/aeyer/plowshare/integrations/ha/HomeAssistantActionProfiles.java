package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.protocol.SchemaCodec;
import io.aeyer.plowshare.protocol.SchemaDefinition;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Exact configured action schema. Raw vendor/configuration trees never leave this codec. */
public final class HomeAssistantActionProfiles {
  private HomeAssistantActionProfiles() {}

  public static SchemaDefinition parameters(HomeAssistantSettings.Action profile) {
    ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
    ObjectNode properties = Json.object();
    profile.parameters().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              var policy = entry.getValue();
              ObjectNode field = Json.object().put("type", policy.type());
              if (policy.minimum() != null) field.put("minimum", policy.minimum());
              if (policy.maximum() != null) field.put("maximum", policy.maximum());
              if (policy.type().equals("string"))
                field.put("maxLength", policy.maxLength() == null ? 4096 : policy.maxLength());
              if (policy.choices() != null)
                field.set("enum", Json.MAPPER.valueToTree(policy.choices()));
              properties.set(entry.getKey(), field);
            });
    schema.set("properties", properties);
    schema.set("required", Json.MAPPER.valueToTree(profile.required().stream().sorted().toList()));
    return decode(schema);
  }

  /** Service, full ordered target bundle, parameter types/bounds/enums and required fields. */
  public static String fingerprint(HomeAssistantSettings.Action profile) {
    ObjectNode schema = Json.object().put("type", "object").put("additionalProperties", false);
    ObjectNode properties = Json.object();
    properties.set("service", Json.object().put("type", "string").put("const", profile.service()));
    ObjectNode targets =
        Json.object()
            .put("type", "array")
            .put("minItems", profile.targets().size())
            .put("maxItems", profile.targets().size());
    ArrayNode items = Json.MAPPER.createArrayNode();
    profile
        .targets()
        .forEach(target -> items.add(Json.object().put("type", "string").put("const", target)));
    if (!profile.targets().isEmpty()) targets.set("prefixItems", items);
    properties.set("targets", targets);
    properties.set("parameters", Json.MAPPER.valueToTree(parameters(profile)));
    schema.set("properties", properties);
    schema.set("required", Json.MAPPER.valueToTree(List.of("service", "targets", "parameters")));
    return SchemaCodec.fingerprint(decode(schema));
  }

  private static SchemaDefinition decode(ObjectNode schema) {
    try {
      return Json.MAPPER.treeToValue(schema, SchemaDefinition.class);
    } catch (IOException invalid) {
      throw new IllegalArgumentException("Configured action schema is unsupported", invalid);
    }
  }
}
