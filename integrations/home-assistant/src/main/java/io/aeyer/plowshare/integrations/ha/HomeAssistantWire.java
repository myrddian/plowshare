package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import io.aeyer.plowshare.integrations.IntegrationCodec;
import io.aeyer.plowshare.integrations.IntegrationResult;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResourceSource.Selection;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Registries;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.RegistryAvailability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import io.aeyer.plowshare.protocol.IntegrationPayload.ActionContext;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Reading;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    } catch (DateTimeException invalid) {
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

  /** Native resource codec: only selected primitives and opt-in metadata leave this class. */
  static List<State> resourceStates(
      List<Selection> selections, JsonNode source, Instant observedAt) {
    Map<String, JsonNode> selected = selectedStates(selections, source);
    List<State> result = new ArrayList<>();
    for (Selection selection : selections) {
      JsonNode state = selected.get(selection.entityId());
      if (state == null) {
        result.add(
            new State(
                selection.reference(),
                Availability.MISSING,
                Optional.empty(),
                Map.of(),
                Set.of(),
                Optional.empty(),
                observedAt,
                false));
        continue;
      }
      if (!state.path("state").isTextual() || !state.path("attributes").isObject())
        throw new IllegalArgumentException("invalid selected HA state");
      String value = state.get("state").textValue();
      String updated = timestamp(state, "last_updated");
      Availability availability =
          Set.of("unknown", "unavailable").contains(value)
              ? Availability.UNAVAILABLE
              : Availability.AVAILABLE;
      if (updated == null) availability = Availability.INVALID;
      Map<String, Parameter> attributes = new LinkedHashMap<>();
      Set<String> unsupported = new HashSet<>();
      for (String name : selection.attributes()) {
        JsonNode attribute = state.path("attributes").get(name);
        if (attribute == null || attribute.isNull()) continue;
        if (!attribute.isValueNode()) {
          unsupported.add(name);
          continue;
        }
        try {
          attributes.put(name, IntegrationCodec.decode(attribute, Parameter.class));
        } catch (IllegalArgumentException invalid) {
          unsupported.add(name);
        }
      }
      result.add(
          new State(
              selection.reference(),
              availability,
              Optional.of(value),
              attributes,
              unsupported,
              Optional.ofNullable(updated).map(Instant::parse),
              observedAt,
              false));
    }
    return List.copyOf(result);
  }

  static List<Description> resourceDescriptions(
      List<Selection> selections,
      JsonNode entityRegistry,
      JsonNode deviceRegistry,
      JsonNode areaRegistry,
      Registries registries) {
    Map<String, JsonNode> entities = indexRegistry(entityRegistry, "entity_id");
    Map<String, JsonNode> devices = indexRegistry(deviceRegistry, "id");
    Map<String, JsonNode> areas = indexRegistry(areaRegistry, "area_id");
    List<Description> result = new ArrayList<>();
    for (Selection selection : selections) {
      JsonNode entity = entities.get(selection.entityId());
      String deviceId = entity == null ? null : optionalText(entity, "device_id");
      JsonNode device = deviceId == null ? null : devices.get(deviceId);
      String areaId = entity == null ? null : optionalText(entity, "area_id");
      if (areaId == null && device != null) areaId = optionalText(device, "area_id");
      Optional<String> permittedDevice =
          selection.metadata().device()
                  && registries.devices() == RegistryAvailability.AVAILABLE
                  && device != null
              ? Optional.of(deviceId)
              : Optional.empty();
      Optional<String> permittedArea =
          selection.metadata().area()
                  && registries.areas() == RegistryAvailability.AVAILABLE
                  && areas.containsKey(areaId)
              ? Optional.of(areaId)
              : Optional.empty();
      String name = selection.reference().alias();
      if (selection.metadata().name()) {
        String configured = entity == null ? null : optionalText(entity, "name");
        if (configured == null && entity != null)
          configured = optionalText(entity, "original_name");
        if (configured != null) name = configured;
      }
      result.add(
          new Description(
              selection.reference(),
              selection.entityId(),
              name,
              permittedDevice,
              permittedArea,
              selection.attributes(),
              registries));
    }
    return List.copyOf(result);
  }

  private static Map<String, JsonNode> selectedStates(List<Selection> selections, JsonNode source) {
    if (!source.isArray()) throw new IllegalArgumentException("HA states array required");
    Set<String> ids = new HashSet<>();
    selections.forEach(selection -> ids.add(selection.entityId()));
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode value : source) {
      if (!value.isObject() || !value.path("entity_id").isTextual())
        throw new IllegalArgumentException("HA state entity identity required");
      String id = value.get("entity_id").textValue();
      if (ids.contains(id) && result.put(id, value) != null)
        throw new IllegalArgumentException("duplicate selected HA state");
    }
    return result;
  }

  private static Map<String, JsonNode> indexRegistry(JsonNode source, String identity) {
    if (!source.isArray()) throw new IllegalArgumentException("HA registry array required");
    Map<String, JsonNode> result = new LinkedHashMap<>();
    for (JsonNode value : source) {
      if (!value.isObject() || !value.path(identity).isTextual())
        throw new IllegalArgumentException("HA registry identity required");
      String id = value.get(identity).textValue();
      if (result.put(id, value) != null)
        throw new IllegalArgumentException("duplicate HA registry identity");
    }
    return result;
  }

  static IntegrationResult acknowledgment(JsonNode n) {
    if (!n.isObject())
      throw new IllegalArgumentException("HA action acknowledgment object required");
    if (n.has("response"))
      throw new IllegalArgumentException("HA service responses require a registered DTO");
    return new IntegrationResult(null, true, "not_observed", context(n.path("context")), null);
  }

  /** Native service acknowledgement, not an assertion that a device reached the requested state. */
  static Optional<ActionContext> actionContext(JsonNode source) {
    return Optional.ofNullable(acknowledgment(source).context());
  }
}
