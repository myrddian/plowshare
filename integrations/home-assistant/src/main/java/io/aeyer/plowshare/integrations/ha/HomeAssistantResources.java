package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter-owned resource contracts, independent of HA JSON and of shared IntegrationPayload
 * variants. Bounds here apply to external metadata and protocol collections, not model prose.
 */
public final class HomeAssistantResources {
  public static final int MAX_RESOURCES = 256;
  public static final int MAX_ACTIONS = 128;
  public static final int MAX_PAGE = 100;

  /** Existing bindings permit 256 explicit attributes plus an implicit unit attribute. */
  public static final int MAX_ATTRIBUTES = 257;

  private HomeAssistantResources() {}

  /**
   * Opaque credential generation, never a token, token hash or HA user ID. Rotation/rebinding gets
   * a new identity; callers may not share snapshots merely because the configured origin matches.
   */
  public record Binding(String binding, String credentialContext) {
    public Binding {
      IntegrationContracts.identity(binding, 256);
      IntegrationContracts.identity(credentialContext, 256);
    }
  }

  /**
   * A stable configured alias in this binding. Display names and current entity IDs are metadata,
   * never identifiers. Initial explicit aliases do not automatically admit future/renamed targets.
   */
  public record Reference(Binding binding, String alias) {
    public Reference {
      Objects.requireNonNull(binding);
      IntegrationContracts.identity(alias, 256);
    }
  }

  /**
   * Metadata and state revisions are independent; reconnect changes the epoch even at version 0.
   */
  public record Revision(
      Binding binding, String policy, String epoch, long metadataVersion, long stateVersion) {
    public Revision {
      Objects.requireNonNull(binding);
      IntegrationContracts.identity(policy, 256);
      IntegrationContracts.identity(epoch, 256);
      if (metadataVersion < 0 || stateVersion < 0)
        throw new IllegalArgumentException("negative HA snapshot revision");
    }
  }

  public enum RegistryAvailability {
    AVAILABLE,
    UNSUPPORTED,
    DENIED
  }

  public record Registries(
      RegistryAvailability entities,
      RegistryAvailability devices,
      RegistryAvailability areas,
      RegistryAvailability services) {
    public Registries {
      Objects.requireNonNull(entities);
      Objects.requireNonNull(devices);
      Objects.requireNonNull(areas);
      Objects.requireNonNull(services);
    }
  }

  /** Only selected relationships enter this DTO; absent registries do not imply empty rooms. */
  public record Description(
      Reference reference,
      String entityId,
      String name,
      Optional<String> device,
      Optional<String> area,
      Set<String> readableAttributes,
      Registries registries) {
    public Description {
      Objects.requireNonNull(reference);
      haName(entityId);
      IntegrationContracts.identity(name, 256);
      device = optionalIdentity(device);
      area = optionalIdentity(area);
      readableAttributes = names(readableAttributes, MAX_ATTRIBUTES);
      Objects.requireNonNull(registries);
      if (device.isPresent() && registries.devices() != RegistryAvailability.AVAILABLE
          || area.isPresent() && registries.areas() != RegistryAvailability.AVAILABLE)
        throw new IllegalArgumentException("HA relationship requires available registry metadata");
    }

    public String domain() {
      return entityId.substring(0, entityId.indexOf('.'));
    }
  }

  /** Cursor contains only a position within an already authorized revision; no source counts. */
  public record Cursor(Revision revision, int offset) {
    public Cursor {
      Objects.requireNonNull(revision);
      if (offset < 0 || offset > MAX_RESOURCES)
        throw new IllegalArgumentException("invalid HA page position");
    }
  }

  public record Search(
      String text,
      Optional<String> domain,
      Optional<String> area,
      int limit,
      Optional<Cursor> cursor) {
    public Search {
      Objects.requireNonNull(text);
      if (text.length() > 256 || text.chars().anyMatch(Character::isISOControl))
        throw new IllegalArgumentException("invalid HA search text");
      domain = optionalIdentity(domain);
      domain.ifPresent(
          value -> {
            if (!value.matches("[a-z0-9_]+"))
              throw new IllegalArgumentException("invalid HA domain filter");
          });
      area = optionalIdentity(area);
      if (limit < 1 || limit > MAX_PAGE)
        throw new IllegalArgumentException("HA page size must be 1..100");
      Objects.requireNonNull(cursor);
    }
  }

  public record Page(Revision revision, List<Description> resources, Optional<Cursor> next) {
    public Page {
      Objects.requireNonNull(revision);
      resources = List.copyOf(resources);
      if (resources.size() > MAX_PAGE)
        throw new IllegalArgumentException("HA page exceeds its bound");
      unique(resources.stream().map(Description::reference).toList());
      resources.forEach(value -> sameBinding(value.reference(), revision));
      Objects.requireNonNull(next);
      if (next.isPresent() && !next.get().revision().equals(revision))
        throw new IllegalArgumentException("HA cursor revision mismatch");
    }
  }

  public enum Availability {
    AVAILABLE,
    UNAVAILABLE,
    MISSING,
    INVALID
  }

