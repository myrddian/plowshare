package io.aeyer.plowshare.integrations.ha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldRole;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldSupport;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.IdentityKind;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResourceSummary;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ValueType;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilityIdentityStore.Anchor;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilitySource.Observation;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilitySource.Snapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantProviderConfiguration.Discovery;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import io.aeyer.plowshare.protocol.IntegrationPayload.BooleanParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Native JSON boundary for the capability view. Unselected values are never decoded or retained.
 */
final class HomeAssistantCapabilityWire {
  private static final int MAX_NATIVE_ENTITIES = 4096;
  private static final Set<String> NUMERIC_CLASSES =
      Set.of(
          "temperature",
          "humidity",
          "pressure",
          "atmospheric_pressure",
          "battery",
          "power",
          "energy",
          "illuminance",
          "carbon_dioxide",
          "carbon_monoxide",
          "moisture",
          "weight",
          "wind_speed",
          "voltage",
          "current",
          "frequency",
          "water",
          "volume",
          "distance",
          "speed",
          "signal_strength",
          "pm1",
          "pm10",
          "pm25");

  private HomeAssistantCapabilityWire() {}

  static Set<String> exposed(JsonNode raw) {
    Json.fields(raw, "version", "assistant", "entities");
    if (!raw.path("version").isIntegralNumber()
        || !raw.path("version").canConvertToInt()
        || raw.path("version").intValue() != 1
        || !raw.path("assistant").isTextual()
        || !raw.path("assistant").textValue().equals("conversation")
        || !raw.path("entities").isArray()
        || raw.path("entities").size() > MAX_NATIVE_ENTITIES)
      throw new IllegalArgumentException("Unsupported effective exposure response");
    Set<String> result = new LinkedHashSet<>();
    for (JsonNode value : raw.path("entities")) {
      if (!value.isTextual())
        throw new IllegalArgumentException("Invalid effective exposure entity");
      HomeAssistantResources.haName(value.textValue());
      if (!result.add(value.textValue()))
        throw new IllegalArgumentException("Duplicate effective exposure entity");
    }
    return Set.copyOf(result);
  }

