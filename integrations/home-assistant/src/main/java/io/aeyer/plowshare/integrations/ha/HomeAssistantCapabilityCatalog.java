package io.aeyer.plowshare.integrations.ha;

import io.aeyer.plowshare.integrations.Json;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Description;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldDescription;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReadRequest;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.FieldReading;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Query;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ReadResult;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.Resolution;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResolutionStatus;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilities.ResourceSummary;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilitySource.Observation;
import io.aeyer.plowshare.integrations.ha.HomeAssistantCapabilitySource.Snapshot;
import io.aeyer.plowshare.integrations.ha.HomeAssistantResources.OperationBudget;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Capability resolution over one admitted native collection. Schema revisions intentionally omit
 * readings and availability: ordinary state changes do not invalidate a field selection. Native
 * source collection owns identity, exposure freshness and field-shape reconciliation.
 */
public final class HomeAssistantCapabilityCatalog implements HomeAssistantResourceCapabilities {
  private final HomeAssistantCapabilitySource source;

  public HomeAssistantCapabilityCatalog(HomeAssistantCapabilitySource source) {
    this.source = Objects.requireNonNull(source);
  }

  /**
   * Opens an independently owned metadata store; the caller immediately retains this close owner.
   */
  public static HomeAssistantResourceCapabilities open(
      HomeAssistantAdapter adapter,
      String bindingName,
      HomeAssistantProviderConfiguration.Discovery configuration,
      HomeAssistantSettings settings,
      String ownerFingerprint)
      throws IOException {
    Objects.requireNonNull(adapter);
    HomeAssistantCapabilities.text(bindingName, 256);
    adapter.requireCapabilitySettings(settings);
    String namespace = Json.hash(settings.endpoint() + "\0" + bindingName);
    var store =
        new HomeAssistantCapabilityIdentityStore(
            configuration.identityJournal(), ownerFingerprint, namespace);
    try {
      return new HomeAssistantCapabilityCatalog(
          adapter.capabilitySource(configuration, settings, store));
    } catch (RuntimeException failure) {
      try {
        store.close();
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  @Override
  public Resolution resolve(Query query, OperationBudget budget) throws IOException {
    Objects.requireNonNull(query);
    var snapshot = collect(budget);
    String revision = revision(snapshot);
    if (query.revision().isPresent()) requireRevision(query.revision().orElseThrow(), revision);
    List<ResourceSummary> pairs = new ArrayList<>();
    for (Observation observation : snapshot.observations()) {
      ResourceSummary resource = observation.resource();
      String domain = resource.entityId().substring(0, resource.entityId().indexOf('.'));
      if (query.domain().isPresent() && !query.domain().orElseThrow().equals(domain)) continue;
      if (query.area().isPresent()
          && resource.area().stream()
              .noneMatch(value -> value.equalsIgnoreCase(query.area().orElseThrow()))) continue;
      for (FieldDescription field : resource.matchedFields()) {
        if (query.capability().isPresent()
            && !field.capability().equalsIgnoreCase(query.capability().orElseThrow())) continue;
        if (query.role().isPresent() && field.role() != query.role().orElseThrow()) continue;
        String searchable =
            normalize(
                resource.name()
                    + " "
                    + resource.entityId()
                    + " "
                    + String.join(" ", resource.aliases())
                    + " "
                    + resource.device().orElse("")
                    + " "
                    + resource.area().orElse("")
                    + " "
                    + field.name()
                    + " "
                    + field.id()
                    + " "
                    + field.capability()
                    + " "
                    + field.role());
        boolean matches = true;
        for (String token : normalize(query.text()).split("\\s+"))
          if (!token.isEmpty() && !searchable.contains(token)) {
            matches = false;
            break;
          }
        if (matches) pairs.add(summary(resource, List.of(field)));
      }
    }
    pairs.sort(
        Comparator.comparing(ResourceSummary::resourceId)
            .thenComparing(value -> value.matchedFields().getFirst().id()));
    if (query.offset() > pairs.size()) throw unavailable();
    int end = Math.min(pairs.size(), query.offset() + query.limit());
    var status =
        pairs.isEmpty()
            ? ResolutionStatus.NO_MATCH
            : pairs.size() == 1 ? ResolutionStatus.RESOLVED : ResolutionStatus.AMBIGUOUS;
    budget.remaining();
    return new Resolution(
        status,
        revision,
        snapshot.observedAt(),
        pairs.subList(query.offset(), end),
        end < pairs.size() ? end : null);
  }

  @Override
  public Description describe(
      String resourceId, String expected, int offset, int limit, OperationBudget budget)
      throws IOException {
    HomeAssistantCapabilities.resourceId(resourceId);
    HomeAssistantCapabilities.revision(expected);
    if (offset < 0
        || offset > HomeAssistantCapabilities.MAX_FIELDS
        || limit < 1
        || limit > HomeAssistantCapabilities.FIELD_PAGE)
      throw new IllegalArgumentException("Invalid capability field page");
    var snapshot = collect(budget);
    String revision = revision(snapshot);
    requireRevision(expected, revision);
    ResourceSummary resource = find(snapshot, resourceId).resource();
    List<FieldDescription> fields = resource.matchedFields();
    if (offset > fields.size()) throw unavailable();
    int end = Math.min(fields.size(), offset + limit);
    budget.remaining();
    return new Description(
        revision,
        snapshot.observedAt(),
        summary(resource, List.of()),
        fields.subList(offset, end),
        end < fields.size() ? end : null);
  }

  @Override
  public ReadResult read(FieldReadRequest request, OperationBudget budget) throws IOException {
    Objects.requireNonNull(request);
    var snapshot = collect(budget);
    String revision = revision(snapshot);
    requireRevision(request.revision(), revision);
    List<FieldReading> result = new ArrayList<>();
    // Resolve the complete selection before returning any values, so a hidden field cannot produce
    // a partially successful response that doubles as an existence oracle.
    for (var selected : request.selections()) {
      Observation observation = find(snapshot, selected.resourceId());
      Map<String, FieldReading> fields = new LinkedHashMap<>();
      observation.readings().forEach(value -> fields.put(value.field().id(), value));
      for (String field : selected.fields()) {
        FieldReading reading = fields.get(field);
        if (reading == null) throw unavailable();
        result.add(reading);
      }
    }
    budget.remaining();
    return new ReadResult(revision, snapshot.observedAt(), result);
  }

  private Snapshot collect(OperationBudget budget) throws IOException {
    Objects.requireNonNull(budget).remaining();
    Snapshot snapshot = source.snapshot(budget);
    budget.remaining();
    return snapshot;
  }

  private static Observation find(Snapshot snapshot, String resourceId) throws IOException {
    return snapshot.observations().stream()
        .filter(value -> value.resource().resourceId().equals(resourceId))
        .findFirst()
        .orElseThrow(HomeAssistantCapabilityCatalog::unavailable);
  }

  private record Shape(
      String resourceId,
      String entityId,
      String name,
      List<String> aliases,
      String device,
      String area,
      HomeAssistantCapabilities.IdentityKind identity,
      List<FieldDescription> fields) {}

  private static String revision(Snapshot snapshot) {
    var shapes =
        snapshot.observations().stream()
            .map(Observation::resource)
            .sorted(Comparator.comparing(ResourceSummary::resourceId))
            .map(
                value ->
                    new Shape(
                        value.resourceId(),
                        value.entityId(),
                        value.name(),
                        value.aliases(),
                        value.device().orElse(null),
                        value.area().orElse(null),
                        value.identity(),
                        value.matchedFields()))
            .toList();
    return Json.hash(
        snapshot.epoch()
            + "\0"
            + snapshot.policyFingerprint()
            + "\0"
            + Json.hash(Json.MAPPER.valueToTree(shapes)));
  }

  private static ResourceSummary summary(ResourceSummary value, List<FieldDescription> fields) {
    return new ResourceSummary(
        value.resourceId(),
        value.entityId(),
        value.name(),
        value.aliases(),
        value.device(),
        value.area(),
        value.availability(),
        value.identity(),
        fields);
  }

  private static String normalize(String text) {
    return text.toLowerCase(Locale.ROOT).replace('_', ' ').replace('.', ' ');
  }

  private static void requireRevision(String expected, String actual) throws IOException {
    if (!expected.equals(actual))
      throw new HomeAssistantResourceCapabilities.SchemaChangedException();
  }

  private static IOException unavailable() {
    return new IOException("Capability resource or field is unavailable under this policy");
  }

  @Override
  public void close() throws IOException {
    source.close();
  }
}