  /**
   * Only declared primitives can be evidence. Nested vendor values are reported by selected
   * attribute name as unsupported; they never cross the boundary as generic JSON/object payloads.
   */
  public record State(
      Reference reference,
      Availability availability,
      Optional<String> value,
      Map<String, Parameter> attributes,
      Set<String> unsupportedAttributes,
      Optional<Instant> sourceUpdatedAt,
      Instant observedAt,
      boolean stale) {
    public State {
      Objects.requireNonNull(reference);
      Objects.requireNonNull(availability);
      Objects.requireNonNull(value);
      value.ifPresent(
          text -> {
            if (text.length() > 4096 || text.indexOf('\0') >= 0)
              throw new IllegalArgumentException("HA state exceeds its bound");
          });
      attributes = Map.copyOf(attributes);
      names(attributes.keySet(), MAX_ATTRIBUTES);
      unsupportedAttributes = names(unsupportedAttributes, MAX_ATTRIBUTES);
      if (!Collections.disjoint(attributes.keySet(), unsupportedAttributes))
        throw new IllegalArgumentException("HA attribute cannot be supported and unsupported");
      Objects.requireNonNull(sourceUpdatedAt);
      Objects.requireNonNull(observedAt);
      if (availability == Availability.AVAILABLE && value.isEmpty()
          || availability == Availability.MISSING && (value.isPresent() || !attributes.isEmpty()))
        throw new IllegalArgumentException("inconsistent HA state availability");
    }
  }

  public record ReadRequest(Revision revision, List<Reference> references) {
    public ReadRequest {
      Objects.requireNonNull(revision);
      references = List.copyOf(references);
      if (references.isEmpty() || references.size() > MAX_RESOURCES)
        throw new IllegalArgumentException("HA read batch must contain 1..256 resources");
      unique(references);
      references.forEach(value -> sameBinding(value, revision));
    }
  }

  public record ReadBatch(Revision revision, List<State> states) {
    public ReadBatch {
      Objects.requireNonNull(revision);
      states = List.copyOf(states);
      if (states.size() > MAX_RESOURCES)
        throw new IllegalArgumentException("HA read result exceeds its bound");
      unique(states.stream().map(State::reference).toList());
      states.forEach(value -> sameBinding(value.reference(), revision));
    }
  }

  /** Fixed configured profiles are the only initial executable selector translator. */
  public sealed interface ActionDescription permits ConfiguredAction, UnsupportedAction {
    String alias();
  }

  public record ConfiguredAction(
      String alias, Revision revision, HomeAssistantSettings.Action profile)
      implements ActionDescription {
    public ConfiguredAction {
      IntegrationContracts.identity(alias, 256);
      Objects.requireNonNull(revision);
      Objects.requireNonNull(profile);
    }
  }

  public enum UnsupportedSelector {
    NESTED_VALUE,
    DYNAMIC_TARGET,
    SERVICE_RESPONSE,
    UNREVIEWED_PROFILE
  }

  public record UnsupportedAction(String alias, UnsupportedSelector reason)
      implements ActionDescription {
    public UnsupportedAction {
      IntegrationContracts.identity(alias, 256);
      Objects.requireNonNull(reason);
    }
  }

  /**
   * Caller-owned monotonic deadline. Nesting uses the same instance, so per-resource work/retries
   * cannot receive fresh budgets. A short request should use the configured two-second default.
   * Timeout rejects late results; remote abort still belongs to the transport implementation.
   */
  public static final class OperationBudget {
    private final long deadline;

    private OperationBudget(Duration limit) {
      deadline = System.nanoTime() + checked(limit).toNanos();
    }

    private OperationBudget(long deadline) {
      this.deadline = deadline;
    }

    public static OperationBudget start(Duration limit) {
      return new OperationBudget(limit);
    }

    /**
     * Derive an earlier child limit without recreating or extending this absolute deadline. This
     * lets a provider's finite pass also honor an invocation that was admitted before that pass.
     * The caller passes the returned instance through every nested resource/transport stage.
     */
    public OperationBudget capped(Duration limit) {
      long selected = System.nanoTime() + checked(limit).toNanos();
      return deadline - selected <= 0 ? this : new OperationBudget(selected);
    }

    private static Duration checked(Duration limit) {
      Objects.requireNonNull(limit);
      if (limit.isNegative() || limit.isZero() || limit.compareTo(Duration.ofDays(1)) > 0)
        throw new IllegalArgumentException("positive finite HA operation budget required");
      return limit;
    }

    public Duration remaining() throws IOException {
      long nanos = deadline - System.nanoTime();
      if (nanos <= 0) throw new IOException("HA operation deadline expired");
      return Duration.ofNanos(nanos);
    }
  }

  static void haName(String value) {
    if (value == null || value.length() > 256 || !value.matches("[a-z0-9_]+\\.[a-z0-9_]+"))
      throw new IllegalArgumentException("invalid HA entity/service");
  }

  static Set<String> names(Set<String> values, int limit) {
    values = Set.copyOf(values);
    if (values.size() > limit) throw new IllegalArgumentException("HA selection exceeds its bound");
    values.forEach(value -> IntegrationContracts.identity(value, 256));
    return values;
  }

  private static Optional<String> optionalIdentity(Optional<String> value) {
    Objects.requireNonNull(value);
    value.ifPresent(text -> IntegrationContracts.identity(text, 256));
    return value;
  }

  private static void sameBinding(Reference reference, Revision revision) {
    if (!reference.binding().equals(revision.binding()))
      throw new IllegalArgumentException(
          "HA resource belongs to another binding credential context");
  }

  private static void unique(List<Reference> values) {
    if (Set.copyOf(values).size() != values.size())
      throw new IllegalArgumentException("duplicate HA resource reference");
  }
}
