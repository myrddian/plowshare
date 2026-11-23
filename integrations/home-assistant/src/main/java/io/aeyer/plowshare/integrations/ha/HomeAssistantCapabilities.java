package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Availability;
import io.aeyer.plowshare.protocol.IntegrationPayload.BooleanParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.NumberParameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import io.aeyer.plowshare.protocol.IntegrationPayload.TextParameter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable adapter-owned capability DTOs. All values are bounded before entering these records.
 */
public final class HomeAssistantCapabilities {
  public static final int MAX_RESOURCES = 256;
  public static final int MAX_FIELDS = 64;
  public static final int MAX_RESULTS = 20;
  public static final int FIELD_PAGE = 16;

  private HomeAssistantCapabilities() {}

  public enum ResolutionStatus {
    NO_MATCH,
    RESOLVED,
    AMBIGUOUS
  }

  public enum ValueType {
    TEXT,
    NUMBER,
    BOOLEAN,
    UNKNOWN,
    UNSUPPORTED
  }

  public enum FieldRole {
    CURRENT,
    TARGET,
    STATUS,
    UNKNOWN
  }

  public enum FieldSupport {
    SUPPORTED,
    UNKNOWN,
    UNSUPPORTED,
    MISSING
  }

  public enum FieldStatus {
    AVAILABLE,
    UNKNOWN,
    UNAVAILABLE,
    MISSING,
    INVALID,
    UNSUPPORTED,
    NOT_VISIBLE
  }

  public enum IdentityKind {
    REGISTRY,
    LOCATOR_ONLY,
    MISSING,
    MISMATCH
  }