  /**
   * All input frames belong to one checked native epoch. Registry/state collections are batched; no
   * entity-specific metadata request is made. A single selected get_entries projection supplies
   * aliases omitted by HA2026.9.4's ordinary bulk registry listing.
   */
  static Snapshot snapshot(
      String epoch,
      Discovery policy,
      HomeAssistantSettings settings,
      HomeAssistantCapabilityIdentityStore store,
      JsonNode statesRaw,
      JsonNode entitiesRaw,
      JsonNode devicesRaw,
      JsonNode areasRaw,
      JsonNode configuration,
      Set<String> exposed,
      Instant observedAt,
      OperationBudget budget)
      throws IOException {
    Map<String, JsonNode> states = index(statesRaw, "entity_id");
    Map<String, JsonNode> entities = index(entitiesRaw, "entity_id");
    Map<String, JsonNode> devices = index(devicesRaw, "id");
    Map<String, JsonNode> areas = index(areasRaw, "area_id");
    Map<String, JsonNode> registryKeys = new LinkedHashMap<>();
    for (var entry : entities.entrySet()) {
      String key = registryKey(entry.getValue(), entry.getKey());
      if (key != null && registryKeys.put(key, entry.getValue()) != null)
        throw new IllegalArgumentException("Duplicate native registry identity");
    }
    var retained = store.state();
    Map<String, Anchor> anchors = new LinkedHashMap<>();
    retained.anchors().forEach(value -> anchors.put(value.selector(), value));
    Set<String> deniedKeys = new LinkedHashSet<>();
    // Literal exclusions also retain verified registry anchors, so a rename cannot escape them.
    for (String denied : policy.excludedEntities()) {
      String prefix = "excluded:" + denied + ":";
      anchors.values().stream()
          .filter(value -> value.selector().startsWith(prefix))
          .forEach(value -> deniedKeys.add(value.registryKey()));
      JsonNode row = entities.get(denied);
      String key = registryKey(row, denied);
      if (key != null) {
        deniedKeys.add(key);
        String selector = prefix + key;
        if (!anchors.containsKey(selector))
          anchors.put(
              selector,
              new Anchor(
                  selector,
                  key,
                  IdentityKind.REGISTRY,
                  denied,
                  id(retained.namespace(), key),
                  denied,
                  List.of(),
                  Optional.empty(),
                  Optional.empty(),
                  List.of()));
      }
    }
    // In follow mode explicit custom field permissions still belong to a verified configured
    // resource identity. A rename preserves them; a replacement at the old locator does not.
    if (policy.mode().equals("assist_exposed")) {
      for (var selected : settings.entities().entrySet()) {
        String selector = "configured:" + selected.getKey();
        String locator = selected.getValue().entity();
        String key = registryKey(entities.get(locator), locator);
        IdentityKind kind = key == null ? IdentityKind.LOCATOR_ONLY : IdentityKind.REGISTRY;
        if (key == null) key = Json.hash("locator:" + locator);
        if (!anchors.containsKey(selector))
          anchors.put(
              selector,
              new Anchor(
                  selector,
                  key,
                  kind,
                  locator,
                  id(retained.namespace(), key),
                  selected.getKey(),
                  List.of(selected.getKey()),
                  Optional.empty(),
                  Optional.empty(),
                  List.of()));
      }
    }
    Map<String, String> selectors = new LinkedHashMap<>();
    if (policy.mode().equals("fixed")) {
      settings.entities().entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(value -> selectors.put("fixed:" + value.getKey(), value.getValue().entity()));
    } else {
      for (String entity : exposed.stream().sorted().toList()) {
        String key = registryKey(entities.get(entity), entity);
        selectors.put(key == null ? "dynamic_locator:" + entity : "dynamic:" + key, entity);
      }
    }
    List<Observation> observations = new ArrayList<>();
    for (var selected : selectors.entrySet()) {
      budget.remaining();
      String selector = selected.getKey();
      String locator = selected.getValue();
      Anchor old = anchors.get(selector);
      JsonNode registry = entities.get(locator);
      String key = registryKey(registry, locator);
      if (old != null && old.identity() == IdentityKind.REGISTRY && policy.mode().equals("fixed")) {
        JsonNode sameIdentity = registryKeys.get(old.registryKey());
        if (sameIdentity == null) {
          if (!policy.excludedEntities().contains(locator)
              && !deniedKeys.contains(old.registryKey()))
            observations.add(
                blocked(
                    old, key == null ? IdentityKind.MISSING : IdentityKind.MISMATCH, observedAt));
          continue;
        }
        registry = sameIdentity;
        locator = requiredText(registry, "entity_id", 256);
        key = old.registryKey();
      } else if (old != null && old.identity() == IdentityKind.LOCATOR_ONLY && key != null) {
        if (!policy.excludedEntities().contains(locator))
          observations.add(blocked(old, IdentityKind.MISMATCH, observedAt));
        continue;
      }
      if (policy.excludedEntities().contains(locator) || key != null && deniedKeys.contains(key))
        continue;
      IdentityKind identity = key == null ? IdentityKind.LOCATOR_ONLY : IdentityKind.REGISTRY;
      if (key == null) key = Json.hash("locator:" + locator);
      String resourceId =
          id(
              retained.namespace(),
              key + (identity == IdentityKind.LOCATOR_ONLY ? "\0" + epoch : ""));
      JsonNode state = states.get(locator);
      JsonNode attributes = state == null ? null : state.get("attributes");
      String alias =
          selector.startsWith("fixed:") ? selector.substring("fixed:".length()) : locator;
      String name = firstDisplayText(attributes, "friendly_name");
      if (name == null) name = firstDisplayText(registry, "name", "original_name");
      if (name == null) name = alias;
      List<String> aliases = aliases(alias, registry);
      JsonNode deviceRow = devices.get(firstText(registry, "device_id"));
      String deviceName = firstDisplayText(deviceRow, "name_by_user", "name");
      String areaId = firstText(registry, "area_id");
      if (areaId == null) areaId = firstText(deviceRow, "area_id");
      String areaName = firstDisplayText(areas.get(areaId), "name");
      Availability availability = availability(state);
      Set<String> attributesAllowed = new LinkedHashSet<>();
      if (selector.startsWith("fixed:"))
        attributesAllowed.addAll(settings.entities().get(alias).selectedAttributes());
      else
        for (var configured : settings.entities().entrySet()) {
          Anchor configuredIdentity = anchors.get("configured:" + configured.getKey());
          boolean same =
              configuredIdentity == null
                  ? configured.getValue().entity().equals(locator)
                  : configuredIdentity.registryKey().equals(key);
          if (same) {
            attributesAllowed.addAll(configured.getValue().selectedAttributes());
            Set<String> names = new LinkedHashSet<>(aliases);
            names.add(configured.getKey());
            aliases = names.stream().sorted().toList();
          }
        }
      String domain = locator.substring(0, locator.indexOf('.'));
      if (policy.profile().equals("standard_measurements"))
        attributesAllowed.addAll(HomeAssistantCapabilityProfiles.attributes(domain));
      List<FieldReading> readings =
          fields(
              resourceId,
              locator,
              state,
              availability,
              attributesAllowed,
              old == null ? List.of() : old.fields(),
              policy.excludedFields(),
              configuration,
              observedAt);
      var resource =
          new ResourceSummary(
              resourceId,
              locator,
              name,
              aliases,
              Optional.ofNullable(deviceName),
              Optional.ofNullable(areaName),
              availability,
              identity,
              readings.stream().map(FieldReading::field).toList());
      if (resource.matchedFields().isEmpty()) continue;
      observations.add(new Observation(resource, readings));
      anchors.put(
          selector,
          new Anchor(
              selector,
              key,
              identity,
              locator,
              resourceId,
              name,
              aliases,
              resource.device(),
              resource.area(),
              resource.matchedFields()));
      if (observations.size() > HomeAssistantCapabilities.MAX_RESOURCES)
        throw new IOException("Capability selection exceeds its resource bound");
    }
    observations.sort(Comparator.comparing(value -> value.resource().resourceId()));
    budget.remaining();
    store.install(
        anchors.values().stream().sorted(Comparator.comparing(Anchor::selector)).toList());
    budget.remaining();
    return new Snapshot(epoch, policy.fingerprint(settings), observedAt, observations);
  }

