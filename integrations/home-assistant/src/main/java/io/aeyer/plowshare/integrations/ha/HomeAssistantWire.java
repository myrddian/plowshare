package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.IntegrationCodec;
import io.aeyer.plowshare.protocol.ExternalResult.IntegrationResult;
import io.aeyer.plowshare.protocol.IntegrationPayload.*;
import java.time.Instant;
import java.util.*;

/** Vendor projection boundary. Unselected attributes/user identities never enter runtime DTOs. */
final class HomeAssistantWire {
  private HomeAssistantWire() {}

  static Reading reading(
      String alias,
      HomeAssistantSettings.Entity selected,
      JsonNode source,
      String epoch,
      Instant observedAt) {
    if (!source.isObject()
        || !source.path("entity_id").isTextual()
        || !selected.entity().equals(source.get("entity_id").textValue())
        || !source.path("state").isTextual())
      return missing(alias, "invalid_state", epoch, observedAt);
    String state = source.get("state").textValue();
    String availability =
        Set.of("unknown", "unavailable").contains(state) ? "unavailable" : "available";
    if (!source.path("attributes").isObject())
      throw new IllegalArgumentException("HA attributes object required");
    Map<String, Parameter> attributes = new LinkedHashMap<>();
    for (String name : selected.selectedAttributes()) {
      JsonNode value = source.path("attributes").get(name);
      // HA null attributes provide no scalar evidence. Nested values need a registered DTO family.
      if (value != null && !value.isNull())
        attributes.put(name, IntegrationCodec.decode(value, Parameter.class));
    }
    Parameter unitValue = attributes.get("unit_of_measurement");
    if (unitValue != null && !(unitValue instanceof TextParameter))
      throw new IllegalArgumentException("HA unit must be text");
    String unit = unitValue == null ? "" : ((TextParameter) unitValue).value();
    String changed = timestamp(source, "last_changed"), updated = timestamp(source, "last_updated");
    if (changed == null || updated == null) availability = "invalid_timestamp";
    return new Reading(
        alias,
        state,
        availability,
        attributes,
        unit,
        changed,
        updated,
        epoch,
        null,
        observedAt.toString(),
        context(source.path("context")));
  }

  private static Reading missing(String alias, String availability, String epoch, Instant at) {
    return new Reading(
        alias, null, availability, Map.of(), null, null, null, epoch, null, at.toString(), null);
  }

  private static String timestamp(JsonNode source, String field) {
    JsonNode value = source.get(field);
    if (value == null || !value.isTextual()) return null;
    try {
      Instant.parse(value.textValue());
      return value.textValue();
    } catch (java.time.DateTimeException invalid) {
      return null;
    }
  }

  private static ActionContext context(JsonNode n) {
    if (n.isMissingNode() || n.isNull()) return null;
    if (!n.isObject()) throw new IllegalArgumentException("HA context object required");
    String id = optionalText(n, "id"), parent = optionalText(n, "parent_id");
    return id == null && parent == null ? null : new ActionContext(id, parent);
  }

  private static String optionalText(JsonNode n, String field) {
    JsonNode value = n.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isTextual()) throw new IllegalArgumentException("HA context identity must be text");
    return value.textValue();
  }

  static IntegrationResult acknowledgment(JsonNode n) {
    if (!n.isObject())
      throw new IllegalArgumentException("HA action acknowledgment object required");
    if (n.has("response"))
      throw new IllegalArgumentException("HA service responses require a registered DTO");
    return new IntegrationResult(null, true, "not_observed", context(n.path("context")), null);
  }
}
