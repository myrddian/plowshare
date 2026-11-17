package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.IntegrationContracts;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ActionDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Binding;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ConfiguredAction;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.ReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Reference;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.Revision;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.State;
import io.aeyer.plowshare.protocol.IntegrationPayload.Parameter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable configured disclosure ceiling for discovery/read results. This policy is independent of
 * Plowshare tool grants and does not authorize execution. Projection happens before search, paging
 * or counts; denied and absent references have the same empty result. Snapshot owners must
 * additionally check current epoch/metadata revisions and current grants before each effect.
 */
public final class HomeAssistantDiscoveryPolicy {
  /** Metadata fields are opt-in; existing alias bindings disclose only the configured alias. */
  public record Metadata(boolean name, boolean device, boolean area) {}

  public record Selection(String entityId, Set<String> attributes, Metadata metadata) {
    public Selection {
      HomeAssistantResources.haName(entityId);
      attributes = HomeAssistantResources.names(attributes, HomeAssistantResources.MAX_ATTRIBUTES);
      Objects.requireNonNull(metadata);
    }
  }

  private final Binding binding;
  private final String revision;
  private final Map<String, Selection> resources;
  private final Set<String> disclosedEntities;
  private final Map<String, HomeAssistantSettings.Action> actions;

  public HomeAssistantDiscoveryPolicy(
      Binding binding,
      String revision,
      Map<String, Selection> resources,
      Map<String, HomeAssistantSettings.Action> actions) {
    this.binding = Objects.requireNonNull(binding);
    this.revision = IntegrationContracts.identity(revision, 256);
    this.resources = Map.copyOf(resources);
    this.actions = Map.copyOf(actions);
    disclosedEntities =
        this.resources.values().stream()
            .map(Selection::entityId)
            .collect(Collectors.toUnmodifiableSet());
    if (this.resources.size() > HomeAssistantResources.MAX_RESOURCES
        || this.actions.size() > HomeAssistantResources.MAX_ACTIONS)
      throw new IllegalArgumentException("HA disclosure policy exceeds its bound");
    this.resources.keySet().forEach(alias -> IntegrationContracts.identity(alias, 256));
    this.actions.keySet().forEach(alias -> IntegrationContracts.identity(alias, 256));
    if (disclosedEntities.size() != this.resources.size())
      throw new IllegalArgumentException("duplicate HA entity aliases");
  }

  /** Existing aliases, selected primitives, fixed action profiles and implicit units stay valid. */
  public static HomeAssistantDiscoveryPolicy fromSettings(
      Binding binding, String revision, HomeAssistantSettings settings) {
    Map<String, Selection> selected = new LinkedHashMap<>();
    settings
        .entities()
        .forEach(
            (alias, entity) ->
                selected.put(
                    alias,
                    new Selection(
                        entity.entity(),
                        entity.selectedAttributes(),
                        new Metadata(false, false, false))));
    return new HomeAssistantDiscoveryPolicy(binding, revision, selected, settings.actions());
  }

  public Binding binding() {
    return binding;
  }

  public String revision() {
    return revision;
  }

  /** Immutable configured selection; discovery still projects each candidate before disclosure. */
  public Set<Reference> references() {
    return resources.keySet().stream()
        .map(alias -> new Reference(binding, alias))
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Exact aliases remain stable across an explicitly configured rename, not automatic retargeting.
   */
  public Optional<Selection> selection(Reference reference) {
    return binding.equals(reference.binding())
        ? Optional.ofNullable(resources.get(reference.alias()))
        : Optional.empty();
  }

  public Optional<Description> project(Description source) {
    Optional<Selection> found = selection(source.reference());
    if (found.isEmpty() || !found.get().entityId().equals(source.entityId()))
      return Optional.empty();
    Selection selected = found.get();
    Set<String> attributes =
        source.readableAttributes().stream()
            .filter(selected.attributes()::contains)
            .collect(Collectors.toUnmodifiableSet());
    return Optional.of(
        new Description(
            source.reference(),
            source.entityId(),
            selected.metadata().name() ? source.name() : source.reference().alias(),
            selected.metadata().device() ? source.device() : Optional.empty(),
            selected.metadata().area() ? source.area() : Optional.empty(),
            attributes,
            source.registries()));
  }

  /** Call before a batched transport read. No denied entity names are included in the refusal. */
  public void require(ReadRequest request) {
    requireRevision(request.revision());
    if (request.references().stream().anyMatch(reference -> selection(reference).isEmpty()))
      throw new IllegalArgumentException("HA resource selection is not available");
  }

  public State project(State source, Revision snapshot) {
    requireRevision(snapshot);
    Selection selected =
        selection(source.reference())
            .orElseThrow(
                () -> new IllegalArgumentException("HA resource selection is not available"));
    Map<String, Parameter> attributes = new LinkedHashMap<>();
    source
        .attributes()
        .forEach(
            (name, value) -> {
              if (selected.attributes().contains(name)) attributes.put(name, value);
            });
    Set<String> unsupported =
        source.unsupportedAttributes().stream()
            .filter(selected.attributes()::contains)
            .collect(Collectors.toUnmodifiableSet());
    return new State(
        source.reference(),
        source.availability(),
        source.value(),
        attributes,
        unsupported,
        source.sourceUpdatedAt(),
        source.observedAt(),
        source.stale());
  }

  /**
   * Only explicit profiles matching this selected entity and whose entire fixed target set is
   * disclosable are offered. A mixed-target profile is omitted, never narrowed: the private manual
   * execution profile retains its original effects, while discovery cannot expose hidden targets.
   */
  public List<ActionDescription> actions(Reference reference, Revision snapshot) {
    requireRevision(snapshot);
    Optional<Selection> selected = selection(reference);
    if (selected.isEmpty()) return List.of();
    return actions.entrySet().stream()
        .filter(entry -> entry.getValue().targets().contains(selected.get().entityId()))
        .filter(entry -> disclosedEntities.containsAll(entry.getValue().targets()))
        .sorted(Map.Entry.comparingByKey())
        .<ActionDescription>map(
            entry -> new ConfiguredAction(entry.getKey(), snapshot, entry.getValue()))
        .toList();
  }

  public void requireRevision(Revision snapshot) {
    if (!binding.equals(snapshot.binding()) || !revision.equals(snapshot.policy()))
      throw new IllegalArgumentException("HA snapshot policy is no longer available");
  }
}