  private static Observation blocked(Anchor anchor, IdentityKind identity, Instant observedAt) {
    var resource =
        new ResourceSummary(
            anchor.resourceId(),
            anchor.entityId(),
            anchor.name(),
            anchor.aliases(),
            anchor.device(),
            anchor.area(),
            Availability.MISSING,
            identity,
            anchor.fields());
    List<FieldReading> fields =
        anchor.fields().stream()
            .map(
                field ->
                    new FieldReading(
                        anchor.resourceId(),
                        anchor.entityId(),
                        field,
                        FieldStatus.MISSING,
                        Optional.empty(),
                        Optional.empty(),
                        observedAt,
                        false))
            .toList();
    return new Observation(resource, fields);
  }

  private static List<FieldReading> fields(
      String resourceId,
      String entity,
      JsonNode state,
      Availability availability,
      Set<String> selected,
      List<FieldDescription> previous,
      Set<String> excluded,
      JsonNode configuration,
      Instant observedAt) {
    String domain = entity.substring(0, entity.indexOf('.'));
    Map<String, FieldDescription> history = new LinkedHashMap<>();
    previous.forEach(value -> history.put(value.id(), value));
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("state", "state");
    selected.stream().sorted().forEach(name -> fields.put("attribute." + name, name));
    fields.keySet().removeAll(excluded);
    if (fields.size() > HomeAssistantCapabilities.MAX_FIELDS)
      throw new IllegalArgumentException("Capability field bound exceeded");
    List<FieldReading> result = new ArrayList<>();
    JsonNode attributes = state == null ? null : state.get("attributes");
    String deviceClass = firstText(attributes, "device_class");
    Optional<Instant> updated = timestamp(state);
    String rawState =
        state != null && state.path("state").isTextual() ? state.path("state").textValue() : null;
    for (var selectedField : fields.entrySet()) {
      String fieldId = selectedField.getKey();
      if (excluded.contains(fieldId)) continue;
      String name = selectedField.getValue();
      FieldDescription old = history.get(fieldId);
      Definition definition = definition(domain, name, deviceClass);
      JsonNode value =
          fieldId.equals("state")
              ? state == null ? null : state.get("state")
              : attributes == null ? null : attributes.get(name);
      String unit = unit(domain, name, attributes, configuration);
      if (unit == null
          && old != null
          && (availability != Availability.AVAILABLE || value == null || value.isNull()))
        unit = old.unit().orElse(null);
      ValueType type = definition.type();
      if (type == ValueType.UNKNOWN) type = valueType(value);
      if (type == ValueType.UNKNOWN && old != null) type = old.type();
      FieldSupport support =
          type == ValueType.UNKNOWN ? FieldSupport.UNKNOWN : FieldSupport.SUPPORTED;
      FieldStatus status;
      Optional<Parameter> decoded = Optional.empty();
      if (availability != Availability.AVAILABLE) {
        status =
            availability == Availability.MISSING
                ? FieldStatus.MISSING
                : availability == Availability.INVALID
                    ? FieldStatus.INVALID
                    : "unknown".equals(rawState) ? FieldStatus.UNKNOWN : FieldStatus.UNAVAILABLE;
        if (old != null) {
          type = old.type();
          support = old.support();
        }
      } else if (value == null || value.isNull()) {
        status = FieldStatus.MISSING;
        support = old == null ? FieldSupport.MISSING : old.support();
      } else {
        try {
          Parameter parsed = decode(value, type, fieldId.equals("state"));
          if (parsed == null) {
            status = FieldStatus.UNSUPPORTED;
            support = FieldSupport.UNSUPPORTED;
          } else {
            status = FieldStatus.AVAILABLE;
            decoded = Optional.of(parsed);
          }
        } catch (IllegalArgumentException invalid) {
          status = FieldStatus.INVALID;
          support = FieldSupport.UNSUPPORTED;
        }
      }
      if (updated.isEmpty() && state != null) {
        status = FieldStatus.INVALID;
        decoded = Optional.empty();
      }
      String capability = definition.capability();
      FieldRole role = definition.role();
      if (availability != Availability.AVAILABLE && old != null) {
        capability = old.capability();
        role = old.role();
      }
      var descriptor =
          new FieldDescription(
              fieldId,
              name.replace('_', ' '),
              type,
              Optional.ofNullable(unit),
              capability,
              role,
              support);
      result.add(
          new FieldReading(
              resourceId, entity, descriptor, status, decoded, updated, observedAt, false));
    }
    return List.copyOf(result);
  }