  public record Query(
      String text,
      Optional<String> domain,
      Optional<String> area,
      Optional<String> capability,
      Optional<FieldRole> role,
      int offset,
      int limit,
      Optional<String> revision) {
    public Query {
      Objects.requireNonNull(text);
      if (text.length() > 256 || text.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("Invalid capability search text");
      domain = optionalText(domain, 64);
      domain.ifPresent(
          value -> {
            if (!value.matches("[a-z][a-z0-9_]*"))
              throw new IllegalArgumentException("Invalid capability domain");
          });
      area = optionalText(area, 256);
      capability = optionalText(capability, 64);
      Objects.requireNonNull(role);
      Objects.requireNonNull(revision).ifPresent(HomeAssistantCapabilities::revision);
      if (offset < 0 || offset > MAX_RESOURCES * MAX_FIELDS || limit < 1 || limit > MAX_RESULTS)
        throw new IllegalArgumentException("Invalid capability page");
      if (offset > 0 && revision.isEmpty())
        throw new IllegalArgumentException("Capability page requires a revision");
    }
  }

  public record Resolution(
      ResolutionStatus status,
      String revision,
      Instant observedAt,
      List<ResourceSummary> candidates,
      Integer nextOffset) {
    public Resolution {
      Objects.requireNonNull(status);
      HomeAssistantCapabilities.revision(revision);
      Objects.requireNonNull(observedAt);
      candidates = List.copyOf(candidates);
      if (candidates.size() > MAX_RESULTS
          || (status == ResolutionStatus.NO_MATCH && !candidates.isEmpty()))
        throw new IllegalArgumentException("Invalid capability candidates");
      next(nextOffset, MAX_RESOURCES * MAX_FIELDS);
    }
  }

  public record ResourceSummary(
      String resourceId,
      String entityId,
      String name,
      List<String> aliases,
      Optional<String> device,
      Optional<String> area,
      Availability availability,
      IdentityKind identity,
      List<FieldDescription> matchedFields) {
    public ResourceSummary {
      HomeAssistantCapabilities.resourceId(resourceId);
      HomeAssistantResources.haName(entityId);
      text(name, 256);
      aliases = List.copyOf(aliases);
      if (aliases.size() > 32 || new HashSet<>(aliases).size() != aliases.size())
        throw new IllegalArgumentException("Invalid capability aliases");
      aliases.forEach(value -> text(value, 256));
      device = optionalText(device, 256);
      area = optionalText(area, 256);
      Objects.requireNonNull(availability);
      Objects.requireNonNull(identity);
      matchedFields = List.copyOf(matchedFields);
      if (matchedFields.size() > MAX_FIELDS
          || matchedFields.stream().map(FieldDescription::id).distinct().count()
              != matchedFields.size())
        throw new IllegalArgumentException("Invalid capability field catalogue");
    }
  }

  public record FieldDescription(
      String id,
      String name,
      ValueType type,
      Optional<String> unit,
      String capability,
      FieldRole role,
      FieldSupport support) {
    public FieldDescription {
      fieldId(id);
      text(name, 256);
      Objects.requireNonNull(type);
      unit = optionalText(unit, 64);
      text(capability, 64);
      Objects.requireNonNull(role);
      Objects.requireNonNull(support);
    }
  }

  public record Description(
      String revision,
      Instant observedAt,
      ResourceSummary resource,
      List<FieldDescription> fields,
      Integer nextOffset) {
    public Description {
      HomeAssistantCapabilities.revision(revision);
      Objects.requireNonNull(observedAt);
      Objects.requireNonNull(resource);
      fields = List.copyOf(fields);
      if (fields.size() > FIELD_PAGE)
        throw new IllegalArgumentException("Capability field page exceeds bound");
      next(nextOffset, MAX_FIELDS);
    }
  }

  public record FieldReadRequest(String revision, List<FieldSelection> selections) {
    public FieldReadRequest {
      HomeAssistantCapabilities.revision(revision);
      selections = List.copyOf(selections);
      if (selections.isEmpty()
          || selections.size() > MAX_RESULTS
          || selections.stream().map(FieldSelection::resourceId).distinct().count()
              != selections.size()
          || selections.stream().mapToInt(value -> value.fields().size()).sum() > MAX_RESULTS)
        throw new IllegalArgumentException("Invalid capability field selection");
    }
  }

  public record FieldSelection(String resourceId, List<String> fields) {
    public FieldSelection {
      HomeAssistantCapabilities.resourceId(resourceId);
      fields = List.copyOf(fields);
      if (fields.isEmpty()
          || fields.size() > MAX_RESULTS
          || new HashSet<>(fields).size() != fields.size())
        throw new IllegalArgumentException("Invalid capability field selection");
      fields.forEach(HomeAssistantCapabilities::fieldId);
    }
  }

  public record ReadResult(String revision, Instant observedAt, List<FieldReading> readings) {
    public ReadResult {
      HomeAssistantCapabilities.revision(revision);
      Objects.requireNonNull(observedAt);
      readings = List.copyOf(readings);
      if (readings.size() > MAX_RESULTS)
        throw new IllegalArgumentException("Capability read exceeds bound");
    }
  }

  public record FieldReading(
      String resourceId,
      String entityId,
      FieldDescription field,
      FieldStatus status,
      Optional<Parameter> value,
      Optional<Instant> sourceUpdatedAt,
      Instant observedAt,
      boolean stale) {
    public FieldReading {
      HomeAssistantCapabilities.resourceId(resourceId);
      HomeAssistantResources.haName(entityId);
      Objects.requireNonNull(field);
      Objects.requireNonNull(status);
      Objects.requireNonNull(value);
      Objects.requireNonNull(sourceUpdatedAt);
      Objects.requireNonNull(observedAt);
      if ((status == FieldStatus.AVAILABLE) != value.isPresent())
        throw new IllegalArgumentException("Inconsistent capability field value");
      if (value.isPresent()) {
        boolean matches =
            switch (field.type()) {
              case NUMBER -> value.get() instanceof NumberParameter;
              case TEXT -> value.get() instanceof TextParameter;
              case BOOLEAN -> value.get() instanceof BooleanParameter;
              case UNKNOWN, UNSUPPORTED -> false;
            };
        if (!matches || field.support() != FieldSupport.SUPPORTED)
          throw new IllegalArgumentException("Capability field value disagrees with its shape");
      }
    }
  }

  static void revision(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid capability revision");
  }

  static void resourceId(String value) {
    if (value == null || !value.matches("r_[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid capability resource reference");
  }

  static void fieldId(String value) {
    if (value == null || !(value.equals("state") || value.startsWith("attribute.")))
      throw new IllegalArgumentException("Invalid capability field reference");
    if (!value.equals("state")) text(value.substring("attribute.".length()), 256);
  }

  static void text(String value, int bound) {
    IntegrationContracts.identity(value, bound);
  }

  private static Optional<String> optionalText(Optional<String> value, int bound) {
    Objects.requireNonNull(value).ifPresent(text -> text(text, bound));
    return value;
  }

  private static void next(Integer value, int maximum) {
    if (value != null && (value < 1 || value > maximum))
      throw new IllegalArgumentException("Invalid capability next page");
  }
}