  /** Only admitted locators enter the one bounded extended-registry projection. */
  static Set<String> selectedLocators(
      Discovery policy,
      HomeAssistantSettings settings,
      HomeAssistantCapabilityIdentityStore store,
      JsonNode entitiesRaw,
      Set<String> exposed)
      throws IOException {
    Map<String, JsonNode> entities = index(entitiesRaw, "entity_id");
    var retained = store.state();
    Set<String> excludedKeys = new LinkedHashSet<>();
    for (String entity : policy.excludedEntities()) {
      String prefix = "excluded:" + entity + ":";
      retained.anchors().stream()
          .filter(value -> value.selector().startsWith(prefix))
          .forEach(value -> excludedKeys.add(value.registryKey()));
      String key = registryKey(entities.get(entity), entity);
      if (key != null) excludedKeys.add(key);
    }
    Set<String> selected = new LinkedHashSet<>();
    if (policy.mode().equals("assist_exposed")) selected.addAll(exposed);
    else
      for (var entry : settings.entities().entrySet()) {
        Anchor old =
            retained.anchors().stream()
                .filter(value -> value.selector().equals("fixed:" + entry.getKey()))
                .findFirst()
                .orElse(null);
        String locator = entry.getValue().entity();
        if (old != null && old.identity() == IdentityKind.REGISTRY) {
          locator = null;
          for (var candidate : entities.entrySet())
            if (old.registryKey().equals(registryKey(candidate.getValue(), candidate.getKey())))
              locator = candidate.getKey();
        } else if (old != null
            && old.identity() == IdentityKind.LOCATOR_ONLY
            && registryKey(entities.get(locator), locator) != null) locator = null;
        if (locator != null) selected.add(locator);
      }
    selected.removeIf(
        entity ->
            policy.excludedEntities().contains(entity)
                || excludedKeys.contains(registryKey(entities.get(entity), entity)));
    if (selected.size() > HomeAssistantCapabilities.MAX_RESOURCES)
      throw new IOException("Capability selection exceeds its resource bound");
    return Set.copyOf(selected);
  }

  static JsonNode withAliases(JsonNode registry, JsonNode extended, Set<String> selected) {
    if (!extended.isObject() || extended.size() != selected.size())
      throw new IllegalArgumentException("Selected registry projection invalid");
    var keys = new LinkedHashSet<String>();
    extended.fieldNames().forEachRemaining(keys::add);
    if (!keys.equals(selected))
      throw new IllegalArgumentException("Selected registry projection mismatch");
    var result = registry.deepCopy();
    for (JsonNode row : result) {
      String entity = requiredText(row, "entity_id", 256);
      if (!selected.contains(entity)) continue;
      JsonNode detail = extended.get(entity);
      if (detail == null
          || detail.isNull()
          || !entity.equals(firstText(detail, "entity_id"))
          || !Objects.equals(registryKey(row, entity), registryKey(detail, entity)))
        throw new IllegalArgumentException("Selected registry identity changed");
      if (detail.has("aliases")) ((ObjectNode) row).set("aliases", detail.get("aliases"));
    }
    return result;
  }

  private record Definition(ValueType type, String capability, FieldRole role) {}

  private static Definition definition(String domain, String field, String deviceClass) {
    if (field.equals("state")) {
      if (domain.equals("binary_sensor"))
        return new Definition(
            ValueType.BOOLEAN,
            deviceClass == null ? "binary_state" : deviceClass,
            FieldRole.CURRENT);
      if (domain.equals("sensor"))
        return new Definition(
            deviceClass != null && NUMERIC_CLASSES.contains(deviceClass)
                ? ValueType.NUMBER
                : ValueType.TEXT,
            deviceClass == null ? "state" : deviceClass,
            FieldRole.CURRENT);
      return new Definition(
          ValueType.TEXT, domain.equals("climate") ? "hvac_mode" : "state", FieldRole.STATUS);
    }
    if (Set.of("climate", "water_heater", "humidifier", "weather").contains(domain)) {
      if (field.equals("current_temperature"))
        return new Definition(ValueType.NUMBER, "temperature", FieldRole.CURRENT);
      if (field.equals("temperature"))
        return new Definition(
            ValueType.NUMBER,
            "temperature",
            domain.equals("weather") ? FieldRole.CURRENT : FieldRole.TARGET);
      if (field.equals("current_humidity"))
        return new Definition(ValueType.NUMBER, "humidity", FieldRole.CURRENT);
      if (field.equals("humidity"))
        return new Definition(
            ValueType.NUMBER,
            "humidity",
            domain.equals("weather") ? FieldRole.CURRENT : FieldRole.TARGET);
      if (domain.equals("weather") && Set.of("pressure", "wind_speed").contains(field))
        return new Definition(ValueType.NUMBER, field, FieldRole.CURRENT);
    }
    return new Definition(ValueType.UNKNOWN, "unknown", FieldRole.UNKNOWN);
  }

  private static String unit(
      String domain, String field, JsonNode attributes, JsonNode configuration) {
    if (field.equals("state")) return firstText(attributes, "unit_of_measurement");
    if (Set.of("climate", "water_heater", "weather").contains(domain)
        && Set.of("temperature", "current_temperature").contains(field)) {
      String unit = firstText(attributes, "temperature_unit");
      if (unit == null && configuration != null)
        unit = firstText(configuration.path("unit_system"), "temperature");
      return unit;
    }
    if (Set.of("current_humidity", "humidity").contains(field)
        && Set.of("climate", "humidifier", "weather").contains(domain)) return "%";
    if (domain.equals("weather") && Set.of("pressure", "wind_speed").contains(field))
      return firstText(attributes, field + "_unit");
    return null;
  }

  private static ValueType valueType(JsonNode value) {
    if (value == null || value.isNull()) return ValueType.UNKNOWN;
    if (value.isNumber()) return ValueType.NUMBER;
    if (value.isBoolean()) return ValueType.BOOLEAN;
    if (value.isTextual()) return ValueType.TEXT;
    return ValueType.UNSUPPORTED;
  }

  private static Parameter decode(JsonNode value, ValueType type, boolean state) {
    return switch (type) {
      case NUMBER -> {
        if (value.isNumber()) yield new NumberParameter(value.decimalValue());
        if (state && value.isTextual() && value.textValue().length() <= 256)
          yield new NumberParameter(new BigDecimal(value.textValue()));
        throw new IllegalArgumentException("Numeric capability value required");
      }
      case TEXT -> {
        if (!value.isTextual())
          throw new IllegalArgumentException("Text capability value required");
        yield new TextParameter(value.textValue());
      }
      case BOOLEAN -> {
        if (value.isBoolean()) yield new BooleanParameter(value.booleanValue());
        if (state && value.isTextual() && Set.of("on", "off").contains(value.textValue()))
          yield new BooleanParameter(value.textValue().equals("on"));
        throw new IllegalArgumentException("Boolean capability value required");
      }
      case UNKNOWN, UNSUPPORTED -> null;
    };
  }

  private static Availability availability(JsonNode state) {
    if (state == null) return Availability.MISSING;
    if (!state.path("state").isTextual()
        || !state.path("attributes").isObject()
        || timestamp(state).isEmpty()) return Availability.INVALID;
    return Set.of("unknown", "unavailable").contains(state.path("state").textValue())
        ? Availability.UNAVAILABLE
        : Availability.AVAILABLE;
  }

  private static Optional<Instant> timestamp(JsonNode state) {
    String text =
        state != null && state.path("last_updated").isTextual()
            ? state.path("last_updated").textValue()
            : null;
    try {
      return text == null ? Optional.empty() : Optional.of(Instant.parse(text));
    } catch (DateTimeParseException invalid) {
      return Optional.empty();
    }
  }

  private static String registryKey(JsonNode entry, String entityId) {
    String id = firstText(entry, "id");
    if (id != null) return Json.hash("registry:" + id);
    String unique = firstText(entry, "unique_id");
    String platform = firstText(entry, "platform");
    return unique == null || platform == null
        ? null
        : Json.hash(
            "unique:"
                + entityId.substring(0, entityId.indexOf('.'))
                + "\0"
                + platform
                + "\0"
                + unique);
  }

  private static String id(String namespace, String key) {
    return "r_" + Json.hash(namespace + "\0" + key);
  }

  private static List<String> aliases(String configured, JsonNode registry) {
    Set<String> aliases = new LinkedHashSet<>();
    aliases.add(configured);
    JsonNode values = registry == null ? null : registry.get("aliases");
    if (values != null && !values.isNull()) {
      if (!values.isArray() || values.size() > 32)
        throw new IllegalArgumentException("Invalid capability aliases");
      for (JsonNode value : values) {
        if (value.isNull()) continue;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid capability alias");
        String alias = displayText(value.textValue());
        if (alias != null) aliases.add(alias);
      }
    }
    if (aliases.size() > 32) throw new IllegalArgumentException("Capability alias bound exceeded");
    return aliases.stream().sorted().toList();
  }

  private static Map<String, JsonNode> index(JsonNode values, String key) {
    if (!values.isArray() || values.size() > MAX_NATIVE_ENTITIES)
      throw new IllegalArgumentException("Native capability collection exceeds bound");
    Map<String, JsonNode> indexed = new LinkedHashMap<>();
    for (JsonNode value : values) {
      String id = requiredText(value, key, 256);
      if (indexed.put(id, value) != null)
        throw new IllegalArgumentException("Duplicate native capability metadata");
    }
    return indexed;
  }

  private static String requiredText(JsonNode value, String key, int bound) {
    String text = firstText(value, key);
    if (text == null) throw new IllegalArgumentException("Native capability identity missing");
    HomeAssistantCapabilities.text(text, bound);
    return text;
  }

  private static String firstText(JsonNode value, String... keys) {
    if (value == null || value.isNull()) return null;
    for (String key : keys) {
      JsonNode text = value.get(key);
      if (text == null || text.isNull()) continue;
      if (!text.isTextual()) throw new IllegalArgumentException("Invalid native capability text");
      HomeAssistantCapabilities.text(text.textValue(), 256);
      return text.textValue();
    }
    return null;
  }

  /**
   * Human labels are lookup metadata, never identities. Normalize only their boundary whitespace;
   * validate raw bounds/controls first so stripping cannot hide malformed or oversized input.
   * Identifiers, registry keys, units and semantic capability identifiers use strict firstText.
   */
  private static String displayText(String value) {
    if (value.length() > 256
        || value
            .chars()
            .anyMatch(
                character ->
                    Character.isISOControl(character)
                        || character == 0x2028
                        || character == 0x2029))
      throw new IllegalArgumentException("Invalid native capability display text");
    String normalized = value.strip();
    if (normalized.isEmpty()) return null;
    HomeAssistantCapabilities.text(normalized, 256);
    return normalized;
  }

  private static String firstDisplayText(JsonNode value, String... keys) {
    if (value == null || value.isNull()) return null;
    for (String key : keys) {
      JsonNode text = value.get(key);
      if (text == null || text.isNull()) continue;
      if (!text.isTextual())
        throw new IllegalArgumentException("Invalid native capability display text");
      String normalized = displayText(text.textValue());
      if (normalized != null) return normalized;
    }
    return null;
  }
}
